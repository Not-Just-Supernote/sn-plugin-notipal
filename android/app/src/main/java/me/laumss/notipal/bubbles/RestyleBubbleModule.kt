package me.laumss.notipal.bubbles

import me.laumss.notipal.TouchInput
import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.overlays.*

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import me.laumss.notipal.ui_common.VectorAssets
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

class PaletteBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "PaletteBubble"

    private val TAG = "PaletteBubble"
    private val handler = Handler(Looper.getMainLooper())

    init { currentInstance = this }

    data class PresetData(val penId: String, val color: String, val thickness: Int, val penType: Int = 10)

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: DraggablePaletteLayout? = null
        @Volatile @JvmStatic private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic private var startX = 0
        @Volatile @JvmStatic private var startY = 0

        @Volatile @JvmStatic private var screenHeight = 1872
        @Volatile @JvmStatic private var screenWidth = 1404

        @Volatile @JvmStatic private var stickyX: Int = 24
        @Volatile @JvmStatic private var stickyY: Int = 200

        @Volatile @JvmStatic private var wantShown: Boolean = false

        @Volatile @JvmStatic
        private var currentInstance: PaletteBubbleModule? = null

        @Volatile @JvmStatic
        private var presets: Array<PresetData?> = arrayOfNulls(MAX_SLOTS)
        private var presetsMosaic = false

        private const val PREFS_NAME = "quicktoolbar_presets"
        
        private const val COLS = 5
        private const val VISIBLE_COLS = 5
        private const val ROWS = 2
        private const val MAX_SLOTS = COLS * ROWS - 1

        
        private val INK = Color.BLACK
        private val LINE = Color.parseColor("#C6C6C6")
        private val LINE2 = Color.parseColor("#9D9D9D")

        @JvmStatic fun toggleStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    wantShown = false
                    hideStatic()
                } else {
                    wantShown = true
                    reloadPresetsInternal()
                    currentInstance?.createBubble()
                }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    bubbleView?.let { FloatingDragRefresh.endNow(it) }
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; layoutParams = null
                }
                currentInstance?.notifyToolbarHighlight()
            }
        }

        
        @JvmStatic fun hideAndForget() {
            wantShown = false
            hideStatic()
        }

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext, force: Boolean = false) {
            if (!wantShown) return
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (!FloatingToolbarModule.isInNoteApp()) return@post
                
                if (FloatingToolbarModule.anyNativeOverlayOwnsScreen()) return@post
                if (bubbleView != null) {
                    if (!force) return@post
                    bubbleView?.let { FloatingDragRefresh.endNow(it) }
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; layoutParams = null
                }
                reloadPresetsInternal()
                val inst = currentInstance
                    ?: try { ctx.getNativeModule(PaletteBubbleModule::class.java) } catch (_: Exception) { null }
                inst?.createBubble()
            }
        }

        @JvmStatic fun handleOrientationChange() {
            Handler(Looper.getMainLooper()).post {
                val inst = currentInstance ?: return@post
                try {
                    val dm = inst.reactApplicationContext.resources.displayMetrics
                    val newW = dm.widthPixels; val newH = dm.heightPixels
                    if (newW == screenWidth && newH == screenHeight) return@post
                    screenWidth = newW; screenHeight = newH
                    val lp = layoutParams ?: return@post
                    val v = bubbleView ?: return@post
                    
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }

        @JvmStatic fun reloadPresets() {
            Handler(Looper.getMainLooper()).post {
                val prevMosaic = presetsMosaic
                val prevPresets = presets
                reloadPresetsInternal()
                
                if (bubbleView != null &&
                    (prevMosaic != presetsMosaic || !prevPresets.contentEquals(presets))) {
                    currentInstance?.rebuildSlots()
                }
            }
        }

        private fun reloadPresetsInternal() {
            val inst = currentInstance ?: return
            presetsMosaic = MosaicLink.isBoardSurface()
            presets = arrayOfNulls(MAX_SLOTS)
            try {
                val prefs = inst.reactApplicationContext.getSharedPreferences(PREFS_NAME, 0)
                val json = prefs.getString(MosaicPaletteSpec.presetsKey(presetsMosaic), null)
                val newPresets = arrayOfNulls<PresetData>(MAX_SLOTS)
                if (json != null) {
                    val arr = JSONArray(json)
                    for (i in 0 until minOf(MAX_SLOTS, arr.length())) {
                        if (arr.isNull(i)) continue
                        val o = arr.getJSONObject(i)
                        val penId = if (presetsMosaic) MosaicPaletteSpec.normalize(o.getString("penId"))
                            else o.getString("penId")
                        val penType = if (presetsMosaic) MosaicPaletteSpec.penType(penId) else o.getInt("penType")
                        newPresets[i] = PresetData(
                            penId = penId,
                            color = if (presetsMosaic) MosaicPaletteSpec.color(penId) else o.getString("color"),
                            thickness = PenSizeSpec.snap(penType, o.getInt("thickness")),
                            penType = penType
                        )
                    }
                }
                presets = newPresets
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.w("PaletteBubble", "reloadPresets: ${e.message}")
            }
        }
    }

    
    @ReactMethod fun hideTemporarily() { hideStatic() }

    @ReactMethod fun restore() { reshowLast(reactApplicationContext) }

    @ReactMethod fun show() {
        handler.post {
            wantShown = true
            if (bubbleView != null) return@post
            reloadPresetsInternal()
            createBubble()
        }
    }

    private fun createBubble() {
        if (FloatingToolbarModule.areFloatingOverlaysSuspendedForPalette()) return
        val context = reactApplicationContext
        
        removeBubble(notify = false)

        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels
        val u = dm.density * BubbleWindowSupport.bubbleScale(context)

        bubbleView = DraggablePaletteLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((12 * u).toInt(), (2 * u).toInt(), (6 * u).toInt(), (10 * u).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((1.5f * u).toInt().coerceAtLeast(1), INK)
                cornerRadius = 12 * u
            }
            frameWidth = 0f
        }
        populateSlots(bubbleView!!)

        layoutParams = BubbleWindowSupport.overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            x = stickyX
            y = stickyY
        }

        
        
        bubbleView!!.onDragStart = {
            val lp = layoutParams
            val view = bubbleView
            if (lp != null && view != null) {
                startX = lp.x
                startY = lp.y
                FloatingDragRefresh.begin(view, lp.x, lp.y)
            }
        }
        bubbleView!!.onDrag = { dx, dy ->
            val lp = layoutParams
            val view = bubbleView
            if (lp != null && view != null) {
                lp.x = startX + dx
                lp.y = startY + dy
                if (!FloatingDragRefresh.move(view, lp.x, lp.y)) {
                    try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                }
            }
        }
        bubbleView!!.onDragEnd = {
            val lp = layoutParams
            val view = bubbleView
            if (lp != null && view != null) {
                val dm = reactApplicationContext.resources.displayMetrics
                screenWidth = dm.widthPixels; screenHeight = dm.heightPixels
                try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                FloatingDragRefresh.end(view)
                stickyX = lp.x
                stickyY = lp.y
            }
        }

        try {
            windowManager?.addView(bubbleView, layoutParams)
            bubbleView?.let { FloatingPenGuard.track("restyle_bubble", it) }
        } catch (e: Exception) {
            Log.e(TAG, "addView FAILED: ${e.message}", e)
            bubbleView = null; layoutParams = null
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "palette bubble shown ($MAX_SLOTS pen slots + settings)")
        notifyToolbarHighlight()
    }

    
    private fun populateSlots(container: LinearLayout) {
        container.removeAllViews()
        val context = reactApplicationContext
        val sf = BubbleWindowSupport.bubbleScale(context)
        val u = context.resources.displayMetrics.density * sf

        val hasPresets = presets.any { it != null }
        container.addView(TextView(context).apply {
            text = NativeLocale.t(if (hasPresets) "palette_bubble_hint" else "palette_bubble_empty")
            textSize = 11.5f * sf
            setTextColor(INK)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (9 * u).toInt()
                bottomMargin = (9 * u).toInt()
            }
        })

        for (row in 0 until ROWS) {
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = (4 * u).toInt() }
            }
            for (col in 0 until VISIBLE_COLS) {
                rowLayout.addView(makeSlotView(row * COLS + col, u))
            }
            container.addView(rowLayout)
        }
    }

    private fun makeSlotView(index: Int, u: Float): View {
        val context = reactApplicationContext
        val settingsCell = index == MAX_SLOTS
        val preset = presets.getOrNull(index)
        val sf = BubbleWindowSupport.bubbleScale(context)
        val circleSz = (44 * u).toInt()

        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                circleSz, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (index % COLS > 0) marginStart = (10 * u).toInt() }
        }

        val circle = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(circleSz, circleSz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                if (preset == null) {
                    val dash = (2 * u).coerceAtLeast(1f)
                    setStroke((1 * u).toInt().coerceAtLeast(1), LINE2, dash, dash)
                } else {
                    setStroke((1.5f * u).toInt().coerceAtLeast(1), INK)
                }
            }
        }
        cell.addView(circle)

        cell.addView(TextView(context).apply {
            text = if (settingsCell) NativeLocale.t("palette_open_settings")
                else preset?.let { PenSizeSpec.label(it.penType, it.thickness) }.orEmpty()
            textSize = 11.5f * sf
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(circleSz, (18 * u).toInt())
        })

        if (settingsCell) {
            circle.addView(TextView(context).apply {
                text = "+"
                textSize = 28f * sf
                setTextColor(INK)
                gravity = Gravity.CENTER
                includeFontPadding = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            cell.contentDescription = NativeLocale.t("palette_header_title")
            cell.setOnClickListener {
                FloatingToolbarModule.currentInstance?.openPaletteSettingsFromBubble(cell)
            }
            return cell
        }
        if (preset == null) return cell

        val mosaicPreset = presetsMosaic
        val iconSz = (26 * u).toInt()
        circle.addView(ImageView(context).apply {
            setImageBitmap(VectorAssets.loadBitmap(
                context, if (mosaicPreset) MosaicPaletteSpec.iconAsset(preset.penId)
                else PenSizeSpec.penIconAsset(preset.penType, preset.color), iconSz))
            layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER)
        })

        cell.setOnClickListener {
            if (mosaicPreset != MosaicLink.isBoardSurface()) {
                reloadPresets()
                return@setOnClickListener
            }
            if (mosaicPreset) {
                MosaicLink.applyPalettePreset(reactApplicationContext, preset.penId, preset.thickness) { result ->
                    if (!result.optBoolean("success")) me.laumss.notipal.ui_common.Dialog.tip(
                        reactApplicationContext, NativeLocale.t("palette_mosaic_retry"))
                }
                return@setOnClickListener
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "slot $index tapped: color=${preset.color} thickness=${preset.thickness} penType=${preset.penType}")
            emitEvent("onPaletteBubbleSlotTap", Arguments.createMap().apply {
                putInt("slotIndex", index)
                putString("color", preset.color)
                putInt("thickness", preset.thickness)
                putInt("penType", preset.penType)
            })
        }

        return cell
    }

    private fun rebuildSlots() {
        val view = bubbleView ?: return
        populateSlots(view)
        try { windowManager?.updateViewLayout(view, layoutParams) } catch (_: Exception) {}
    }

    private fun removeBubble(notify: Boolean = true) {
        if (bubbleView != null) {
            bubbleView?.let { FloatingDragRefresh.endNow(it) }
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; layoutParams = null
        }
        if (notify) notifyToolbarHighlight()
    }

    private fun emitEvent(name: String, params: WritableMap) =
        BubbleWindowSupport.emitEvent(reactApplicationContext, TAG, name, params)

    private fun notifyToolbarHighlight() {
        try {
            FloatingToolbarModule.currentInstance?.setToolActive(
                FloatingToolbarModule.INK_PALETTE_TOOL_ID,
                bubbleView != null
            )
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "notifyToolbarHighlight: ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping palette bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}


class DraggablePaletteLayout(context: android.content.Context) : LinearLayout(context) {
    private companion object {
        
        const val DRAG_THRESHOLD_PX = 10
    }

    var onDragStart: (() -> Unit)? = null
    var onDrag: ((Int, Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false

    private fun beginDragIfMoved(ev: MotionEvent): Boolean {
        if (dragging) return true
        if (Math.abs(ev.rawX - downRawX) > DRAG_THRESHOLD_PX || Math.abs(ev.rawY - downRawY) > DRAG_THRESHOLD_PX) {
            dragging = true
            onDragStart?.invoke()
        }
        return dragging
    }

    
    var frameWidth = 0f
    var frameColor = Color.BLACK
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (frameWidth > 0f) {
            framePaint.strokeWidth = frameWidth
            framePaint.color = frameColor
            val h = frameWidth / 2f
            canvas.drawRect(h, h, width - h, height - h, framePaint)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_STYLUS)) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!TouchInput.isFinger(ev)) return false
                if (beginDragIfMoved(ev)) return true
            }
        }
        return false
    }

    
    
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!TouchInput.isFinger(ev)) return true
                if (beginDragIfMoved(ev)) {
                    onDrag?.invoke((ev.rawX - downRawX).toInt(), (ev.rawY - downRawY).toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onDragEnd?.invoke()
                    dragging = false
                }
            }
        }
        return true
    }
}
