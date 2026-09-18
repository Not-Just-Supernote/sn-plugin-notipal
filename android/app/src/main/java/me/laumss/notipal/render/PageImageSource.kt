package me.laumss.notipal.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SizeF
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import com.ratta.supernote.plugincommon.response.PluginAPIResponse
import me.laumss.notipal.BuildConfig
import me.laumss.notipal.FloatingToolbarModule
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


object PageImageSource {

    private const val TAG = "PageImageSource"
    private const val HOST_API_CLASS = "com.ratta.supernote.pluginlib.api.HostCommonAPI"
    private const val HOST_CALLBACK_CLASS = "com.ratta.supernote.pluginlib.callback.RequestHostCallback"
    private const val FOREGROUND_RETRY_COUNT = 3
    private const val FOREGROUND_RETRY_MS = 600L
    
    private const val HOST_OUTPUT_DIR = "/sdcard/EXPORT/inkling_capture"
    
    private const val INCLUDE_NOTE_TEMPLATE = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "Inkling-page-render").apply { isDaemon = true } }
    }
    @Volatile private var pluginAppCache: Any? = null

    
    fun renderCurrentPage(ctx: ReactApplicationContext, outPath: String, onDone: (String?, String?) -> Unit) {
        val done = AtomicBoolean(false)
        fun finish(path: String?, error: String?) {
            if (!done.compareAndSet(false, true)) return
            if (error != null) Log.w(TAG, "renderCurrentPage failed: $error")
            postMain { onDone(path, error) }
        }
        callHost(ctx, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
            val path = (pathValue as? String)?.takeIf { it.isNotBlank() }
                ?: return@callHost finish(null, pathError ?: "current file path empty")
            callHost(ctx, "getCurrentPageNum", emptyList()) { pageValue, pageError ->
                val page = (pageValue as? Number)?.toInt()
                    ?: return@callHost finish(null, pageError ?: "current page empty")
                callHost(ctx, "getPageSize", listOf(path, page)) { sizeValue, _ ->
                    val size = sizeValue as? SizeF
                    val screenW = FloatingToolbarModule.screenWidth.takeIf { it > 0 }
                        ?: ctx.resources.displayMetrics.widthPixels
                    val screenH = FloatingToolbarModule.screenHeight.takeIf { it > 0 }
                        ?: ctx.resources.displayMetrics.heightPixels
                    val pageW = size?.width?.toInt()?.takeIf { it > 0 } ?: screenW
                    val pageH = size?.height?.toInt()?.takeIf { it > 0 } ?: screenH
                    if (isNotePath(path)) {
                        
                        renderNote(ctx, path, page, pageW, pageH, outPath, ::finish)
                    } else {
                        renderDoc(ctx, path, page, pageW, pageH, screenW, screenH, outPath, ::finish)
                    }
                }
            }
        }
    }

    

    private fun renderNote(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        pageW: Int,
        pageH: Int,
        outPath: String,
        finish: (String?, String?) -> Unit,
    ) {
        val stem = "note_${System.currentTimeMillis()}"
        val templatePath = if (INCLUDE_NOTE_TEMPLATE) hostOutputFile("${stem}_tpl.png") else null
        fun withTemplate(readyTemplate: String) {
            loadElements(ctx, path, page) { elements, error, release ->
                if (elements == null) {
                    release()
                    runCatching { File(readyTemplate).delete() }
                    finish(null, error ?: "elements empty")
                    return@loadElements
                }
                withLayerPreview(ctx, path, page, stem, elements) { previewPath ->
                    renderExecutor.execute {
                        val result = runCatching {
                            val background = readyTemplate.takeIf { it.isNotEmpty() }?.let(::decodeFile)
                            try {
                                writePng(
                                    VectorPageRenderer.render(
                                        elements, pageW, pageH, pageW, pageH,
                                        background = background,
                                        layerPreviewPath = previewPath,
                                    ),
                                    outPath,
                                )
                            } finally {
                                background?.recycle()
                            }
                        }
                        release()
                        runCatching { if (readyTemplate.isNotEmpty()) File(readyTemplate).delete() }
                        runCatching { if (previewPath.isNotEmpty()) File(previewPath).delete() }
                        result.fold(
                            onSuccess = {
                                if (BuildConfig.ENABLE_DEBUG) {
                                    Log.i(TAG, "note page rendered page=$page elements=${elements.size} " +
                                        "size=${pageW}x$pageH → $outPath")
                                }
                                finish(outPath, null)
                            },
                            onFailure = { finish(null, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }
            }
        }
        if (templatePath == null) {
            withTemplate("")
        } else {
            callHost(ctx, "generateNoteTemplatePng", listOf(path, page, templatePath)) { value, _ ->
                withTemplate(if (value == true && File(templatePath).isFile) templatePath else "")
            }
        }
    }

    

    private fun renderDoc(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        pageW: Int,
        pageH: Int,
        screenW: Int,
        screenH: Int,
        outPath: String,
        finish: (String?, String?) -> Unit,
    ) {
        val stem = "doc_${System.currentTimeMillis()}"
        val basePath = hostOutputFile("${stem}_base.png")
        runCatching { File(basePath).delete() }
        
        
        callHost(
            ctx, "generateCurrentDocImage",
            listOf(page, basePath, SizeF(screenW.toFloat(), screenH.toFloat()), 1),
        ) { value, error ->
            val baseOk = value == true && File(basePath).isFile && File(basePath).length() > 0L
            if (!baseOk) {
                runCatching { File(basePath).delete() }
                finish(null, error ?: "doc base image failed (result=$value)")
                return@callHost
            }
            
            loadElements(ctx, path, page, emptyIsOk = true) { elements, _, release ->
                val list = elements.orEmpty()
                withLayerPreview(ctx, path, page, stem, list) { previewPath ->
                    renderExecutor.execute {
                        val result = runCatching {
                            val background = decodeFile(basePath)
                                ?: throw IllegalStateException("doc base decode failed")
                            try {
                                writePng(
                                    VectorPageRenderer.render(
                                        list, pageW, pageH, screenW, screenH,
                                        background = background,
                                        layerPreviewPath = previewPath,
                                    ),
                                    outPath,
                                )
                            } finally {
                                background.recycle()
                            }
                        }
                        release()
                        runCatching { File(basePath).delete() }
                        runCatching { if (previewPath.isNotEmpty()) File(previewPath).delete() }
                        result.fold(
                            onSuccess = {
                                if (BuildConfig.ENABLE_DEBUG) {
                                    Log.i(TAG, "doc page rendered page=$page elements=${list.size} " +
                                        "page=${pageW}x$pageH target=${screenW}x$screenH → $outPath")
                                }
                                finish(outPath, null)
                            },
                            onFailure = { finish(null, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }
            }
        }
    }

    
    private fun withLayerPreview(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        stem: String,
        elements: List<Element>,
        onReady: (String) -> Unit,
    ) {
        val needsPreview = elements.any {
            it.type == Element.TRAIL_TYPE_PICTURE &&
                it.picture?.picturePath?.let { p -> p.isEmpty() || !File(p).exists() } != false
        }
        if (!needsPreview) return onReady("")
        val previewPath = hostOutputFile("${stem}_layer.png")
        callHost(ctx, "generateLayerPreviewImage", listOf(path, page, 0, previewPath)) { v0, _ ->
            if (v0 == true && File(previewPath).isFile) return@callHost onReady(previewPath)
            callHost(ctx, "generateLayerPreviewImage", listOf(path, page, -1, previewPath)) { v1, e1 ->
                if (v1 == true && File(previewPath).isFile) {
                    onReady(previewPath)
                } else {
                    Log.w(TAG, "layer preview unavailable: $e1, pictures skipped")
                    onReady("")
                }
            }
        }
    }

    

    
    private fun loadElements(
        ctx: ReactApplicationContext,
        path: String,
        page: Int,
        emptyIsOk: Boolean = false,
        attempt: Int = 0,
        onLoaded: (List<Element>?, String?, () -> Unit) -> Unit,
    ) {
        var loaded: List<Element>? = null
        val released = AtomicBoolean(false)
        val release: () -> Unit = {
            if (released.compareAndSet(false, true)) releaseElements(ctx, loaded.orEmpty())
        }
        fun deliver(elements: List<Element>?, error: String?) {
            loaded = elements
            postMain { onLoaded(elements, error, release) }
        }
        fun retryOrFail(message: String) {
            if (isForegroundDenied(message) && attempt + 1 < FOREGROUND_RETRY_COUNT) {
                mainHandler.postDelayed({
                    loadElements(ctx, path, page, emptyIsOk, attempt + 1, onLoaded)
                }, FOREGROUND_RETRY_MS)
            } else {
                deliver(null, message)
            }
        }
        runNative(ctx) {
            try {
                val pluginApp = getPluginApp(ctx)
                val checkMethod = pluginApp.javaClass.methods.firstOrNull {
                    it.name == "checkTrailCache" && it.parameterCount == 0
                }
                if (checkMethod?.invoke(pluginApp) == false) {
                    deliver(null, "host trail cache busy")
                    return@runNative
                }
                val hostClass = Class.forName(HOST_API_CLASS)
                val host = hostClass.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("HostCommonAPI instance is empty")
                val callbackClass = Class.forName(HOST_CALLBACK_CLASS)
                val hostMethod = hostClass.methods.firstOrNull { m ->
                    m.name == "getElements" &&
                        m.parameterCount == 4 &&
                        compatible(m.parameterTypes[0], pluginApp) &&
                        compatible(m.parameterTypes[1], page) &&
                        compatible(m.parameterTypes[2], path) &&
                        m.parameterTypes[3].isAssignableFrom(callbackClass)
                } ?: throw NoSuchMethodException("HostCommonAPI.getElements/3")
                val readMethod = pluginApp.javaClass.methods.firstOrNull {
                    it.name == "readElementFromFile" && it.parameterCount == 1
                } ?: throw NoSuchMethodException("PluginApp.readElementFromFile")

                val proxy = Proxy.newProxyInstance(
                    callbackClass.classLoader, arrayOf(callbackClass),
                ) { proxyValue, method, values ->
                    when (method.name) {
                        "onResponse" -> {
                            val response = values?.firstOrNull() as? PluginAPIResponse
                            if (response == null) {
                                deliver(null, "host callback empty")
                            } else if (!response.isSuccess) {
                                retryOrFail(response.error?.message ?: response.toString())
                            } else {
                                val tempPath = response.result as? String
                                if (tempPath.isNullOrEmpty()) {
                                    if (emptyIsOk) deliver(emptyList(), null)
                                    else deliver(null, "element path empty")
                                } else {
                                    try {
                                        
                                        @Suppress("UNCHECKED_CAST")
                                        val elements = readMethod.invoke(pluginApp, tempPath) as? List<Element>
                                        if (elements == null) deliver(null, "elements deserialize empty")
                                        else deliver(elements, null)
                                    } catch (e: Throwable) {
                                        deliver(null, messageOf(e))
                                    }
                                }
                            }
                            null
                        }
                        "toString" -> "InklingElementCallback(getElements)"
                        "hashCode" -> System.identityHashCode(proxyValue)
                        "equals" -> proxyValue === values?.firstOrNull()
                        else -> null
                    }
                }
                hostMethod.invoke(host, pluginApp, page, path, proxy)
            } catch (error: Throwable) {
                retryOrFail(messageOf(error))
            }
        }
    }

    private fun releaseElements(ctx: ReactApplicationContext, elements: List<Element>) {
        if (elements.isEmpty()) return
        val uuids = elements.mapNotNull { it.uuid }
        elements.forEach { runCatching { it.recycle() } }
        try {
            ctx.runOnNativeModulesQueueThread {
                try {
                    val pluginApp = pluginAppCache ?: getPluginApp(ctx)
                    val method = pluginApp.javaClass.methods.firstOrNull {
                        it.name == "removeTrail" && it.parameterCount == 1
                    }
                    if (method != null) uuids.forEach { uuid -> method.invoke(pluginApp, uuid) }
                } catch (error: Throwable) {
                    Log.w(TAG, "releaseElements: ${messageOf(error)}")
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "releaseElements queue: ${messageOf(error)}")
        }
    }

    

    
    private fun callHost(
        ctx: ReactApplicationContext,
        methodName: String,
        args: List<Any?>,
        attempt: Int = 0,
        callback: (Any?, String?) -> Unit,
    ) {
        fun retryOrFail(message: String) {
            if (isForegroundDenied(message) && attempt + 1 < FOREGROUND_RETRY_COUNT) {
                mainHandler.postDelayed({ callHost(ctx, methodName, args, attempt + 1, callback) }, FOREGROUND_RETRY_MS)
            } else {
                postMain { callback(null, message) }
            }
        }
        runNative(ctx) {
            try {
                val pluginApp = getPluginApp(ctx)
                val hostClass = Class.forName(HOST_API_CLASS)
                val host = hostClass.getMethod("getInstance").invoke(null)
                    ?: throw IllegalStateException("HostCommonAPI instance is empty")
                val callbackClass = Class.forName(HOST_CALLBACK_CLASS)
                val hostMethod = hostClass.methods.firstOrNull { method ->
                    method.name == methodName &&
                        method.parameterCount == args.size + 2 &&
                        compatible(method.parameterTypes.first(), pluginApp) &&
                        args.indices.all { index -> compatible(method.parameterTypes[index + 1], args[index]) } &&
                        method.parameterTypes.last().isAssignableFrom(callbackClass)
                } ?: throw NoSuchMethodException("HostCommonAPI.$methodName/${args.size}")

                val proxy = Proxy.newProxyInstance(
                    callbackClass.classLoader, arrayOf(callbackClass),
                ) { proxyValue, method, values ->
                    when (method.name) {
                        "onResponse" -> {
                            val response = values?.firstOrNull() as? PluginAPIResponse
                            when {
                                response == null -> postMain { callback(null, "host callback empty") }
                                response.isSuccess -> postMain { callback(response.result, null) }
                                else -> retryOrFail(response.error?.message ?: response.toString())
                            }
                            null
                        }
                        "toString" -> "InklingHostCallback($methodName)"
                        "hashCode" -> System.identityHashCode(proxyValue)
                        "equals" -> proxyValue === values?.firstOrNull()
                        else -> null
                    }
                }
                hostMethod.invoke(host, *(listOf(pluginApp) + args + proxy).toTypedArray())
            } catch (error: Throwable) {
                retryOrFail(messageOf(error))
            }
        }
    }

    private fun getPluginApp(ctx: ReactApplicationContext): Any {
        pluginAppCache?.let { return it }
        val nativeApi = ctx.catalystInstance.getNativeModule("NativePluginAPI")
            ?: throw IllegalStateException("NativePluginAPI module is empty")
        var owner: Class<*>? = nativeApi.javaClass
        while (owner != null) {
            val field = owner.declaredFields.firstOrNull { it.name == "mPluginApp" }
            if (field != null) {
                field.isAccessible = true
                val pluginApp = field.get(nativeApi)
                    ?: throw IllegalStateException("PluginAppAPI instance is empty")
                pluginAppCache = pluginApp
                return pluginApp
            }
            owner = owner.superclass
        }
        throw NoSuchFieldException("NativePluginAPI.mPluginApp")
    }

    private fun runNative(ctx: ReactApplicationContext, block: () -> Unit) {
        ctx.runOnNativeModulesQueueThread(block)
    }

    private fun postMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun compatible(type: Class<*>, value: Any?): Boolean {
        if (value == null) return !type.isPrimitive
        val boxed = when (type) {
            java.lang.Integer.TYPE -> Integer::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            else -> type
        }
        return boxed.isInstance(value)
    }

    private fun isForegroundDenied(message: String?): Boolean =
        message?.contains("not allowed to use this API", ignoreCase = true) == true

    private fun messageOf(error: Throwable): String {
        val actual = if (error is InvocationTargetException && error.targetException != null) error.targetException else error
        return actual.message ?: actual.javaClass.simpleName
    }

    private fun isNotePath(path: String): Boolean =
        path.endsWith(".note", ignoreCase = true) || path.endsWith(".mark", ignoreCase = true)

    private fun hostOutputFile(name: String): String {
        val dir = File(HOST_OUTPUT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name).absolutePath
    }

    private fun decodeFile(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    } catch (_: Throwable) { null }

    private fun writePng(bitmap: Bitmap, outPath: String) {
        try {
            File(outPath).parentFile?.mkdirs()
            val ok = FileOutputStream(outPath).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(ok && File(outPath).length() > 0L) { "PNG encode failed" }
        } finally {
            bitmap.recycle()
        }
    }
}
