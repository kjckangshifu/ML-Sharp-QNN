/**
 * merge_patches.c — 合并 patch 实现
 * merge_patches.c — patch merge implementation
 *
 * PyTorch 参考 (spn_encoder.py:341-369):
 * PyTorch reference (spn_encoder.py:341-369):
 *   outer loop = rows
 *     inner loop = cols
 *       patch = patches[idx]
 *       crop top    if j != 0       => padding
 *       crop left   if i != 0       => padding
 *       crop bottom if j != steps-1 => padding
 *       crop right  if i != steps-1 => padding
 *       concat along width  (row)   => dim=-1
 *     concat along height (col)     => dim=-2
 */
#include "merge_patches.h"
#include <string.h>
#include <stdlib.h>

void merge_grid(const float *patches, float *dst,
                int B, int C, int steps, int ph, int pw,
                int padding,
                int H_out, int W_out)
{
    int patch_pixels = ph * pw;
    int patch_plane  = C * patch_pixels;
    int dst_plane    = C * H_out * W_out;
    int rows         = steps;
    int cols         = steps;

    for (int b = 0; b < B; b++) {
        const float *pb = patches + b * (rows * cols) * patch_plane;
        float *db       = dst + b * dst_plane;

        /* 对每行 j */
        /* For each row j */
        for (int j = 0; j < rows; j++) {
            int row_h = ph - (j != 0 ? padding : 0) - (j != rows - 1 ? padding : 0);
            int row_offset = (j != 0 ? padding : 0);

            /* 累积该行的所有列 patch */
            /* Accumulate all column patches of this row */
            float *row_buf = (float *)malloc((size_t)C * row_h * W_out * sizeof(float));
            if (!row_buf) { return; }  /* out of memory */
            memset(row_buf, 0, (size_t)C * row_h * W_out * sizeof(float));

            int col_offset = 0;

            for (int i = 0; i < cols; i++) {
                int idx = j * cols + i;
                const float *patch = pb + idx * patch_plane;

                int col_w = pw - (i != 0 ? padding : 0) - (i != cols - 1 ? padding : 0);
                int col_offset_src = (i != 0 ? padding : 0);

                /* 对每个通道 c, 将裁减后的矩形复制到 row_buf 的列偏移位置 */
                /* For each channel c, copy the cropped rectangle into row_buf at the column offset */
                for (int c = 0; c < C; c++) {
                    const float *src_c = patch + c * patch_pixels
                                        + row_offset * pw + col_offset_src;
                    float *dst_c = row_buf + c * row_h * W_out + col_offset;

                    for (int y = 0; y < row_h; y++) {
                        memcpy(dst_c + y * W_out,
                               src_c + y * pw,
                               col_w * sizeof(float));
                    }
                }

                col_offset += col_w;
            }

            /* 把该行写入最终输出 */
            /* Write the row into the final output */
            int dst_row_offset = 0;
            for (int jj = 0; jj < j; jj++) {
                dst_row_offset += ph - (jj != 0 ? padding : 0) - (jj != rows - 1 ? padding : 0);
            }

            for (int c = 0; c < C; c++) {
                memcpy(db + c * H_out * W_out + dst_row_offset * W_out,
                       row_buf + c * row_h * W_out,
                       row_h * W_out * sizeof(float));
            }

            free(row_buf);
        }
    }
}
