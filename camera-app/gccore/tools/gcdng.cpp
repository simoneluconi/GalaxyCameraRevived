// gcdng: M9MO .raw (+ .raw.txt sidecar from the HAL) to DNG, with gccore's writer.
// The command-line twin of tools/m9mo-dng.py, for testing the app's writer on a PC.
//
// Usage: gcdng IMG_x.raw [-o out.dng] [--preview isp.jpg]

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "gccore/dng.h"

namespace {

std::map<std::string, std::string> readSidecar(const std::string& path) {
    std::map<std::string, std::string> m;
    std::ifstream in(path);
    std::string line;
    while (std::getline(in, line)) {
        auto eq = line.find('=');
        if (eq != std::string::npos) m[line.substr(0, eq)] = line.substr(eq + 1);
    }
    return m;
}

int intOf(const std::map<std::string, std::string>& m, const char* k, int def = 0) {
    auto it = m.find(k);
    return it == m.end() ? def : std::atoi(it->second.c_str());
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s IMG_x.raw [-o out.dng]\n", argv[0]);
        return 2;
    }
    std::string in = argv[1];
    std::string out = in.substr(0, in.rfind('.')) + ".dng";
    std::string previewPath;
    for (int i = 2; i + 1 < argc; i++) {
        if (std::strcmp(argv[i], "-o") == 0) out = argv[++i];
        else if (std::strcmp(argv[i], "--preview") == 0) previewPath = argv[++i];
    }
    std::vector<uint8_t> preview;
    if (!previewPath.empty()) {
        std::ifstream pf(previewPath, std::ios::binary);
        preview.assign(std::istreambuf_iterator<char>(pf), std::istreambuf_iterator<char>());
    }

    std::ifstream f(in, std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    auto meta = readSidecar(in + ".txt");

    gccore::RawFrame raw;
    raw.data = data.data();
    raw.size = data.size();
    if (meta.count("raw-size")) std::sscanf(meta["raw-size"].c_str(), "%dx%d", &raw.width, &raw.height);
    if (meta.count("raw-active-area")) {
        std::sscanf(meta["raw-active-area"].c_str(), "%d,%d,%d,%d", &raw.left, &raw.top, &raw.right, &raw.bottom);
    }
    if (meta.count("raw-format") && !(raw.bits = gccore::formatBits(meta["raw-format"].c_str()))) {
        std::fprintf(stderr, "unsupported raw-format %s\n", meta["raw-format"].c_str());
        return 1;
    }
    raw.black = intOf(meta, "raw-black-level", 64);
    raw.white = intOf(meta, "raw-white-level", 1023);
    if (meta.count("raw-cfa") && !gccore::parseCfa(meta["raw-cfa"].c_str(), raw.cfa)) {
        std::fprintf(stderr, "bad raw-cfa %s\n", meta["raw-cfa"].c_str());
        return 1;
    }

    gccore::DngMeta dm;
    dm.software = "gccore gcdng";
    // The format tells the cameras apart, as in tools/m9mo-dng.py
    dm.model = raw.bits == 12 ? "EK-GC200" : "EK-GC100";
    dm.iso = intOf(meta, "exif-iso");
    if (int tv = intOf(meta, "exif-tv")) dm.exposureSeconds = std::pow(2.0, -tv / 100.0);
    if (int av = intOf(meta, "exif-av")) dm.fNumber = std::pow(2.0, av / 200.0);
    dm.focalMm = intOf(meta, "exif-fl") / 1000.0;
    dm.focal35mm = static_cast<int>(std::lround(intOf(meta, "exif-fl-35mm") / 100.0));
    dm.biasEv = intOf(meta, "exif-ebv") / 100.0;
    // IMG_YYYYMMDD_HHMMSS.raw
    auto slash = in.find_last_of("/\\");
    std::string base = in.substr(slash == std::string::npos ? 0 : slash + 1);
    int Y, M, D, h, m, s;
    if (std::sscanf(base.c_str(), "IMG_%4d%2d%2d_%2d%2d%2d", &Y, &M, &D, &h, &m, &s) == 6) {
        char buf[32];
        std::snprintf(buf, sizeof buf, "%04d:%02d:%02d %02d:%02d:%02d", Y, M, D, h, m, s);
        dm.dateTime = buf;
    }

    dm.previewJpeg = preview.empty() ? nullptr : preview.data();
    dm.previewJpegSize = preview.size();

    FILE* o = std::fopen(out.c_str(), "wb");
    std::string err;
    bool ok = gccore::writeDng(raw, dm, o, &err);
    if (o) std::fclose(o);
    if (!ok) {
        std::fprintf(stderr, "%s: %s\n", out.c_str(), err.c_str());
        return 1;
    }
    double n[3];
    gccore::estimateNeutral(raw, n);
    std::printf("%s: %dx%d, neutral %.3f,1,%.3f\n", out.c_str(), raw.width, raw.height, n[0], n[2]);
    return 0;
}
