/**
 * save_ply.c — 写入 .ply 文件
 * save_ply.c — writes .ply files
 */
#include "save_ply.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>

#ifdef __ANDROID__
#include <android/log.h>
#define PLY_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SharpCore", __VA_ARGS__)
#else
#define PLY_LOGE(...) fprintf(stderr, "[SharpCore] " __VA_ARGS__)
#endif

static float linear_to_sh0(float c)
{
    /* linearRGB -> sRGB -> SH0, 合并为一步避免中间变量 */
    /* linearRGB -> sRGB -> SH0, combined into one step */
    float srgb;
    if (c <= 0.0031308f)
        srgb = c * 12.92f;
    else
        srgb = 1.055f * powf(c, 1.0f/2.4f) - 0.055f;
    return (srgb - 0.5f) * 3.54490770181f; /* 1/sqrt(1/(4*pi)) ≈ 3.5449 */
}

static float quickselect(float *a, int n, int k)
{
    int lo = 0, hi = n - 1;
    while (lo < hi) {
        float pivot = a[(lo + hi) >> 1];
        int i = lo - 1, j = hi + 1;
        while (1) {
            do i++; while (a[i] < pivot);
            do j--; while (a[j] > pivot);
            if (i >= j) break;
            float t = a[i]; a[i] = a[j]; a[j] = t;
        }
        if (j < k) lo = j + 1;
        else       hi = j;
    }
    return a[k];
}

typedef struct {
    float x, y, z;
    float f_dc_0, f_dc_1, f_dc_2;
    float opacity;
    float scale_0, scale_1, scale_2;
    float rot_0, rot_1, rot_2, rot_3;
} PlyVertex;

int save_ply(const Gaussians3DFlat *g, float f_px,
             int image_w, int image_h, const char *fpath)
{
    FILE *fp = fopen(fpath, "wb");
    if (!fp) { PLY_LOGE("Cannot open %s\n", fpath); return 1; }

    setvbuf(fp, NULL, _IOFBF, 256 * 1024);

    int N = g->num_points;

    /* PLY header */
    fprintf(fp,
        "ply\nformat binary_little_endian 1.0\n"
        "element vertex %d\n"
        "property float x\nproperty float y\nproperty float z\n"
        "property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n"
        "property float opacity\n"
        "property float scale_0\nproperty float scale_1\nproperty float scale_2\n"
        "property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n"
        "element extrinsic 16\nproperty float extrinsic\n"
        "element intrinsic 9\nproperty float intrinsic\n"
        "element image_size 2\nproperty uint image_size\n"
        "element frame 2\nproperty int frame\n"
        "element disparity 2\nproperty float disparity\n"
        "element color_space 1\nproperty uchar color_space\n"
        "element version 3\nproperty uchar version\n"
        "end_header\n", N);

    for (int i = 0; i < N; i++) {
        PlyVertex v;
        v.x = g->mean_vectors[0*N+i];
        v.y = g->mean_vectors[1*N+i];
        v.z = g->mean_vectors[2*N+i];

        v.f_dc_0 = linear_to_sh0(g->colors[0*N+i]);
        v.f_dc_1 = linear_to_sh0(g->colors[1*N+i]);
        v.f_dc_2 = linear_to_sh0(g->colors[2*N+i]);

        float op = g->opacities[i];
        v.opacity = logf(op / (1.0f - op));

        v.scale_0 = logf(g->singular_values[0*N+i]);
        v.scale_1 = logf(g->singular_values[1*N+i]);
        v.scale_2 = logf(g->singular_values[2*N+i]);

        v.rot_0 = g->quaternions[0*N+i];
        v.rot_1 = g->quaternions[1*N+i];
        v.rot_2 = g->quaternions[2*N+i];
        v.rot_3 = g->quaternions[3*N+i];

        fwrite(&v, sizeof(PlyVertex), 1, fp);
    }

    /* extrinsic: eye(4) */
    float eye4[16];
    memset(eye4, 0, sizeof(eye4));
    eye4[0] = eye4[5] = eye4[10] = eye4[15] = 1.0f;

    /* intrinsic: [f,0,W/2, 0,f,H/2, 0,0,1] */
    float intr[9] = {f_px, 0.0f, image_w * 0.5f,
                     0.0f, f_px, image_h * 0.5f,
                     0.0f, 0.0f, 1.0f};

    /* image_size: [W, H] (u4) */
    unsigned int img_size[2] = { (unsigned int)image_w, (unsigned int)image_h };

    /* frame: [1, N] (i4) */
    int frames[2] = { 1, N };

    /* disparity: quantile 0.1/0.9 of 1/mean_z (quickselect, O(N)) */
    float quant[2];
    {
        float *disp = (float*)malloc((size_t)N * sizeof(float));
        if (!disp) { fclose(fp); return 1; }
        for (int i = 0; i < N; i++)
            disp[i] = 1.0f / g->mean_vectors[2*N+i];
        float qs[2] = { 0.1f, 0.9f };
        for (int k = 0; k < 2; k++) {
            int idx = (int)(qs[k] * (N - 1));
            int lo = idx, hi = idx + 1 < N ? idx + 1 : idx;
            float v_lo = quickselect(disp, N, lo);
            float v_hi = quickselect(disp, N, hi);
            float frac = qs[k] * (N - 1) - lo;
            quant[k] = v_lo * (1.0f - frac) + v_hi * frac;
        }
        free(disp);
    }

    /* color_space: 0 = sRGB (u1) */
    unsigned char cs = 0;

    /* version: [1,5,0] (u1) */
    unsigned char ver[3] = { 1, 5, 0 };

    /* 批量写入所有元数据（7 次 fwrite → 1 次） */
    /* Batch write all metadata (7 fwrite calls → 1) */
    {
        unsigned char buf[128];
        int off = 0;
        memcpy(buf + off, eye4, 64);       off += 64;
        memcpy(buf + off, intr, 36);       off += 36;
        memcpy(buf + off, img_size, 8);    off += 8;
        memcpy(buf + off, frames, 8);      off += 8;
        memcpy(buf + off, quant, 8);       off += 8;
        buf[off++] = cs;
        memcpy(buf + off, ver, 3);         off += 3;
        fwrite(buf, 1, off, fp);
    }

    fclose(fp);
    return 0;
}