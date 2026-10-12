package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.View


internal object FilePickerSelectionArtwork {
    private fun decode(data: String): Bitmap {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
    private val checked by lazy { decode("iVBORw0KGgoAAAANSUhEUgAAADoAAAA6CAAAAADE0BX0AAABKElEQVRIx+3WIU8DMRiA4TaBiSVkYWQWM4Ei2R8AsV/ABBYHCo1jYvMIDkLCT9gEE7NDgMGQETwGOSRhySDZO3EFBlx3/T5H0k/fk9y1b5szJk6cOGGzmtxdlFSycAt0VPQSYKyRhwAMFbL+ATDZksvqCwAHcrnyCMC5XNoeANcFOW0D8FSRy90pwOumXNbeAKYNuaw8A9BU9HcDQNcq++Oh6H2gfNLfs/7+Ruv+jbsHEuvtb9v/VhuQaauj3P7W3jOt6+9s4WIcAcCpzehvsLx4IZsZthXY318b3t9v6/rbCdn8n9b1dxwWzrx1/XVC+5uzaX/DYnCwXza3P6+90tx/zgKwLzxk3zYRH9BPO1gySqu5/1Kruf9Sm9ef92NaxvTir0icfzAzJsklBbuZT48AAAAASUVORK5CYII=") }
    private val unchecked by lazy { decode("iVBORw0KGgoAAAANSUhEUgAAADoAAAA6CAAAAADE0BX0AAAAL0lEQVRIx+3NwQkAQAgEsfX671l78HEgZP5hkmNVkt65t7+iKIqiKIqiKPqbnmsAbXIBaHztXTUAAAAASUVORK5CYII=") }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun draw(canvas: Canvas, selected: Boolean, bounds: RectF) {
        canvas.drawBitmap(if (selected) checked else unchecked, null, bounds, paint)
    }
}

internal class FilePickerSelectionMark(ctx: Context) : View(ctx) {
    var checked = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val bounds = RectF()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        FilePickerSelectionArtwork.draw(canvas, checked, bounds)
    }
}
