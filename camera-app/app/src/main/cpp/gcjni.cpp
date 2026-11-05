// JNI bridge from the app to gccore.

#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <exception>
#include <string>

#include "gccore/dng.h"
#ifdef GCCORE_WITH_JPEG
#include "gccore/jpeg.h"
#endif

namespace {

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return {};
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

}  // namespace

/**
 * NativeDng.write: writes [raw] (the HAL's raw10be frame) as a DNG to [fd].
 * layout = {width, height, left, top, right, bottom}; levels = {black, white};
 * exposure = {iso, seconds, fNumber, focalMm, focal35mm, biasEv, program, orientation};
 * model = the TIFF Model ("EK-GC100"; null keeps that default);
 * xmp = the app's XMP properties (DngMeta::xmpProperties; may be null);
 * gps = {latitude, longitude, hasAltitude (0/1), altitude m, fix time ms UTC, accuracy m
 * (0 = unknown)}, or null for no geotag;
 * preview = the ISP JPEG to embed full size (may be null).
 * Returns null on success, or the error.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_simoneluconi_gccamera_raw_NativeDng_write(JNIEnv* env, jclass, jbyteArray raw, jintArray layout,
                                                   jdoubleArray levels, jstring cfa, jdoubleArray exposure,
                                                   jstring dateTime, jstring offsetTime, jstring software,
                                                   jstring artist, jstring copyright, jstring model,
                                                   jstring xmp, jdoubleArray gps, jbyteArray preview, jint fd) {
    jint l[6];
    env->GetIntArrayRegion(layout, 0, 6, l);
    jdouble lv[2];
    env->GetDoubleArrayRegion(levels, 0, 2, lv);
    jdouble ex[8];
    env->GetDoubleArrayRegion(exposure, 0, 8, ex);
    jdouble g[6] = {};
    if (gps) env->GetDoubleArrayRegion(gps, 0, 6, g);
    // A short array throws on the Java side; nothing below may run with garbage
    if (env->ExceptionCheck()) return nullptr;

    gccore::RawFrame frame;
    frame.width = l[0];
    frame.height = l[1];
    frame.left = l[2];
    frame.top = l[3];
    frame.right = l[4];
    frame.bottom = l[5];
    frame.black = lv[0];
    frame.white = lv[1];
    std::string cfaStr = str(env, cfa);
    if (!gccore::parseCfa(cfaStr.c_str(), frame.cfa)) return env->NewStringUTF("bad raw-cfa");

    gccore::DngMeta meta;
    meta.iso = static_cast<int>(ex[0]);
    meta.exposureSeconds = ex[1];
    meta.fNumber = ex[2];
    meta.focalMm = ex[3];
    meta.focal35mm = static_cast<int>(ex[4]);
    meta.biasEv = ex[5];
    meta.exposureProgram = static_cast<int>(ex[6]);
    meta.orientation = static_cast<int>(ex[7]);
    meta.dateTime = str(env, dateTime);
    meta.offsetTime = str(env, offsetTime);
    meta.software = str(env, software);
    meta.artist = str(env, artist);
    meta.copyright = str(env, copyright);
    if (model) meta.model = str(env, model);
    meta.xmpProperties = str(env, xmp);
    if (gps) {
        meta.hasGps = true;
        meta.latitude = g[0];
        meta.longitude = g[1];
        meta.hasAltitude = g[2] != 0;
        meta.altitude = g[3];
        meta.gpsTimeMs = static_cast<int64_t>(g[4]);
        meta.gpsAccuracy = g[5];
    }

    // A 20 MB array lives in ART's non-moving large-object space, so this does not copy
    jbyte* data = env->GetByteArrayElements(raw, nullptr);
    if (!data) return env->NewStringUTF("out of memory");
    frame.data = reinterpret_cast<const uint8_t*>(data);
    frame.size = static_cast<size_t>(env->GetArrayLength(raw));
    jbyte* jpeg = preview ? env->GetByteArrayElements(preview, nullptr) : nullptr;
    if (jpeg) {
        meta.previewJpeg = reinterpret_cast<const uint8_t*>(jpeg);
        meta.previewJpegSize = static_cast<size_t>(env->GetArrayLength(preview));
    }

    std::string error;
    bool ok = false;
    int own = dup(fd);
    FILE* f = own >= 0 ? fdopen(own, "wb") : nullptr;
    if (f) {
        // C++ exceptions (bad_alloc) must not cross into the VM: they would abort the app
        try {
#ifdef GCCORE_WITH_JPEG
            // The ISP's own white balance, read back from its JPEG (far better than grey world)
            if (meta.previewJpeg) {
                gccore::IspParams p;
                if (gccore::matchIsp(frame, meta.previewJpeg, meta.previewJpegSize,
                                     meta.focalMm > 0 ? meta.focalMm : 4.1, p)) {
                    for (int c = 0; c < 3; c++) meta.neutral[c] = p.neutral[c];
                }
            }
#endif
            // Large writes: fewer syscalls through the card's FUSE layer
            setvbuf(f, nullptr, _IOFBF, 512 * 1024);
            ok = gccore::writeDng(frame, meta, f, &error);
        } catch (const std::exception& e) {
            ok = false;
            error = e.what();
        }
        if (std::fclose(f) != 0 && ok) {
            ok = false;
            error = "close failed";
        }
    } else {
        if (own >= 0) close(own);
        error = "cannot open the output";
    }
    if (jpeg) env->ReleaseByteArrayElements(preview, jpeg, JNI_ABORT);
    env->ReleaseByteArrayElements(raw, data, JNI_ABORT);
    return ok ? nullptr : env->NewStringUTF(error.c_str());
}
