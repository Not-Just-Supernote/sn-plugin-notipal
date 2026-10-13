package me.laumss.notipal.render

import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.ratta.supernote.plugincommon.data.common.trail.Element
import com.ratta.supernote.plugincommon.data.common.trail.Stroke
import me.laumss.notipal.PenSizeSpec
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt


object ClipboardElementIo {
    private const val TAG = "ClipboardElementIo"
    
    private const val NOTE_PRESSURE_MAX = 4095
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Notipal-clipboard-io").apply { isDaemon = true }
    }

    private fun rectMap(left: Int, top: Int, right: Int, bottom: Int): WritableMap =
        Arguments.createMap().apply {
            putInt("left", left); putInt("top", top)
            putInt("right", right); putInt("bottom", bottom)
        }

    fun exportSidecar(
        ctx: ReactApplicationContext, path: String, uuids: ReadableArray,
        pageWidth: Int, pageHeight: Int, sourceRect: ReadableMap?, targetRect: ReadableMap?,
        promise: Promise,
    ) {
        val started = SystemClock.elapsedRealtime()
        
        
        try {
            val plugin = PageImageSource.getPluginApp(ctx)
            val getTrail = plugin.javaClass.getMethod("getTrail", String::class.java)
            val elements = (0 until uuids.size()).map { index ->
                getTrail.invoke(plugin, uuids.getString(index)) as? Element
                    ?: error("clipboard element cache missing at $index")
            }
            worker.execute {
                try {
                    var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
                    var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
                    var count = 0; var pointCount = 0
                    val skipped = linkedMapOf<String, Int>()
                    val skippedNums = ArrayList<Int>()
                    fun skip(reason: String) {
                        skipped[reason] = (skipped[reason] ?: 0) + 1
                    }
                    val penCounts = linkedMapOf<Int, Int>()
                    val rawPenCounts = linkedMapOf<Int, Int>()
                    val markerColors = linkedMapOf<Int, Int>()
                    var minThickness = Int.MAX_VALUE
                    var maxThickness = Int.MIN_VALUE
                    var minWidth = Double.POSITIVE_INFINITY
                    var maxWidth = Double.NEGATIVE_INFINITY
                    val file = File("$path.mosaic.json")
                    file.parentFile?.mkdirs()
                    JsonWriter(file.bufferedWriter()).use { out ->
                        out.beginObject().name("v").value(3L)
                        out.name("coordinateSpace").value("note-page-px")
                        out.name("pageSize").beginObject()
                        out.name("width").value(pageWidth.toLong())
                        out.name("height").value(pageHeight.toLong()).endObject()
                        out.name("strokes").beginArray()
                        for (el in elements) {
                            val skipReason = writeStroke(
                                out, el,
                                { transformCoordinate(it, sourceRect, targetRect, "left", pageWidth) },
                                { transformCoordinate(it, sourceRect, targetRect, "top", pageHeight) },
                            ) { x, y ->
                                left = minOf(left, x); top = minOf(top, y)
                                right = maxOf(right, x); bottom = maxOf(bottom, y)
                                pointCount++
                            }
                            if (skipReason != null) {
                                skip(skipReason)
                                if ((skipReason.startsWith("shortPoints") || skipReason.startsWith("penType")) &&
                                    el.trailNumInPage > 0) {
                                    skippedNums.add(el.trailNumInPage)
                                }
                                continue
                            }
                            val stroke = el.stroke
                            rawPenCounts[stroke.penType] = (rawPenCounts[stroke.penType] ?: 0) + 1
                            val sdkPenType = stroke.penType
                            val rawThickness = el.thickness.coerceAtLeast(1)
                            minThickness = minOf(minThickness, rawThickness)
                            maxThickness = maxOf(maxThickness, rawThickness)
                            minWidth = minOf(minWidth, rawThickness / 100.0)
                            maxWidth = maxOf(maxWidth, rawThickness / 100.0)
                            penCounts[sdkPenType] = (penCounts[sdkPenType] ?: 0) + 1
                            if (sdkPenType == 11) {
                                
                                markerColors[stroke.penColor] = (markerColors[stroke.penColor] ?: 0) + 1
                            }
                            count++
                        }
                        out.endArray().endObject()
                    }
                    check(count > 0) { "clipboard has no stroke points skipped=$skipped skippedNums=${skippedNums.take(100)}" }
                    val rect = rectMap((left - 32).coerceAtLeast(0), (top - 32).coerceAtLeast(0),
                        (right + 32).coerceAtMost(pageWidth), (bottom + 32).coerceAtMost(pageHeight))
                    
                    val metadata = JSONObject().put("v", 1)
                        .put("sourcePageSize", JSONObject().put("width", pageWidth).put("height", pageHeight))
                        .put("sourceRect", JSONObject(rect.toHashMap()))
                    File("$path.meta.json").writeText(metadata.toString())
                    val elapsed = SystemClock.elapsedRealtime() - started
                    Log.i(TAG, "[CLIP-DBG/NATIVE] export strokes=$count points=$pointCount " +
                        "bytes=${file.length()} elapsedMs=$elapsed thickness=$minThickness..$maxThickness " +
                        "width=${"%.2f".format(java.util.Locale.US, minWidth)}..${"%.2f".format(java.util.Locale.US, maxWidth)} " +
                        "rawPenTypes=$rawPenCounts penTypes=$penCounts markerColors=$markerColors " +
                        "input=${elements.size} skipped=$skipped skippedNums=${skippedNums.take(100)}")
                    promise.resolve(Arguments.createMap().apply {
                        putBoolean("success", true); putInt("count", count)
                        putDouble("elapsedMs", elapsed.toDouble()); putMap("rect", rect)
                    })
                } catch (error: Throwable) {
                    promise.reject("CLIP_EXPORT", error.message, error)
                }
            }
        } catch (error: Throwable) {
            promise.reject("CLIP_EXPORT", error.message, error)
        }
    }

    
    internal fun writeStroke(
        out: JsonWriter,
        el: Element,
        mapX: (Int) -> Int = { it },
        mapY: (Int) -> Int = { it },
        onPoint: (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        if (el.type != Element.TRAIL_TYPE_STROKE) return "type:${el.type}"
        val stroke = el.stroke ?: return "missingStroke"
        val points = stroke.points ?: return "missingPoints"
        if (points.size < 4) return "shortPoints:${points.size / 2}"
        val pressures = stroke.pressures
        val sdkPenType = stroke.penType
        if (sdkPenType !in SUPPORTED_PEN_TYPES) return "penType:$sdkPenType"
        
        
        
        
        val rawThickness = el.thickness.coerceAtLeast(1)
        
        val style = when (sdkPenType) {
            PenSizeSpec.NEEDLE, PenSizeSpec.BALL -> 15
            PenSizeSpec.MARKER -> 11
            else -> 16
        }
        out.beginObject().name("penStyle").value(style.toLong())
        out.name("sourcePenType").value(stroke.penType.toLong())
        out.name("sdkPenType").value(sdkPenType.toLong())
        out.name("layerNum").value(el.layerNum.toLong())
        out.name("sdkThickness").value(rawThickness.toLong())
        
        
        
        out.name("drawPathWidth").value(rawThickness.toLong())
        out.name("sdkFactorResize").value(el.factorResize)
        out.name("width").value((rawThickness / 100.0).coerceAtLeast(1.0))
        out.name("penColor").value(stroke.penColor.toLong())
        
        
        el.angles?.takeIf { it.isNotEmpty() }?.let { angles ->
            out.name("angles").beginArray()
            for (angle in angles) out.value(angle.toLong())
            out.endArray()
            if (angles.size >= 2) {
                out.name("tilt").beginArray().value(angles[0].toLong()).value(angles[1].toLong()).endArray()
            }
        }
        out.name("pts").beginArray()
        for (i in 0 until points.size / 2) {
            val x = mapX(points[i * 2]); val y = mapY(points[i * 2 + 1])
            val pressure = pressures?.getOrNull(i)?.toInt()?.div(NOTE_PRESSURE_MAX.toDouble())
                ?.coerceIn(0.0, 1.0) ?: 1.0
            out.value(x.toLong()).value(y.toLong()).value(pressure)
            onPoint(x, y)
        }
        out.endArray().endObject()
        return null
    }

    
    private val SUPPORTED_PEN_TYPES = setOf(
        PenSizeSpec.NEEDLE, PenSizeSpec.BALL, PenSizeSpec.CALLIGRAPHY, PenSizeSpec.MARKER,
    )

    private fun transformCoordinate(
        value: Int, sourceRect: ReadableMap?, targetRect: ReadableMap?, axis: String, pageSize: Int,
    ): Int {
        if (sourceRect == null || targetRect == null) return value
        val sourceStart = sourceRect.getInt(axis)
        val sourceEnd = sourceRect.getInt(if (axis == "left") "right" else "bottom")
        val targetStart = targetRect.getInt(axis)
        val targetEnd = targetRect.getInt(if (axis == "left") "right" else "bottom")
        val sourceSpan = sourceEnd - sourceStart
        if (sourceSpan <= 1 || targetEnd <= targetStart) return value
        return (targetStart + (value - sourceStart).toDouble() *
            (targetEnd - targetStart).toDouble() / sourceSpan).roundToInt().coerceIn(0, pageSize)
    }

}
