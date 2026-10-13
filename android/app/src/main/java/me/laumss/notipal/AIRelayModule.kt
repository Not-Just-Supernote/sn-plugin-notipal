package me.laumss.notipal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import me.laumss.notipal.render.PageImageSource
import me.laumss.notipal.relay.AIRelayCore
import me.laumss.notipal.relay.core.AppPrefs
import java.io.File
import java.io.FileOutputStream


class AIRelayModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), AIRelayCore.Listener {
    override fun getName() = "AIRelayModule"

    private val core by lazy { AIRelayCore.get(reactApplicationContext) }
    private var listening = false
    private var signatureLogged = false

    private fun logHostResizeSignature() {
        if (signatureLogged) return
        signatureLogged = true
        try {
            val cls = Class.forName("com.ratta.supernote.pluginlib.modules.CommAPIModule")
            cls.declaredMethods.filter { it.name == "resizeLassoRect" }.forEach { method ->
                Log.i("AIRelayModule", "host resizeLassoRect(${method.parameterTypes.joinToString { it.simpleName }})")
            }
        } catch (error: Throwable) {
            Log.w("AIRelayModule", "resizeLassoRect signature probe failed: $error")
        }
    }

    @ReactMethod
    fun capturePageRegion(
        left: Int, top: Int, right: Int, bottom: Int, margin: Int, outPath: String,
        expectedFile: String, expectedPage: Int, expectedWidth: Int, expectedHeight: Int, promise: Promise,
    ) {
        PageImageSource.readCurrentContext(reactApplicationContext) { source, _ ->
            if (source == null || source.filePath != expectedFile || source.pageNum != expectedPage ||
                source.pageWidth != expectedWidth || source.pageHeight != expectedHeight || MosaicLink.isBoardSurface()) {
                promise.resolve(null)
            } else {
                captureRenderedRegion(left, top, right, bottom, margin, outPath, source, promise)
            }
        }
    }

    private fun captureRenderedRegion(
        left: Int, top: Int, right: Int, bottom: Int, margin: Int, outPath: String,
        source: PageImageSource.PageContext?, promise: Promise,
    ) {
        Thread {
            val temporary = File(reactApplicationContext.cacheDir, "formula_page_${System.nanoTime()}.png")
            var bitmap: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                require(right > left && bottom > top && margin >= 0) { "invalid selection bounds" }
                val rendered = PageImageSource.renderCurrentPageBlocking(reactApplicationContext,
                    temporary.absolutePath, source) ?: run { promise.resolve(null); return@Thread }
                val page = BitmapFactory.decodeFile(rendered) ?: run { promise.resolve(null); return@Thread }
                bitmap = page
                val x = (left - margin).coerceIn(0, page.width - 1)
                val y = (top - margin).coerceIn(0, page.height - 1)
                val width = ((right + margin).coerceAtMost(page.width) - x).coerceAtLeast(1)
                val height = ((bottom + margin).coerceAtMost(page.height) - y).coerceAtLeast(1)
                val region = Bitmap.createBitmap(page, x, y, width, height)
                cropped = region
                File(outPath).parentFile?.mkdirs()
                check(FileOutputStream(outPath).use { region.compress(Bitmap.CompressFormat.PNG, 100, it) })
                promise.resolve(outPath)
            } catch (error: Exception) {
                runCatching { File(outPath).delete() }
                Log.e("AIRelayModule", "live page region capture ended", error)
                promise.resolve(null)
            } finally {
                cropped?.recycle()
                if (bitmap !== cropped) bitmap?.recycle()
                
                runCatching { temporary.delete() }
            }
        }.apply { name = "AIRelay-live-capture"; isDaemon = true }.start()
    }

    
    @ReactMethod
    fun prepareLivePageForSelection(
        expectedFile: String, expectedPage: Int, expectedWidth: Int, expectedHeight: Int, promise: Promise,
    ) {
        PageImageSource.readCurrentContext(reactApplicationContext) { source, _ ->
            if (source == null || source.filePath != expectedFile || source.pageNum != expectedPage ||
                source.pageWidth != expectedWidth || source.pageHeight != expectedHeight || MosaicLink.isBoardSurface()) {
                promise.resolve(false)
                return@readCurrentContext
            }
            Thread {
                val temporary = File(reactApplicationContext.cacheDir, "selection_sync_${System.nanoTime()}.png")
                try {
                    val rendered = PageImageSource.renderCurrentPageBlocking(
                        reactApplicationContext, temporary.absolutePath, source, 15_000L)
                    promise.resolve(rendered != null)
                } catch (error: Exception) {
                    Log.w("AIRelayModule", "prepareLivePageForSelection ended: ${error.message}")
                    promise.resolve(false)
                } finally {
                    runCatching { temporary.delete() }
                }
            }.apply { name = "AIRelay-selection-sync"; isDaemon = true }.start()
        }
    }

    @ReactMethod
    fun startListening() {
        logHostResizeSignature()
        ensureCoreListener()
    }

    @ReactMethod
    fun startInboxListening() {
        ensureCoreListener()
        emitInbox()
    }

    private fun ensureCoreListener() {
        if (listening) return
        listening = true
        core.addListener(this)
    }

    @ReactMethod
    fun isRelayEnabled(promise: Promise) {
        promise.resolve(core.isEnabled())
    }

    @ReactMethod
    fun toggleRelayEnabled(promise: Promise) {
        ensureCoreListener()
        promise.resolve(core.toggleEnabledByUser())
    }

    @ReactMethod
    fun setRelayEnabled(enabled: Boolean, promise: Promise) {
        ensureCoreListener()
        promise.resolve(core.setEnabledByUser(enabled))
    }

    override fun onInboxChanged(messages: List<me.laumss.notipal.relay.model.RelayMessage>) {
        
        
        
        
        
        emitInbox()
    }

    override fun onInsertionRequested(request: AIRelayCore.InsertionRequest) {
        val emitter = reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        when (request) {
            is AIRelayCore.InsertionRequest.Text -> emitter.emit("onTextFromRelay", request.text)
            is AIRelayCore.InsertionRequest.Blocks -> emitter.emit(
                if (request.replace) "onReplaceFromRelay" else "onBlocksFromRelay",
                Arguments.createMap().apply {
                    putString("text", request.text)
                    putString("blocks", request.blocks)
                },
            )
        }
    }

    override fun onFormulaReplyReady(messageId: String) {
        
        
    }

    private fun emitInbox() {
        reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onInboxFromRelay", core.capsuleItemsJson())
    }

    @ReactMethod
    fun sendInsertRequest(id: String) { ensureCoreListener(); core.insertMessage(id) }

    
    
    @ReactMethod
    fun openRelayMain(@Suppress("UNUSED_PARAMETER") pkg: String, promise: Promise) = openAirRelay(null, promise)

    @ReactMethod
    fun openRelayDetail(@Suppress("UNUSED_PARAMETER") pkg: String, id: String, promise: Promise) = openAirRelay(id, promise)

    private fun openAirRelay(detailId: String?, promise: Promise) {
        val toolbar = FloatingToolbarModule.currentInstance
        if (toolbar == null) {
            promise.resolve(false)
            return
        }
        toolbar.openPanel(detailId?.let { "airRelayDetail:$it" } ?: "airRelay")
        promise.resolve(true)
    }

    @ReactMethod
    fun sendAck(text: String, success: Boolean, error: String?) {
        ensureCoreListener()
        core.ackInsertion(text, success, error)
    }

    @ReactMethod
    fun sendQuery(text: String) { ensureCoreListener(); core.submitQuery(text) }

    
    @ReactMethod
    fun sendQueryWithResult(text: String, promise: Promise) {
        ensureCoreListener()
        val result = core.submitQuery(text)
        promise.resolve(result.name.lowercase())
    }

    @ReactMethod
    fun pingRelay(promise: Promise) {
        ensureCoreListener()
        promise.resolve(if (core.isBackendReachable()) "1" else "0")
    }

    @ReactMethod
    fun sendAlive() { ensureCoreListener() }

    @ReactMethod
    fun startHeartbeat() { ensureCoreListener() }

    @ReactMethod
    fun stopHeartbeat() {}

    @ReactMethod
    fun sendFormulaSessionReset() { ensureCoreListener(); core.resetFormulaSession() }

    @ReactMethod
    fun sendImageQueryKind(imagePath: String, maskPath: String, prompt: String, kind: String) {
        ensureCoreListener()
        core.submitImageQuery(imagePath, maskPath, prompt, kind)
    }

    @ReactMethod
    fun getAutoOpenDetail(promise: Promise) {
        promise.resolve(AppPrefs.autoOpenDetail(reactApplicationContext))
    }

    @ReactMethod
    fun sendInsertPosition(page: Int, top: Int) { ensureCoreListener(); core.updateInsertPosition(page, top) }

    @ReactMethod
    fun stopListening() {
        if (!listening) return
        listening = false
        core.removeListener(this)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    override fun invalidate() {
        stopListening()
        super.invalidate()
    }
}
