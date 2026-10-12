package me.laumss.notipal.ui_common.filepicker

import kotlin.math.min


enum class FilePickerDeviceType(val sdkValue: Int) {
    A5(0),
    A6(1),
    A6X(2),
    A5X(3),
    A6X2(4),
    A5X2(5),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromSdkValue(value: Int): FilePickerDeviceType =
            entries.firstOrNull { it.sdkValue == value } ?: UNKNOWN
    }
}

data class FilePickerDeviceProfile(
    val type: FilePickerDeviceType,
    val screenWidth: Int,
    val screenHeight: Int,
    val uiScale: Float,
    val titleScale: Float,
    val pickerGridColumns: Int,
    val supportsA5BookFeatures: Boolean,
)


object FilePickerDeviceProfiles {
    private const val DESIGN_WIDTH_PX = 1920f
    private const val FALLBACK_MIN_SCALE = 0.75f
    private const val A5X_TITLE_SCALE = 0.88f
    private const val A6_UI_SCALE = 0.86f

    fun create(sdkType: Int, screenWidth: Int, screenHeight: Int): FilePickerDeviceProfile {
        val type = FilePickerDeviceType.fromSdkValue(sdkType)
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val shortSide = min(width, height)
        val knownDevice = type != FilePickerDeviceType.UNKNOWN

        return FilePickerDeviceProfile(
            type = type,
            screenWidth = width,
            screenHeight = height,
            uiScale = when {
                type == FilePickerDeviceType.A6X || type == FilePickerDeviceType.A6X2 -> A6_UI_SCALE
                knownDevice -> 1f
                else -> (shortSide / DESIGN_WIDTH_PX).coerceIn(FALLBACK_MIN_SCALE, 1f)
            },
            titleScale = when {
                type == FilePickerDeviceType.A5X -> A5X_TITLE_SCALE
                type == FilePickerDeviceType.A6X || type == FilePickerDeviceType.A6X2 -> A6_UI_SCALE
                knownDevice -> 1f
                else -> (shortSide / DESIGN_WIDTH_PX).coerceIn(A5X_TITLE_SCALE, 1f)
            },
            pickerGridColumns = if (shortSide >= DESIGN_WIDTH_PX) 5 else 4,
            supportsA5BookFeatures = type == FilePickerDeviceType.A5X ||
                type == FilePickerDeviceType.A5X2,
        )
    }
}
