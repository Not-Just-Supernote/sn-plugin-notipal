package me.laumss.notipal

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log


internal object HostPenSignals {
    private const val TAG = "HostPenSignals"
    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    
    
    

    @Volatile private var pendingReassertReason = "unknown"

    private val reassertRunnable: Runnable = Runnable {
        val reason = pendingReassertReason
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert fired reason=$reason active=${FloatingPenGuard.hasActiveRects()} " +
                "guard=${FloatingPenGuard.debugState()}")
        }
        FloatingPenGuard.reassert(reason)
    }

    private val foregroundFollowupRunnable: Runnable = Runnable {
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert followup fired guard=${FloatingPenGuard.debugState()}")
        }
        FloatingPenGuard.reassert("foreground change followup")
    }

    
    fun scheduleReassert(
        reason: String,
        allowEmptyAtSchedule: Boolean = false,
        delayMs: Long = PenGuardTiming.GUARD_REASSERT_DELAY_MS
    ) {
        val active = FloatingPenGuard.hasActiveRects()
        if (!active && !allowEmptyAtSchedule) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "guard reassert not scheduled: no active rects reason=$reason " +
                    "guard=${FloatingPenGuard.debugState()}")
            }
            return
        }
        pendingReassertReason = reason
        main.removeCallbacks(reassertRunnable)
        main.postDelayed(reassertRunnable, delayMs)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "guard reassert scheduled reason=$reason active=$active " +
                "allowEmpty=$allowEmptyAtSchedule delayMs=$delayMs " +
                "guard=${FloatingPenGuard.debugState()}")
        }
    }

    
    fun scheduleForegroundReassert(fgKey: String) {
        if (!FloatingPenGuard.hasActiveRects()) return
        scheduleReassert(
            reason = "foreground changed: $fgKey",
            delayMs = PenGuardTiming.HOST_SNAPSHOT_REASSERT_DELAY_MS
        )
        main.removeCallbacks(foregroundFollowupRunnable)
        main.postDelayed(foregroundFollowupRunnable, PenGuardTiming.FOREGROUND_REASSERT_FOLLOWUP_MS)
    }

    fun cancelScheduledReasserts() {
        main.removeCallbacks(reassertRunnable)
        main.removeCallbacks(foregroundFollowupRunnable)
    }

    

    
    @Volatile private var lassoWritableResetPending = false

    fun clearLassoWritableReset() {
        lassoWritableResetPending = false
    }

    
    fun onNativePenAreas(
        rects: List<Rect>,
        rotationActive: Boolean,
        onInsertPlacementSettled: () -> Unit
    ) {
        val isWritableReset = PenAreaGeometry.isWritableReset(rects)
        val isFullScreenSnapshot = PenAreaGeometry.isHostFullScreenSnapshot(rects)
        if (isWritableReset && SubviewLogMonitor.isLassoMenuOpen()) {
            lassoWritableResetPending = true
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "lasso close candidate: writable reset observed")
            }
        } else if (lassoWritableResetPending && !isFullScreenSnapshot) {
            
            lassoWritableResetPending = false
            SubviewLogMonitor.closeLassoMenuFromFallback("writable reset completed")
        }
        
        val transientHostUi = SubviewLogMonitor.isToolbarMenuOpen() ||
            SubviewLogMonitor.isLassoMenuOpen() ||
            SubviewLogMonitor.isSubviewOpen() ||
            SubviewLogMonitor.isOwnedRotationDialogOpen()
        val accepted = FloatingPenGuard.updateHostPenAreas(
            incoming = rects,
            commitBaseline = !transientHostUi && !rotationActive
        )
        val guardActive = FloatingPenGuard.hasActiveRects()
        if (accepted && !isWritableReset && !isFullScreenSnapshot && !transientHostUi && !rotationActive) {
            DocChromeProbe.request("host native pen snapshot")
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host native pen snapshot count=${rects.size} rects=$rects " +
                "accepted=$accepted transientUi=$transientHostUi " +
                "writableReset=$isWritableReset guardActive=$guardActive " +
                "guard=${FloatingPenGuard.debugState()}")
        }

        if (rotationActive) {
            
            if (accepted) FloatingPenGuard.reassert("rotation full-screen hold")
        } else if (accepted && guardActive && !isWritableReset) {
            
            FloatingPenGuard.reassert("host native pen snapshot immediate")
            scheduleReassert(
                reason = "host native pen snapshot",
                delayMs = PenGuardTiming.HOST_SNAPSHOT_REASSERT_DELAY_MS
            )
        } else if (accepted && isWritableReset) {
            
            cancelScheduledReasserts()
            if (!guardActive) {
                FloatingPenGuard.dropIdleGuardState("writable reset, no live rects")
            } else {
                FloatingPenGuard.reassertDocumentAreas("document writable reset")
            }
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "writable reset: stale reassert cancelled; document local areas republished when active")
            }
        }

        
        if (insertImageFlowActive && !SubviewLogMonitor.isLassoMenuOpen()) {
            if (isWritableReset) {
                insertPlacementResetSeen = true
            } else if (insertPlacementResetSeen && accepted &&
                !isFullScreenSnapshot && rects.isNotEmpty()) {
                main.post {
                    if (!insertImageFlowActive) return@post
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "insert placement settled (reset→baseline fallback)")
                    }
                    endInsertImageGuard()
                    onInsertPlacementSettled()
                }
            }
        }
    }

    
    fun onHostTableRebuilt(rotationActive: Boolean) {
        FloatingPenGuard.noteHostTableRewritten()
        if (!rotationActive) scheduleReassert("host pen table rebuild")
        else FloatingPenGuard.reassert("rotation full-screen hold (host rebuild)")
    }

    

    private val lassoMenuReleaseRunnable: Runnable = Runnable {
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host lasso menu full-screen release after grace")
        }
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_LASSO_MENU)
    }

    fun scheduleLassoMenuRelease() {
        main.removeCallbacks(lassoMenuReleaseRunnable)
        main.postDelayed(lassoMenuReleaseRunnable, PenGuardTiming.LASSO_RELEASE_GRACE_MS)
    }

    fun cancelLassoMenuRelease() {
        main.removeCallbacks(lassoMenuReleaseRunnable)
    }

    
    
    

    @Volatile var insertImageFlowActive = false
        private set

    @Volatile private var insertPlacementResetSeen = false

    fun beginInsertImageGuard() {
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.INSERT_IMAGE)
        insertImageFlowActive = true
        insertPlacementResetSeen = false
    }

    fun endInsertImageGuard() {
        if (!insertImageFlowActive) return
        insertImageFlowActive = false
        insertPlacementResetSeen = false
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.INSERT_IMAGE)
    }

    

    fun beginPaletteApplyGuard(): Unit =
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.PALETTE_APPLY)

    fun endPaletteApplyGuard(): Unit =
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.PALETTE_APPLY)

    
    
    
    
    
    
    
    

    @Volatile private var docLassoHoldArmedAt = 0L
    @Volatile private var docStickerHoldArmedAt = 0L

    
    @Volatile var docStickerPending = false
        private set

    private val docStickerHoldTimeoutRunnable: Runnable = Runnable { releaseDocStickerHold("timeout") }
    private val stickerPendingTimeoutRunnable: Runnable = Runnable {
        docStickerPending = false
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "inserted-sticker pending selection timed out")
    }

    
    fun armDocLassoHold(reason: String, confirmed: Boolean = false): Unit = onMain {
        if (!confirmed) return@onMain
        docLassoHoldArmedAt = SystemClock.elapsedRealtime()
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.DOC_LASSO)
        FloatingPenGuard.reassert("doc selection confirmed")
        Log.i(TAG, "doc lasso hold armed reason=$reason confirmed=$confirmed")
    }

    fun releaseDocLassoHold(reason: String): Unit = onMain {
        if (docLassoHoldArmedAt == 0L) return@onMain
        docLassoHoldArmedAt = 0L
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.DOC_LASSO)
        Log.i(TAG, "doc lasso hold released reason=$reason")
    }

    
    fun onStickerInserted(document: Boolean): Unit = onMain {
        docStickerPending = true
        main.removeCallbacks(stickerPendingTimeoutRunnable)
        main.postDelayed(stickerPendingTimeoutRunnable, PenGuardTiming.DOC_STICKER_HOLD_MAX_MS)
        if (document) armDocStickerHold("sticker inserted (doc)")
    }

    fun clearDocStickerPending(): Unit = onMain {
        docStickerPending = false
        main.removeCallbacks(stickerPendingTimeoutRunnable)
    }

    private fun armDocStickerHold(reason: String) {
        docStickerHoldArmedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(docStickerHoldTimeoutRunnable)
        main.postDelayed(docStickerHoldTimeoutRunnable, PenGuardTiming.DOC_STICKER_HOLD_MAX_MS)
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.DOC_STICKER)
        Log.i(TAG, "doc sticker hold armed reason=$reason")
    }

    fun releaseDocStickerHold(reason: String): Unit = onMain {
        if (docStickerHoldArmedAt == 0L) return@onMain
        docStickerHoldArmedAt = 0L
        main.removeCallbacks(docStickerHoldTimeoutRunnable)
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.DOC_STICKER)
        Log.i(TAG, "doc sticker hold released reason=$reason")
    }

    
    fun onDocWriteAreaRebuilt(): Unit = onMain {
        if (docLassoHoldArmedAt != 0L || docStickerHoldArmedAt != 0L) {
            FloatingPenGuard.reassert("doc selection write-area rebuilt")
        } else {
            FloatingPenGuard.reassertDocumentAreas("document write-area rebuilt")
        }
    }

    

    
    fun resetForMonitorStop(reason: String) {
        cancelScheduledReasserts()
        cancelLassoMenuRelease()
        lassoWritableResetPending = false
        endInsertImageGuard()
        endPaletteApplyGuard()
        releaseDocStickerHold(reason)
        releaseDocLassoHold(reason)
        clearDocStickerPending()
    }
}
