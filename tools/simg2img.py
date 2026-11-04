"""Convert an Android sparse image to a raw image."""
import struct, sys

src, dst = sys.argv[1], sys.argv[2]
with open(src, 'rb') as f, open(dst, 'wb') as o:
    magic, major, minor, fhs, chs, blksz, nblocks, nchunks, _ = struct.unpack('<I4H4I', f.read(28))
    assert magic == 0xED26FF3A
    f.seek(fhs)
    for _ in range(nchunks):
        ctype, _, csz, total = struct.unpack('<2H2I', f.read(12))
        f.seek(chs - 12, 1)
        n = total - chs
        if ctype == 0xCAC1:  # raw
            while n:
                b = f.read(min(n, 1 << 24)); o.write(b); n -= len(b)
        elif ctype == 0xCAC2:  # fill
            pat = f.read(4)
            o.write(pat * (csz * blksz // 4))
        elif ctype == 0xCAC3:  # don't care
            o.seek(csz * blksz, 1)
        elif ctype == 0xCAC4:  # crc
            f.read(n)
        else:
            raise ValueError(hex(ctype))
    o.truncate(nblocks * blksz)
print(f'{nblocks} blocks of {blksz} -> {dst}')
