package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt


object FilePickerCells {
    const val COVER_ASPECT = 450f / 350f
    private const val DOCUMENT_FOLD_RATIO = 0.18f
    private const val NOTE_SPINE_RATIO = 0.052f
    private const val FILE_CARD_STROKE = 2f
    private const val FILE_CARD_SELECTED_STROKE = 4f
    private val fileCardStroke = Color.rgb(184, 184, 184)
    private val previewImageExts = setOf("jpg", "jpeg", "png", "webp")
    private val documentPreviewExts = setOf("note", "pdf", "cbz", "epub")

    fun isPreviewImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in previewImageExts

    fun breadcrumb(
        ctx: Context,
        rootPath: String,
        currentPath: String,
        onNavigate: (String) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun addLevel(label: String, destination: String, home: Boolean) {
            row.addView(TextView(ctx).apply {
                text = label
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    FilePickerScale.px(ctx, if (home) 57f else 42f).toFloat(),
                )
                typeface = if (home) Typeface.SERIF else Typeface.DEFAULT
                setTextColor(Color.rgb(20, 20, 20))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = FilePickerScale.px(ctx, if (home) 340f else 420f)
                setOnClickListener { onNavigate(destination) }
            })
        }
        addLevel("Supernote", rootPath, home = true)
        val relative = currentPath.removePrefix(rootPath).trim('/')
        if (relative.isNotEmpty()) {
            var destination = rootPath
            relative.split('/').filter { it.isNotBlank() }.forEach { component ->
                destination = "$destination/$component"
                row.addView(TextView(ctx).apply {
                    text = "›"
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, FilePickerScale.px(ctx, 50f).toFloat())
                    setTextColor(Color.rgb(35, 35, 35))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        FilePickerScale.px(ctx, 58f),
                        LinearLayout.LayoutParams.MATCH_PARENT,
                    )
                })
                addLevel(component, destination, home = false)
            }
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    fun homeFolderCell(
        ctx: Context,
        name: String,
        onClick: () -> Unit,
    ): View {
        val scale = FilePickerScale.canvasScale(ctx)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                FilePickerScale.px(ctx, 14f), FilePickerScale.px(ctx, 21f),
                FilePickerScale.px(ctx, 14f), FilePickerScale.px(ctx, 21f),
            )
            setOnClickListener { onClick() }
            addView(
                CompactFolderIconView(ctx, scale, dark = true),
                LinearLayout.LayoutParams(FilePickerScale.px(ctx, 62f), FilePickerScale.px(ctx, 54f)).apply {
                    marginEnd = FilePickerScale.px(ctx, 18f)
                },
            )
            addView(TextView(ctx).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, FilePickerScale.px(ctx, 38f).toFloat())
                setTextColor(Color.rgb(24, 24, 24))
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, FilePickerScale.px(ctx, 54f), 1f))
        }
    }

    fun folderCell(
        ctx: Context,
        name: String,
        childCount: Int,
        coverW: Int,
        coverH: Int,
        previewPaths: List<String>,
        loadThumb: (String, ImageView, Int, Int) -> Unit,
        onClick: () -> Unit,
    ): View {
        val scale = FilePickerScale.canvasScale(ctx)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick() }
        }
        val preview = FolderPreviewView(ctx, scale, previewPaths, loadThumb)
        cell.addView(preview, LinearLayout.LayoutParams(coverW, coverH))
        cell.addView(metaBlock(ctx, name, me.laumss.notipal.NativeLocale.itemCount(childCount), folder = true))
        return cell
    }

    fun fileCell(
        ctx: Context,
        name: String,
        detail: String,
        path: String,
        coverW: Int,
        coverH: Int,
        selected: Boolean,
        loadThumb: (String, ImageView, Int, Int) -> Unit,
        onClick: () -> Unit,
    ): Pair<View, FileCardView> {
        val scale = FilePickerScale.canvasScale(ctx)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val card = FileCardView(ctx, scale, extension, selected).apply {
            setOnClickListener { onClick() }
        }
        if (extension in previewImageExts) {
            val image = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(240, 240, 240))
            }
            card.addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            loadThumb(path, image, coverW, coverH)
        } else {
            card.addView(TextView(ctx).apply {
                text = extension.uppercase(Locale.ROOT).ifBlank { "FILE" }
                setTextSize(TypedValue.COMPLEX_UNIT_PX, FilePickerScale.px(ctx, 36f).toFloat())
                typeface = Typeface.SERIF
                setTextColor(Color.rgb(65, 65, 65))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        cell.addView(card, LinearLayout.LayoutParams(coverW, coverH))
        val title = File(name).nameWithoutExtension.takeIf { it.isNotBlank() } ?: name
        cell.addView(metaBlock(ctx, title, detail, folder = false))
        return cell to card
    }

    private fun metaBlock(
        ctx: Context,
        title: String,
        detail: String,
        folder: Boolean,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(0, FilePickerScale.px(ctx, 20f), 0, 0)
        addView(TextView(ctx).apply {
            text = title
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                FilePickerScale.px(ctx, if (folder) 30f else 36f).toFloat(),
            )
            typeface = Typeface.SERIF
            setTextColor(Color.rgb(24, 24, 24))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = detail
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                FilePickerScale.px(ctx, if (folder) 22f else 24f).toFloat(),
            )
            setTextColor(Color.rgb(102, 102, 102))
            maxLines = 1
        })
    }

    class FileCardView(
        context: Context,
        private val scale: Float,
        private val extension: String,
        selected: Boolean,
    ) : FrameLayout(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var cardSelected = selected

        init {
            setWillNotDraw(false)
            isClickable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val noteStart = if (extension == "note") {
                (w * NOTE_SPINE_RATIO + 3f * scale).roundToInt().coerceAtLeast(1)
            } else 0
            setPadding(noteStart, 0, 0, 0)
        }

        fun setCardSelected(selected: Boolean) {
            cardSelected = selected
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            drawCard(canvas, fillOnly = true)
            super.onDraw(canvas)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokePaint.strokeWidth = (if (cardSelected) FILE_CARD_SELECTED_STROKE else FILE_CARD_STROKE) * scale
            strokePaint.color = if (cardSelected) Color.BLACK else fileCardStroke
            val inset = strokePaint.strokeWidth / 2f
            if (extension in documentPreviewExts && extension != "note") {
                val fold = w * DOCUMENT_FOLD_RATIO
                val outline = documentOutline(w, h, inset, fold)
                val checkpoint = canvas.save()
                canvas.clipPath(outline)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(checkpoint)
                canvas.drawPath(outline, strokePaint)
                val foldedCorner = Path().apply {
                    moveTo(w - fold, inset)
                    lineTo(w - fold, fold)
                    lineTo(w - inset, fold)
                    close()
                }
                canvas.drawPath(foldedCorner, fillPaint)
                canvas.drawPath(foldedCorner, strokePaint)
            } else {
                val rect = RectF(inset, inset, w - inset, h - inset)
                val checkpoint = canvas.save()
                canvas.clipRect(rect)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(checkpoint)
                canvas.drawRect(rect, strokePaint)
                if (extension == "note") {
                    val spine = w * NOTE_SPINE_RATIO
                    canvas.drawLine(spine, inset, spine, h - inset, strokePaint)
                }
            }
            if (cardSelected) drawSelectionBadge(canvas)
        }

        
        private fun drawSelectionBadge(canvas: Canvas) {
            val left = 10f * scale
            val top = 10f * scale
            val size = 58f * scale
            FilePickerSelectionArtwork.draw(canvas, true, RectF(left, top, left + size, top + size))
        }

        private fun drawCard(canvas: Canvas, fillOnly: Boolean) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokePaint.strokeWidth = (if (cardSelected) FILE_CARD_SELECTED_STROKE else FILE_CARD_STROKE) * scale
            strokePaint.color = if (cardSelected) Color.BLACK else fileCardStroke
            val inset = strokePaint.strokeWidth / 2f
            if (extension in documentPreviewExts && extension != "note") {
                val outline = documentOutline(w, h, inset, w * DOCUMENT_FOLD_RATIO)
                canvas.drawPath(outline, fillPaint)
                if (!fillOnly) canvas.drawPath(outline, strokePaint)
            } else {
                val rect = RectF(inset, inset, w - inset, h - inset)
                canvas.drawRect(rect, fillPaint)
                if (!fillOnly) canvas.drawRect(rect, strokePaint)
            }
        }

        private fun documentOutline(width: Float, height: Float, inset: Float, fold: Float) =
            Path().apply {
                moveTo(inset, inset)
                lineTo(width - fold, inset)
                lineTo(width - inset, fold)
                lineTo(width - inset, height - inset)
                lineTo(inset, height - inset)
                close()
            }
    }

    
    private fun folderPath(
        path: Path,
        left: Float, top: Float, right: Float, bottom: Float,
        tabEnd: Float, tabSlope: Float, bodyTop: Float, radius: Float,
    ) {
        val tabRadius = minOf(radius, (bodyTop - top) * 0.9f)
        path.reset()
        path.moveTo(left, bottom - radius)
        path.lineTo(left, top + tabRadius)
        path.quadTo(left, top, left + tabRadius, top)
        path.lineTo(tabEnd, top)
        val midX = tabEnd + tabSlope / 2f
        path.cubicTo(midX, top, midX, bodyTop, tabEnd + tabSlope, bodyTop)
        path.lineTo(right - radius, bodyTop)
        path.quadTo(right, bodyTop, right, bodyTop + radius)
        path.lineTo(right, bottom - radius)
        path.quadTo(right, bottom, right - radius, bottom)
        path.lineTo(left + radius, bottom)
        path.quadTo(left, bottom, left, bottom - radius)
        path.close()
    }

    
    private class CompactFolderIconView(
        context: Context,
        private val scale: Float,
        dark: Boolean,
    ) : View(context) {
        private val ink = if (dark) Color.rgb(20, 20, 20) else Color.WHITE
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.FILL
        }
        private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.rgb(20, 20, 20)
            style = Paint.Style.FILL
        }
        private val back = Path()
        private val front = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            
            val iconW = minOf(w, h * 1.2f)
            val iconH = iconW / 1.2f
            val left = (w - iconW) / 2f
            val top = (h - iconH) / 2f
            val right = left + iconW
            val bottom = top + iconH
            val radius = iconH * 0.12f
            FilePickerCells.folderPath(
                back, left, top, right, bottom,
                tabEnd = left + iconW * 0.34f,
                tabSlope = iconW * 0.1f,
                bodyTop = top + iconH * 0.16f,
                radius = radius,
            )
            canvas.drawPath(back, fillPaint)
            val gap = maxOf(3f * scale, 1.5f)
            front.set(left, top + iconH * 0.3f, right, bottom)
            canvas.drawRoundRect(
                front.left, front.top - gap, front.right, front.bottom,
                radius, radius, gapPaint,
            )
            canvas.drawRoundRect(front, radius, radius, fillPaint)
        }
    }

    private class FolderPreviewView(
        context: Context,
        private val scale: Float,
        previewPaths: List<String>,
        private val loadThumb: (String, ImageView, Int, Int) -> Unit,
    ) : FrameLayout(context) {
        private val previewImages = ArrayList<ImageView>()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(222, 222, 222)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 28, 28)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * scale
        }
        private val outline = Path()

        init {
            setWillNotDraw(false)
            previewPaths.take(4).forEach { path ->
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        setStroke((2f * scale).roundToInt().coerceAtLeast(1), Color.rgb(35, 35, 35))
                    }
                    tag = path
                }
                previewImages.add(image)
                addView(image, LayoutParams(1, 1))
            }
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            if (width <= 0 || height <= 0) return
            val slotWidth = (width * 0.34f).roundToInt()
            val slotHeight = (slotWidth * 1.31f).roundToInt()
            val horizontalGap = (width * 0.045f).roundToInt()
            val verticalGap = (height * 0.038f).roundToInt()
            val left = (width - slotWidth * 2 - horizontalGap) / 2
            val top = (height * 0.13f).roundToInt()
            previewImages.forEachIndexed { index, image ->
                image.layoutParams = LayoutParams(slotWidth, slotHeight).apply {
                    leftMargin = left + (index % 2) * (slotWidth + horizontalGap)
                    topMargin = top + (index / 2) * (slotHeight + verticalGap)
                }
                val path = image.tag as? String ?: return@forEachIndexed
                loadThumb(path, image, slotWidth, slotHeight)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = strokePaint.strokeWidth / 2f
            val bodyTop = h * 0.075f
            val tabEnd = w * 0.36f
            val tabSlope = w * 0.08f
            FilePickerCells.folderPath(
                outline, inset, inset, w - inset, h - inset,
                tabEnd, tabSlope, bodyTop, radius = w * 0.045f,
            )
            canvas.drawPath(outline, fillPaint)
            
            
            val checkpoint = canvas.save()
            canvas.clipPath(outline)
            canvas.drawRect(0f, 0f, tabEnd + tabSlope, bodyTop, tabPaint)
            canvas.restoreToCount(checkpoint)
            canvas.drawPath(outline, strokePaint)
            canvas.drawLine(inset, bodyTop, tabEnd + tabSlope, bodyTop, strokePaint)
        }
    }
}
