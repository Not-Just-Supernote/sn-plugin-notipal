package me.laumss.notipal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.UUID

internal object MosaicLink {

    private const val TAG = "MosaicLink"

    const val ACTION_BOARD_STATE = "me.laumss.mosaic.BOARD_STATE"
    const val ACTION_TOOLBAR_RECT = "me.laumss.mosaic.INKLING_TOOLBAR_RECT"
    
    const val ACTION_OVERLAY_RECTS = "me.laumss.mosaic.INKLING_OVERLAY_RECTS"
    
    const val ACTION_OVERLAY_DRAG = "me.laumss.mosaic.INKLING_OVERLAY_DRAG"
    const val ACTION_REQUEST = "me.laumss.mosaic.INKLING_REQUEST"
    const val ACTION_PALETTE_RESULT = "me.laumss.mosaic.PALETTE_RESULT"

    const val CMD_CLOSE = "close"
    const val CMD_SYNC = "sync"
    const val CMD_PASTE_STROKES = "paste_strokes"
    
    const val CMD_CLEAR_SELECTION = "clear_selection"
    
    const val CMD_DELETE_SELECTION = "delete_selection"
    
    const val CMD_TEXT_CARD = "text_card"
    
    const val CMD_INBOX = "inbox"

    
    const val SURFACE_BOARD = "board"
    const val SURFACE_NOTE = "note"

    @Volatile private var boardVisible = false
    @Volatile private var mosaicSurface = SURFACE_BOARD
    @Volatile private var mosaicNoteRef: String? = null
    
    @Volatile private var chromeMenuOpen = false
    @Volatile private var appContext: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var lastSentRect: Rect? = null
    @Volatile private var lastSentOverlayRects: List<Rect> = emptyList()
    @Volatile private var lastSentOverlayDrag = false
    private val paletteHandler = Handler(Looper.getMainLooper())
    private val paletteRequests = mutableMapOf<String, Pair<(JSONObject) -> Unit, Runnable>>()

    fun isBoardVisible(): Boolean = boardVisible

    
    fun getSurface(): String = mosaicSurface

    fun getNoteRef(): String? = mosaicNoteRef

    fun isChromeMenuOpen(): Boolean = boardVisible && chromeMenuOpen

    
    fun isBoardSurface(): Boolean = boardVisible && mosaicSurface == SURFACE_BOARD

    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PALETTE_RESULT) {
                    val id = intent.getStringExtra("requestId") ?: return
                    val result = try { JSONObject(intent.getStringExtra("result") ?: "{}") }
                    catch (_: Exception) { paletteError("invalid_result") }
                    finishPaletteRequest(id, result)
                    return
                }
                if (intent?.action != ACTION_BOARD_STATE) return
                onBoardState(
                    visible = intent.getBooleanExtra("visible", false),
                    surface = intent.getStringExtra("surface"),
                    noteRef = intent.getStringExtra("noteRef"),
                    chromeMenu = intent.getBooleanExtra("chromeMenu", false),
                    reason = intent.getStringExtra("reason") ?: "",
                )
            }
        }
        val filter = IntentFilter(ACTION_BOARD_STATE).apply { addAction(ACTION_PALETTE_RESULT) }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiver registration failed: ${e.message}", e)
            return
        }
        receiver = r
        appContext = app
        Log.i(TAG, "board-state receiver registered")
        send(app, ACTION_REQUEST) { it.putExtra("cmd", CMD_SYNC) }
    }

    private fun onBoardState(
        visible: Boolean,
        surface: String?,
        noteRef: String?,
        chromeMenu: Boolean,
        reason: String,
    ) {
        val nextSurface = surface.orEmpty()
        val nextNoteRef = noteRef?.trim()?.takeIf { it.isNotEmpty() }
        val nextMenu = visible && chromeMenu
        val menuChanged = chromeMenuOpen != nextMenu
        chromeMenuOpen = nextMenu
        val changed = boardVisible != visible ||
            mosaicSurface != nextSurface ||
            mosaicNoteRef != nextNoteRef
        if (!changed) {
            if (menuChanged) {
                Log.i(TAG, "chrome menu open=$nextMenu reason=$reason")
                FloatingToolbarModule.onMosaicChromeMenuChanged(nextMenu)
            }
            return
        }

        boardVisible = visible
        mosaicSurface = nextSurface
        mosaicNoteRef = nextNoteRef
        me.laumss.notipal.bubbles.PaletteBubbleModule.reloadPresets()
        if (!isBoardSurface()) {
            paletteRequests.keys.toList().forEach { finishPaletteRequest(it, paletteError("surface_changed")) }
        }
        if (!visible) {
            lastSentRect = null
            lastSentOverlayRects = emptyList()
            lastSentOverlayDrag = false
        }
        Log.i(TAG, "board state visible=$visible surface=$nextSurface noteRef=$nextNoteRef reason=$reason")
        FloatingToolbarModule.onMosaicVisibilityChanged(visible, nextSurface, nextNoteRef)
        if (menuChanged) FloatingToolbarModule.onMosaicChromeMenuChanged(nextMenu)
        
        FloatingPenGuard.republishMosaicSnapshot("mosaic-state:$reason")
    }

    fun publishToolbarRect(context: Context?, rect: Rect?, force: Boolean = false) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        if (!force && rect == lastSentRect) return
        lastSentRect = rect?.let(::Rect)
        send(ctx, ACTION_TOOLBAR_RECT) {
            if (rect == null) {
                it.putExtra("present", false)
            } else {
                it.putExtra("present", true)
                it.putExtra("left", rect.left)
                it.putExtra("top", rect.top)
                it.putExtra("right", rect.right)
                it.putExtra("bottom", rect.bottom)
            }
        }
        Log.i(TAG, "toolbar rect published rect=$rect")
    }

    
    fun publishOverlayRects(context: Context?, rects: List<Rect>, force: Boolean = false) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        val normalized = rects.map(::Rect)
        if (!force && normalized == lastSentOverlayRects) return
        lastSentOverlayRects = normalized.map(::Rect)
        send(ctx, ACTION_OVERLAY_RECTS) {
            it.putExtra("count", normalized.size)
            val l = IntArray(normalized.size)
            val t = IntArray(normalized.size)
            val r = IntArray(normalized.size)
            val b = IntArray(normalized.size)
            normalized.forEachIndexed { i, rect ->
                l[i] = rect.left; t[i] = rect.top; r[i] = rect.right; b[i] = rect.bottom
            }
            it.putExtra("left", l)
            it.putExtra("top", t)
            it.putExtra("right", r)
            it.putExtra("bottom", b)
        }
        Log.i(TAG, "overlay rects published count=${normalized.size} rects=$normalized")
    }

    
    fun publishOverlayDrag(context: Context?, active: Boolean) {
        if (active == lastSentOverlayDrag) return
        if (active && !boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        lastSentOverlayDrag = active
        send(ctx, ACTION_OVERLAY_DRAG) { it.putExtra("active", active) }
        Log.i(TAG, "overlay drag published active=$active")
    }

    fun requestClose(context: Context?) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "close requested")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_CLOSE) }
    }

    
    fun requestPasteStrokes(context: Context?) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "paste-strokes requested")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_PASTE_STROKES) }
    }

    
    private const val INBOX_DIR = "/sdcard/EXPORT/mosaic/inbox"

    
    fun enqueueImage(imagePathSrc: String, source: String): Boolean {
        return try {
            val dir = inboxDir() ?: return false
            val id = "inkling-${System.currentTimeMillis()}-${(0..9999).random()}"
            val imagePath = java.io.File(dir, "$id.png")
            java.io.File(imagePathSrc).copyTo(imagePath, overwrite = true)
            val request = org.json.JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("imagePath", imagePath.absolutePath)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) {
                imagePath.delete()
                return false
            }
            Log.i(TAG, "image queued id=$id source=$source src=$imagePathSrc")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueImage failed: ${e.message}", e)
            false
        }
    }

    
    fun newNoteImportFile(): java.io.File? {
        val dir = inboxDir() ?: return null
        return java.io.File(dir, "inkling-note-${System.currentTimeMillis()}-${(0..9999).random()}$NOTE_PAGES_SUFFIX")
    }

    
    fun enqueueNote(pagesFile: java.io.File, title: String, source: String): Boolean {
        return try {
            val dir = pagesFile.parentFile ?: return false
            val id = pagesFile.name.removeSuffix(NOTE_PAGES_SUFFIX)
            val request = org.json.JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("type", "note")
                put("notePath", pagesFile.absolutePath)
                put("title", title)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            if (!writeRequest(dir, id, request)) return false
            Log.i(TAG, "note queued id=$id source=$source bytes=${pagesFile.length()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueNote failed: ${e.message}", e)
            false
        }
    }

    private const val NOTE_PAGES_SUFFIX = ".pages"

    private fun inboxDir(): java.io.File? {
        val dir = java.io.File(INBOX_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "inbox mkdir failed: $INBOX_DIR")
            return null
        }
        return dir
    }

    
    private fun writeRequest(dir: java.io.File, id: String, request: org.json.JSONObject): Boolean {
        val requestPath = java.io.File(dir, "$id.json")
        val tempRequestPath = java.io.File(dir, "$id.json.tmp")
        java.io.FileOutputStream(tempRequestPath).use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        if (!tempRequestPath.renameTo(requestPath)) {
            tempRequestPath.delete()
            return false
        }
        appContext?.let { ctx ->
            send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_INBOX) }
        }
        return true
    }

    
    fun requestTextCard(context: Context?, text: String, anchorScreenX: Int, anchorScreenY: Int) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "text-card requested anchor=($anchorScreenX,$anchorScreenY) len=${text.length}")
        send(ctx, ACTION_REQUEST) {
            it.putExtra("cmd", CMD_TEXT_CARD)
            it.putExtra("text", text)
            it.putExtra("anchorScreenX", anchorScreenX)
            it.putExtra("anchorScreenY", anchorScreenY)
        }
    }

    
    fun requestClearSelection(context: Context?, delete: Boolean) {
        val ctx = context?.applicationContext ?: appContext ?: return
        Log.i(TAG, "clear-selection requested delete=$delete")
        send(ctx, ACTION_REQUEST) { it.putExtra("cmd", if (delete) CMD_DELETE_SELECTION else CMD_CLEAR_SELECTION) }
    }

    private fun paletteError(error: String) = JSONObject().put("success", false).put("error", error)

    private fun finishPaletteRequest(id: String, result: JSONObject) {
        val pending = paletteRequests.remove(id) ?: return
        paletteHandler.removeCallbacks(pending.second)
        pending.first(result)
    }

    fun requestPalette(context: Context, data: JSONObject? = null, result: (JSONObject) -> Unit) {
        paletteHandler.post {
            ensureRegistered(context)
            if (!isBoardSurface()) { result(paletteError("surface_changed")); return@post }
            val id = UUID.randomUUID().toString()
            val timeout = Runnable { finishPaletteRequest(id, paletteError("timeout")) }
            paletteRequests[id] = result to timeout
            paletteHandler.postDelayed(timeout, 6000L)
            send(context.applicationContext, ACTION_REQUEST) {
                it.putExtra("cmd", if (data == null) "palette_info" else "palette_apply")
                it.putExtra("requestId", id)
                if (data != null) it.putExtra("data", data.toString())
            }
        }
    }

    fun applyPalettePreset(context: Context, penId: String, thickness: Int, result: (JSONObject) -> Unit) {
        requestPalette(context) { info ->
            if (!info.optBoolean("success")) { result(info); return@requestPalette }
            requestPalette(context, info.put("penId", penId).put("thickness", thickness), result)
        }
    }

    private inline fun send(ctx: Context, action: String, fill: (Intent) -> Unit) {
        try {
            ctx.sendBroadcast(Intent(action).apply { setPackage(ctx.packageName); fill(this) })
        } catch (e: Exception) {
            Log.e(TAG, "send $action failed: ${e.message}", e)
        }
    }
}
