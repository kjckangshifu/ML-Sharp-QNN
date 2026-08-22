/**
 * merge_patches.h — 合并 patch 为完整特征图
 * merge_patches.h — merges patches back into full feature maps
 *
 *   SHARP SPN merge:
 *   ViT 的输出是 [B*N, C, patch_h, patch_w] (例如 25 个 patch, 每个 1024x24x24)
 *   The ViT output is [B*N, C, patch_h, patch_w] (e.g. 25 patches of 1024x24x24).
 *   merge 按 grid (steps x steps) 拼回整图, 每边裁掉 padding 个像素。
 *   Merge reassembles the image on a grid (steps x steps), cropping `padding`
 *   pixels on each edge.
 *
 *   Call sites:
 *     x0 latent0/latent1/patch_features: 25 patches,  padding=3  → 96x96
 *     x1 patch_features:                  9 patches,  padding=6  → 48x48
 *     x2:                                 1 patch,    无需 merge
 *                                             no merge needed
 */
#ifndef MERGE_PATCHES_H
#define MERGE_PATCHES_H

#ifdef __cplusplus
extern "C" {
#endif

/** 合并 grid 状 patch 为完整特征图。
 * Merges grid-shaped patches into a full feature map.
 *  @param patches  输入 patch 数据, shape [B * steps*steps, C, ph, pw]
 *                  Input patches, shape [B * steps*steps, C, ph, pw]
 *  @param dst      输出特征图,   shape [B, C, H_out, W_out]
 *                  Output feature map, shape [B, C, H_out, W_out]
 *  batch count
 *  channel count
 *  B)) / grid steps (sqrt(num_patches / B))
 *  patch height (24)
 *  patch width (24)
 *  per-edge crop (3 or 6)
 *  @param H_out    输出高 (steps*ph - 2*padding*(steps-1) 或通过外部已知值)
 *                  Output height (steps*ph - 2*padding*(steps-1) or externally known)
 *  output width
 */
void merge_grid(const float *patches, float *dst,
                int B, int C, int steps, int ph, int pw,
                int padding,
                int H_out, int W_out);

#ifdef __cplusplus
}
#endif

#endif /* MERGE_PATCHES_H */
