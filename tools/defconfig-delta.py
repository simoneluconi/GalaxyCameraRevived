"""Compute the config delta between two defconfigs and apply it on top of a third.

Usage: defconfig-delta.py <base> <variant> <target> [--apply OUT]

Prints the symbols the variant sets, unsets or changes relative to the base. With --apply, writes
<target> with that delta applied (delta wins; symbols untouched by the delta keep the target's value).
"""
import re
import sys

LINE = re.compile(r'^(CONFIG_\w+)=(.*)$')
UNSET = re.compile(r'^# (CONFIG_\w+) is not set$')


def parse(path):
    vals = {}
    for line in open(path):
        line = line.rstrip('\n')
        m = LINE.match(line)
        if m:
            vals[m.group(1)] = m.group(2)
            continue
        m = UNSET.match(line)
        if m:
            vals[m.group(1)] = 'n'
    return vals


base, variant, target = (parse(p) for p in sys.argv[1:4])
delta = {}
for sym in sorted(set(base) | set(variant)):
    b, v = base.get(sym, 'n'), variant.get(sym, 'n')
    if b != v:
        delta[sym] = v
        print(f'{sym}: {b} -> {v}   (target: {target.get(sym, "n")})')

if '--apply' in sys.argv:
    out = sys.argv[sys.argv.index('--apply') + 1]
    merged = dict(target)
    merged.update(delta)
    with open(out, 'w') as fh:
        for sym in sorted(merged):
            val = merged[sym]
            fh.write(f'# {sym} is not set\n' if val == 'n' else f'{sym}={val}\n')
    print(f'wrote {out}: {len(delta)} symbols from the delta')
