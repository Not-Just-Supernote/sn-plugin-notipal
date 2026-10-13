package me.laumss.notipal.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.panels.DocScreenshotService.StitchParams
import me.laumss.notipal.panels.DocScreenshotService.StitchSessionData
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


object StitchLayout {

    private const val TAG = "StitchLayout"

    
    const val MIN_VISIBLE_PX = 24

    
    class Piece(val index: Int, val src: Rect, val dst: RectF)

    class Result(val width: Int, val height: Int, val pieces: List<Piece>)

    fun compute(s: StitchSessionData): Result =
        if (s.params.mode == StitchParams.MODE_GRID) grid(s) else strip(s)

    
    fun maxOverlap(s: StitchSessionData, i: Int): Int {
        if (i <= 0 || i >= s.images.size) return 0
        val srcs = stripSources(s)
        return max(0, min(srcs[i - 1].height() - MIN_VISIBLE_PX, srcs[i].height()))
    }

    private fun stripSources(s: StitchSessionData): List<Rect> {
        val p = s.params
        val last = s.images.size - 1
        return s.images.mapIndexed { i, img ->
            val l = (img.width * p.cropLeft).roundToInt()
            val r = max(l + 1, (img.width * (1f - p.cropRight)).roundToInt())
            val b = if (i == last) (img.height * (1f - p.cropBottom)).roundToInt() else img.height
            val t = if (i == 0) (img.height * p.cropTop).roundToInt() else img.trimTop
            Rect(l, t.coerceIn(0, max(0, b - MIN_VISIBLE_PX)), r, b)
        }
    }

    private fun strip(s: StitchSessionData): Result {
        val srcs = stripSources(s)
        if (srcs.isEmpty()) return Result(0, 0, emptyList())
        val width = srcs.maxOf { it.width() }
        val pieces = ArrayList<Piece>(srcs.size)
        var y = 0f
        for ((i, src) in srcs.withIndex()) {
            if (i > 0) {
                val cap = max(0, min(srcs[i - 1].height() - MIN_VISIBLE_PX, src.height()))
                y -= s.images[i].overlap.coerceIn(0, cap)
            }
            val x = (width - src.width()) / 2f
            pieces.add(Piece(i, src, RectF(x, y, x + src.width(), y + src.height())))
            y += src.height()
        }
        val height = pieces.maxOf { it.dst.bottom }.roundToInt()
        return Result(width, height, pieces)
    }

    private fun grid(s: StitchSessionData): Result {
        val p = s.params
        val n = s.images.size
        if (n == 0) return Result(0, 0, emptyList())
        val cols = p.cols.coerceIn(1, n)
        val rows = (n + cols - 1) / cols
        val srcs = s.images.map { img ->
            val l = (img.width * p.cropLeft).roundToInt()
            val t = (img.height * p.cropTop).roundToInt()
            Rect(
                l, t,
                max(l + 1, (img.width * (1f - p.cropRight)).roundToInt()),
                max(t + 1, (img.height * (1f - p.cropBottom)).roundToInt())
            )
        }
        val cellW = srcs.maxOf { it.width() }
        val cellH = srcs.maxOf { it.height() }
        val byColumn = p.gridOrder == "col"
        val pieces = srcs.mapIndexed { i, src ->
            val col = if (byColumn) i / rows else i % cols
            val row = if (byColumn) i % rows else i / cols
            val fit = min(cellW.toFloat() / src.width(), cellH.toFloat() / src.height())
            val w = src.width() * fit
            val h = src.height() * fit
            val x = col * cellW + (cellW - w) / 2f
            val y = row * cellH + (cellH - h) / 2f
            Piece(i, src, RectF(x, y, x + w, y + h))
        }
        return Result(cellW * cols, cellH * rows, pieces)
    }

    
    fun render(s: StitchSessionData, outPath: String): Pair<Int, Int>? {
        val layout = compute(s)
        if (layout.width <= 0 || layout.height <= 0) return null
        var out: Bitmap? = null
        return try {
            val result = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            out = result
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (piece in layout.pieces) {
                val bmp = BitmapFactory.decodeFile(s.images[piece.index].path) ?: return null
                canvas.drawBitmap(bmp, piece.src, piece.dst, paint)
                bmp.recycle()
            }
            FileOutputStream(outPath).use { fos ->
                if (!result.compress(Bitmap.CompressFormat.PNG, 100, fos)) return null
            }
            layout.width to layout.height
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "render failed: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "render out of memory", e)
            null
        } finally {
            out?.recycle()
        }
    }
}
