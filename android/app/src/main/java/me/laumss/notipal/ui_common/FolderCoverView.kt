package me.laumss.notipal.ui_common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView


class FolderCoverView(context: Context) : ViewGroup(context) {

    private data class Geometry(
        val tabW: Float, val tabH: Float, val slant: Float, val radius: Float,
        val slots: List<RectF>
    )

    private val strokePx = ScreenScale.dp(context, 1.5f).coerceAtLeast(2).toFloat()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = strokePx
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val outline = Path()
    private var geo: Geometry? = null

    
    val slots: List<ImageView> = List(4) { makeSlot() }

    init {
        setWillNotDraw(false)
        slots.forEach { addView(it) }
    }

    private fun makeSlot(): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        val border = ScreenScale.dp(context, 1).coerceAtLeast(1)
        setPadding(border, border, border, border)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(border, Color.parseColor("#999999"))
        }
        visibility = INVISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val g = geometry(w, h, strokePx).also { geo = it }
        for ((i, slot) in slots.withIndex()) {
            val r = g.slots[i]
            slot.measure(
                MeasureSpec.makeMeasureSpec(r.width().toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height().toInt(), MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = geo ?: return
        for ((i, slot) in slots.withIndex()) {
            val rect = g.slots[i]
            val x = rect.left.toInt(); val y = rect.top.toInt()
            slot.layout(x, y, x + slot.measuredWidth, y + slot.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val g = geo ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val i = strokePx / 2f
        val r = g.radius
        outline.reset()
        outline.moveTo(i, g.tabH + r)
        outline.lineTo(i, i + r)
        outline.quadTo(i, i, i + r, i)
        outline.lineTo(g.tabW, i)
        outline.lineTo(g.tabW + g.slant, g.tabH)
        outline.lineTo(w - i - r, g.tabH)
        outline.quadTo(w - i, g.tabH, w - i, g.tabH + r)
        outline.lineTo(w - i, h - i - r)
        outline.quadTo(w - i, h - i, w - i - r, h - i)
        outline.lineTo(i + r, h - i)
        outline.quadTo(i, h - i, i, h - i - r)
        outline.close()
        canvas.drawPath(outline, fillPaint)
        canvas.drawPath(outline, outlinePaint)
    }

    companion object {
        private fun geometry(w: Int, h: Int, stroke: Float): Geometry {
            val tabH = (h * 0.075f).coerceAtLeast(stroke * 3)
            val pad = w * 0.085f
            val gap = w * 0.05f
            val top = tabH + pad
            val bottom = h - stroke / 2f - pad
            val slotW = ((w - 2 * pad - gap) / 2f).coerceAtLeast(1f)
            val slotH = ((bottom - top - gap) / 2f).coerceAtLeast(1f)
            val slots = List(4) { k ->
                val x = pad + (k % 2) * (slotW + gap)
                val y = top + (k / 2) * (slotH + gap)
                RectF(x, y, x + slotW, y + slotH)
            }
            return Geometry(
                tabW = w * 0.42f, tabH = tabH, slant = tabH * 0.9f, radius = w * 0.045f,
                slots = slots
            )
        }

        
        fun slotSize(ctx: Context, w: Int, h: Int): Pair<Int, Int> {
            val r = geometry(w, h, ScreenScale.dp(ctx, 1.5f).coerceAtLeast(2).toFloat()).slots[0]
            return r.width().toInt() to r.height().toInt()
        }
    }
}
