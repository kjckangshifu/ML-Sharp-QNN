/* exif.c — JPEG APP1 EXIF 解析
 *
 * MM, 42, IFD0 偏移)
 *       IFD0: Orientation(0x0112) / ExifOffset(0x8769)
 *       ExifSubIFD: FocalLength(0x920A) / FocalLengthIn35mmFilm(0xA405, 0xA40C)
 */
#include "exif.h"
#include <string.h>

static unsigned r16(const unsigned char *p, int le)
{
    return le ? (unsigned)(p[0] | p[1] << 8)
              : (unsigned)(p[0] << 8 | p[1]);
}

static unsigned r32(const unsigned char *p, int le)
{
    return le ? (unsigned)(p[0] | p[1] << 8 | p[2] << 16 | p[3] << 24)
              : ((unsigned)p[0] << 24 | (unsigned)p[1] << 16 | (unsigned)p[2] << 8 | p[3]);
}

static double rrational(const unsigned char *p, int le)
{
    unsigned n = r32(p, le), d = r32(p + 4, le);
    return d ? (double)n / d : 0.0;
}

static double rdouble(const unsigned char *p, int le)
{
    unsigned long long b = 0;
    double v;
    for (int k = 0; k < 8; k++)
        b = (b << 8) | (le ? p[7 - k] : p[k]);
    memcpy(&v, &b, sizeof v);
    return v;
}

static void parse_ifd(const unsigned char *p, size_t size, unsigned off, int le,
                      ExifData *out, int depth)
{
    if (depth > 4 || off + 2 > size)
        return;
    unsigned n = r16(p + off, le);
    unsigned base = off + 2;
    if (n > 512 || base + (size_t)n * 12 > size)
        return;

    for (unsigned e = 0; e < n; e++) {
        const unsigned char *ep = p + base + (size_t)e * 12;
        unsigned tag = r16(ep, le), type = r16(ep + 2, le);
        unsigned cnt = r32(ep + 4, le);
        unsigned uval = 0;
        double dval = 0.0;
        int have_u = 0, have_d = 0;

        if (type == 3 && cnt == 1) { uval = r16(ep + 8, le); have_u = 1; }
        else if (type == 4 && cnt == 1) { uval = r32(ep + 8, le); have_u = 1; }
        else if (type == 1 && cnt == 1) { uval = ep[8]; have_u = 1; }
        else if (type == 5 && cnt == 1) {
            unsigned vo = r32(ep + 8, le);
            if (vo + 8 <= size) { dval = rrational(p + vo, le); have_d = 1; }
        }
        else if (type == 12 && cnt == 1) { /* DOUBLE: Pillow 写焦距用 */
            unsigned vo = r32(ep + 8, le);
            if (vo + 8 <= size) { dval = rdouble(p + vo, le); have_d = 1; }
        }

        if (depth == 0) {
            if (tag == 0x0112 && have_u)
                out->orientation = (int)uval;
            else if (tag == 0x8769 && have_u)
                parse_ifd(p, size, uval, le, out, depth + 1);
        } else if (depth == 1) {
            if (tag == 0x920A && have_d)
                out->focal_len_mm = dval;
            else if (tag == 0xA405) {
                /* FocalLengthIn35mmFilm: EXIF spec says SHORT (type=3),
                   But some cameras/software write it as LONG/RATIONAL, so we handle all numeric types.
                   Fix: the original code only read when have_d (RATIONAL/DOUBLE),
                   causing standard SHORT type to be ignored, falling back to FocalLength or 30mm default. */
                if (have_u)
                    out->focal_len_35mm = (double)uval;
                else if (have_d)
                    out->focal_len_35mm = dval;
            }
            else if (tag == 0xA40C && have_d) {
                if (out->focal_len_35mm <= 0.0)
                    out->focal_len_35mm = dval;
            }
        }
    }
}

int exif_parse(const unsigned char *jpeg, size_t len, ExifData *out)
{
    memset(out, 0, sizeof *out);
    if (!jpeg || len < 4 || jpeg[0] != 0xFF || jpeg[1] != 0xD8)
        return 0;

    size_t i = 2;
    while (i + 4 <= len) {
        if (jpeg[i] != 0xFF) { i++; continue; }
        unsigned marker = jpeg[i + 1];
        if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) { i += 2; continue; }
        if (marker == 0xDA || marker == 0xD9) break;
        unsigned seglen = r16(jpeg + i + 2, 0); /* JPEG 段长度固定大端 */
        if (seglen < 2) break;
        if (marker == 0xE1 && seglen >= 8 && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x' &&
            jpeg[i + 6] == 'i' && jpeg[i + 7] == 'f' && jpeg[i + 8] == 0 && jpeg[i + 9] == 0) {
            const unsigned char *tiff = jpeg + i + 10;
            size_t tsize = seglen - 2 - 6;
            if (tsize >= 8) {
                int le = (tiff[0] == 'I' && tiff[1] == 'I');
                int be = (tiff[0] == 'M' && tiff[1] == 'M');
                if ((le || be) && r16(tiff + 2, le) == 42) {
                    unsigned ifd0 = r32(tiff + 4, le);
                    parse_ifd(tiff, tsize, ifd0, le, out, 0);
                }
            }
            return 1;
        }
        i += 2 + seglen;
    }
    return 0;
}