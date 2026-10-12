package me.laumss.notipal.ui_common


object PanelRegistry {
    private val live = LinkedHashSet<PanelBase>()

    
    var onActiveEdge: ((anyShowing: Boolean) -> Unit)? = null

    internal fun onShown(panel: PanelBase) {
        val wasEmpty = live.isEmpty()
        live += panel
        if (wasEmpty && live.isNotEmpty()) onActiveEdge?.invoke(true)
    }
    internal fun onHidden(panel: PanelBase) {
        val wasShowing = live.isNotEmpty()
        live -= panel
        if (wasShowing && live.isEmpty()) onActiveEdge?.invoke(false)
    }

    fun anyShowing(): Boolean = live.isNotEmpty()

    fun hideAll() = live.toList().forEach { it.hide() }
    fun suspendAll() = live.toList().forEach { it.suspendVisibility() }
    fun resumeAll() = live.toList().forEach { it.resumeVisibility() }

    fun handleRotation(): Boolean {
        var restoreToolbar = false
        for (panel in live.toList()) if (panel.onRotation()) restoreToolbar = true
        return restoreToolbar
    }
}
