#include "gccore/dng.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>
#include <utility>
#include <vector>

#include "gccore/lens.h"
#include "gccore/tiff.h"

namespace gccore {

namespace {

// The M9MO's shutter speeds (the HAL's shutter-speed-values) as exact fractions, so a
// 2^-TV readout lands on the nominal 1/250 and not on 1/251
const std::pair<uint32_t, uint32_t> kShutterSteps[] = {
    {16, 1}, {12, 1}, {8, 1}, {6, 1}, {4, 1}, {3, 1}, {2, 1}, {3, 2}, {1, 1}, {7, 10},
    {1, 2}, {1, 3}, {1, 4}, {1, 6}, {1, 8}, {1, 15}, {1, 20}, {1, 30}, {1, 45}, {1, 60},
    {1, 90}, {1, 125}, {1, 180}, {1, 250}, {1, 350}, {1, 500}, {1, 750}, {1, 1000},
    {1, 1500}, {1, 2000},
};

/** Degrees as EXIF degrees, minutes and milliseconds-precise seconds. */
std::vector<std::pair<uint32_t, uint32_t>> dms(double v) {
    const double a = std::fabs(v);
    const double d = std::floor(a);
    const double mf = (a - d) * 60;
    const double m = std::floor(mf);
    return {{static_cast<uint32_t>(d), 1},
            {static_cast<uint32_t>(m), 1},
            {static_cast<uint32_t>(std::lround((mf - m) * 60 * 1000)), 1000}};
}

/** The GPS IFD, tag for tag the same as the app's JPEG (JpegMeta.gpsIfd). */
void gpsIfd(const DngMeta& m, Ifd& gps) {
    gps.bytes(0, {2, 3, 0, 0});  // GPSVersionID
    gps.ascii(1, m.latitude >= 0 ? "N" : "S");
    gps.rationalsExact(2, dms(m.latitude));
    gps.ascii(3, m.longitude >= 0 ? "E" : "W");
    gps.rationalsExact(4, dms(m.longitude));
    if (m.hasAltitude) {
        gps.bytes(5, {static_cast<uint8_t>(m.altitude >= 0 ? 0 : 1)});
        gps.rationals(6, {std::fabs(m.altitude)}, 100);
    }
    const std::time_t t = static_cast<std::time_t>(m.gpsTimeMs / 1000);
    std::tm utc{};
#ifdef _WIN32
    gmtime_s(&utc, &t);
#else
    gmtime_r(&t, &utc);
#endif
    gps.rationalsExact(7, {{static_cast<uint32_t>(utc.tm_hour), 1},
                           {static_cast<uint32_t>(utc.tm_min), 1},
                           {static_cast<uint32_t>(utc.tm_sec), 1}});
    gps.ascii(18, "WGS-84");  // GPSMapDatum
    char date[11];
    std::strftime(date, sizeof date, "%Y:%m:%d", &utc);
    gps.ascii(29, date);  // GPSDateStamp
    if (m.gpsAccuracy > 0) gps.rationals(31, {m.gpsAccuracy}, 100);  // GPSHPositioningError
}

/**
 * ExposureTime as the JPEG has it (the app's JpegMeta): the nearest step when the time is within
 * 1/6 EV of it, as CameraCaps.nominalSeconds snaps; a time between steps (P/A) stays as read,
 * 1/N below 0.5 s and tenths above.
 */
std::pair<uint32_t, uint32_t> nominalShutter(double seconds) {
    auto best = kShutterSteps[0];
    double bestErr = 1e9;
    for (auto& s : kShutterSteps) {
        double err = std::fabs(std::log2(static_cast<double>(s.first) / s.second / seconds));
        if (err < bestErr) {
            bestErr = err;
            best = s;
        }
    }
    if (bestErr <= 1.0 / 6) return best;
    if (seconds < 0.5) return {1, static_cast<uint32_t>(std::lround(1 / seconds))};
    return {static_cast<uint32_t>(std::lround(seconds * 10)), 10};
}

// Lens: 4.1-86.1 mm, f/2.8-5.9
const std::vector<double> kLensSpec = {4.1, 86.1, 2.8, 5.9};

// The preview in IFD0: small enough to cost nothing, big enough for a gallery grid
constexpr int kThumbWidth = 256;

// DefaultCrop leaves a margin inside the active area for the demosaic
constexpr int kCropMargin = 8;

// XMP padding: 20 lines of 99 spaces, room to edit the packet in place
constexpr int kXmpPaddingLines = 20;

uint8_t srgbEncode(double v) {
    v = std::clamp(v, 0.0, 1.0);
    double e = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1 / 2.4) - 0.055;
    return static_cast<uint8_t>(std::lround(e * 255));
}

/** Averages one Bayer quad at (x, y) (even offsets from the active area) into r, g, b. */
void quad(const RawFrame& raw, int x, int y, double rgb[3]) {
    double sum[3] = {0, 0, 0};
    int n[3] = {0, 0, 0};
    for (int i = 0; i < 4; i++) {
        int c = raw.cfa[i];
        sum[c] += std::max(0.0, raw.at(x + (i & 1), y + (i >> 1)) - raw.black);
        n[c]++;
    }
    for (int c = 0; c < 3; c++) rgb[c] = n[c] ? sum[c] / n[c] : 0;
}

/** A quick white-balanced, auto-brightened sRGB preview from Bayer quads. */
std::vector<uint8_t> makeThumb(const RawFrame& raw, const double neutral[3], int tw, int th) {
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const double range = raw.white - raw.black;
    std::vector<double> lin(static_cast<size_t>(tw) * th * 3);
    double gSum = 0;
    for (int ty = 0; ty < th; ty++) {
        for (int tx = 0; tx < tw; tx++) {
            // Two quads per output pixel, spread across its footprint, to tame aliasing
            double acc[3] = {0, 0, 0};
            for (int k = 0; k < 2; k++) {
                int x = raw.left + (static_cast<int>((tx + 0.25 + 0.5 * k) * aw / tw) & ~1);
                int y = raw.top + (static_cast<int>((ty + 0.25 + 0.5 * k) * ah / th) & ~1);
                x = std::min(x, raw.right - 2);
                y = std::min(y, raw.bottom - 2);
                double rgb[3];
                quad(raw, x, y, rgb);
                for (int c = 0; c < 3; c++) acc[c] += rgb[c] / 2;
            }
            double* p = &lin[(static_cast<size_t>(ty) * tw + tx) * 3];
            p[0] = acc[0] / range / neutral[0];
            p[1] = acc[1] / range;
            p[2] = acc[2] / range / neutral[2];
            gSum += p[1];
        }
    }
    // Bring the average to mid grey, within reason: a preview, not a development
    double mean = gSum / (static_cast<double>(tw) * th);
    double gain = mean > 1e-6 ? std::clamp(0.18 / mean, 1.0, 8.0) : 1.0;
    std::vector<uint8_t> out(lin.size());
    for (size_t i = 0; i < lin.size(); i++) out[i] = srgbEncode(lin[i] * gain);
    return out;
}

bool writeAll(FILE* f, const void* p, size_t n) { return std::fwrite(p, 1, n, f) == n; }

/**
 * Camera Raw settings (the crs namespace Lightroom and Camera Raw read on import): turn on the
 * camera's lens profile (tools/gc100-lcp.py) and crop to what it leaves, like "Enable Profile
 * Corrections" + "Constrain Crop". The user can switch both off; the pixels are untouched.
 */
/** The Lightroom settings (crs:) of the lens profile and its crop, as rdf:Description attributes. */
std::string lensXmp(const RawFrame& raw, double focalMm, const std::string& model) {
    // The crop is in the profile-corrected image, i.e. the DefaultCrop image after correction
    const double w = raw.right - raw.left - 2 * kCropMargin;
    const double h = raw.bottom - raw.top - 2 * kCropMargin;
    const OpticalCentre centre = opticalCentreOf(model);
    double crop[4] = {0, 0, 1, 1};
    if (focalMm > 0) {
        ispFrameAfterProfile(lensAt(focalMm), w, h, centre.x - kCropMargin, centre.y - kCropMargin, crop);
    }
    char buf[1536];
    std::snprintf(buf, sizeof buf,
                  "    xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\"\n"
                  "   crs:HasSettings=\"True\"\n"
                  "   crs:LensProfileEnable=\"1\"\n"
                  // Auto: Lightroom picks the installed profile by camera and lens
                  "   crs:LensProfileSetup=\"Auto\"\n"
                  "   crs:LensProfileDistortionScale=\"100\"\n"
                  "   crs:LensProfileVignettingScale=\"100\"\n"
                  "   crs:LensProfileChromaticAberrationScale=\"100\"\n"
                  "   crs:LensProfileIsEmbedded=\"False\"\n"
                  "   crs:HasCrop=\"True\"\n"
                  "   crs:CropLeft=\"%.6f\"\n"
                  "   crs:CropTop=\"%.6f\"\n"
                  "   crs:CropRight=\"%.6f\"\n"
                  "   crs:CropBottom=\"%.6f\"\n"
                  "   crs:CropAngle=\"0\"\n"
                  "   crs:CropConstrainToWarp=\"0\"\n",
                  crop[0], crop[1], crop[2], crop[3]);
    return buf;
}

/**
 * The XMP packet: the lens settings, the app's own properties, and ~2 KB of padding, so the
 * gallery can patch the rating in place instead of rewriting 35 MB.
 */
std::string xmpPacket(const RawFrame& raw, const DngMeta& meta) {
    std::string x =
        "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
        " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
        "  <rdf:Description rdf:about=\"\"\n";
    if (meta.lensSettings) x += lensXmp(raw, meta.focalMm, meta.model);
    x += meta.xmpProperties.empty() ? "   xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n   xmp:Rating=\"0\"\n"
                                    : meta.xmpProperties;
    x += "  />\n </rdf:RDF>\n</x:xmpmeta>\n";
    for (int i = 0; i < kXmpPaddingLines; i++) x += std::string(99, ' ') + '\n';
    x += "<?xpacket end=\"w\"?>";
    return x;
}

}  // namespace

bool jpegSize(const uint8_t* d, size_t n, int* width, int* height) {
    if (n < 4 || d[0] != 0xff || d[1] != 0xd8) return false;
    size_t i = 2;
    while (i + 9 < n) {
        if (d[i] != 0xff) return false;
        const uint8_t marker = d[i + 1];
        if (marker == 0xff) {
            i++;  // fill byte
            continue;
        }
        const size_t len = (static_cast<size_t>(d[i + 2]) << 8) | d[i + 3];
        // SOF0..SOF15, except DHT (C4), JPG (C8) and DAC (CC)
        if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
            *height = (d[i + 5] << 8) | d[i + 6];
            *width = (d[i + 7] << 8) | d[i + 8];
            return *width > 0 && *height > 0;
        }
        i += 2 + len;
    }
    return false;
}

void estimateNeutral(const RawFrame& raw, double out[3]) {
    // Centre quarter of the active area, clear of the dark corners of the image circle;
    // even steps keep the CFA phase
    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const int x0 = raw.left + (aw / 4 & ~1), x1 = raw.left + (3 * aw / 4 & ~1);
    const int y0 = raw.top + (ah / 4 & ~1), y1 = raw.top + (3 * ah / 4 & ~1);
    double sum[3] = {0, 0, 0};
    long n = 0;
    for (int y = y0; y < y1; y += 8) {
        for (int x = x0; x < x1; x += 8) {
            double rgb[3];
            quad(raw, x, y, rgb);
            for (int c = 0; c < 3; c++) sum[c] += rgb[c];
            n++;
        }
    }
    if (n == 0 || sum[0] <= 0 || sum[1] <= 0 || sum[2] <= 0) {
        // A black frame (lens cap): nothing to estimate
        out[0] = out[1] = out[2] = 1.0;
        return;
    }
    out[0] = sum[0] / sum[1];
    out[1] = 1.0;
    out[2] = sum[2] / sum[1];
}

bool writeDng(const RawFrame& raw, const DngMeta& meta, FILE* f, std::string* error) {
    auto fail = [&](const char* why) {
        if (error) *error = why;
        return false;
    };
    if (!raw.valid()) return fail("invalid raw frame or layout");
    if (!f) return fail("no output");

    double neutral[3] = {meta.neutral[0], meta.neutral[1], meta.neutral[2]};
    if (neutral[0] <= 0 || neutral[1] <= 0 || neutral[2] <= 0) estimateNeutral(raw, neutral);

    const int aw = raw.right - raw.left;
    const int ah = raw.bottom - raw.top;
    const int tw = kThumbWidth;
    const int th = std::max(1, static_cast<int>(std::lround(static_cast<double>(tw) * ah / aw)));
    const std::vector<uint8_t> thumb = makeThumb(raw, neutral, tw, th);
    const uint32_t rawBytes = static_cast<uint32_t>(raw.width) * raw.height * 2;

    // IFD0: the preview, plus the camera-wide DNG tags
    Ifd ifd0;
    ifd0.longVal(254, 1);  // NewSubFileType: reduced-resolution preview
    ifd0.longVal(256, tw);
    ifd0.longVal(257, th);
    ifd0.shorts(258, {8, 8, 8});
    ifd0.shortVal(259, 1);
    ifd0.shortVal(262, 2);  // RGB
    ifd0.ascii(271, "Samsung");
    ifd0.ascii(272, meta.model);
    ifd0.longVal(273, 0);  // StripOffsets, set below
    ifd0.shortVal(274, static_cast<uint16_t>(std::clamp(meta.orientation, 1, 8)));
    ifd0.shortVal(277, 3);
    ifd0.longVal(278, th);
    ifd0.longVal(279, static_cast<uint32_t>(thumb.size()));
    ifd0.shortVal(284, 1);
    ifd0.ascii(305, meta.software);
    if (!meta.dateTime.empty()) ifd0.ascii(306, meta.dateTime);
    if (!meta.artist.empty()) ifd0.ascii(315, meta.artist);
    ifd0.longVal(330, 0);  // SubIFDs, set below
    if (!meta.copyright.empty()) ifd0.ascii(33432, meta.copyright);
    ifd0.longVal(34665, 0);  // Exif IFD, set below
    if (meta.hasGps) ifd0.longVal(34853, 0);  // GPS IFD, set below
    ifd0.bytes(50706, {1, 4, 0, 0});  // DNGVersion
    ifd0.bytes(50707, {1, 1, 0, 0});  // DNGBackwardVersion
    ifd0.ascii(50708, "Samsung " + meta.model);
    ifd0.srationals(50721, std::vector<double>(meta.colorMatrix, meta.colorMatrix + 9));
    ifd0.rationals(50728, {neutral[0], neutral[1], neutral[2]});
    ifd0.srationals(50730, {meta.baselineExposure}, 100);  // BaselineExposure
    ifd0.rationals(50736, kLensSpec, 10);  // LensInfo
    ifd0.shortVal(50778, static_cast<uint16_t>(meta.calibrationIlluminant));
    const std::string xmp = xmpPacket(raw, meta);
    ifd0.bytes(700, std::vector<uint8_t>(xmp.begin(), xmp.end()));  // XMLPacket

    // SubIFD: the CFA image
    Ifd sub;
    sub.longVal(254, 0);
    sub.longVal(256, raw.width);
    sub.longVal(257, raw.height);
    sub.shortVal(258, 16);
    sub.shortVal(259, 1);
    sub.shortVal(262, 32803);  // CFA
    sub.longVal(273, 0);  // StripOffsets, set below
    sub.shortVal(277, 1);
    sub.longVal(278, raw.height);
    sub.longVal(279, rawBytes);
    sub.shortVal(284, 1);
    sub.shorts(33421, {2, 2});
    sub.bytes(33422, {raw.cfa[0], raw.cfa[1], raw.cfa[2], raw.cfa[3]});
    sub.bytes(50710, {0, 1, 2});  // CFAPlaneColor
    sub.shortVal(50711, 1);  // CFALayout: rectangular
    sub.shorts(50713, {1, 1});  // BlackLevelRepeatDim
    sub.rationals(50714, {raw.black}, 100);
    sub.longVal(50717, static_cast<uint32_t>(std::lround(raw.white)));
    sub.longs(50719, {kCropMargin, kCropMargin});  // DefaultCropOrigin, in the active area
    sub.longs(50720, {static_cast<uint32_t>(aw - 2 * kCropMargin), static_cast<uint32_t>(ah - 2 * kCropMargin)});
    sub.longs(50829, {static_cast<uint32_t>(raw.top), static_cast<uint32_t>(raw.left),
                      static_cast<uint32_t>(raw.bottom), static_cast<uint32_t>(raw.right)});

    // Optional second SubIFD: the full-size JPEG preview
    int pw = 0, ph = 0;
    const bool hasPreview = meta.previewJpeg && meta.previewJpegSize > 0 &&
                            jpegSize(meta.previewJpeg, meta.previewJpegSize, &pw, &ph);
    Ifd prev;
    if (hasPreview) {
        prev.longVal(254, 1);  // reduced-resolution preview
        prev.longVal(256, pw);
        prev.longVal(257, ph);
        prev.shorts(258, {8, 8, 8});
        prev.shortVal(259, 7);  // JPEG
        prev.shortVal(262, 6);  // YCbCr
        prev.longVal(273, 0);  // StripOffsets, set below
        prev.shortVal(277, 3);
        prev.longVal(278, ph);
        prev.longVal(279, static_cast<uint32_t>(meta.previewJpegSize));
        prev.shortVal(284, 1);
        prev.longVal(50970, 2);  // PreviewColorSpace: sRGB (1 is Gray Gamma 2.2)
        ifd0.longs(330, {0, 0});  // SubIFDs: CFA image, preview
    }

    // Exif IFD
    Ifd exif;
    if (meta.exposureSeconds > 0) exif.rationalsExact(33434, {nominalShutter(meta.exposureSeconds)});
    if (meta.fNumber > 0) exif.rationals(33437, {meta.fNumber}, 10);
    if (meta.exposureProgram > 0) exif.shortVal(34850, static_cast<uint16_t>(meta.exposureProgram));
    if (meta.iso > 0) exif.shortVal(34855, static_cast<uint16_t>(meta.iso));
    exif.bytes(36864, {'0', '2', '3', '1'}, kUndefined);  // ExifVersion
    if (!meta.dateTime.empty()) {
        exif.ascii(36867, meta.dateTime);
        exif.ascii(36868, meta.dateTime);
    }
    if (!meta.offsetTime.empty()) exif.ascii(36881, meta.offsetTime);
    exif.srationals(37380, {meta.biasEv}, 100);
    if (meta.focalMm > 0) exif.rationals(37386, {meta.focalMm}, 100);
    if (meta.focal35mm > 0) exif.shortVal(41989, static_cast<uint16_t>(meta.focal35mm));
    exif.rationals(42034, kLensSpec, 10);  // LensSpecification
    exif.ascii(42035, "Samsung");
    exif.ascii(42036, "4.1-86.1mm f/2.8-5.9");

    Ifd gps;
    if (meta.hasGps) gpsIfd(meta, gps);

    // Layout: header, IFD0, SubIFD, [preview SubIFD], Exif IFD, [GPS IFD], thumbnail,
    // [JPEG preview], CFA data. Every value above has its final size already, so setting the
    // offsets below does not move anything.
    auto even = [](size_t v) { return static_cast<uint32_t>(v + (v & 1)); };
    const uint32_t ifd0Off = 8;
    const uint32_t subOff = even(ifd0Off + ifd0.size());
    const uint32_t prevOff = even(subOff + sub.size());
    const uint32_t exifOff = even(prevOff + (hasPreview ? prev.size() : 0));
    const uint32_t gpsOff = even(exifOff + exif.size());
    const uint32_t thumbOff = even(gpsOff + (meta.hasGps ? gps.size() : 0));
    const uint32_t jpegOff = even(thumbOff + thumb.size());
    const uint32_t rawOff = even(jpegOff + (hasPreview ? meta.previewJpegSize : 0));
    ifd0.longVal(273, thumbOff);
    if (hasPreview) {
        ifd0.longs(330, {subOff, prevOff});
        prev.longVal(273, jpegOff);
    } else {
        ifd0.longVal(330, subOff);
    }
    ifd0.longVal(34665, exifOff);
    if (meta.hasGps) ifd0.longVal(34853, gpsOff);
    sub.longVal(273, rawOff);

    std::vector<uint8_t> head = {'I', 'I', 42, 0};
    putU32(head, ifd0Off);
    auto append = [&](const std::vector<uint8_t>& b, uint32_t at) {
        head.resize(at, 0);
        head.insert(head.end(), b.begin(), b.end());
    };
    append(ifd0.pack(ifd0Off), ifd0Off);
    append(sub.pack(subOff), subOff);
    if (hasPreview) append(prev.pack(prevOff), prevOff);
    append(exif.pack(exifOff), exifOff);
    if (meta.hasGps) append(gps.pack(gpsOff), gpsOff);
    append(thumb, thumbOff);
    // The ~5 MB JPEG preview goes straight from the caller's buffer, not through [head]
    head.resize(hasPreview ? jpegOff : rawOff, 0);
    if (!writeAll(f, head.data(), head.size())) return fail("write failed (header)");
    if (hasPreview) {
        static const uint8_t kPad = 0;
        if (!writeAll(f, meta.previewJpeg, meta.previewJpegSize) ||
            (rawOff > jpegOff + meta.previewJpegSize && !writeAll(f, &kPad, 1))) {
            return fail("write failed (preview)");
        }
    }

    // The CFA image, one row at a time, as little-endian 16-bit
    std::vector<uint16_t> row(raw.width);
    const uint16_t probe = 1;
    const bool littleEndian = *reinterpret_cast<const uint8_t*>(&probe) == 1;
    for (int y = 0; y < raw.height; y++) {
        raw.unpackRow(y, row.data());
        if (!littleEndian) {
            for (auto& v : row) v = static_cast<uint16_t>((v >> 8) | (v << 8));
        }
        if (!writeAll(f, row.data(), row.size() * 2)) return fail("write failed (image)");
    }
    if (std::fflush(f) != 0) return fail("flush failed");
    return true;
}

}  // namespace gccore
