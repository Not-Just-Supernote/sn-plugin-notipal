package me.laumss.notipal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.WeakHashMap


internal object FloatingDragRefresh {
    private const val TAG = "InklingDragRefresh"
    private const val OVERLAY_UPDATE_MODE = 11
    private const val RELEASE_DELAY_MS = 200L

    private val handler = Handler(Looper.getMainLooper())
    private val activeDrags = WeakHashMap<View, DragState>()

    private class DragState(
        val windowManager: WindowManager,
        val proxyRoot: FrameLayout?,
        val proxyImage: ImageView?,
        val bitmap: Bitmap?,
        var release: Runnable? = null,
        var followsLayoutParams: Boolean = false,
        var layoutOriginX: Int = 0,
        var layoutOriginY: Int = 0,
        var screenOriginX: Int = 0,
        var screenOriginY: Int = 0,
        var xDirection: Int = 1,
        var yDirection: Int = 1
    )

    
    fun begin(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        begin(view, location[0], location[1])
    }

    
    fun begin(view: View, params: WindowManager.LayoutParams) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        begin(view, location[0], location[1])

        val absoluteGravity = Gravity.getAbsoluteGravity(params.gravity, view.layoutDirection)
        synchronized(activeDrags) {
            activeDrags[view]?.apply {
                followsLayoutParams = true
                layoutOriginX = params.x
                layoutOriginY = params.y
                screenOriginX = location[0]
                screenOriginY = location[1]
                xDirection = if (
                    (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
                ) -1 else 1
                yDirection = if (
                    (absoluteGravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                ) -1 else 1
            }
        }
    }

    fun begin(view: View, screenX: Int, screenY: Int) {
        val existing = synchronized(activeDrags) { activeDrags[view] }
        if (existing != null) {
            val pendingRelease = existing.release
            if (pendingRelease == null) {
                move(view, screenX, screenY)
                return
            }
            handler.removeCallbacks(pendingRelease)
            synchronized(activeDrags) {
                if (activeDrags[view] === existing) activeDrags.remove(view)
            }
            view.alpha = 1f
            destroyProxy(view, existing)
        }

        setViewMode(view, true)
        val state = createProxy(view, screenX, screenY)
        synchronized(activeDrags) { activeDrags[view] = state }

        if (state.proxyImage != null) {
            view.alpha = 0f
            Log.i(
                TAG,
                "dragProxy begin mode=$OVERLAY_UPDATE_MODE screen=$screenX,$screenY " +
                    "size=${view.width}x${view.height} view=${view.javaClass.name}"
            )
        } else {
            Log.w(TAG, "dragProxy fallback mode=$OVERLAY_UPDATE_MODE view=${view.javaClass.name}")
        }
    }

    
    fun move(view: View, screenX: Int, screenY: Int): Boolean {
        val image = synchronized(activeDrags) { activeDrags[view]?.proxyImage } ?: return false
        image.x = screenX.toFloat()
        image.y = screenY.toFloat()
        image.postInvalidateOnAnimation()
        return true
    }

    
    fun move(view: View, params: WindowManager.LayoutParams): Boolean {
        val state = synchronized(activeDrags) { activeDrags[view] } ?: return false
        if (!state.followsLayoutParams) return false
        return move(
            view,
            state.screenOriginX + (params.x - state.layoutOriginX) * state.xDirection,
            state.screenOriginY + (params.y - state.layoutOriginY) * state.yDirection
        )
    }

    
    fun end(view: View) {
        val state = synchronized(activeDrags) { activeDrags[view] } ?: return
        state.release?.let { handler.removeCallbacks(it) }

        view.alpha = 1f
        view.postInvalidateOnAnimation()

        lateinit var release: Runnable
        release = Runnable {
            val owned = synchronized(activeDrags) {
                if (activeDrags[view]?.release === release) {
                    activeDrags.remove(view)
                    true
                } else {
                    false
                }
            }
            if (owned) destroyProxy(view, state)
        }
        state.release = release
        handler.postDelayed(release, RELEASE_DELAY_MS)
        Log.i(
            TAG,
            "dragProxy release scheduled delayMs=$RELEASE_DELAY_MS mode=$OVERLAY_UPDATE_MODE " +
                "view=${view.javaClass.name}"
        )
    }

    
    fun endNow(view: View) {
        val state = synchronized(activeDrags) { activeDrags.remove(view) } ?: return
        state.release?.let { handler.removeCallbacks(it) }
        view.alpha = 1f
        destroyProxy(view, state)
    }

    private fun createProxy(view: View, screenX: Int, screenY: Int): DragState {
        val wm = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return DragState(wm, null, null, null)

        var bitmap: Bitmap? = null
        var root: FrameLayout? = null
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            val image = ImageView(view.context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                x = screenX.toFloat()
                y = screenY.toFloat()
                layoutParams = FrameLayout.LayoutParams(width, height)
            }

            root = FrameLayout(view.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                clipChildren = false
                clipToPadding = false
                addView(image)
            }
            val displayMetrics = view.resources.displayMetrics
            root.measure(
                View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(root, lp)
            
            
            
            setViewMode(root, true)
            setViewMode(image, true)
            image.postInvalidateOnAnimation()
            Log.i(
                TAG,
                "dragProxy attached root=${root.width}x${root.height} image=${image.width}x${image.height} " +
                    "rootAttached=${root.isAttachedToWindow} imageAttached=${image.isAttachedToWindow}"
            )
            return DragState(wm, root, image, bitmap)
        } catch (e: Exception) {
            root?.let { runCatching { wm.removeViewImmediate(it) } }
            bitmap?.recycle()
            Log.w(TAG, "dragProxy create exception view=${view.javaClass.simpleName}: ${e.message}")
            return DragState(wm, null, null, null)
        }
    }

    private fun destroyProxy(view: View, state: DragState) {
        state.proxyRoot?.let { root ->
            runCatching { state.windowManager.removeViewImmediate(root) }
                .onFailure { Log.w(TAG, "dragProxy remove exception: ${it.message}") }
        }
        setViewMode(view, false)
        state.bitmap?.recycle()
        Log.i(TAG, "dragProxy released mode=$OVERLAY_UPDATE_MODE view=${view.javaClass.name}")
    }

    private fun setViewMode(view: View, enable: Boolean): Boolean {
        return try {
            if (enable) {
                View::class.java.getMethod("setEinkUpdateMode", Int::class.javaPrimitiveType)
                    .invoke(view, OVERLAY_UPDATE_MODE)
                Log.i(
                    TAG,
                    "overlayMode=$OVERLAY_UPDATE_MODE applied attached=${view.isAttachedToWindow} " +
                        "size=${view.width}x${view.height} view=${view.javaClass.name}"
                )
            } else {
                View::class.java.getMethod("resetEinkUpdateMode").invoke(view)
                view.postInvalidateOnAnimation()
            }
            true
        } catch (e: Exception) {
            Log.w(
                TAG,
                "overlayMode call exception enable=$enable view=${view.javaClass.simpleName}: ${e.message}"
            )
            false
        }
    }
}
