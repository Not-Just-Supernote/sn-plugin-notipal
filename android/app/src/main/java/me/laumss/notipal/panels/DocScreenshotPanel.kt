package me.laumss.notipal.panels
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.*
import me.laumss.notipal.overlays.*
import me.laumss.notipal.bubbles.*

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.ui_common.ButtonHandle
import me.laumss.notipal.ui_common.CheckboxHandle
import me.laumss.notipal.ui_common.MultiSelectState
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.PanelGrid
import me.laumss.notipal.ui_common.PanelHost
import me.laumss.notipal.ui_common.PanelScrollHost
import me.laumss.notipal.ui_common.PanelTabBar
import me.laumss.notipal.ui_common.PanelWidgets
import me.laumss.notipal.ui_common.TabBarHandle
import java.io.File
import kotlin.concurrent.thread

class DocScreenshotPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "DocScreenshotPanel"
    override val panelName = "screenshot"
    override val heightRatio = 0.81

    companion object {
        @Volatile var currentInstance: DocScreenshotPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): DocScreenshotPanel {
            val inst = currentInstance ?: DocScreenshotPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val QUEUE_DIR   = "/sdcard/SCREENSHOT/.plugin_staging/queue"
        private const val HISTORY_DIR = "/sdcard/SCREENSHOT/.plugin_history"
    }

    private var activeTab = "history"
    private var selectedPath: String? = null

    private lateinit var tabBarH: TabBarHandle
    private lateinit var insertBtn: ButtonHandle
    private lateinit var deleteBtn: ButtonHandle
    private lateinit var multiCheckbox: CheckboxHandle
    private val multi = MultiSelectState()
    private val cellFrameMap = mutableMapOf<String, FrameLayout>()
    private var deleting = false
    private var gridRebuild: (() -> Unit)? = null
    private var gridScrollTop: (() -> Unit)? = null

    fun show(initialTab: String = "history", onResult: ((Boolean) -> Unit)? = null) {
        ScreenshotBubble.pendingReshow = false
        ScreenshotBubble.hide()
        currentInstance = this
        selectedPath = null
        multi.clear()
        deleting = false
        activeTab = initialTab
        if (activeTab == "queue") {
            val queueDir = java.io.File(QUEUE_DIR)
            val hasFiles = queueDir.exists() && queueDir.listFiles()?.any { it.name.endsWith(".png") } == true
            if (!hasFiles) activeTab = "history"
        }
        showPanel(onResult)
    }

    override fun onHide() {
        cellFrameMap.clear()
        gridRebuild = null
        gridScrollTop = null
        currentInstance = null
    }

    override fun buildContent(root: LinearLayout) {
        renderDsl(root) {
            header(NativeLocale.t("screenshot_panel_title"))
            tabBarH = tabBar(
                listOf(
                    PanelTabBar.Tab(NativeLocale.t("tab_queue")),
                    PanelTabBar.Tab(NativeLocale.t("tab_history"))
                ),
                initial = if (activeTab == "queue") 0 else 1
            ) { idx -> switchTab(if (idx == 0) "queue" else "history") }

            custom { host ->
                val scroll = PanelScrollHost(host.ctx, overlayScrollbar = true)
                gridRebuild = {
                    scroll.content.removeAllViews()
                    cellFrameMap.clear()
                    val files = loadFiles()
                    if (files.isEmpty()) {
                        scroll.content.addView(PanelWidgets.emptyView(host,
                            if (activeTab == "queue") NativeLocale.t("no_queue")
                            else NativeLocale.t("no_history")))
                    } else {
                        PanelGrid.build(host.ctx, scroll, host.screenW, host.screenH, host.panelW, files) { file, colW ->
                            screenshotCell(host, file, colW)
                        }
                    }
                    scroll.refreshThumb()
                }
                gridScrollTop = { scroll.scrollToTop() }
                gridRebuild?.invoke()
                scroll.view
            }

            bottomBar {
                multiCheckbox = checkbox(NativeLocale.t("multi_select")) { toggleMultiSelect() }
                outlined(NativeLocale.t("cancel")) { closeAndRestore() }
                deleteBtn = outlined(NativeLocale.t("delete")) { doDelete() }
                insertBtn = filled(NativeLocale.t("insert")) { doInsert() }
            }
        }
        updateButtons()
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        selectedPath = null
        updateButtons()
        gridRebuild?.invoke()
        gridScrollTop?.invoke()
    }

    private fun selectionPaths(): List<String> =
        if (multi.isActive) multi.selectedPaths else listOfNotNull(selectedPath)

    private fun updateButtons() {
        val hasSelection = selectionPaths().isNotEmpty() && !deleting
        insertBtn.enabled = hasSelection
        deleteBtn.enabled = hasSelection
        multiCheckbox.setChecked(multi.isActive)
        multiCheckbox.setLabel(if (multi.isActive) "${NativeLocale.t("multi_select")} (${multi.count})"
            else NativeLocale.t("multi_select"))
        multiCheckbox.setActive(!deleting && (multi.isActive || selectedPath != null))
    }

    private fun toggleMultiSelect() {
        if (deleting || (!multi.isActive && selectedPath == null)) return
        if (multi.isActive) multi.deactivate() else multi.activate(seed = selectedPath)
        selectedPath = null
        updateButtons()
        gridRebuild?.invoke()
    }

    private fun updateCellSelection(host: PanelHost, path: String, selected: Boolean) {
        val frame = cellFrameMap[path] ?: return
        (frame.background as? GradientDrawable)?.setStroke(
            if (selected) host.dp(2) else host.dp(1),
            if (selected) Color.BLACK else Color.parseColor("#CCCCCC"))
        val checkmark = frame.findViewWithTag<View>("checkmark")
        if (selected && multi.isActive && checkmark == null) {
            frame.addView(PanelWidgets.imageSelectionCheckmark(host))
        } else if (!selected && checkmark != null) frame.removeView(checkmark)
    }

    private fun loadFiles(): List<File> {
        val dir = if (activeTab == "queue") QUEUE_DIR else HISTORY_DIR
        val folder = File(dir)
        if (!folder.exists() || !folder.isDirectory) return emptyList()
        return (folder.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".png") }
            .sortedByDescending { it.name.removeSuffix(".png").toLongOrNull() ?: 0L }
    }

    private fun screenshotCell(host: PanelHost, file: File, colW: Int): View {
        val thumbH = (colW * 1.1f).toInt()
        val isSelected = if (multi.isActive) multi.isSelected(file.absolutePath)
            else selectedPath == file.absolutePath

        val cell = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (deleting) return@setOnClickListener
                if (multi.isActive) {
                    val selected = multi.toggle(file.absolutePath)
                    updateButtons()
                    updateCellSelection(host, file.absolutePath, selected)
                } else {
                    val previous = selectedPath
                    selectedPath = if (previous == file.absolutePath) null else file.absolutePath
                    updateButtons()
                    previous?.let { updateCellSelection(host, it, false) }
                    selectedPath?.let { updateCellSelection(host, it, true) }
                }
            }
        }

        val thumbFrame = FrameLayout(host.ctx).apply {
            layoutParams = LinearLayout.LayoutParams(colW, thumbH)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(if (isSelected) host.dp(2) else host.dp(1),
                    if (isSelected) Color.BLACK else Color.parseColor("#CCCCCC"))
                cornerRadius = host.dp(4).toFloat()
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, host.dp(4).toFloat())
                }
            }
        }
        val imageView = ImageView(host.ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2))
        }
        thumbFrame.addView(imageView)
        loadThumbnail(file.absolutePath, colW, thumbH, imageView)
        if (isSelected && multi.isActive) thumbFrame.addView(PanelWidgets.imageSelectionCheckmark(host))
        cellFrameMap[file.absolutePath] = thumbFrame
        cell.addView(thumbFrame)

        val textContainer = LinearLayout(host.ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(2), host.dp(6), host.dp(2), host.dp(4))
        }
        val ts = file.name.removeSuffix(".png").toLongOrNull() ?: 0L
        val timeStr = if (ts > 0) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } else file.name
        textContainer.addView(TextView(host.ctx).apply {
            text = timeStr; textSize = host.gridSp(12f); setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
        })
        textContainer.addView(TextView(host.ctx).apply {
            text = PanelWidgets.formatSize(file.length()); textSize = host.gridSp(10f)
            setTextColor(Color.parseColor("#666666"))
        })
        cell.addView(textContainer)
        return cell
    }

    private fun doInsert() {
        if (deleting) return
        val paths = selectionPaths()
        if (paths.isEmpty()) return
        val notePath = FloatingToolbarModule.lastNotePath
        val pageNum = FloatingToolbarModule.lastPageNum
        multi.clear()
        hide()
        if (!MosaicLink.isBoardSurface()) {
            thread(isDaemon = true) {
                paths.forEach { ImagePanel.saveToInsertCacheStatic(it, notePath, pageNum) }
            }
        }
        toolbar.startMultiImageInsert(paths, fromReceived = false)
    }

    private fun doDelete() {
        if (deleting) return
        val paths = selectionPaths()
        if (paths.isEmpty()) return
        val ownerView = rootView
        deleting = true
        updateButtons()
        thread(isDaemon = true) {
            val deleted = paths.filter { path ->
                try {
                    val file = File(path)
                    val ok = !file.exists() || file.delete()
                    if (ok) DocScreenshotService.unmarkInsertNext(file.name)
                    ok
                } catch (e: Exception) {
                    Log.w(tag, "Screenshot deletion failed: $path", e)
                    false
                }
            }
            handler.post {
                if (rootView !== ownerView || rootView == null) return@post
                deleting = false
                deleted.forEach { path ->
                    if (multi.isSelected(path)) multi.toggle(path)
                    if (selectedPath == path) selectedPath = null
                }
                updateButtons()
                gridRebuild?.invoke()
                if (deleted.size != paths.size) {
                    me.laumss.notipal.ui_common.Dialog.tip(reactContext,
                        NativeLocale.t("screenshot_delete_failed"))
                }
            }
        }
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun loadThumbnail(path: String, reqW: Int, reqH: Int, imageView: ImageView) {
        thread(isDaemon = true) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                val halfH = opts.outHeight / 2; val halfW = opts.outWidth / 2
                while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
                opts.inSampleSize = sample
                opts.inJustDecodeBounds = false
                val bmp = BitmapFactory.decodeFile(path, opts) ?: return@thread
                handler.post { imageView.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }
}
