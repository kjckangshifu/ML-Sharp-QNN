/**
 * composer.h — GaussianComposer: delta + base_values -> NDC gaussians
 *
 *   参考 composer.py:92-251
 *   Reference: composer.py:92-251
 *   Pure arithmetic + activation functions only
 */
#ifndef COMPOSER_H
#define COMPOSER_H

#include "initializer.h"  /* GaussianBaseValues */

#ifdef __cplusplus
extern "C" {
#endif

/* 输出 3D Gaussians (平铺布局) */
/* Output 3D Gaussians (flat layout) */
typedef struct {
    float *mean_vectors;          /* [B, L*H*W, 3] */
    float *singular_values;       /* [B, L*H*W, 3] */
    float *quaternions;           /* [B, L*H*W, 4] */
    float *colors;                /* [B, L*H*W, 3] */
    float *opacities;             /* [B, L*H*W] */
    int num_points;
} Gaussians3DFlat;

/* 参数 */
/* Params */
typedef struct {
    float delta_xy;       /* DeltaFactor.xy   (0.001) */
    float delta_z;        /* DeltaFactor.z    (0.001) */
    float delta_color;    /* DeltaFactor.color (0.1) */
    float delta_opacity;  /* DeltaFactor.opacity (1.0) */
    float delta_scale;    /* DeltaFactor.scale (1.0) */
    float delta_quat;     /* DeltaFactor.quaternion (1.0) */
    float min_scale;      /* (0.0) */
    float max_scale;      /* (10.0) */
    int color_activation; /* 0=sigmoid, 1=softplus, 2=exp */
    int color_space;      /* 0=sRGB, 1=linearRGB */
    int base_scale_on_predicted_mean; /* (1=true) */
} ComposerParams;

/**
 * 运行 composer, 从 delta + base_values 生成 NDC gaussians。
 * Runs the composer, building NDC gaussians from delta + base_values.
 * from the prediction_head output)
 * base values (from the initializer)
 * scale factor (from the initializer)
 * delta spatial size (768)
 * @param L         num_layers (2)
 * params
 * whether to flatten the output (1=yes)
 * the flattened Gaussians3D
 */
Gaussians3DFlat composer_run(
    const float *delta,
    const GaussianBaseValues *base,
    float global_scale,
    int H, int W, int L,
    const ComposerParams *params,
    int flatten_output);

/* 释放 composer 输出内存 */
/* Frees the composer output memory */
void composer_free_output(Gaussians3DFlat *out);

#ifdef __cplusplus
}
#endif

#endif /* COMPOSER_H */
