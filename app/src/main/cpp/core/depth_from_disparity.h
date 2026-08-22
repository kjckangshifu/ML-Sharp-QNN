/**
 * depth_from_disparity.h — disparity -> depth 转换
 * depth_from_disparity.h — disparity to depth conversion
 *
 *   depth = d_factor / clamp(disparity, 1e-4, 1e4)
 *
 *   输入:  disparity [1, 2, H, W] 或 [B, C, H, W] (C=2 层)
 *   Input: disparity [1, 2, H, W] or [B, C, H, W] (C=2 layers)
 *   输出:  depth    [1, 2, H, W]
 *   Output: depth   [1, 2, H, W]
 *   original_width  (标量)
 *   Args:  d_factor = f_px / original_width  (scalar)
 */
#ifndef DEPTH_FROM_DISPARITY_H
#define DEPTH_FROM_DISPARITY_H

#ifdef __cplusplus
extern "C" {
#endif

void depth_from_disparity(const float *disparity, float *depth,
                          int C, int H, int W, float d_factor,
                          float clamp_min, float clamp_max);

#ifdef __cplusplus
}
#endif

#endif /* DEPTH_FROM_DISPARITY_H */
