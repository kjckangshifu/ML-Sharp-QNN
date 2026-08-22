 /*
 * prep_input.cpp — image preprocessing replicating e2e/prep_input.py
 *
 *   PNG/WebP/BMP/GIF 等) 读图
 *   stb_image (single-header source, supports JPEG/PNG/WebP/BMP/GIF etc.) decodes the image
 *   + exif.c 解析 EXIF (Orientation 自动旋转 + 焦距读取, 同原项目 io.py)
 *   + exif.c parses EXIF (automatic Orientation rotation + focal length, like io.py)
 *   -> resize 1536x1536 (torch bilinear align_corners=True)
 *   -> image.raw (float32 NCHW [1,3,1536,1536], 0-1) + f_px 估计
 *   -> image.raw (float32 NCHW [1,3,1536,1536], 0-1) + f_px estimate
 *
 * 用法: 库模式被 pipeline_core.c 调用 (sharp_prep_image);
 * Usage: library mode, called by pipeline_core.c (sharp_prep_image);
 *       独立命令行入口 main() 需定义 PREP_INPUT_STANDALONE (桌面端调试用)
 *       a standalone main() requires PREP_INPUT_STANDALONE (desktop debugging)
 *
 * 原项目 io.load_rgb 对齐的细节:
 * Alignment details with prep_input.py / io.load_rgb:
 *   270/90 度 (先旋转后算 f_px)
 *   - EXIF Orientation 3/6/8 -> rotate 180/270/90 degrees (rotate before computing f_px)
 *   - 焦距: FocalLengthIn35mmFilm 优先; 否则 FocalLength (<10mm *8.4);
 *   - Focal length: FocalLengthIn35mmFilm first; otherwise FocalLength (<10mm *8.4);
 *     都没有用 30mm
 *     otherwise 30mm
 *   h)
 *   - f_px = f_mm * sqrt(w^2+h^2) / sqrt(36^2+24^2)  (w/h after rotation)
 *   - 读图: stb 解码为 3 通道 RGB (PNG 等无损格式与 PIL 逐位一致;
 *   - Decode: stb yields 3-channel RGB (lossless formats match PIL bit-for-bit;
 *     255 解码器差异, 对量化推理无感知影响)
 *     JPEG differs from PIL/libjpeg by ±3/255 at the decoder, imperceptible for quantized inference)
 *   - resize: bilinear align_corners=True, 与 torch CPU 实现同公式 (float 精度)
 *   - resize: bilinear align_corners=True, same formula as the torch CPU kernel (float precision)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#include "exif.h"

#define OUT_SIZE 1536

static double compute_fpx(int w, int h, double f_mm)
{
    return f_mm * sqrt((double)w * w + (double)h * h) / sqrt(36.0 * 36.0 + 24.0 * 24.0);
}

static double focal_length_from_exif(const ExifData *ex)
{
    double f = ex->focal_len_35mm;
    if (f < 1.0) {
        f = ex->focal_len_mm;
        if (f <= 0.0)
            f = 30.0;
        if (f < 10.0)
            f *= 8.4;
    }
    return f;
}

 /* Rotates by EXIF Orientation (PIL transpose semantics), HWC uint8 pixels.
 * 算术, 与 float 域旋转逐位等价):
 * Rotation happens in the uint8 domain (rotation = element reorder, no interpolation
 * or arithmetic, bit-equivalent to rotating in float):
 * 48MP 时峰值从 src+out 两份 float (2*576MB=1.15GB) 降到两份 uint8 (2*144MB=288MB),
 * At 48MP the peak drops from two float buffers (2*576MB=1.15GB) to two uint8
 * buffers (2*144MB=288MB),
 * 转 float 阶段为不可避免的单份 576MB+144MB=720MB。
 * and the float conversion stage inevitably peaks at 576MB+144MB=720MB. */
static void rotate_u8_by_orientation(unsigned char **pixels, int *w, int *h, int orientation)
{
    int W = *w, H = *h;
    unsigned char *out = NULL;
    int swap = 0;

    if (orientation == 3) {
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                memcpy(out + ((size_t)(H - 1 - y) * W + (W - 1 - x)) * 3,
                       (*pixels) + ((size_t)y * W + x) * 3, 3);
    } else if (orientation == 6) { /* PIL ROTATE_270: 逆时针 90 度 / 90 degrees counter-clockwise */
        swap = 1;
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < W; y++)
            for (int x = 0; x < H; x++)
                memcpy(out + ((size_t)y * H + x) * 3,
                       (*pixels) + ((size_t)(H - 1 - x) * W + y) * 3, 3);
    } else if (orientation == 8) { /* PIL ROTATE_90: 顺时针 90 度 / 90 degrees clockwise */
        swap = 1;
        out = (unsigned char *)malloc((size_t)W * H * 3);
        for (int y = 0; y < W; y++)
            for (int x = 0; x < H; x++)
                memcpy(out + ((size_t)y * H + x) * 3,
                       (*pixels) + ((size_t)x * W + (W - 1 - y)) * 3, 3);
    } else {
        return;
    }

    free(*pixels);
    *pixels = out;
    if (swap) {
        *w = H;
        *h = W;
    }
}

/* torch F.interpolate bilinear align_corners=True, CPU float32 语义 */
/* torch F.interpolate bilinear align_corners=True, CPU float32 semantics */
static void resize_bilinear(const float *src, int src_w, int src_h, int ch,
                            float *dst)
{
    /* torch CPU: 对 float32 输入, accscalar_t = float, 全流程 float 精度 */
    /* torch CPU: for float32 input, accscalar_t = float, full float precision */
    const float rh = (float)(src_h - 1) / (float)(OUT_SIZE - 1);
    const float rw = (float)(src_w - 1) / (float)(OUT_SIZE - 1);

    for (int oy = 0; oy < OUT_SIZE; oy++) {
        const float h1r = oy * rh;
        const int   h1  = (int)h1r;                        /* truncation (floor for positives) */
        const int   h1p = (h1 < src_h - 1) ? 1 : 0;
        const float h1l = h1r - h1;
        const float h0l = 1.0f - h1l;

        for (int ox = 0; ox < OUT_SIZE; ox++) {
            const float w1r = ox * rw;
            const int   w1  = (int)w1r;
            const int   w1p = (w1 < src_w - 1) ? 1 : 0;
            const float w1l = w1r - w1;
            const float w0l = 1.0f - w1l;

            for (int c = 0; c < ch; c++) {
                const float *p00 = src + (size_t)(h1 * src_w + w1) * ch + c;
                const float *p10 = p00 + (size_t)h1p * src_w * ch;
                const float *p01 = p00 + w1p * ch;
                const float *p11 = p10 + w1p * ch;
                const float  val = h0l * (w0l * p00[0] + w1l * p01[0])
                                 + h1l * (w0l * p10[0] + w1l * p11[0]);
                dst[((size_t)oy * OUT_SIZE + ox) * ch + c] = val;
            }
        }
    }
}

// In the Android build this file compiles in library mode (sharp_prep_image); main() is dead code.
// Define PREP_INPUT_STANDALONE for a standalone CLI entry on desktop debugging
#ifdef PREP_INPUT_STANDALONE

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: prep_input.exe <jpg> <out_dir>\n");
        return 1;
    }
    const char *src_path = argv[1];
    const char *out_dir  = argv[2];

    /* 读整个文件字节: 同时用于 stb 解码和 EXIF 解析 */
    /* Read the whole file: shared by stb decoding and EXIF parsing */
    FILE *fin = fopen(src_path, "rb");
    if (!fin) {
        fprintf(stderr, "ERROR: cannot open %s\n", src_path);
        return 1;
    }
    fseek(fin, 0, SEEK_END);
    long fsize = ftell(fin);
    fseek(fin, 0, SEEK_SET);
    unsigned char *fbytes = (unsigned char *)malloc((size_t)fsize);
    if (fread(fbytes, 1, (size_t)fsize, fin) != (size_t)fsize) {
        fprintf(stderr, "ERROR: cannot read %s\n", src_path);
        return 1;
    }
    fclose(fin);

    ExifData ex;
    exif_parse(fbytes, (size_t)fsize, &ex);
    const double f_mm = focal_length_from_exif(&ex);

    int w = 0, h = 0, comp = 0;
    unsigned char *img = stbi_load_from_memory(fbytes, (int)fsize, &w, &h, &comp, 3);
    free(fbytes);
    if (!img) {
        fprintf(stderr, "ERROR: stbi_load failed for %s\n", src_path);
        return 1;
    }

     * Rotate in the uint8 domain first (peak 288MB), then convert to float (one 576MB buffer) —
     * 避免 float 域旋转时 src+out 双份 float (1.15GB) 同存
     * avoids holding both src+out floats (1.15GB) during a float-domain rotation. */
    rotate_u8_by_orientation(&img, &w, &h, ex.orientation);

    float *src = (float *)malloc((size_t)w * h * 3 * sizeof(float));
    for (size_t i = 0; i < (size_t)w * h * 3; i++)
        src[i] = img[i] / 255.0f;
    free(img);

    float *dst = (float *)calloc(OUT_SIZE * OUT_SIZE * 3, sizeof(float));
    resize_bilinear(src, w, h, 3, dst);
    free(src);

    /* 转 NCHW: [1,3,1536,1536] (src 为 HWC, dst 循环内按 HWC 存) */
    /* Convert to NCHW: [1,3,1536,1536] (src is HWC; dst is stored HWC in the loop) */
    float *nchw = (float *)malloc(OUT_SIZE * OUT_SIZE * 3 * sizeof(float));
    for (int c = 0; c < 3; c++)
        for (int y = 0; y < OUT_SIZE; y++)
            for (int x = 0; x < OUT_SIZE; x++)
                nchw[(c * OUT_SIZE + y) * OUT_SIZE + x] = dst[(y * OUT_SIZE + x) * 3 + c];
    free(dst);

    char path[1024];
    snprintf(path, sizeof path, "%s/image.raw", out_dir);
    FILE *f = fopen(path, "wb");
    if (!f) { fprintf(stderr, "ERROR: cannot write %s\n", path); return 1; }
    fwrite(nchw, sizeof(float), (size_t)OUT_SIZE * OUT_SIZE * 3, f);
    fclose(f);

    float mn = nchw[0], mx = nchw[0];
    for (size_t i = 1; i < (size_t)OUT_SIZE * OUT_SIZE * 3; i++) {
        if (nchw[i] < mn) mn = nchw[i];
        if (nchw[i] > mx) mx = nchw[i];
    }

    const double f_px = compute_fpx(w, h, f_mm);
    printf("wrote %s ([1,3,%d,%d], min=%.4f, max=%.4f)\n",
           path, OUT_SIZE, OUT_SIZE, mn, mx);
    printf("orig size = %dx%d, f_px = %.2f (f_mm=%.1f, orient=%d, d_factor = %.4f)\n",
           w, h, f_px, f_mm, ex.orientation, f_px / w);
    free(nchw);
    return 0;
}

#endif /* PREP_INPUT_STANDALONE */