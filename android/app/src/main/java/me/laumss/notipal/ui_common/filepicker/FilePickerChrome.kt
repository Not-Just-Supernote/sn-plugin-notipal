package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout


object FilePickerChrome {

    class BottomTrayHandle(
        val view: View,
        private val actionButton: TrayIconButton,
        private val divider: View,
        private val multiButton: TrayIconButton,
    ) {
        
        fun setAction(label: String, visible: Boolean, enabled: Boolean = true, count: Int = 0) {
            actionButton.contentDescription = label
            actionButton.count = count
            actionButton.visibility = if (visible) View.VISIBLE else View.GONE
            divider.visibility = if (visible) View.VISIBLE else View.GONE
            actionButton.isEnabled = enabled
        }

        fun setMultiSelection(visible: Boolean, enabled: Boolean, active: Boolean) {
            multiButton.visibility = if (visible) View.VISIBLE else View.GONE
            multiButton.isEnabled = enabled
            multiButton.active = active
        }
    }

    
    class TrayIconButton(ctx: Context, private val glyph: Glyph) : View(ctx) {
        enum class Glyph { BACK, CHECK, MULTI }

        var count = 0
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        
        var active = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val activeRect = RectF()
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val path = Path()
        private val badgeRect = RectF()

        init {
            isClickable = true
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val color = when {
                active -> Color.WHITE
                isEnabled -> Color.BLACK
                else -> Color.rgb(158, 158, 158)
            }
            if (active) {
                val inset = height * 0.08f
                activeRect.set(inset, inset, width - inset, height - inset)
                val radius = activeRect.height() / 2f
                canvas.drawRoundRect(activeRect, radius, radius, activePaint)
            }
            
            val size = minOf(width, height) * 0.6f
            val s = size / 24f
            val ox = (width - size) / 2f
            val oy = (height - size) / 2f
            strokePaint.color = color
            strokePaint.strokeWidth = 2.2f * s
            path.reset()
            when (glyph) {
                Glyph.BACK -> {
                    path.moveTo(ox + 19.5f * s, oy + 12f * s)
                    path.lineTo(ox + 4.5f * s, oy + 12f * s)
                    path.moveTo(ox + 11f * s, oy + 5.5f * s)
                    path.lineTo(ox + 4.5f * s, oy + 12f * s)
                    path.lineTo(ox + 11f * s, oy + 18.5f * s)
                }
                Glyph.CHECK -> {
                    path.moveTo(ox + 4f * s, oy + 12.5f * s)
                    path.lineTo(ox + 9.5f * s, oy + 18f * s)
                    path.lineTo(ox + 20f * s, oy + 6.5f * s)
                }
                Glyph.MULTI -> {
                    
                    path.moveTo(ox + 8f * s, oy + 4f * s)
                    path.lineTo(ox + 17.5f * s, oy + 4f * s)
                    path.quadTo(ox + 20f * s, oy + 4f * s, ox + 20f * s, oy + 6.5f * s)
                    path.lineTo(ox + 20f * s, oy + 16f * s)
                    path.addRoundRect(
                        ox + 4f * s, oy + 8f * s, ox + 16f * s, oy + 20f * s,
                        2.5f * s, 2.5f * s, Path.Direction.CW,
                    )
                    path.moveTo(ox + 7f * s, oy + 14f * s)
                    path.lineTo(ox + 9.3f * s, oy + 16.3f * s)
                    path.lineTo(ox + 13.2f * s, oy + 11.8f * s)
                }
            }
            canvas.drawPath(path, strokePaint)
            if (count > 0) drawBadge(canvas, color, ox + size + 1.5f * s, oy + 2.5f * s, s)
        }

        private fun drawBadge(canvas: Canvas, color: Int, cx: Float, cy: Float, s: Float) {
            val label = if (count > 99) "99+" else count.toString()
            badgeTextPaint.textSize = 9.5f * s
            val radius = 7.5f * s
            val halfWidth = maxOf(radius, badgeTextPaint.measureText(label) / 2f + 4f * s)
            badgeRect.set(cx - halfWidth, cy - radius, cx + halfWidth, cy + radius)
            badgePaint.color = Color.WHITE
            val ring = 1.5f * s
            canvas.drawRoundRect(
                badgeRect.left - ring, badgeRect.top - ring, badgeRect.right + ring, badgeRect.bottom + ring,
                radius + ring, radius + ring, badgePaint,
            )
            badgePaint.color = color
            canvas.drawRoundRect(badgeRect, radius, radius, badgePaint)
            val baseline = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(label, cx, baseline, badgeTextPaint)
        }
    }

    
    fun buildBottomBackTray(
        ctx: Context,
        onBack: () -> Unit,
        onAction: () -> Unit,
        onMulti: () -> Unit,
    ): BottomTrayHandle {
        val density = ctx.resources.displayMetrics.density
        val buttonW = FilePickerScale.dp(ctx, 52f)
        val buttonH = FilePickerScale.dp(ctx, 40f)
        val tray = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(FilePickerScale.dp(ctx, 6f), FilePickerScale.dp(ctx, 4f), FilePickerScale.dp(ctx, 6f), FilePickerScale.dp(ctx, 4f))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(FilePickerScale.dp(ctx, 1f), 0xffcccccc.toInt())
                cornerRadius = 28f * density
            }
            elevation = 4f * density
        }
        tray.addView(TrayIconButton(ctx, TrayIconButton.Glyph.BACK).apply {
            contentDescription = me.laumss.notipal.NativeLocale.t("picker_back")
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(buttonW, buttonH))
        val multi = TrayIconButton(ctx, TrayIconButton.Glyph.MULTI).apply {
            contentDescription = me.laumss.notipal.NativeLocale.t("multi_select")
            visibility = View.GONE
            setOnClickListener { onMulti() }
        }
        tray.addView(multi, LinearLayout.LayoutParams(buttonW, buttonH))
        val action = TrayIconButton(ctx, TrayIconButton.Glyph.CHECK).apply {
            contentDescription = me.laumss.notipal.NativeLocale.t("picker_select")
            visibility = View.GONE
            setOnClickListener { onAction() }
        }
        val divider = View(ctx).apply {
            setBackgroundColor(0xffcccccc.toInt())
            layoutParams = LinearLayout.LayoutParams(FilePickerScale.dp(ctx, 1f), FilePickerScale.dp(ctx, 22f)).apply {
                leftMargin = FilePickerScale.dp(ctx, 2f)
                rightMargin = FilePickerScale.dp(ctx, 2f)
            }
            visibility = View.GONE
        }
        tray.addView(divider)
        tray.addView(action, LinearLayout.LayoutParams(buttonW, buttonH))
        return BottomTrayHandle(tray, action, divider, multi)
    }

}
