package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.core.AppPrefs
import me.laumss.notipal.ui_common.*
import org.json.JSONArray
import org.json.JSONObject

class ConfigPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "ConfigPanel"
    override val panelName = "config"

    companion object {
        @Volatile var currentInstance: ConfigPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): ConfigPanel {
            val inst = currentInstance ?: ConfigPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val SHARED_CONFIG_STORE_KEY = "shared_preset"
        private const val CONFIG_STORE_KEY = "note_preset"
        private const val DOC_CONFIG_STORE_KEY = "doc_preset"
        private const val PREFS_NAME = "quicktoolbar_presets"
        private const val TOOL_GRID_COLUMNS = 5
        private const val TOOL_GRID_CELL_HEIGHT_DP = 84
        private const val TOOL_GRID_ICON_SIZE_DP = 44
        private const val NOTE_SECTION_TITLE_SP = 21.11f
        private const val NOTE_SECTION_HINT_SP = 16.15f
        private const val TAB_TOOLS = 0

        
        private var currentTab = TAB_TOOLS
    }

    data class ToolDef(
        val id: String,
        val iconAsset: String,
        val action: String,
        val nameKey: String,
        var hidden: Boolean = false
    )

    private val TOOL_DEFS = listOf(
        ToolDef("insert_image",          "icons/ic_tool_image.xml",    "insert_image",          "config_tool_image"),
        ToolDef("insert_doc_screenshot", "icons/ic_tool_doc.xml",      "insert_doc_screenshot", "config_tool_doc"),
        ToolDef("insert_text",           "icons/ic_tool_text.xml",     "insert_text",           "config_tool_text"),
        ToolDef(FloatingToolbarModule.SMART_LASSO_TOOL_ID, "icons/ic_tool_lasso_ai.xml", FloatingToolbarModule.SMART_LASSO_TOOL_ID, "config_tool_smart_lasso"),
        ToolDef("insert_link",           "icons/ic_tool_link.xml",     "insert_link",           "config_tool_link"),
        ToolDef(FloatingToolbarModule.INK_PALETTE_TOOL_ID, "icons/ic_tool_palette.xml", FloatingToolbarModule.INK_PALETTE_TOOL_ID, "config_tool_ink_palette"),
        ToolDef(FloatingToolbarModule.AI_RELAY_TOOL_ID, "icons/ic_tool_ai_relay.xml", FloatingToolbarModule.AI_RELAY_TOOL_ID, "config_tool_ai_relay"),
        ToolDef(FloatingToolbarModule.CLIPBOARD_TOOL_ID, "icons/ic_tool_clipboard.xml", FloatingToolbarModule.CLIPBOARD_TOOL_ID, "config_tool_clipboard"),
        ToolDef(FloatingToolbarModule.COLLAPSE_TOOL_ID, "icons/ic_tool_collapse.xml", FloatingToolbarModule.COLLAPSE_TOOL_ID, "config_tool_collapse"),
    )

    private enum class ToolScope { SHARED, NOTE, DOC }

    private var sharedTools = mutableListOf<ToolDef>()
    private var tools = mutableListOf<ToolDef>()
    private var docTools = mutableListOf<ToolDef>()

    private var scrollHost: PanelScrollHost? = null
    private var contentArea: LinearLayout? = null
    private var selectedToolsGrid: GridLayout? = null
    private var removedToolsGrid: GridLayout? = null
    private var selectedEmptyView: View? = null
    private var removedToolsSection: View? = null
    private var noteSelectedToolsGrid: GridLayout? = null
    private var noteRemovedToolsGrid: GridLayout? = null
    private var noteSelectedEmptyView: View? = null
    private var noteRemovedToolsSection: View? = null
    private var docSelectedToolsGrid: GridLayout? = null
    private var docRemovedToolsGrid: GridLayout? = null
    private var docRemovedToolsSection: View? = null
    private var toolGridSpanCount = TOOL_GRID_COLUMNS
    private var draggingToolId: String? = null
    private var draggingScope = ToolScope.NOTE
    private val tabPages = mutableListOf<View>()
    private var restoreBtn: View? = null

    
    internal val isActuallyVisible: Boolean
        get() = rootView?.let { root ->
            root.visibility == View.VISIBLE && root.isAttachedToWindow
        } == true

    fun show() {
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(tag, "show requested showing=$isShowing visible=$isActuallyVisible")
        }
        currentInstance = this
        
        
        FloatingToolbarModule.setConfigPanelShowPending(true)
        FloatingToolbarModule.setConfigPanelActive(true)
        loadSharedTools()
        loadTools()
        loadDocTools()
        if (isShowing) resumeVisibility()
        showPanel { shown ->
            
            
            if (currentInstance === this) {
                FloatingToolbarModule.setConfigPanelShowPending(false)
            }
            if (shown) {
                currentInstance = this
                FloatingToolbarModule.setConfigPanelActive(true)
            } else if (currentInstance === this) {
                FloatingToolbarModule.setConfigPanelActive(false)
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(tag, "show result=$shown showing=$isShowing visible=$isActuallyVisible")
            }
        }
    }

    override fun onHide() {
        val wasCurrent = currentInstance === this
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "onHide current=$wasCurrent")
        scrollHost = null
        contentArea = null
        selectedToolsGrid = null
        removedToolsGrid = null
        selectedEmptyView = null
        removedToolsSection = null
        noteSelectedToolsGrid = null
        noteRemovedToolsGrid = null
        noteSelectedEmptyView = null
        noteRemovedToolsSection = null
        docSelectedToolsGrid = null
        docRemovedToolsGrid = null
        docRemovedToolsSection = null
        draggingToolId = null
        draggingScope = ToolScope.NOTE
        tabPages.clear()
        restoreBtn = null
        if (wasCurrent) {
            
            
            
            if (FloatingToolbarModule.isConfigPanelShowPending()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "onHide: newer show pending, keep session")
                return
            }
            currentInstance = null
            FloatingToolbarModule.setConfigPanelShowPending(false)
            FloatingToolbarModule.setConfigPanelActive(false)
        }
    }

    override fun buildContent(root: LinearLayout) {
        buildMainContent(root)
    }

    private fun buildMainContent(root: LinearLayout) {
        tabPages.clear()
        renderDsl(root) {
            header("Notipal")

            tabBar(
                listOf("config_tab_tools", "config_tab_connections", "config_tab_about")
                    .map { PanelTabBar.Tab(NativeLocale.t(it)) },
                initial = currentTab
            ) { idx -> selectTab(idx) }

            custom { h ->
                val pages = FrameLayout(h.ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val sh = PanelScrollHost(h.ctx)
                scrollHost = sh
                contentArea = sh.content
                rebuildToolList()
                for (page in listOf(sh.view, buildConnectionsPage(h), buildAboutPage(h))) {
                    page.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    pages.addView(page)
                    tabPages.add(page)
                }
                selectTab(currentTab)
                pages
            }

            custom { _ ->
                val restore = makeOutlinedBtn(NativeLocale.t("config_restore_defaults")) {
                    restoreDefaultSharedTools()
                    restoreDefaultTools()
                    docTools.clear()
                    docTools.addAll(docToolDefs())
                    refreshDocToolGrids(rebind = true)
                }
                restoreBtn = restore
                restore.visibility = if (currentTab == TAB_TOOLS) View.VISIBLE else View.GONE
                makeBottomBar(
                    leftButtons = listOf(
                        makeOutlinedBtn(NativeLocale.t("btn_cancel")) {
                            hide()
                            toolbar.destroyAll()
                        },
                        restore
                    ),
                    rightButtons = listOf(
                        makeFilledBtn(NativeLocale.t("btn_confirm")) {
                            saveSharedTools()
                            saveTools()
                            saveDocTools()
                            hide()
                            toolbar.destroyAll()
                        }
                    )
                )
            }
        }
    }

    

    private fun selectTab(idx: Int) {
        currentTab = idx
        for (i in tabPages.indices) tabPages[i].visibility = if (i == idx) View.VISIBLE else View.GONE
        restoreBtn?.visibility = if (idx == TAB_TOOLS) View.VISIBLE else View.GONE
        if (idx == TAB_TOOLS) scrollHost?.refreshThumb()
    }

    private fun lightDivider(h: PanelHost, sideDp: Int): View = View(h.ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            leftMargin = h.dp(sideDp); rightMargin = h.dp(sideDp)
        }
        setBackgroundColor(Color.parseColor("#D8D8D8"))
    }

    private fun pageSection(h: PanelHost, title: String): TextView = TextView(h.ctx).apply {
        text = title
        textSize = h.sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.parseColor("#666666"))
        setPadding(h.dp(28), h.dp(22), h.dp(28), h.dp(4))
    }

    private fun scrollPage(h: PanelHost, build: (LinearLayout) -> Unit): View {
        val content = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, h.dp(20))
        }
        build(content)
        return ScrollView(h.ctx).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            addView(content)
        }
    }

    

    
    private fun settingRow(h: PanelHost, title: String, value: TextView?, action: View?): LinearLayout {
        val row = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h.dp(28), h.dp(16), h.dp(28), h.dp(16))
        }
        val col = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = h.dp(16) }
        }
        col.addView(TextView(h.ctx).apply {
            text = title
            textSize = h.sp(17f)
            setTextColor(Color.BLACK)
        })
        if (value != null) col.addView(value)
        row.addView(col)
        if (action != null) row.addView(action)
        return row
    }

    private fun valueText(h: PanelHost): TextView = TextView(h.ctx).apply {
        textSize = h.sp(14f)
        setTextColor(Color.parseColor("#777777"))
        setPadding(0, h.dp(4), 0, 0)
    }

    private fun smallButton(h: PanelHost, label: String, onClick: () -> Unit): TextView =
        TextView(h.ctx).apply {
            text = label
            textSize = h.sp(15f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            minWidth = h.dp(88)
            setPadding(h.dp(16), h.dp(8), h.dp(16), h.dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(h.dp(1), Color.BLACK)
            }
            setOnClickListener { onClick() }
        }

    private fun buildConnectionsPage(h: PanelHost): View = scrollPage(h) { page ->
        page.addView(pageSection(h, NativeLocale.t("config_sec_localsend")))
        val peerValue = valueText(h)
        lateinit var peerBtn: TextView
        fun refreshPeer() {
            val def = LocalSendModule.getDefaultPeer(reactContext)
            if (def != null) {
                peerValue.text = "${def.alias} · ${def.ip}"
                peerBtn.visibility = View.VISIBLE
            } else {
                peerValue.text = NativeLocale.t("config_default_peer_none")
                peerBtn.visibility = View.GONE
            }
        }
        peerBtn = smallButton(h, NativeLocale.t("config_default_peer_unbind")) {
            LocalSendModule.setDefaultPeer(reactContext, null)
            refreshPeer()
        }
        refreshPeer()
        page.addView(settingRow(h, NativeLocale.t("config_default_peer"), peerValue, peerBtn))

        page.addView(pageSection(h, NativeLocale.t("config_sec_airrelay")))
        val core = AIRelayCore.get(reactContext)
        val phoneValue = valueText(h)
        lateinit var phoneBtn: TextView
        fun refreshPhone() {
            val info = core.pairedPhoneInfo()
            if (info != null) {
                phoneValue.text = "${info.host}:${info.port}"
                phoneBtn.visibility = View.VISIBLE
            } else {
                phoneValue.text = NativeLocale.t("config_airrelay_none")
                phoneBtn.visibility = View.GONE
            }
        }
        phoneBtn = smallButton(h, NativeLocale.t("config_airrelay_unbind")) {
            core.unpairPhone()
            refreshPhone()
        }
        refreshPhone()
        page.addView(settingRow(h, NativeLocale.t("config_airrelay_phone"), phoneValue, phoneBtn))
        page.addView(lightDivider(h, 28))

        val toggle = SwitchMark(h.ctx, AppPrefs.autoOpenDetail(reactContext)).apply {
            layoutParams = LinearLayout.LayoutParams(h.dp(56), h.dp(32))
        }
        val autoRow = settingRow(h, NativeLocale.t("config_airrelay_auto_open"), null, toggle)
        autoRow.setOnClickListener {
            val next = !AppPrefs.autoOpenDetail(reactContext)
            AppPrefs.setAutoOpenDetail(reactContext, next)
            toggle.on = next
        }
        page.addView(autoRow)
    }

    
    private class SwitchMark(ctx: android.content.Context, initial: Boolean) : View(ctx) {
        var on: Boolean = initial
            set(value) { field = value; invalidate() }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        override fun onDraw(canvas: android.graphics.Canvas) {
            val stroke = height * 0.07f
            val r = height / 2f
            rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (on) Color.BLACK else Color.WHITE
            canvas.drawRoundRect(rect, r, r, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = Color.BLACK
            canvas.drawRoundRect(rect, r, r, paint)
            val knobR = r - stroke * 2.5f
            val cx = if (on) width - r else r
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (on) Color.WHITE else Color.BLACK
            canvas.drawCircle(cx, height / 2f, knobR, paint)
        }
    }

    

    private fun buildAboutPage(h: PanelHost): View = scrollPage(h) { page ->
        page.addView(LinearLayout(h.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(h.dp(28), h.dp(24), h.dp(28), h.dp(4))
            addView(TextView(h.ctx).apply {
                text = "Notipal"
                textSize = h.sp(22f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.BLACK)
            })
            val version = AboutInfo.versionLabel()
            addView(TextView(h.ctx).apply {
                text = if (version.isNotEmpty()) NativeLocale.t("about_version", version)
                       else NativeLocale.t("about_tagline")
                textSize = h.sp(14f)
                setTextColor(Color.parseColor("#777777"))
                setPadding(0, h.dp(4), 0, 0)
            })
        })

        page.addView(qrCard(
            h,
            payload = AboutInfo.DISCORD_URL,
            title = NativeLocale.t("about_discord_title"),
            desc = NativeLocale.t("about_discord_desc"),
            link = AboutInfo.DISCORD_URL.removePrefix("https://")
        ))
        if (AboutInfo.FEEDBACK_EMAIL.isNotEmpty()) {
            page.addView(qrCard(
                h,
                payload = AboutInfo.feedbackMailto(),
                title = NativeLocale.t("about_feedback_title"),
                desc = NativeLocale.t("about_feedback_desc"),
                link = AboutInfo.FEEDBACK_EMAIL
            ))
        }
    }

    
    private fun qrCard(h: PanelHost, payload: String, title: String, desc: String, link: String): View {
        val card = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(h.dp(16), h.dp(16), h.dp(20), h.dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(h.dp(1), Color.parseColor("#BBBBBB"))
                cornerRadius = h.dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = h.dp(24); rightMargin = h.dp(24); topMargin = h.dp(16) }
        }
        val qr = QrCode.encode(payload)
        if (qr != null) {
            val target = h.dp(150)
            val scale = maxOf(2, target / (qr.size + 4))
            val bmp = qr.toBitmap(scale, quiet = 2)
            card.addView(ImageView(h.ctx).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams(bmp.width, bmp.height).apply { rightMargin = h.dp(20) }
            })
        }
        val col = LinearLayout(h.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(h.ctx).apply {
            text = title
            textSize = h.sp(18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        col.addView(TextView(h.ctx).apply {
            text = desc
            textSize = h.sp(14f)
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, h.dp(6), 0, 0)
            setLineSpacing(h.dp(2).toFloat(), 1f)
        })
        col.addView(TextView(h.ctx).apply {
            text = link
            textSize = h.sp(14f)
            setTextColor(Color.BLACK)
            setPadding(0, h.dp(10), 0, 0)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        card.addView(col)
        return card
    }

    private fun rebuildToolList() {
        val content = contentArea ?: return
        scrollHost?.prepareForContentChange()
        content.removeAllViews()
        toolGridSpanCount = TOOL_GRID_COLUMNS

        fun addSectionTitle(title: String, hint: String) {
            content.addView(TextView(reactContext).apply {
                text = title
                textSize = sp(NOTE_SECTION_TITLE_SP)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(Color.parseColor("#222222"))
                setPadding(dp(10), dp(12), dp(10), dp(3))
            })
            content.addView(TextView(reactContext).apply {
                text = hint
                textSize = sp(NOTE_SECTION_HINT_SP)
                setTextColor(Color.parseColor("#555555"))
                setPadding(dp(10), 0, dp(10), dp(8))
            })
        }

        fun addRemovedSection(onCreated: (GridLayout) -> Unit): View {
            val section = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(reactContext).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        leftMargin = dp(10); rightMargin = dp(10)
                        topMargin = dp(12); bottomMargin = dp(12)
                    }
                    setBackgroundColor(Color.parseColor("#BBBBBB"))
                })
                addView(TextView(reactContext).apply {
                    text = NativeLocale.t("config_removed_tools")
                    textSize = sp(NOTE_SECTION_HINT_SP)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#222222"))
                    setPadding(dp(10), 0, dp(10), dp(3))
                })
                addView(TextView(reactContext).apply {
                    text = NativeLocale.t("config_removed_hint")
                    textSize = sp(NOTE_SECTION_HINT_SP)
                    setTextColor(Color.parseColor("#555555"))
                    setPadding(dp(10), 0, dp(10), dp(7))
                })
            }
            val grid = makeToolGrid()
            section.addView(grid)
            onCreated(grid)
            content.addView(section)
            return section
        }

        addSectionTitle(
            NativeLocale.t("config_shared_tools"),
            NativeLocale.t("config_shared_hint")
        )
        selectedToolsGrid = makeToolGrid()
        content.addView(selectedToolsGrid)

        addSectionTitle(
            NativeLocale.t("config_note_tools"),
            NativeLocale.t("config_note_hint")
        )
        noteSelectedToolsGrid = makeToolGrid()
        content.addView(noteSelectedToolsGrid)

        if (docToolDefs().isNotEmpty()) {
            addSectionTitle(
                NativeLocale.t("config_doc_tools"),
                NativeLocale.t("config_doc_hint")
            )
            docSelectedToolsGrid = makeToolGrid()
            content.addView(docSelectedToolsGrid)
            docRemovedToolsSection = addRemovedSection { docRemovedToolsGrid = it }
        }

        refreshSharedToolGrids(rebind = true)
        refreshToolGrids(rebind = true)
        refreshDocToolGrids(rebind = true)
    }

    private fun sharedToolDefs(): List<ToolDef> {
        val ids = listOf(
            "insert_doc_screenshot",
            FloatingToolbarModule.SMART_LASSO_TOOL_ID,
            FloatingToolbarModule.INK_PALETTE_TOOL_ID,
            FloatingToolbarModule.AI_RELAY_TOOL_ID
        )
        return ids.mapNotNull { id -> TOOL_DEFS.find { it.id == id } }
    }

    private fun noteToolDefs(): List<ToolDef> {
        val ids = listOf("insert_image", "insert_link")
        return ids.mapNotNull { id -> TOOL_DEFS.find { it.id == id } }
    }

    private fun docToolDefs(): List<ToolDef> = emptyList()

    private fun defaultSharedToolDefs(): List<ToolDef> = sharedToolDefs()

    private fun defaultToolDefs(): List<ToolDef> = listOf(
        TOOL_DEFS.first { it.id == "insert_image" }
    )

    private fun makeToolGrid(): GridLayout {
        return GridLayout(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            )
            orientation = GridLayout.HORIZONTAL
            columnCount = toolGridSpanCount
            isColumnOrderPreserved = true
            useDefaultMargins = false
            setBackgroundColor(Color.WHITE)
        }
    }

    private fun mutableTools(scope: ToolScope): MutableList<ToolDef> = when (scope) {
        ToolScope.SHARED -> sharedTools
        ToolScope.NOTE -> tools
        ToolScope.DOC -> docTools
    }

    private fun selectedGrid(scope: ToolScope): GridLayout? = when (scope) {
        ToolScope.SHARED -> selectedToolsGrid
        ToolScope.NOTE -> noteSelectedToolsGrid
        ToolScope.DOC -> docSelectedToolsGrid
    }

    private fun makeToolCell(tool: ToolDef, enableDrag: Boolean, scope: ToolScope): FrameLayout {
        val gridWidth = scrollHost?.availableContentWidth(winW) ?: winW
        val cell = FrameLayout(reactContext).apply {
            tag = tool.id
            layoutParams = GridLayout.LayoutParams().apply {
                width = gridWidth / toolGridSpanCount
                height = dp(TOOL_GRID_CELL_HEIGHT_DP)
            }
            isClickable = true
            isFocusable = true
            contentDescription = NativeLocale.t(tool.nameKey)
        }
        val icon = ImageView(reactContext).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = FrameLayout.LayoutParams(
                dp(TOOL_GRID_ICON_SIZE_DP), dp(TOOL_GRID_ICON_SIZE_DP), Gravity.CENTER
            )
            setImageDrawable(
                UiUtils.loadAssetIcon(
                    reactContext, tool.iconAsset, dp(TOOL_GRID_ICON_SIZE_DP),
                    Color.parseColor("#111111")
                )
            )
        }
        cell.addView(icon)
        if (scope == ToolScope.NOTE) applyNoteToolVisibility(icon, tool)
        cell.setOnClickListener {
            if (scope != ToolScope.NOTE) return@setOnClickListener
            tool.hidden = !tool.hidden
            applyNoteToolVisibility(icon, tool)
        }

        if (enableDrag) {
            cell.setOnLongClickListener {
                draggingToolId = tool.id
                draggingScope = scope
                val clip = android.content.ClipData.newPlainText("tool", tool.id)
                val started = cell.startDragAndDrop(
                    clip, View.DragShadowBuilder(cell), tool.id, 0
                )
                if (started) {
                    icon.visibility = View.INVISIBLE
                    cell.background = makeDragPlaceholder()
                } else {
                    draggingToolId = null
                }
                started
            }
            cell.setOnDragListener { _, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                        draggingToolId?.let { sourceId ->
                            if (sourceId != tool.id && draggingScope == scope) {
                                moveDraggedTool(sourceId, tool.id, scope)
                            }
                        }
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        finishToolDrag()
                        true
                    }
                    else -> true
                }
            }
        }
        return cell
    }

    private fun makeDragPlaceholder(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(dp(1), Color.BLACK, dp(3).toFloat(), dp(3).toFloat())
    }

    private fun moveDraggedTool(sourceId: String, targetId: String, scope: ToolScope) {
        val list = mutableTools(scope)
        val from = list.indexOfFirst { it.id == sourceId }
        val to = list.indexOfFirst { it.id == targetId }
        if (from !in list.indices || to !in list.indices || from == to) return

        val moved = list.removeAt(from)
        list.add(to, moved)
        val grid = selectedGrid(scope) ?: return
        val draggedView = (0 until grid.childCount)
            .map { grid.getChildAt(it) }
            .firstOrNull { it.tag == sourceId }
            ?: return
        grid.removeView(draggedView)
        grid.addView(draggedView, to)
        grid.requestLayout()
        grid.invalidate()
    }

    private fun finishToolDrag() {
        val sourceId = draggingToolId ?: return
        val grid = selectedGrid(draggingScope)
        val draggedView = grid?.let {
            (0 until it.childCount).map { index -> it.getChildAt(index) }
                .firstOrNull { view -> view.tag == sourceId }
        }
        draggedView?.background = null
        val icon = (draggedView as? FrameLayout)?.getChildAt(0)
        icon?.visibility = View.VISIBLE
        if (draggingScope == ToolScope.NOTE && icon is ImageView) {
            tools.find { it.id == sourceId }?.let { applyNoteToolVisibility(icon, it) }
        }
        draggedView?.invalidate()
        grid?.invalidate()
        draggingToolId = null
    }

    private fun applyNoteToolVisibility(icon: ImageView, tool: ToolDef) {
        icon.alpha = 1f
        icon.clearColorFilter()
        icon.setImageDrawable(
            UiUtils.loadAssetIcon(
                reactContext, tool.iconAsset, dp(TOOL_GRID_ICON_SIZE_DP),
                if (tool.hidden) Color.parseColor("#B3B3B3") else Color.parseColor("#111111")
            )
        )
    }

    private fun rebuildGrid(grid: GridLayout?, items: List<ToolDef>, enableDrag: Boolean, scope: ToolScope) {
        val target = grid ?: return
        target.removeAllViews()
        target.columnCount = toolGridSpanCount
        for (tool in items) target.addView(makeToolCell(tool, enableDrag, scope))
    }

    private fun refreshSharedToolGrids(rebind: Boolean) {
        val available = sharedToolDefs()
        val selected = sharedTools.filter { tool -> available.any { it.id == tool.id } }
        if (rebind) {
            draggingToolId = null
            rebuildGrid(selectedToolsGrid, selected, enableDrag = true, scope = ToolScope.SHARED)
        }
        updateGridHeight(selectedToolsGrid, selected.size)
        scrollHost?.refreshThumb()
    }

    private fun refreshToolGrids(rebind: Boolean) {
        val available = noteToolDefs()
        val selected = tools.filter { tool -> available.any { it.id == tool.id } }
        if (rebind) {
            draggingToolId = null
            rebuildGrid(noteSelectedToolsGrid, selected, enableDrag = true, scope = ToolScope.NOTE)
        }
        updateGridHeight(noteSelectedToolsGrid, selected.size)
        scrollHost?.refreshThumb()
    }

    private fun refreshDocToolGrids(rebind: Boolean) {
        val available = docToolDefs()
        val selectedIds = docTools.mapTo(mutableSetOf()) { it.id }
        val selected = docTools.filter { tool -> available.any { it.id == tool.id } }
        val removed = available.filter { it.id !in selectedIds }
        if (rebind) {
            rebuildGrid(docSelectedToolsGrid, selected, enableDrag = true, scope = ToolScope.DOC)
            rebuildGrid(docRemovedToolsGrid, removed, enableDrag = false, scope = ToolScope.DOC)
        }
        updateGridHeight(docSelectedToolsGrid, selected.size)
        updateGridHeight(docRemovedToolsGrid, removed.size)
        docRemovedToolsSection?.visibility = if (removed.isEmpty()) View.GONE else View.VISIBLE
        scrollHost?.refreshThumb()
    }

    private fun updateGridHeight(grid: GridLayout?, itemCount: Int) {
        val target = grid ?: return
        val rows = (itemCount + toolGridSpanCount - 1) / toolGridSpanCount
        target.rowCount = rows.coerceAtLeast(1)
        target.layoutParams = (target.layoutParams as LinearLayout.LayoutParams).apply {
            height = rows * dp(TOOL_GRID_CELL_HEIGHT_DP)
        }
        target.visibility = if (itemCount == 0) View.GONE else View.VISIBLE
        target.requestLayout()
    }

    private fun removeTool(id: String, scope: ToolScope) {
        val list = mutableTools(scope)
        list.removeAll { it.id == id }
        when (scope) {
            ToolScope.SHARED -> refreshSharedToolGrids(rebind = true)
            ToolScope.NOTE -> refreshToolGrids(rebind = true)
            ToolScope.DOC -> refreshDocToolGrids(rebind = true)
        }
    }

    private fun addTool(tool: ToolDef, scope: ToolScope) {
        val list = mutableTools(scope)
        if (list.any { it.id == tool.id }) return
        list.add(tool)
        when (scope) {
            ToolScope.SHARED -> refreshSharedToolGrids(rebind = true)
            ToolScope.NOTE -> refreshToolGrids(rebind = true)
            ToolScope.DOC -> refreshDocToolGrids(rebind = true)
        }
    }

    private fun restoreDefaultSharedTools() {
        sharedTools.clear()
        sharedTools.addAll(defaultSharedToolDefs())
        refreshSharedToolGrids(rebind = true)
    }

    private fun restoreDefaultTools() {
        val visibleIds = defaultToolDefs().mapTo(mutableSetOf()) { it.id }
        tools.clear()
        tools.addAll(noteToolDefs().map { it.copy(hidden = it.id !in visibleIds) })
        refreshToolGrids(rebind = true)
    }

    private fun readStoredTools(key: String, allowed: List<ToolDef>): Pair<MutableList<ToolDef>, Boolean> {
        val result = mutableListOf<ToolDef>()
        var found = false
        try {
            val raw = reactContext.getSharedPreferences(PREFS_NAME, 0).getString(key, null)
            val arr = raw?.let { JSONObject(it).optJSONArray("tools") }
            if (arr != null) {
                found = true
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val action = item.optString("action", "")
                    val def = allowed.find { it.id == id || it.action == action } ?: continue
                    if (result.none { it.id == def.id }) {
                        result.add(def.copy(hidden = item.optBoolean("hidden", false)))
                    }
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(tag, "readStoredTools[$key]: ${e.message}")
        }
        return result to found
    }

    private fun loadSharedTools() {
        val available = sharedToolDefs()
        val (stored, found) = readStoredTools(SHARED_CONFIG_STORE_KEY, available)
        sharedTools.clear()
        val ordered = mutableListOf<ToolDef>()
        ordered.addAll(if (found) stored.map { it.copy(hidden = false) } else defaultSharedToolDefs())
        for (tool in available) if (ordered.none { it.id == tool.id }) ordered.add(tool)
        sharedTools.addAll(ordered)
    }

    private fun loadTools() {
        val available = noteToolDefs()
        val (stored, found) = readStoredTools(CONFIG_STORE_KEY, available)
        tools.clear()
        if (found) {
            val ordered = stored.map { it.copy() }.toMutableList()
            for (tool in available) {
                if (ordered.none { it.id == tool.id }) ordered.add(tool.copy(hidden = true))
            }
            tools.addAll(ordered)
        } else {
            val visibleIds = defaultToolDefs().mapTo(mutableSetOf()) { it.id }
            tools.addAll(available.map { it.copy(hidden = it.id !in visibleIds) })
        }
    }

    private fun toolJson(items: List<ToolDef>): String {
        val arr = JSONArray()
        items.forEach { tool ->
            arr.put(JSONObject().apply {
                put("id", tool.id)
                put("action", tool.action)
                put("nameKey", tool.nameKey)
                put("name", NativeLocale.t(tool.nameKey))
                put("icon", tool.id)
                put("latches", tool.action in setOf("insert_text", "text_recv_nospacing", "text_recv_paragraph", "ai_relay"))
                put("hidden", tool.hidden)
            })
        }
        return JSONObject().put("tools", arr).toString()
    }

    private fun saveSharedTools() {
        reactContext.getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(SHARED_CONFIG_STORE_KEY, toolJson(sharedTools)).apply()
    }

    private fun saveTools() {
        try {
            reactContext.getSharedPreferences(PREFS_NAME, 0).edit()
                .putString(CONFIG_STORE_KEY, toolJson(tools)).apply()
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "saveTools: saved ${tools.size} note-only tools")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(tag, "saveTools: ${e.message}")
        }
    }

    private fun loadDocTools() {
        val (stored, found) = readStoredTools(DOC_CONFIG_STORE_KEY, docToolDefs())
        docTools.clear()
        val groupedPreset = reactContext.getSharedPreferences(PREFS_NAME, 0)
            .contains(SHARED_CONFIG_STORE_KEY)
        docTools.addAll(if (found && (groupedPreset || stored.isNotEmpty())) stored else docToolDefs())
    }

    private fun saveDocTools() {
        reactContext.getSharedPreferences(PREFS_NAME, 0).edit()
            .putString(DOC_CONFIG_STORE_KEY, toolJson(docTools)).apply()
    }

}
