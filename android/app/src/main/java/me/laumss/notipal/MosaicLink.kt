package me.laumss.notipal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.util.Log

internal object MosaicLink {

    private const val TAG = "MosaicLink"

    const val ACTION_BOARD_STATE = "me.laumss.mosaic.BOARD_STATE"
    const val ACTION_TOOLBAR_RECT = "me.laumss.mosaic.INKLING_TOOLBAR_RECT"
    
    const val ACTION_OVERLAY_RECTS = "me.laumss.mosaic.INKLING_OVERLAY_RECTS"
    const val ACTION_REQUEST = "me.laumss.mosaic.INKLING_REQUEST"

    const val CMD_CLOSE = "close"
    const val CMD_SYNC = "sync"
    const val CMD_PASTE_STROKES = "paste_strokes"
    
    const val CMD_CLEAR_SELECTION = "clear_selection"
    
    const val CMD_DELETE_SELECTION = "delete_selection"
    
    const val CMD_TEXT_CARD = "text_card"
    
    const val CMD_INBOX = "inbox"

    @Volatile private var boardVisible = false
    @Volatile private var appContext: Context? = null
    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var lastSentRect: Rect? = null
    @Volatile private var lastSentOverlayRects: List<Rect> = emptyList()

    fun isBoardVisible(): Boolean = boardVisible

    fun ensureRegistered(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_BOARD_STATE) return
                onBoardState(
                    visible = intent.getBooleanExtra("visible", false),
                    reason = intent.getStringExtra("reason") ?: "",
                )
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, IntentFilter(ACTION_BOARD_STATE), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(r, IntentFilter(ACTION_BOARD_STATE))
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

    private fun onBoardState(visible: Boolean, reason: String) {
        if (boardVisible == visible) return
        boardVisible = visible
        if (!visible) {
            lastSentRect = null
            lastSentOverlayRects = emptyList()
        }
        Log.i(TAG, "board state visible=$visible reason=$reason")
        FloatingToolbarModule.onMosaicVisibilityChanged(visible)
    }

    fun publishToolbarRect(context: Context?, rect: Rect?) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        if (rect == lastSentRect) return
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

    
    fun publishOverlayRects(context: Context?, rects: List<Rect>) {
        if (!boardVisible) return
        val ctx = context?.applicationContext ?: appContext ?: return
        val normalized = rects.map(::Rect)
        if (normalized == lastSentOverlayRects) return
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
            val dir = java.io.File(INBOX_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "inbox mkdir failed: $INBOX_DIR")
                return false
            }
            val id = "inkling-${System.currentTimeMillis()}-${(0..9999).random()}"
            val imagePath = java.io.File(dir, "$id.png")
            val requestPath = java.io.File(dir, "$id.json")
            val tempRequestPath = java.io.File(dir, "$id.json.tmp")
            java.io.File(imagePathSrc).copyTo(imagePath, overwrite = true)
            val request = org.json.JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("imagePath", imagePath.absolutePath)
                put("createdAt", System.currentTimeMillis())
                put("source", source)
            }
            java.io.FileOutputStream(tempRequestPath).use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            if (!tempRequestPath.renameTo(requestPath)) {
                tempRequestPath.delete()
                imagePath.delete()
                return false
            }
            Log.i(TAG, "image queued id=$id source=$source src=$imagePathSrc")
            appContext?.let { ctx ->
                send(ctx, ACTION_REQUEST) { it.putExtra("cmd", CMD_INBOX) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "enqueueImage failed: ${e.message}", e)
            false
        }
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

    private inline fun send(ctx: Context, action: String, fill: (Intent) -> Unit) {
        try {
            ctx.sendBroadcast(Intent(action).apply { setPackage(ctx.packageName); fill(this) })
        } catch (e: Exception) {
            Log.e(TAG, "send $action failed: ${e.message}", e)
        }
    }
}
