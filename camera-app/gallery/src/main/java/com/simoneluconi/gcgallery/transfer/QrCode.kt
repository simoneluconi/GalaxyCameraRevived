// SPDX-License-Identifier: GPL-3.0-or-later
package com.simoneluconi.gcgallery.transfer

/**
 * A QR code for a short text (the hotspot's "WIFI:" string), as a square of modules. ISO/IEC
 * 18004 in byte mode at error correction level M, versions 1 to 10 (up to 213 bytes): the
 * framework has no encoder, and this is all the transfer screen needs. The structure follows
 * Project Nayuki's reference generator.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    /** True for a dark module; x to the right, y down. */
    operator fun get(x: Int, y: Int) = modules[y][x]

    companion object {
        /** The smallest version that holds [text] (UTF-8), or null if it is longer than version 10 takes. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            for (ver in 1..10) {
                val capacity = dataCodewords(ver) * 8
                // Mode (4 bits), count (8 bits up to version 9, 16 from 10), the bytes
                val countBits = if (ver <= 9) 8 else 16
                if (4 + countBits + data.size * 8 <= capacity) return Builder(ver).build(data, countBits)
            }
            return null
        }

        // Level M, versions 1-10 (index 0 unused)
        private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        /** Level M in the format information. */
        private const val FORMAT_M = 0

        private fun rawModules(ver: Int): Int {
            var r = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val align = ver / 7 + 2
                r -= (25 * align - 10) * align - 55
                if (ver >= 7) r -= 36
            }
            return r
        }

        private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

        /** GF(2^8) with the QR polynomial 0x11D. */
        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val r = IntArray(degree)
            r[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    r[j] = gfMul(r[j], root)
                    if (j + 1 < degree) r[j] = r[j] xor r[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return r
        }

        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val r = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor r[0]
                System.arraycopy(r, 1, r, 0, r.size - 1)
                r[r.size - 1] = 0
                for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
            }
            return r
        }
    }

    private class Builder(val ver: Int) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun build(data: ByteArray, countBits: Int): QrCode {
            drawFunctionPatterns()
            drawCodewords(addEcc(dataBits(data, countBits)))
            // The mask with the lowest penalty, as the standard asks
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (m in 0 until 8) {
                applyMask(m)
                drawFormat(m)
                val p = penalty()
                if (p < bestPenalty) {
                    best = m
                    bestPenalty = p
                }
                applyMask(m) // undo: XOR again
            }
            applyMask(best)
            drawFormat(best)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            function[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                // Not over the three finders
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            // Reserve the format areas now; the real bits come with the mask
            drawFormat(0)
            if (ver >= 7) drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val r = IntArray(n)
            r[0] = 6
            var p = size - 7
            for (i in n - 1 downTo 1) {
                r[i] = p
                p -= step
            }
            return r
        }

        private fun drawFormat(mask: Int) {
            val data = FORMAT_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = ver shl 12 or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark)
                set(b, a, dark)
            }
        }

        /** Byte mode, terminator, then the 0xEC/0x11 padding up to the version's data capacity. */
        private fun dataBits(data: ByteArray, countBits: Int): IntArray {
            val capacity = dataCodewords(ver)
            val bits = ArrayList<Boolean>(capacity * 8)
            fun append(v: Int, n: Int) {
                for (i in n - 1 downTo 0) bits.add((v ushr i) and 1 != 0)
            }
            append(0b0100, 4)
            append(data.size, countBits)
            for (b in data) append(b.toInt() and 0xFF, 8)
            append(0, minOf(4, capacity * 8 - bits.size))
            append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity * 8) {
                append(pad, 8)
                pad = pad xor (0xEC xor 0x11)
            }
            return IntArray(capacity) { i ->
                var v = 0
                for (j in 0 until 8) v = v shl 1 or (if (bits[i * 8 + j]) 1 else 0)
                v
            }
        }

        /** Split into blocks, append each block's Reed-Solomon bytes, then interleave. */
        private fun addEcc(data: IntArray): IntArray {
            val blocks = BLOCKS[ver]
            val ecc = ECC_PER_BLOCK[ver]
            val raw = rawModules(ver) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val div = rsDivisor(ecc)
            val all = ArrayList<IntArray>(blocks)
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - ecc + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val block = IntArray(shortLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                val rem = rsRemainder(dat, div)
                System.arraycopy(rem, 0, block, block.size - ecc, ecc)
                all.add(block)
            }
            val out = IntArray(raw)
            var n = 0
            for (i in 0 until shortLen + 1) for (j in 0 until blocks) {
                // The short blocks have no byte at this position
                if (i != shortLen - ecc || j >= shortBlocks) out[n++] = all[j][i]
            }
            return out
        }

        /** The zigzag from the bottom right, two columns at a time, skipping the timing column. */
        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(m: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val invert = when (m) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        /** Runs of five or more, 2x2 blocks, finder-like 1:1:3:1:1 runs, and the dark/light balance. */
        private fun penalty(): Int {
            var p = 0
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    var run = 0
                    var last = false
                    val line = BooleanArray(size) { b -> if (horizontal) modules[a][b] else modules[b][a] }
                    for (b in 0 until size) {
                        if (b > 0 && line[b] == last) {
                            run++
                            if (run == 5) p += 3 else if (run > 5) p++
                        } else {
                            run = 1
                            last = line[b]
                        }
                    }
                    for (b in 0..size - 11) {
                        val pattern = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
                        var fwd = true
                        var back = true
                        for (k in 0 until 11) {
                            if (line[b + k] != pattern[k]) fwd = false
                            if (line[b + k] != pattern[10 - k]) back = false
                        }
                        if (fwd) p += 40
                        if (back) p += 40
                    }
                }
            }
            var dark = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (modules[y][x]) dark++
                if (x < size - 1 && y < size - 1) {
                    val c = modules[y][x]
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3
                }
            }
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            p += maxOf(0, k) * 10
            return p
        }
    }
}
