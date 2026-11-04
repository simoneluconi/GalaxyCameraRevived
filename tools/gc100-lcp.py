#!/usr/bin/env python3
"""Write the Adobe lens profile (.lcp) of the Galaxy Camera EK-GC100's zoom lens.

Lightroom, Camera Raw (Photoshop) and RawTherapee read .lcp files ("Enable Profile
Corrections"). The distortion per focal length comes from the calibration against the ISP's
own JPEGs (docs/CAMERA-APP.md §9, gccore/src/lens.cpp). The model is Adobe's Camera Model:
an ideal (corrected) point at (x - x0)/fx comes from the image at x * (1 + k1 r^2 + k2 r^4).

Our fit normalises r by the ISP frame's half diagonal (2880 px at 1:1 with the RAW), so the
profile's focal length is that, in units of the image's longest side.

Usage: gc100-lcp.py [out.lcp] [--model "Samsung EK-GC100"] [--name "Samsung EK-GC100 (B)"]
Install: copy it to %APPDATA%\\Adobe\\CameraRaw\\LensProfiles\\1.0\\ and restart Lightroom.
"""
import sys

# (focal mm, k1, k2) from gccore/src/lens.cpp. From 16.5 mm on the ISP corrects nothing, but
# Lightroom rejects a whole profile with an all-zero entry ("Unable to load lens profile",
# found 2026-11-02), so those get a negligible k1 instead (1e-4 is under 0.03 px at r = 1).
NO_CORRECTION = -0.0001
TABLE = [
    (4.1, -0.269, +0.117),
    (5.0, -0.177, +0.050),
    (6.2, -0.090, +0.008),
    (9.2, -0.025, +0.010),
    (16.5, NO_CORRECTION, 0.0),
    (86.1, NO_CORRECTION, 0.0),
]

# The DNG's image after DefaultCrop (8 px inside the 4670x3452 active area)
WIDTH, HEIGHT = 4654, 3436
HALF_DIAGONAL = 2880.0  # the ISP frame's, in RAW pixels
# Optical centre measured in the active area, (2331, 1739), moved into the cropped image
CENTRE_X = (2331 - 8) / WIDTH
CENTRE_Y = (1739 - 8) / HEIGHT
# f-numbers at the zoom ends, for the ApertureValue (APEX) of each entry
F_WIDE, F_TELE, FOCAL_WIDE, FOCAL_TELE = 2.8, 5.9, 4.1, 86.1

# Exactly the shape of Adobe's own profiles (e.g. "Apple iPad (...) - RAW.lcp" in
# C:\ProgramData\Adobe\CameraRaw\LensProfiles\1.0): attributes on rdf:Description, and the
# PerspectiveModel as one element with attributes. Lightroom finds but cannot load other
# valid-XMP shapes ("Unable to load lens profile").
ENTRY = """     <rdf:li>
      <rdf:Description
       stCamera:Author="GalaxyCameraRevived"
       stCamera:Make="Samsung"
       stCamera:Model="{model}"
       stCamera:CameraRawProfile="True"
       stCamera:Lens="4.1-86.1mm f/2.8-5.9"
       stCamera:LensInfo="41/10 861/10 28/10 59/10"
       stCamera:CameraPrettyName="Samsung EK-GC100"
       stCamera:LensPrettyName="Samsung 4.1-86.1mm f/2.8-5.9"
       stCamera:ProfileName="{name}"
       stCamera:SensorFormatFactor="5.61"
       stCamera:ImageWidth="{w}"
       stCamera:ImageLength="{h}"
       stCamera:FocalLength="{focal}"
       stCamera:FocusDistance="10000"
       stCamera:ApertureValue="{av:.6f}">
      <stCamera:PerspectiveModel>
       <rdf:Description
        stCamera:Version="2"
        stCamera:FocalLengthX="{fx:.6f}"
        stCamera:FocalLengthY="{fx:.6f}"
        stCamera:ImageXCenter="{cx:.6f}"
        stCamera:ImageYCenter="{cy:.6f}"
        stCamera:RadialDistortParam1="{k1:.6f}"
        stCamera:RadialDistortParam2="{k2:.6f}"
        stCamera:RadialDistortParam3="0.000000">
       <stCamera:VignetteModel
        stCamera:FocalLengthX="{fx:.6f}"
        stCamera:FocalLengthY="{fx:.6f}"
        stCamera:ImageXCenter="{cx:.6f}"
        stCamera:ImageYCenter="{cy:.6f}"
        stCamera:VignetteModelParam1="{v1:.6f}"
        stCamera:VignetteModelParam2="0.000000"
        stCamera:VignetteModelParam3="0.000000"/>
       </rdf:Description>
      </stCamera:PerspectiveModel>
      </rdf:Description>
     </rdf:li>
"""

# Light fall-off 1 + v1 r^2 that the profile evens out. Not measured yet (that needs a flat
# field, docs/TODO.md): set by eye in Lightroom by the user (2026-11-02), who liked the slider
# at 150 over a first -0.25, so -0.375 (about +0.7 EV in the corners) is now 100.
VIGNETTE_V1 = -0.375


def main():
    import argparse
    import math
    ap = argparse.ArgumentParser()
    ap.add_argument("out", nargs="?", default="Samsung EK-GC100.lcp")
    # EXIF Model of our DNGs; their UniqueCameraModel is "Samsung EK-GC100"
    ap.add_argument("--model", default="EK-GC100")
    ap.add_argument("--name", default="Samsung EK-GC100")
    a = ap.parse_args()
    out = a.out
    fx = HALF_DIAGONAL / max(WIDTH, HEIGHT)
    entries = []
    for focal, k1, k2 in TABLE:
        # f-number roughly geometric between the zoom ends
        t = math.log(focal / FOCAL_WIDE) / math.log(FOCAL_TELE / FOCAL_WIDE)
        fnum = F_WIDE * (F_TELE / F_WIDE) ** t
        entries.append(ENTRY.format(w=WIDTH, h=HEIGHT, focal=focal, av=2 * math.log2(fnum),
                                    fx=fx, cx=CENTRE_X, cy=CENTRE_Y, k1=k1, k2=k2,
                                    model=a.model, name=a.name, v1=VIGNETTE_V1))
    # CRLF and plain ASCII, like Adobe's own .lcp files
    with open(out, "w", encoding="ascii", newline="\r\n") as f:
        f.write('<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.6-c140 79.160451, 2017/05/06-01:08:21        ">\n'
                ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
                '  <rdf:Description rdf:about=""\n'
                '    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"\n'
                '    xmlns:stCamera="http://ns.adobe.com/photoshop/1.0/camera-profile">\n'
                '   <photoshop:CameraProfiles>\n'
                '    <rdf:Seq>\n')
        f.write("".join(entries))
        f.write('    </rdf:Seq>\n'
                '   </photoshop:CameraProfiles>\n'
                '  </rdf:Description>\n'
                ' </rdf:RDF>\n'
                '</x:xmpmeta>\n')
    print(f"{out}: {len(entries)} focal lengths, fx {fx:.4f}, centre {CENTRE_X:.4f} {CENTRE_Y:.4f}")


if __name__ == "__main__":
    main()
