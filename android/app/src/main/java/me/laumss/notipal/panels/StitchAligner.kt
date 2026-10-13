package me.laumss.notipal.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.panels.DocScreenshotService.StitchImage
import kotlin.math.abs
import kotlin.math.max


object StitchAligner {

    private const val TAG = "StitchAligner"

    class Seam(val trimTop: Int, val overlap: Int)

    private const val COLS = 48
    private const val SAME_ROW = 3f
    private const val FLAT_ROW = 10f
    private const val BG_TOL = 16f
    private const val MATCH_MAX = 6f
    private const val COARSE = 4
    private const val EDGE_MAX = 0.3f
    private const val EDGE_GAP = 0.04f
    private const val MIN_OVERLAP = 0.05f
    private const val KEEP_BEST = 0.85f

    
    fun align(prev: StitchImage, next: StitchImage): Seam? {
        if (prev.width != next.width || prev.height != next.height) return null
        return try {
            val a = signatures(prev.path) ?: return null
            val b = signatures(next.path) ?: return null
            if (a.n != b.n) return null
            compute(a, b).also {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "align trimTop=${it.trimTop} overlap=${it.overlap}")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "align failed: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "align out of memory", e)
            null
        }
    }

    private fun compute(a: Rows, b: Rows): Seam {
        val n = a.n
        if (a.meanDiff(b) < SAME_ROW * 0.5f) return Seam(0, 0)

        val bg = a.background()
        val blankA = a.blanks(bg)
        val blankB = b.blanks(bg)
        var head = edgeBand(a, b, blankA, fromTop = true)
        var foot = edgeBand(a, b, blankA, fromTop = false)
        if (head + foot > n * 0.6f) { head = 0; foot = 0 }

        val c0 = head
        val c1 = n - foot
        val content = c1 - c0
        val minOverlap = max(12, (content * MIN_OVERLAP).toInt())
        if (content <= minOverlap + 1) return Seam(head, foot)

        val a4 = a.down(COARSE)
        val b4 = b.down(COARSE)
        val blankA4 = a4.blanks(bg)
        val blankB4 = b4.blanks(bg)
        val c04 = (c0 + COARSE - 1) / COARSE
        val c14 = c1 / COARSE
        val minOverlap4 = max(3, ((c14 - c04) * MIN_OVERLAP).toInt())
        val buf = FloatArray(n)

        val candidates = ArrayList<Pair<Float, Int>>()
        for (s in 1 until (c14 - c04) - minOverlap4) {
            val sc = score(a4, b4, blankA4, blankB4, s, c04, c14, 4, buf)
            if (!sc.isNaN()) candidates.add(sc to s)
        }
        candidates.sortBy { it.first }

        var best = Float.MAX_VALUE
        var bestShift = -1
        val tried = HashSet<Int>()
        for ((_, s4) in candidates.take(3)) {
            for (s in (s4 * COARSE - COARSE - 2)..(s4 * COARSE + COARSE + 2)) {
                if (s < 1 || s > content - minOverlap || !tried.add(s)) continue
                val sc = score(a, b, blankA, blankB, s, c0, c1, 12, buf)
                if (!sc.isNaN() && sc < best) { best = sc; bestShift = s }
            }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "head=$head foot=$foot shift=$bestShift score=$best")
        return if (bestShift > 0 && best <= MATCH_MAX) Seam(head, n - head - bestShift) else Seam(head, foot)
    }

    
    
    private fun edgeBand(a: Rows, b: Rows, blankA: BooleanArray, fromTop: Boolean): Int {
        val n = a.n
        val limit = (n * EDGE_MAX).toInt()
        val tolerance = max(4, (n * EDGE_GAP).toInt())
        var band = 0
        var run = 0
        for (i in 0 until limit) {
            val y = if (fromTop) i else n - 1 - i
            if (a.diff(y, b, y) <= SAME_ROW) {
                if (!blankA[y]) { band = i + 1; run = 0 }
            } else {
                run++
                if (run > tolerance) break
            }
        }
        return band
    }

    
    private fun score(
        a: Rows, b: Rows, blankA: BooleanArray, blankB: BooleanArray,
        shift: Int, c0: Int, c1: Int, minRows: Int, buf: FloatArray
    ): Float {
        var count = 0
        for (k in 0 until c1 - c0 - shift) {
            val ya = c0 + shift + k
            val yb = c0 + k
            if (blankA[ya] && blankB[yb]) continue
            buf[count++] = a.diff(ya, b, yb)
        }
        if (count < minRows) return Float.NaN
        java.util.Arrays.sort(buf, 0, count)
        val keep = max(minRows, (count * KEEP_BEST).toInt())
        var sum = 0f
        for (i in 0 until keep) sum += buf[i]
        return sum / keep
    }

    private fun signatures(path: String): Rows? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            
            val x0 = (w * 0.04f).toInt()
            val x1 = (w * 0.96f).toInt()
            val edges = IntArray(COLS + 1) { x0 + (x1 - x0) * it / COLS }
            val line = IntArray(w)
            val out = FloatArray(h * COLS)
            for (y in 0 until h) {
                bmp.getPixels(line, 0, w, 0, y, w, 1)
                for (c in 0 until COLS) {
                    var sum = 0
                    for (x in edges[c] until edges[c + 1]) {
                        val p = line[x]
                        sum += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    }
                    out[y * COLS + c] = sum.toFloat() / max(1, edges[c + 1] - edges[c])
                }
            }
            return Rows(h, out)
        } finally {
            bmp.recycle()
        }
    }

    private class Rows(val n: Int, val v: FloatArray) {

        fun diff(y: Int, o: Rows, oy: Int): Float {
            val i = y * COLS
            val j = oy * COLS
            var s = 0f
            for (c in 0 until COLS) s += abs(v[i + c] - o.v[j + c])
            return s / COLS
        }

        fun meanDiff(o: Rows): Float {
            var s = 0f
            for (y in 0 until n) s += diff(y, o, y)
            return s / n
        }

        private inline fun rowStats(y: Int, block: (lo: Float, hi: Float, mean: Float) -> Unit) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var sum = 0f
            for (c in 0 until COLS) {
                val x = v[y * COLS + c]
                if (x < lo) lo = x
                if (x > hi) hi = x
                sum += x
            }
            block(lo, hi, sum / COLS)
        }

        
        fun background(): Float {
            val means = ArrayList<Float>()
            for (y in 0 until n) rowStats(y) { lo, hi, mean -> if (hi - lo < FLAT_ROW) means.add(mean) }
            if (means.isEmpty()) return 255f
            means.sort()
            return means[means.size / 2]
        }

        
        fun blanks(bg: Float): BooleanArray = BooleanArray(n) { y ->
            var blank = false
            rowStats(y) { lo, hi, mean -> blank = hi - lo < FLAT_ROW && abs(mean - bg) < BG_TOL }
            blank
        }

        fun down(f: Int): Rows {
            val m = n / f
            val out = FloatArray(m * COLS)
            for (y in 0 until m) {
                for (c in 0 until COLS) {
                    var s = 0f
                    for (k in 0 until f) s += v[(y * f + k) * COLS + c]
                    out[y * COLS + c] = s / f
                }
            }
            return Rows(m, out)
        }
    }
}
