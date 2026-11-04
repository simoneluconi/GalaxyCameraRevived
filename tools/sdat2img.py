"""Convert a block-based OTA <name>.new.dat (+ .transfer.list) into a raw ext4 image."""
import sys

BLOCK = 4096


def ranges(spec):
    vals = [int(v) for v in spec.split(',')]
    assert vals[0] == len(vals) - 1
    return list(zip(vals[1::2], vals[2::2]))


transfer_list, new_dat, out = sys.argv[1:4]
with open(transfer_list) as f:
    lines = f.read().splitlines()
version = int(lines[0])
commands = lines[4:] if version >= 2 else lines[2:]

max_block = 0
with open(new_dat, 'rb') as src, open(out, 'wb') as dst:
    for line in commands:
        op, _, arg = line.partition(' ')
        if op == 'new':
            for start, end in ranges(arg):
                dst.seek(start * BLOCK)
                n = (end - start) * BLOCK
                while n:
                    chunk = src.read(min(n, 1 << 24))
                    assert chunk, 'new.dat ended early'
                    dst.write(chunk)
                    n -= len(chunk)
                max_block = max(max_block, end)
        elif op in ('erase', 'zero'):
            for _, end in ranges(arg):
                max_block = max(max_block, end)
        elif op in ('stash', 'free', 'move', 'bsdiff', 'imgdiff'):
            raise ValueError(f'incremental OTA command not supported: {op}')
    dst.truncate(max_block * BLOCK)
print(f'{out}: {max_block} blocks')
