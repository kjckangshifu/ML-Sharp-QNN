// qnn_tensor.cpp — QNN tensor utilities implementation
// float32 <-> UFIXED_POINT_16/8 quantized conversion, tensor memory helpers
#include "qnn_tensor.h"

#include <cmath>
#include <algorithm>

namespace qnn {

// float -> UFIXED_POINT_16 quantization
// Formula: quantized = clamp(round(float / scale) - offset, 0, 65535)
// (offset 是零点偏移, 通常为负数, 如 -32768 表示 float=0 对应 uint16=32768)
// (offset is the zero-point shift, usually negative; e.g. -32768 means float=0 maps to uint16=32768)
void floatToUfixed16(const float* src, uint16_t* dst, size_t count,
                     float scale, int32_t offset) {
    for (size_t i = 0; i < count; i++) {
        float scaled = src[i] / scale;
        long q = static_cast<long>(std::round(scaled)) - offset;
        if (q < 0) q = 0;
        if (q > 65535) q = 65535;
        dst[i] = static_cast<uint16_t>(q);
    }
}

// float -> UFIXED_POINT_8 quantization
// Formula: quantized = clamp(round(float / scale) - offset, 0, 255)
// (offset 是零点偏移, 通常为负数, 如 -128 表示 float=0 对应 uint8=128)
// (offset is the zero-point shift, usually negative; e.g. -128 means float=0 maps to uint8=128)
void floatToUfixed8(const float* src, uint8_t* dst, size_t count,
                    float scale, int32_t offset) {
    for (size_t i = 0; i < count; i++) {
        float scaled = src[i] / scale;
        long q = static_cast<long>(std::round(scaled)) - offset;
        if (q < 0) q = 0;
        if (q > 255) q = 255;
        dst[i] = static_cast<uint8_t>(q);
    }
}

// UFIXED_POINT_16 -> float dequantization
// Formula: float = (quantized + offset) * scale
// (offset 是零点偏移, 通常为负数, quantized=32768 + offset=-32768 → float=0)
// (offset is the zero-point shift, usually negative; quantized=32768 + offset=-32768 -> float=0)
void ufixed16ToFloat(const uint16_t* src, float* dst, size_t count,
                     float scale, int32_t offset) {
    for (size_t i = 0; i < count; i++) {
        dst[i] = (static_cast<int32_t>(src[i]) + offset) * scale;
    }
}

// UFIXED_POINT_8 -> float dequantization
void ufixed8ToFloat(const uint8_t* src, float* dst, size_t count,
                    float scale, int32_t offset) {
    for (size_t i = 0; i < count; i++) {
        dst[i] = (static_cast<int32_t>(src[i]) + offset) * scale;
    }
}

// Element count of a tensor (product of dims)
size_t calculateElementCount(const std::vector<uint32_t>& dims) {
    size_t count = 1;
    for (size_t i = 0; i < dims.size(); i++) {
        count *= dims[i];
    }
    return count;
}

// NCHW -> NHWC transpose (float32, 4D)
// src layout: [N][C][H][W], dst layout: [N][H][W][C]
void nchwToNhwc(const float* src, float* dst, int n, int c, int h, int w) {
    for (int ni = 0; ni < n; ni++) {
        for (int hi = 0; hi < h; hi++) {
            for (int wi = 0; wi < w; wi++) {
                for (int ci = 0; ci < c; ci++) {
                    int srcIdx = ((ni * c + ci) * h + hi) * w + wi;
                    int dstIdx = ((ni * h + hi) * w + wi) * c + ci;
                    dst[dstIdx] = src[srcIdx];
                }
            }
        }
    }
}

// NHWC -> NCHW transpose (float32, 4D)
// src layout: [N][H][W][C], dst layout: [N][C][H][W]
void nhwcToNchw(const float* src, float* dst, int n, int c, int h, int w) {
    for (int ni = 0; ni < n; ni++) {
        for (int ci = 0; ci < c; ci++) {
            for (int hi = 0; hi < h; hi++) {
                for (int wi = 0; wi < w; wi++) {
                    int srcIdx = ((ni * h + hi) * w + wi) * c + ci;
                    int dstIdx = ((ni * c + ci) * h + hi) * w + wi;
                    dst[dstIdx] = src[srcIdx];
                }
            }
        }
    }
}

} // namespace qnn
