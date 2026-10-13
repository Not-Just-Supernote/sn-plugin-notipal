package me.laumss.notipal.panels

import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.ui_common.filepicker.FilePicker
import java.io.File


class DocLinkPanel(
    ctx: ReactApplicationContext,
    private val toolbar: FloatingToolbarModule,
) : PanelBase(ctx, toolbar) {
    override val tag = "NativeDocPanel"
    override val panelName = "doc"
    override val fullScreen = true
    override val windowFlags = 0

    companion object {
        @Volatile var currentInstance: DocLinkPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): DocLinkPanel {
            val instance = currentInstance ?: DocLinkPanel(ctx, module)
            currentInstance = instance
            return instance
        }

        val DOC_EXTS = setOf("epub", "pdf", "cbz", "doc", "docx", "note", "mobi", "fb2")
    }

    private var picker: FilePicker? = null
    private var currentNotePath: String? = null
    private var session = 0
    private var fileReadPermissionRequested = false
    private var lastFilePermissionToastAt = 0L

    fun show(currentFilePath: String?) {
        currentInstance = this
        currentNotePath = currentFilePath?.let(::normalizePath)
        fileReadPermissionRequested = false
        val showingSession = ++session
        showPanel { shown ->
            if (!shown) {
                toolbarModule.restoreToolbar()
                return@showPanel
            }
            if (session != showingSession) return@showPanel
            picker?.show(
                title = NativeLocale.t("doc_panel_title"),
                docExtensions = DOC_EXTS,
                confirmDocuments = true,
                allowDocumentMulti = true,
                excludedPaths = setOfNotNull(currentNotePath),
                additionalRootFolders = listOf("LocalSend", "Books", "Download"),
                emptyHint = NativeLocale.t("doc_no_files"),
                actionLabel = NativeLocale.t("doc_insert_link"),
                onReadBlocked = { requestFileReadPermissionOnce(showingSession) },
                onCancel = { closeAndRestore() },
                onPicked = { paths -> insertLinks(paths) },
            )
            rootView?.requestFocus()
        }
    }

    override fun buildFullScreenContent(): View {
        
        val host = object : FrameLayout(reactContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) picker?.handleBack()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }
        picker = FilePicker(reactContext, host)
        return host
    }

    override fun onHide() {
        ++session
        picker?.destroy()
        picker = null
        fileReadPermissionRequested = false
        if (currentInstance === this) currentInstance = null
    }

    
    fun onFileReceived() {
        handler.post { if (isShowing) picker?.refresh() }
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun requestFileReadPermissionOnce(showingSession: Int) {
        if (session != showingSession || !isShowing || fileReadPermissionRequested) return
        
        
        fileReadPermissionRequested = true
        val now = System.currentTimeMillis()
        if (now - lastFilePermissionToastAt > 2500L) {
            lastFilePermissionToastAt = now
            Toast.makeText(reactContext, NativeLocale.t("file_read_permission_needed"), Toast.LENGTH_LONG).show()
        }
        toolbar.requestPluginFileReadPermission { granted ->
            handler.post {
                if (session != showingSession || !isShowing) return@post
                if (granted) picker?.refresh() else toolbar.openPluginSettingsAfterFileReadDenied()
            }
        }
    }

    private fun normalizePath(path: String): String = runCatching { File(path).canonicalPath }
        .getOrElse { path.replace("/sdcard/", "${FilePicker.ROOT}/") }

    private fun insertLinks(paths: List<String>) {
        val selected = paths.filter { normalizePath(it) != currentNotePath }.distinct()
        if (selected.isEmpty()) {
            closeAndRestore()
            return
        }
        hide()
        toolbar.startMultiDocLinkInsert(selected)
    }
}
