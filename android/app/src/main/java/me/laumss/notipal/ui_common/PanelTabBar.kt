package me.laumss.notipal.ui_common

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.ReactApplicationContext


class PanelTabBar(
    private val ctx: ReactApplicationContext,
    private val tabs: List<Tab>,
    private val sideMarginDp: Int = 28,
    private val onSelect: (index: Int) -> Unit
) {
    class Tab(val label: String)

    private fun dp(v: Int) = ScreenScale.dp(ctx, v)
    private fun sp(v: Float) = ScreenScale.sp(ctx, v)

    private var selected: Int = 0
    private val labels = mutableListOf<TextView>()
    private val indicators = mutableListOf<View>()

    fun setSelection(i: Int) {
        selected = i.coerceIn(0, tabs.size - 1)
        if (indicators.isNotEmpty()) updateStyles()
    }

    fun getSelection(): Int = selected

    fun createView(): View {
        val side = dp(sideMarginDp)
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
                leftMargin = side; rightMargin = side
            }
        }

        labels.clear(); indicators.clear()

        for ((idx, tab) in tabs.withIndex()) {
            val cell = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    if (selected != idx) {
                        selected = idx
                        updateStyles()
                        onSelect(idx)
                    }
                }
            }
            val label = TextView(ctx).apply {
                text = tab.label
                textSize = sp(17f)
                gravity = Gravity.CENTER
                isSingleLine = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            val indicator = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                setBackgroundColor(Color.BLACK)
            }
            cell.addView(label)
            cell.addView(indicator)
            labels.add(label)
            indicators.add(indicator)
            bar.addView(cell)
        }

        wrapper.addView(bar)
        
        wrapper.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = side; rightMargin = side
            }
            setBackgroundColor(Color.parseColor("#D8D8D8"))
        })
        updateStyles()
        return wrapper
    }

    private fun updateStyles() {
        for (i in tabs.indices) {
            val active = i == selected
            labels.getOrNull(i)?.apply {
                typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) Color.BLACK else Color.parseColor("#888888"))
            }
            indicators.getOrNull(i)?.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
    }
}
