package me.laumss.notipal


internal enum class PenGuardScope {
    
    SYSTEM,

    
    HOST_UI,

    
    FLOW,

    
    PRESS,

    
    WINDOW,
}


internal object PenGuardOwner {
    const val HOST_BOOTSTRAP = "host-bootstrap"
    const val ROTATION_SYNC = "rotation-sync"
    const val FOREIGN_ACTIVITY = "foreign-activity"

    const val HOST_TOOLBAR_MENU = "host-toolbar-menu"
    const val HOST_LASSO_MENU = "host-lasso-menu"
    const val NOTE_SUBVIEW = "note-subview"
    const val DOC_TEXT_SELECTION = "doc-text-selection"

    const val INSERT_IMAGE = "insert-image"
    const val PALETTE_APPLY = "palette-apply"
    const val DOC_LASSO = "doc-lasso"
    const val DOC_STICKER = "doc-sticker-transition"

    const val SCREENSHOT_BUBBLE_PRESS = "screenshot-bubble-press"
    private const val TOOLBAR_PRESS_PREFIX = "toolbar-long-press:"

    private const val PANEL_PREFIX = "panel:"
    private const val PEN_SELECTION_PREFIX = "pen-selection:"
    const val PEN_LASSO_PREFIX = "pen-lasso:"

    
    private const val FLOW_MAX_HOLD_MS = 120_000L

    
    private const val PRESS_MAX_HOLD_MS = 10_000L

    fun panel(name: String) = "$PANEL_PREFIX$name"
    fun toolbarPress(toolId: String) = "$TOOLBAR_PRESS_PREFIX$toolId"
    fun penSelection(requestId: String) = "$PEN_SELECTION_PREFIX$requestId"

    fun scopeOf(owner: String): PenGuardScope = when {
        owner == HOST_BOOTSTRAP || owner == ROTATION_SYNC || owner == FOREIGN_ACTIVITY ->
            PenGuardScope.SYSTEM
        owner == HOST_TOOLBAR_MENU || owner == HOST_LASSO_MENU || owner == NOTE_SUBVIEW ||
            owner == DOC_TEXT_SELECTION ->
            PenGuardScope.HOST_UI
        owner == INSERT_IMAGE || owner == PALETTE_APPLY ||
            owner == DOC_LASSO || owner == DOC_STICKER -> PenGuardScope.FLOW
        owner == SCREENSHOT_BUBBLE_PRESS || owner.startsWith(TOOLBAR_PRESS_PREFIX) -> PenGuardScope.PRESS
        else -> PenGuardScope.WINDOW
    }

    
    fun maxHoldMs(owner: String): Long? = when {
        owner == INSERT_IMAGE || owner == PALETTE_APPLY -> FLOW_MAX_HOLD_MS
        scopeOf(owner) == PenGuardScope.PRESS -> PRESS_MAX_HOLD_MS
        else -> null
    }
}
