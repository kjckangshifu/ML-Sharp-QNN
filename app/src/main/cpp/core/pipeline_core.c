/**
 * pipeline_core.c — SHARP core 库化实现 (从 pipeline.c 移植, Android NDK)
 * pipeline_core.c — SHARP core library implementation (ported from pipeline.c, Android NDK)
 *
 *   sharp_pre        : image.raw -> 35 patches (NCHW) + x2.raw + input_list_*.txt
 *   sharp_merge      : 设备输出 (pe_out + ie_out) -> 6 个特征 raw (NCHW, 供 rest.onnx)
 *                      device outputs (pe_out + ie_out) -> 6 feature raws (NCHW, for rest.onnx)
 *   sharp_post       : image.raw + delta.raw + disparity.raw -> output.ply
 *   焦距 -> resize 1536 -> image.raw
 *
 * 从 pipeline.c 的 main() 提取, 暴露为可调用函数供 JNI 层使用。
 * Extracted from pipeline.c's main(); exposed as callable functions for the JNI layer.
 * 去掉命令行参数解析, 去掉 main()。
 * No command-line parsing, no main().
 *
 * 布局约定:
 * Layout conventions:
 *   - 组件间: NCHW float32
 *   - between components: NCHW float32
 *   - 设备输入 .raw: NCHW float32 (DLC 输入 tensor 布局, 与 ONNX 一致)
 *   - device input .raw: NCHW float32 (DLC input tensor layout, matching ONNX)
 *   - 设备输出 .raw: NCHW float32
 *   - device output .raw: NCHW float32
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifdef __ANDROID__
#include <android/log.h>
#define CORE_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SharpCore", __VA_ARGS__)
#else
#define CORE_LOGE(...) fprintf(stderr, "[SharpCore] " __VA_ARGS__)
#endif

#include "sharp_pipeline.h"    /* library interface definitions */

#include "normalizer.h"
#include "pyramid.h"
#include "split_patches.h"
#include "merge_patches.h"
#include "depth_from_disparity.h"
#include "initializer.h"
#include "composer.h"
#include "unproject.h"
#include "save_ply.h"

/* sharp_prep_image 需要 stb_image 解码 + exif 解析
 * sharp_prep_image needs stb_image decoding + exif parsing
 * (STB_IMAGE_IMPLEMENTATION 定义在 prep_input.cpp, 此处仅引入声明)
 * (STB_IMAGE_IMPLEMENTATION is defined in prep_input.cpp; only the declarations are included here) */
#include "stb_image.h"
#include "exif.h"

#define IMG 1536
#define PATCH 384
#define OUT_SIZE 1536

/* ─────────────── 进度回调 ─────────────── */
/* ─────────────── Progress callback ─────────────── */
static SharpProgressCallback g_progress_cb = NULL;

void sharp_set_progress_callback(SharpProgressCallback cb)
{
    g_progress_cb = cb;
}

static void sharp_progress(int stageId, const char *stageName,
                           int current, int total, const char *detail)
{
    if (g_progress_cb)
        g_progress_cb(stageId, stageName, current, total, 0, detail);
}

/* ─────────────── 工具函数 ───────────────
 * ─────────────── Utilities ───────────────
 * NULL, 由调用方清理已分配资源并向上返回。
 * On failure always return an error code / NULL so the caller can clean up
 * allocated resources and propagate. */
static float *read_raw(const char *path, size_t n)
{
    FILE *f = fopen(path, "rb");
    if (!f) { CORE_LOGE("ERROR: cannot open %s\n", path); return NULL; }
    float *buf = (float *)malloc(n * sizeof(float));
    if (!buf) {
        fclose(f);
        CORE_LOGE("ERROR: out of memory reading %s (%zu floats)\n", path, n);
        return NULL;
    }
    size_t got = fread(buf, sizeof(float), n, f);
    fclose(f);
    if (got != n) {
        CORE_LOGE("ERROR: %s short read: got %zu want %zu\n", path, got, n);
        free(buf);
        return NULL;
    }
    return buf;
}

/* 返回 0 成功, 非 0 失败 */
/* Returns 0 on success, non-zero on failure */
static int write_raw(const char *path, const float *buf, size_t n)
{
    FILE *f = fopen(path, "wb");
    if (!f) { CORE_LOGE("ERROR: cannot write %s\n", path); return -1; }
    size_t written = fwrite(buf, sizeof(float), n, f);
    fclose(f);
    if (written != n) {
        CORE_LOGE("ERROR: %s short write: got %zu want %zu\n", path, written, n);
        return -1;
    }
    return 0;
}

/* ─────────────── prep_image 辅助函数 (从 prep_input.cpp 移植) ─────────────── */
/* ─────────────── prep_image helpers (ported from prep_input.cpp) ─────────────── */
static double compute_fpx(int w, int h, double f_mm)
{
    return f_mm * sqrt((double)w * w + (double)h * h) / sqrt(36.0 * 36.0 + 24.0 * 24.0);
}

static double focal_length_from_exif(const ExifData *ex)
{
    double f = ex->focal_len_35mm;
    if (f < 1.0) {
        f = ex->focal_len_mm;
        if (f <= 0.0)
            f = 30.0;
        if (f < 10.0)
            f *= 8.4;
    }
    return f;
}

/* 按 EXIF Orientation 旋转 (PIL transpose 语义), HWC uint8 像素
 * Rotates by EXIF Orientation (PIL transpose semantics), HWC uint8 pixels.
 * 算术, 与 float 域旋转逐位等价):
 * Rotation happens in the uint8 domain (rotation = element reorder, no
 * interpolation/arithmetic, bit-equivalent to rotating in float):
 * 48MP 时峰值从 src+out 两份 float (2*576MB=1.15GB) 降到两份 uint8 (2*144MB=288MB),
 * At 48MP the peak drops from two float buffers (2*576MB=1.15GB) to two uint8
 * buffers (2*144MB=288MB),
 * 转 float 阶段为不可避免的单份 576MB+144MB=720MB。
 * and the float conversion stage inevitably peaks at 576MB+144MB=720MB. */
static void rotate_u8_by_orientation(unsigned char **pixels, int *w, int *h, int orientation)
{
    int W = *w, H = *h;
    unsigned char *out = NULL;
    int swap = 0;

    if (orientation == 3) {
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                memcpy(out + ((size_t)(H - 1 - y) * W + (W - 1 - x)) * 3,
                       (*pixels) + ((size_t)y * W + x) * 3, 3);
    } else if (orientation == 6) { /* PIL ROTATE_270: 逆时针 90 度 / 90 degrees counter-clockwise */
        swap = 1;
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < W; y++)
            for (int x = 0; x < H; x++)
                memcpy(out + ((size_t)y * H + x) * 3,
                       (*pixels) + ((size_t)(H - 1 - x) * W + y) * 3, 3);
    } else if (orientation == 8) { /* PIL ROTATE_90: 顺时针 90 度 / 90 degrees clockwise */
        swap = 1;
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < W; y++)
            for (int x = 0; x < H; x++)
                memcpy(out + ((size_t)y * H + x) * 3,
                       (*pixels) + ((size_t)x * W + (W - 1 - y)) * 3, 3);
    } else {
        return;
    }

    free(*pixels);
    *pixels = out;
    if (swap) {
        *w = H;
        *h = W;
    }
}

/* torch F.interpolate bilinear align_corners=True, CPU float32 语义 */
/* torch F.interpolate bilinear align_corners=True, CPU float32 semantics */
static void resize_bilinear(const float *src, int src_w, int src_h, int ch,
                            float *dst)
{
    const float rh = (float)(src_h - 1) / (float)(OUT_SIZE - 1);
    const float rw = (float)(src_w - 1) / (float)(OUT_SIZE - 1);

    for (int oy = 0; oy < OUT_SIZE; oy++) {
        const float h1r = oy * rh;
        const int   h1  = (int)h1r;                        /* truncation (floor for positives) */
        const int   h1p = (h1 < src_h - 1) ? 1 : 0;
        const float h1l = h1r - h1;
        const float h0l = 1.0f - h1l;

        for (int ox = 0; ox < OUT_SIZE; ox++) {
            const float w1r = ox * rw;
            const int   w1  = (int)w1r;
            const int   w1p = (w1 < src_w - 1) ? 1 : 0;
            const float w1l = w1r - w1;
            const float w0l = 1.0f - w1l;

            for (int c = 0; c < ch; c++) {
                const float *p00 = src + (size_t)(h1 * src_w + w1) * ch + c;
                const float *p10 = p00 + (size_t)h1p * src_w * ch;
                const float *p01 = p00 + w1p * ch;
                const float *p11 = p10 + w1p * ch;
                const float  val = h0l * (w0l * p00[0] + w1l * p01[0])
                                 + h1l * (w0l * p10[0] + w1l * p11[0]);
                dst[((size_t)oy * OUT_SIZE + ox) * ch + c] = val;
            }
        }
    }
}

/* prep 链路重采样 (固定 1536x1536, HWC float32)
 * Prep-chain resampling (fixed 1536x1536, HWC float32)
 * 恒走 CPU bilinear, align_corners=True — 与训练参考 src 完全一致
 * Always CPU bilinear with align_corners=True — identical to the training reference
 * (predict.py: F.interpolate bilinear align_corners=True, 见 resize_bilinear)
 * (predict.py: F.interpolate bilinear align_corners=True, see resize_bilinear)
 * 返回 0 成功, 非 0 失败
 * Returns 0 on success, non-zero on failure */
static int resize_prep(const float *src, int src_w, int src_h, int ch,
                       float *dst)
{
    resize_bilinear(src, src_w, src_h, ch, dst);
    return 0;
}

/* ─────────────── sharp_pre ─────────────── */
/* 预处理: image.raw -> 35 x patch_p*.raw + x2.raw + input_list_*.txt
 * Preprocess: image.raw -> 35 x patch_p*.raw + x2.raw + input_list_*.txt
 * imageRawPath: 输入 image.raw [1,3,1536,1536] NCHW f32
 *               input image.raw [1,3,1536,1536] NCHW f32
 * workDir: 工作目录 (输出文件放在此目录)
 *          work directory (outputs are written here)
 * 返回 0 成功, 非 0 失败
 * Returns 0 on success, non-zero on failure
 */
int sharp_pre(const char *imageRawPath, const char *workDir)
{
    char path[512];
    sharp_progress(SHARP_STAGE_PRE, "pre", 0, 5, "start");

    float *image = read_raw(imageRawPath, 3 * IMG * IMG);
    if (!image) return -1;

    /* normalizer [0,1] -> [-1,1] */
    float *nrm = (float *)malloc(3 * IMG * IMG * sizeof(float));
    if (!nrm) { free(image); return -1; }
    normalizer_apply(image, nrm, 1, 3, IMG, IMG, 2.0f, -1.0f);
    sharp_progress(SHARP_STAGE_PRE, "pre", 1, 5, "normalizer");

    /* pyramid */
    float *x0 = (float *)malloc(3 * IMG * IMG * sizeof(float));
    float *x1 = (float *)malloc(3 * 768 * 768 * sizeof(float));
    float *x2 = (float *)malloc(3 * 384 * 384 * sizeof(float));
    if (!x0 || !x1 || !x2) {
        CORE_LOGE("ERROR: out of memory in sharp_pre\n");
        free(image); free(nrm); free(x0); free(x1); free(x2);
        return -1;
    }
    int pr = pyramid_create(nrm, x0, x1, x2, 1, 3, IMG, IMG);
    if (pr != 0) {
        CORE_LOGE("ERROR: pyramid_create failed: %d\n", pr);
        free(image); free(nrm); free(x0); free(x1); free(x2);
        return pr;
    }
    sharp_progress(SHARP_STAGE_PRE, "pre", 2, 5, "pyramid");

    /* split -> 35 patches [35,3,384,384] NCHW */
    float *patches = (float *)malloc(35 * 3 * PATCH * PATCH * sizeof(float));
    if (!patches) {
        CORE_LOGE("ERROR: out of memory in sharp_pre (patches)\n");
        free(image); free(nrm); free(x0); free(x1); free(x2);
        return -1;
    }
    split_pyramid_batch(x0, x1, x2, patches, 1, 3,
                        IMG, IMG, 768, 768, 384, 384, PATCH);
    sharp_progress(SHARP_STAGE_PRE, "pre", 3, 5, "split");

    /* 写 35 个 patch (NCHW, 与 DLC 输入布局一致) + x2 */
    /* Write 35 patches (NCHW, matching the DLC input layout) + x2 */
    const int PX = 3 * PATCH * PATCH;
    for (int p = 0; p < 35; p++) {
        const float *src = patches + p * PX;
        snprintf(path, sizeof(path), "%s/patch_p%04d.raw", workDir, p);
        if (write_raw(path, src, PX) != 0) {
            free(image); free(nrm); free(x0); free(x1); free(x2); free(patches);
            return -1;
        }
    }
    /* x2.raw = patch 34 */
    snprintf(path, sizeof(path), "%s/x2.raw", workDir);
    if (write_raw(path, patches + 34 * PX, PX) != 0) {
        free(image); free(nrm); free(x0); free(x1); free(x2); free(patches);
        return -1;
    }

    /* input_list_pe.txt: 35 行 */
    /* input_list_pe.txt: 35 lines */
    snprintf(path, sizeof(path), "%s/input_list_pe.txt", workDir);
    FILE *f = fopen(path, "w");
    if (!f) {
        CORE_LOGE("ERROR: cannot write %s\n", path);
        free(image); free(nrm); free(x0); free(x1); free(x2); free(patches);
        return -1;
    }
    for (int p = 0; p < 35; p++)
        fprintf(f, "patch_p%04d.raw\n", p);
    fclose(f);

    /* input_list_ie.txt: 1 行 */
    /* input_list_ie.txt: 1 line */
    snprintf(path, sizeof(path), "%s/input_list_ie.txt", workDir);
    f = fopen(path, "w");
    if (!f) {
        CORE_LOGE("ERROR: cannot write %s\n", path);
        free(image); free(nrm); free(x0); free(x1); free(x2); free(patches);
        return -1;
    }
    fprintf(f, "x2.raw\n");
    fclose(f);

    sharp_progress(SHARP_STAGE_PRE, "pre", 5, 5, "done");
    printf("pre done: 35 patches + x2 + input_lists in %s\n", workDir);
    free(image); free(nrm); free(x0); free(x1); free(x2); free(patches);
    return 0;
}

/* ─────────────── sharp_merge ─────────────── */
static int merge_one(const char *io_dir, const char *out_dir, int n_patches,
                     int start_idx, int steps, int padding,
                     const char *tensor, const char *out_name,
                     int C, int ph, int pw, int H_out, int W_out)
{
    char path[512];
    /* 读入全部 patches [n, C, ph, pw] */
    /* Read all patches [n, C, ph, pw] */
    float *all = (float *)malloc((size_t)n_patches * C * ph * pw * sizeof(float));
    if (!all) return -1;
    for (int p = 0; p < n_patches; p++) {
        /* qnn-net-run output: <io_dir>/Result_N/<tensor>.raw */
        snprintf(path, sizeof(path), "%s/Result_%d/%s.raw", io_dir, start_idx + p, tensor);
        FILE *f = fopen(path, "rb");
        if (!f) {
            CORE_LOGE("ERROR: cannot open %s\n", path);
            free(all);
            return -1;
        }
        size_t got = fread(all + (size_t)p * C * ph * pw, sizeof(float),
                           (size_t)C * ph * pw, f);
        fclose(f);
        if (got != (size_t)C * ph * pw) {
            CORE_LOGE("ERROR: short read %s (%zu)\n", path, got);
            free(all);
            return -1;
        }
    }
    float *dst = (float *)malloc((size_t)C * H_out * W_out * sizeof(float));
    if (!dst) { free(all); return -1; }
    merge_grid(all, dst, 1, C, steps, ph, pw, padding, H_out, W_out);
    snprintf(path, sizeof(path), "%s/%s", out_dir, out_name);
    if (write_raw(path, dst, (size_t)C * H_out * W_out) != 0) {
        free(all); free(dst);
        return -1;
    }
    printf("  %s -> %s [%d,%d,%d]\n", tensor, out_name, C, H_out, W_out);
    free(all); free(dst);
    return 0;
}

/* Merge: HTP 输出 -> 6 个合并特征 raw
 * Merge: HTP outputs -> 6 merged feature raws
 * work directory
 * )
 *           patch_encoder output directory (contains Result_0..34/)
 * )
 *           image_encoder output directory (contains Result_0/)
 * 返回 0 成功, 非 0 失败
 * Returns 0 on success, non-zero on failure
 */
int sharp_merge(const char *workDir, const char *peOutDir, const char *ieOutDir)
{
    sharp_progress(SHARP_STAGE_MERGE, "merge", 0, 6, "patch_features x0");
    printf("merging patch_features...\n");
    if (merge_one(peOutDir, workDir, 25, 0, 5, 3, "patch_features", "x0_feat.raw", 1024, 24, 24, 96, 96) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 1, 6, "patch_features x1");
    if (merge_one(peOutDir, workDir, 9, 25, 3, 6, "patch_features", "x1_feat.raw", 1024, 24, 24, 48, 48) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 2, 6, "patch_features x2");
    if (merge_one(peOutDir, workDir, 1, 34, 1, 0, "patch_features", "x2_feat.raw", 1024, 24, 24, 24, 24) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 3, 6, "latent0");
    printf("merging latent0...\n");
    if (merge_one(peOutDir, workDir, 25, 0, 5, 3, "latent0", "x_latent0.raw", 1024, 24, 24, 96, 96) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 4, 6, "latent1");
    printf("merging latent1...\n");
    if (merge_one(peOutDir, workDir, 25, 0, 5, 3, "latent1", "x_latent1.raw", 1024, 24, 24, 96, 96) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 5, 6, "image_features");
    printf("merging image_encoder output...\n");
    if (merge_one(ieOutDir, workDir, 1, 0, 1, 0, "image_features", "x_lowres_feat.raw", 1024, 24, 24, 24, 24) != 0) return -1;
    sharp_progress(SHARP_STAGE_MERGE, "merge", 6, 6, "done");
    printf("merge done\n");
    return 0;
}

/* ─────────────── sharp_post ─────────────── */
/* Post: delta + disparity + image -> output.ply
 * workDir: 工作目录 (含 image.raw, disparity.raw, delta.raw)
 *          work directory (contains image.raw, disparity.raw, delta.raw)
 * focal length in pixels
 * original image width and height
 * output PLY file path
 * 返回 0 成功, 非 0 失败
 * Returns 0 on success, non-zero on failure
 */
int sharp_post(const char *workDir, float fpx, int origW, int origH, const char *outPlyPath)
{
    char path[512];
    sharp_progress(SHARP_STAGE_POST, "post", 0, 5, "read inputs");

    snprintf(path, sizeof(path), "%s/image.raw", workDir);
    float *image = read_raw(path, 3 * IMG * IMG);
    if (!image) return -1;
    snprintf(path, sizeof(path), "%s/disparity.raw", workDir);
    float *disparity = read_raw(path, 2 * IMG * IMG);
    if (!disparity) { free(image); return -1; }
    snprintf(path, sizeof(path), "%s/delta.raw", workDir);
    const int DNP = 14 * 2 * 768 * 768;
    float *delta = read_raw(path, DNP);
    if (!delta) { free(image); free(disparity); return -1; }

    /* Size validation: fail if origW/origH is invalid (subsequent fpx/origW, fx=IMG/origW require positive dimensions) */
    if (origW <= 0 || origH <= 0) {
        CORE_LOGE("ERROR: invalid orig size %dx%d\n", origW, origH);
        free(image); free(disparity); free(delta);
        return -1;
    }

    const float d_factor = fpx / origW;

    /* 1. depth_from_disparity */
    float *depth = (float *)malloc(2 * IMG * IMG * sizeof(float));
    depth_from_disparity(disparity, depth, 2, IMG, IMG, d_factor, 1e-4f, 1e4f);
    sharp_progress(SHARP_STAGE_POST, "post", 1, 5, "depth");

    /* 2. initializer */
    InitializerOutput init = initializer_run(
        image, depth, IMG, IMG,
        /*stride*/2, /*num_layers*/2, /*base_depth*/10.0f, /*scale_factor*/1.0f,
        /*init_disparity_factor*/1.0f, /*color_option*/2, /*normalize_depth*/1);
    printf("global_scale = %f\n", init.global_scale);
    /* feature_input (~47MB) 在本流水线无任何消费者:
     * feature_input (~47MB) has no consumer in this pipeline:
     * 立即释放降低 post 阶段峰值; initializer_free_output 对 NULL free 安全
     * release it immediately to lower the post-stage peak; initializer_free_output
     * is NULL-safe for free */
    free(init.feature_input);
    init.feature_input = NULL;

    if (getenv("POST_DUMP")) {
        char p2[512];
        const int NP = 2 * 768 * 768;
        snprintf(p2, sizeof(p2), "%s/base_depth.raw", workDir);
        write_raw(p2, depth, 2 * IMG * IMG);
        snprintf(p2, sizeof(p2), "%s/base_mean_x.raw", workDir);
        write_raw(p2, init.base.mean_x_ndc, NP);
        snprintf(p2, sizeof(p2), "%s/base_mean_y.raw", workDir);
        write_raw(p2, init.base.mean_y_ndc, NP);
        snprintf(p2, sizeof(p2), "%s/base_invz.raw", workDir);
        write_raw(p2, init.base.mean_inverse_z_ndc, NP);
        snprintf(p2, sizeof(p2), "%s/base_scale.raw", workDir);
        write_raw(p2, init.base.scales, NP);
        snprintf(p2, sizeof(p2), "%s/base_quat.raw", workDir);
        write_raw(p2, init.base.quaternions, 4 * NP);
        snprintf(p2, sizeof(p2), "%s/base_color.raw", workDir);
        write_raw(p2, init.base.colors, 3 * NP);
        snprintf(p2, sizeof(p2), "%s/base_opacity.raw", workDir);
        write_raw(p2, init.base.opacities, NP);
        printf("dumped base values\n");
    }
    sharp_progress(SHARP_STAGE_POST, "post", 2, 5, "initializer");

    /* 3. composer (PredictorParams 默认值, color_space=linearRGB) */
    /* 3. composer (PredictorParams defaults, color_space=linearRGB) */
    ComposerParams cp;
    cp.delta_xy = 0.001f; cp.delta_z = 0.001f;
    cp.delta_color = 0.1f; cp.delta_opacity = 1.0f;
    cp.delta_scale = 1.0f; cp.delta_quat = 1.0f;
    cp.min_scale = 0.0f; cp.max_scale = 10.0f;
    cp.color_activation = 0; cp.color_space = 1;
    cp.base_scale_on_predicted_mean = 1;
    Gaussians3DFlat g = composer_run(delta, &init.base, init.global_scale,
                                     768, 768, 2, &cp, 1);
    printf("composed %d gaussians\n", g.num_points);

    if (getenv("POST_DUMP")) {
        char p3[512];
        const int NG = g.num_points;
        snprintf(p3, sizeof(p3), "%s/g_mean.raw", workDir);
        write_raw(p3, g.mean_vectors, 3 * NG);
        snprintf(p3, sizeof(p3), "%s/g_sv.raw", workDir);
        write_raw(p3, g.singular_values, 3 * NG);
        snprintf(p3, sizeof(p3), "%s/g_quat.raw", workDir);
        write_raw(p3, g.quaternions, 4 * NG);
        snprintf(p3, sizeof(p3), "%s/g_color.raw", workDir);
        write_raw(p3, g.colors, 3 * NG);
        snprintf(p3, sizeof(p3), "%s/g_op.raw", workDir);
        write_raw(p3, g.opacities, NG);
        printf("dumped ndc gaussians\n");
    }
    sharp_progress(SHARP_STAGE_POST, "post", 3, 5, "composer");

/* 4. unproject (extrinsics=identity) — 完整 3x3 变换 + SVD,
   4. unproject (extrinsics=identity) — full 3x3 transform + SVD,
           与 torch unproject_gaussians 一致: T = inv(ndc4 @ intr4)[:3]
           consistent with torch unproject_gaussians: T = inv(ndc4 @ intr4)[:3]
           torch (predict.py:135-141) cx_orig=(orig_w-1)/2, cy_orig=(orig_h-1)/2,
           Scale to internal resolution: cx = cx_orig * IMG / orig_w (non-integer, consistent with torch)
           scaled to the internal resolution: cx = cx_orig * IMG / orig_w
           (non-integer, as in torch)
           ndc4@intr4 = [[2fx/W,0,2cx/W-1,0],[0,2fy/H,2cy/H-1,0],[0,0,1,0],[0,0,0,1]]
           inv(3x3) = [[W/2fx,0,(1-2cx/W)W/2fx],[0,H/2fy,(1-2cy/H)H/2fy],[0,0,1]] */
    float fx = fpx * (float)IMG / (float)origW;
    float fy = fpx * (float)IMG / (float)origH;
    float cx = (float)(origW - 1) * 0.5f * (float)IMG / (float)origW;
    float cy = (float)(origH - 1) * 0.5f * (float)IMG / (float)origH;
    float a = 2.0f * fx / (float)IMG, b = 2.0f * cx / (float)IMG - 1.0f;
    float c2 = 2.0f * fy / (float)IMG, d = 2.0f * cy / (float)IMG - 1.0f;
    float transform[12] = {
        1.0f / a, 0.0f, -b / a, 0.0f,
        0.0f, 1.0f / c2, -d / c2, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f
    };
    /* 原地变换 (unproject_full 逐点先读入局部变量再写同索引, out==g 安全):
     * In-place transform (unproject_full reads each point into locals before
     * writing the same index, so out==g is safe):
     * 省去 metric 全套缓冲 (~66MB), 同时避免事后双份 free
     * saves the full metric buffers (~66MB) and avoids freeing the arrays twice */
    unproject_full(&g, transform, &g);
    sharp_progress(SHARP_STAGE_POST, "post", 4, 5, "unproject");

    /* 5. save_ply (metadata 用原始焦距与原始尺寸) */
    /* 5. save_ply (metadata uses the original focal length and image size) */
    int rc = save_ply(&g, fpx, origW, origH, outPlyPath);
    printf("save_ply rc=%d -> %s\n", rc, outPlyPath);

    sharp_progress(SHARP_STAGE_POST, "post", 5, 5, "done");

    composer_free_output(&g);
    initializer_free_output(&init);
    free(image); free(disparity); free(delta); free(depth);
    return rc;
}

/* ─────────────── sharp_prep_image ─────────────── */
/* Preprocess image: decode JPEG/PNG -> EXIF rotation/focal -> resize 1536 -> image.raw
 * input image path
 * outRawPath: output image.raw path [1,3,1536,1536] NCHW f32 in [0,1]
 * outFpx: output focal length in pixels
 * outDfactor: output disparity_factor = f_px / orig_w
 * outOrigW, outOrigH: output original image size (after EXIF rotation)
 * Returns 0 on success, non-zero on failure
 */
int sharp_prep_image(const char *imagePath, const char *outRawPath,
                     float *outFpx, float *outDfactor, int *outOrigW, int *outOrigH)
{
    sharp_progress(SHARP_STAGE_PREP_IMAGE, "prep_image", 0, 4, "read file");

    /* 读整个文件字节: 同时用于 stb 解码和 EXIF 解析 */
    /* Read the whole file: shared by stb decoding and EXIF parsing */
    FILE *fin = fopen(imagePath, "rb");
    if (!fin) {
        CORE_LOGE("ERROR: cannot open %s\n", imagePath);
        return 1;
    }
    fseek(fin, 0, SEEK_END);
    long fsize = ftell(fin);
    fseek(fin, 0, SEEK_SET);
    unsigned char *fbytes = (unsigned char *)malloc((size_t)fsize);
    if (fread(fbytes, 1, (size_t)fsize, fin) != (size_t)fsize) {
        CORE_LOGE("ERROR: cannot read %s\n", imagePath);
        fclose(fin);
        return 1;
    }
    fclose(fin);

    /* EXIF 解析 (Orientation + 焦距) */
    /* EXIF parsing (Orientation + focal length) */
    ExifData ex;
    exif_parse(fbytes, (size_t)fsize, &ex);
    const double f_mm = focal_length_from_exif(&ex);
    sharp_progress(SHARP_STAGE_PREP_IMAGE, "prep_image", 1, 4, "exif parsed");

    /* stb_image 解码为 3 通道 RGB */
    /* stb_image decode into 3-channel RGB */
    int w = 0, h = 0, comp = 0;
    unsigned char *img = stbi_load_from_memory(fbytes, (int)fsize, &w, &h, &comp, 3);
    free(fbytes);
    if (!img) {
        CORE_LOGE("ERROR: stbi_load failed for %s\n", imagePath);
        return 1;
    }

    /* 先 uint8 域旋转 (峰值 288MB), 再转 float (单份 576MB) —
     * Rotate in the uint8 domain first (peak 288MB), then convert to float (one 576MB buffer) —
     * 避免 float 域旋转时 src+out 双份 float (1.15GB) 同存
     * avoids holding both src+out floats (1.15GB) during a float-domain rotation. */
    rotate_u8_by_orientation(&img, &w, &h, ex.orientation);

    /* 转 float [0,1] HWC */
    /* Convert to float [0,1] HWC */
    float *src = (float *)malloc((size_t)w * h * 3 * sizeof(float));
    for (size_t i = 0; i < (size_t)w * h * 3; i++)
        src[i] = img[i] / 255.0f;
    free(img);
    sharp_progress(SHARP_STAGE_PREP_IMAGE, "prep_image", 2, 4, "decoded+rotated");

    /* resize 1536x1536: CPU bilinear, 与训练参考 src 一致 (predict.py) */
    /* resize 1536x1536: CPU bilinear, matching the training reference (predict.py) */
    float *dst = (float *)calloc(OUT_SIZE * OUT_SIZE * 3, sizeof(float));
    int rr = resize_prep(src, w, h, 3, dst);
    free(src);
    if (rr != 0) {
        CORE_LOGE("ERROR: resize_prep 失败: %d\n", rr);
        free(dst);
        return 1;
    }

    /* HWC -> NCHW [1,3,1536,1536] */
    float *nchw = (float *)malloc(OUT_SIZE * OUT_SIZE * 3 * sizeof(float));
    for (int c = 0; c < 3; c++)
        for (int y = 0; y < OUT_SIZE; y++)
            for (int x = 0; x < OUT_SIZE; x++)
                nchw[(c * OUT_SIZE + y) * OUT_SIZE + x] = dst[(y * OUT_SIZE + x) * 3 + c];
    free(dst);

    /* 写 image.raw */
    /* Write image.raw */
    FILE *f = fopen(outRawPath, "wb");
    if (!f) {
        CORE_LOGE("ERROR: cannot write %s\n", outRawPath);
        free(nchw);
        return 1;
    }
    fwrite(nchw, sizeof(float), (size_t)OUT_SIZE * OUT_SIZE * 3, f);
    fclose(f);

    /* 计算输出参数 */
    /* Compute the output parameters */
    const double f_px = compute_fpx(w, h, f_mm);
    if (outFpx) *outFpx = (float)f_px;
    if (outDfactor) *outDfactor = (float)(f_px / w);
    if (outOrigW) *outOrigW = w;
    if (outOrigH) *outOrigH = h;

    sharp_progress(SHARP_STAGE_PREP_IMAGE, "prep_image", 4, 4, "done");
    printf("prep_image: orig=%dx%d, f_px=%.2f (f_mm=%.1f, orient=%d, d_factor=%.4f)\n",
           w, h, f_px, f_mm, ex.orientation, f_px / w);

    free(nchw);
    return 0;
}