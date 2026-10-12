package me.laumss.notipal.overlays
import me.laumss.notipal.*
import me.laumss.notipal.panels.*
import me.laumss.notipal.bubbles.*

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.LinearLayout

class TouchSinkLayout(context: Context) : LinearLayout(context) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_STYLUS)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
