/**
 * normalizer.h — AffineRangeNormalizer: image [0,1] -> [-1,1]
 *
 *   out = x * scale + bias    (scale=2.0, bias=-1.0)
 *
 * 输入布局: NCHW (batch, channel, height, width)
 * Input layout: NCHW (batch, channel, height, width)
 * CHW 分量: RGB 三通道
 * CHW channels: RGB
 */
#ifndef NORMALIZER_H
#define NORMALIZER_H

#include <stddef.h>  /* size_t */

#ifdef __cplusplus
extern "C" {
#endif

/** 将 [0.0, 1.0] 范围图像归一化到 [-1.0, 1.0]。
 * Normalizes an image in [0.0, 1.0] to [-1.0, 1.0].
 *  @param src  输入数据 (float*, NCHW 布局), 大小 = batch * channel * height * width
 *              Input (float*, NCHW layout), size = batch * channel * height * width
 *  @param dst  输出数据 (float*, NCHW 布局), 大小同 src
 *              Output (float*, NCHW layout), same size as src
 *  batch count
 *  channel count (usually 3)
 *  image height
 *  image width
 *  scale factor (default 2.0)
 *  bias (default -1.0)
 */
void normalizer_apply(const float *src, float *dst,
                      int batch, int channel, int height, int width,
                      float scale, float bias);

/**
 *  就地版本 (src==dst)
 *  In-place variant (src==dst)
 */
void normalizer_apply_inplace(float *data,
                              int batch, int channel, int height, int width,
                              float scale, float bias);

#ifdef __cplusplus
}
#endif

#endif /* NORMALIZER_H */
