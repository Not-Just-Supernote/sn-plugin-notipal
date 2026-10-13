package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig

import me.laumss.notipal.*

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.panels.DocScreenshotService.StitchParams
import me.laumss.notipal.panels.DocScreenshotService.StitchSessionData
import me.laumss.notipal.ui_common.PanelBar
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.ScreenScale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StitchPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "StitchPanel"
    override val panelName = "stitch"
    override val fullScreen = true
    override val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    
    override val forcedOrientation: Int? = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    companion object {
        @Volatile var currentInstance: StitchPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): StitchPanel {
            val inst = currentInstance ?: StitchPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val PREFS_NAME = "screenshot_state"
        private const val PREF_LAST_MODE = "stitch_last_mode"
        private const val PREF_LAST_COLS = "stitch_last_cols"

        private const val PAD_DP = 16
        private const val HIT_RADIUS_DP = 32
        private const val MAX_EDGE_CROP = 0.6f
        private const val MAX_SIDE_CROP = 0.8f
        private val FINE_STEPS = intArrayOf(-10, -1, 1, 10)

        private const val H_SEAM = 0
        private const val H_TOP = 1
        private const val H_BOTTOM = 2
        private const val H_LEFT = 3
        private const val H_RIGHT = 4
    }

    private var session: StitchSessionData? = null
    private var onConfirm: ((StitchSessionData) -> Unit)? = null
    private var onContinue: ((StitchSessionData) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private val previews = mutableListOf<Bitmap?>()
    private var canvasView: StitchCanvas? = null
    private var seamLabel: TextView? = null
    
    private var selectedSeam = 1
    private var busy = false
    private var aligning = false
    private var alignGen = 0

    private fun px(v: Number) = ScreenScale.px(reactContext, v)
    private fun isStrip(s: StitchSessionData) = s.params.mode != StitchParams.MODE_GRID

    fun show(
        session: StitchSessionData,
        onConfirm: (StitchSessionData) -> Unit,
        onContinue: (StitchSessionData) -> Unit,
        onCancel: () -> Unit,
        onShowResult: ((Boolean) -> Unit)? = null
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show() images=${session.images.size}")
        currentInstance = this
        this.session = session
        this.onConfirm = onConfirm
        this.onContinue = onContinue
        this.onCancel = onCancel
        busy = false
        aligning = false
        alignGen++
        selectedSeam = session.images.size - 1
        applySavedLayout(session)
        loadPreviews(session)
        if (isShowing) {
            rebuildContent()
            onShowResult?.invoke(true)
            return
        }
        showPanel(onShowResult)
    }

    override fun onHide() {
        alignGen++
        recyclePreviews()
        session = null
        onConfirm = null
        onContinue = null
        onCancel = null
        canvasView = null
        seamLabel = null
        busy = false
        aligning = false
        currentInstance = null
    }

    private fun prefs() = try {
        reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) { null }

    private fun applySavedLayout(s: StitchSessionData) {
        val p = prefs() ?: return
        s.params.mode = if (p.getString(PREF_LAST_MODE, null) == StitchParams.MODE_GRID)
            StitchParams.MODE_GRID else StitchParams.MODE_STRIP
        s.params.cols = p.getInt(PREF_LAST_COLS, 2).coerceIn(2, 4)
    }

    private fun saveLayout(params: StitchParams) {
        prefs()?.edit()
            ?.putString(PREF_LAST_MODE, params.mode)
            ?.putInt(PREF_LAST_COLS, params.cols)
            ?.apply()
    }

    private fun loadPreviews(s: StitchSessionData) {
        recyclePreviews()
        val sample = if (s.images.size <= 2) 1 else 2
        for (img in s.images) {
            previews.add(try {
                BitmapFactory.decodeFile(img.path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } catch (e: OutOfMemoryError) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "preview decode out of memory", e)
                null
            })
        }
    }

    private fun recyclePreviews() {
        previews.forEach { it?.recycle() }
        previews.clear()
    }

    override fun buildFullScreenContent(): View {
        val root = FrameLayout(reactContext).apply {
            setBackgroundColor(Color.parseColor("#E8E8E8"))
        }
        populateContent(root)
        return root
    }

    private fun rebuildContent() {
        val root = rootView as? FrameLayout ?: return
        root.removeAllViews()
        canvasView = null
        seamLabel = null
        populateContent(root)
    }

    private fun populateContent(root: FrameLayout) {
        val s = session ?: return
        val strip = isStrip(s)

        val actions = mutableListOf<PanelBar.Cell>(
            PanelBar.Action("icons/ic_stitch_add.xml", NativeLocale.t("stitch_add_page")) { doContinue() }
        )
        if (strip) {
            actions.add(PanelBar.Action("icons/ic_stitch_align.xml", NativeLocale.t("stitch_auto_align")) { realignAll() })
        }
        actions.add(PanelBar.Action("icons/ic_stitch_reverse.xml", NativeLocale.t("stitch_reverse")) { reverseOrder() })
        actions.add(PanelBar.Action("icons/ic_stitch_remove.xml", NativeLocale.t("stitch_remove_last")) { removeLast() })

        val bar = PanelBar.build(
            ctx = reactContext,
            style = PanelBar.Style.INBOX,
            left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { doCancel() }),
            center = actions,
            right = listOf(PanelBar.TextBtn(NativeLocale.t("stitch_next")) { doConfirm() })
        )
        val headerH = bar.heightPx
        bar.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerH
        ).apply { gravity = Gravity.TOP }
        root.addView(bar.view)

        val footer = buildFooter(s, strip)
        root.addView(footer)
        val footerH = measureHeight(footer)

        val cv = StitchCanvas(reactContext)
        cv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = headerH; bottomMargin = footerH }
        root.addView(cv)
        canvasView = cv
        updateSeamLabel()
    }

    private fun measureHeight(v: View): Int {
        val w = reactContext.resources.displayMetrics.widthPixels
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    

    private fun buildFooter(s: StitchSessionData, strip: Boolean): LinearLayout {
        val wrapper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        wrapper.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1).coerceAtLeast(1)
            )
            setBackgroundColor(Color.BLACK)
        })

        val row = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(52), px(26), px(52), px(30))
        }
        row.addView(segmented(
            listOf(NativeLocale.t("stitch_mode_strip"), NativeLocale.t("stitch_mode_grid")),
            if (strip) 0 else 1
        ) { idx -> setMode(if (idx == 0) StitchParams.MODE_STRIP else StitchParams.MODE_GRID) })

        if (strip) {
            buildSeamControls(row)
        } else {
            row.addView(View(reactContext), LinearLayout.LayoutParams(0, 1, 1f))
            buildGridControls(row, s)
        }
        wrapper.addView(row)
        return wrapper
    }

    private fun buildSeamControls(row: LinearLayout) {
        val label = makeLabel("").apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = px(24); marginEnd = px(8) }
        }
        seamLabel = label
        row.addView(label)
        for (step in FINE_STEPS) {
            row.addView(makeChip(if (step > 0) "+$step" else "−${-step}") { nudgeSeam(step) })
        }
    }

    private fun buildGridControls(row: LinearLayout, s: StitchSessionData) {
        val n = s.images.size
        if (n > 2) {
            row.addView(makeLabel(NativeLocale.t("stitch_cols")))
            val active = s.params.cols.coerceIn(2, min(4, n))
            for (c in 2..min(4, n)) {
                row.addView(makeChip("$c", selected = c == active) { setCols(c) })
            }
        }
        
        if (n > s.params.cols.coerceIn(1, n)) {
            row.addView(
                segmented(
                    listOf(NativeLocale.t("stitch_order_row"), NativeLocale.t("stitch_order_col")),
                    if (s.params.gridOrder == "col") 1 else 0
                ) { idx -> setGridOrder(if (idx == 1) "col" else "row") },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = px(36) }
            )
        }
    }

    private fun makeLabel(text: String): TextView = TextView(reactContext).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(reactContext, 30))
        setTextColor(Color.BLACK)
    }

    private fun makeChip(label: String, selected: Boolean = false, onClick: () -> Unit): TextView =
        TextView(reactContext).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(reactContext, 30))
            setTextColor(if (selected) Color.WHITE else Color.BLACK)
            gravity = Gravity.CENTER
            minWidth = px(88)
            setPadding(px(18), px(14), px(18), px(14))
            background = GradientDrawable().apply {
                setColor(if (selected) Color.BLACK else Color.WHITE)
                setStroke(px(3).coerceAtLeast(1), Color.BLACK)
                cornerRadius = px(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(14) }
            setOnClickListener { onClick() }
        }

    private fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val stroke = px(3).coerceAtLeast(1)
        val radius = px(8).toFloat()
        val box = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(stroke, stroke, stroke, stroke)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(stroke, Color.BLACK)
                cornerRadius = radius
            }
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            box.addView(TextView(reactContext).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, ScreenScale.textPx(reactContext, 30))
                setTextColor(if (on) Color.WHITE else Color.BLACK)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                minWidth = px(120)
                setPadding(px(26), px(12), px(26), px(12))
                if (on) background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = radius * 0.6f
                }
                setOnClickListener { if (!on) onSelect(i) }
            })
        }
        return box
    }

    private fun updateSeamLabel() {
        val label = seamLabel ?: return
        val s = session ?: return
        if (aligning) {
            label.text = NativeLocale.t("stitch_aligning")
            return
        }
        if (s.images.size < 2) { label.text = ""; return }
        val i = selectedSeam.coerceIn(1, s.images.size - 1)
        val overlap = NativeLocale.t("stitch_overlap",
            s.images[i].overlap.coerceAtMost(StitchLayout.maxOverlap(s, i)))
        label.text = if (s.images.size > 2) "${NativeLocale.t("stitch_seam", i)} · $overlap" else overlap
    }

    

    private fun setMode(mode: String) {
        val s = session ?: return
        if (busy) return
        s.params.mode = mode
        saveLayout(s.params)
        rebuildContent()
    }

    private fun setCols(cols: Int) {
        val s = session ?: return
        if (busy) return
        s.params.cols = cols
        saveLayout(s.params)
        rebuildContent()
    }

    private fun setGridOrder(order: String) {
        val s = session ?: return
        if (busy) return
        s.params.gridOrder = order
        rebuildContent()
    }

    private fun nudgeSeam(step: Int) {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        val i = selectedSeam.coerceIn(1, s.images.size - 1)
        val img = s.images[i]
        img.overlap = (img.overlap.coerceAtMost(StitchLayout.maxOverlap(s, i)) + step)
            .coerceIn(0, StitchLayout.maxOverlap(s, i))
        canvasView?.refresh()
        updateSeamLabel()
    }

    private fun realignAll() {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        aligning = true
        val gen = ++alignGen
        updateSeamLabel()
        val snapshot = s.images.toList()
        kotlin.concurrent.thread(isDaemon = true) {
            val seams = (1 until snapshot.size).map { i -> StitchAligner.align(snapshot[i - 1], snapshot[i]) }
            handler.post {
                if (gen != alignGen || session !== s) return@post
                aligning = false
                seams.forEachIndexed { k, seam ->
                    snapshot[k + 1].trimTop = seam?.trimTop ?: 0
                    snapshot[k + 1].overlap = seam?.overlap ?: 0
                }
                canvasView?.refresh()
                updateSeamLabel()
            }
        }
    }

    private fun reverseOrder() {
        val s = session ?: return
        if (busy || aligning) return
        s.images.reverse()
        previews.reverse()
        
        for (img in s.images) { img.trimTop = 0; img.overlap = 0 }
        selectedSeam = s.images.size - 1
        rebuildContent()
        realignAll()
    }

    private fun removeLast() {
        val s = session ?: return
        if (busy || aligning || s.images.size < 2) return
        val last = s.images.removeAt(s.images.size - 1)
        try { java.io.File(last.path).delete() } catch (_: Exception) {}
        if (previews.size > s.images.size) previews.removeAt(previews.size - 1)?.recycle()
        if (s.images.size < 2) {
            
            busy = true
            onContinue?.invoke(s)
            return
        }
        selectedSeam = s.images.size - 1
        rebuildContent()
    }

    private fun doContinue() {
        val s = session ?: return
        if (busy || aligning) return
        busy = true
        saveLayout(s.params)
        onContinue?.invoke(s)
    }

    private fun doConfirm() {
        val s = session ?: return
        if (busy || aligning) return
        busy = true
        saveLayout(s.params)
        onConfirm?.invoke(s)
    }

    private fun doCancel() {
        if (busy) return
        onCancel?.invoke()
        hide()
    }

    

    
    private class Handle(
        val kind: Int, val index: Int,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val cx: Float, val cy: Float
    ) {
        val horizontal get() = y1 == y2

        fun distance(x: Float, y: Float): Float {
            val dx = max(max(x1 - x, 0f), x - x2)
            val dy = max(max(y1 - y, 0f), y - y2)
            return hypot(dx, dy)
        }
    }

    private inner class StitchCanvas(ctx: Context) : View(ctx) {

        private val pad = dp(PAD_DP).toFloat()
        private val hitRadius = dp(HIT_RADIUS_DP).toFloat()
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val framePaint = Paint().apply {
            color = Color.parseColor("#888888"); style = Paint.Style.STROKE
            strokeWidth = px(2).coerceAtLeast(1).toFloat()
        }
        private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = px(3).coerceAtLeast(1).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(px(16).toFloat(), px(10).toFloat()), 0f)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = px(3).coerceAtLeast(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            textSize = ScreenScale.textPx(reactContext, 26)
        }

        private var arrangement: StitchLayout.Result? = null
        private var scale = 1f
        private var originX = 0f
        private var originY = 0f
        private var scrollOff = 0f
        private var maxScroll = 0f
        private var focusPending = true

        private var drag: Handle? = null
        private var dragScroll = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var startVal = 0f
        private var startOverlap = 0
        private var startScroll = 0f
        private var startOriginX = 0f
        private var startOriginY = 0f

        fun refresh() {
            recompute()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            recompute()
        }

        private fun recompute() {
            val s = session ?: return
            if (width == 0 || height == 0) return
            val lay = StitchLayout.compute(s)
            arrangement = lay
            if (lay.width <= 0 || lay.height <= 0) return
            val availW = width - pad * 2
            val availH = height - pad * 2
            val fitWidth = availW / lay.width
            val fitAll = min(fitWidth, availH / lay.height)
            
            scale = if (isStrip(s)) min(fitWidth, max(fitAll, fitWidth * 0.4f)) else fitAll
            val contentH = lay.height * scale
            originX = (width - lay.width * scale) / 2f
            if (contentH <= availH) {
                originY = (height - contentH) / 2f
                maxScroll = 0f
            } else {
                originY = pad
                maxScroll = contentH - availH
            }
            if (focusPending) {
                
                focusPending = false
                val seamY = lay.pieces.getOrNull(selectedSeam)?.dst?.top ?: 0f
                scrollOff = seamY * scale - availH / 2f
            }
            scrollOff = scrollOff.coerceIn(0f, maxScroll)
        }

        private fun vx(x: Float) = originX + x * scale
        private fun vy(y: Float) = originY - scrollOff + y * scale
        private fun toView(r: RectF) = RectF(vx(r.left), vy(r.top), vx(r.right), vy(r.bottom))

        private fun handles(lay: StitchLayout.Result): List<Handle> {
            val pieces = lay.pieces
            if (pieces.isEmpty()) return emptyList()
            val left = vx(pieces.minOf { it.dst.left })
            val right = vx(pieces.maxOf { it.dst.right })
            val top = vy(pieces.first().dst.top)
            val bottom = vy(pieces.last().dst.bottom)
            val cx = (left + right) / 2f
            val midY = (height / 2f).coerceIn(top, max(top, bottom))
            val list = ArrayList<Handle>()
            list.add(Handle(H_TOP, 0, left, top, right, top, cx, top))
            list.add(Handle(H_BOTTOM, 0, left, bottom, right, bottom, cx, bottom))
            list.add(Handle(H_LEFT, 0, left, top, left, bottom, left, midY))
            list.add(Handle(H_RIGHT, 0, right, top, right, bottom, right, midY))
            for (i in 1 until pieces.size) {
                val y = vy(pieces[i].dst.top)
                list.add(Handle(H_SEAM, i, left, y, right, y, cx, y))
            }
            return list
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = session ?: return
            val lay = arrangement ?: return
            val strip = isStrip(s)

            for (piece in lay.pieces) {
                val bmp = previews.getOrNull(piece.index) ?: continue
                if (bmp.isRecycled) continue
                val img = s.images[piece.index]
                val fx = bmp.width.toFloat() / img.width
                val fy = bmp.height.toFloat() / img.height
                val src = Rect(
                    (piece.src.left * fx).toInt(), (piece.src.top * fy).toInt(),
                    (piece.src.right * fx).roundToInt(), (piece.src.bottom * fy).roundToInt()
                )
                val dst = toView(piece.dst)
                if (dst.bottom < 0f || dst.top > height) continue
                canvas.drawBitmap(bmp, src, dst, bitmapPaint)
                if (!strip) canvas.drawRect(dst, framePaint)
            }

            if (strip && lay.pieces.isNotEmpty()) {
                val all = handles(lay)
                canvas.drawRect(all[0].x1, all[0].y1, all[1].x2, all[1].y1, framePaint)
                for (h in all) {
                    if (h.kind == H_SEAM) {
                        canvas.drawLine(h.x1, h.y1, h.x2, h.y2, seamPaint)
                        drawSeamHandle(canvas, h.cx, h.cy, h.index == selectedSeam)
                    } else {
                        drawEdgeHandle(canvas, h.cx, h.cy, h.horizontal)
                    }
                }
            }

            for (piece in lay.pieces) drawBadge(canvas, toView(piece.dst), piece.index + 1)
        }

        private fun drawSeamHandle(canvas: Canvas, cx: Float, cy: Float, selected: Boolean) {
            val w = px(112).toFloat()
            val h = px(44).toFloat()
            val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            fillPaint.color = if (selected) Color.BLACK else Color.WHITE
            canvas.drawRoundRect(r, h / 2, h / 2, fillPaint)
            strokePaint.color = Color.BLACK
            canvas.drawRoundRect(r, h / 2, h / 2, strokePaint)
            strokePaint.color = if (selected) Color.WHITE else Color.BLACK
            val gw = px(22).toFloat()
            val gy = px(6).toFloat()
            canvas.drawLine(cx - gw, cy - gy, cx + gw, cy - gy, strokePaint)
            canvas.drawLine(cx - gw, cy + gy, cx + gw, cy + gy, strokePaint)
        }

        private fun drawEdgeHandle(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean) {
            val long = px(112).toFloat()
            val short = px(16).toFloat()
            val w = if (horizontal) long else short
            val h = if (horizontal) short else long
            fillPaint.color = Color.BLACK
            canvas.drawRoundRect(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), short / 2, short / 2, fillPaint)
        }

        private fun drawBadge(canvas: Canvas, r: RectF, number: Int) {
            val size = px(44).toFloat()
            val inset = px(12).toFloat()
            val left = r.left + inset
            val top = r.top + inset
            if (top + size < 0f || top > height) return
            fillPaint.color = Color.BLACK
            canvas.drawRoundRect(RectF(left, top, left + size, top + size), px(6).toFloat(), px(6).toFloat(), fillPaint)
            val baseline = top + size / 2 - (badgeText.descent() + badgeText.ascent()) / 2
            canvas.drawText("$number", left + size / 2, baseline, badgeText)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val s = session ?: return false
            val lay = arrangement ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    moved = false
                    drag = null
                    dragScroll = false
                    if (aligning || busy || !isStrip(s)) return true
                    drag = handles(lay)
                        .map { it to it.distance(e.x, e.y) }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }?.first
                    startOriginX = originX
                    startOriginY = originY
                    val h = drag
                    if (h == null) {
                        dragScroll = maxScroll > 0f
                        startScroll = scrollOff
                        return true
                    }
                    when (h.kind) {
                        H_SEAM -> {
                            startOverlap = s.images[h.index].overlap.coerceAtMost(StitchLayout.maxOverlap(s, h.index))
                            if (selectedSeam != h.index) {
                                selectedSeam = h.index
                                updateSeamLabel()
                                invalidate()
                            }
                        }
                        H_TOP -> startVal = s.params.cropTop
                        H_BOTTOM -> startVal = s.params.cropBottom
                        H_LEFT -> startVal = s.params.cropLeft
                        H_RIGHT -> startVal = s.params.cropRight
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!moved && hypot(dx, dy) < touchSlop) return true
                    moved = true
                    val h = drag
                    if (h == null) {
                        if (dragScroll) {
                            scrollOff = (startScroll - dy).coerceIn(0f, maxScroll)
                            invalidate()
                        }
                        return true
                    }
                    val first = s.images.first()
                    val last = s.images.last()
                    when (h.kind) {
                        H_SEAM -> s.images[h.index].overlap = (startOverlap - dy / scale).roundToInt()
                            .coerceIn(0, StitchLayout.maxOverlap(s, h.index))
                        H_TOP -> s.params.cropTop = (startVal + dy / scale / first.height).coerceIn(0f, MAX_EDGE_CROP)
                        H_BOTTOM -> s.params.cropBottom = (startVal - dy / scale / last.height).coerceIn(0f, MAX_EDGE_CROP)
                        H_LEFT -> s.params.cropLeft = (startVal + dx / scale / first.width)
                            .coerceIn(0f, max(0f, MAX_SIDE_CROP - s.params.cropRight))
                        H_RIGHT -> s.params.cropRight = (startVal - dx / scale / first.width)
                            .coerceIn(0f, max(0f, MAX_SIDE_CROP - s.params.cropLeft))
                    }
                    
                    arrangement = StitchLayout.compute(s)
                    when (h.kind) {
                        H_TOP -> originY = startOriginY + (s.params.cropTop - startVal) * first.height * scale
                        H_LEFT -> originX = startOriginX + (s.params.cropLeft - startVal) * first.width * scale
                    }
                    if (h.kind == H_SEAM) updateSeamLabel()
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val h = drag
                    drag = null
                    dragScroll = false
                    if (h != null && moved) refresh()
                    return true
                }
            }
            return true
        }
    }
}
