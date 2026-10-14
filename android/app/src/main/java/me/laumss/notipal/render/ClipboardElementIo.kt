package me.laumss.notipal.render

import android.os.SystemClock
import android.os.Parcel
import android.util.Size
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
    private val contourBatches = mutableMapOf<String, List<Pair<Element, Array<FloatArray>?>>>()
    private var stickerEngine: ClipboardStickerEngine? = null 

    internal fun stickerContours(
        hasSamplePoints: Boolean, contours: Array<FloatArray>?,
        displayWidth: Double, displayHeight: Double, pageWidth: Double, pageHeight: Double,
    ): Array<FloatArray>? {
        require(listOf(displayWidth, displayHeight, pageWidth, pageHeight).all { it.isFinite() && it > 0 })
        if (hasSamplePoints) return null
        val sx = pageWidth / displayWidth
        val sy = pageHeight / displayHeight
        return contours?.map { contour ->
            require(contour.size % 2 == 0 && contour.all { it.isFinite() })
            FloatArray(contour.size) { i -> (contour[i] * if (i % 2 == 0) sx else sy).toFloat() }
        }?.toTypedArray()
    }

    fun restoreContours(path: String, promise: Promise) {
        try {
            val originals = contourBatches.remove(path) ?: error("clipboard contour batch missing: $path")
            originals.forEach { (element, contours) -> element.contoursSrc = contours }
            Log.i(TAG, "[CLIP-COORD] restored path=$path elements=${originals.size}")
            promise.resolve(true)
        } catch (error: Throwable) {
            promise.reject("CLIP_CONTOURS_RESTORE", error.message, error)
        }
    }

    private fun rectMap(left: Int, top: Int, right: Int, bottom: Int): WritableMap =
        Arguments.createMap().apply {
            putInt("left", left); putInt("top", top)
            putInt("right", right); putInt("bottom", bottom)
        }

    private class CoordinateBounds {
        var count = 0
        var invalid = 0
        private var left = Double.POSITIVE_INFINITY
        private var top = Double.POSITIVE_INFINITY
        private var right = Double.NEGATIVE_INFINITY
        private var bottom = Double.NEGATIVE_INFINITY

        fun add(x: Double, y: Double) {
            if (!x.isFinite() || !y.isFinite()) { invalid++; return }
            count++
            left = minOf(left, x); top = minOf(top, y)
            right = maxOf(right, x); bottom = maxOf(bottom, y)
        }

        override fun toString(): String = if (count == 0) "empty(invalid=$invalid)"
            else "[$left,$top,$right,$bottom](count=$count,invalid=$invalid)"
    }

    fun clearContours(ctx: ReactApplicationContext, uuids: ReadableArray, context: ReadableMap, promise: Promise) {
        var preparedPath: String? = null
        try {
            val plugin = PageImageSource.getPluginApp(ctx)
            val getTrail = plugin.javaClass.getMethod("getTrail", String::class.java)
            val elements = (0 until uuids.size()).map { index ->
                getTrail.invoke(plugin, uuids.getString(index)) as? Element
                    ?: error("clipboard element cache missing at $index")
            }
            val path = context.getString("path") ?: error("clipboard path missing")
            check(!contourBatches.containsKey(path)) { "clipboard contour batch already active: $path" }
            val pageWidth = context.getDouble("pageWidth")
            val pageHeight = context.getDouble("pageHeight")
            val displayWidth = context.getDouble("displayWidth")
            val displayHeight = context.getDouble("displayHeight")
            val prepared = elements.map { el ->
                stickerContours(
                    el.type == Element.TRAIL_TYPE_STROKE && (el.stroke?.points?.size ?: 0) >= 2,
                    el.contoursSrc, displayWidth, displayHeight, pageWidth, pageHeight,
                )
            }
            contourBatches[path] = elements.map { it to it.contoursSrc }
            preparedPath = path
            val clearedIndexes = Arguments.createArray()
            Log.i(TAG, "[CLIP-COORD] before-clear context=${context.toHashMap()} " +
                "displayPx=${ctx.resources.displayMetrics.widthPixels}x${ctx.resources.displayMetrics.heightPixels} " +
                "elements=${elements.size}")
            elements.forEachIndexed { index, el ->
                val points = el.stroke?.points
                val contours = el.contoursSrc
                val pointBounds = CoordinateBounds()
                val contourBounds = CoordinateBounds()
                points?.let { data ->
                    for (i in 0 until data.size / 2) {
                        pointBounds.add(data[i * 2].toDouble(), data[i * 2 + 1].toDouble())
                    }
                }
                contours?.forEach { data ->
                    for (i in 0 until data.size / 2) {
                        contourBounds.add(data[i * 2].toDouble(), data[i * 2 + 1].toDouble())
                    }
                }
                Log.i(TAG, "[CLIP-COORD] before-clear index=$index uuid=${uuids.getString(index)} " +
                    "trailNum=${el.trailNumInPage} type=${el.type} layer=${el.layerNum} " +
                    "max=${el.maxX}x${el.maxY} pen=${el.stroke?.penType} " +
                    "thickness=${el.thickness} factorResize=${el.factorResize} " +
                    "pointValues=${points?.size ?: -1} points=$pointBounds " +
                    "contours=${contours?.size ?: -1} contourBounds=$contourBounds " +
                    "pressures=${el.stroke?.pressures?.size ?: -1} angles=${el.angles?.size ?: -1}")
                val hasSamplePoints = el.type == Element.TRAIL_TYPE_STROKE && (points?.size ?: 0) >= 2
                el.contoursSrc = prepared[index]
                if (hasSamplePoints) clearedIndexes.pushInt(index)
                val afterBounds = CoordinateBounds()
                el.contoursSrc?.forEach { data ->
                    for (i in 0 until data.size / 2) afterBounds.add(data[i * 2].toDouble(), data[i * 2 + 1].toDouble())
                }
                Log.i(TAG, "[CLIP-COORD] prepared trailNum=${el.trailNumInPage} " +
                    "action=${if (hasSamplePoints) "clear" else "preserve-scale"} " +
                    "scale=${pageWidth / displayWidth},${pageHeight / displayHeight} contours=$afterBounds")
            }
            Log.i(TAG, "[CLIP-COORD] prepared-summary processed=${elements.size} cleared=${clearedIndexes.size()} " +
                "remainingWithPoints=${elements.count { el -> el.contoursSrc?.any { it.isNotEmpty() } == true }}")
            promise.resolve(Arguments.createMap().apply {
                putInt("processed", elements.size)
                putArray("clearedIndexes", clearedIndexes)
                putInt("contourElements", prepared.count { rings -> rings?.any { it.isNotEmpty() } == true })
            })
        } catch (error: Throwable) {
            preparedPath?.let { path ->
                contourBatches.remove(path)?.forEach { (element, contours) -> element.contoursSrc = contours }
            }
            promise.reject("CLIP_CONTOURS", error.message, error)
        }
    }

    fun convertSticker(ctx: ReactApplicationContext, metadata: ReadableArray, context: ReadableMap, promise: Promise) {
        try {
            val path = context.getString("path") ?: error("clipboard path missing")
            val batch = contourBatches[path] ?: error("clipboard contour batch missing")
            require(metadata.size() == batch.size && batch.isNotEmpty())
            val pageWidth = context.getInt("pageWidth")
            val pageHeight = context.getInt("pageHeight")
            require(pageWidth > 0 && pageHeight > 0)
            val machine = context.getInt("machineType")
            val rotation = if (pageWidth > pageHeight) 1090 else 1000
            
            val snapshots = batch.mapIndexed { index, (source, _) ->
                val meta = metadata.getMap(index) ?: error("clipboard metadata missing")
                check(meta.getString("uuid") == source.uuid) { "clipboard element order changed" }
                val parcel = Parcel.obtain()
                try {
                    source.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    (Element.CREATOR.createFromParcel(parcel) as Element).apply {
                        maxX = meta.getInt("maxX")
                        maxY = meta.getInt("maxY")
                        layerNum = meta.getInt("layerNum")
                    }
                } finally { parcel.recycle() }
            }
            val note = ctx.packageManager.getApplicationInfo("com.ratta.supernote.note", 0)
            worker.execute {
                try {
                    val engine = stickerEngine ?: ClipboardStickerEngine(note.sourceDir, note.nativeLibraryDir)
                        .also { stickerEngine = it }
                    val trails = snapshots.map { source ->
                        engine.convertElement(engine.copyElement(source), machine, rotation, Size(pageWidth, pageHeight),
                            source.maxX, source.maxY, source.layerNum)
                    }
                    engine.write(trails, machine, rotation, path)
                    promise.resolve(Arguments.createMap().apply {
                        putBoolean("success", true)
                        putBoolean("result", true)
                    })
                } catch (error: Throwable) {
                    Log.e(TAG, "[CLIP-LOCAL] save failed path=$path", error)
                    promise.reject("CLIP_LOCAL_SAVE", error.message, error)
                }
            }
        } catch (error: Throwable) {
            promise.reject("CLIP_LOCAL_PREPARE", error.message, error)
        }
    }

    fun applyContourPalette(ctx: ReactApplicationContext, uuids: ReadableArray, context: ReadableMap,
                            style: ReadableMap, promise: Promise) {
        try {
            val plugin = PageImageSource.getPluginApp(ctx)
            val getTrail = plugin.javaClass.getMethod("getTrail", String::class.java)
            val elements = (0 until uuids.size()).map { index ->
                getTrail.invoke(plugin, uuids.getString(index)) as? Element
                    ?: error("palette element cache missing: $index")
            }
            val outlineCount = elements.count { it.type == 0 && (it.stroke?.points?.size ?: 0) == 0 &&
                it.contoursSrc?.any { ring -> ring.isNotEmpty() } == true }
            if (outlineCount == 0) {
                promise.resolve(Arguments.createMap().apply { putBoolean("handled", false) })
                return
            }
            val snapshots = elements.map { source ->
                val parcel = Parcel.obtain()
                try {
                    source.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                    Element.CREATOR.createFromParcel(parcel) as Element
                } finally { parcel.recycle() }
            }
            val path = context.getString("filePath") ?: error("palette file missing")
            val page = context.getInt("pageNum")
            val width = context.getInt("pageWidth")
            val height = context.getInt("pageHeight")
            require(width > 0 && height > 0 && page >= 0)
            fun optionalInt(name: String) = if (style.hasKey(name) && !style.isNull(name)) style.getInt(name) else null
            val penColor = optionalInt("penColor")
            val thickness = optionalInt("thickness")
            val penType = optionalInt("penType")
            val note = ctx.packageManager.getApplicationInfo("com.ratta.supernote.note", 0)
            worker.execute {
                try {
                    val engine = stickerEngine ?: ClipboardStickerEngine(note.sourceDir, note.nativeLibraryDir)
                        .also { stickerEngine = it }
                    PaletteFileWriter.apply(engine, path, page, Size(width, height), snapshots, penColor, thickness, penType)
                    promise.resolve(Arguments.createMap().apply {
                        putBoolean("handled", true); putBoolean("success", true); putBoolean("requiresReload", true)
                        putInt("contourCount", outlineCount)
                        putArray("result", Arguments.createArray().apply { snapshots.forEach { pushInt(it.trailNumInPage) } })
                    })
                } catch (error: Throwable) {
                    Log.e(TAG, "[PLT-CONTOUR] batch failed page=$page", error)
                    promise.reject("PALETTE_CONTOUR", error.message, error)
                }
            }
        } catch (error: Throwable) { promise.reject("PALETTE_PREPARE", error.message, error) }
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
                                Log.i(TAG, "[CLIP-COORD] export-skip path=$path " +
                                    "trailNum=${el.trailNumInPage} type=${el.type} reason=$skipReason")
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
        includeContours: Boolean = false,
        onPoint: (Int, Int) -> Unit = { _, _ -> },
    ): String? {
        val geometry = if (includeContours && el.type == 700) el.geometry else null
        if (el.type != Element.TRAIL_TYPE_STROKE && geometry == null) return "type:${el.type}"
        val stroke = el.stroke
        if (stroke == null && geometry == null) return "missingStroke"
        val points = if (geometry != null) IntArray(0) else stroke?.points ?: IntArray(0)
        val outlines = if (includeContours) el.contoursSrc?.filter { it.isNotEmpty() } else null
        val hasOutline = !outlines.isNullOrEmpty()
        if (!hasOutline && points.size < (if (includeContours) 2 else 4)) return "shortPoints:${points.size / 2}"
        if (hasOutline) require(outlines!!.all { ring -> ring.size % 2 == 0 && ring.all { it.isFinite() } }) { "Invalid note contour" }
        val pressures = stroke?.pressures
        val sdkPenType = geometry?.penType ?: stroke!!.penType
        if (sdkPenType !in SUPPORTED_PEN_TYPES) return "penType:$sdkPenType"
        
        
        
        
        val rawThickness = (geometry?.penWidth ?: el.thickness).coerceAtLeast(1)
        
        val style = when (sdkPenType) {
            PenSizeSpec.NEEDLE, PenSizeSpec.BALL -> 15
            PenSizeSpec.MARKER -> 11
            else -> 16
        }
        out.beginObject().name("penStyle").value(style.toLong())
        out.name("sourcePenType").value(sdkPenType.toLong())
        out.name("sdkPenType").value(sdkPenType.toLong())
        out.name("layerNum").value(el.layerNum.toLong())
        out.name("sdkThickness").value(rawThickness.toLong())
        
        
        
        out.name("drawPathWidth").value(rawThickness.toLong())
        out.name("sdkFactorResize").value(el.factorResize)
        out.name("width").value((rawThickness / 100.0).coerceAtLeast(1.0))
        out.name("penColor").value((geometry?.penColor ?: stroke!!.penColor).toLong())
        
        
        el.angles?.takeIf { it.isNotEmpty() }?.let { angles ->
            out.name("angles").beginArray()
            for (angle in angles) out.value(angle.toLong())
            out.endArray()
            if (angles.size >= 2) {
                out.name("tilt").beginArray().value(angles[0].toLong()).value(angles[1].toLong()).endArray()
            }
        }
        if (hasOutline) {
            out.name("geometry").value("contours")
            out.name("contours").beginArray()
            for (ring in outlines!!) {
                out.beginArray()
                for (value in ring) out.value(value.toDouble())
                out.endArray()
            }
            out.endArray()
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
