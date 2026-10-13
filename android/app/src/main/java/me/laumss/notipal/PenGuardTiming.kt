package me.laumss.notipal


internal object PenGuardTiming {
    

    
    const val FOLLOW_INTERVAL_MS = 80L

    
    const val REMOVE_LINGER_MS = 500L

    
    const val HOST_BOOTSTRAP_FALLBACK_MS = 450L

    
    const val BINDER_RETRY_DELAY_MS = 1_000L
    const val BINDER_RETRY_ATTEMPTS = 3

    
    const val OWNERSHIP_CHECK_INTERVAL_MS = 1_000L

    

    
    const val GUARD_REASSERT_DELAY_MS = 600L

    
    const val HOST_SNAPSHOT_REASSERT_DELAY_MS = 120L

    
    const val FOREGROUND_REASSERT_FOLLOWUP_MS = 1_000L

    
    const val LASSO_RELEASE_GRACE_MS = 300L

    

    
    const val ROTATION_DISPLAY_SETTLE_MS = 250L

    
    const val ROTATION_VIEWS_RETRY_MS = 80L

    
    const val ROTATION_DIALOG_RETRY_MS = 800L

    

    
    const val DOC_STICKER_HOLD_MAX_MS = 120_000L

    

    
    const val TOOLBAR_PRESS_RELEASE_MS = 180L

    
    const val TOOLBAR_LONG_PRESS_RELEASE_MS = 350L

    
    const val BUBBLE_PRESS_RELEASE_MS = 600L

    
    val PEN_LASSO_REARM_DELAYS_MS = longArrayOf(400L, 900L, 1_700L)

    
    const val PEN_SELECTION_ACTION_TIMEOUT_MS = 20_000L
    const val PEN_SELECTION_SESSION_TIMEOUT_MS = 120_000L
}
