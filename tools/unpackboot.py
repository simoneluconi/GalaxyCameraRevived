"""Unpack an Android v0 boot.img: kernel, ramdisk (gzip newc cpio extracted), header info."""
import gzip, os, struct, sys

img, out = sys.argv[1], sys.argv[2]
data = open(img, 'rb').read()
assert data[:8] == b'ANDROID!'
(ksz, kaddr, rsz, raddr, ssz, saddr, tags, page) = struct.unpack_from('<8I', data, 8)
name = data[48:64].rstrip(b'\0').decode()
cmdline = data[64:576].rstrip(b'\0').decode()
os.makedirs(out, exist_ok=True)
with open(os.path.join(out, 'header.txt'), 'w') as f:
    f.write(f'kernel_size={ksz}\nkernel_addr=0x{kaddr:08x}\nramdisk_size={rsz}\nramdisk_addr=0x{raddr:08x}\n'
            f'second_size={ssz}\nsecond_addr=0x{saddr:08x}\ntags_addr=0x{tags:08x}\npagesize={page}\n'
            f'base=0x{kaddr - 0x8000:08x}\nname={name}\ncmdline={cmdline}\n')

def pages(n):
    return (n + page - 1) // page * page

koff = page
roff = koff + pages(ksz)
open(os.path.join(out, 'zImage'), 'wb').write(data[koff:koff + ksz])
rd = data[roff:roff + rsz]
open(os.path.join(out, 'ramdisk.cpio.gz'), 'wb').write(rd)

cpio = gzip.decompress(rd)
root = os.path.join(out, 'ramdisk')
listing = []
pos = 0
while True:
    hdr = cpio[pos:pos + 110]
    assert hdr[:6] in (b'070701', b'070702'), hdr[:6]
    f = [int(hdr[6 + i * 8:14 + i * 8], 16) for i in range(13)]
    mode, fsize, nsize = f[1], f[6], f[11]
    pos += 110
    fname = cpio[pos:pos + nsize - 1].decode()
    pos = (pos + nsize + 3) & ~3
    body = cpio[pos:pos + fsize]
    pos = (pos + fsize + 3) & ~3
    if fname == 'TRAILER!!!':
        break
    kind = mode & 0o170000
    listing.append(f'{mode:07o} {f[2]:5d} {f[3]:5d} {fsize:9d} {fname}' + (f' -> {body.decode()}' if kind == 0o120000 else ''))
    dest = os.path.join(root, fname)
    if kind == 0o040000:
        os.makedirs(dest, exist_ok=True)
    elif kind == 0o100000:
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        open(dest, 'wb').write(body)
open(os.path.join(out, 'ramdisk-listing.txt'), 'w').write('\n'.join(listing) + '\n')
print(open(os.path.join(out, 'header.txt')).read())
print(f'{len(listing)} ramdisk entries')
