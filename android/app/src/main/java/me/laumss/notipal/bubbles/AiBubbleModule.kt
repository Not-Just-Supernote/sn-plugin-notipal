package me.laumss.notipal.bubbles

import me.laumss.notipal.TouchInput
import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.overlays.*
import me.laumss.notipal.panels.*
import me.laumss.notipal.ui_common.ScreenScale
import me.laumss.notipal.ui_common.FloatingChromeColors
import me.laumss.notipal.ui_common.VectorAssets

import android.graphics.Color
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
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import kotlin.math.roundToInt

class AiBubbleModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "AiBubble"

    private val TAG = "AiBubble"
    private val handler = Handler(Looper.getMainLooper())
    private var modeMenuActionId: String? = null

    private val CLR_INK       = Color.parseColor("#1E1E1B")
    private val CLR_GRIP = FloatingChromeColors.dividerAndGrip
    private val CLR_PANEL     = Color.WHITE
    private val CLR_LINE      = Color.parseColor("#CBCBC4")
    private val CLR_SEL_FG    = Color.WHITE

    private val MODE_AI    = "ai"
    private val MODE_VOICE = "voice"

    init { currentInstance = this }

    companion object {
        @Volatile @JvmStatic private var windowManager: WindowManager? = null
        @Volatile @JvmStatic private var bubbleView: View? = null
        @Volatile @JvmStatic private var statusText: TextView? = null
        @Volatile @JvmStatic private var iconView: ImageView? = null
        @Volatile @JvmStatic private var actionRow: LinearLayout? = null
        @Volatile @JvmStatic private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic private var startX = 0
        @Volatile @JvmStatic private var startY = 0
        @Volatile @JvmStatic private var startRawX = 0f
        @Volatile @JvmStatic private var startRawY = 0f
        @Volatile @JvmStatic private var isDragging = false

        @Volatile @JvmStatic private var pageHeight = 1872
        @Volatile @JvmStatic private var screenHeight = 1872
        @Volatile @JvmStatic private var screenWidth = 1404

        @Volatile @JvmStatic private var stickyY: Int = 120

        @Volatile @JvmStatic private var cachedActionsJson: String = "[]"
        @Volatile @JvmStatic private var cachedInboxJson: String = "[]"
        @Volatile @JvmStatic private var inboxCol: LinearLayout? = null

        @JvmStatic private fun clampY(desired: Int, viewH: Int): Int =
            BubbleWindowSupport.clampToBand(screenHeight, desired, viewH)

        
        private const val BAR_WIDTH_DP = 310
        private const val CAPSULE_WIDTH_DP = 537   
        private const val MAX_CAPSULES = 4

        @Volatile @JvmStatic var lastShownText: String = ""
        @Volatile @JvmStatic private var currentMode: String = "ai"

        

        @Volatile @JvmStatic
        private var currentInstance: AiBubbleModule? = null

        @JvmStatic fun reshowLast(ctx: ReactApplicationContext, force: Boolean = false) {
            if (lastShownText.isEmpty()) return
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                if (!FloatingToolbarModule.isInNoteApp()) return@post
                
                if (FloatingToolbarModule.anyNativeOverlayOwnsScreen()) return@post
                try {
                    if (bubbleView != null) {
                        if (!force) {
                            statusText?.text = lastShownText
                            return@post
                        }
                        bubbleView?.let { FloatingDragRefresh.endNow(it) }
                        try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                        bubbleView = null; statusText = null; iconView = null
                        actionRow = null; inboxCol = null
                        layoutParams = null
                    }
                    val inst = currentInstance
                        ?: try { ctx.getNativeModule(AiBubbleModule::class.java) } catch (_: Exception) { null }
                    inst?.createBubble(lastShownText)
                } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w("AiBubble", "reshowLast: ${e.message}") }
            }
        }

        @JvmStatic fun hideStatic() {
            val h = Handler(Looper.getMainLooper())
            h.post {
                if (bubbleView != null) {
                    bubbleView?.let { FloatingDragRefresh.endNow(it) }
                    try { windowManager?.removeView(bubbleView) } catch (_: Exception) {}
                    bubbleView = null; statusText = null; iconView = null
                    actionRow = null; inboxCol = null
                    layoutParams = null
                }
            }
        }

        @JvmStatic fun hideAndForget() {
            lastShownText = ""
            hideStatic()
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
                    val vh = v.height.takeIf { it > 0 } ?: 60
                    lp.y = clampY(lp.y, vh)
                    stickyY = lp.y
                    try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }

    @ReactMethod fun show(text: String, mode: String) {
        lastShownText = text
        val m = if (mode == MODE_VOICE) MODE_VOICE else MODE_AI
        handler.post {
            try {
                if (bubbleView != null && currentMode == m) {
                    statusText?.text = text; return@post
                }
                currentMode = m
                createBubble(text)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "show: ${e.message}", e) }
        }
    }

    
    @ReactMethod fun hide() {
        lastShownText = ""
        handler.post { try { removeBubble() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod fun updateText(text: String) {
        lastShownText = text
        handler.post { statusText?.text = text }
    }

    
    @ReactMethod fun flashText(text: String, durationMs: Double, thenText: String) {
        updateText(text)
        handler.postDelayed({
            if (lastShownText == text) {
                lastShownText = thenText
                statusText?.text = thenText
            }
        }, durationMs.toLong().coerceAtLeast(0L))
    }

    @ReactMethod fun setActionButtons(json: String) {
        cachedActionsJson = json
        handler.post { try { rebuildActionRow() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "setActionButtons: ${e.message}", e) } }
    }

    
    @ReactMethod fun setInboxItems(json: String) {
        cachedInboxJson = json
        handler.post { try { rebuildInboxCol() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "setInboxItems: ${e.message}", e) } }
    }

    @ReactMethod fun setPageHeight(height: Int) { pageHeight = height }

    private val sf: Float by lazy { ScreenScale.factor(reactApplicationContext) }
    private fun dp(v: Int): Int = (v * reactApplicationContext.resources.displayMetrics.density * sf).roundToInt()
    private fun dp(v: Float): Int = (v * reactApplicationContext.resources.displayMetrics.density * sf).roundToInt()
    private fun sp(v: Float): Float = v * sf

    private fun createBubble(text: String) {
        if (FloatingToolbarModule.areFloatingOverlaysSuspendedForPalette()) return
        val context = reactApplicationContext
        removeBubble()
        windowManager = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = context.resources.displayMetrics
        screenHeight = dm.heightPixels
        screenWidth = dm.widthPixels


        val isVoice = currentMode == MODE_VOICE
        val bgColor = CLR_PANEL
        val fgColor = CLR_INK
        val gripColor = CLR_GRIP
        val borderColor = CLR_INK
        val borderW = dp(2)
        val cornerR = dp(16).toFloat()

        val outerWrapper = FrameLayout(context)

        val mainBarAndActions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val capsule = TouchSinkLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(bgColor)
                if (borderW > 0) setStroke(borderW, borderColor)
                this.cornerRadius = cornerR
            }
            
            
            layoutParams = LinearLayout.LayoutParams(
                dp(BAR_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }

        val iconSz = dp(28)
        iconView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(iconSz, iconSz).apply {
                rightMargin = dp(14)
            }
            val assetName = if (isVoice) "icons/icon_sound.xml" else "icons/icon_ai_spark.xml"
            val bmp = VectorAssets.loadBitmapTinted(context, assetName, iconSz, fgColor)
            if (bmp != null) setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        capsule.addView(iconView)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(context).apply {
            this.text = text; textSize = sp(17f); setTextColor(fgColor)
            typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            letterSpacing = 0.02f
        }
        textCol.addView(statusText)
        capsule.addView(textCol)

        val gripW = dp(18); val gripH = dp(26)
        val gripView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(gripW, gripH)
            val bmp = VectorAssets.loadBitmapTinted(context, "icons/icon_grip_h.xml", gripW, gripColor)
            if (bmp != null) setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        capsule.addView(gripView)

        mainBarAndActions.addView(capsule)

        
        
        inboxCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            visibility = View.GONE
        }
        mainBarAndActions.addView(inboxCol)
        rebuildInboxCol()

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            visibility = View.GONE
        }
        mainBarAndActions.addView(actionRow)
        rebuildActionRow()

        outerWrapper.addView(mainBarAndActions)
        bubbleView = outerWrapper

        val barW = dp(CAPSULE_WIDTH_DP)
        layoutParams = BubbleWindowSupport.overlayParams(
            barW, WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0; y = clampY(stickyY, 60)
        }

        
        
        
        outerWrapper.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val lp = layoutParams ?: return@addOnLayoutChangeListener
            val ny = clampY(lp.y, bottom - top)
            if (ny != lp.y) {
                lp.y = ny; stickyY = ny
                try { windowManager?.updateViewLayout(v, lp) } catch (_: Exception) {}
            }
        }

        capsule.setOnTouchListener { _, ev ->
            val lp = layoutParams ?: return@setOnTouchListener false
            val view = bubbleView ?: return@setOnTouchListener false
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(ev)) return@setOnTouchListener true
                    val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                        FloatingDragRefresh.begin(view, lp)
                    }
                    if (isDragging) {
                        lp.y = clampY(startY + dy.toInt(), view.height.takeIf { it > 0 } ?: 60)
                        if (!FloatingDragRefresh.move(view, lp)) {
                            try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                        }
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                        FloatingDragRefresh.end(view)
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        stickyY = lp.y
                        val sy = lp.y.toFloat()
                        val r = pageHeight.toFloat() / screenHeight.toFloat()
                        emitEvent("onAiBubbleDragEnd", Arguments.createMap().apply {
                            putDouble("screenY", sy.toDouble())
                            putInt("pageY", (sy * r).toInt())
                        })
                    } else if (ev.x >= gripView.left - dp(10)) {
                        
                        Unit
                    } else {
                        emitEvent("onAiBubbleTap", Arguments.createMap())
                    }
                    if (isDragging) {
                        try { windowManager?.updateViewLayout(view, lp) } catch (_: Exception) {}
                        FloatingDragRefresh.end(view)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(outerWrapper, layoutParams)
        FloatingPenGuard.track("ai_bubble", outerWrapper)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "AI bubble shown: mode=$currentMode text='$text'")
    }

    
    private fun rebuildActionRow() {
        val row = actionRow ?: return
        row.removeAllViews()
        row.setBackgroundColor(Color.TRANSPARENT)
        if (currentMode == MODE_VOICE) { row.visibility = View.GONE; return }
        try {
            val arr = JSONArray(cachedActionsJson)
            if (arr.length() == 0) { row.visibility = View.GONE; tryUpdateLayout(); return }
            row.visibility = View.VISIBLE
            row.orientation = LinearLayout.HORIZONTAL
            val menuId = modeMenuActionId
            val menu = (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("id") == menuId }
            val options = menu?.optJSONArray("modeOptions")
            if (menu != null && options != null && options.length() > 0) {
                
                row.orientation = LinearLayout.VERTICAL
                row.setBackgroundColor(CLR_PANEL)
                row.addView(TextView(reactApplicationContext).apply {
                    text = NativeLocale.t("ai_mode_choose")
                    textSize = sp(13f); setTextColor(CLR_INK)
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                })
                for (i in 0 until options.length()) {
                    val option = options.getJSONObject(i)
                    val mode = option.getString("id")
                    val selected = mode == menu.optString("selectedMode", "text")
                    row.addView(TextView(reactApplicationContext).apply {
                        text = option.getString("label")
                        textSize = sp(15f); gravity = Gravity.CENTER
                        setTextColor(if (selected) CLR_SEL_FG else CLR_INK)
                        minHeight = dp(48)
                        setPadding(dp(18), dp(10), dp(18), dp(10))
                        background = GradientDrawable().apply {
                            setColor(if (selected) CLR_INK else CLR_PANEL)
                            setStroke(dp(1.5f), CLR_INK); cornerRadius = dp(10).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6) }
                        isSelected = selected
                        contentDescription = text
                        setOnClickListener {
                            menu.put("selectedMode", mode)
                            cachedActionsJson = arr.toString()
                            modeMenuActionId = null
                            rebuildActionRow()
                            emitEvent("onAiBubbleActionMode", Arguments.createMap().apply {
                                putString("actionId", menu.getString("id")); putString("mode", mode)
                            })
                        }
                    })
                }
                row.addView(TextView(reactApplicationContext).apply {
                    text = NativeLocale.t("ai_mode_back")
                    textSize = sp(13f); setTextColor(CLR_INK); gravity = Gravity.CENTER
                    minHeight = dp(48)
                    setOnClickListener { modeMenuActionId = null; rebuildActionRow() }
                })
                tryUpdateLayout()
                return
            }
            modeMenuActionId = null
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val actionId = obj.getString("id")
                val asset = obj.optString("icon")
                val label = obj.optString("label", actionId)
                val primary = obj.optBoolean("primary", false)
                val fg = if (primary) CLR_SEL_FG else CLR_INK
                val bitmap = VectorAssets.loadBitmapTinted(reactApplicationContext, asset, dp(24), fg)
                val button: View = if (bitmap != null) {
                    ImageView(reactApplicationContext).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }
                } else {
                    TextView(reactApplicationContext).apply {
                        text = label; textSize = sp(12f); setTextColor(fg); gravity = Gravity.CENTER
                    }
                }
                button.apply {
                    minimumWidth = dp(48); minimumHeight = dp(48)
                    background = GradientDrawable().apply {
                        setColor(if (primary) CLR_INK else CLR_PANEL)
                        setStroke(dp(1.5f), CLR_INK); cornerRadius = dp(10).toFloat()
                    }
                    contentDescription = label
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(dp(52), dp(48)).apply {
                        if (i > 0) leftMargin = dp(10)
                    }
                    setOnClickListener {
                        emitEvent("onAiBubbleAction", Arguments.createMap().apply { putString("actionId", actionId) })
                    }
                    setOnLongClickListener {
                        if ((obj.optJSONArray("modeOptions")?.length() ?: 0) > 0) {
                            modeMenuActionId = actionId
                            rebuildActionRow()
                        } else {
                            android.widget.Toast.makeText(reactApplicationContext, label, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
                row.addView(button)
            }
            tryUpdateLayout()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "rebuildActionRow: ${e.message}")
            row.visibility = View.GONE
        }
    }

    private fun rebuildInboxCol() {
        val col = inboxCol ?: return
        col.removeAllViews()
        try {
            val arr = JSONArray(cachedInboxJson)
            if (arr.length() == 0) { col.visibility = View.GONE; tryUpdateLayout(); return }
            col.visibility = View.VISIBLE

            val count = minOf(arr.length(), MAX_CAPSULES)
            for (i in 0 until count) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.optString("title", "")
                val preview = obj.optString("preview", "")
                
                
                val body = if (preview.startsWith(title) || title.isEmpty()) preview
                           else title + "\n" + preview

                val bar = TextView(reactApplicationContext).apply {
                    text = body
                    textSize = sp(14f)
                    setTextColor(CLR_INK)
                    setLineSpacing(0f, 1.15f)
                    minLines = 3; maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = GradientDrawable().apply {
                        setColor(CLR_PANEL)
                        setStroke(dp(2), CLR_INK)
                        cornerRadius = dp(16).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (i == 0) 0 else dp(8) }
                    setOnClickListener {
                        emitEvent("onAiCapsuleTap", Arguments.createMap().apply { putString("id", id) })
                    }
                    setOnLongClickListener {
                        if (FloatingToolbarModule.isDocMode()) return@setOnLongClickListener true
                        emitEvent("onAiCapsuleLongPress", Arguments.createMap().apply { putString("id", id) })
                        true
                    }
                }
                col.addView(bar)
            }
            tryUpdateLayout()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "rebuildInboxCol: ${e.message}")
            col.visibility = View.GONE
        }
    }

    private fun tryUpdateLayout() {
        try { if (bubbleView != null && layoutParams != null)
            windowManager?.updateViewLayout(bubbleView, layoutParams) } catch (_: Exception) {}
    }

    private fun removeBubble() {
        modeMenuActionId = null
        if (bubbleView != null) {
            bubbleView?.let { FloatingDragRefresh.endNow(it) }
            try { windowManager?.removeView(bubbleView) } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "removeView: ${e.message}") }
            bubbleView = null; statusText = null; iconView = null
            actionRow = null; inboxCol = null
            layoutParams = null
        }
    }

    private fun emitEvent(name: String, params: WritableMap) =
        BubbleWindowSupport.emitEvent(reactApplicationContext, TAG, name, params)

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping AI bubble alive")
        super.onCatalystInstanceDestroy()
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}


}
