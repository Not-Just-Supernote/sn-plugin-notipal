package me.laumss.notipal.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.PathParser
import com.ratta.supernote.plugincommon.data.common.trail.Element
import java.io.File


object VectorPageRenderer {

    private const val PEN_TYPE_MARKER = 11
    private const val MARKER_WASH_ALPHA = 0.45f
    
    private const val GEOMETRY_PEN_WIDTH_SCALE = 100f
    private const val FIVE_STAR_THICKNESS_SCALE = 100f
    private const val TITLE_DARK_GRAY = 0x9D
    private const val TITLE_LIGHT_GRAY = 0xC9
    private const val TITLE_HATCH_STEP = 10f
    private const val LINK_STROKE_WIDTH = 3f
    private const val LINK_ICON_SIZE = 42f
    private const val LINK_ICON_OFFSET = 50f
    private const val TEXT_FRAME_RADIUS = 8f
    private const val LINK_ICON_PATH_1 =
        "M17.408,14.592a5.855,5.855 45,0 1,0 8.28l-2.3,2.3 -2.3,2.3a5.855,5.855 0,0 1,-8.28,0a5.855,5.855 0,0 1,0,-8.28l2.3,-2.3 2.3,-2.3"
    private const val LINK_ICON_PATH_2 =
        "M14.592,17.408a5.855,5.855 0,0 1,0,-8.28l2.3,-2.3 2.3,-2.3a5.855,5.855 0,0 1,8.28,0a5.855,5.855 0,0 1,0,8.28l-2.3,2.3 -2.3,2.3"

    private const val TAG = "VectorPageRenderer"

    
    fun render(
        elements: List<Element>,
        pageW: Int,
        pageH: Int,
        targetW: Int,
        targetH: Int,
        background: Bitmap? = null,
        colorMode: Boolean = false,
        layerPreviewPath: String = "",
    ): Bitmap {
        require(pageW > 0 && pageH > 0) { "invalid page size: ${pageW}x$pageH" }
        require(targetW > 0 && targetH > 0) { "invalid target size: ${targetW}x$targetH" }
        val scaleX = targetW.toFloat() / pageW
        val scaleY = targetH.toFloat() / pageH
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            if (background != null) {
                canvas.drawBitmap(
                    background,
                    null,
                    Rect(0, 0, targetW, targetH),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }

            var layerPreview: Bitmap? = null
            val needsPreview = elements.any {
                it.type == Element.TRAIL_TYPE_PICTURE &&
                    it.picture?.picturePath?.let { p -> p.isEmpty() || !File(p).exists() } != false
            }
            if (needsPreview && layerPreviewPath.isNotEmpty() && File(layerPreviewPath).exists()) {
                layerPreview = decodeSampledBitmap(layerPreviewPath, targetW, targetH)
                Log.i(TAG, "layer preview for pictures: " +
                    "${layerPreview?.width}x${layerPreview?.height} from $layerPreviewPath")
            }
            try {
                
                canvas.save()
                canvas.scale(scaleX, scaleY)
                drawElementLayers(
                    canvas = canvas,
                    elements = elements,
                    pageW = pageW,
                    pageH = pageH,
                    scale = 1f,
                    colorMode = colorMode,
                    layerPreview = layerPreview,
                )
                canvas.restore()
            } finally {
                try { layerPreview?.recycle() } catch (_: Throwable) {}
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    
    private fun drawElementLayers(
        canvas: Canvas,
        elements: List<Element>,
        pageW: Int,
        pageH: Int,
        scale: Float,
        colorMode: Boolean,
        layerPreview: Bitmap?,
    ) {
        val titleStyleByTrail = HashMap<Int, Int>()
        elements.forEach { element ->
            if (element.type != Element.TRAIL_TYPE_TILE) return@forEach
            val title = element.title ?: return@forEach
            if (title.style !in 1..4) return@forEach
            title.controlTrailNums.orEmpty().forEach { trailNum ->
                if (trailNum >= 0) titleStyleByTrail[trailNum] = title.style
            }
            drawTitleBackground(canvas, element, scale)
        }

        elements.forEach { element ->
            val titleStyle = titleStyleByTrail[element.trailNumInPage]
                ?: titleStyleByTrail[element.trailNum]
            val titleColorOverride = titleStyle?.let(::titleInkColor)
            when (element.type) {
                Element.TRAIL_TYPE_STROKE -> drawStroke(
                    canvas, element, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_GEO -> drawGeometry(
                    canvas, element, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_FIVE_STAR -> drawFiveStar(
                    canvas, element, pageW, pageH, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_TEXT,
                Element.TRAIL_TYPE_TEXT_DIGEST_QUOTE,
                Element.TRAIL_TYPE_TEXT_DIGEST_CREATE -> drawText(
                    canvas, element, scale, colorMode, titleColorOverride,
                )
                Element.TRAIL_TYPE_PICTURE -> drawPicture(
                    canvas, element, pageW, pageH, scale, layerPreview,
                )
            }
        }

        elements.forEach { element ->
            if (element.type == Element.TRAIL_TYPE_LINK) {
                drawLink(canvas, element, scale, colorMode)
            }
        }
    }

    private fun drawTitleBackground(canvas: Canvas, element: Element, scale: Float) {
        val title = element.title ?: return
        val rect = RectF(
            title.x * scale,
            title.y * scale,
            (title.x + title.width) * scale,
            (title.y + title.height) * scale,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return
        when (title.style) {
            1 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Color.BLACK
            })
            2 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Color.rgb(TITLE_DARK_GRAY, TITLE_DARK_GRAY, TITLE_DARK_GRAY)
            })
            3 -> canvas.drawRect(rect, Paint().apply {
                style = Paint.Style.FILL
                color = Color.rgb(TITLE_LIGHT_GRAY, TITLE_LIGHT_GRAY, TITLE_LIGHT_GRAY)
            })
            4 -> {
                canvas.drawRect(rect, Paint().apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                })
                val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = (1.5f * scale).coerceAtLeast(1f)
                    strokeCap = Paint.Cap.SQUARE
                    color = Color.BLACK
                }
                canvas.save()
                canvas.clipRect(rect)
                var x = rect.left - rect.height()
                val step = TITLE_HATCH_STEP * scale
                while (x < rect.right) {
                    canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, hatchPaint)
                    x += step
                }
                canvas.restore()
            }
        }
    }

    private fun titleInkColor(style: Int): Int = when (style) {
        1, 2 -> Color.WHITE
        else -> Color.BLACK
    }

    private fun drawStroke(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
    ) {
        val contours = element.contoursSrc ?: return
        if (contours.isEmpty()) return
        val path = Path().apply { fillType = Path.FillType.WINDING }
        contours.forEach { points ->
            if (points == null || points.size < 6) return@forEach
            path.moveTo(points[0] * scale, points[1] * scale)
            var index = 2
            while (index + 1 < points.size) {
                path.lineTo(points[index] * scale, points[index + 1] * scale)
                index += 2
            }
            path.close()
        }

        val stroke = element.stroke
        val color = colorOverride ?: resolveColor(stroke?.penColor ?: 0, colorMode)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            if (colorOverride != null) {
                this.color = colorOverride
            } else if (stroke?.penType == PEN_TYPE_MARKER) {
                this.color = washColor(color)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            } else {
                this.color = color
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawGeometry(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
    ) {
        val geometry = element.geometry ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (geometry.penWidth / GEOMETRY_PEN_WIDTH_SCALE * scale)
                .coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorOverride ?: resolveColor(geometry.penColor, colorMode)
        }

        when (geometry.type) {
            "GEO_circle", "GEO_ellipse" -> {
                val center = geometry.ellipseCenterPoint ?: return
                val cx = center.x * scale
                val cy = center.y * scale
                val rx = geometry.ellipseMajorAxisRadius * scale
                val ry = geometry.ellipseMinorAxisRadius * scale
                canvas.save()
                canvas.rotate(geometry.ellipseAngle.toFloat(), cx, cy)
                canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)
                canvas.restore()
            }
            else -> {
                val points = geometry.points ?: return
                if (points.size < 2) return
                val path = Path().apply {
                    moveTo(points[0].x * scale, points[0].y * scale)
                    for (index in 1 until points.size) {
                        lineTo(points[index].x * scale, points[index].y * scale)
                    }
                    if (geometry.type == "GEO_polygon" || geometry.type == "curvePolygon") close()
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawFiveStar(
        canvas: Canvas,
        element: Element,
        pageW: Int,
        pageH: Int,
        scale: Float,
        colorMode: Boolean,
        colorOverride: Int? = null,
    ) {
        val points = element.fiveStar?.points ?: return
        if (points.size < 6) return
        val first = emrToPage(
            points[0].toFloat(), points[1].toFloat(), pageW, pageH, element.maxX, element.maxY,
        ) ?: return
        val path = Path().apply { moveTo(first.x * scale, first.y * scale) }
        var index = 2
        while (index + 1 < points.size) {
            val point = emrToPage(
                points[index].toFloat(), points[index + 1].toFloat(), pageW, pageH,
                element.maxX, element.maxY,
            ) ?: return
            path.lineTo(point.x * scale, point.y * scale)
            index += 2
        }
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (element.thickness / FIVE_STAR_THICKNESS_SCALE * scale)
                .coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = colorOverride ?: resolveColor(0, colorMode)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawText(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
        textColorOverride: Int? = null,
    ) {
        val textBox = element.textBox ?: return
        val content = textBox.textContentFull ?: return
        if (content.isEmpty()) return
        val rect = textBox.textRect ?: return
        val frameRect = RectF(
            rect.left * scale,
            rect.top * scale,
            rect.right * scale,
            rect.bottom * scale,
        )
        if (frameRect.width() <= 0f || frameRect.height() <= 0f) return

        val frameStyle = textBox.textFrameStyle
        val frameWidth = ((textBox.textFrameWidth.takeIf { it > 0 } ?: 2) * scale)
            .coerceAtLeast(1f)
        drawTextFrame(canvas, frameRect, frameStyle, frameWidth, scale, textBox, colorMode)

        val framed = frameStyle in 1..3
        val paddingX = if (framed) maxOf(8f * scale, frameWidth * 1.5f) else 0f
        val paddingY = if (framed) maxOf(4f * scale, frameWidth) else 0f
        val textLeft = frameRect.left + paddingX
        val textTop = frameRect.top + paddingY
        val width = Math.round(frameRect.width() - paddingX * 2f)
        if (width <= 0) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColorOverride ?: resolveColor(textBox.textColor, colorMode)
            textSize = (textBox.fontSize * scale).coerceAtLeast(1f)
            letterSpacing = textBox.letterSpacing
            typeface = resolveTypeface(textBox.fontPath, textBox.textBold, textBox.textItalics)
        }
        if (element.type == Element.TRAIL_TYPE_TEXT_DIGEST_CREATE) {
            drawDigestCreateMarker(canvas, frameRect, content, textPaint, scale)
            return
        }
        val alignment = when (textBox.textAlign) {
            1 -> Layout.Alignment.ALIGN_CENTER
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val spacingMultiplier = textBox.lineSpacingMultiplier.takeIf { it > 0f } ?: 1f
        val spacingExtra = textBox.lineSpacingExtra * scale
        @Suppress("DEPRECATION")
        val layout = StaticLayout(
            content, textPaint, width, alignment, spacingMultiplier, spacingExtra, false,
        )
        canvas.save()
        canvas.translate(textLeft, textTop)
        layout.draw(canvas)
        canvas.restore()
    }

    
    private fun drawDigestCreateMarker(
        canvas: Canvas,
        rect: RectF,
        content: String,
        textPaint: TextPaint,
        scale: Float,
    ) {
        val leftBracket = "["
        val rightBracket = "]"
        val gap = maxOf(3f * scale, textPaint.textSize * 0.12f)
        val leftWidth = textPaint.measureText(leftBracket)
        val contentWidth = textPaint.measureText(content)
        val rightWidth = textPaint.measureText(rightBracket)
        val totalWidth = leftWidth + gap + contentWidth + gap + rightWidth
        val startX = rect.centerX() - totalWidth / 2f
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        val contentX = startX + leftWidth + gap
        val rightX = contentX + contentWidth + gap

        canvas.drawText(leftBracket, startX, baseline, textPaint)
        canvas.drawText(content, contentX, baseline, textPaint)
        canvas.drawText(rightBracket, rightX, baseline, textPaint)

        val underlineY = baseline + maxOf(2f * scale, textPaint.textSize * 0.07f)
        canvas.drawLine(
            contentX,
            underlineY,
            contentX + contentWidth,
            underlineY,
            Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1.5f * scale, textPaint.textSize * 0.04f)
                strokeCap = Paint.Cap.SQUARE
            },
        )
    }

    private fun drawTextFrame(
        canvas: Canvas,
        rect: RectF,
        frameStyle: Int,
        frameWidth: Float,
        scale: Float,
        textBox: com.ratta.supernote.plugincommon.data.common.trail.TextBox,
        colorMode: Boolean,
    ) {
        val radius = TEXT_FRAME_RADIUS * scale
        if (frameStyle == 2) {
            canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = resolveColor(textBox.textFrameFillColor, colorMode)
            })
        }
        if (frameStyle in 1..3) {
            val inset = frameWidth / 2f
            val strokeRect = RectF(rect).apply { inset(inset, inset) }
            canvas.drawRoundRect(strokeRect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = frameWidth
                color = resolveColor(textBox.textFrameStrokeColor, colorMode)
            })
        }
    }

    private fun drawLink(
        canvas: Canvas,
        element: Element,
        scale: Float,
        colorMode: Boolean,
    ) {
        val link = element.link ?: return
        val rect = RectF(
            link.x * scale,
            link.y * scale,
            (link.x + link.width) * scale,
            (link.y + link.height) * scale,
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val strokeWidth = (LINK_STROKE_WIDTH * scale).coerceAtLeast(1f)
        val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            color = resolveColor(0, colorMode)
            if (link.style == 2) {
                pathEffect = DashPathEffect(floatArrayOf(8f * scale, 4f * scale), 0f)
            }
        }

        if (link.category == 0) {
            val content = link.showText.takeIf { it.isNotBlank() } ?: link.fullText.orEmpty()
            if (content.isNotEmpty()) {
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = resolveColor(0, colorMode)
                    textSize = (link.fontSize * scale).takeIf { it > 0f }
                        ?: (36f * scale).coerceAtLeast(1f)
                    typeface = resolveTypeface(link.fontPath, link.bold, link.italic)
                    isFakeBoldText = true
                }
                val metrics = textPaint.fontMetrics
                val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(content, rect.left + 10f * scale, baseline, textPaint)
            }
        }

        when (link.style) {
            0 -> canvas.drawLine(
                rect.left,
                rect.bottom - 2f * scale,
                rect.right,
                rect.bottom - 2f * scale,
                decorationPaint,
            )
            1, 2 -> canvas.drawRect(
                rect.left + 1f * scale,
                rect.top + 1f * scale,
                rect.right - 2f * scale,
                rect.bottom - 2f * scale,
                decorationPaint,
            )
        }

        val iconSize = LINK_ICON_SIZE * scale
        drawChainIcon(
            canvas,
            rect.right - LINK_ICON_OFFSET * scale,
            rect.bottom - LINK_ICON_OFFSET * scale,
            iconSize,
            colorMode,
        )
    }

    private fun drawChainIcon(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        colorMode: Boolean,
    ) {
        val first = PathParser.createPathFromPathData(LINK_ICON_PATH_1) ?: return
        val second = PathParser.createPathFromPathData(LINK_ICON_PATH_2) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = resolveColor(0, colorMode)
        }
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(size / 32f, size / 32f)
        canvas.drawPath(first, paint)
        canvas.drawPath(second, paint)
        canvas.restore()
    }

    private fun resolveTypeface(pathValue: String?, bold: Int, italic: Int): Typeface {
        val style = when {
            bold == 1 && italic == 1 -> Typeface.BOLD_ITALIC
            bold == 1 -> Typeface.BOLD
            italic == 1 -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val path = pathValue.orEmpty()
        return if (path.isNotEmpty() && File(path).exists()) {
            try {
                Typeface.create(Typeface.createFromFile(path), style)
            } catch (_: Exception) {
                Typeface.create(Typeface.DEFAULT, style)
            }
        } else {
            Typeface.create(Typeface.DEFAULT, style)
        }
    }

    private fun drawPicture(
        canvas: Canvas,
        element: Element,
        pageW: Int,
        pageH: Int,
        scale: Float,
        layerPreview: Bitmap?,
    ) {
        val picture = element.picture ?: return
        val rect = picture.rect ?: return
        
        
        val left = minOf(rect.left, rect.right).toFloat()
        val top = minOf(rect.top, rect.bottom).toFloat()
        val right = maxOf(rect.left, rect.right).toFloat()
        val bottom = maxOf(rect.top, rect.bottom).toFloat()
        val destination = RectF(left * scale, top * scale, right * scale, bottom * scale)
        if (destination.width() <= 0f || destination.height() <= 0f) {
            Log.w(TAG, "picture degenerate rect: l=${rect.left} t=${rect.top} r=${rect.right} b=${rect.bottom}")
            return
        }

        val path = picture.picturePath.orEmpty()
        if (path.isNotEmpty() && File(path).exists()) {
            val bitmap = decodeSampledBitmap(
                path,
                destination.width().toInt().coerceAtLeast(1),
                destination.height().toInt().coerceAtLeast(1),
            )
            if (bitmap != null) {
                try {
                    canvas.drawBitmap(
                        bitmap, null, destination,
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                    )
                } finally {
                    bitmap.recycle()
                }
                return
            }
            Log.w(TAG, "picture decode failed: $path")
        } else {
            Log.i(TAG, "picture path unreadable ($path), using layer preview crop")
        }

        
        
        val preview = layerPreview ?: return
        val px = preview.width.toFloat() / pageW
        val py = preview.height.toFloat() / pageH
        val src = Rect(
            (left * px).toInt().coerceIn(0, preview.width),
            (top * py).toInt().coerceIn(0, preview.height),
            (right * px).toInt().coerceIn(0, preview.width),
            (bottom * py).toInt().coerceIn(0, preview.height),
        )
        if (src.width() <= 0 || src.height() <= 0) {
            Log.w(TAG, "picture preview crop empty, skipped")
            return
        }
        canvas.drawBitmap(
            preview, src, destination,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    private fun decodeSampledBitmap(path: String, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetW &&
            bounds.outHeight / (sample * 2) >= targetH
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    
    private fun emrToPage(
        x: Float,
        y: Float,
        pageW: Int,
        pageH: Int,
        elementMaxX: Int,
        elementMaxY: Int,
    ): PointF? {
        if (pageW <= 1 || pageH <= 1) return null
        val fallback = fallbackEmrMax(pageW, pageH)
        val maxX = elementMaxX.takeIf { it > 0 }?.toFloat() ?: fallback?.first ?: return null
        val maxY = elementMaxY.takeIf { it > 0 }?.toFloat() ?: fallback?.second ?: return null
        val sourceX = x / (maxX / (pageH - 1))
        val sourceY = y / (maxY / (pageW - 1))
        return PointF(pageW - 1f - sourceY, sourceX)
    }

    private fun fallbackEmrMax(pageW: Int, pageH: Int): Pair<Float, Float>? = when (pageW to pageH) {
        1920 to 2560 -> 21632f to 16224f
        1404 to 1872 -> 15819f to 11864f
        2560 to 3414 -> 28854f to 21632f
        1872 to 2496 -> 21098f to 15819f
        2560 to 1920 -> 16224f to 21632f
        1872 to 1404 -> 11864f to 15819f
        3414 to 2560 -> 21632f to 28854f
        2496 to 1872 -> 15819f to 21098f
        else -> null
    }

    
    private fun resolveColor(raw: Int, colorMode: Boolean): Int {
        val value = raw.toLong() and 0xFFFFFFFFL
        return if (value <= 0xFF) {
            val gray = value.toInt()
            Color.rgb(gray, gray, gray)
        } else if (colorMode) {
            (0xFF000000L or (value and 0x00FFFFFFL)).toInt()
        } else {
            val color = value.toInt()
            val luminance = (
                Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29
            ) shr 8
            Color.rgb(luminance, luminance, luminance)
        }
    }

    private fun washColor(color: Int): Int {
        val red = (255 - (255 - Color.red(color)) * MARKER_WASH_ALPHA).toInt()
        val green = (255 - (255 - Color.green(color)) * MARKER_WASH_ALPHA).toInt()
        val blue = (255 - (255 - Color.blue(color)) * MARKER_WASH_ALPHA).toInt()
        return Color.rgb(red, green, blue)
    }
}
