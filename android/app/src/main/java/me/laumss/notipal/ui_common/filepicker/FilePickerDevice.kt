package me.laumss.notipal.ui_common.filepicker

import android.content.Context
import com.ratta.supernote.pluginlib.utils.DeviceUtils


object FilePickerDevice {
    fun profile(ctx: Context): FilePickerDeviceProfile {
        val metrics = ctx.resources.displayMetrics
        val sdkType = runCatching { DeviceUtils.getDeviceType() }.getOrDefault(-1)
        return FilePickerDeviceProfiles.create(
            sdkType = sdkType,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
    }
}
