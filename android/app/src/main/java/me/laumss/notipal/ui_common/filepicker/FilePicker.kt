package me.laumss.notipal.ui_common.filepicker

import me.laumss.notipal.NativeLocale
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.ui_common.MultiSelectState
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque


class FilePicker(
    private val ctx: Context,
    private val parent: FrameLayout,
) {
    companion object {
        const val ROOT = "/storage/emulated/0"
        
        val HOME_FOLDERS = listOf("Document", "Export", "MyStyle", "Note", "SCREENSHOT", "INBOX")

        fun pathForChip(key: String): String = when (key) {
            "Export" -> "$ROOT/EXPORT"
            else -> "$ROOT/$key"
        }

        fun chipForPath(path: String): String? =
            HOME_FOLDERS.firstOrNull { pathForChip(it) == path }
    }

    private data class Item(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val isImage: Boolean,
        val size: Long,
        val childCount: Int,
        val previewPaths: List<String> = emptyList(),
        val modifiedAt: Long = 0L,
    )

    
    private data class ListingFilter(
        val imageTypes: Set<String>,
        val allowedTypes: Set<String>,
        val selectedTypes: Set<String>,
        val excludedPaths: Set<String>,
        val rootFolders: List<String>,
        val generation: Int,
    )

    private var overlay: View? = null
    private var breadcrumbHost: LinearLayout? = null
    private var chips: FilePickerWidgets.FormatChips? = null
    private var topHost: FrameLayout? = null
    private var gridContainer: LinearLayout? = null
    private var gridScroll: ScrollView? = null
    private var bottomTray: FilePickerChrome.BottomTrayHandle? = null

    private var imageExtensions: Set<String> = emptySet()
    private var docExtensions: Set<String> = emptySet()
    private var filterExtensions: Set<String> = emptySet()
    private var filterChipKeys: List<String> = emptyList()
    private var allowImageMulti = false
    private var confirmDocuments = false
    private var allowDocumentMulti = false
    private val documentSelection = MultiSelectState()
    private var excludedPaths: Set<String> = emptySet()
    private var rootFolders: List<String> = HOME_FOLDERS
    private var onReadBlocked: (() -> Unit)? = null
    private var readBlockedGeneration = -1
    private var keepOpenAfterPick = false
    private var pickOnTap = false
    private var maxImages = 30
    private var emptyHint = ""
    private var actionLabel = ""
    private var onPicked: ((List<String>) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private var currentPath = ROOT
    private var items: List<Item> = emptyList()
    private val selectedImages = LinkedHashSet<String>()
    private var selectedDoc: String? = null
    private val fileCards = mutableMapOf<String, FilePickerCells.FileCardView>()
    private val selectedFilters = LinkedHashSet<String>()
    @Volatile private var generation = 0
    private var gridRevision = 0
    private var listingPending = false
    private var active = false
    private var nestedSuspended = false
    private var traceSequence = 0

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dp(v: Int) = FilePickerScale.dp(ctx, v)

    val isShowing: Boolean get() = active && overlay?.visibility == View.VISIBLE

    fun suspendForNested() {
        nestedSuspended = true
        overlay?.visibility = View.INVISIBLE
    }

    fun resumeAfterNested() {
        if (!active) return
        nestedSuspended = false
        if (listingPending) return
        overlay?.apply {
            visibility = View.VISIBLE
            bringToFront()
        }
    }

    fun show(
        title: String,
        imageExtensions: Set<String> = emptySet(),
        docExtensions: Set<String> = emptySet(),
        allowImageMulti: Boolean = false,
        confirmDocuments: Boolean = false,
        allowDocumentMulti: Boolean = false,
        excludedPaths: Set<String> = emptySet(),
        additionalRootFolders: List<String> = emptyList(),
        onReadBlocked: (() -> Unit)? = null,
        keepOpenAfterPick: Boolean = false,
        pickOnTap: Boolean = false,
        maxImages: Int = 30,
        startDir: String = ROOT,
        emptyHint: String = NativeLocale.t("picker_file_default"),
        actionLabel: String = NativeLocale.t("picker_select"),
        topContent: View? = null,
        onCancel: (() -> Unit)? = null,
        onPicked: (List<String>) -> Unit,
    ) {
        
        
        
        if (overlay != null) destroy()
        nestedSuspended = false
        this.keepOpenAfterPick = keepOpenAfterPick
        this.pickOnTap = pickOnTap
        attach()
        active = true
        this.imageExtensions = imageExtensions.map { it.lowercase() }.toSet()
        this.docExtensions = docExtensions.map { it.lowercase() }.toSet()
        this.filterExtensions = (this.imageExtensions + this.docExtensions).toSet()
        filterChipKeys = orderedFormatKeys(filterExtensions)
        this.allowImageMulti = allowImageMulti
        this.confirmDocuments = confirmDocuments
        this.allowDocumentMulti = confirmDocuments && allowDocumentMulti
        this.excludedPaths = excludedPaths.map(::normalizePath).toSet()
        this.rootFolders = (HOME_FOLDERS + additionalRootFolders.filter {
            it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it
        }).distinct()
        this.onReadBlocked = onReadBlocked
        documentSelection.clear()
        this.maxImages = maxImages.coerceAtLeast(1)
        this.emptyHint = emptyHint
        this.actionLabel = actionLabel
        setTopContent(topContent)
        this.onPicked = onPicked
        this.onCancel = onCancel
        trace("show title=$title pickOnTap=$pickOnTap keepOpen=$keepOpenAfterPick")
        selectedImages.clear()
        selectedDoc = null
        selectedFilters.clear()
        chips?.setKeys(filterChipKeys)
        chips?.clearSelection()
        val initialPath = normalizePath(startDir).trimEnd('/')
        currentPath = initialPath.takeIf { it == ROOT || it.startsWith("$ROOT/") } ?: ROOT
        refreshSelectionChrome()
        navigateTo(currentPath)
    }

    fun handleBack() {
        if (currentPath != ROOT) navigateUp() else dismiss()
    }

    fun dismiss(cancelled: Boolean = true) {
        trace("dismiss cancelled=$cancelled")
        generation++
        active = false
        listingPending = false
        nestedSuspended = false
        overlay?.visibility = View.INVISIBLE
        fileCards.clear()
        gridContainer?.removeAllViews()
        val cancel = onCancel
        onPicked = null
        onCancel = null
        if (cancelled) cancel?.invoke()
    }

    fun refresh() {
        if (active) navigateTo(currentPath)
    }

    private fun relayout() {
        if (active && !listingPending) rebuildGrid()
    }

    private fun normalizePath(path: String): String = runCatching { File(path).canonicalPath }
        .getOrElse { path.replace("/sdcard/", "$ROOT/") }

    fun destroy() {
        dismiss(cancelled = false)
        overlay?.let { parent.removeView(it) }
        overlay = null
        breadcrumbHost = null
        chips = null
        topHost = null
        gridContainer = null
        gridScroll = null
        bottomTray = null
        onReadBlocked = null
    }

    private fun attach() {
        if (overlay != null) return
        val page = FrameLayout(ctx).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.INVISIBLE
            isClickable = true
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (oldRight > oldLeft && oldBottom > oldTop &&
                    (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
                    handler.post { relayout() }
                }
            }
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        breadcrumbHost = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                FilePickerScale.px(ctx, 86f),
            )
        }
        body.addView(breadcrumbHost)
        chips = FilePickerWidgets.FormatChips(ctx, emptyList()) { formats ->
            selectedFilters.clear()
            selectedFilters.addAll(formats)
            
            
            val hasImageFilter = formats.any { key ->
                imageExtensions.any { ext -> matchesFilterKey(ext, key) }
            }
            if (!confirmDocuments && formats.isNotEmpty() && !hasImageFilter) {
                selectedImages.clear()
                selectedDoc = null
            }
            navigateTo(currentPath)
            refreshSelectionChrome()
        }
        body.addView(chips!!.createView())

        topHost = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        body.addView(topHost)

        gridScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            
            setPadding(0, 0, 0, dp(84))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        gridContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        gridScroll?.addView(gridContainer)
        body.addView(gridScroll)

        page.addView(body)
        bottomTray = FilePickerChrome.buildBottomBackTray(ctx, { handleBack() }, { confirm() }, { toggleDocumentMulti() })
        page.addView(bottomTray!!.view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(24) })

        bottomTray?.setAction(NativeLocale.t("picker_select"), visible = false)

        overlay = page
        parent.addView(page)
    }

    
    fun setTopContent(content: View?) {
        val host = topHost ?: return
        host.removeAllViews()
        content ?: return
        host.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun navigateUp() {
        if (currentPath == ROOT) return
        val parentDir = File(currentPath).parent ?: ROOT
        navigateTo(if (parentDir.length < ROOT.length) ROOT else parentDir)
    }

    private fun navigateTo(path: String) {
        if (!active) return
        val gen = ++generation
        listingPending = true
        currentPath = path
        refreshSelectionChrome()
        val filter = ListingFilter(imageExtensions.toSet(), filterExtensions.toSet(), selectedFilters.toSet(),
            excludedPaths, rootFolders.toList(), gen)
        
        
        Thread({
            val nextItems = try {
                if (path == ROOT) {
                    loadHomeFolders(filter) + collectHomeFiles(filter, gen)
                } else {
                    loadTraditional(path, filter)
                }
            } catch (_: SecurityException) {
                reportReadBlocked(gen)
                emptyList()
            }
            handler.post {
                if (gen != generation) return@post
                items = nextItems
                rebuildGrid(resetScroll = true)
            }
        }, "InklingPickerDirectory").apply { isDaemon = true }.start()
    }

    private fun refreshBreadcrumb() {
        val host = breadcrumbHost ?: return
        host.removeAllViews()
        host.addView(
            FilePickerCells.breadcrumb(ctx, ROOT, currentPath) { navigateTo(it) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
            FilePickerScale.px(ctx, 86f),
            ),
        )
    }

    
    private fun loadHomeFolders(filter: ListingFilter): List<Item> =
        HOME_FOLDERS.mapNotNull { key ->
            val folder = File(pathForChip(key))
            if (!isDirectory(folder, filter.generation)) return@mapNotNull null
            val kids = visibleChildren(folder, filter.generation)
            Item(
                name = key,
                path = folder.absolutePath,
                isDir = true,
                isImage = false,
                size = 0L,
                childCount = kids.size,
                modifiedAt = try { folder.lastModified() } catch (_: SecurityException) { 0L },
            )
        }

    private fun collectHomeFiles(filter: ListingFilter, expectedGeneration: Int): List<Item> {
        val seen = HashSet<String>()
        val files = ArrayList<File>()
        filter.rootFolders.forEach { key ->
            val root = File(pathForChip(key))
            if (!isDirectory(root, expectedGeneration)) return@forEach
            val pending = ArrayDeque<File>()
            val visited = HashSet<String>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                if (expectedGeneration != generation) return emptyList()
                val folder = pending.removeFirst()
                val folderPath = normalizePath(folder.absolutePath)
                if (!visited.add(folderPath)) continue
                visibleChildren(folder, expectedGeneration).forEach { child ->
                    when {
                        child.isDirectory -> pending.addLast(child)
                        child.isFile && matchesExtension(child.name, filter) &&
                            normalizePath(child.absolutePath) !in filter.excludedPaths && seen.add(child.absolutePath) ->
                            files.add(child)
                    }
                }
            }
        }
        return files.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        ).map { fileItem(it, filter) }
    }

    private fun loadTraditional(path: String, filter: ListingFilter): List<Item> {
        val dir = File(path)
        val children = try {
            dir.listFiles() ?: emptyArray()
        } catch (_: SecurityException) {
            reportReadBlocked(filter.generation)
            return emptyList()
        }
        return children.asSequence()
            .filter { !it.name.startsWith(".") }
            .mapNotNull { f ->
                val isDir = try { f.isDirectory } catch (_: SecurityException) { false }
                if (isDir) {
                    val kids = visibleChildren(f, filter.generation)
                    val previews = kids.asSequence()
                        .filter { it.isFile && FilePickerCells.isPreviewImage(it.name) }
                        .take(4)
                        .map { it.absolutePath }
                        .toList()
                    Item(
                        f.name, f.absolutePath, isDir = true, isImage = false, 0L, kids.size,
                        previews, try { f.lastModified() } catch (_: SecurityException) { 0L },
                    )
                } else {
                    if (!matchesExtension(f.name, filter) || normalizePath(f.absolutePath) in filter.excludedPaths) return@mapNotNull null
                    fileItem(f, filter)
                }
            }
            .sortedWith(
                compareByDescending<Item> { it.isDir }
                    .thenByDescending { it.modifiedAt }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

    private fun reportReadBlocked(expectedGeneration: Int) {
        handler.post {
            if (generation != expectedGeneration || readBlockedGeneration == expectedGeneration) return@post
            readBlockedGeneration = expectedGeneration
            onReadBlocked?.invoke()
        }
    }

    private fun isDirectory(file: File, expectedGeneration: Int): Boolean = try {
        file.isDirectory
    } catch (_: SecurityException) {
        reportReadBlocked(expectedGeneration)
        false
    }

    private fun visibleChildren(directory: File, expectedGeneration: Int): List<File> = try {
        directory.listFiles()?.filter { it.name.isNotBlank() && !it.name.startsWith('.') }.orEmpty()
    } catch (_: SecurityException) {
        reportReadBlocked(expectedGeneration)
        emptyList()
    }

    private fun fileItem(file: File, filter: ListingFilter) = Item(
        name = file.name,
        path = file.absolutePath,
        isDir = false,
        isImage = extensionOf(file.name) in filter.imageTypes,
        size = try { file.length() } catch (_: SecurityException) { 0L },
        childCount = 0,
        modifiedAt = try { file.lastModified() } catch (_: SecurityException) { 0L },
    )

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    
    private fun orderedFormatKeys(extensions: Set<String>): List<String> {
        val aliases = linkedMapOf(
            "epub" to setOf("epub"),
            "pdf" to setOf("pdf"),
            "txt" to setOf("txt"),
            "md" to setOf("md", "markdown"),
            "cbz" to setOf("cbz"),
            "jpg" to setOf("jpg", "jpeg"),
            "png" to setOf("png"),
            "webp" to setOf("webp"),
        )
        val ordered = aliases.filterValues { it.any(extensions::contains) }.keys.toMutableList()
        extensions.filter { ext -> aliases.values.none { ext in it } }.sorted().forEach(ordered::add)
        return ordered
    }

    private fun matchesFilterKey(ext: String, key: String): Boolean = when (key) {
        "md" -> ext == "md" || ext == "markdown"
        "jpg" -> ext == "jpg" || ext == "jpeg"
        else -> ext == key
    }

    private fun matchesExtension(name: String, filter: ListingFilter): Boolean {
        val ext = extensionOf(name)
        if (ext !in filter.allowedTypes) return false
        return filter.selectedTypes.isEmpty() || filter.selectedTypes.any { matchesFilterKey(ext, it) }
    }

    private fun pickerColumns(): Int {
        val landscape = if (parent.width > 0 && parent.height > 0) {
            parent.width > parent.height
        } else {
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        return if (landscape) 6 else FilePickerDevice.profile(ctx).pickerGridColumns
    }

    private fun rebuildGrid(resetScroll: Boolean = false) {
        val container = gridContainer ?: return
        val gen = generation
        val revision = ++gridRevision
        if (container.width <= 0) {
            container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                                            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    if (right <= left) return
                    v.removeOnLayoutChangeListener(this)
                    handler.post {
                        if (gen == generation && revision == gridRevision) rebuildGrid(resetScroll)
                    }
                }
            })
            return
        }
        val columns = pickerColumns()
        val nextCards = mutableMapOf<String, FilePickerCells.FileCardView>()
        val nextGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (items.isEmpty()) {
            nextGrid.addView(TextView(ctx).apply {
                text = emptyHint.ifBlank { NativeLocale.t("picker_empty_folder") }
                textSize = FilePickerScale.sp(ctx, 13.5f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(0, dp(60), 0, dp(60))
            })
        } else {
            val atRoot = currentPath == ROOT
            val dirs = items.filter { it.isDir }
            val files = items.filter { !it.isDir }
            if (atRoot && dirs.isNotEmpty()) {
                nextGrid.addView(buildHomeFolderRow(dirs, columns))
            }
            val gridItems = if (atRoot) files else items
            if (gridItems.isNotEmpty()) {
                nextGrid.addView(buildStandardGrid(gridItems, gen, columns, nextCards))
            }
        }
        if (gen != generation || revision != gridRevision) return
        
        
        container.removeAllViews()
        container.addView(nextGrid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fileCards.clear()
        fileCards.putAll(nextCards)
        listingPending = false
        refreshBreadcrumb()
        refreshSelectionChrome()
        val page = overlay ?: return
        if (parent.width > 0 && parent.height > 0) {
            page.measure(
                View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY),
            )
            page.layout(0, 0, parent.width, parent.height)
        }
        if (resetScroll) gridScroll?.scrollTo(0, 0)
        if (!nestedSuspended) {
            page.visibility = View.VISIBLE
            page.bringToFront()
        }
        trace("grid_commit path=$currentPath columns=$columns items=${items.size} generation=$gen")
    }

    private fun buildHomeFolderRow(dirs: List<Item>, columns: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        dirs.chunked(columns).forEach { chunk ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { item ->
                line.addView(
                    FilePickerCells.homeFolderCell(ctx, item.name) { navigateTo(item.path) },
                    LinearLayout.LayoutParams(0, FilePickerScale.px(ctx, 126f), 1f),
                )
            }
            repeat(columns - chunk.size) {
                line.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            }
            row.addView(line)
        }
        return row
    }

    private fun buildStandardGrid(gridItems: List<Item>, gen: Int, columns: Int,
                                  cards: MutableMap<String, FilePickerCells.FileCardView>): View {
        val container = gridContainer ?: return View(ctx)
        val gap = dp(12)
        val colW = ((container.width - container.paddingLeft - container.paddingRight -
            gap * (columns - 1)) / columns).coerceAtLeast(dp(80))
        val coverW = (colW * 0.92f).toInt()
        val coverH = (coverW * FilePickerCells.COVER_ASPECT).toInt()
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var line: LinearLayout? = null
        gridItems.forEachIndexed { index, item ->
            if (index % columns == 0) {
                line = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, gap)
                }
                grid.addView(line)
            }
            val cell = if (item.isDir) {
                FilePickerCells.folderCell(
                    ctx, item.name, item.childCount, coverW, coverH, item.previewPaths,
                    ::loadThumbnail,
                ) { navigateTo(item.path) }
            } else {
                val (view, card) = FilePickerCells.fileCell(
                    ctx, item.name, fileDetail(item), item.path, coverW, coverH,
                    isSelectedPath(item.path), ::loadThumbnail,
                ) { onCellTap(item) }
                cards[item.path] = card
                view
            }
            line?.addView(cell, LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index % columns != 0) marginStart = gap
            })
        }
        if (gen != generation) return View(ctx)
        return grid
    }

    private fun fileDetail(item: Item): String {
        val ext = extensionOf(item.name).uppercase().ifBlank { "FILE" }
        return "$ext · ${formatSize(item.size)}"
    }

    private fun loadThumbnail(path: String, view: ImageView, reqW: Int, reqH: Int) {
        val gen = generation
        Thread({
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= reqW &&
                    bounds.outHeight / (sample * 2) >= reqH) sample *= 2
                val bmp = BitmapFactory.decodeFile(
                    path, BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@Thread
                handler.post {
                    if (gen == generation) view.setImageBitmap(bmp) else bmp.recycle()
                }
            } catch (_: Throwable) {}
        }, "InklingPickerThumb").apply { isDaemon = true }.start()
    }

    private fun isSelectedPath(path: String): Boolean =
        path == selectedDoc || path in selectedImages || documentSelection.isSelected(path)

    private fun onCellTap(item: Item) {
        if (!active || listingPending) return
        if (item.isDir) {
            navigateTo(item.path)
            return
        }
        if (confirmDocuments && !item.isImage) {
            
            
            if (documentSelection.isActive) {
                updateCell(item.path, documentSelection.toggle(item.path))
                refreshSelectionChrome()
            } else {
                trace("tap_auto path=${item.path}")
                selectedDoc = item.path
                confirm()
            }
            return
        }
        if (pickOnTap && (!item.isImage || !allowImageMulti)) {
            trace("tap_auto path=${item.path}")
            selectedImages.clear()
            selectedDoc = item.path
            confirm()
            return
        }
        trace("tap_select path=${item.path} image=${item.isImage}")
        if (!item.isImage) {
            selectedImages.clear()
            selectedDoc = item.path
            confirm()
            return
        }
        if (allowImageMulti) {
            selectedDoc?.let { doc ->
                selectedDoc = null
                updateCell(doc, false)
            }
            if (item.path in selectedImages) {
                selectedImages.remove(item.path)
                updateCell(item.path, false)
            } else {
                if (selectedImages.size >= maxImages) return
                selectedImages.add(item.path)
                updateCell(item.path, true)
            }
        } else {
            if (selectedImages.isNotEmpty()) {
                val cleared = selectedImages.toList()
                selectedImages.clear()
                cleared.forEach { updateCell(it, false) }
            }
            val previous = selectedDoc
            selectedDoc = if (previous == item.path) null else item.path
            previous?.let { updateCell(it, false) }
            selectedDoc?.let { updateCell(it, true) }
        }
        refreshSelectionChrome()
    }

    private fun updateCell(path: String, selected: Boolean) {
        fileCards[path]?.setCardSelected(selected)
    }

    private fun toggleDocumentMulti() {
        if (!active || !allowDocumentMulti || listingPending) return
        if (documentSelection.isActive) {
            documentSelection.deactivate()
        } else {
            documentSelection.activate()
        }
        selectedDoc = null
        fileCards.forEach { (path, card) -> card.setCardSelected(isSelectedPath(path)) }
        refreshSelectionChrome()
    }

    private fun refreshSelectionChrome() {
        val imageCount = selectedImages.size
        val hasImageSelection = imageCount > 0 || selectedDoc?.let { extensionOf(it) in imageExtensions } == true
        val multiActive = documentSelection.isActive
        val count = if (multiActive) documentSelection.count else imageCount
        
        
        val actionVisible = hasImageSelection || (confirmDocuments && multiActive)
        val actionEnabled = (hasImageSelection || (multiActive && count > 0)) && !listingPending
        bottomTray?.setMultiSelection(
            visible = allowDocumentMulti,
            enabled = !listingPending,
            active = multiActive,
        )
        bottomTray?.setAction(
            if (count > 0) "$actionLabel (${NativeLocale.itemCount(count)})" else actionLabel,
            visible = actionVisible,
            enabled = actionEnabled,
            count = count,
        )
    }

    private fun confirm() {
        if (!active || listingPending) return
        
        val result = when {
            documentSelection.isActive -> documentSelection.selectedPaths.ifEmpty { return }
            selectedDoc != null -> listOf(selectedDoc!!)
            selectedImages.isNotEmpty() -> selectedImages.toList()
            else -> return
        }
        trace("confirm paths=${result.joinToString()}")
        val done = onPicked
        if (keepOpenAfterPick) {
            selectedImages.clear()
            selectedDoc = null
            documentSelection.clear()
            rebuildGrid()
            refreshSelectionChrome()
            done?.invoke(result)
        } else {
            dismiss(cancelled = false)
            done?.invoke(result)
        }
    }

    private fun trace(event: String) {
        if (!BuildConfig.ENABLE_DEBUG) return
        traceSequence += 1
        android.util.Log.i(
            "InklingFilePicker",
            "picker-trace seq=$traceSequence wallMs=${System.currentTimeMillis()} " +
                "elapsedMs=${SystemClock.elapsedRealtime()} $event",
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
