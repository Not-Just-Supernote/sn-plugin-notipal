package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.bubbles.PaletteBubbleModule

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.ui_common.*
import org.json.JSONArray
import org.json.JSONObject

class PalettePanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "PalettePanel"
    override val panelName = "palette"
    override val heightRatio = 0.90

    companion object {
        @Volatile var currentInstance: PalettePanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): PalettePanel {
            val inst = currentInstance ?: PalettePanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val PREFS_NAME = "quicktoolbar_presets"
        private const val PRESETS_KEY = "palette_presets"
        private const val TIER_PREFS_KEY = "palette_tier_prefs"

        
        private const val SNAPSHOT_PREF_KEY = "preset_95"
        private const val SNAP_COLS = 1
        private const val SNAP_ROWS = 3

        
        const val COLS = 5
        const val VISIBLE_COLS = 5
        const val ROWS = 2
        const val MAX_SLOTS = COLS * ROWS

        private const val SLOT_CIRCLE_PX = 96
        private const val SLOT_ICON_PX = 54
        private const val SLOT_LABEL_HEIGHT_PX = 46
        private const val SLOT_LABEL_MARGIN_PX = 9
        private const val SLOT_COLUMN_GAP_PX = 106
        private const val SLOT_ROW_GAP_PX = 28
        private const val SLOT_BORDER_PX = 2
        private const val SLOT_SELECTED_BORDER_PX = 7
        private const val SLOT_DASH_PX = 2
        private const val COLOR_BORDER_PX = 1
        private const val COLOR_SELECTED_BORDER_PX = 6
        private const val COLOR_DASH_PX = 2
        private const val COLOR_DASH_GAP_PX = 4

        private const val EDITOR_CIRCLE_PX = 88
        private const val EDITOR_ICON_PX = 48
        private const val EDITOR_GAP_PX = 24
        private const val COLOR_SWATCH_PX = 60

        private val INK = Color.BLACK
        private val INK2 = Color.parseColor("#6B6B6B")
        private val LINE = Color.parseColor("#C6C6C6")
        private val LINE2 = Color.parseColor("#9D9D9D")
        private val FAINT = Color.parseColor("#9D9D9D")

        fun normalizePenType(raw: Int): Int? = when (raw) {
            10, 16, 14, 11 -> raw
            0, 2 -> 10
            1 -> 16
            15, 4 -> 14
            else -> null
        }
    }

    data class PenDef(
        val id: String,
        val penType: Int,
        val label: String,
    )

    private val PENS = listOf(
        PenDef("needle", 10, NativeLocale.t("pen_needle")),
        PenDef("ball",   16, NativeLocale.t("pen_ball")),
        PenDef("brush",  14, NativeLocale.t("pen_calligraphy")),
        PenDef("marker", 11, NativeLocale.t("pen_marker")),
    )

    private fun canonicalPenId(id: String): String = if (id.startsWith("mk-")) "marker" else id

    private fun penDefById(id: String) = PENS.find { it.id == canonicalPenId(id) } ?: PENS[0]

    data class PenColorDef(val key: String, val value: Int, val label: String, val swatch: Int, val hex: String = "", val isExtended: Boolean = false)

    private val PRODUCT_COLORS = listOf(
        PenColorDef("black",     0x00, NativeLocale.t("color_black"),      Color.BLACK, "#000000"),
        PenColorDef("darkGray",  0x9D, NativeLocale.t("color_dark_gray"),  Color.parseColor("#9D9D9D"), "#9D9D9D"),
        PenColorDef("lightGray", 0xC9, NativeLocale.t("color_light_gray"), Color.parseColor("#C9C9C9"), "#C9C9C9"),
        PenColorDef("ghost",     0xFE, NativeLocale.t("color_ghost"),      Color.WHITE, "#FFFFFF"),
    )

    private val EXTENDED_COLORS = listOf(
        PenColorDef("blue",   0x00, NativeLocale.t("color_blue"),   Color.parseColor("#3F6FA8"), "#3F6FA8", isExtended = true),
        PenColorDef("red",    0x00, NativeLocale.t("color_red"),    Color.parseColor("#B23F3F"), "#B23F3F", isExtended = true),
        PenColorDef("pink",   0x00, NativeLocale.t("color_pink"),   Color.parseColor("#C266A0"), "#C266A0", isExtended = true),
        PenColorDef("orange", 0x00, NativeLocale.t("color_orange"), Color.parseColor("#C67C33"), "#C67C33", isExtended = true),
        PenColorDef("green",  0x00, NativeLocale.t("color_green"),  Color.parseColor("#4F8C4F"), "#4F8C4F", isExtended = true),
        PenColorDef("cyan",   0x00, NativeLocale.t("color_cyan"),   Color.parseColor("#3C9A9A"), "#3C9A9A", isExtended = true),
        PenColorDef("lime",   0x00, NativeLocale.t("color_lime"),   Color.parseColor("#94A93C"), "#94A93C", isExtended = true),
        PenColorDef("purple", 0x00, NativeLocale.t("color_purple"), Color.parseColor("#7B5BA6"), "#7B5BA6", isExtended = true),
    )

    private val EXTENDED_BRIGHT = listOf(
        PenColorDef("blue_b",   0x00, NativeLocale.t("color_blue"),   Color.parseColor("#7FB2E2"), "#7FB2E2", isExtended = true),
        PenColorDef("red_b",    0x00, NativeLocale.t("color_red"),    Color.parseColor("#E58585"), "#E58585", isExtended = true),
        PenColorDef("pink_b",   0x00, NativeLocale.t("color_pink"),   Color.parseColor("#ECA6CF"), "#ECA6CF", isExtended = true),
        PenColorDef("orange_b", 0x00, NativeLocale.t("color_orange"), Color.parseColor("#EEB66E"), "#EEB66E", isExtended = true),
        PenColorDef("green_b",  0x00, NativeLocale.t("color_green"),  Color.parseColor("#90CA90"), "#90CA90", isExtended = true),
        PenColorDef("cyan_b",   0x00, NativeLocale.t("color_cyan"),   Color.parseColor("#7BCDCD"), "#7BCDCD", isExtended = true),
        PenColorDef("lime_b",   0x00, NativeLocale.t("color_lime"),   Color.parseColor("#CCDB79"), "#CCDB79", isExtended = true),
        PenColorDef("purple_b", 0x00, NativeLocale.t("color_purple"), Color.parseColor("#B398DB"), "#B398DB", isExtended = true),
    )

    private val ALL_COLORS = PRODUCT_COLORS + EXTENDED_COLORS + EXTENDED_BRIGHT

    private fun colorDefByKey(key: String) = ALL_COLORS.find { it.key == key }

    data class TierPreset(val key: String, var labelValue: Float)

    private val tierPresets = mutableListOf(
        TierPreset("thin",   0.3f),
        TierPreset("medium", 0.5f),
        TierPreset("wide",   0.7f),
        TierPreset("thick",  1.0f),
    )

    data class Preset(val penId: String, val color: String, val thickness: Int) {
        val penType: Int get() = when {
            penId == "marker" || penId.startsWith("mk-") -> 11
            penId == "ball" -> 16
            penId == "brush" -> 14
            else -> 10
        }
    }

    private var strokeCount = 0
    private var geometryCount = 0
    private var avgThickness = 100
    private var hasMarkerStroke = false
    private var elementNums = listOf<Int>()
    private var dominantPenType: Int? = null
    private var dominantPenColor: Int? = null

    private var presets = arrayOfNulls<Preset>(MAX_SLOTS)
    private var selectedSlot: Int? = null

    private var selectedColor: String? = null
    private var selectedPenId: String? = null
    private var thickness = 100
    private var thicknessChanged = false
    private var initialColor: String? = null
    private var initialPenType: Int? = null
    private var activeTierKey: String? = null

    private var thicknessLabel: TextView? = null
    private var gridContainer: LinearLayout? = null
    private var colorContainer: LinearLayout? = null
    private var penTypeLabel: TextView? = null
    private var thicknessHeaderLabel: TextView? = null
    private var thicknessSection: LinearLayout? = null
    private var applyBtn: TextView? = null
    private val penBtns = mutableMapOf<String, View>()
    private val colorBtns = mutableMapOf<String, View>()
    private val tierBtns = mutableMapOf<String, View>()

    
    data class SnapshotRec(
        val id: String, val sticker: String, val thumb: String,
        val note: String, val page: Int, val ts: Long
    )
    private var snapshots = listOf<SnapshotRec>()
    private var snapshotGrid: LinearLayout? = null

    fun show(infoJson: String) {
        currentInstance = this
        PaletteBubbleModule.hideStatic()
        parseInfo(infoJson)
        loadPresets()
        loadTierPrefs()
        selectedPenType(dominantPenType)
        selectedColor = dominantPenColor?.let { c -> PRODUCT_COLORS.find { it.value == c }?.key } ?: "black"
        initialPenType = dominantPenType?.let { normalizePenType(it) }
        initialColor = selectedColor
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show: dominantPenType=$dominantPenType -> $selectedPenId, " +
            "dominantPenColor=$dominantPenColor -> $selectedColor, avgThickness=$avgThickness")
        thicknessChanged = false
        activeTierKey = null
        thickness = PenSizeSpec.snap(initialPenType, avgThickness)
        
        
        if (elementNums.isNotEmpty()) pushCurrentPreset()
        showPanel()
    }

    private fun selectedPenType(type: Int?) {
        val normalized = type?.let { normalizePenType(it) }
        selectedPenId = when (normalized) {
            10 -> "needle"
            16 -> "ball"
            14 -> "brush"
            11 -> "marker"
            else -> "needle"
        }
    }

    override fun onHide() {
        thicknessLabel = null
        gridContainer = null
        snapshotGrid = null
        colorContainer = null
        colorHeaderLabel = null
        penTypeLabel = null
        thicknessHeaderLabel = null
        thicknessSection = null
        applyBtn = null
        penBtns.clear()
        colorBtns.clear()
        tierBtns.clear()
        currentInstance = null
    }

    private fun parseInfo(json: String) {
        try {
            val o = JSONObject(json)
            strokeCount = o.optInt("strokeCount", 0)
            geometryCount = o.optInt("geometryCount", 0)
            avgThickness = o.optInt("avgThickness", 100)
            hasMarkerStroke = o.optBoolean("hasMarkerStroke", false)
            dominantPenType = if (o.has("dominantPenType") && !o.isNull("dominantPenType"))
                o.getInt("dominantPenType") else null
            dominantPenColor = if (o.has("dominantPenColor") && !o.isNull("dominantPenColor"))
                o.getInt("dominantPenColor") else null
            val arr = o.optJSONArray("elementNums")
            elementNums = if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "parseInfo: ${e.message}")
        }
    }

    private fun curPreset(): Preset? = selectedSlot?.let { presets[it] }

    
    private fun pushCurrentPreset() {
        val penId = selectedPenId ?: return
        val color = selectedColor ?: return
        val penType = penDefById(penId).penType
        val incoming = Preset(penId, color, PenSizeSpec.snap(penType, thickness))
        val ordered = mutableListOf(incoming)
        for (preset in presets) {
            if (preset != null) ordered.add(preset)
        }
        presets.fill(null)
        ordered.take(MAX_SLOTS).forEachIndexed { index, preset -> presets[index] = preset }
        selectedSlot = 0
        savePresets()
    }

    private fun currentPenDef(): PenDef? = selectedPenId?.let { penDefById(it) }
    private fun effectivePenType(): Int? = currentPenDef()?.penType

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("palette_header_title"))

            custom { host ->
                val scrollHost = PanelScrollHost(host.ctx, overlayScrollbar = true)
                val content = scrollHost.content
                content.setPadding(0, 0, 0, 0)

                content.addView(buildSlotsSection())
                content.addView(makeFullDivider())

                content.addView(buildLowerSplitSection())

                updatePenSelection()
                updateColorSelection()
                updateTierSelection()
                syncThicknessDisplay()

                scrollHost.view
            }

            custom { _ ->
                applyBtn = makeFilledBtn(NativeLocale.t("palette_apply")) { doApply() }
                updateApplyState()
                makeBottomBar(
                    leftButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("palette_reset")) { doResetAll() }
                    ),
                    rightButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("cancel")) { closeAndRestore() },
                        applyBtn!!
                    )
                )
            }
        }
    }

    private fun makeFullDivider(): View {
        return View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1.5f)
            )
            setBackgroundColor(LINE)
        }
    }

    private fun makeSectionHeader(zhLabel: String, enLabel: String, trailingText: String? = null): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }

            addView(TextView(reactContext).apply {
                text = zhLabel
                textSize = sp(14.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
            })

            addView(TextView(reactContext).apply {
                text = enLabel.uppercase()
                textSize = sp(11.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK2)
                letterSpacing = 0.15f
                setPadding(dp(9), 0, 0, 0)
            })

            if (trailingText != null) {
                addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                addView(TextView(reactContext).apply {
                    text = trailingText
                    textSize = sp(12.5f)
                    setTextColor(FAINT)
                })
            }
        }
    }

    private fun buildSlotsSection(): LinearLayout {
        val section = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(22), 0, dp(22))
        }

        gridContainer = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        rebuildGrid()
        section.addView(gridContainer)

        return section
    }

    private fun rebuildGrid() {
        val container = gridContainer ?: return
        container.removeAllViews()
        val columnGap = ScreenScale.px(reactContext, SLOT_COLUMN_GAP_PX)
        val rowGap = ScreenScale.px(reactContext, SLOT_ROW_GAP_PX)
        for (r in 0 until ROWS) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = rowGap }
            }
            for (c in 0 until VISIBLE_COLS) {
                val idx = r * COLS + c
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(columnGap, 0)
                })
                row.addView(makeSlotCell(idx))
            }
            container.addView(row)
        }
    }

    private fun makeSlotCell(index: Int): View {
        val preset = presets[index]
        val isActive = index == selectedSlot
        val circleSize = ScreenScale.px(reactContext, SLOT_CIRCLE_PX)
        val iconSize = ScreenScale.px(reactContext, SLOT_ICON_PX)

        val cell = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                circleSize, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (preset != null) {
                    if (selectedSlot == index) {
                        selectedSlot = null
                        restoreFromLasso()
                    } else {
                        selectedSlot = index
                        loadSlotIntoEditor()
                    }
                    refreshAll()
                } else {
                    val color = selectedColor ?: "black"
                    val penId = selectedPenId ?: "needle"
                    presets[index] = Preset(penId, color, thickness)
                    savePresets()
                    refreshAll()
                }
            }
        }

        val circle = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = makeSlotCircleBackground(isActive)
        }

        if (preset == null) {
            circle.addView(TextView(reactContext).apply {
                text = "+"
                textSize = sp(28f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        } else {
            val pen = penDefById(preset.penId)
            addNativePenIcon(
                circle,
                PenSizeSpec.penIconAsset(pen.penType, preset.color),
                iconSize,
            )
        }

        cell.addView(circle)

        val label = preset?.let {
            PenSizeSpec.label(penDefById(it.penId).penType, it.thickness)
        }.orEmpty()
        cell.addView(TextView(reactContext).apply {
            text = label
            textSize = sp(17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)
            ).apply {
                topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
                bottomMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX)
            }
        })

        return cell
    }

    private fun makeSlotCircleBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            if (selected) {
                setStroke(ScreenScale.px(reactContext, SLOT_SELECTED_BORDER_PX).coerceAtLeast(1), INK)
            } else {
                val dash = ScreenScale.px(reactContext, SLOT_DASH_PX).coerceAtLeast(1).toFloat()
                setStroke(
                    ScreenScale.px(reactContext, SLOT_BORDER_PX).coerceAtLeast(1),
                    INK,
                    dash,
                    dash
                )
            }
        }
    }

    private fun addNativePenIcon(host: FrameLayout, asset: String, iconSize: Int) {
        host.addView(ImageView(reactContext).apply {
            setImageBitmap(VectorAssets.loadBitmap(reactContext, asset, iconSize))
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        })
    }

    

    
    fun reloadSnapshots() {
        handler.post {
            loadSnapshots()
            rebuildSnapshotGrid()
        }
    }

    private fun loadSnapshots() {
        snapshots = try {
            val json = reactContext.getSharedPreferences(PREFS_NAME, 0)
                .getString(SNAPSHOT_PREF_KEY, null)
            if (json == null) emptyList() else {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val sticker = o.optString("sticker", "")
                    if (sticker.isEmpty()) return@mapNotNull null
                    SnapshotRec(
                        o.optString("id", ""), sticker, o.optString("thumb", ""),
                        o.optString("note", ""), o.optInt("page", 0), o.optLong("ts", 0L)
                    )
                }.take(SNAP_ROWS)
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "loadSnapshots: ${e.message}")
            emptyList()
        }
    }

    private fun buildSnapshotSection(): LinearLayout {
        val section = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val headerRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        headerRow.addView(TextView(reactContext).apply {
            text = NativeLocale.t("palette_snapshot_header")
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
        })
        section.addView(headerRow)

        snapshotGrid = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        section.addView(snapshotGrid)

        loadSnapshots()
        rebuildSnapshotGrid()
        return section
    }

    private fun rebuildSnapshotGrid() {
        val container = snapshotGrid ?: return
        container.removeAllViews()
        val gap = dp(7)

        for (r in 0 until SNAP_ROWS) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (r > 0) topMargin = gap }
            }
            for (c in 0 until SNAP_COLS) {
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 0)
                })
                val idx = r * SNAP_COLS + c
                row.addView(if (idx < snapshots.size) {
                    makeSnapshotCard(snapshots[idx])
                } else {
                    makeEmptySnapshotCard()
                })
            }
            container.addView(row)
        }

    }

    private fun makeEmptySnapshotCard(): View {
        return FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(170), 1f)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1.5f), LINE)
                cornerRadius = 0f
            }
        }
    }

    private fun makeSnapshotCard(rec: SnapshotRec): View {
        val cardH = dp(170)
        val card = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, cardH, 1f)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1.5f), LINE2)
                cornerRadius = 0f
            }
            setOnClickListener { requestSnapshotRestore(rec) }
        }

        val inner = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(6), dp(6), dp(6), dp(5))
        }
        inner.addView(ImageView(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            decodeThumb(rec.thumb, dp(140))?.let { setImageBitmap(it) }
                ?: setImageDrawable(null)
        })
        inner.addView(TextView(reactContext).apply {
            text = "P${rec.page + 1} · " + java.text.SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(rec.ts))
            textSize = sp(11.5f)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(inner)

        card.addView(TextView(reactContext).apply {
            text = "✕"
            textSize = sp(14f)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            val sz = dp(30)
            layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.END)
            setOnClickListener { requestSnapshotDelete(rec) }
        })

        return card
    }

    private fun decodeThumb(path: String, targetW: Int): Bitmap? {
        if (path.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "decodeThumb: ${e.message}")
            null
        }
    }

    private fun requestSnapshotRestore(rec: SnapshotRec) {
        showConfirmDialog(NativeLocale.t("palette_snapshot_restore_confirm")) {
            
            hide()
            toolbarModule.restoreToolbar()
            toolbarModule.emitEvent("paletteSnapshot", Arguments.createMap().apply {
                putString("op", "restore")
                putString("sticker", rec.sticker)
            })
        }
    }

    private fun requestSnapshotDelete(rec: SnapshotRec) {
        showConfirmDialog(NativeLocale.t("palette_snapshot_delete_confirm")) {
            toolbarModule.emitEvent("paletteSnapshot", Arguments.createMap().apply {
                putString("op", "delete")
                putString("id", rec.id)
            })
        }
    }

    private fun buildLowerSplitSection(): LinearLayout {
        val lower = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(22), 0, dp(26))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editorCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
        }
        penTypeLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        updatePenTypeLabel()
        editorCol.addView(penTypeLabel)
        buildPenTypeGrid(editorCol)

        val colorSection = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        }
        colorContainer = colorSection
        buildColorSection(colorSection)
        editorCol.addView(colorSection)

        val thicknessControls = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        }
        thicknessSection = thicknessControls
        thicknessHeaderLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        thicknessControls.addView(thicknessHeaderLabel)
        buildThicknessInline(thicknessControls)
        editorCol.addView(thicknessControls)
        lower.addView(editorCol)

        lower.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(1.5f), LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
            setBackgroundColor(LINE2)
        })

        val snapshotCol = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f)
            setPadding(dp(18), 0, dp(24), 0)
            addView(buildSnapshotSection())
        }
        lower.addView(snapshotCol)

        return lower
    }

    private fun buildPenTypeGrid(parent: LinearLayout) {
        penBtns.clear()
        parent.addView(makePenRow(PENS))
    }

    private fun makePenRow(pens: List<PenDef>): LinearLayout {
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for ((i, pen) in pens.withIndex()) {
                if (i > 0) addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ScreenScale.px(reactContext, EDITOR_GAP_PX), 0
                    )
                })
                val tile = makePenTile(pen)
                penBtns[pen.id] = tile
                addView(tile)
            }
        }
    }

    private fun makePenTile(pen: PenDef): FrameLayout {
        val isActive = selectedPenId == pen.id
        return FrameLayout(reactContext).apply {
            val circleSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = makeSlotCircleBackground(isActive)
            setOnClickListener {
                if (selectedPenId == pen.id) return@setOnClickListener
                val prevPen = effectivePenType()
                selectedPenId = pen.id
                activeTierKey = null
                val newPen = effectivePenType()
                val converted = PenSizeSpec.convert(prevPen, newPen, thickness)
                if (converted != thickness) {
                    thickness = converted
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updatePenSelection()
                updateColorSelection()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            renderPenTileIcon(this, pen)
        }
    }

    private fun renderPenTileIcon(host: FrameLayout, pen: PenDef) {
        host.removeAllViews()
        addNativePenIcon(
            host,
            PenSizeSpec.penIconAsset(pen.penType),
            ScreenScale.px(reactContext, EDITOR_ICON_PX),
        )
    }

    private fun updatePenTypeLabel() {
        penTypeLabel?.text = currentPenDef()?.label ?: NativeLocale.t("palette_pen_type")
    }

    private fun updatePenSelection() {
        for ((id, btn) in penBtns) {
            val selected = selectedPenId == id
            val frame = btn as? FrameLayout ?: continue
            frame.background = makeSlotCircleBackground(selected)
            renderPenTileIcon(frame, penDefById(id))
        }
        updatePenTypeLabel()
    }

    private var colorHeaderLabel: TextView? = null

    private fun buildColorSection(parent: LinearLayout) {
        colorBtns.clear()

        colorHeaderLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        updateColorHeaderLabel()
        parent.addView(colorHeaderLabel)

        buildColorGrid(parent, PRODUCT_COLORS, 4)
    }

    private fun buildColorGrid(parent: LinearLayout, colors: List<PenColorDef>, cols: Int) {
        val gap = ScreenScale.px(reactContext, EDITOR_GAP_PX)
        for (i in colors.indices step cols) {
            val row = LinearLayout(reactContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                if (i > 0) layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = gap }
            }
            for (c in 0 until cols) {
                if (c > 0) row.addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(gap, 0)
                })
                if (i + c < colors.size) {
                    row.addView(makeColorDot(colors[i + c]))
                } else {
                    row.addView(View(reactContext).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    })
                }
            }
            parent.addView(row)
        }
    }

    private fun updateColorHeaderLabel() {
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        colorHeaderLabel?.text = colorDef?.label ?: NativeLocale.t("palette_color")
    }

    private fun makeColorDot(def: PenColorDef): FrameLayout {
        val cellSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
        val dotSz = ScreenScale.px(reactContext, COLOR_SWATCH_PX)
        val cell = FrameLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            setOnClickListener {
                selectedColor = def.key
                updateColorSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }
            addView(View(reactContext).apply {
                layoutParams = FrameLayout.LayoutParams(dotSz, dotSz, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(def.swatch)
                    setStroke(
                        if (def.isExtended) dp(1) else dp(1.5f),
                        when (def.key) {
                            "black" -> Color.BLACK
                            "darkGray" -> Color.parseColor("#9D9D9D")
                            else -> Color.parseColor("#C9C9C9")
                        }
                    )
                }
            })
        }
        colorBtns[def.key] = cell
        return cell
    }

    private fun updateColorSelection() {
        for ((key, btn) in colorBtns) {
            btn.alpha = 1f
            btn.background = makeColorCircleBackground(selectedColor == key)
        }
        updateColorHeaderLabel()
    }

    private fun makeColorCircleBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            if (selected) {
                setStroke(
                    ScreenScale.px(reactContext, COLOR_SELECTED_BORDER_PX).coerceAtLeast(1),
                    INK,
                )
            } else {
                setStroke(
                    ScreenScale.px(reactContext, COLOR_BORDER_PX).coerceAtLeast(1),
                    INK,
                    ScreenScale.px(reactContext, COLOR_DASH_PX).coerceAtLeast(1).toFloat(),
                    ScreenScale.px(reactContext, COLOR_DASH_GAP_PX).coerceAtLeast(1).toFloat(),
                )
            }
        }
    }

    private fun buildThicknessInline(parent: LinearLayout) {

        val tierRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for ((i, tier) in tierPresets.withIndex()) {
            if (i > 0) tierRow.addView(View(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ScreenScale.px(reactContext, EDITOR_GAP_PX), 0
                )
            })
            val btn = makeTierBtn(tier)
            tierBtns[tier.key] = btn
            tierRow.addView(btn)
        }
        parent.addView(tierRow)

        val stepper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        }
        stepper.addView(makeStepperBtn("−") { stepThickness(-1) })
        thicknessLabel = TextView(reactContext).apply {
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
        }
        stepper.addView(thicknessLabel)
        stepper.addView(makeStepperBtn("+") { stepThickness(1) })
        parent.addView(stepper)

        syncThicknessDisplay()
    }

    private fun makeTierBtn(tier: TierPreset): LinearLayout {
        val isActive = activeTierKey == tier.key
        return LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val circleSize = ScreenScale.px(reactContext, EDITOR_CIRCLE_PX)
            layoutParams = LinearLayout.LayoutParams(
                circleSize, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                if (effectivePenType() == PenSizeSpec.MARKER) return@setOnClickListener
                if (activeTierKey == tier.key) {
                    activeTierKey = null
                } else {
                    activeTierKey = tier.key
                    thickness = PenSizeSpec.rawForLabel(effectivePenType(), tier.labelValue)
                    thicknessChanged = true
                }
                syncThicknessDisplay()
                updateTierSelection()
                updateApplyState()
                autoSaveToActivePreset()
            }

            addView(FrameLayout(reactContext).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
                background = makeSlotCircleBackground(isActive)
                val dotSize = tierDotSize(effectiveTierValue(tier))
                addView(View(reactContext).apply {
                    layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(INK)
                    }
                })
            })

            addView(TextView(reactContext).apply {
                text = PenSizeSpec.formatLabel(effectiveTierValue(tier))
                textSize = sp(15f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(INK)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    circleSize, ScreenScale.px(reactContext, SLOT_LABEL_HEIGHT_PX)
                ).apply { topMargin = ScreenScale.px(reactContext, SLOT_LABEL_MARGIN_PX) }
            })
        }
    }

    private fun effectiveTierValue(tier: TierPreset): Float {
        val labels = PenSizeSpec.labelsFor(effectivePenType())
        if (labels.isEmpty()) return tier.labelValue
        val idx = labels.indices.minByOrNull { Math.abs(labels[it] - tier.labelValue) } ?: 0
        return labels[idx]
    }

    private fun tierDotSize(value: Float): Int {
        val progress = ((value - 0.1f) / 0.9f).coerceIn(0f, 1f)
        return ScreenScale.px(reactContext, 18f + 30f * progress)
    }

    private fun makeStepperBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(INK2)
            gravity = Gravity.CENTER
            val sz = dp(38)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1.5f), LINE2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateTierSelection() {
        val isMarker = effectivePenType() == PenSizeSpec.MARKER
        thicknessSection?.visibility = if (isMarker) View.GONE else View.VISIBLE
        for ((key, btn) in tierBtns) {
            val isActive = activeTierKey == key
            val tier = tierPresets.find { it.key == key } ?: continue
            val cell = btn as? LinearLayout ?: continue
            val circle = cell.getChildAt(0) as? FrameLayout ?: continue
            val dot = circle.getChildAt(0) ?: continue
            val label = cell.getChildAt(1) as? TextView ?: continue
            val value = effectiveTierValue(tier)
            val dotSize = tierDotSize(value)

            circle.background = makeSlotCircleBackground(isActive)
            dot.layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            label.text = PenSizeSpec.formatLabel(value)
        }
    }

    private fun syncThicknessDisplay() {
        val isMarker = effectivePenType() == PenSizeSpec.MARKER
        val labelText = if (isMarker) "—" else PenSizeSpec.label(effectivePenType(), thickness)
        thicknessLabel?.text = labelText
        thicknessHeaderLabel?.text = NativeLocale.t("palette_thickness")
    }

    private fun stepThickness(direction: Int) {
        val penType = effectivePenType()
        if (penType == PenSizeSpec.MARKER) return

        if (activeTierKey != null) {
            val tier = tierPresets.find { it.key == activeTierKey } ?: return
            val labels = PenSizeSpec.labelsFor(penType)
            if (labels.isEmpty()) return
            val curIdx = labels.indices.minByOrNull { Math.abs(labels[it] - tier.labelValue) } ?: return
            val newIdx = (curIdx + direction).coerceIn(0, labels.lastIndex)
            tier.labelValue = labels[newIdx]
            thickness = PenSizeSpec.rawForLabel(penType, tier.labelValue)
            thicknessChanged = true
            saveTierPrefs()
            updateTierSelection()
        } else {
            val steps = PenSizeSpec.stepsFor(penType)
            val curIdx = steps.indexOfFirst { it == thickness }.takeIf { it >= 0 }
                ?: steps.indices.minByOrNull { Math.abs(steps[it] - thickness) } ?: 0
            val newIdx = (curIdx + direction).coerceIn(0, steps.lastIndex)
            thickness = steps[newIdx]
            thicknessChanged = true
        }
        syncThicknessDisplay()
        updateApplyState()
        autoSaveToActivePreset()
    }

    private fun loadSlotIntoEditor() {
        val preset = curPreset() ?: return
        selectedPenId = preset.penId
        selectedColor = preset.color
        thickness = preset.thickness
        thicknessChanged = true
        activeTierKey = null
    }

    private fun restoreFromLasso() {
        selectedPenType(dominantPenType)
        selectedColor = initialColor
        thickness = PenSizeSpec.snap(initialPenType?.let { normalizePenType(it) }, avgThickness)
        thicknessChanged = false
        activeTierKey = null
    }

    private fun doResetAll() {
        val count = presets.count { it != null }
        if (count == 0) return
        showDeleteConfirm(count) {
            for (i in presets.indices) presets[i] = null
            selectedSlot = null
            savePresets()
            refreshAll()
        }
    }

    private fun showDeleteConfirm(count: Int, onConfirm: () -> Unit) {
        showConfirmDialog(NativeLocale.t("palette_delete_confirm", count), onConfirm)
    }

    private fun showConfirmDialog(msg: String, onConfirm: () -> Unit) {
        com.ratta.supernote.pluginlib.api.HostUIAPI.getInstance().showRattaDialog(
            reactContext.currentActivity, msg,
            NativeLocale.t("cancel"), NativeLocale.t("confirm"), false,
            object : com.ratta.supernote.pluginlib.callback.RattaDialogListener {
                override fun onConfirm() { onConfirm() }
                override fun onCancel() {}
            }
        )
    }

    private fun autoSaveToActivePreset() {
        val idx = selectedSlot ?: return
        val preset = curPreset() ?: return
        val color = selectedColor ?: return
        val penId = selectedPenId ?: return
        presets[idx] = Preset(penId, color, thickness)
        savePresets()
        rebuildGrid()
    }

    private fun colorChanged(): Boolean {
        val cur = selectedColor ?: return false
        return cur != initialColor
    }

    private fun penTypeChanged(): Boolean {
        val curType = effectivePenType() ?: return false
        return curType != initialPenType
    }

    private fun updateApplyState() {
        val canApply = colorChanged() || thicknessChanged || penTypeChanged()
        applyBtn?.alpha = if (canApply) 1f else 0.4f
        applyBtn?.isEnabled = canApply
    }

    private fun refreshAll() {
        rebuildGrid()
        updatePenSelection()
        updateColorSelection()
        updateTierSelection()
        syncThicknessDisplay()
        updateApplyState()
    }

    private fun doApply() {
        val colorDef = selectedColor?.let { colorDefByKey(it) }
        val colorValue = if (colorChanged() && colorDef != null) {
            if (colorDef.isExtended) 0x00 else colorDef.value
        } else null

        val map = Arguments.createMap().apply {
            if (colorValue != null) putInt("penColor", colorValue)
            if (thicknessChanged) putInt("thickness", thickness)
            if (penTypeChanged()) putInt("penType", effectivePenType()!!)
            putString("elementNums", JSONArray(elementNums).toString())
            
            
            putBoolean("hasMarkerStroke", hasMarkerStroke)
        }
        toolbarModule.emitEvent("paletteApply", map)
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun loadPresets() {
        try {
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            val json = prefs.getString(PRESETS_KEY, null) ?: return
            val arr = JSONArray(json)
            
            
            val legacyVisible = intArrayOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 10)
            for (i in 0 until MAX_SLOTS) {
                val sourceIndex = if (arr.length() >= 12) legacyVisible[i] else i
                if (sourceIndex >= arr.length() || arr.isNull(sourceIndex)) {
                    presets[i] = null
                    continue
                }
                val o = arr.getJSONObject(sourceIndex)
                val penId = o.optString("penId", "")
                val color = o.optString("color", "black")
                val rawThickness = o.optInt("thickness", 200)
                if (penId.isEmpty()) {
                    val rawPt = o.optInt("penType", 10)
                    val pt = when (rawPt) { 0 -> 10; 1 -> 16; 2 -> 10; else -> rawPt }
                    val migPenId = when {
                        pt == 11 -> "marker"
                        pt == 16 -> "ball"
                        pt == 14 -> "brush"
                        else -> "needle"
                    }
                    presets[i] = Preset(migPenId, color, PenSizeSpec.snap(pt, rawThickness))
                } else {
                    val normalizedPenId = canonicalPenId(penId)
                    val pen = penDefById(normalizedPenId)
                    presets[i] = Preset(normalizedPenId, color, PenSizeSpec.snap(pen.penType, rawThickness))
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "loadPresets: ${e.message}")
        }
    }

    private fun savePresets() {
        try {
            val arr = JSONArray()
            for (i in 0 until MAX_SLOTS) {
                val p = presets[i]
                if (p == null) { arr.put(JSONObject.NULL); continue }
                arr.put(JSONObject().apply {
                    put("penId", p.penId)
                    put("color", p.color)
                    put("thickness", p.thickness)
                    put("penType", p.penType)
                })
            }
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString(PRESETS_KEY, arr.toString()).apply()
            PaletteBubbleModule.reloadPresets()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "savePresets: ${e.message}")
        }
    }

    private fun loadTierPrefs() {
        try {
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            val json = prefs.getString(TIER_PREFS_KEY, null) ?: return
            val o = JSONObject(json)
            for (tier in tierPresets) {
                if (o.has(tier.key)) {
                    tier.labelValue = o.getDouble(tier.key).toFloat()
                }
            }
        } catch (_: Exception) {}
    }

    private fun saveTierPrefs() {
        try {
            val o = JSONObject()
            for (tier in tierPresets) {
                o.put(tier.key, tier.labelValue.toDouble())
            }
            val prefs = reactContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.edit().putString(TIER_PREFS_KEY, o.toString()).apply()
        } catch (_: Exception) {}
    }
}
