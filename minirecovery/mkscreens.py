#!/usr/bin/env python3
"""Render the mini recovery status screens as gzipped PPM files for fbsplash.

The panel is 720x1280 portrait and the camera is used in landscape (the
status bar sits on the right edge of the framebuffer), so each screen is
drawn at 1280x720 and rotated 90 degrees clockwise.

Usage: mkscreens.py <out dir>
"""
import gzip
import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
MONO = '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf'

SCREENS = {
    'main': ('Galaxy Camera mini recovery', (90, 160, 255), [
        ('text', 'adb shell over USB, then su for root'),
        ('gap', None),
        ('mono', 'backup-critical        copy EFS, PARAM, BOOT... to /tmp/backup'),
        ('mono', 'flash-image PART file  write BOOT, RECOVERY, SYSTEM or HIDDEN'),
        ('mono', 'wipe-data --yes        factory reset (/data and /cache)'),
        ('mono', 'cat /tmp/recovery.log  what this recovery did'),
        ('gap', None),
        ('text', 'adb reboot   |   adb reboot download'),
        ('text', 'Without adb: hold Power, or remove the battery.'),
    ]),
    'wiping': ('Factory reset', (255, 190, 60), [
        ('text', 'Wiping /data and /cache...'),
        ('text', 'Do not remove the battery.'),
    ]),
    'done': ('Factory reset', (80, 220, 120), [
        ('text', 'Done. Rebooting, LineageOS will format the partitions.'),
    ]),
    'error': ('Error', (255, 80, 80), [
        ('text', 'Something went wrong.'),
        ('text', 'Connect adb and read /tmp/recovery.log'),
    ]),
}


def render(title, color, lines):
    img = Image.new('RGB', (1280, 720), (0, 0, 0))
    d = ImageDraw.Draw(img)
    title_font = ImageFont.truetype(FONT, 52)
    text_font = ImageFont.truetype(FONT, 32)
    mono_font = ImageFont.truetype(MONO, 26)
    y = 70
    d.text((70, y), title, font=title_font, fill=color)
    y += 110
    for kind, text in lines:
        if kind == 'gap':
            y += 24
            continue
        font = mono_font if kind == 'mono' else text_font
        d.text((70, y), text, font=font, fill=(230, 230, 230))
        y += 50 if kind == 'text' else 42
    # Image.ROTATE_270 is the pre-9.1 spelling of Image.Transpose.ROTATE_270
    return img.transpose(Image.ROTATE_270)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, (title, color, lines) in SCREENS.items():
        img = render(title, color, lines)
        assert img.size == (720, 1280)
        ppm = b'P6\n720 1280\n255\n' + img.tobytes()
        with gzip.open(os.path.join(out, name + '.ppm.gz'), 'wb', 9) as f:
            f.write(ppm)
        img.save(os.path.join(out, name + '.png'))


if __name__ == '__main__':
    main()
