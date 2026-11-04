# Our RAW development ("Advanced" JPEG): research and plan

The user's bar (2026-11-04): the JPEG we develop from the RAW must be **clearly better** than the M9MO ISP's own JPEG, not equal. If it cannot be, the feature is removed everywhere (app setting, background job, gccore develop code).

This file sums up the state-of-the-art research done on 2026-11-04 (three web surveys: demosaic and denoise; sharpening and lens; colour and tone) and turns it into a plan with a measurable go/no-go gate. The current pipeline state and its numbers are in docs/TODO.md ("Advanced" processing).

## 1. Where the gain can come from

**Constraints.** One RAW frame per shot (the M9MO cannot burst RAW). A 1/2.3" sensor with 1.34 µm pixels behind a 21x zoom. Background processing on 4x Cortex-A9 with NEON, ~200–300 MB, strips, minutes allowed.

- **Noise and texture at ISO 400 and up: the biggest win.**
  - Reviews of this camera all name the ISP's smearing noise reduction, visible even at ISO 100–200.
  - Same-class 2012 superzooms score clearly better from RAW than from JPEG (DPReview FZ200: 8.5 RAW vs 7.0 JPEG).
- **Halo-free crispness and the corners: a likely win.**
  - The ISP applies one global sharpening, ×1.38 at 2 px, with halos.
  - A field-varying deconvolution plus an overshoot clamp can match its centre crispness without halos, and beat it in the corners.
- **Highlights: 0–1 stop**, depending on whether the ISP clips before the 10-bit RAW saturates. Measure this.
- **Colour: about parity.** We are at ΔE ~1.2 against the ISP. A dual-illuminant profile improves robustness, not the look.
- **No win at the long end.** At f/5.9 diffraction cuts everything above 0.41 cycles/pixel, so 50–86 mm is at best a tie.
- **At web or screen size, differences are small.** The win shows at 100% and at high ISO.

**Why our pipeline only reached parity:**
- the demosaic is bilinear-class, about 8–10 dB worse than modern methods, with zipper and false colour that the NR then fights;
- the NR is per-pixel wavelet shrinkage without a variance-stabilising transform;
- the sharpening is USM on the output grid after a Catmull-Rom warp that softens with sub-pixel phase.

## 2. Pipeline (target order)

1. Raw: black level and hot pixels.
2. Vignetting and colour-shading gain (flat-field profile per zoom step) and preliminary WB.
3. **Denoise in the raw domain.**
   - Phase 1: our à-trous wavelet NR, run in a VST domain (generalised Anscombe from a measured Poisson-Gaussian a, b per ISO, with the exact unbiased inverse).
   - Phase 2: a PMRID-class raw U-Net, run on the CPU through ncnn. It costs 3.6 GMAC/MP, estimated at 20 s–2 min per frame. Train it on our own tripod-averaged ISO 100 frames plus calibrated synthetic noise. PMRID is Apache-2.0.
4. **Demosaic: RCD** (darktable's default; port from librtprocess), plus raw lateral-CA correction (`CA_correct`, optional).
5. Residual chroma denoise at large scales (U/V).
6. **Capture sharpening: Richardson-Lucy on linear luma.**
   - The Gaussian PSF varies with focal length, aperture and field position (calibrated, §3), plus a small blind focus term.
   - An RT-style contrast mask and an early stop per 32×32 tile.
   - Strong at wide angle, gentle at tele because of the diffraction limit.
   - Estimated cost: 10–40 s.
7. **Warp**: distortion plus lateral CA as per-channel radial scale, in one Lanczos-3 pass with a 3×3 min/max overshoot clamp, in linear RGB. Lanczos-3 replaces Catmull-Rom.
8. Colour: the matrix now; later a dual-illuminant ForwardMatrix by CCT, a gentle HueSatMap, and chroma gamut compression instead of per-channel clipping.
9. Tone: the measured ISP curve, applied hue-preserving. Later, a low-resolution local tone map (exposure fusion or a fast local Laplacian on a 1/8 luma, upsampled as a gain map) and highlight reconstruction.
10. **Output detail**: edge-aware à-trous (Hanika 2011). Boost levels 1–3 with edge weights, soft-threshold level 0, overshoot clamp with about 10% overshoot allowed, because a little reads as sharpness.
11. JPEG at q92+ with **4:4:4** (it also helps small saturated details, see the "blue gap"). Later jpegli, a drop-in libjpeg replacement that gives about 35% smaller files.

## 3. Calibration and measurement first

1. **Per-stage MTF harness.**
   - Use MTF Mapper (BSD), with a printed slanted-edge chart: 1200 dpi on matte paper, A3, one high-contrast and one low-contrast (4:1) version.
   - Measure MTF50, MTF50P, overshoot % and noise SD at each stage: raw green, after demosaic, after deconvolution, after warp, final. Do the same on the ISP JPEG.
   - Shoot on a tripod with the 2 s timer and OIS off, at ISO 100 and one high ISO, at 4.1 / 7 / 12 / 25 / 50 / 86 mm.
   - This shows where we lose to the ISP before any new algorithm goes in.
2. **Noise profile.** Dark frames and flat frames at every ISO give a, b per CFA channel, plus a check for row and fixed-pattern noise.
3. **Flat fields** per zoom step: an opal diffuser or white paper over the lens towards an even sky. They give luminance and colour shading.
4. **PSF per field position and focal length**, from the same slanted-edge shots on the raw green channel: sagittal and meridional sigma, stored next to the distortion model.
5. **Colour (phase 2).** A genuine ColorChecker Classic or Mini; clones have no trustworthy reference data. Shoot it in midday sun and under a halogen lamp. Fluorescent and LED light are for validation only.
   - Fit with dcamprof into a dual-illuminant DCP, used both in our pipeline and in the DNG.
   - Diagnose the blue gap first: a large blue patch tells a 3x3/spectral limit apart from a demosaic/NR/4:2:0 spatial cause.

## 4. Go / no-go gate

After steps 3.1–3.3 and pipeline steps 3, 4, 6, 7, 10 and 11 (phase 1):
- **Pass**: at ISO 100, 400 and 1600, on the user's real scenes, both of these hold:
  - the blind A/B at 100% prefers ours (random side, user as judge);
  - MTF50P ≥ ISP, MTF50 within 10% of the ISP, and overshoot well below the ISP's.
  - Then phase 2 follows (learned denoiser, colour profile, local tone map), and the app setting is enabled.
- **Fail**: if the measurements show we are at the optical limit and the user still prefers the ISP, **remove the feature**: the setting, the job and gccore's develop code. The DNG writer stays.

## 5. Licences (decision needed before porting)

| Code | Licence | Used for |
|---|---|---|
| RCD, librtprocess (RawTherapee) | GPL-3 | port |
| RawTherapee and darktable (RL capture sharpening, auto-CA, diffuse-or-sharpen) | GPL-3 | reference |
| PMRID | Apache-2.0 | GPL-3 compatible |
| MTF Mapper | BSD | measurement only, not linked |

Porting GPL-3 code makes gccore, the app that links it, and the PC tool GPL-3. The README still says "to be confirmed" for the apps. Otherwise we reimplement from the papers: RL, the Lanczos warp and à-trous are short to write, RCD is the subtle one.

## 6. Main sources

- Demosaic and denoise:
  - Jin, Facciolo & Morel, CVPRW 2020: demosaic first, then denoise at 1.5σ in YUV.
  - Ehret & Facciolo, IPOL 2019.
  - darktable "denoise (profiled)" and the [demosaic docs](https://docs.darktable.org/usermanual/4.6/en/module-reference/processing-modules/demosaic/).
  - [PMRID](https://github.com/MegEngine/PMRID) (Wang et al., ECCV 2020).
  - [librtprocess](https://github.com/CarVac/librtprocess).
  - ncnn's Cortex-A9 benchmark, the basis for the cost estimates.
- Sharpening and lens:
  - RawTherapee [Capture Sharpening](https://rawpedia.pixls.us/capture_sharpening/).
  - Polyblur (Delbracio et al., IPOL 2022).
  - Hanika et al. 2011, edge-optimised à-trous.
  - Aubry et al. 2014, fast local Laplacian.
  - MTF Mapper; Imatest on MTF50P and overshoot.
- Colour and tone:
  - [dcamprof](https://rawtherapee.com/mirror/dcamprof/dcamprof.html).
  - Finlayson et al. 2015, root-polynomial colour correction.
  - FFCC (Barron 2017), the AWB benchmark; the conclusion is to keep the ISP's WB.
  - HDR+ (Hasinoff 2016) `finish` stage, the single-frame tone part.
  - jpegli and libultrahdr.
