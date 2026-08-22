// ply_optimize.cpp — PLY optimization using GaussSimplify
// Reads a binary PLY file, converts to GaussianCloudIR,
// calls gs::simplify, and writes the optimized result back.

#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>
#include <cstdint>
#include <thread>
#include <algorithm>
#include <atomic>

#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

#include "gs/simplify.h"
#include "gf/core/gauss_ir.h"
#include "gf/core/metadata.h"
#include "../include/sharp_pipeline.h"

#define TAG "PlyOptimize"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct PlyVertex {
    float x, y, z;
    float f_dc_0, f_dc_1, f_dc_2;
    float opacity;
    float scale_0, scale_1, scale_2;
    float rot_0, rot_1, rot_2, rot_3;
};

static constexpr size_t kPlyVertexSize = sizeof(PlyVertex);
static constexpr size_t kWriteBatchSize = 524288;  // 512KB
static constexpr size_t kWriteBatchVertices = kWriteBatchSize / kPlyVertexSize;

// Parsed PLY header + cached metadata (avoids re-reading the file on write)
struct PlyHeader {
    std::string text;               // full header text including "end_header\n"
    long data_offset = 0;           // byte offset of vertex data
    int vertex_count = 0;
    std::vector<uint8_t> metadata;  // bytes after vertex data (extrinsic, intrinsic, etc.)
};

// RAII wrapper for mmap
struct MmapFile {
    void* addr = MAP_FAILED;
    size_t size = 0;

    ~MmapFile() { close(); }
    void close() {
        if (addr != MAP_FAILED) { munmap(addr, size); addr = MAP_FAILED; }
        size = 0;
    }
    [[nodiscard]] bool valid() const { return addr != MAP_FAILED; }
};

// Open + mmap a file for reading. Returns the mapped pointer and size.
bool open_mmap(const char* path, MmapFile& out) {
    int fd = ::open(path, O_RDONLY);
    if (fd < 0) {
        LOGE("Cannot open for mmap: %s", path);
        return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGE("fstat failed or empty file: %s", path);
        ::close(fd);
        return false;
    }
    out.size = static_cast<size_t>(st.st_size);
    out.addr = mmap(nullptr, out.size, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);
    if (out.addr == MAP_FAILED) {
        LOGE("mmap failed: %s (size=%zu)", path, out.size);
        return false;
    }
    // Advise sequential read
    madvise(out.addr, out.size, MADV_SEQUENTIAL);
    return true;
}

// Parse header from mmap'd memory in one pass.
// Extracts vertex count, data offset, and caches metadata.
bool parse_header(const uint8_t* data, size_t size, PlyHeader& header) {
    std::string_view view(reinterpret_cast<const char*>(data), size);

    auto end_hdr = view.find("end_header\n");
    if (end_hdr == std::string_view::npos) {
        LOGE("Cannot find end_header in PLY data");
        return false;
    }
    header.data_offset = static_cast<long>(end_hdr) + 11;  // "end_header\n" = 11 chars
    header.text = std::string(view.substr(0, header.data_offset));

    auto elem_pos = view.find("element vertex ");
    if (elem_pos == std::string_view::npos) {
        LOGE("Cannot find element vertex in header");
        return false;
    }
    auto num_start = elem_pos + 15;  // "element vertex " = 15 chars
    auto num_end = view.find('\n', num_start);
    if (num_end == std::string_view::npos) {
        LOGE("Malformed element vertex line");
        return false;
    }
    std::string num_str(view.substr(num_start, num_end - num_start));
    header.vertex_count = std::stoi(num_str);
    if (header.vertex_count <= 0) {
        LOGE("Invalid vertex count: %d", header.vertex_count);
        return false;
    }

    // Cache metadata (bytes after vertex data)
    size_t meta_start = header.data_offset + static_cast<size_t>(header.vertex_count) * kPlyVertexSize;
    if (meta_start < size) {
        size_t meta_size = size - meta_start;
        header.metadata.assign(data + meta_start, data + size);
        LOGI("Cached %zu bytes metadata", meta_size);
    }

    return true;
}

// Multi-threaded vertex parsing: split N vertices across hw threads.
// Each thread writes directly into pre-allocated GaussianCloudIR ranges.
void parse_vertices_range(const PlyVertex* src, size_t start, size_t end,
                          gf::GaussianCloudIR& ir) {
    for (size_t i = start; i < end; ++i) {
        const auto& v = src[i];
        const size_t i3 = i * 3;
        const size_t i4 = i * 4;

        ir.positions[i3 + 0] = v.x;
        ir.positions[i3 + 1] = v.y;
        ir.positions[i3 + 2] = v.z;

        ir.colors[i3 + 0] = v.f_dc_0;
        ir.colors[i3 + 1] = v.f_dc_1;
        ir.colors[i3 + 2] = v.f_dc_2;

        ir.alphas[i] = v.opacity;

        ir.scales[i3 + 0] = v.scale_0;
        ir.scales[i3 + 1] = v.scale_1;
        ir.scales[i3 + 2] = v.scale_2;

        ir.rotations[i4 + 0] = v.rot_0;
        ir.rotations[i4 + 1] = v.rot_1;
        ir.rotations[i4 + 2] = v.rot_2;
        ir.rotations[i4 + 3] = v.rot_3;
    }
}

// Parse PLY vertex data into GaussianCloudIR using mmap + multi-threading.
bool parse_ply_to_ir(const char* ply_path, gf::GaussianCloudIR& ir, PlyHeader& header) {
    MmapFile mf;
    if (!open_mmap(ply_path, mf)) return false;

    const auto* data = static_cast<const uint8_t*>(mf.addr);
    if (!parse_header(data, mf.size, header)) return false;

    int N = header.vertex_count;
    LOGI("PLY: %d vertices, data offset %ld", N, header.data_offset);

    ir.numPoints = N;
    ir.positions.resize(static_cast<size_t>(N) * 3);
    ir.scales.resize(static_cast<size_t>(N) * 3);
    ir.rotations.resize(static_cast<size_t>(N) * 4);
    ir.alphas.resize(static_cast<size_t>(N));
    ir.colors.resize(static_cast<size_t>(N) * 3);

    const auto* vertices = reinterpret_cast<const PlyVertex*>(data + header.data_offset);

    // Multi-threaded parse
    unsigned int num_threads = std::min<unsigned int>(
        std::thread::hardware_concurrency(), 8);
    if (num_threads < 2 || N < 100000) {
        // Small files: single-threaded
        parse_vertices_range(vertices, 0, static_cast<size_t>(N), ir);
    } else {
        std::vector<std::thread> threads;
        threads.reserve(num_threads);
        size_t chunk = static_cast<size_t>(N) / num_threads;
        for (unsigned int t = 0; t < num_threads; ++t) {
            size_t start = t * chunk;
            size_t end = (t == num_threads - 1) ? static_cast<size_t>(N) : start + chunk;
            threads.emplace_back(parse_vertices_range, vertices, start, end, std::ref(ir));
        }
        for (auto& th : threads) th.join();
    }

    ir.meta.color = gf::ColorSpace::kSRGB;
    ir.meta.shDegree = 0;

    LOGI("PLY parsed: %d vertices (threads=%u)", N, num_threads);
    return true;
}

// Write GaussianCloudIR back to the PLY file using cached header + metadata.
// No re-reading of the original file is needed.
bool write_ir_to_ply(const char* ply_path, const gf::GaussianCloudIR& ir,
                     const PlyHeader& header) {
    FILE* fp_out = fopen(ply_path, "wb");
    if (!fp_out) {
        LOGE("Cannot open PLY for writing: %s", ply_path);
        return false;
    }
    setvbuf(fp_out, nullptr, _IOFBF, 512 * 1024);  // 512KB write buffer

    // Update vertex count in header
    std::string new_header = header.text;
    auto pos = new_header.find("element vertex ");
    if (pos != std::string::npos) {
        auto end_pos = new_header.find('\n', pos);
        if (end_pos != std::string::npos) {
            std::string new_line = "element vertex " + std::to_string(ir.numPoints);
            new_header.replace(pos, end_pos - pos, new_line);
        }
    }
    fprintf(fp_out, "%s", new_header.c_str());

    // Write vertex data (512KB batches)
    const int N = ir.numPoints;
    std::vector<PlyVertex> batch(kWriteBatchVertices);
    size_t written = 0;
    while (written < static_cast<size_t>(N)) {
        size_t to_write = static_cast<size_t>(N) - written;
        if (to_write > kWriteBatchVertices) to_write = kWriteBatchVertices;
        for (size_t i = 0; i < to_write; ++i) {
            const size_t idx = written + i;
            const size_t i3 = idx * 3;
            const size_t i4 = idx * 4;
            auto& v = batch[i];
            v.x = ir.positions[i3 + 0];
            v.y = ir.positions[i3 + 1];
            v.z = ir.positions[i3 + 2];
            v.f_dc_0 = ir.colors[i3 + 0];
            v.f_dc_1 = ir.colors[i3 + 1];
            v.f_dc_2 = ir.colors[i3 + 2];
            v.opacity = ir.alphas[idx];
            v.scale_0 = ir.scales[i3 + 0];
            v.scale_1 = ir.scales[i3 + 1];
            v.scale_2 = ir.scales[i3 + 2];
            v.rot_0 = ir.rotations[i4 + 0];
            v.rot_1 = ir.rotations[i4 + 1];
            v.rot_2 = ir.rotations[i4 + 2];
            v.rot_3 = ir.rotations[i4 + 3];
        }
        fwrite(batch.data(), kPlyVertexSize, to_write, fp_out);
        written += to_write;
    }

    // Write cached metadata (no re-read needed)
    if (!header.metadata.empty()) {
        fwrite(header.metadata.data(), 1, header.metadata.size(), fp_out);
    }
    fclose(fp_out);

    LOGI("PLY written: %d vertices", N);
    return true;
}

} // namespace

// JNI: optimize a PLY file using GaussSimplify.
extern "C" jboolean
Java_com_sharp_qnn_pipeline_QnnJni_nativeOptimizePly(
    JNIEnv* env, jobject /*thiz*/,
    jstring jPlyPath,
    jint jMergeK,
    jdouble jMergeRatio,
    jdouble jMergeCap,
    jdouble jPruneThreshold,
    jint jSorNeighbors,
    jdouble jSorStdRatio) {

    const char* ply_path = env->GetStringUTFChars(jPlyPath, nullptr);
    if (!ply_path) return JNI_FALSE;

    LOGI("Optimizing PLY: %s (k=%d, ratio=%.2f, cap=%.2f, prune=%.2f, sor_nb=%d, sor_std=%.2f)",
         ply_path, jMergeK, jMergeRatio, jMergeCap, jPruneThreshold, jSorNeighbors, jSorStdRatio);

    // Parse PLY with mmap + multi-threading; cache header + metadata for write
    PlyHeader header;
    gf::GaussianCloudIR ir;
    if (!parse_ply_to_ir(ply_path, ir, header)) {
        LOGE("Failed to parse PLY: %s", ply_path);
        env->ReleaseStringUTFChars(jPlyPath, ply_path);
        return JNI_FALSE;
    }

    int original_count = ir.numPoints;
    LOGI("Input: %d points", original_count);

    gs::SimplifyOptions options;
    options.knn_k = static_cast<int>(jMergeK);
    options.ratio = static_cast<double>(jMergeRatio);
    options.merge_cap = static_cast<double>(jMergeCap);
    options.opacity_prune_threshold = static_cast<float>(jPruneThreshold);
    options.sor_nb_neighbors = static_cast<int>(jSorNeighbors);
    options.sor_std_ratio = static_cast<float>(jSorStdRatio);
    options.target_sh_degree = -1;

    // Sub-step counter: track stage transitions inside gs::simplify and report to Java
    static const char* kSubStepKeys[] = {
        "stage_ply_prune",  // Prune
        "stage_ply_sor",    // SOR
        "stage_ply_knn"     // kNN Merge
    };
    static const int kSubStepCount = sizeof(kSubStepKeys) / sizeof(kSubStepKeys[0]);
    int subStep = 0;
    std::string lastStage;
    gs::ProgressCallback progress = [&](float p, const std::string& stage) -> bool {
        LOGI("Simplify: %.1f%% - %s", p * 100.0f, stage.c_str());
        if (stage != lastStage && subStep < kSubStepCount) {
            lastStage = stage;
            cbProgress(SHARP_STAGE_PLY_OPTIMIZE, subStep, 0, 0, kSubStepKeys[subStep]);
            subStep++;
        }
        return true;
    };

    auto result = gs::simplify(ir, options, progress);
    if (!result) {
        LOGE("Simplify failed: %s", result.error().message.c_str());
        env->ReleaseStringUTFChars(jPlyPath, ply_path);
        return JNI_FALSE;
    }

    auto& optimized = result.value();
    LOGI("Optimized: %d -> %d points (%.1f%% reduction)",
         original_count, optimized.numPoints,
         (1.0 - static_cast<double>(optimized.numPoints) / static_cast<double>(original_count)) * 100.0);

    // Write with cached header + metadata (no re-read)
    if (!write_ir_to_ply(ply_path, optimized, header)) {
        LOGE("Failed to write optimized PLY: %s", ply_path);
        env->ReleaseStringUTFChars(jPlyPath, ply_path);
        return JNI_FALSE;
    }

    LOGI("PLY optimization complete: %s", ply_path);
    env->ReleaseStringUTFChars(jPlyPath, ply_path);
    return JNI_TRUE;
}
