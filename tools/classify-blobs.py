"""Split the files of a mounted vendor partition into "built from source" and "prebuilt blob".

A file counts as built from source when some Android.bp / Android.mk in the tree defines a module
with the same stem (libfoo.so -> libfoo, android.hardware.x@1.0-service -> same). Everything else is
reported as a blob candidate. Paths are relative to the vendor partition root.

Usage: classify-blobs.py <android-tree> <mounted-vendor-dir> <out-prefix>
"""
import os
import re
import sys

tree, vendor, out = sys.argv[1:4]

SKIP_DIRS = {'out', '.repo', 'prebuilts'}
bp_name = re.compile(r'\bname:\s*"([^"]+)"')
mk_name = re.compile(r'^\s*LOCAL_MODULE\s*:?=\s*(\S+)', re.M)
stem_name = re.compile(r'\bstem:\s*"([^"]+)"')
mk_stem = re.compile(r'^\s*LOCAL_MODULE_STEM\s*:?=\s*(\S+)', re.M)

modules = set()
for root, dirs, files in os.walk(tree):
    rel = os.path.relpath(root, tree)
    if rel.split(os.sep)[0] in SKIP_DIRS or rel.startswith('vendor/samsung'):
        dirs[:] = []
        continue
    dirs[:] = [d for d in dirs if not d.startswith('.')]
    for f in files:
        if f not in ('Android.bp', 'Android.mk'):
            continue
        try:
            text = open(os.path.join(root, f), errors='ignore').read()
        except OSError:
            continue
        for rx in (bp_name, mk_name, stem_name, mk_stem):
            modules.update(rx.findall(text))

built, blobs = [], []
for root, _, files in os.walk(vendor):
    for f in files:
        path = os.path.relpath(os.path.join(root, f), vendor)
        if path.startswith('lost+found'):
            continue
        stem = f
        for ext in ('.so', '.rc', '.xml', '.apk', '.jar', '.ko'):
            if stem.endswith(ext):
                stem = stem[:-len(ext)]
                break
        (built if stem in modules or f in modules else blobs).append(path)

for name, items in (('built', built), ('blobs', blobs)):
    with open(f'{out}-{name}.txt', 'w') as fh:
        fh.write('\n'.join(sorted(items)) + '\n')
print(f'{len(modules)} modules indexed; {len(built)} built from source, {len(blobs)} blob candidates')
