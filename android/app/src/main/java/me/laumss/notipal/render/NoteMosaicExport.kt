package me.laumss.notipal.render

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import android.util.SizeF
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.plugincommon.data.common.trail.Element
import java.io.File
import java.util.concurrent.Executors


object NoteMosaicExport {

    private const val TAG = "NoteMosaicExport"
    private const val BUSY_RETRY_COUNT = 10
    private const val BUSY_RETRY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Notipal-note-export").apply { isDaemon = true }
    }

    class Exported(val notePath: String, val pageCount: Int, val strokeCount: Int)

    
    fun exportCurrentNote(
        ctx: ReactApplicationContext,
        outFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Exported?, String?) -> Unit,
    ) {
        PageImageSource.callHost(ctx, "getCurrentFilePath", emptyList()) { pathValue, pathError ->
            val path = (pathValue as? String)?.takeIf { it.endsWith(".note", ignoreCase = true) }
            if (path == null) {
                onDone(null, pathError ?: "current file is not a note: $pathValue")
                return@callHost
            }
            PageImageSource.callHost(ctx, "getNoteTotalPageNum", listOf(path)) { totalValue, totalError ->
                val total = (totalValue as? Number)?.toInt()?.takeIf { it > 0 }
                if (total == null) {
                    onDone(null, totalError ?: "note page count empty")
                    return@callHost
                }
                Export(ctx, path, total, outFile, onProgress, onDone).start()
            }
        }
    }

    private class Export(
        val ctx: ReactApplicationContext,
        val path: String,
        val total: Int,
        val outFile: File,
        val onProgress: (Int, Int) -> Unit,
        val onDone: (Exported?, String?) -> Unit,
    ) {
        private val started = SystemClock.elapsedRealtime()
        private var out: JsonWriter? = null
        private var strokeCount = 0
        private var finished = false

        fun start() {
            worker.execute {
                val error = runCatching {
                    outFile.parentFile?.mkdirs()
                    out = JsonWriter(outFile.bufferedWriter()).apply {
                        beginObject().name("kind").value("note")
                        name("version").value(2L)
                        name("coordinateSpace").value("note-page-px")
                        name("source").value(path)
                        name("pages").beginArray()
                    }
                }.exceptionOrNull()
                mainHandler.post { if (error != null) fail(messageOf(error)) else step(0, 0) }
            }
        }

        private fun step(page: Int, attempt: Int) {
            if (finished) return
            if (page >= total) {
                complete()
                return
            }
            if (attempt == 0 && (page == 0 || page % 5 == 0 || page == total - 1)) onProgress(page + 1, total)
            PageImageSource.callHost(ctx, "getPageSize", listOf(path, page)) { sizeValue, sizeError ->
                val size = sizeValue as? SizeF
                if (size == null || !(size.width > 0f) || !(size.height > 0f)) {
                    fail(sizeError ?: "page $page size unavailable")
                    return@callHost
                }
                PageImageSource.loadPageElements(ctx, path, page) { elements, error, release ->
                    if (elements == null) {
                        release()
                        if (error == PageImageSource.TRAIL_CACHE_BUSY && attempt + 1 < BUSY_RETRY_COUNT) {
                            mainHandler.postDelayed({ step(page, attempt + 1) }, BUSY_RETRY_MS)
                        } else {
                            fail(error ?: "page $page elements unavailable")
                        }
                        return@loadPageElements
                    }
                    worker.execute {
                        val writeError = try {
                            writePage(size, elements)
                            null
                        } catch (e: Throwable) {
                            e
                        } finally {
                            release()
                        }
                        mainHandler.post {
                            if (writeError != null) fail(messageOf(writeError)) else step(page + 1, 0)
                        }
                    }
                }
            }
        }

        private fun writePage(size: SizeF, elements: List<Element>) {
            val writer = out ?: error("export writer closed")
            writer.beginObject()
            writer.name("pageSize").beginObject()
            writer.name("width").value(size.width.toInt().toLong())
            writer.name("height").value(size.height.toInt().toLong()).endObject()
            writer.name("strokes").beginArray()
            var pageStrokes = 0
            var contourStrokes = 0
            val skipped = linkedMapOf<String, Int>()
            for (el in elements) {
                val reason = ClipboardElementIo.writeStroke(writer, el, includeContours = true)
                if (reason == null) {
                    strokeCount++; pageStrokes++
                    if (el.contoursSrc?.any { it.isNotEmpty() } == true) contourStrokes++
                } else {
                    skipped[reason] = (skipped[reason] ?: 0) + 1
                    check(el.type != Element.TRAIL_TYPE_STROKE && el.type != 700) { "Handwriting export incomplete: $reason trail=${el.trailNumInPage}" }
                }
            }
            writer.endArray().name("strokeCount").value(pageStrokes.toLong()).endObject()
            Log.i(TAG, "[NOTE-MOSAIC] pageSize=$size strokes=$pageStrokes contours=$contourStrokes skipped=$skipped")
        }

        private fun complete() {
            worker.execute {
                val error = runCatching {
                    out?.apply { endArray().endObject().close() }
                    out = null
                }.exceptionOrNull()
                mainHandler.post {
                    if (error != null) {
                        fail(messageOf(error))
                        return@post
                    }
                    finished = true
                    Log.i(TAG, "exported note=$path pages=$total strokes=$strokeCount " +
                        "bytes=${outFile.length()} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    onDone(Exported(path, total, strokeCount), null)
                }
            }
        }

        private fun fail(message: String) {
            if (finished) return
            finished = true
            Log.w(TAG, "export failed note=$path: $message")
            worker.execute {
                runCatching { out?.close() }
                out = null
                runCatching { outFile.delete() }
                mainHandler.post { onDone(null, message) }
            }
        }

        private fun messageOf(error: Throwable): String = error.message ?: error.javaClass.simpleName
    }
}
