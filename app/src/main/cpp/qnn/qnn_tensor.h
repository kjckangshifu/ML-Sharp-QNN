// qnn_tensor.h — QNN tensor utilities
// float32 <-> UFIXED_POINT_16/8 quantized conversion, tensor memory helpers
#pragma once

#include <cstdint>
#include <vector>

namespace qnn {

// float -> UFIXED_POINT_16 quantization
// Formula: quantized = clamp(round(float / scale) - offset, 0, 65535)
void floatToUfixed16(const float* src, uint16_t* dst, size_t count, float scale, int32_t offset);

// float -> UFIXED_POINT_8 quantization
void floatToUfixed8(const float* src, uint8_t* dst, size_t count, float scale, int32_t offset);

// UFIXED_POINT_16 -> float dequantization
// Formula: float = (quantized + offset) * scale
// (offset 是零点偏移, 通常为负数, quantized=32768 + offset=-32768 → float=0)
// (offset is the zero-point shift, usually negative; quantized=32768 + offset=-32768 -> float=0)
void ufixed16ToFloat(const uint16_t* src, float* dst, size_t count, float scale, int32_t offset);

// UFIXED_POINT_8 -> float dequantization
// Formula: float = (quantized + offset) * scale
void ufixed8ToFloat(const uint8_t* src, float* dst, size_t count, float scale, int32_t offset);

// Element count of a tensor (product of dims)
size_t calculateElementCount(const std::vector<uint32_t>& dims);

// NCHW -> NHWC transpose (float32, 4D)
void nchwToNhwc(const float* src, float* dst, int n, int c, int h, int w);

// NHWC -> NCHW transpose (float32, 4D)
void nhwcToNchw(const float* src, float* dst, int n, int c, int h, int w);

} // namespace qnn
