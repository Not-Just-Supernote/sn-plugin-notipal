package me.laumss.notipal.overlays
import me.laumss.notipal.BuildConfig

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import me.laumss.notipal.FloatingToolbarModule
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.bubbles.AiBubbleModule
import me.laumss.notipal.bubbles.FloatingBubbleModule
import me.laumss.notipal.panels.CropPanel
import me.laumss.notipal.panels.DocScreenshotService
import me.laumss.notipal.panels.SendPanel
import me.laumss.notipal.panels.StickyNotes
import me.laumss.notipal.ui_common.Dialog
import me.laumss.notipal.relay.AIRelayCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread


object RegionCaptureFlow {

    
    private const val TAG = "RegionCaptureFlow"
    private const val STAGE_DIR = "/sdcard/EXPORT/lasso_ai"
    
    private const val MOSAIC_INBOX_DIR = "/sdcard/EXPORT/mosaic/inbox"
    private const val REFRESH_WAIT_MS = 500L

    private val handler = Handler(Looper.getMainLooper())

    fun capture(
        ctx: ReactApplicationContext,
        toolbar: FloatingToolbarModule,
        mode: String,
        fromBubble: Boolean,
        left: Int, top: Int, right: Int, bottom: Int,
    ) {
        thread(isDaemon = false) {
            
            try { Thread.sleep(REFRESH_WAIT_MS) } catch (_: InterruptedException) {}
            val srcPath = runScreencap(ctx) ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "screencap failed")
                emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                return@thread
            }
            when (mode) {
                "send" -> {
                    val cropped = cropRegion(ctx, srcPath, left, top, right, bottom)
                    handler.post {
                        if (cropped != null) {
                            val sendPanel = SendPanel.getInstance(ctx, toolbar)
                            sendPanel.show(fromBubble)
                            sendPanel.updateLassoData("", listOf(cropped))
                        } else {
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                        }
                    }
                }
                "docScreenshot" -> {
                    val dims = decodeDims(srcPath)
                    val bmpW = dims?.first ?: 0
                    val bmpH = dims?.second ?: 0
                    val x = left.coerceIn(0, (bmpW - 1).coerceAtLeast(0))
                    val y = top.coerceIn(0, (bmpH - 1).coerceAtLeast(0))
                    val w = (right - left).coerceAtLeast(1).coerceAtMost((bmpW - x).coerceAtLeast(1))
                    val h = (bottom - top).coerceAtLeast(1).coerceAtMost((bmpH - y).coerceAtLeast(1))
                    val saved = if (bmpW > 0 && bmpH > 0) {
                        DocScreenshotService.saveToHistory(
                            srcPath,
                            CropPanel.CropResult(offsetX = x, offsetY = y, width = w, height = h)
                        )
                    } else null
                    handler.post {
                        Dialog.tip(ctx, NativeLocale.t(
                            if (saved != null) "added_to_doc_screenshots" else "screenshot_save_failed"))
                        emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                    }
                }
                "sticky" -> {
                    val cropped = cropRegion(ctx, srcPath, left, top, right, bottom)
                    handler.post {
                        if (cropped == null) {
                            Dialog.tip(ctx, NativeLocale.t("screenshot_save_failed"))
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                            return@post
                        }
                        StickyNotes.add(ctx, toolbar, cropped) { ok ->
                            if (!ok) {
                                Dialog.tip(ctx, NativeLocale.t("sticky_limit_reached"))
                            }
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                        }
                    }
                }
                "mosaic" -> {
                    val cropped = cropRegion(ctx, srcPath, left, top, right, bottom)
                    val queued = cropped?.let { enqueueMosaicCard(it) } == true
                    handler.post {
                        if (cropped == null || !queued) {
                            Dialog.tip(ctx, NativeLocale.t("mosaic_insert_failed"))
                            emitCloseAndRestore(toolbar, ctx, fromBubble, null)
                            return@post
                        }
                        
                        
                        emitCloseAndRestore(
                            toolbar, ctx, fromBubble, null, restoreUi = false)
                        toolbar.openMosaicPluginForPending()
                    }
                }
                else -> { 
                    val bbox = stageAndBroadcast(ctx, srcPath, left, top, right, bottom)
                    handler.post { emitCloseAndRestore(toolbar, ctx, fromBubble, bbox) }
                }
            }
        }
    }

    private fun runScreencap(ctx: ReactApplicationContext): String? {
        return try {
            val ts = System.currentTimeMillis()
            val outPath = "${ctx.cacheDir.absolutePath}/region_screenshot_$ts.png"
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
            val exit = proc.waitFor()
            val f = File(outPath)
            if (exit == 0 && f.exists() && f.length() > 500) outPath else null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "screencap EX: ${e.message}", e)
            null
        }
    }

    
    private fun enqueueMosaicCard(croppedPath: String): Boolean {
        return try {
            val dir = File(MOSAIC_INBOX_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic inbox mkdir failed: $MOSAIC_INBOX_DIR")
                return false
            }
            val id = "inkling-${System.currentTimeMillis()}-${(0..9999).random()}"
            val imagePath = File(dir, "$id.png")
            val requestPath = File(dir, "$id.json")
            val tempRequestPath = File(dir, "$id.json.tmp")
            File(croppedPath).copyTo(imagePath, overwrite = true)
            val request = JSONObject().apply {
                put("version", 1)
                put("id", id)
                put("imagePath", imagePath.absolutePath)
                put("createdAt", System.currentTimeMillis())
                put("source", "inkling-smart-lasso")
            }
            FileOutputStream(tempRequestPath).use {
                it.write(request.toString().toByteArray(Charsets.UTF_8))
            }
            if (!tempRequestPath.renameTo(requestPath)) {
                tempRequestPath.delete()
                imagePath.delete()
                return false
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "Mosaic card queued id=$id path=${imagePath.absolutePath}")
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "enqueueMosaicCard failed: ${e.message}", e)
            false
        }
    }

    private fun decodeDims(path: String): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (_: Exception) { null }
    }

    private fun cropRegion(
        ctx: ReactApplicationContext, srcPath: String,
        left: Int, top: Int, right: Int, bottom: Int,
    ): String? {
        return try {
            val srcBmp = BitmapFactory.decodeFile(srcPath) ?: return null
            val x = left.coerceIn(0, srcBmp.width - 1)
            val y = top.coerceIn(0, srcBmp.height - 1)
            val w = (right - left).coerceAtLeast(1).coerceAtMost(srcBmp.width - x)
            val h = (bottom - top).coerceAtLeast(1).coerceAtMost(srcBmp.height - y)
            val croppedBmp = Bitmap.createBitmap(srcBmp, x, y, w, h)
            srcBmp.recycle()
            val ts = System.currentTimeMillis()
            val outPath = "${ctx.cacheDir.absolutePath}/region_crop_$ts.png"
            FileOutputStream(outPath).use { out ->
                croppedBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            croppedBmp.recycle()
            outPath
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "cropRegion failed: ${e.message}", e)
            null
        }
    }

    
    private fun stageAndBroadcast(
        ctx: ReactApplicationContext, srcPath: String,
        left: Int, top: Int, right: Int, bottom: Int,
    ): IntArray? {
        val core = AIRelayCore.get(ctx)
        if (!core.isEnabled()) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "stageAndBroadcast ignored: AIRelay disabled")
            return null
        }
        return try {
            val dir = File(STAGE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val dims = decodeDims(srcPath) ?: return null
            val ts = System.currentTimeMillis()
            val destImg = "$STAGE_DIR/$ts.png"
            val destMask = "$STAGE_DIR/$ts.mask.json"

            File(srcPath).copyTo(File(destImg), overwrite = true)

            val polyArr = JSONArray()
            for (p in listOf(left to top, right to top, right to bottom, left to bottom)) {
                polyArr.put(JSONObject().apply { put("x", p.first); put("y", p.second) })
            }
            val boxObj = JSONObject().apply {
                put("x", left); put("y", top)
                put("w", right - left); put("h", bottom - top)
            }
            val mask = JSONObject().apply {
                put("imageWidth", dims.first); put("imageHeight", dims.second)
                put("boundingBox", boxObj); put("polygon", polyArr); put("ts", ts)
            }
            FileOutputStream(destMask).use { it.write(mask.toString().toByteArray(Charsets.UTF_8)) }

            core.start()
            core.submitImageQuery(destImg, destMask, "")
            intArrayOf(left, top, right, bottom)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "stageAndBroadcast failed: ${e.message}", e)
            null
        }
    }

    
    fun emitCloseAndRestore(
        toolbar: FloatingToolbarModule,
        ctx: ReactApplicationContext,
        fromBubble: Boolean,
        bbox: IntArray?,
        restoreUi: Boolean = true,
    ) {
        try {
            toolbar.emitEvent("onNativePanelClose",
                Arguments.createMap().apply {
                    putString("panel", "lassoScreenshot")
                    putBoolean("cameFromBubble", fromBubble)
                    if (bbox != null) {
                        putMap("screenshotBbox", Arguments.createMap().apply {
                            putInt("left", bbox[0])
                            putInt("top", bbox[1])
                            putInt("right", bbox[2])
                            putInt("bottom", bbox[3])
                        })
                    }
                })
        } catch (_: Exception) {}
        handler.postDelayed({
            if (!restoreUi) return@postDelayed
            
            
            
            toolbar.restoreToolbar()
            if (fromBubble) {
                FloatingBubbleModule.reshowLast(ctx)
                AiBubbleModule.reshowLast(ctx)
            }
        }, 350)
    }
}
