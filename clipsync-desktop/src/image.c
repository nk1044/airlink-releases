#define COBJMACROS
#include "image.h"
#include <wincodec.h>

#define RELEASE(p) do { if (p) { (p)->lpVtbl->Release(p); (p) = NULL; } } while (0)

typedef struct {
    IWICImagingFactory *factory;
    IWICStream *stream;
    IWICBitmapDecoder *decoder;
    IWICBitmapFrameDecode *frame;
} Decoded;

static void decoded_free(Decoded *d)
{
    RELEASE(d->frame);
    RELEASE(d->decoder);
    RELEASE(d->stream);
    RELEASE(d->factory);
}

static BOOL decode(const BYTE *data, size_t len, Decoded *d)
{
    memset(d, 0, sizeof *d);
    if (len > 0xFFFFFFFFu ||
        FAILED(CoCreateInstance(&CLSID_WICImagingFactory, NULL, CLSCTX_INPROC_SERVER, &IID_IWICImagingFactory,
                                (void **)&d->factory)) ||
        FAILED(IWICImagingFactory_CreateStream(d->factory, &d->stream)) ||
        FAILED(IWICStream_InitializeFromMemory(d->stream, (BYTE *)data, (DWORD)len)) ||
        FAILED(IWICImagingFactory_CreateDecoderFromStream(d->factory, (IStream *)d->stream, NULL,
                                                          WICDecodeMetadataCacheOnDemand, &d->decoder)) ||
        FAILED(IWICBitmapDecoder_GetFrame(d->decoder, 0, &d->frame))) {
        decoded_free(d);
        return FALSE;
    }
    return TRUE;
}

/* Encodes src as PNG or JPEG (quality > 0) into a malloc'd buffer. */
static BOOL encode(IWICImagingFactory *f, IWICBitmapSource *src, const GUID *container, float quality,
                   BYTE **out, size_t *out_len)
{
    IStream *st = NULL;
    IWICBitmapEncoder *enc = NULL;
    IWICBitmapFrameEncode *fe = NULL;
    IPropertyBag2 *bag = NULL;
    BOOL ok = FALSE;
    UINT w, h;
    WICPixelFormatGUID pf;

    if (FAILED(CreateStreamOnHGlobal(NULL, TRUE, &st)) ||
        FAILED(IWICImagingFactory_CreateEncoder(f, container, NULL, &enc)) ||
        FAILED(IWICBitmapEncoder_Initialize(enc, st, WICBitmapEncoderNoCache)) ||
        FAILED(IWICBitmapEncoder_CreateNewFrame(enc, &fe, &bag)))
        goto done;
    if (quality > 0) {
        PROPBAG2 opt = { 0 };
        VARIANT v;
        memset(&v, 0, sizeof v);
        opt.pstrName = L"ImageQuality";
        v.vt = VT_R4;
        v.fltVal = quality;
        IPropertyBag2_Write(bag, 1, &opt, &v);
    }
    if (FAILED(IWICBitmapFrameEncode_Initialize(fe, bag)) ||
        FAILED(IWICBitmapSource_GetSize(src, &w, &h)) ||
        FAILED(IWICBitmapFrameEncode_SetSize(fe, w, h)) ||
        FAILED(IWICBitmapSource_GetPixelFormat(src, &pf)) ||
        FAILED(IWICBitmapFrameEncode_SetPixelFormat(fe, &pf)) ||
        FAILED(IWICBitmapFrameEncode_WriteSource(fe, src, NULL)) || /* converts the pixel format if needed */
        FAILED(IWICBitmapFrameEncode_Commit(fe)) ||
        FAILED(IWICBitmapEncoder_Commit(enc)))
        goto done;

    HGLOBAL hg;
    STATSTG ss;
    if (FAILED(GetHGlobalFromStream(st, &hg)) || FAILED(IStream_Stat(st, &ss, STATFLAG_NONAME))) goto done;
    size_t n = (size_t)ss.cbSize.QuadPart;
    void *p = GlobalLock(hg);
    if (p && (*out = malloc(n ? n : 1))) {
        memcpy(*out, p, n);
        *out_len = n;
        ok = TRUE;
    }
    if (p) GlobalUnlock(hg);
done:
    RELEASE(bag);
    RELEASE(fe);
    RELEASE(enc);
    RELEASE(st);
    return ok;
}

BOOL img_is_png(const BYTE *data, size_t len)
{
    return len >= 8 && !memcmp(data, "\x89PNG\r\n\x1a\n", 8);
}

BOOL img_dib_to_png(const BYTE *dib, size_t len, BYTE **out, size_t *out_len)
{
    if (len < sizeof(BITMAPINFOHEADER)) return FALSE;
    const BITMAPINFOHEADER *bi = (const BITMAPINFOHEADER *)dib;
    if (bi->biSize < sizeof(BITMAPINFOHEADER) || bi->biSize > len) return FALSE;

    /* Wrap the DIB in a BMP file header and let WIC's BMP decoder handle every variant. */
    DWORD colors = bi->biClrUsed ? bi->biClrUsed : (bi->biBitCount <= 8 ? 1u << bi->biBitCount : 0);
    DWORD masks = bi->biSize == sizeof(BITMAPINFOHEADER) && bi->biCompression == BI_BITFIELDS ? 12 : 0;
    BITMAPFILEHEADER fh = { 0 };
    size_t total = sizeof fh + len;
    fh.bfType = 0x4D42; /* "BM" */
    fh.bfSize = (DWORD)total;
    fh.bfOffBits = (DWORD)(sizeof fh + bi->biSize + masks + colors * 4);
    BYTE *bmp = malloc(total);
    if (!bmp) return FALSE;
    memcpy(bmp, &fh, sizeof fh);
    memcpy(bmp + sizeof fh, dib, len);

    Decoded d;
    BOOL ok = FALSE;
    if (decode(bmp, total, &d)) {
        ok = encode(d.factory, (IWICBitmapSource *)d.frame, &GUID_ContainerFormatPng, 0, out, out_len);
        decoded_free(&d);
    }
    free(bmp);
    return ok;
}

BOOL img_to_dib(const BYTE *data, size_t len, HGLOBAL *dib)
{
    Decoded d;
    IWICFormatConverter *conv = NULL;
    BOOL ok = FALSE;
    UINT w, h;

    if (!decode(data, len, &d)) return FALSE;
    if (FAILED(IWICImagingFactory_CreateFormatConverter(d.factory, &conv)) ||
        FAILED(IWICFormatConverter_Initialize(conv, (IWICBitmapSource *)d.frame, &GUID_WICPixelFormat32bppPBGRA,
                                              WICBitmapDitherTypeNone, NULL, 0.0, WICBitmapPaletteTypeCustom)) ||
        FAILED(IWICFormatConverter_GetSize(conv, &w, &h)) || !w || !h || (UINT64)w * h > 100000000ULL)
        goto done;

    UINT stride = w * 4, size = stride * h;
    HGLOBAL g = GlobalAlloc(GMEM_MOVEABLE, sizeof(BITMAPINFOHEADER) + size);
    if (!g) goto done;
    BYTE *p = GlobalLock(g);
    BITMAPINFOHEADER *bi = (BITMAPINFOHEADER *)p;
    BYTE *px = p + sizeof *bi;
    memset(bi, 0, sizeof *bi);
    bi->biSize = sizeof *bi;
    bi->biWidth = (LONG)w;
    bi->biHeight = (LONG)h; /* bottom-up: what most apps expect from CF_DIB */
    bi->biPlanes = 1;
    bi->biBitCount = 32;
    bi->biCompression = BI_RGB;
    bi->biSizeImage = size;

    if (SUCCEEDED(IWICFormatConverter_CopyPixels(conv, NULL, stride, size, px))) {
        /* Flatten premultiplied alpha onto white, since many apps ignore DIB alpha. */
        for (UINT i = 0; i < size; i += 4) {
            BYTE inv = (BYTE)(255 - px[i + 3]);
            px[i] = (BYTE)(px[i] + inv);
            px[i + 1] = (BYTE)(px[i + 1] + inv);
            px[i + 2] = (BYTE)(px[i + 2] + inv);
            px[i + 3] = 255;
        }
        /* WIC rows are top-down; flip them. */
        BYTE *tmp = malloc(stride);
        if (tmp) {
            for (UINT y = 0; y < h / 2; y++) {
                BYTE *a = px + (size_t)y * stride, *b = px + (size_t)(h - 1 - y) * stride;
                memcpy(tmp, a, stride);
                memcpy(a, b, stride);
                memcpy(b, tmp, stride);
            }
            free(tmp);
            ok = TRUE;
        }
    }
    GlobalUnlock(g);
    if (ok) *dib = g;
    else GlobalFree(g);
done:
    RELEASE(conv);
    decoded_free(&d);
    return ok;
}

/* Re-encodes as JPEG, shrinking the image until it fits in max bytes (used for the Bluetooth cap). */
BOOL img_fit_jpeg(const BYTE *data, size_t len, size_t max, BYTE **out, size_t *out_len)
{
    Decoded d;
    UINT w, h;
    BOOL ok = FALSE;
    if (!decode(data, len, &d)) return FALSE;
    if (FAILED(IWICBitmapFrameDecode_GetSize(d.frame, &w, &h))) goto done;

    double scale = 1.0;
    for (int attempt = 0; attempt < 8 && !ok; attempt++, scale *= 0.7) {
        IWICBitmapSource *src = (IWICBitmapSource *)d.frame;
        IWICBitmapScaler *sc = NULL;
        if (scale < 1.0) {
            UINT sw = (UINT)(w * scale), sh = (UINT)(h * scale);
            if (sw < 16 || sh < 16 ||
                FAILED(IWICImagingFactory_CreateBitmapScaler(d.factory, &sc)) ||
                FAILED(IWICBitmapScaler_Initialize(sc, src, sw, sh, WICBitmapInterpolationModeFant))) {
                RELEASE(sc);
                break;
            }
            src = (IWICBitmapSource *)sc;
        }
        BYTE *jpg;
        size_t jl;
        if (encode(d.factory, src, &GUID_ContainerFormatJpeg, 0.85f, &jpg, &jl)) {
            if (jl <= max) {
                *out = jpg;
                *out_len = jl;
                ok = TRUE;
            } else {
                free(jpg);
            }
        }
        RELEASE(sc);
    }
done:
    decoded_free(&d);
    return ok;
}
