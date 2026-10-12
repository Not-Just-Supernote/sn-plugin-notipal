package me.laumss.notipal

import me.laumss.notipal.bubbles.*
import me.laumss.notipal.ui_common.PanelRegistry



object ToolRegistry {

    fun hideAll() {
        PanelRegistry.hideAll()
        FloatingBubbleModule.hideStatic()
        AiBubbleModule.hideStatic()
        PaletteBubbleModule.hideStatic()
    }

    fun handleRotation(): Boolean = PanelRegistry.handleRotation()

    fun anyPanelShowing(): Boolean = PanelRegistry.anyShowing()

    
    fun setPanelActiveEdgeListener(l: ((anyShowing: Boolean) -> Unit)?) {
        PanelRegistry.onActiveEdge = l
    }

    fun suspendAll() = PanelRegistry.suspendAll()

    fun resumeAll() = PanelRegistry.resumeAll()
}
