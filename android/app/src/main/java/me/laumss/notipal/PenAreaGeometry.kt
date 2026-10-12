package me.laumss.notipal

import android.graphics.Rect
import android.view.Surface


internal object PenAreaGeometry {
    
    private const val SENTINEL_EXTENT = 18888

    
    private const val HOST_FULL_SCREEN_MIN_PX = 1000

    fun clearSentinel(): Rect = Rect(0, 0, SENTINEL_EXTENT, SENTINEL_EXTENT)

    fun isClearSentinel(rect: Rect): Boolean =
        rect.left == 0 && rect.top == 0 &&
            rect.right == SENTINEL_EXTENT && rect.bottom == SENTINEL_EXTENT

    
    fun isWritableReset(rects: List<Rect>): Boolean =
        rects.size == 1 && isClearSentinel(rects[0])

    
    fun isHostFullScreenRect(rect: Rect): Boolean =
        rect.left <= 0 && rect.top <= 0 && !isClearSentinel(rect) &&
            rect.width() >= HOST_FULL_SCREEN_MIN_PX && rect.height() >= HOST_FULL_SCREEN_MIN_PX

    
    fun isHostFullScreenSnapshot(rects: List<Rect>): Boolean =
        rects.size == 1 && isHostFullScreenRect(rects[0])

    
    fun isScreenCovering(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean =
        rect.left <= 0 && rect.top <= 0 && !isClearSentinel(rect) &&
            ((rect.right >= screenWidth && rect.bottom >= screenHeight) || isHostFullScreenRect(rect))

    
    fun normalize(source: List<Rect>, keepClearSentinel: Boolean): List<Rect> =
        source.asSequence()
            .filter { keepClearSentinel || !isClearSentinel(it) }
            .map(::Rect)
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            .sortedWith(compareBy<Rect>({ it.top }, { it.left }, { it.bottom }, { it.right }))
            .toList()

    fun sameRects(a: List<Rect>, b: List<Rect>): Boolean =
        normalize(a, keepClearSentinel = true) == normalize(b, keepClearSentinel = true)

    
    fun toNaturalDisplayRect(
        logical: Rect,
        rotation: Int,
        naturalWidth: Int,
        naturalHeight: Int
    ): Rect {
        val transformed = when (rotation) {
            Surface.ROTATION_90 -> Rect(
                naturalWidth - logical.bottom,
                logical.left,
                naturalWidth - logical.top,
                logical.right
            )
            Surface.ROTATION_180 -> Rect(
                naturalWidth - logical.right,
                naturalHeight - logical.bottom,
                naturalWidth - logical.left,
                naturalHeight - logical.top
            )
            Surface.ROTATION_270 -> Rect(
                logical.top,
                naturalHeight - logical.right,
                logical.bottom,
                naturalHeight - logical.left
            )
            else -> Rect(logical)
        }
        transformed.left = transformed.left.coerceIn(0, naturalWidth)
        transformed.right = transformed.right.coerceIn(0, naturalWidth)
        transformed.top = transformed.top.coerceIn(0, naturalHeight)
        transformed.bottom = transformed.bottom.coerceIn(0, naturalHeight)
        return transformed
    }
}
