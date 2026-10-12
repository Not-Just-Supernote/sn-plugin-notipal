package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import kotlin.math.roundToInt


object FilePickerScale {
    private const val DESIGN_DENSITY = 1.875f
    private const val CONTENT_SCALE = 1.25f

    
    fun canvasScale(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        return dm.density / DESIGN_DENSITY * FilePickerDevice.profile(ctx).uiScale
    }

    
    fun contentFactor(ctx: Context): Float =
        CONTENT_SCALE * FilePickerDevice.profile(ctx).uiScale

    
    fun px(ctx: Context, designPx: Number): Int =
        (designPx.toFloat() * canvasScale(ctx)).roundToInt()

    
    fun noteSp(ctx: Context, designPx: Number): Float =
        designPx.toFloat() / DESIGN_DENSITY * FilePickerDevice.profile(ctx).uiScale

    
    fun titleSp(ctx: Context, value: Float): Float =
        value * FilePickerDevice.profile(ctx).titleScale

    fun dp(ctx: Context, v: Int): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * contentFactor(ctx)).roundToInt()
    }

    fun dp(ctx: Context, v: Float): Int {
        val dm = ctx.resources.displayMetrics
        return (v * dm.density * contentFactor(ctx)).roundToInt()
    }

    fun sp(ctx: Context, v: Float): Float = v * contentFactor(ctx)
}
