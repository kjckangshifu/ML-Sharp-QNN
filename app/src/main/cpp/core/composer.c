/**
 * composer.c — GaussianComposer 实现
 * composer.c — GaussianComposer implementation
 */
#include "composer.h"
#include <string.h>
#include <math.h>
#include <stdlib.h>

/* math helpers */
static float inverse_sigmoid(float x)
{
    if (x <= 0.0f) return -20.0f;
    if (x >= 1.0f) return 20.0f;
    return logf(x / (1.0f - x));
}

static float inverse_softplus(float x)
{
    if (x < 1e-6f) x = 1e-6f;
    /* 数值稳定: log(exp(x) - 1) = x + log(1 - exp(-x)) */
    /* Numerically stable: log(exp(x) - 1) = x + log(1 - exp(-x)) */
    float ex = expf(-x);
    if (ex > 0.5f)  /* use expm1 to avoid cancellation */
        return logf(expm1f(x));
    return x + logf(1.0f - ex);
}

static float sigmoid(float x)
{
    return 1.0f / (1.0f + expf(-x));
}

static float softplus(float x)
{
    if (x > 20.0f) return x;
    return logf(1.0f + expf(x));
}

static float delta_factor_scale_correction(float a, float b, float delta,
                                           float min_s, float max_s)
{
    /* sigmoid(a * delta + b) */
    float s = sigmoid(a * delta + b);
    return (max_s - min_s) * s + min_s;
}

/* sRGB2linearRGB */
static float srgb_to_linear(float c)
{
    if (c <= 0.04045f)
        return c / 12.92f;
    else
        return powf((c + 0.055f) / 1.055f, 2.4f);
}

Gaussians3DFlat composer_run(
    const float *delta,
    const GaussianBaseValues *base,
    float global_scale,
    int H, int W, int L,
    const ComposerParams *params,
    int flatten_output)
{
    int bh = H, bw = W;  /* base spatial size (768x768) */
    int base_plane = L * bh * bw;

    /* 常量计算 */
    /* Constant computation */
    /* _get_scale_activation_constant(max_scale, min_scale):
       constant_a = (M-m) / (1-m) / (M-1) = (M-m) / ((1-m)*(M-1))
       constant_b = inverse_sigmoid((1-m)/(M-m)) */
    float ms = params->max_scale, mis = params->min_scale;
    float cst_a = (ms - mis) / ((1.0f - mis) * (ms - 1.0f));
    float cst_b = inverse_sigmoid((1.0f - mis) / (ms - mis));

    Gaussians3DFlat out;
    memset(&out, 0, sizeof(out));
    int np = L * bh * bw;
    out.num_points = np;

    /* 分配输出 (平铺布局: [B, L*bh*bw, C] 但 B=1) */
    /* Allocate output (flat layout: [B, L*bh*bw, C], with B=1) */
    out.mean_vectors    = (float*)malloc(3 * np * sizeof(float));
    out.singular_values = (float*)malloc(3 * np * sizeof(float));
    out.quaternions     = (float*)malloc(4 * np * sizeof(float));
    out.colors          = (float*)malloc(3 * np * sizeof(float));
    out.opacities       = (float*)malloc(np * sizeof(float));

    /* 预计算 global_scale 乘子，避免循环内条件分支 */
    /* Precompute global_scale multiplier to avoid conditional branch inside loop */
    float gs = (global_scale != 0.0f) ? global_scale : 1.0f;

    for (int l = 0; l < L; l++) {
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int idx_base = l * bh * bw + y * bw + x;  /* base index (L,bh,bw) */
                int idx_delta = l * H * W + y * W + x;    /* delta index (L,H,W)  — same dims because scale_factor=1 */

                /* delta values */
                float d_mx = delta[0*np + idx_delta];
                float d_my = delta[1*np + idx_delta];
                float d_mz = delta[2*np + idx_delta];
                float d_sx = delta[3*np + idx_delta];
                float d_sy = delta[4*np + idx_delta];
                float d_sz = delta[5*np + idx_delta];
                /* quaternion 通道顺序 [w, x, y, z] (与 initializer.py:213 一致) */
                /* quaternion channel order [w, x, y, z] (matching initializer.py:213) */
                float d_qw = delta[6*np + idx_delta];
                float d_qx = delta[7*np + idx_delta];
                float d_qy = delta[8*np + idx_delta];
                float d_qz = delta[9*np + idx_delta];
                float d_cr = delta[10*np + idx_delta];
                float d_cg = delta[11*np + idx_delta];
                float d_cb = delta[12*np + idx_delta];
                float d_oa = delta[13*np + idx_delta];

                /* base values */
                float bx = base->mean_x_ndc[idx_base];
                float by = base->mean_y_ndc[idx_base];
                float bz = base->mean_inverse_z_ndc[idx_base];
                float bs = base->scales[idx_base];
                float bq0 = base->quaternions[0*np + idx_base];
                float bq1 = base->quaternions[1*np + idx_base];
                float bq2 = base->quaternions[2*np + idx_base];
                float bq3 = base->quaternions[3*np + idx_base];
                float bc_r = base->colors[0*np + idx_base];
                float bc_g = base->colors[1*np + idx_base];
                float bc_b = base->colors[2*np + idx_base];
                float bo = base->opacities[idx_base];

                /* 1. mean: base_vectors_ndc = [bx, by, bz]; delta_factor = [xy, xy, z] */
                float df_xy = params->delta_xy;
                float df_z  = params->delta_z;

                /* xx = bx + df_xy * delta[0]; yy = by + df_xy * delta[1] */
                float xx = bx + df_xy * d_mx;
                float yy = by + df_xy * d_my;

                /* inverse_zz = softplus(inverse_softplus(bz) + df_z * delta[2]) */
                float raw_z = inverse_softplus(bz) + df_z * d_mz;
                float inv_zz = softplus(raw_z);

                float zz = 1.0f / (inv_zz + 1e-3f);

                float mean_x = zz * xx;
                float mean_y = zz * yy;
                float mean_z = zz;

                /* 2. scale: base_scales * scale_factor */
                float base_s = bs;
                if (params->base_scale_on_predicted_mean) {
                    base_s = bs * bz * mean_z;
                }
                float sf = delta_factor_scale_correction(cst_a, cst_b,
                                params->delta_scale * d_sx, mis, ms);
                float sv_x = base_s * sf;
                sf = delta_factor_scale_correction(cst_a, cst_b,
                                params->delta_scale * d_sy, mis, ms);
                float sv_y = base_s * sf;
                sf = delta_factor_scale_correction(cst_a, cst_b,
                                params->delta_scale * d_sz, mis, ms);
                float sv_z = base_s * sf;

                /* 3. quaternion: base + df_quat * delta */
                float qw = bq0 + params->delta_quat * d_qw;
                float qx = bq1 + params->delta_quat * d_qx;
                float qy = bq2 + params->delta_quat * d_qy;
                float qz = bq3 + params->delta_quat * d_qz;

                /* 4. color: sigmoid(inverse_sigmoid(base) + df_color * delta) */
                float bc_r_clp = bc_r;  /* clamped below */
                float bc_g_clp = bc_g;
                float bc_b_clp = bc_b;
                if (params->color_activation == 0) { /* sigmoid */
                    bc_r_clp = bc_r < 0.01f ? 0.01f : (bc_r > 0.99f ? 0.99f : bc_r);
                    bc_g_clp = bc_g < 0.01f ? 0.01f : (bc_g > 0.99f ? 0.99f : bc_g);
                    bc_b_clp = bc_b < 0.01f ? 0.01f : (bc_b > 0.99f ? 0.99f : bc_b);
                }
                float col_r = sigmoid(inverse_sigmoid(bc_r_clp) + params->delta_color * d_cr);
                float col_g = sigmoid(inverse_sigmoid(bc_g_clp) + params->delta_color * d_cg);
                float col_b = sigmoid(inverse_sigmoid(bc_b_clp) + params->delta_color * d_cb);

                /* sRGB -> linearRGB if needed */
                if (params->color_space == 1) {
                    col_r = srgb_to_linear(col_r);
                    col_g = srgb_to_linear(col_g);
                    col_b = srgb_to_linear(col_b);
                }

                /* 5. opacity: sigmoid(inverse_sigmoid(base) + df_opacity * delta) */
                float op = sigmoid(inverse_sigmoid(bo) + params->delta_opacity * d_oa);

                /* 写出结果 (pre-flatten 或直接平铺) */
                /* Write result (pre-flatten, i.e. directly flat) */
                int out_idx = idx_base;  /* 平铺后 B=1, [L,H,W,C] -> [L*H*W, C] */
                                         /* flat layout B=1: [L,H,W,C] -> [L*H*W, C] */

                out.mean_vectors[0*np + out_idx] = mean_x * gs;
                out.mean_vectors[1*np + out_idx] = mean_y * gs;
                out.mean_vectors[2*np + out_idx] = mean_z * gs;
                out.singular_values[0*np + out_idx] = sv_x * gs;
                out.singular_values[1*np + out_idx] = sv_y * gs;
                out.singular_values[2*np + out_idx] = sv_z * gs;
                out.quaternions[0*np + out_idx] = qw;
                out.quaternions[1*np + out_idx] = qx;
                out.quaternions[2*np + out_idx] = qy;
                out.quaternions[3*np + out_idx] = qz;
                out.colors[0*np + out_idx] = col_r;
                out.colors[1*np + out_idx] = col_g;
                out.colors[2*np + out_idx] = col_b;
                out.opacities[out_idx] = op;
            }
        }
    }

    return out;
}

void composer_free_output(Gaussians3DFlat *out)
{
    if (!out) return;
    free(out->mean_vectors);
    free(out->singular_values);
    free(out->quaternions);
    free(out->colors);
    free(out->opacities);
    memset(out, 0, sizeof(*out));
}