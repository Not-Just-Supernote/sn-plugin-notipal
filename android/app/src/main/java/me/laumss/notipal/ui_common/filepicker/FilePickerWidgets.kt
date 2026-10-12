package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView


object FilePickerWidgets {
    class FormatChips(
        private val ctx: Context,
        keys: List<String>,
        private val onChange: (Set<String>) -> Unit,
    ) {
        private var keys: List<String> = keys.distinct()
        private val selected = LinkedHashSet<String>()
        private var container: LinearLayout? = null

        fun createView(): View {
            val row = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
            }
            container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                
                setPadding(d(26), d(12), d(26), d(16))
            }
            rebuild()
            row.addView(container)
            return row
        }

        fun setKeys(next: List<String>) {
            val normalized = next.distinct()
            if (normalized == keys) return
            keys = normalized
            selected.retainAll(keys.toSet())
            rebuild()
        }

        fun clearSelection() {
            if (selected.isEmpty()) return
            selected.clear()
            rebuild()
        }

        fun getSelection(): Set<String> = selected.toSet()

        private fun rebuild() {
            val c = container ?: return
            c.removeAllViews()
            (c.parent as? HorizontalScrollView)?.scrollTo(0, 0)
            keys.forEachIndexed { index, key ->
                val active = key in selected
                c.addView(TextView(ctx).apply {
                    text = key.uppercase(java.util.Locale.ROOT)
                    textSize = FilePickerScale.sp(ctx, 14f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(if (active) Color.WHITE else Color.BLACK)
                    gravity = Gravity.CENTER
                    setPadding(d(14), d(8), d(14), d(8))
                    background = GradientDrawable().apply {
                        setColor(if (active) Color.BLACK else Color.WHITE)
                        setStroke(2, Color.BLACK)
                        cornerRadius = d(53).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) marginStart = d(20) }
                    setOnClickListener {
                        if (!selected.add(key)) selected.remove(key)
                        rebuild()
                        onChange(selected.toSet())
                    }
                })
            }
        }

        private fun d(v: Int) = FilePickerScale.dp(ctx, v)
    }
}
