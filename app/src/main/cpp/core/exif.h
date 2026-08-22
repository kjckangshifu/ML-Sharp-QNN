/* exif.h — JPEG APP1 EXIF 解析 (Orientation + FocalLength), 无第三方依赖
 * exif.h — JPEG APP1 EXIF parsing (Orientation + FocalLength), no third-party dependencies
 */
#ifndef EXIF_H
#define EXIF_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    int orientation;     /* 1..8, 0 = no EXIF or no Orientation */
    double focal_len_mm; /* FocalLength 0x920A, 0 = none */
    double focal_len_35mm; /* FocalLengthIn35mmFilm 0xA405 / 0xA40C, 0 = none */
} ExifData;

/* 从 JPEG 字节流中解析 EXIF, 成功(找到 Exif APP1 段)返回 1, 否则 0
 * Parses EXIF from a JPEG byte stream; returns 1 on success (Exif APP1 segment found), 0 otherwise
 */
int exif_parse(const unsigned char *jpeg, size_t len, ExifData *out);

#ifdef __cplusplus
}
#endif

#endif /* EXIF_H */