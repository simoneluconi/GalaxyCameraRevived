// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * RCD demosaicing, ported to gccore from librtprocess (RawTherapee), src/demosaic/rcd.cc,
 * commit 9a858270 (2023-06-27).
 *
 *  Copyright (c) 2017-2018 Luis Sanz Rodriguez (luis.sanz.rodriguez(at)gmail(dot)com) and
 *  Ingo Weyrich (heckflosse67@gmx.de). Tuned with Hanno Schwalm (hanno@schwalm-bremen.de).
 *
 *  RATIO CORRECTED DEMOSAICING, release 2.3 @ 171125, https://github.com/LuisSR/RCD-Demosaicing
 *
 *  This program is free software: you can redistribute it and/or modify it under the terms of
 *  the GNU General Public License as published by the Free Software Foundation, either version
 *  3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY
 *  WARRANTY; see <https://www.gnu.org/licenses/>.
 *
 * Changes for gccore: plain float planes in and out (0..1), no librtprocess helpers, tiles
 * shared out with std::thread instead of OpenMP, and a bilinear border instead of
 * bayerborder_demosaic. The algorithm itself is unchanged.
 */
#include "gccore/demosaic.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <thread>
#include <vector>

namespace gccore {

namespace {

constexpr int kTileBorder = 9;  // avoid tile-overlap errors
constexpr int kRcdBorder = 9;
constexpr int kTileSize = 194;
constexpr int kTileSizeN = kTileSize - 2 * kTileBorder;

inline float sqr(float x) { return x * x; }
/** RawTherapee's intp: a * b + (1 - a) * c. */
inline float intp(float a, float b, float c) { return a * (b - c) + c; }

struct Tiles {
    std::vector<float> cfa, rgb, vhDir, pqDir, pHpf, qHpf;
    Tiles()
        : cfa(kTileSize * kTileSize),
          rgb(3 * kTileSize * kTileSize),
          vhDir(kTileSize * kTileSize),
          pqDir(kTileSize * kTileSize / 2),
          pHpf(kTileSize * kTileSize / 2),
          qHpf(kTileSize * kTileSize / 2) {}
};

void rcdTile(int width, int height, const float* in, const int pattern[4], float* const out[3], int tr, int tc,
             int numTh, int numTw, Tiles& t) {
    auto fc = [&](int row, int col) { return pattern[(row & 1) * 2 + (col & 1)]; };
    constexpr int w1 = kTileSize, w2 = 2 * kTileSize, w3 = 3 * kTileSize, w4 = 4 * kTileSize;
    constexpr float eps = 1e-5f;
    constexpr float epssq = 1e-10f;

    const int rowStart = tr * kTileSizeN;
    const int rowEnd = std::min(rowStart + kTileSize, height);
    if (rowStart + kRcdBorder == rowEnd - kRcdBorder) return;
    const int colStart = tc * kTileSizeN;
    const int colEnd = std::min(colStart + kTileSize, width);
    if (colStart + kRcdBorder == colEnd - kRcdBorder) return;
    const int tileRows = std::min(rowEnd - rowStart, kTileSize);
    const int tilecols = std::min(colEnd - colStart, kTileSize);

    float* const cfa = t.cfa.data();
    float* const rgb[3] = {t.rgb.data(), t.rgb.data() + kTileSize * kTileSize, t.rgb.data() + 2 * kTileSize * kTileSize};
    float* const VH_Dir = t.vhDir.data();
    float* const PQ_Dir = t.pqDir.data();
    float* const lpf = PQ_Dir;  // reuse buffer, they don't overlap in usage
    float* const P_CDiff_Hpf = t.pHpf.data();
    float* const Q_CDiff_Hpf = t.qHpf.data();

    for (int row = rowStart; row < rowEnd; row++) {
        const int c0 = fc(row, colStart);
        const int c1 = fc(row, colStart + 1);
        for (int col = colStart, indx = (row - rowStart) * kTileSize; col < colEnd; ++col, ++indx) {
            const float v = std::clamp(in[static_cast<size_t>(row) * width + col], 0.0f, 1.0f);
            cfa[indx] = rgb[c0][indx] = rgb[c1][indx] = v;
        }
    }

    // Step 1: Find cardinal and diagonal interpolation directions
    float bufferV[3][kTileSize - 8];

    // Step 1.1: Calculate the square of the vertical and horizontal color difference high pass filter
    for (int row = 3; row < std::min(tileRows - 3, 5); ++row) {
        for (int col = 4, indx = row * kTileSize + col; col < tilecols - 4; ++col, ++indx) {
            bufferV[row - 3][col - 4] = sqr((cfa[indx - w3] - cfa[indx - w1] - cfa[indx + w1] + cfa[indx + w3]) -
                                            3.f * (cfa[indx - w2] + cfa[indx + w2]) + 6.f * cfa[indx]);
        }
    }

    // Step 1.2: Obtain the vertical and horizontal directional discrimination strength
    float bufferH[kTileSize - 6];
    float* V0 = bufferV[0];
    float* V1 = bufferV[1];
    float* V2 = bufferV[2];
    for (int row = 4; row < tileRows - 4; ++row) {
        for (int col = 3, indx = row * kTileSize + col; col < tilecols - 3; ++col, ++indx) {
            bufferH[col - 3] = sqr((cfa[indx - 3] - cfa[indx - 1] - cfa[indx + 1] + cfa[indx + 3]) -
                                   3.f * (cfa[indx - 2] + cfa[indx + 2]) + 6.f * cfa[indx]);
        }
        for (int col = 4, indx = (row + 1) * kTileSize + col; col < tilecols - 4; ++col, ++indx) {
            V2[col - 4] = sqr((cfa[indx - w3] - cfa[indx - w1] - cfa[indx + w1] + cfa[indx + w3]) -
                              3.f * (cfa[indx - w2] + cfa[indx + w2]) + 6.f * cfa[indx]);
        }
        for (int col = 4, indx = row * kTileSize + col; col < tilecols - 4; ++col, ++indx) {
            float V_Stat = std::max(epssq, V0[col - 4] + V1[col - 4] + V2[col - 4]);
            float H_Stat = std::max(epssq, bufferH[col - 4] + bufferH[col - 3] + bufferH[col - 2]);
            VH_Dir[indx] = V_Stat / (V_Stat + H_Stat);
        }
        // rotate pointers from row0, row1, row2 to row1, row2, row0
        std::swap(V0, V2);
        std::swap(V0, V1);
    }

    // Step 2: Low pass filter incorporating green, red and blue local samples from the raw data
    for (int row = 2; row < tileRows - 2; ++row) {
        for (int col = 2 + (fc(row, 0) & 1), indx = row * kTileSize + col, lpindx = indx / 2; col < tilecols - 2;
             col += 2, indx += 2, ++lpindx) {
            lpf[lpindx] = cfa[indx] + 0.5f * (cfa[indx - w1] + cfa[indx + w1] + cfa[indx - 1] + cfa[indx + 1]) +
                          0.25f * (cfa[indx - w1 - 1] + cfa[indx - w1 + 1] + cfa[indx + w1 - 1] + cfa[indx + w1 + 1]);
        }
    }

    // Step 3: Populate the green channel at blue and red CFA positions
    for (int row = 4; row < tileRows - 4; ++row) {
        for (int col = 4 + (fc(row, 0) & 1), indx = row * kTileSize + col, lpindx = indx / 2; col < tilecols - 4;
             col += 2, indx += 2, ++lpindx) {
            // Cardinal gradients
            const float cfai = cfa[indx];
            const float N_Grad = eps + (std::fabs(cfa[indx - w1] - cfa[indx + w1]) + std::fabs(cfai - cfa[indx - w2])) +
                                 (std::fabs(cfa[indx - w1] - cfa[indx - w3]) + std::fabs(cfa[indx - w2] - cfa[indx - w4]));
            const float S_Grad = eps + (std::fabs(cfa[indx - w1] - cfa[indx + w1]) + std::fabs(cfai - cfa[indx + w2])) +
                                 (std::fabs(cfa[indx + w1] - cfa[indx + w3]) + std::fabs(cfa[indx + w2] - cfa[indx + w4]));
            const float W_Grad = eps + (std::fabs(cfa[indx - 1] - cfa[indx + 1]) + std::fabs(cfai - cfa[indx - 2])) +
                                 (std::fabs(cfa[indx - 1] - cfa[indx - 3]) + std::fabs(cfa[indx - 2] - cfa[indx - 4]));
            const float E_Grad = eps + (std::fabs(cfa[indx - 1] - cfa[indx + 1]) + std::fabs(cfai - cfa[indx + 2])) +
                                 (std::fabs(cfa[indx + 1] - cfa[indx + 3]) + std::fabs(cfa[indx + 2] - cfa[indx + 4]));

            // Cardinal pixel estimations
            const float lpfi = lpf[lpindx];
            const float N_Est = cfa[indx - w1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx - w1]);
            const float S_Est = cfa[indx + w1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx + w1]);
            const float W_Est = cfa[indx - 1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx - 1]);
            const float E_Est = cfa[indx + 1] * (lpfi + lpfi) / (eps + lpfi + lpf[lpindx + 1]);

            // Vertical and horizontal estimations
            const float V_Est = (S_Grad * N_Est + N_Grad * S_Est) / (N_Grad + S_Grad);
            const float H_Est = (W_Grad * E_Est + E_Grad * W_Est) / (E_Grad + W_Grad);

            // G@B and G@R interpolation
            // Refined vertical and horizontal local discrimination
            const float VH_Central_Value = VH_Dir[indx];
            const float VH_Neighbourhood_Value =
                0.25f * ((VH_Dir[indx - w1 - 1] + VH_Dir[indx - w1 + 1]) + (VH_Dir[indx + w1 - 1] + VH_Dir[indx + w1 + 1]));

            const float VH_Disc = std::fabs(0.5f - VH_Central_Value) < std::fabs(0.5f - VH_Neighbourhood_Value)
                                      ? VH_Neighbourhood_Value
                                      : VH_Central_Value;
            rgb[1][indx] = intp(VH_Disc, H_Est, V_Est);
        }
    }

    // Step 4.0: Calculate the square of the P/Q diagonals color difference high pass filter
    for (int row = 3; row < tileRows - 3; ++row) {
        for (int col = 3, indx = row * kTileSize + col, indx2 = indx / 2; col < tilecols - 3; col += 2, indx += 2, indx2++) {
            P_CDiff_Hpf[indx2] = sqr((cfa[indx - w3 - 3] - cfa[indx - w1 - 1] - cfa[indx + w1 + 1] + cfa[indx + w3 + 3]) -
                                     3.f * (cfa[indx - w2 - 2] + cfa[indx + w2 + 2]) + 6.f * cfa[indx]);
            Q_CDiff_Hpf[indx2] = sqr((cfa[indx - w3 + 3] - cfa[indx - w1 + 1] - cfa[indx + w1 - 1] + cfa[indx + w3 - 3]) -
                                     3.f * (cfa[indx - w2 + 2] + cfa[indx + w2 - 2]) + 6.f * cfa[indx]);
        }
    }

    // Step 4.1: Obtain the P/Q diagonals directional discrimination strength
    for (int row = 4; row < tileRows - 4; ++row) {
        for (int col = 4 + (fc(row, 0) & 1), indx = row * kTileSize + col, indx2 = indx / 2, indx3 = (indx - w1 - 1) / 2,
                 indx4 = (indx + w1 - 1) / 2;
             col < tilecols - 4; col += 2, indx += 2, indx2++, indx3++, indx4++) {
            float P_Stat = std::max(epssq, P_CDiff_Hpf[indx3] + P_CDiff_Hpf[indx2] + P_CDiff_Hpf[indx4 + 1]);
            float Q_Stat = std::max(epssq, Q_CDiff_Hpf[indx3 + 1] + Q_CDiff_Hpf[indx2] + Q_CDiff_Hpf[indx4]);
            PQ_Dir[indx2] = P_Stat / (P_Stat + Q_Stat);
        }
    }

    // Step 4.2: Populate the red and blue channels at blue and red CFA positions
    for (int row = 4; row < tileRows - 4; ++row) {
        for (int col = 4 + (fc(row, 0) & 1), indx = row * kTileSize + col, c = 2 - fc(row, col), pqindx = indx / 2,
                 pqindx2 = (indx - w1 - 1) / 2, pqindx3 = (indx + w1 - 1) / 2;
             col < tilecols - 4; col += 2, indx += 2, ++pqindx, ++pqindx2, ++pqindx3) {
            // Refined P/Q diagonal local discrimination
            float PQ_Central_Value = PQ_Dir[pqindx];
            float PQ_Neighbourhood_Value = 0.25f * (PQ_Dir[pqindx2] + PQ_Dir[pqindx2 + 1] + PQ_Dir[pqindx3] + PQ_Dir[pqindx3 + 1]);

            float PQ_Disc = (std::fabs(0.5f - PQ_Central_Value) < std::fabs(0.5f - PQ_Neighbourhood_Value)) ? PQ_Neighbourhood_Value
                                                                                                         : PQ_Central_Value;

            // Diagonal gradients
            float NW_Grad = eps + std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx + w1 + 1]) +
                            std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx - w3 - 3]) + std::fabs(rgb[1][indx] - rgb[1][indx - w2 - 2]);
            float NE_Grad = eps + std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx + w1 - 1]) +
                            std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx - w3 + 3]) + std::fabs(rgb[1][indx] - rgb[1][indx - w2 + 2]);
            float SW_Grad = eps + std::fabs(rgb[c][indx - w1 + 1] - rgb[c][indx + w1 - 1]) +
                            std::fabs(rgb[c][indx + w1 - 1] - rgb[c][indx + w3 - 3]) + std::fabs(rgb[1][indx] - rgb[1][indx + w2 - 2]);
            float SE_Grad = eps + std::fabs(rgb[c][indx - w1 - 1] - rgb[c][indx + w1 + 1]) +
                            std::fabs(rgb[c][indx + w1 + 1] - rgb[c][indx + w3 + 3]) + std::fabs(rgb[1][indx] - rgb[1][indx + w2 + 2]);

            // Diagonal colour differences
            float NW_Est = rgb[c][indx - w1 - 1] - rgb[1][indx - w1 - 1];
            float NE_Est = rgb[c][indx - w1 + 1] - rgb[1][indx - w1 + 1];
            float SW_Est = rgb[c][indx + w1 - 1] - rgb[1][indx + w1 - 1];
            float SE_Est = rgb[c][indx + w1 + 1] - rgb[1][indx + w1 + 1];

            // P/Q estimations
            float P_Est = (NW_Grad * SE_Est + SE_Grad * NW_Est) / (NW_Grad + SE_Grad);
            float Q_Est = (NE_Grad * SW_Est + SW_Grad * NE_Est) / (NE_Grad + SW_Grad);

            // R@B and B@R interpolation
            rgb[c][indx] = rgb[1][indx] + intp(PQ_Disc, Q_Est, P_Est);
        }
    }

    // Step 4.3: Populate the red and blue channels at green CFA positions
    for (int row = 4; row < tileRows - 4; ++row) {
        for (int col = 4 + (fc(row, 1) & 1), indx = row * kTileSize + col; col < tilecols - 4; col += 2, indx += 2) {
            // Refined vertical and horizontal local discrimination
            float VH_Central_Value = VH_Dir[indx];
            float VH_Neighbourhood_Value =
                0.25f * ((VH_Dir[indx - w1 - 1] + VH_Dir[indx - w1 + 1]) + (VH_Dir[indx + w1 - 1] + VH_Dir[indx + w1 + 1]));

            float VH_Disc = (std::fabs(0.5f - VH_Central_Value) < std::fabs(0.5f - VH_Neighbourhood_Value)) ? VH_Neighbourhood_Value
                                                                                                         : VH_Central_Value;
            float rgb1 = rgb[1][indx];
            float N1 = eps + std::fabs(rgb1 - rgb[1][indx - w2]);
            float S1 = eps + std::fabs(rgb1 - rgb[1][indx + w2]);
            float W1 = eps + std::fabs(rgb1 - rgb[1][indx - 2]);
            float E1 = eps + std::fabs(rgb1 - rgb[1][indx + 2]);

            float rgb1mw1 = rgb[1][indx - w1];
            float rgb1pw1 = rgb[1][indx + w1];
            float rgb1m1 = rgb[1][indx - 1];
            float rgb1p1 = rgb[1][indx + 1];
            for (int c = 0; c <= 2; c += 2) {
                // Cardinal gradients
                float SNabs = std::fabs(rgb[c][indx - w1] - rgb[c][indx + w1]);
                float EWabs = std::fabs(rgb[c][indx - 1] - rgb[c][indx + 1]);
                float N_Grad = N1 + SNabs + std::fabs(rgb[c][indx - w1] - rgb[c][indx - w3]);
                float S_Grad = S1 + SNabs + std::fabs(rgb[c][indx + w1] - rgb[c][indx + w3]);
                float W_Grad = W1 + EWabs + std::fabs(rgb[c][indx - 1] - rgb[c][indx - 3]);
                float E_Grad = E1 + EWabs + std::fabs(rgb[c][indx + 1] - rgb[c][indx + 3]);

                // Cardinal colour differences
                float N_Est = rgb[c][indx - w1] - rgb1mw1;
                float S_Est = rgb[c][indx + w1] - rgb1pw1;
                float W_Est = rgb[c][indx - 1] - rgb1m1;
                float E_Est = rgb[c][indx + 1] - rgb1p1;

                // Vertical and horizontal estimations
                float V_Est = (N_Grad * S_Est + S_Grad * N_Est) / (N_Grad + S_Grad);
                float H_Est = (E_Grad * W_Est + W_Grad * E_Est) / (E_Grad + W_Grad);

                // R@G and B@G interpolation
                rgb[c][indx] = rgb1 + intp(VH_Disc, H_Est, V_Est);
            }
        }
    }

    // For the outermost tiles in all directions we can use a smaller border margin
    const int firstVertical = rowStart + ((tr == 0) ? kRcdBorder : kTileBorder);
    const int lastVertical = rowEnd - ((tr == numTh - 1) ? kRcdBorder : kTileBorder);
    const int firstHorizontal = colStart + ((tc == 0) ? kRcdBorder : kTileBorder);
    const int lastHorizontal = colEnd - ((tc == numTw - 1) ? kRcdBorder : kTileBorder);
    for (int row = firstVertical; row < lastVertical; ++row) {
        for (int col = firstHorizontal; col < lastHorizontal; ++col) {
            const int idx = (row - rowStart) * kTileSize + col - colStart;
            const size_t o = static_cast<size_t>(row) * width + col;
            for (int c = 0; c < 3; c++) out[c][o] = std::max(0.f, rgb[c][idx]);
        }
    }
}

/** Bilinear for the [border] pixels RCD leaves out: the mean of each colour in the 3x3. */
void bilinearBorder(int width, int height, const float* in, const int pattern[4], float* const out[3], int border) {
    for (int y = 0; y < height; y++) {
        const bool edgeRow = y < border || y >= height - border;
        for (int x = 0; x < width; x++) {
            if (!edgeRow && x >= border && x < width - border) {
                x = width - border - 1;
                continue;
            }
            float sum[3] = {0, 0, 0};
            int n[3] = {0, 0, 0};
            for (int dy = -1; dy <= 1; dy++) {
                const int yy = y + dy;
                if (yy < 0 || yy >= height) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    const int xx = x + dx;
                    if (xx < 0 || xx >= width) continue;
                    const int c = pattern[(yy & 1) * 2 + (xx & 1)];
                    sum[c] += std::clamp(in[static_cast<size_t>(yy) * width + xx], 0.0f, 1.0f);
                    n[c]++;
                }
            }
            const size_t o = static_cast<size_t>(y) * width + x;
            for (int c = 0; c < 3; c++) out[c][o] = n[c] ? sum[c] / n[c] : 0.0f;
        }
    }
}

}  // namespace

void rcdDemosaic(int width, int height, const float* cfa, const int pattern[4], float* red, float* green, float* blue,
                 int threads) {
    float* const out[3] = {red, green, blue};
    const int numTh = height / kTileSizeN + ((height % kTileSizeN) ? 1 : 0);
    const int numTw = width / kTileSizeN + ((width % kTileSizeN) ? 1 : 0);
    std::atomic<int> next{0};
    auto worker = [&] {
        Tiles t;
        for (int i = next++; i < numTh * numTw; i = next++) rcdTile(width, height, cfa, pattern, out, i / numTw, i % numTw, numTh, numTw, t);
    };
    std::vector<std::thread> pool;
    for (int k = 1; k < std::max(1, threads); k++) pool.emplace_back(worker);
    worker();
    for (auto& th : pool) th.join();
    bilinearBorder(width, height, cfa, pattern, out, kRcdBorder);
}

}  // namespace gccore
