/**
 * save_ply.h — 将 metric Gaussians 写入 .ply 文件
 * save_ply.h — writes metric Gaussians to a .ply file
 *
 *   gaussians.py:346-482 的完整 C 移植:
 *   Full C port of gaussians.py:346-482:
 *   - vertex: xyz, f_dc(线性RGB->sRGB->SH0), opacity(logits), scale(log), rot
 *   - 元数据: extrinsic(16), intrinsic(9), image_size(2), frame(2),
 *   - Metadata: extrinsic(16), intrinsic(9), image_size(2), frame(2),
 *             disparity(2, 0.1/0.9 分位数), color_space(1), version(3)
 *             disparity(2, 0.1/0.9 quantiles), color_space(1), version(3)
 */
#ifndef SAVE_PLY_H
#define SAVE_PLY_H

#include "composer.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 将 metric Gaussians 保存为 .ply 文件。
 * Saves metric Gaussians to a .ply file.
 * @param gaussians  输入 metric gaussians (平铺布局 [3,N] per attr)
 *                  Input metric gaussians (flat layout [3,N] per attribute)
 * focal length (px)
 * image width
 * image height
 * output file path
 * 0=success
 */
int save_ply(const Gaussians3DFlat *gaussians,
             float f_px, int image_w, int image_h,
             const char *filepath);

#ifdef __cplusplus
}
#endif

#endif /* SAVE_PLY_H */
