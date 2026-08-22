/**
 * pyramid.h — 图像金字塔: bilinear 下采样 1536->768->384
 * pyramid.h — image pyramid: bilinear downsampling 1536->768->384
 *
 *   0.25, mode="bilinear", align_corners=False)
 *   PyTorch reference: F.interpolate(x, scale_factor=0.5/0.25, mode="bilinear", align_corners=False)
 *   输入布局: NCHW
 *   Input layout: NCHW
 */
#ifndef PYRAMID_H
#define PYRAMID_H

#ifdef __cplusplus
extern "C" {
#endif

/** 创建 3 级图像金字塔。
 * Builds a 3-level image pyramid.
 *  @param src   输入数据 (float*, NCHW, 形状 [batch, C, H, W], 假设 H==W==1536)
 *              Input (float*, NCHW, shape [batch, C, H, W], assuming H==W==1536)
 *  @param x0    输出 x0 (同 src, 1536) — 可选, 为 NULL 则跳过
 *              Output x0 (same as src, 1536) — optional, skipped when NULL
 *  @param x1    输出 x1 (768) — 可选
 *              Output x1 (768) — optional
 *  @param x2    输出 x2 (384) — 可选
 *              Output x2 (384) — optional
 *  batch
 *  channel count
 *  input height (1536)
 *  input width (1536)
 *
 *  注意: 所有输出 buffer 需要调用方预先分配。
 *  Note: all output buffers must be pre-allocated by the caller.
 *  x0 输出与 src 相同, 仅 memcpy。
 *  x0 is identical to src and is simply memcpy'd.
 *
 *  @return 0 成功; 非 0 失败
 *         0 on success; non-zero on failure
 */
int pyramid_create(const float *src,
                   float *x0,
                   float *x1,
                   float *x2,
                   int batch, int C, int H, int W);

#ifdef __cplusplus
}
#endif

#endif /* PYRAMID_H */
