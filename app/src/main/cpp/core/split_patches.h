/**
 * split_patches.h — 滑窗切割图像为 patches (overlap)
 * split_patches.h — sliding-window split of images into patches (overlap)
 *
 *   SHARP SPN split:
 *     x0[1,3,1536,1536] -> 25 patches (overlap=0.25)
 *     x1[1,3,768,768]   -> 9 patches  (overlap=0.5)
 *     used directly)
 *
 *   输出顺序: 行优先外循环 (j), 列优先内循环 (i)
 *   Output order: row-major outer loop (j), column-major inner loop (i)
 *   最后顺序: 25 x0 + 9 x1 + 1 x2 = 35 patches
 *   Final order: 25 x0 + 9 x1 + 1 x2 = 35 patches
 */
#ifndef SPLIT_PATCHES_H
#define SPLIT_PATCHES_H

#ifdef __cplusplus
extern "C" {
#endif

/** 对单张图执行滑窗 split。输入 shape [C,H,W]，输出 shape [C * num_patches, patch_size, patch_size]。
 * Sliding-window split of a single image. Input [C,H,W], output [C * num_patches, patch_size, patch_size].
 *  @param src  输入图像 (float*, CHW 布局, 单帧)
 *              Input image (float*, CHW layout, single frame)
 *  @param dst  输出 patches (float*) 事先分配好
 *              Output patches (float*), pre-allocated
 *  channel count
 *  image height
 *  image width
 *  patch size (384)
 *  overlap ratio (0.25 or 0.5)
 *  number of patches produced
 */
int split_single(const float *src, float *dst,
                 int C, int H, int W,
                 int patch_size, float overlap_ratio);

/** 对整个金字塔做 split。
 * Splits the whole pyramid.
 *  @param x0_batch  x0 图像 (批次优先布局), shape (B, C, H_hi, W_hi)  NCHW
 *                  x0 images (batch-first), shape (B, C, H_hi, W_hi), NCHW
 *  x1 images
 *  x2 images
 *  @param patches   输出 buffer (B * 35, C, 384, 384)
 *                  Output buffer (B * 35, C, 384, 384)
 *  batch count
 *  channel count
 *  x0 size (1536)
 *  x0 size
 *  x1 size (768)
 *  x1 size
 *  x2 size (384)
 *  x2 size
 *
 *  Output layout:
 *    patches[batch][patch_idx][C][384][384]
 *    patch_idx: 0..24 = x0, 25..33 = x1, 34 = x2
 */
void split_pyramid_batch(const float *x0_batch, const float *x1_batch, const float *x2_batch,
                         float *patches,
                         int batch, int C,
                         int H_hi, int W_hi,
                         int H_mid, int W_mid,
                         int H_lo, int W_lo,
                         int patch_size);

#ifdef __cplusplus
}
#endif

#endif /* SPLIT_PATCHES_H */
