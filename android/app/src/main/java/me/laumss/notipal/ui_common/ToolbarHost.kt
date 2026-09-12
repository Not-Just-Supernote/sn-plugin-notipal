package me.laumss.notipal.ui_common

import com.facebook.react.bridge.WritableMap


interface ToolbarHost {
    val screenW: Int
    val screenH: Int
    fun refreshScreenDimensions()
    fun restoreToolbar()
    fun emitEvent(name: String, params: WritableMap)
}
