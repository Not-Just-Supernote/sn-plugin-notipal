package me.laumss.notipal.ui_common

import android.graphics.Bitmap
import android.graphics.Color


class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {

    fun get(x: Int, y: Int): Boolean = modules[y][x]

    
    fun toBitmap(scale: Int, quiet: Int = 4): Bitmap {
        val dim = (size + quiet * 2) * scale
        val pixels = IntArray(dim * dim) { Color.WHITE }
        for (y in 0 until size) for (x in 0 until size) {
            if (!modules[y][x]) continue
            val px = (x + quiet) * scale
            val py = (y + quiet) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * dim + px
                for (dx in 0 until scale) pixels[row + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.RGB_565)
    }

    companion object {
        private const val MAX_VERSION = 15

        
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24)
        private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10)
        private const val ECL_BITS = 0 

        
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            var version = 1
            while (true) {
                if (version > MAX_VERSION) return null
                val capacityBits = dataCodewords(version) * 8
                val countBits = if (version <= 9) 8 else 16
                if (4 + countBits + data.size * 8 <= capacityBits) break
                version++
            }
            val bits = BitBuffer()
            bits.append(0x4, 4)
            bits.append(data.size, if (version <= 9) 8 else 16)
            for (b in data) bits.append(b.toInt() and 0xFF, 8)
            val capacity = dataCodewords(version) * 8
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }

            val codewords = ByteArray(bits.size / 8)
            for (i in 0 until bits.size) if (bits[i]) {
                codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
            return build(version, interleave(version, codewords))
        }

        private fun rawModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(ver: Int): Int =
            rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

        private fun interleave(ver: Int, data: ByteArray): ByteArray {
            val numBlocks = NUM_BLOCKS[ver]
            val eccLen = ECC_PER_BLOCK[ver]
            val rawCodewords = rawModules(ver) / 8
            val numShort = numBlocks - rawCodewords % numBlocks
            val shortLen = rawCodewords / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortLen - eccLen + if (i < numShort) 0 else 1
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val ecc = rsRemainder(dat, divisor)
                val block = ByteArray(shortLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                System.arraycopy(ecc, 0, block, block.size - eccLen, eccLen)
                blocks.add(block)
            }
            val result = ByteArray(rawCodewords)
            var idx = 0
            for (i in 0 until shortLen + 1) {
                for ((j, block) in blocks.withIndex()) {
                    if (i != shortLen - eccLen || j >= numShort) result[idx++] = block[i]
                }
            }
            return result
        }

        private fun rsDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < degree) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) {
                    result[i] = (result[i].toInt() xor gfMul(divisor[i].toInt() and 0xFF, factor)).toByte()
                }
            }
            return result
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor ((y ushr i) and 1) * x
            }
            return z and 0xFF
        }

        private fun build(ver: Int, codewords: ByteArray): QrCode {
            val size = ver * 4 + 17
            val m = Array(size) { BooleanArray(size) }
            val fn = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
                }
            }
            val align = alignmentPositions(ver)
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) {
                    set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
            drawFormat(m, fn, size, 0)
            if (ver >= 7) {
                var rem = ver
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val bit = ((bits ushr i) and 1) != 0
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }

            
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!fn[y][x] && i < codewords.size * 8) {
                            m[y][x] = ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                            i++
                        }
                    }
                }
                right -= 2
            }

            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(m, fn, size, mask)
                drawFormat(m, fn, size, mask)
                val p = penalty(m, size)
                if (p < bestPenalty) { bestPenalty = p; bestMask = mask }
                applyMask(m, fn, size, mask)
            }
            applyMask(m, fn, size, bestMask)
            drawFormat(m, fn, size, bestMask)
            return QrCode(size, m)
        }

        private fun alignmentPositions(ver: Int): IntArray {
            if (ver == 1) return IntArray(0)
            val num = ver / 7 + 2
            val step = (ver * 8 + num * 3 + 5) / (num * 4 - 4) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = ver * 4 + 10
            for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun drawFormat(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, mask: Int) {
            val data = (ECL_BITS shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun applyMask(m: Array<BooleanArray>, fn: Array<BooleanArray>, size: Int, mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (fn[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert) m[y][x] = !m[y][x]
            }
        }

        private fun penalty(m: Array<BooleanArray>, size: Int): Int {
            var result = 0
            fun lineScore(get: (Int) -> Boolean) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                fun push(len: Int) {
                    var l = len
                    if (history[0] == 0) l += size
                    System.arraycopy(history, 0, history, 1, 6)
                    history[0] = l
                }
                fun finderLike(): Int {
                    val n = history[1]
                    val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
                    return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                        (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
                }
                for (i in 0 until size) {
                    if (get(i) == runColor) {
                        runLen++
                        if (runLen == 5) result += 3 else if (runLen > 5) result++
                    } else {
                        push(runLen)
                        if (!runColor) result += finderLike() * 40
                        runColor = get(i)
                        runLen = 1
                    }
                }
                
                if (runColor) { push(runLen); runLen = 0 }
                runLen += size
                push(runLen)
                result += finderLike() * 40
            }
            for (y in 0 until size) lineScore { x -> m[y][x] }
            for (x in 0 until size) lineScore { y -> m[y][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in m) for (v in row) if (v) dark++
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        operator fun get(i: Int) = bits[i]
        fun append(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }
    }
}
