/**
 * pyramid.c — 图像金字塔实现
 * pyramid.c — image pyramid implementation
 *
 * 与训练参考 src 完全一致: bilinear 插值, align_corners=False (torch 对齐)
 * Matches the training reference exactly: bilinear interpolation, align_corners=False (torch-aligned)
 *
 * 坐标映射 (align_corners=False):
 * Coordinate mapping (align_corners=False):
 *   src_x = (dst_x + 0.5) * scale_inv - 0.5
 *   h_out
 *   where scale_inv = H / h_out
 *
 * 对 x1 (scale_factor=0.5):  scale_inv = 2.0
 * For x1 (scale_factor=0.5): scale_inv = 2.0
 *    src_x = (dst_x + 0.5) * 2.0 - 0.5 = 2*dst_x + 0.5
 *
 * 对 x2 (scale_factor=0.25): scale_inv = 4.0
 * For x2 (scale_factor=0.25): scale_inv = 4.0
 *    src_x = (dst_x + 0.5) * 4.0 - 0.5 = 4*dst_x + 1.5
 */
#include "pyramid.h"
#include <string.h>
#include <math.h>

/* bilinear 单层 NCHW 下采样 */
/* Bilinear single-layer NCHW downsampling */
static void bilinear_down(const float *src, float *dst,
                          int C, int H, int W, int h_out, int w_out)
{
    float scale_h = (float)H / (float)h_out;
    float scale_w = (float)W / (float)w_out;

    for (int c = 0; c < C; c++) {
        const float *src_c = src + c * H * W;
        float       *dst_c = dst + c * h_out * w_out;

        for (int i = 0; i < h_out; i++) {
            float sy = (i + 0.5f) * scale_h - 0.5f;
            int y0 = (int)sy;
            if (y0 < 0) y0 = 0;
            int y1 = (y0 + 1 < H) ? (y0 + 1) : y0;
            float wy1 = sy - (float)y0;
            float wy0 = 1.0f - wy1;

            for (int j = 0; j < w_out; j++) {
                float sx = (j + 0.5f) * scale_w - 0.5f;
                int x0 = (int)sx;
                if (x0 < 0) x0 = 0;
                int x1 = (x0 + 1 < W) ? (x0 + 1) : x0;
                float wx1 = sx - (float)x0;
                float wx0 = 1.0f - wx1;

                float v00 = src_c[y0 * W + x0];
                float v01 = src_c[y0 * W + x1];
                float v10 = src_c[y1 * W + x0];
                float v11 = src_c[y1 * W + x1];

                dst_c[i * w_out + j] = wy0 * (wx0 * v00 + wx1 * v01)
                                     + wy1 * (wx0 * v10 + wx1 * v11);
            }
        }
    }
}

int pyramid_create(const float *src,
                   float *x0,
                   float *x1,
                   float *x2,
                   int batch, int C, int H, int W)
{
    const int HW = H * W;
    const int plane_n = C * HW;

    for (int b = 0; b < batch; b++) {
        const float *src_b = src + b * plane_n;

        if (x0) {
            float *x0_b = x0 + b * plane_n;
            memcpy(x0_b, src_b, plane_n * sizeof(float));
        }

        if (x1) {
            float *x1_b = x1 + b * C * (H/2) * (W/2);
            bilinear_down(src_b, x1_b, C, H, W, H/2, W/2);
        }

        if (x2) {
            float *x2_b = x2 + b * C * (H/4) * (W/4);
            bilinear_down(src_b, x2_b, C, H, W, H/4, W/4);
        }
    }
    return 0;
}
