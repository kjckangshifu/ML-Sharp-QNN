/**
 * initializer.h — 创建 Gaussian 基值和 feature_input
 * initializer.h — builds Gaussian base values and feature_input
 *
 *   基于 init_model (initializer.py:64-253)
 *   Based on init_model (initializer.py:64-253)
 *   Pure arithmetic only (no learned weights)
 */
#ifndef INITIALIZER_H
#define INITIALIZER_H

#include <stddef.h>

/* 基值结构体，对应 GaussianBaseValues */
/* Base values struct, matching GaussianBaseValues */
typedef struct {
    float *mean_x_ndc;          /* [1, 1, num_layers, H/2, W/2] */
    float *mean_y_ndc;          /* same */
    float *mean_inverse_z_ndc;  /* same (disparity layers) */
    float *scales;             /* [1, 1, num_layers, H/2, W/2] */
    float *quaternions;        /* [1, 4, num_layers, H/2, W/2] */
    float *colors;             /* [1, 3, num_layers, H/2, W/2] */
    float *opacities;          /* [1, 1, num_layers, H/2, W/2] */
} GaussianBaseValues;

/* 初始器输出，对应 InitializerOutput */
/* Initializer output, matching InitializerOutput */
typedef struct {
    float *feature_input;     /* [1, 5, H, W] output of prepare_feature_input(image, depth) */
    GaussianBaseValues base;  /* base values (self-managed memory) */
    float global_scale;       /* scale factor, scalar [1] */
} InitializerOutput;

/**
 * 运行 initializer 产生基值 + feature_input。
 * Runs the initializer to produce base values + feature_input.
 * normalized image in [0,1]
 * @param depth      [1, 2, H, W]  metric depth
 * downsample stride (2)
 * layer count (2)
 * base depth (10.0)
 * @param scale_factor  (1.0)
 * @param init_disparity_factor  init_model 的 disparity_factor (1.0)
 * @param color_option  颜色初始化选项 (0=none, 1=first_layer, 2=all_layers)
 * whether to normalize depth (true=1)
 * @return           InitializerOutput。调用方负责 free_output()
 *                   InitializerOutput. Caller is responsible for free_output().
 *
 * clamp 后的。
 * Note: `image` is in [0,1] and `depth` is metric (m), not gap/clamped values.
 */
InitializerOutput initializer_run(const float *image, const float *depth,
                                   int H, int W,
                                   int stride, int num_layers,
                                   float base_depth, float scale_factor,
                                   float init_disparity_factor,
                                   int color_option,
                                   int normalize_depth);

/* 释放 initializer 输出的内部内存 */
/* Frees the internal memory of an initializer output */
void initializer_free_output(InitializerOutput *out);

#ifdef __cplusplus
}
#endif

#endif /* INITIALIZER_H */