package me.laumss.notipal
import me.laumss.notipal.panels.*
import me.laumss.notipal.overlays.*
import me.laumss.notipal.bubbles.*
import me.laumss.notipal.ui_common.Dialog
import me.laumss.notipal.ui_common.PanelBase
import me.laumss.notipal.render.PageImageSource
import me.laumss.notipal.render.ClipboardElementIo
import me.laumss.notipal.render.NoteMosaicExport

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingToolbarModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), me.laumss.notipal.ui_common.ToolbarHost {

    internal enum class MosaicPermissionResult {
        GRANTED,
        DENIED,
        PLUGIN_UNAVAILABLE,
    }

    override fun getName() = "FloatingToolbar"

    override val screenW: Int get() = screenWidth
    override val screenH: Int get() = screenHeight

    override fun getConstants(): MutableMap<String, Any> {
        val model = Build.MODEL.lowercase()
        val (w, h) = when {
            model.contains("a6 x") || model.contains("a6x") -> 1404 to 1872
            model.contains("a5 x") || model.contains("a5x") -> 1920 to 2560
            model.contains("nomad") -> 1920 to 2560
            else -> 1920 to 2560
        }
        return mutableMapOf("ENABLE_DEBUG" to BuildConfig.ENABLE_DEBUG,
            "PAGE_WIDTH" to w, "PAGE_HEIGHT" to h)
    }

    companion object {
        private const val TAG = "FloatingToolbar"
        private const val PLUGIN_FILE_READ_PERMISSION = "plugin.permission.FILE:READ"
        private const val PLUGIN_FILE_WRITE_PERMISSION = "plugin.permission.FILE:WRITE"
        private const val PLUGIN_FILE_DELETE_PERMISSION = "plugin.permission.FILE:DELETE"
        private const val PLUGIN_INTERNET_PERMISSION = "plugin.permission.INTERNET"
        private const val PLUGIN_PERMISSION_STATUS_ALWAYS = 2
        private const val PLUGIN_PERMISSION_STATUS_SESSION = 1
        
        
        
        private const val INKLING_PLUGIN_ID = "19uq35yjd3tr3zjr"
        private const val MOSAIC_PLUGIN_ID = "mosaic20260730ab"
        private const val HOST_PLUGIN_MANAGER_CLASS =
            "com.ratta.supernote.pluginhost.manager.PluginManager"
        private const val HOST_PERMISSION_CALLBACK_CLASS =
            "com.ratta.supernote.pluginhost.security.PluginPermissionManager\$PermissionCallback"
        private const val MOSAIC_PACKAGE = "me.laumss.mosaic"
        private const val MOSAIC_ACTIVITY = "me.laumss.mosaic.MainActivity"
        private const val SCREENSHOT_STATE_PREFS = "screenshot_state"
        private const val STITCH_SESSION_ACTIVE_KEY = "stitch_session_active"
        private const val STITCH_ARMED_AT_KEY = "stitch_armed_at"
        
        private const val STITCH_RECENT_MS = 15 * 60 * 1000L

        private val RETRY_DELAYS_MS = longArrayOf(50, 250, 750, 1500, 2500)

        @Volatile @JvmStatic
        private var windowManager: WindowManager? = null
        @Volatile @JvmStatic
        private var rootView: View? = null
        @Volatile @JvmStatic
        private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic
        private var expandedRoot: LinearLayout? = null
        @Volatile @JvmStatic
        private var toolContainer: LinearLayout? = null

        @Volatile @JvmStatic
        private var collapsedRoot: View? = null

        @Volatile @JvmStatic
        private var collapsed: Boolean = false
        @Volatile @JvmStatic
        private var dockSide: String = "left"
        @Volatile @JvmStatic
        private var tools: MutableList<ToolItem> = mutableListOf()
        @Volatile private var docMode = false
        @Volatile private var loadedDocMode: Boolean? = null
        @Volatile private var toolbarSessionActive = false
        @Volatile private var mosaicMode = false
        @Volatile private var mosaicSurface = MosaicLink.SURFACE_BOARD
        @Volatile private var mosaicNoteRef: String? = null
        @Volatile private var pendingMosaicRebuild = false
        @Volatile private var mosaicRebuildRunnable: Runnable? = null
        
        @Volatile private var surfaceBubbleRaisePending = false

        private val LATCHING_ACTIONS: Set<String> = setOf(
            "insert_text", "text_recv_nospacing", "text_recv_paragraph", "ai_relay"
        )

        
        internal const val SMART_LASSO_TOOL_ID = "smart_lasso"
        internal const val AI_RELAY_TOOL_ID = "ai_relay"
        internal const val INK_PALETTE_TOOL_ID = "ink_palette"
        internal const val CLIPBOARD_TOOL_ID = "toolbar_clipboard"
        internal const val COLLAPSE_TOOL_ID = "toolbar_collapse"
        internal const val GOTO_NOTE_TOOL_ID = "goto_note"
        
        internal const val NOTE_TO_MOSAIC_TOOL_ID = "note_to_mosaic"
        internal const val INSERT_TEXT_TOOL_ID = "insert_text"

        private fun defaultToolNameKey(id: String): String = when (id) {
            "insert_image" -> "config_tool_image"
            "insert_doc_screenshot" -> "config_tool_doc"
            INSERT_TEXT_TOOL_ID -> "config_tool_text"
            SMART_LASSO_TOOL_ID -> "config_tool_smart_lasso"
            "insert_link" -> "config_tool_link"
            GOTO_NOTE_TOOL_ID -> "config_tool_goto_note"
            AI_RELAY_TOOL_ID -> "config_tool_ai_relay"
            INK_PALETTE_TOOL_ID -> "config_tool_ink_palette"
            CLIPBOARD_TOOL_ID -> "config_tool_clipboard"
            COLLAPSE_TOOL_ID -> "config_tool_collapse"
            else -> ""
        }
        private const val SHARED_PRESET_KEY = "shared_preset"
        private const val SHARED_DEFAULT_TOOLS_JSON =
            "[{\"id\":\"insert_doc_screenshot\",\"action\":\"insert_doc_screenshot\",\"icon\":\"Sc\"}," +
            "{\"id\":\"smart_lasso\",\"action\":\"smart_lasso\",\"icon\":\"AI\"}," +
            "{\"id\":\"ink_palette\",\"action\":\"ink_palette\",\"icon\":\"Ip\"}," +
            "{\"id\":\"ai_relay\",\"action\":\"ai_relay\",\"icon\":\"Rl\"}]"
        private const val NOTE_DEFAULT_TOOLS_JSON =
            "[{\"id\":\"insert_image\",\"action\":\"insert_image\",\"icon\":\"Im\"}]"
        private const val DOC_DEFAULT_TOOLS_JSON = "[]"

        private val TOOLBAR_UTILITY_TOOL_IDS = setOf(
            CLIPBOARD_TOOL_ID,
            COLLAPSE_TOOL_ID,
            GOTO_NOTE_TOOL_ID,
            INSERT_TEXT_TOOL_ID
        )

        private val NOTE_ONLY_TOOL_IDS = setOf("insert_image", "insert_link")

        private val MOSAIC_TOOL_IDS = setOf(
            "insert_image", "insert_doc_screenshot", SMART_LASSO_TOOL_ID, AI_RELAY_TOOL_ID,
        )

        private val TOOL_CATALOG_IDS = setOf(
            "insert_image",
            "insert_doc_screenshot",
            INSERT_TEXT_TOOL_ID,
            SMART_LASSO_TOOL_ID,
            "insert_link",
            GOTO_NOTE_TOOL_ID,
            AI_RELAY_TOOL_ID,
            INK_PALETTE_TOOL_ID,
            CLIPBOARD_TOOL_ID,
            COLLAPSE_TOOL_ID,
        )

        private fun isCatalogTool(id: String, action: String): Boolean =
            id in TOOL_CATALOG_IDS && action in TOOL_CATALOG_IDS

        internal fun isToolbarUtilityTool(id: String): Boolean = id in TOOLBAR_UTILITY_TOOL_IDS

        @JvmStatic
        private val activeModeIds: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        @Volatile @JvmStatic
        private var pendingOpenMain: Boolean = false

        @Volatile @JvmStatic
        private var pendingScreen: String = ""

        
        @Volatile @JvmStatic
        private var selfShowPluginViewAt = 0L
        private const val SELF_SHOW_GRACE_MS = 3_000L

        @JvmStatic
        private fun isSelfInitiatedHostShow(): Boolean =
            android.os.SystemClock.elapsedRealtime() - selfShowPluginViewAt < SELF_SHOW_GRACE_MS

        
        @JvmStatic
        private fun toolbarShowOwnsHostEntry(): Boolean =
            toolbarHostClosePending && toolbarHostCloseGeneration == toolbarBuildGeneration

        @Volatile @JvmStatic
        private var pendingShow: Boolean = false

        
        @Volatile @JvmStatic
        private var toolbarHostClosePending: Boolean = false
        @Volatile @JvmStatic
        private var toolbarHostCloseGeneration: Long = -1L

        
        @Volatile @JvmStatic
        private var toolbarBuildGeneration: Long = 0L

        @Volatile @JvmStatic
        private var insertPluginViewClosed: Boolean = false

        @Volatile @JvmStatic
        private var startX = 0
        @Volatile @JvmStatic
        private var startY = 0
        @Volatile @JvmStatic
        private var startRawX = 0f
        @Volatile @JvmStatic
        private var startRawY = 0f
        @Volatile @JvmStatic
        private var isDragging = false
        @Volatile @JvmStatic
        private var longPressTriggered = false

        private const val AUTO_COLLAPSE_MS = 9000L
        
        
        
        private const val DOCK_FLUSH_TOLERANCE = 6

        @JvmStatic
        internal var screenWidth = 1404
        @JvmStatic
        internal var screenHeight = 1872

        @Volatile @JvmStatic
        private var configCallbackRegistered = false
        @Volatile @JvmStatic
        private var displayListenerRegistered = false
        @Volatile @JvmStatic
        private var lastDisplayRotation = -1

        @Volatile @JvmStatic
        var lastNotePath: String = ""
        @Volatile @JvmStatic
        var lastPageNum: Int = 0

        @Volatile @JvmStatic
        private var foregroundMonitorRunning = false
        
        @JvmStatic
        private var pendingRestoreRunnable: Runnable? = null
        @Volatile @JvmStatic
        private var isInNoteApp = true

        
        @Volatile @JvmStatic
        private var lastForegroundKey: String? = null

        
        @Volatile @JvmStatic
        private var noteForegroundSinceMs = 0L

        @JvmStatic
        fun isInNoteApp(): Boolean = isInNoteApp

        
        @JvmStatic
        fun isDocumentContext(): Boolean = docMode && !MosaicLink.isBoardVisible()

        private fun syncDocumentTextSelectionGuard() {
            FloatingPenGuard.setFullScreenActive(
                isInNoteApp && isDocumentContext() && SubviewLogMonitor.isDocTextSelectionActive(),
                PenGuardOwner.DOC_TEXT_SELECTION,
            )
        }

        
        private fun syncPluginHostGuard() {
            FloatingPenGuard.setFullScreenActive(
                isInNoteApp && isPluginHostWindowShowing && !mosaicMode,
                PenGuardOwner.PLUGIN_HOST,
            )
        }

        
        @JvmStatic
        fun isTextReceiveModeActive(): Boolean = activeModeIds.contains(INSERT_TEXT_TOOL_ID)

        @JvmStatic
        fun isDocMode(): Boolean = docMode

        @JvmStatic
        fun isMosaicMode(): Boolean = mosaicMode

        @Volatile @JvmStatic
        private var isPluginHostWindowShowing = false

        
        @Volatile @JvmStatic
        private var configPanelActive = false

        
        @Volatile @JvmStatic
        private var configPanelShowPending = false

        @JvmStatic
        internal fun setConfigPanelActive(active: Boolean) {
            configPanelActive = active
        }

        @JvmStatic
        internal fun setConfigPanelShowPending(pending: Boolean) {
            configPanelShowPending = pending
        }

        @JvmStatic
        internal fun isConfigPanelShowPending(): Boolean = configPanelShowPending

        @JvmStatic
        internal fun isConfigPanelActive(): Boolean = configPanelActive

        
        @JvmStatic
        private fun configPanelBlocksToolbar(): Boolean {
            val visible = ConfigPanel.currentInstance?.isActuallyVisible == true
            if (visible || configPanelShowPending) {
                configPanelActive = true
                return true
            }
            if (configPanelActive) {
                configPanelActive = false
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "config panel state reconciled: no visible/pending panel")
                }
            }
            return false
        }

        
        @JvmStatic
        internal fun resetConfigPanelState() {
            configPanelActive = false
            configPanelShowPending = false
        }

        @JvmStatic
        private var windowStateMonitor: WindowStateMonitor? = null


        
        @Volatile @JvmStatic
        private var toolbarRestorePending = false
        private var entryPermissionInFlight = false

        
        @Volatile @JvmStatic
        private var terminateAfterPluginMenuClose = false

        
        @Volatile @JvmStatic
        private var hostButtonChannelBroken = false

        @JvmStatic
        private val subviewListener = object : SubviewLogMonitor.Listener {
            override fun onSubviewOpened(generation: Int) = onNoteSubviewChanged(true, generation)
            override fun onSubviewClosed(generation: Int) = onNoteSubviewChanged(false, generation)
            override fun onToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int) =
                handleToolbarMenuChanged(open, pluginListMenu, generation)
            override fun onLassoMenuChanged(open: Boolean, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.clearLassoWritableReset()
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) return@post
                    HostPenSignals.cancelLassoMenuRelease()
                    if (open) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu full-screen=true")
                        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.HOST_LASSO_MENU)
                        
                        
                        SubviewLogMonitor.clearLassoRelocating()

                        HostPenSignals.endInsertImageGuard()
                        onMultiInsertSelectionShown()

                        
                        
                        
                        
                        
                        if (!paletteApplyInFlight) HostPenSignals.endPaletteApplyGuard()
                    } else {
                        if (paletteApplyInFlight) {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "host lasso menu close: palette apply in flight; defer restore")
                            }
                            return@post
                        }
                        
                        
                        
                        
                        if (SubviewLogMonitor.isLassoMenuOpen()) {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "host lasso menu close: stale callback, newer lasso is open")
                            }
                            return@post
                        }
                        
                        
                        
                        
                        if ((paletteSelectionHold || multiInsertQueue.isNotEmpty()) &&
                            SubviewLogMonitor.isLassoRelocating()) {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "host lasso menu close: held selection is being moved; keep hold")
                            }
                            return@post
                        }
                        handleLassoMenuCancelled("lasso menu closed")
                    }
                }
            }

            override fun onMovedLassoGone(generation: Int) {
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) return@post
                    if (paletteApplyInFlight || !(paletteSelectionHold || multiInsertQueue.isNotEmpty())) return@post
                    
                    if (SubviewLogMonitor.isLassoMenuOpen()) return@post
                    handleLassoMenuCancelled("moved lasso selection cancelled")
                }
            }

            override fun onNativePenAreasChanged(rects: List<Rect>, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onNativePenAreas(rects, rotationActive = rotationEpoch != 0L) {
                    
                    if (multiInsertAwaitingSelection && !multiInsertDocLinks) {
                        onMultiInsertSelectionShown()
                        if (advanceMultiInsert("placed without selection")) return@onNativePenAreas
                    }
                    scheduleRestore(RESTORE_DELAY_NORMAL, "insert placement settled")
                }
            }

            override fun onHostFullScreenDisableArea(generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onHostTableRebuilt(rotationActive = rotationEpoch != 0L)
            }

            override fun onDocWriteAreaRebuilt(generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                HostPenSignals.onDocWriteAreaRebuilt()
            }

            override fun onDocTextSelectionChanged(active: Boolean, generation: Int) {
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) return@post
                    syncDocumentTextSelectionGuard()
                    if (SubviewLogMonitor.isDocTextSelectionActive()) {
                        FloatingPenGuard.reassertDocumentAreas("document text selection")
                    }
                }
            }

            override fun onDocLassoReleased(reason: String, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                monitorHandler.post {
                    
                    
                    
                    
                    
                    
                    val pendingInsertedSticker = HostPenSignals.docStickerPending
                    
                    
                    
                    HostPenSignals.releaseDocStickerHold(reason)
                    if (paletteApplyInFlight) return@post
                    HostPenSignals.releaseDocLassoHold(reason)
                    
                    
                    
                    
                    
                    
                    if (!pendingInsertedSticker && paletteSelectionHold) {
                        setPaletteSelectionHold(false)
                        scheduleRestore(RESTORE_DELAY_NORMAL, "doc lasso released")
                    }
                }
            }

            override fun onDocLassoStarted(confirmed: Boolean, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                if (!docMode) return
                HostPenSignals.armDocLassoHold("host selection", confirmed)
            }

            override fun onOwnedRotationDialogChanged(
                open: Boolean,
                token: Long,
                generation: Int
            ) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                if (token != rotationEpoch || rotationEpoch == 0L) return
                rotationDialogShown = open
                if (!open) rotationDialogArmed = false
                monitorHandler.post {
                    if (token != rotationEpoch || rotationEpoch == 0L) return@post
                    if (open) {
                        Log.i(TAG, "owned dialog shown epoch=$token")
                    } else {
                        Log.i(TAG, "owned dialog dismissed epoch=$token → destroyAll")
                        currentInstance?.destroyAll()
                    }
                }
            }
        }

        
        @JvmStatic
        private fun handleLassoMenuCancelled(reason: String) {
            setPaletteSelectionHold(false)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host lasso menu cancelled: release full-screen owner reason=$reason")
            }
            HostPenSignals.cancelLassoMenuRelease()
            
            
            HostPenSignals.cancelScheduledReasserts()
            FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_LASSO_MENU)
            HostPenSignals.endPaletteApplyGuard()
            
            if (multiInsertAwaitingSelection) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "multi insert: stale lasso close while awaiting selection")
                return
            }
            HostPenSignals.endInsertImageGuard()
            if (advanceMultiInsert(reason)) return
            scheduleRestore(RESTORE_DELAY_NORMAL, reason)
        }

        
        private val multiInsertQueue = ArrayDeque<String>()
        private var multiInsertFromReceived = false
        private var multiInsertDocLinks = false
        private var activeDocLinkRequest: String? = null
        private var docLinkTargetFile = ""
        private var docLinkTargetPage = -1

        
        private var multiInsertAwaitingSelection = false

        
        private const val MULTI_INSERT_AWAIT_MAX_MS = 15_000L

        @JvmStatic
        private val multiInsertAwaitTimeout = Runnable {
            if (!multiInsertAwaitingSelection) return@Runnable
            Log.w(TAG, "multi insert: selection never shown; abandon remaining=${multiInsertQueue.size}")
            clearMultiInsert("selection timeout")
            HostPenSignals.endInsertImageGuard()
            scheduleRestore(RESTORE_DELAY_NORMAL, "multi insert timeout")
        }

        @JvmStatic
        private fun onMultiInsertSelectionShown() {
            if (multiInsertDocLinks && activeDocLinkRequest != null) return
            if (!multiInsertAwaitingSelection) return
            multiInsertAwaitingSelection = false
            monitorHandler.removeCallbacks(multiInsertAwaitTimeout)
        }

        
        @JvmStatic
        private fun advanceMultiInsert(reason: String): Boolean {
            if (multiInsertQueue.isEmpty()) return false
            val inst = currentInstance ?: run {
                clearMultiInsert("no toolbar instance")
                return false
            }
            val next = multiInsertQueue.removeFirst()
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "multi insert: $reason → next=$next remaining=${multiInsertQueue.size}")
            }
            if (multiInsertDocLinks) inst.insertMultiDocLink(next) else inst.insertMultiImage(next)
            return true
        }

        @JvmStatic
        private fun clearMultiInsert(reason: String) {
            activeDocLinkRequest = null
            multiInsertDocLinks = false
            docLinkTargetFile = ""
            docLinkTargetPage = -1
            if (multiInsertQueue.isEmpty() && !multiInsertAwaitingSelection) return
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "multi insert cleared: $reason remaining=${multiInsertQueue.size}")
            }
            multiInsertQueue.clear()
            multiInsertAwaitingSelection = false
            monitorHandler.removeCallbacks(multiInsertAwaitTimeout)
        }

        
        @Volatile @JvmStatic
        private var paletteApplyInFlight = false

        
        @Volatile @JvmStatic
        private var paletteSelectionHold = false

        @Volatile private var paletteOverlaysParked = false
        @Volatile private var regionCaptureActive = false

        @JvmStatic fun areFloatingOverlaysSuspendedForPalette(): Boolean =
            paletteApplyInFlight || paletteSelectionHold || paletteOverlaysParked || regionCaptureActive

        
        private const val PALETTE_SELECTION_HOLD_MAX_MS = 120_000L

        @JvmStatic
        private val paletteSelectionHoldTimeoutRunnable = Runnable {
            if (!paletteSelectionHold) return@Runnable
            paletteSelectionHold = false
            Log.w(TAG, "palette selection hold timed out; restoring toolbar")
            HostPenSignals.releaseDocLassoHold("palette hold timeout")
            currentInstance?.restoreToolbar()
        }

        
        @JvmStatic
        private fun setPaletteSelectionHold(hold: Boolean) {
            paletteSelectionHold = hold
            monitorHandler.removeCallbacks(paletteSelectionHoldTimeoutRunnable)
            if (hold) {
                monitorHandler.postDelayed(
                    paletteSelectionHoldTimeoutRunnable,
                    PALETTE_SELECTION_HOLD_MAX_MS,
                )
            }
        }

        
        @JvmStatic
        fun onNoteSubviewChanged(open: Boolean, generation: Int) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onNoteSubviewChanged: open=$open gen=$generation")
            if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
            val inst = currentInstance ?: return
            val applyState = Runnable {
                
                if (!SubviewLogMonitor.isCurrentGeneration(generation) ||
                    SubviewLogMonitor.isSubviewOpen() != open) return@Runnable
                
                
                
                if (open) {
                    markToolbarForRestore()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "note subview opened, hiding overlays (toolbarRestorePending=$toolbarRestorePending)")
                    
                    
                    SubviewLogMonitor.closeLassoMenuFromFallback("subview opened")
                    FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.NOTE_SUBVIEW)
                    if (rootView != null) inst.removeAll()
                    inst.suspendAllNativePanels()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                    ScreenshotBubble.hideForSettings()
                } else {
                    FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.NOTE_SUBVIEW)

                    
                    
                    
                    if (!isPluginHostWindowShowing && ScreenshotBubble.hiddenBySettings) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "note subviews closed, restoring ScreenshotBubble independently")
                        ScreenshotBubble.reshowIfHiddenBySettings()
                    }

                    scheduleRestore(RESTORE_DELAY_NORMAL, "note subview closed")
                    DocChromeProbe.request("subview closed")
                    
                    
                    
                    HostPenSignals.scheduleReassert(
                        reason = "note subview restored",
                        allowEmptyAtSchedule = true
                    )
                }
            }
            if (open) monitorHandler.post(applyState)
            else monitorHandler.postDelayed(applyState, RESTORE_DELAY_NORMAL)
        }

        
        

        @JvmStatic
        private fun handleToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleToolbarMenuChanged: open=$open pluginList=$pluginListMenu gen=$generation isCurrent=${SubviewLogMonitor.isCurrentGeneration(generation)}")
            if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
            if (open) {
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation) ||
                        !SubviewLogMonitor.isToolbarMenuOpen()) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu open: state stale, skip")
                        return@post
                    }
                    val inst = currentInstance ?: return@post
                    val toolbarWasRunning = rootView != null || toolbarRestorePending
                    if (pluginListMenu && toolbarWasRunning && hostButtonChannelBroken) {
                        terminateAfterPluginMenuClose = true
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin list menu open: arm Inkling termination on close (button channel broken)")
                    }
                    markToolbarForRestore()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu open: hiding overlays toolbarRestorePending=$toolbarRestorePending")
                    
                    
                    FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.HOST_TOOLBAR_MENU)
                    if (isDocumentContext()) DocChromeProbe.request("document toolbar menu opened")
                    if (rootView != null) inst.removeAll()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                }
            } else {
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation) ||
                        SubviewLogMonitor.isToolbarMenuOpen()) return@post
                    
                    
                    FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_TOOLBAR_MENU)
                    DocChromeProbe.request("toolbar menu closed")
                }
                monitorHandler.postDelayed({
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu close: generation stale, skip")
                        return@postDelayed
                    }
                    if (SubviewLogMonitor.isToolbarMenuOpen()) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu close: still open (SubviewLogMonitor), skip restore")
                        return@postDelayed
                    }
                    val inst = currentInstance ?: return@postDelayed
                    if (terminateAfterPluginMenuClose) {
                        terminateAfterPluginMenuClose = false
                        toolbarRestorePending = false
                        cancelScheduledRestore()
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin list menu close: terminating Inkling")
                        
                        
                        inst.destroyAll()
                        return@postDelayed
                    }
                    
                    
                    scheduleRestore(RESTORE_DELAY_SHORT, "toolbar menu closed")
                }, RESTORE_DELAY_LONG)
            }
        }

        
        @JvmStatic
        private fun onNativePanelActiveEdge(anyShowing: Boolean) {
            val inst = currentInstance ?: return
            if (anyShowing) {
                FloatingBubbleModule.hideStatic()
                AiBubbleModule.hideStatic()
                PaletteBubbleModule.hideStatic()
            } else {
                monitorHandler.postDelayed({
                    if (ToolRegistry.anyPanelShowing()) return@postDelayed
                    evaluateRestore("native panel closed")
                }, RESTORE_DELAY_NORMAL)
            }
        }

        
        @JvmStatic
        internal fun anyNativeOverlayOwnsScreen(): Boolean =
            ToolRegistry.anyPanelShowing() || penLassoOverlay != null || regionCaptureActive

        private val configFallbackRunnable = Runnable {
            val inst = currentInstance ?: return@Runnable
            if (configPanelBlocksToolbar()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: config panel active, skip")
                return@Runnable
            }
            
            
            
            if (isSelfInitiatedHostShow() || pendingScreen.isNotEmpty() ||
                SendPanel.currentInstance?.isShowing == true) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                    "configFallback: plugin-purpose session active (pendingScreen=$pendingScreen), skip")
                return@Runnable
            }
            val hostSnapshot = readHostEntrySnapshot(inst.reactApplicationContext)
            if (hostSnapshot?.opensConfig(isSelfInitiatedHostShow()) != true) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: entry not owned config $hostSnapshot")
                return@Runnable
            }
            if (ConfigPanel.currentInstance?.isActuallyVisible == true) {
                configPanelActive = true
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: ConfigPanel already visible, keep it")
                return@Runnable
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: lastShowType=1 → opening ConfigPanel from native fallback")
            inst.removeAll()
            inst.emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "config") })
            ConfigPanel.getInstance(inst.reactApplicationContext, inst).show()
            inst.closeHostPluginView()
        }

        @JvmStatic
        private fun readHostEntrySnapshot(ctx: ReactApplicationContext): HostEntrySnapshot? {
            return try {
                val pm = ctx.catalystInstance?.getNativeModule("NativePluginManager") ?: return null
                val paField = findDeclaredFieldStatic(pm.javaClass, "pluginApp") ?: return null
                paField.isAccessible = true
                val pa = paField.get(pm) ?: return null

                HostEntrySnapshot.read(pa)
            } catch (_: Throwable) {
                null
            }
        }

        @JvmStatic
        private fun findDeclaredFieldStatic(cls: Class<*>, name: String): java.lang.reflect.Field? {
            var c: Class<*>? = cls
            while (c != null) {
                c.declaredFields.firstOrNull { it.name == name }?.let { return it }
                c = c.superclass
            }
            return null
        }

        @JvmStatic
        fun onPluginHostStateChanged(showing: Boolean) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onPluginHostStateChanged: showing=$showing")
            isPluginHostWindowShowing = showing
            syncPluginHostGuard()

            val inst = currentInstance
            if (inst == null) {
                
                
                
                if (rootView == null && !anyNativeOverlayOwnsScreen() && showing) {
                    FloatingPenGuard.reassert("host visible, module unavailable")
                }
                
                
                
                
                if (!showing && toolbarRestorePending) {
                    scheduleInklingRestoreBootstrap("PluginHost hidden without active module")
                } else if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "onPluginHostStateChanged: module unavailable showing=$showing " +
                        "pendingRestore=$toolbarRestorePending")
                }
                return
            }
            if (showing) {
                val hostEntry = readHostEntrySnapshot(inst.reactApplicationContext)
                val ownsHost = hostEntry?.isCurrentOwner == true
                Log.i(TAG, "[HOST-ENTRY] visible ownerMatch=$ownsHost snapshot=$hostEntry")
                if (ownsHost && configPanelBlocksToolbar()) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onPluginHostStateChanged: config panel active, close host")
                    inst.closeHostPluginView()
                    return
                }
                
                
                
                val toolbarOwnsHost = ownsHost && toolbarShowOwnsHostEntry()
                val selfInitiated = ownsHost &&
                    (toolbarOwnsHost || isSelfInitiatedHostShow() || pendingScreen.isNotEmpty())
                if (!ownsHost) {
                    
                    ToolRegistry.suspendAll()
                }
                val rootAtShowEdge = rootView?.takeIf { it.isAttachedToWindow }
                if (rootAtShowEdge != null && !toolbarOwnsHost) {
                    markToolbarForRestore()
                    monitorHandler.post {
                        
                        
                        
                        if (currentInstance !== inst || !isPluginHostWindowShowing ||
                            rootView !== rootAtShowEdge) {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "host-visible removal stale, keep current toolbar")
                            }
                            return@post
                        }
                        inst.removeAll()
                        if (!selfInitiated) inst.suspendAllNativePanels()
                        FloatingBubbleModule.hideStatic()
                        AiBubbleModule.hideStatic()
                        PaletteBubbleModule.hideStatic()
                    }
                }
                monitorHandler.removeCallbacks(configFallbackRunnable)
                if (ownsHost && !selfInitiated) {
                    monitorHandler.postDelayed(configFallbackRunnable, 200)
                } else if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "onPluginHostStateChanged: toolbar/plugin-owned show " +
                        "(pendingScreen=$pendingScreen toolbarOwnsHost=$toolbarOwnsHost), " +
                        "skip panel suspend + config fallback")
                }
            } else {
                monitorHandler.removeCallbacks(configFallbackRunnable)
                scheduleRestore(RESTORE_DELAY_SHORT, "PluginHost hidden")
            }
        }

        @JvmStatic
        internal fun onMosaicVisibilityChanged(
            visible: Boolean,
            surface: String = MosaicLink.SURFACE_BOARD,
            noteRef: String? = null,
        ) {
            val nextSurface = surface.ifBlank { MosaicLink.SURFACE_BOARD }
            val stateChanged = mosaicMode != visible ||
                mosaicSurface != nextSurface ||
                mosaicNoteRef != noteRef
            if (!stateChanged && rootView != null) return
            if (!visible && !mosaicMode) {
                
                
                mosaicSurface = nextSurface
                mosaicNoteRef = noteRef
                return
            }
            
            surfaceBubbleRaisePending = visible && (surfaceBubbleRaisePending || !mosaicMode)
            mosaicMode = visible
            syncDocumentTextSelectionGuard()
            syncPluginHostGuard()
            mosaicSurface = nextSurface
            mosaicNoteRef = noteRef
            if (visible && nextSurface == MosaicLink.SURFACE_BOARD) {
                
                
                
                
                
                toolbarRestorePending = false
                cancelScheduledRestore()
                
                
                HostPenSignals.cancelLassoMenuRelease()
                HostPenSignals.clearLassoWritableReset()
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_TOOLBAR_MENU)
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_LASSO_MENU)
                FloatingPenGuard.endPark("Mosaic board visible")
            }
            emitMosaicBoardVisible(visible, nextSurface, noteRef)
            DocChromeProbe.request("mosaic visibility")
            val inst = currentInstance
            if (inst == null) {
                pendingMosaicRebuild = true
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "onMosaicVisibilityChanged: module unavailable visible=$visible " +
                        "surface=$nextSurface, rebuild pending")
                }
                return
            }
            pendingMosaicRebuild = false
            inst.scheduleMosaicRebuild(visible, nextSurface, "board state")
        }

        
        @JvmStatic
        internal fun onMosaicChromeMenuChanged(open: Boolean) {
            monitorHandler.post {
                if (MosaicLink.isChromeMenuOpen() != open) return@post
                val inst = currentInstance ?: return@post
                if (open) {
                    cancelScheduledRestore()
                    markToolbarForRestore()
                    if (rootView != null) inst.removeAll()
                    hideSurfaceBubbles()
                    
                    ScreenshotBubble.hideForPalette()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Mosaic menu open: overlays hidden")
                } else {
                    scheduleRestore(RESTORE_DELAY_SHORT, "mosaic menu closed")
                }
            }
        }

        @JvmStatic
        private fun hideSurfaceBubbles() {
            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()
            PaletteBubbleModule.hideStatic()
        }

        @JvmStatic
        private fun emitMosaicBoardVisible(visible: Boolean, surface: String, noteRef: String?) {
            val ctx = currentInstance?.reactApplicationContext ?: lastReactContext ?: return
            try {
                ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onMosaicBoardVisible", Arguments.createMap().apply {
                        putBoolean("visible", visible)
                        putString("surface", surface)
                        if (noteRef == null) putNull("noteRef") else putString("noteRef", noteRef)
                    })
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                    "onMosaicBoardVisible emitted visible=$visible surface=$surface noteRef=$noteRef")
            } catch (e: Exception) {
                Log.w(TAG, "emit onMosaicBoardVisible: ${e.message}")
            }
        }

        
        @JvmStatic
        private fun recreateSurfaceBubbles(reason: String, raise: Boolean) {
            val inst = currentInstance ?: return
            if (anyNativeOverlayOwnsScreen() || MosaicLink.isChromeMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "recreateSurfaceBubbles[$reason]: skipped, overlay or Mosaic menu owns screen")
                }
                return
            }
            if (!isInNoteApp) {
                hideSurfaceBubbles()
                return
            }
            val ctx = inst.reactApplicationContext
            FloatingBubbleModule.reshowLast(ctx, force = raise)
            AiBubbleModule.reshowLast(ctx, force = raise)
            PaletteBubbleModule.reshowLast(ctx, force = raise)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "recreateSurfaceBubbles[$reason]: queued raise=$raise")
        }

        @JvmStatic
        private val deniedPluginPermissions: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        private const val NOTE_PACKAGE = "com.ratta.supernote.note"
        private const val NOTE_INSIDE_PAGES_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity"
        private const val PLUGIN_PACKAGE = "com.ratta.supernote.pluginhost"
        private const val DOC_PACKAGE = "com.supernote.document"
        private const val WEREAD_PACKAGE = "com.tencent.weread.eink"

        private const val SETTINGS_PACKAGE = "com.ratta.settings"
        private const val SETTINGS_ACTIVITY = "com.ratta.settings.SettingsActivity"
        private const val SETTINGS_PLUGIN_DETAIL_ACTION = "com.ratta.settings.application.PluginDetailFragment"
        private const val PLUGIN_ID = "Inkling"
        private const val MONITOR_INTERVAL_MS = 800L

        
        
        private const val RESTORE_DELAY_SHORT = 150L
        
        private const val RESTORE_DELAY_NORMAL = 350L
        
        private const val RESTORE_DELAY_LONG = 700L
        
        private const val TOOLBAR_HOST_CLOSE_DELAY_MS = 150L

        

        
        internal const val CLIP_SLOT_COUNT = 8
        
        private const val MAX_VISIBLE_CLIPS = 6

        @Volatile @JvmStatic
        private var titleClipFilled: BooleanArray = BooleanArray(CLIP_SLOT_COUNT) { false }

        @Volatile @JvmStatic
        private var clipPage: Int = 0

        @Volatile @JvmStatic
        private var orientation: String = "vertical"
        private const val ORIENTATION_STORE_KEY = "preset_97"

        @Volatile @JvmStatic private var stickyX: Int = -1
        @Volatile @JvmStatic private var stickyY: Int = -1
        @Volatile @JvmStatic private var preDockX: Int = -1
        @Volatile @JvmStatic private var preDockY: Int = -1
        @Volatile @JvmStatic private var positionLoaded: Boolean = false
        private const val POSITION_STORE_KEY_X = "toolbar_pos_x"
        private const val POSITION_STORE_KEY_Y = "toolbar_pos_y"

        @Volatile @JvmStatic
        private var wasBubbleVisible = false

        @Volatile @JvmStatic
        private var insertNextChainActive = false

        
        @Volatile @JvmStatic
        private var docScreenshotRoutePending = false

        @Volatile @JvmStatic
        private var stitchCommitPending = false

        @Volatile @JvmStatic
        private var screenshotCapturePending = false

        @Volatile @JvmStatic
        private var knownStitchSessionActive = false

        @Volatile @JvmStatic
        private var penLassoOverlay: PenLassoOverlay? = null

        @Volatile @JvmStatic
        private var rotationEpoch = 0L
        @Volatile @JvmStatic
        private var rotationStartForegroundKey: String? = null
        @Volatile @JvmStatic
        private var rotationDialogArmed = false
        @Volatile @JvmStatic
        private var rotationDialogShown = false

        @JvmStatic
        private val rotationDisplaySettledRunnable = Runnable {
            currentInstance?.onRotationDisplaySettled(rotationEpoch)
        }

        @JvmStatic
        private val rotationDialogRetryRunnable = Runnable {
            currentInstance?.showRotationDialogWhenReady(rotationEpoch)
        }

        @JvmStatic
        private val monitorHandler = Handler(Looper.getMainLooper())

        @Volatile @JvmStatic
        internal var currentInstance: FloatingToolbarModule? = null

        
        @Volatile @JvmStatic
        private var lastReactContext: ReactApplicationContext? = null

        
        @Volatile @JvmStatic
        private var restoreBootstrapPending = false
        @JvmStatic
        private var restoreBootstrapRunnable: Runnable? = null

        @JvmStatic
        private val staticMonitorRunnable = object : Runnable {
            override fun run() {
                if (!foregroundMonitorRunning) return
                val inst = currentInstance ?: return
                inst.runMonitorTick()
                monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }

        

        
        @JvmStatic
        private fun scheduleRestore(delay: Long, reason: String) {
            pendingRestoreRunnable?.let { monitorHandler.removeCallbacks(it) }
            val r = Runnable { pendingRestoreRunnable = null; evaluateRestore(reason) }
            pendingRestoreRunnable = r
            monitorHandler.postDelayed(r, delay)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scheduleRestore: delay=${delay}ms reason=$reason")
        }

        
        @JvmStatic
        private fun scheduleInklingRestoreBootstrap(reason: String) {
            if (restoreBootstrapPending || currentInstance != null ||
                !toolbarRestorePending || isPluginHostWindowShowing) return
            val context = lastReactContext ?: run {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "restore bootstrap deferred: no bridge context reason=$reason")
                }
                return
            }
            restoreBootstrapPending = true
            restoreBootstrapRunnable?.let { monitorHandler.removeCallbacks(it) }
            val task = Runnable {
                restoreBootstrapRunnable = null
                if (currentInstance != null || !toolbarRestorePending ||
                    isPluginHostWindowShowing || !isInNoteApp) {
                    restoreBootstrapPending = false
                    return@Runnable
                }
                val opened = showInklingHostForRestore(context)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "restore bootstrap reason=$reason opened=$opened " +
                        "pendingRestore=$toolbarRestorePending")
                }
                if (!opened) restoreBootstrapPending = false
            }
            restoreBootstrapRunnable = task
            monitorHandler.postDelayed(task, RESTORE_DELAY_SHORT)
        }

        
        @JvmStatic
        private fun showInklingHostForRestore(context: ReactApplicationContext): Boolean {
            val loader = context.classLoader
            try {
                val managerClass = Class.forName(HOST_PLUGIN_MANAGER_CLASS, false, loader)
                val manager = managerClass.methods.firstOrNull {
                    it.name == "getInstance" && it.parameterCount == 0
                }?.invoke(null)
                val getPluginApp = managerClass.methods.firstOrNull {
                    it.name == "getPluginApp" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                val pluginApp = if (manager != null && getPluginApp != null) {
                    getPluginApp.invoke(manager, INKLING_PLUGIN_ID)
                } else null
                if (pluginApp != null) {
                    val directShow = pluginApp.javaClass.methods.firstOrNull {
                        it.name == "showPluginView" && it.parameterCount == 1 &&
                            (it.parameterTypes[0] == Integer.TYPE ||
                                it.parameterTypes[0] == Integer::class.java)
                    }
                    if (directShow != null) {
                        directShow.invoke(pluginApp, 0)
                        if (BuildConfig.ENABLE_DEBUG) {
                            Log.i(TAG, "Inkling restore via PluginApp.showPluginView(0)")
                        }
                        return true
                    }
                }
                val managerShow = managerClass.methods.firstOrNull {
                    it.name == "showPluginView" && it.parameterCount == 2 &&
                        it.parameterTypes[0].isAssignableFrom(String::class.java) &&
                        (it.parameterTypes[1] == Integer.TYPE ||
                            it.parameterTypes[1] == Integer::class.java)
                }
                if (manager != null && managerShow != null) {
                    managerShow.invoke(manager, INKLING_PLUGIN_ID, 0)
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "Inkling restore via PluginManager.showPluginView(0)")
                    }
                    return true
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "Inkling restore manager route unavailable: ${e.message}")
                }
            }

            return try {
                val eventClass = Class.forName(
                    "com.ratta.supernote.pluginhost.eventbus.PluginShowEvent", false, loader)
                val event = eventClass.getConstructor(String::class.java, Integer.TYPE)
                    .newInstance(INKLING_PLUGIN_ID, 0)
                val eventBusClass = Class.forName("org.greenrobot.eventbus.EventBus", false, loader)
                val eventBus = eventBusClass.getMethod("getDefault").invoke(null)
                eventBusClass.getMethod("post", Any::class.java).invoke(eventBus, event)
                true
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "Inkling restore event route unavailable: ${e.message}")
                }
                false
            }
        }

        
        @JvmStatic
        private fun cancelScheduledRestore() {
            pendingRestoreRunnable?.let { monitorHandler.removeCallbacks(it) }
            pendingRestoreRunnable = null
        }

        
        @JvmStatic
        private fun evaluateRestore(reason: String) {
            val inst = currentInstance ?: return
            if (regionCaptureActive) return
            if (configPanelBlocksToolbar()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: config panel active")
                return
            }

            
            if (!isInNoteApp) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, not in note")
                return
            }
            if (SubviewLogMonitor.isSubviewOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, subview open")
                return
            }
            if (SubviewLogMonitor.isToolbarMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, toolbar menu open")
                return
            }
            
            
            
            
            if (paletteApplyInFlight || paletteSelectionHold) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, palette selection held")
                return
            }
            if (MosaicLink.isChromeMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, Mosaic menu open")
                return
            }
            
            
            if (mosaicMode && MosaicLink.isBoardVisible()) {
                paletteOverlaysParked = false
                inst.resumeAllNativePanels()
                if (!anyNativeOverlayOwnsScreen()) {
                    if (rootView == null) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: rebuilding mosaic toolbar")
                        inst.rebuildForMosaic(true)
                    } else {
                        recreateSurfaceBubbles("restore evaluated[$reason] (mosaic)", raise = false)
                    }
                    ScreenshotBubble.reshowIfHiddenByPalette()
                }
                toolbarRestorePending = false
                FloatingPenGuard.endPark("restore evaluated[$reason] (mosaic)")
                return
            }
            if (isPluginHostWindowShowing) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, PluginHost showing")
                return
            }
            if (docScreenshotRoutePending) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, doc screenshot route pending")
                return
            }
            
            
            if (HostPenSignals.insertImageFlowActive) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, insert-image in flight")
                return
            }
            if (SubviewLogMonitor.isLassoMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, lasso menu open")
                return
            }

            
            paletteOverlaysParked = false
            inst.resumeAllNativePanels()
            val anyPanelOpen = anyNativeOverlayOwnsScreen()

            
            
            if (!anyPanelOpen) {
                if (toolbarRestorePending && rootView == null && tools.isNotEmpty()) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: restoring toolbar (collapsed=$collapsed)")
                    inst.restoreToolbarWindow()
                }
                toolbarRestorePending = false
            }

            if (!anyPanelOpen) {
                val ctx = inst.reactApplicationContext
                FloatingBubbleModule.reshowLast(ctx)
                AiBubbleModule.reshowLast(ctx)
                PaletteBubbleModule.reshowLast(ctx)
                ScreenshotBubble.reshowIfHiddenByPalette()
                
                
                FloatingPenGuard.endPark("restore evaluated[$reason]")
            }


            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: done anyPanel=$anyPanelOpen")
        }

        
        @JvmStatic
        private fun markToolbarForRestore(parkGuards: Boolean = true) {
            if (parkGuards) FloatingPenGuard.beginPark("hide for restore")
            toolbarRestorePending = toolbarRestorePending || rootView != null || toolbarSessionActive
        }
    }

    init {
        
        
        
        if (ConfigPanel.currentInstance?.isActuallyVisible != true) {
            ConfigPanel.currentInstance = null
            resetConfigPanelState()
        }
        
        
        
        
        if ((rootView == null || !rootView!!.isAttachedToWindow) &&
            !toolbarRestorePending) {
            toolbarRestorePending = false
            toolbarHostClosePending = false
            toolbarHostCloseGeneration = -1L
        }
        lastReactContext = reactApplicationContext
        MosaicLink.ensureRegistered(reactApplicationContext)
        restoreBootstrapPending = false
        restoreBootstrapRunnable?.let { monitorHandler.removeCallbacks(it) }
        restoreBootstrapRunnable = null
        currentInstance = this
        
        
        
        
        val previousMonitor = windowStateMonitor
        if (previousMonitor == null || !previousMonitor.isFor(reactApplicationContext)) {
            previousMonitor?.stop()
            windowStateMonitor = WindowStateMonitor(reactApplicationContext)
        }
        windowStateMonitor?.start()
        
        ToolRegistry.setPanelActiveEdgeListener { anyShowing -> onNativePanelActiveEdge(anyShowing) }
        knownStitchSessionActive = reactApplicationContext
            .getSharedPreferences(SCREENSHOT_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(STITCH_SESSION_ACTIVE_KEY, knownStitchSessionActive)
        if (!configCallbackRegistered) {
            configCallbackRegistered = true
            reactApplicationContext.applicationContext.registerComponentCallbacks(
                object : android.content.ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        Handler(Looper.getMainLooper()).post {
                            currentInstance?.handleOrientationChange("configuration")
                        }
                    }
                    override fun onLowMemory() {}
                    override fun onTrimMemory(level: Int) {}
                }
            )
        }
        if (!displayListenerRegistered) {
            try {
                val displayManager = reactApplicationContext.applicationContext
                    .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                displayManager.registerDisplayListener(
                    object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}
                        override fun onDisplayRemoved(displayId: Int) {}
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                                Handler(Looper.getMainLooper()).post {
                                    currentInstance?.handleOrientationChange("display-listener")
                                }
                            }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                displayListenerRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "display listener registration failed: ${e.message}")
            }
        }
        Handler(Looper.getMainLooper()).post {
            if (currentInstance === this) {
                handleOrientationChange("module-init")
                
                
                
                if (toolbarRestorePending && !isPluginHostWindowShowing) {
                    scheduleRestore(RESTORE_DELAY_SHORT, "module init pending toolbar restore")
                }
                if (pendingMosaicRebuild) {
                    pendingMosaicRebuild = false
                    val visible = mosaicMode
                    scheduleMosaicRebuild(visible, mosaicSurface, "module init")
                }
            }
        }
    }

    override fun invalidate() {
        if (currentInstance === this) {
            cancelRotationSync("module invalidated")
            toolbarBuildGeneration += 1L
            pendingShow = false
            cancelHostCloseIntent()
            toolbarRetryRunnable?.let(handler::removeCallbacks)
            toolbarRetryRunnable = null
            currentInstance = null
            foregroundMonitorRunning = false
            monitorHandler.removeCallbacks(staticMonitorRunnable)
            mosaicRebuildRunnable?.let(monitorHandler::removeCallbacks)
            mosaicRebuildRunnable = null
            HostPenSignals.releaseDocStickerHold("module invalidated")
            
            
            
            if (ConfigPanel.currentInstance?.isActuallyVisible != true) {
                ConfigPanel.currentInstance = null
                resetConfigPanelState()
            }
        }
        super.invalidate()
    }

    
    private fun scheduleMosaicRebuild(visible: Boolean, surface: String, reason: String) {
        mosaicRebuildRunnable?.let(monitorHandler::removeCallbacks)
        var deferrals = 0
        val task = object : Runnable {
            override fun run() {
                if (currentInstance !== this@FloatingToolbarModule ||
                    mosaicMode != visible || mosaicSurface != surface) {
                    return
                }
                
                
                
                
                val forceBoardRebuild = visible && surface == MosaicLink.SURFACE_BOARD && deferrals >= 8
                if (isToolbarHandoffPending(visible) && !forceBoardRebuild) {
                    deferrals++
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "Mosaic toolbar rebuild deferred reason=$reason " +
                            "pendingShow=$pendingShow hostClose=${toolbarShowOwnsHostEntry()} " +
                            "hostCloseRunnable=${hostCloseRunnable != null} " +
                            "restorePending=$toolbarRestorePending mosaicMode=$mosaicMode attempt=$deferrals")
                    }
                    monitorHandler.postDelayed(this, RESTORE_DELAY_SHORT)
                    return
                }
                if (forceBoardRebuild && BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "Mosaic toolbar rebuild forcing board ownership after $deferrals deferrals")
                }
                mosaicRebuildRunnable = null
                rebuildForMosaic(visible)
                FloatingPenGuard.republishMosaicSnapshot("toolbar rebuild: $reason")
            }
        }
        mosaicRebuildRunnable = task
        monitorHandler.postDelayed(task, RESTORE_DELAY_SHORT)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "Mosaic toolbar rebuild scheduled delay=${RESTORE_DELAY_SHORT}ms " +
                "visible=$visible surface=$surface reason=$reason")
        }
    }

    private fun isToolbarHandoffPending(visible: Boolean): Boolean =
        pendingShow || toolbarShowOwnsHostEntry() || hostCloseRunnable != null ||
            (visible && toolbarRestorePending && !mosaicMode)

    private val handler = Handler(Looper.getMainLooper())

    
    private var toolbarRetryRunnable: Runnable? = null

    
    private var hostCloseRunnable: Runnable? = null

    private fun cancelScheduledHostClose() {
        hostCloseRunnable?.let(handler::removeCallbacks)
        hostCloseRunnable = null
    }

    private fun cancelHostCloseIntent() {
        cancelScheduledHostClose()
        toolbarHostClosePending = false
        toolbarHostCloseGeneration = -1L
    }

    
    private fun armHostCloseForToolbarShow() {
        cancelScheduledHostClose()
        toolbarHostClosePending = true
        toolbarHostCloseGeneration = toolbarBuildGeneration
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "toolbar host hand-off armed generation=$toolbarBuildGeneration")
        }
    }

    
    private fun scheduleHostCloseAfterToolbarShown() {
        if (!toolbarShowOwnsHostEntry()) return
        val generation = toolbarBuildGeneration
        cancelScheduledHostClose()
        val close = Runnable {
            hostCloseRunnable = null
            if (currentInstance !== this || !toolbarShowOwnsHostEntry() ||
                toolbarBuildGeneration != generation) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "toolbar host hand-off stale generation=$generation")
                }
                return@Runnable
            }
            val root = rootView
            if (root == null || !root.isAttachedToWindow) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "toolbar host hand-off deferred: root not attached")
                }
                return@Runnable
            }
            toolbarHostClosePending = false
            toolbarHostCloseGeneration = -1L
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "toolbar host hand-off closing host generation=$generation")
            }
            closeHostPluginView()
        }
        hostCloseRunnable = close
        handler.postDelayed(close, TOOLBAR_HOST_CLOSE_DELAY_MS)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "toolbar host hand-off scheduled delayMs=$TOOLBAR_HOST_CLOSE_DELAY_MS generation=$generation")
        }
    }

    private fun scheduleToolbarRetry(
        reason: String,
        delayMs: Long = 350L,
        rebuildExistingRoot: Boolean = false,
    ) {
        toolbarRetryRunnable?.let(handler::removeCallbacks)
        val generation = toolbarBuildGeneration
        val retry = Runnable {
            toolbarRetryRunnable = null
            val rootAttached = rootView?.isAttachedToWindow == true
            if (generation != toolbarBuildGeneration || currentInstance !== this ||
                (!rebuildExistingRoot && rootAttached) || !pendingShow || !isInNoteApp) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "toolbar retry cancelled reason=$reason generation=$generation " +
                        "current=$toolbarBuildGeneration root=${rootView != null} pending=$pendingShow " +
                        "inNote=$isInNoteApp")
                }
                return@Runnable
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar retry running reason=$reason")
            if (rebuildExistingRoot && rootAttached) rebuildButtons()
            else createExpandedToolbar()
        }
        toolbarRetryRunnable = retry
        handler.postDelayed(retry, delayMs)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "toolbar retry scheduled reason=$reason delayMs=$delayMs generation=$generation")
        }
    }

    
    private fun clearToolbarBuildForRetry() {
        val attachedRoot = rootView
        if (attachedRoot != null) {
            try { FloatingDragRefresh.endNow(attachedRoot) } catch (_: Throwable) {}
            try { windowManager?.removeView(attachedRoot) } catch (_: Throwable) {}
        }
        rootView = null
        expandedRoot = null
        toolContainer = null
        collapsedRoot = null
        layoutParams = null
        textReceiveBtn = null
        paletteSidebarBtn = null
        clipIconViews = arrayOfNulls(MAX_VISIBLE_CLIPS)
        pagedClipTools.clear()
        pendingShow = true
    }

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        hideAllNativePanels()
        removeAll()
        ConfigPanel.getInstance(reactApplicationContext, this).show()
    }

    private val BTN_SIZE_DP = 54
    private val VERTICAL_BTN_SIZE_DP = 48
    
    private val TOOLBAR_ICON_STROKE_DELTA_PX = -1f
    private val BTN_GAP_DP = 2
    private val PANEL_PAD_DP = 8
    private val BORDER_WIDTH = 2
    private val TITLE_ROW_H_DP = 38
    private val TITLE_SEP_DP = 1
    private val CORNER_RADIUS_DP = 0f
    private val BTN_TEXT_SIZE_SP = 22f
    private val TITLE_TEXT_SIZE_SP = 13f

    private val CLR_BTN_FG   = Color.parseColor("#1A1A1A")
    private val CLR_BTN_ACT  = Color.parseColor("#111111")
    private val CLR_SIDEBAR_BG = Color.WHITE
    private val CLR_SEP      = me.laumss.notipal.ui_common.FloatingChromeColors.dividerAndGrip
    private val CLR_BORDER   = Color.parseColor("#111111")

    private val SIDE_INDICATOR_DP = 4
    private val HANDLE_WIDTH_DP = 4
    
    private val COLLAPSED_WIDTH_DP = 16
    private val COLLAPSED_HIT_WIDTH_DP = 24
    private val COLLAPSED_HEIGHT_DP = 72

    private val SNAP_THRESHOLD = 40
    
    
    private val EDGE_COLLAPSE_THRESHOLD = 20
    private val LONG_PRESS_MS = 600L

    data class ToolItem(
        val id: String,
        val storedName: String,
        val icon: String,
        val action: String,
        val latches: Boolean = false,
        val nameKey: String = ""
    ) {
        val name: String
            get() {
                val key = nameKey.ifEmpty { defaultToolNameKey(id) }
                if (key.isNotEmpty()) {
                    val translated = NativeLocale.t(key)
                    if (translated != key) return translated
                }
                return storedName
            }
    }

    private val CLIP_ICON_DP = 32
    
    private val CLIP_RADIUS_DP = 3
    private val LAYER_BTN_DP = 20
    private var clipIconViews: Array<TextView?> = arrayOfNulls(MAX_VISIBLE_CLIPS)
    private val pagedClipTools = mutableListOf<View>()
    private var textReceiveBtn: View? = null
    
    private var paletteSidebarBtn: View? = null
    
    private var noteImportBusy = false
    
    private var collapseBtn: View? = null

    @ReactMethod
    fun setOrientation(value: String) {
        handler.post {
            val normalized = if (value == "vertical") "vertical" else "horizontal"
            if (normalized == orientation) return@post
            orientation = normalized
            try {
                reactApplicationContext
                    .getSharedPreferences("quicktoolbar_presets", 0)
                    .edit().putString(ORIENTATION_STORE_KEY, normalized).apply()
            } catch (_: Exception) {}
            if (rootView != null && !collapsed) {
                removeAll()
                createExpandedToolbar()
            }
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getOrientationSync(): String {
        loadOrientationFromPrefs()
        return orientation
    }

    private fun loadOrientationFromPrefs() {
        orientation = "vertical"
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            prefs.edit().putString(ORIENTATION_STORE_KEY, "vertical").apply()
        } catch (_: Exception) {}
    }

    private fun loadPositionFromPrefs() {
        if (positionLoaded) return
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            stickyX = prefs.getInt(POSITION_STORE_KEY_X, -1)
            stickyY = prefs.getInt(POSITION_STORE_KEY_Y, -1)
        } catch (_: Exception) {}
        positionLoaded = true
    }

    
    private fun refreshCollapseIconSide() {
        val btn = collapseBtn ?: return
        val lp = layoutParams ?: return
        val vw = expandedRoot?.width?.takeIf { it > 0 } ?: expandedRoot?.measuredWidth ?: 0
        btn.scaleX = if (lp.x + vw / 2 <= screenWidth / 2) -1f else 1f
    }

    private fun savePositionToPrefs(x: Int, y: Int) {
        stickyX = x; stickyY = y; positionLoaded = true
        refreshCollapseIconSide()
        try {
            reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
                .edit().putInt(POSITION_STORE_KEY_X, x).putInt(POSITION_STORE_KEY_Y, y).apply()
        } catch (_: Exception) {}
    }

    @ReactMethod
    fun hide() {
        handler.post { try { removeAll() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    
    @ReactMethod
    fun toggleFromPluginButton(mode: String?) {
        handler.post {
            
            
            monitorHandler.removeCallbacks(configFallbackRunnable)
            val requestedDocMode = requestedToolbarDocMode(mode)
            val attachedRoot = rootView?.takeIf { it.isAttachedToWindow }
            
            
            if (rootView != null && attachedRoot == null) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "toggleFromPluginButton: discard detached root")
                }
                removeAll()
            }


            val active = attachedRoot != null || toolbarRestorePending
            Log.i(TAG, "toggleFromPluginButton active=$active requestedDocMode=$requestedDocMode " +
                "rootAttached=${attachedRoot != null} pendingShow=$pendingShow " +
                "toolbarRestorePending=$toolbarRestorePending")
            if (active) {
                if (docMode != requestedDocMode) {
                    showToolbarNow(requestedDocMode)
                    return@post
                }
                toolbarRestorePending = false
                cancelScheduledRestore()
                FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.HOST_TOOLBAR_MENU)
                destroyAll()
            } else {
                Log.i(TAG, "toggleFromPluginButton: requesting entry permissions before toolbar")
                ensureEntryPermissions { granted ->
                    handler.post {
                        if (!granted) {
                            Log.w(TAG, "toggleFromPluginButton: entry permissions denied")
                            return@post
                        }
                        showToolbarNow(requestedDocMode)
                    }
                }
            }
        }
    }

    
    private fun ensureEntryPermissions(onResult: (Boolean) -> Unit) {
        if (entryPermissionInFlight) return
        entryPermissionInFlight = true
        Log.i(TAG, "ensureEntryPermissions: FILE:READ")
        fun finish(granted: Boolean) {
            entryPermissionInFlight = false
            onResult(granted)
        }
        requestPluginFileReadPermission { read ->
            if (!read) { finish(false); return@requestPluginFileReadPermission }
            Log.i(TAG, "ensureEntryPermissions: FILE:WRITE")
            requestPluginFileWritePermission { write ->
                if (!write) { finish(false); return@requestPluginFileWritePermission }
                Log.i(TAG, "ensureEntryPermissions: FILE:DELETE")
                requestPluginFileDeletePermission { delete ->
                    if (!delete) { finish(false); return@requestPluginFileDeletePermission }
                    Log.i(TAG, "ensureEntryPermissions: INTERNET")
                    requestPluginInternetPermission { internet -> finish(internet) }
                }
            }
        }
    }

    
    @ReactMethod
    fun inspectAndFlushHostEntry(promise: Promise) {
        handler.post {
            var result = "none"
            try {
                val pm = reactApplicationContext.catalystInstance
                    ?.getNativeModule("NativePluginManager")
                if (pm == null) {
                    Log.i(TAG, "hostdiag: NativePluginManager module=null")
                    promise.resolve(result); return@post
                }
                val pa = findDeclaredField(pm.javaClass, "pluginApp")
                    ?.apply { isAccessible = true }?.get(pm)
                if (pa == null) {
                    Log.i(TAG, "hostdiag: pluginApp=null")
                    promise.resolve(result); return@post
                }
                fun readField(name: String): Any? = try {
                    findDeclaredField(pa.javaClass, name)
                        ?.apply { isAccessible = true }?.get(pa)
                } catch (_: Exception) { null }
                val entry = HostEntrySnapshot.read(pa)
                if (!entry.isCurrentOwner) {
                    Log.i(TAG, "[HOST-ENTRY] ignore background mount snapshot=$entry")
                    promise.resolve(result)
                    return@post
                }
                val state = readField("state")
                val stateName = (state as? Enum<*>)?.name ?: state?.toString()
                val cache = readField("pluginEventCacheList") as? List<*>
                val eventNames = cache?.map { it?.javaClass?.simpleName ?: "null" } ?: emptyList()
                val moduleRef = readField("mPluginModule")
                val lastShowType = (readField("lastShowType") as? Number)?.toInt()
                Log.i(TAG, "hostdiag: state=$stateName cache=${cache?.size} [${eventNames.joinToString(",")}] pluginModule=${moduleRef != null} lastShowType=$lastShowType")

                val hasButtonEvent = eventNames.any { it.contains("PluginButtonEvent") }
                val hasConfigEvent = eventNames.any { it.contains("ConfigButtonPressEvent") }
                if (stateName == "initialized" && (hasButtonEvent || hasConfigEvent)) {
                    Log.i(TAG, "hostdiag: state stuck at initialized, flushing entry events via PluginApp.onMounted()")
                    try {
                        pa.javaClass.getMethod("onMounted").invoke(pa)
                        result = when {
                            hasConfigEvent -> "config"
                            else -> "flushed"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "hostdiag: onMounted invoke failed: ${e.message}")
                    }
                } else if (entry.opensConfig(isSelfInitiatedHostShow())) {
                    result = "config"
                }
            } catch (e: Exception) {
                Log.e(TAG, "hostdiag: error: ${e.message}")
            }
            promise.resolve(result)
        }
    }

    private fun findDeclaredField(cls: Class<*>, name: String): java.lang.reflect.Field? =
        findDeclaredFieldStatic(cls, name)

    
    @ReactMethod
    fun reportHostButtonChannel(working: Boolean) {
        hostButtonChannelBroken = !working
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host button channel report working=$working")
    }

    
    @ReactMethod
    fun reportHostButtonRaw(payload: String) {
        Log.i(TAG, "host button raw: $payload")
    }

    @ReactMethod
    fun showCurrent() {
        handler.post { showToolbarNow(null) }
    }

    private fun requestedToolbarDocMode(mode: String?): Boolean = when (mode?.trim()?.lowercase()) {
        "doc_notipal", "doc", "document" -> true
        "note_notipal", "note" -> false
        else -> when (foregroundActivity()?.packageName) {
            DOC_PACKAGE -> true
            NOTE_PACKAGE -> false
            else -> docMode
        }
    }

    private fun hasToolbarSession(): Boolean =
        toolbarSessionActive || rootView != null || pendingShow || toolbarRestorePending

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isSessionActiveSync(): Boolean = hasToolbarSession()

    internal fun requestLocalSendForActiveSession(reason: String) {
        handler.post {
            if (!hasToolbarSession()) return@post
            Log.i(TAG, "LocalSend auto-start: $reason")
            emitEvent("startLocalSendFromNative", Arguments.createMap().apply {
                putBoolean("silent", true)
            })
        }
    }

    private fun setToolbarAppMode(document: Boolean, reason: String) {
        val appChanged = docMode != document
        val changed = appChanged || loadedDocMode != document
        docMode = document
        syncDocumentTextSelectionGuard()
        FloatingPenGuard.setAppContext(document)
        
        DocChromeProbe.clear("app mode: $reason")
        DocChromeProbe.request("app mode: $reason")
        
        if (appChanged) {
            emitEvent("onHostAppModeChanged", Arguments.createMap().apply {
                putBoolean("document", document)
                putString("reason", reason)
            })
        }
        if (!changed) return
        loadedDocMode = document
        tools.clear()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "toolbar app mode=${if (document) "doc_notipal" else "note_notipal"} reason=$reason")
        }
    }

    
    private fun switchToolbarAppMode(document: Boolean, reason: String) {
        val hadSession = hasToolbarSession()
        val wasCollapsed = collapsed
        setToolbarAppMode(document, reason)
        if (!hadSession) return
        ensureToolsLoaded()
        val blocked = !isInNoteApp || configPanelBlocksToolbar() ||
            SubviewLogMonitor.isSubviewOpen() || SubviewLogMonitor.isToolbarMenuOpen() ||
            isPluginHostWindowShowing || anyNativeOverlayOwnsScreen()
        if (blocked) {
            toolbarRestorePending = true
            if (rootView != null) {
                markToolbarForRestore()
                removeAll()
            }
            return
        }
        toolbarRestorePending = false
        cancelScheduledRestore()
        removeAll()
        if (wasCollapsed) {
            createCollapsedHandle()
        } else {
            pendingShow = true
            createExpandedToolbar()
        }
    }

    
    private fun mosaicExitSwapBlocked(): Boolean =
        !isInNoteApp || configPanelBlocksToolbar() || isPluginHostWindowShowing ||
            SubviewLogMonitor.isSubviewOpen() || SubviewLogMonitor.isToolbarMenuOpen() ||
            SubviewLogMonitor.isLassoMenuOpen() || anyNativeOverlayOwnsScreen() ||
            paletteApplyInFlight || paletteSelectionHold || paletteOverlaysParked ||
            docScreenshotRoutePending || HostPenSignals.insertImageFlowActive

    internal fun rebuildForMosaic(visible: Boolean) {
        if (configPanelBlocksToolbar()) return
        if (visible && MosaicLink.isChromeMenuOpen()) {
            
            if (rootView != null) {
                markToolbarForRestore()
                removeAll()
            }
            return
        }
        if (hasToolbarSession()) {
            tools.clear()
            if (!visible) {
                
                
                
                ensureToolsLoaded()
                if (rootView != null && !mosaicExitSwapBlocked()) {
                    
                    toolbarRestorePending = false
                    cancelScheduledRestore()
                    removeAll()
                    if (collapsed) createCollapsedHandle() else { pendingShow = true; createExpandedToolbar() }
                    Log.i(TAG, "toolbar swapped in place after mosaic closed tools=${tools.size}")
                } else {
                    markToolbarForRestore()
                    removeAll()
                    scheduleRestore(RESTORE_DELAY_SHORT, "mosaic closed")
                }
                monitorHandler.postDelayed(
                    { recreateSurfaceBubbles("mosaic closed", raise = false) },
                    RESTORE_DELAY_SHORT,
                )
                return
            }
            ensureToolsLoaded()
            toolbarRestorePending = false
            cancelScheduledRestore()
            removeAll()
            if (collapsed) createCollapsedHandle() else { pendingShow = true; createExpandedToolbar() }
            Log.i(TAG, "toolbar rebuilt for mosaic canvas tools=${tools.size}")
        } else if (!visible) {
            monitorHandler.postDelayed(
                { recreateSurfaceBubbles("mosaic closed", raise = false) },
                RESTORE_DELAY_SHORT,
            )
            return
        }
        if (visible) {
            val raise = surfaceBubbleRaisePending
            surfaceBubbleRaisePending = false
            recreateSurfaceBubbles("mosaic opened", raise)
        }
    }

    private fun showToolbarNow(requestedDocMode: Boolean? = null) {
        
        monitorHandler.removeCallbacks(configFallbackRunnable)
        if (configPanelBlocksToolbar()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "showToolbarNow: config panel active, skip")
            cancelHostCloseIntent()
            closeHostPluginView()
            return
        }
        Log.i(TAG, "showToolbarNow rootAttached=${rootView?.isAttachedToWindow == true} " +
            "foregroundMonitor=$foregroundMonitorRunning")
        toolbarSessionActive = true
        toolbarRestorePending = false
        cancelScheduledRestore()
        try {
            setToolbarAppMode(requestedDocMode ?: requestedToolbarDocMode(null), "toolbar show")
            ensureToolsLoaded()
            collapsed = false
            removeAll()
            pendingShow = true
            
            armHostCloseForToolbarShow()
            
            
            if (!foregroundMonitorRunning) startForegroundMonitor()
            createExpandedToolbar()
            requestLocalSendForActiveSession("toolbar show")
        } catch (t: Throwable) {
            Log.e(TAG, "showToolbarNow: ${t.message}", t)
            scheduleToolbarRetry("showToolbarNow failed")
        }
    }
    private fun ensureToolsLoaded() {
        if (tools.isNotEmpty()) return
        val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
        fun presetArray(key: String, fallback: String): JSONArray = try {
            prefs.getString(key, null)?.let { JSONObject(it).optJSONArray("tools") }
                ?: JSONArray(fallback)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "ensureToolsLoaded[$key]: ${e.message}")
            JSONArray(fallback)
        }
        val sharedItems = parseToolEntries(presetArray(SHARED_PRESET_KEY, SHARED_DEFAULT_TOOLS_JSON))
            .map { it.first }
            .filter { !isToolbarUtilityTool(it.id) && it.id !in NOTE_ONLY_TOOL_IDS }
        val noteEntries = parseToolEntries(
            presetArray(
                if (docMode) "doc_preset" else "note_preset",
                if (docMode) DOC_DEFAULT_TOOLS_JSON else NOTE_DEFAULT_TOOLS_JSON
            )
        ).filter { it.first.id in NOTE_ONLY_TOOL_IDS }
        val head = noteEntries.getOrNull(0)?.takeIf { !it.second }?.first
        val tail = noteEntries.getOrNull(1)?.takeIf { !it.second }?.first
        val used = setOfNotNull(head?.id, tail?.id)
        val extraNote = noteEntries.drop(1).dropLast(if (noteEntries.size > 1) 1 else 0)
            .filter { !it.second && it.first.id !in used }
            .map { it.first }
        tools.clear()
        if (head != null) tools.add(head)
        tools.addAll(sharedItems)
        tools.addAll(extraNote)
        if (tail != null && tail.id != head?.id) tools.add(tail)
        if (docMode && !prefs.contains(SHARED_PRESET_KEY)) {
            parseToolEntries(JSONArray(DOC_DEFAULT_TOOLS_JSON)).forEach { (item, hidden) ->
                if (!hidden && tools.none { it.id == item.id }) tools.add(item)
            }
        }
        if (mosaicMode) {
            tools.retainAll { it.id in MOSAIC_TOOL_IDS }
            ensureMosaicCanvasTools()
        }
        appendMissingUtilityTools()
    }

    
    private fun ensureMosaicCanvasTools() {
        val present = tools.mapTo(mutableSetOf()) { it.id }
        val catalog = listOf(
            Triple("insert_image", "Im", "config_tool_image"),
            Triple("insert_doc_screenshot", "Sc", "config_tool_doc"),
            Triple(SMART_LASSO_TOOL_ID, "AI", "config_tool_smart_lasso"),
            Triple(AI_RELAY_TOOL_ID, "Rl", "config_tool_ai_relay"),
        )
        for ((id, icon, nameKey) in catalog) {
            if (id in present) continue
            val item = ToolItem(id, NativeLocale.t(nameKey), icon, id, nameKey = nameKey)
            if (id == "insert_image") tools.add(0, item) else tools.add(item)
            present.add(id)
        }
    }

    private fun appendMissingUtilityTools() {
        val selectedIds = tools.mapTo(mutableSetOf()) { it.id }
        val defaults = listOf(
            ToolItem(CLIPBOARD_TOOL_ID, NativeLocale.t("config_tool_clipboard"), "Cl", CLIPBOARD_TOOL_ID, nameKey = "config_tool_clipboard"),
            ToolItem(COLLAPSE_TOOL_ID, NativeLocale.t("config_tool_collapse"), "⋯", COLLAPSE_TOOL_ID, nameKey = "config_tool_collapse"),
            ToolItem(GOTO_NOTE_TOOL_ID, NativeLocale.t("config_tool_goto_note"), "N", GOTO_NOTE_TOOL_ID, nameKey = "config_tool_goto_note"),
            ToolItem(INSERT_TEXT_TOOL_ID, NativeLocale.t("config_tool_text"), "Tx", INSERT_TEXT_TOOL_ID, nameKey = "config_tool_text")
        )
        for (tool in defaults) {
            if (selectedIds.add(tool.id)) tools.add(tool)
        }
    }

    @ReactMethod
    fun collapse() {
        handler.post { switchToCollapsed() }
    }

    @ReactMethod
    fun dockToEdge() {
        handler.post { dockToNearestEdge() }
    }

    @ReactMethod
    fun expand() {
        handler.post { switchToExpanded() }
    }

    @ReactMethod
    fun setSide(side: String) {
        handler.post {
            dockSide = if (side == "right") "right" else "left"
            if (collapsed) {
                removeAll()
                createCollapsedHandle()
            } else if (expandedRoot != null) {

                removeAll()
                createExpandedToolbar()
            }
        }
    }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(rootView?.isAttachedToWindow == true) }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isShowingSync(): Boolean = rootView?.isAttachedToWindow == true

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isMosaicBoardVisible(): Boolean = MosaicLink.isBoardVisible()

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isDocumentMode(): Boolean = docMode

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isMosaicBoardSurface(): Boolean = MosaicLink.isBoardSurface()

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getMosaicSurface(): String = MosaicLink.getSurface()

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getMosaicNoteRef(): String = MosaicLink.getNoteRef() ?: ""

    
    @ReactMethod
    fun requestMosaicPasteStrokes() {
        MosaicLink.requestPasteStrokes(reactApplicationContext)
    }

    
    @ReactMethod
    fun requestMosaicClearSelection(delete: Boolean) {
        MosaicLink.requestClearSelection(reactApplicationContext, delete)
    }

    
    @ReactMethod
    fun requestMosaicTextCard(text: String, anchorScreenX: Int, anchorScreenY: Int) {
        MosaicLink.requestTextCard(reactApplicationContext, text, anchorScreenX, anchorScreenY)
    }

    
    @ReactMethod
    fun enqueueMosaicImage(path: String, source: String, promise: Promise) {
        kotlin.concurrent.thread(isDaemon = true) {
            val ok = MosaicLink.enqueueImage(path, source)
            promise.resolve(ok)
        }
    }

    
    internal fun enqueueImageToMosaic(path: String, source: String, after: (() -> Unit)? = null) {
        kotlin.concurrent.thread(isDaemon = true) {
            val ok = MosaicLink.enqueueImage(path, source)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[INSERT-DBG/Kt] enqueueImageToMosaic ok=$ok source=$source path=$path")
            }
            handler.post {
                after?.invoke()
                restoreToolbar()
            }
        }
    }

    
    @ReactMethod
    fun notifyStickerInserted() {
        val document = docMode
        if (!document && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "notifyStickerInserted: note mode, pending committed-sticker selection")
        }
        HostPenSignals.onStickerInserted(document)
    }

    @ReactMethod
    fun hasPendingDocSticker(promise: Promise) {
        monitorHandler.post { promise.resolve(HostPenSignals.docStickerPending) }
    }

    @ReactMethod
    fun clearPendingDocSticker() {
        HostPenSignals.clearDocStickerPending()
    }

    @ReactMethod
    fun checkPendingOpenMain(promise: Promise) {
        val v = pendingOpenMain
        pendingOpenMain = false
        promise.resolve(v)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun checkPendingOpenMainSync(): Boolean = pendingOpenMain

    @ReactMethod
    fun ackOpenMain() { pendingOpenMain = false }

    @ReactMethod
    fun setPendingScreen(name: String) { pendingScreen = name ?: "" }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getPendingScreenSync(): String = pendingScreen

    @ReactMethod
    fun ackPendingScreen() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] ackPendingScreen (was=$pendingScreen)")
        pendingScreen = ""
    }

    @ReactMethod
    fun openPluginView() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPluginView called (pendingScreen=$pendingScreen)")
            try { callShowPluginView() } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[LASSO-DBG/Kt] openPluginView FAIL: ${e.message}")
            }
        }
    }

    
    
    private fun openNativePanel(name: String, show: () -> Unit) {
        emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", name) })
        show()
    }

    @ReactMethod
    fun openPanel(screen: String) {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPanel screen=$screen (prev pendingScreen=$pendingScreen)")

            
            
            
            
            if (screen == "config" || screen == "main") {
                val existingConfig = ConfigPanel.currentInstance
                if (configPanelBlocksToolbar() || existingConfig?.isActuallyVisible == true) {
                    if (existingConfig?.isShowing == true) existingConfig.resumeVisibility()
                    if (existingConfig?.isActuallyVisible == true) {
                        configPanelActive = true
                    }
                    closeHostPluginView()
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "openPanel config: existing/pending config session, keep it")
                    }
                    return@post
                }
                hideAllNativePanels()
                pendingScreen = ""
                removeAll()
                openNativePanel("config") {
                    ConfigPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
                }
                closeHostPluginView()
                return@post
            }

            hideAllNativePanels()

            if (screen == "nativeSendClipboard") {
                pendingScreen = "nativeSendHelper"
                removeAll()
                openNativePanel("send") {
                    SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(syncClipboard = true)
                }
                handler.postDelayed({
                    
                    
                    
                    if (pendingScreen != "nativeSendHelper") return@postDelayed
                    callShowPluginView()
                    emitOpenMainWithRetries("nativeSendHelper")
                }, 150)
                return@post
            }

            if (screen == "airRelay" || screen.startsWith("airRelayDetail:")) {
                pendingScreen = ""
                removeAll()
                val detailId = screen.removePrefix("airRelayDetail:").takeIf { screen.startsWith("airRelayDetail:") }
                openNativePanel("airRelay") {
                    val panel = RelayInboxPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                    if (detailId == null) panel.showList() else panel.showDetail(detailId)
                }
                return@post
            }

            pendingScreen = screen ?: ""
            removeAll()

            handler.postDelayed({
                emitEvent("onToolbarOpenMain", Arguments.createMap())
            }, 80)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPanel done, pendingScreen now=$pendingScreen")
        }
    }

    @ReactMethod
    fun forceClosePluginView() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] forceClosePluginView called")
            insertPluginViewClosed = true
            callClosePluginView()
        }
    }

    @ReactMethod
    fun updateTitleClips(json: String) {
        handler.post {
            try {
                val arr = JSONArray(json)
                for (i in 0 until minOf(arr.length(), CLIP_SLOT_COUNT)) {
                    titleClipFilled[i] = arr.getBoolean(i)
                }
                rebuildClipIcons()
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "updateTitleClips: ${e.message}") }
        }
    }

    @ReactMethod
    fun setLocale(loc: String) {
        NativeLocale.setLocale(loc)
    }

    @ReactMethod
    fun setPluginVersion(versionName: String, versionCode: String) {
        AboutInfo.versionName = versionName
        AboutInfo.versionCode = versionCode
    }

    @ReactMethod
    fun savePreset(num: Int, json: String, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            prefs.edit().putString("preset_$num", json).apply()
            promise.resolve(true)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "savePreset: ${e.message}")
            promise.resolve(false)
        }
    }

    
    @ReactMethod
    fun paletteSnapshotsChanged() {
        me.laumss.notipal.panels.PalettePanel.currentInstance?.reloadSnapshots()
    }

    @ReactMethod
    fun loadPreset(num: Int, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            val json = prefs.getString("preset_$num", null)
            promise.resolve(json)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "loadPreset: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "getStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun ensureStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            if (!dir.exists()) {
                val ok = dir.mkdirs()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "ensureStickerDir: mkdirs=${ok} path=${dir.absolutePath}")
            }
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "ensureStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun deleteQueueFile(path: String, promise: Promise) {
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val f = java.io.File(path)
                val fileName = f.name
                DocScreenshotService.unmarkInsertNext(fileName)
                if (f.exists()) {
                    val deleted = f.delete()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path → deleted=$deleted")
                    promise.resolve(deleted)
                } else {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path already gone")
                    promise.resolve(false)
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] deleteQueueFile error: ${e.message}")
                promise.reject("DELETE_ERROR", e.message, e)
            }
        }
    }

    
    @ReactMethod
    fun resizeInsertedSticker(rect: ReadableMap, promise: Promise) {
        PageImageSource.resizeLassoRectAfterInsert(reactApplicationContext, rect) { ok, error ->
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[CLIP-DBG/NATIVE] resizeInsertedSticker done ok=$ok error=${error ?: "none"}")
            }
            promise.resolve(ok)
        }
    }

    
    @ReactMethod
    fun hasHostLasso(promise: Promise) {
        try {
            val app = PageImageSource.getPluginApp(reactApplicationContext)
            promise.resolve(app.javaClass.getMethod("isLassoTrail").invoke(app) == true)
        } catch (error: Throwable) {
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun getObservedLassoDisplayRect(promise: Promise) {
        val rect = SubviewLogMonitor.getObservedLassoDisplayRect()
        promise.resolve(rect?.let {
            Arguments.createMap().apply {
                putInt("left", it.left); putInt("top", it.top)
                putInt("right", it.right); putInt("bottom", it.bottom)
            }
        })
    }

    @ReactMethod
    fun getObservedLassoPageRect(pageWidth: Int, pageHeight: Int, promise: Promise) {
        val display = SubviewLogMonitor.getObservedLassoDisplayRect()
        if (display == null || screenWidth <= 0 || screenHeight <= 0 || pageWidth <= 0 || pageHeight <= 0) {
            promise.resolve(null)
            return
        }
        promise.resolve(Arguments.createMap().apply {
            putInt("left", (display.left * pageWidth / screenWidth).coerceIn(0, pageWidth))
            putInt("top", (display.top * pageHeight / screenHeight).coerceIn(0, pageHeight))
            putInt("right", (display.right * pageWidth / screenWidth).coerceIn(0, pageWidth))
            putInt("bottom", (display.bottom * pageHeight / screenHeight).coerceIn(0, pageHeight))
        })
    }

    @ReactMethod
    fun clearClipboardContours(uuids: ReadableArray, context: ReadableMap, promise: Promise) {
        ClipboardElementIo.clearContours(reactApplicationContext, uuids, context, promise)
    }

    @ReactMethod
    fun applyContourPalette(uuids: ReadableArray, context: ReadableMap, style: ReadableMap, promise: Promise) {
        ClipboardElementIo.applyContourPalette(reactApplicationContext, uuids, context, style, promise)
    }

    @ReactMethod
    fun convertClipboardSticker(metadata: ReadableArray, context: ReadableMap, promise: Promise) {
        ClipboardElementIo.convertSticker(reactApplicationContext, metadata, context, promise)
    }

    @ReactMethod
    fun restoreClipboardContours(path: String, promise: Promise) {
        ClipboardElementIo.restoreContours(path, promise)
    }

    @ReactMethod
    fun exportClipboardSidecar(
        path: String, uuids: ReadableArray, pageWidth: Int, pageHeight: Int,
        sourceRect: ReadableMap?, targetRect: ReadableMap?, promise: Promise,
    ) {
        ClipboardElementIo.exportSidecar(
            reactApplicationContext, path, uuids, pageWidth, pageHeight, sourceRect, targetRect, promise,
        )
    }

    @ReactMethod
    fun openPluginSettings() {
        openPluginSettingsFor(PLUGIN_ID)
    }

    internal fun openMosaicPluginSettings() {
        openPluginSettingsFor(MOSAIC_PLUGIN_ID)
    }

    private fun openPluginSettingsFor(pluginId: String) {
        try {
            val intent = Intent(SETTINGS_PLUGIN_DETAIL_ACTION).apply {
                component = ComponentName(SETTINGS_PACKAGE, SETTINGS_ACTIVITY)
                putExtra("pluginId", pluginId)
                putExtra("fromAPP", pluginId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openPluginSettings: launched plugin detail for $pluginId")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "openPluginSettings plugin detail failed: ${e.message}", e)
            try {
                reactApplicationContext.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    setPackage(SETTINGS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (fallback: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "openPluginSettings fallback failed: ${fallback.message}", fallback)
            }
        }
    }

    fun openPluginSettingsAfterFileReadDenied() {
        if (shouldOpenPluginSettingsAfterDenied(PLUGIN_FILE_READ_PERMISSION)) openPluginSettings()
    }

    fun openPluginSettingsAfterFileWriteDenied() {
        if (shouldOpenPluginSettingsAfterDenied(PLUGIN_FILE_WRITE_PERMISSION)) openPluginSettings()
    }

    @ReactMethod
    fun openPluginSettingsAfterFileWritePermissionDenied() {
        openPluginSettingsAfterFileWriteDenied()
    }

    private fun shouldOpenPluginSettingsAfterDenied(permission: String): Boolean {
        return if (deniedPluginPermissions.contains(permission)) {
            true
        } else {
            deniedPluginPermissions.add(permission)
            false
        }
    }

    private fun clearPluginPermissionDenied(permission: String) {
        deniedPluginPermissions.remove(permission)
    }

    private fun parsePermissionStatus(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    
    
    
    
    private fun isPluginPermissionGranted(status: Int): Boolean =
        status == PLUGIN_PERMISSION_STATUS_ALWAYS || status == PLUGIN_PERMISSION_STATUS_SESSION

    
    private fun permissionRequestDesc(permission: String): String = when (permission) {
        PLUGIN_INTERNET_PERMISSION -> NativeLocale.t("perm_desc_internet")
        PLUGIN_FILE_WRITE_PERMISSION -> NativeLocale.t("perm_desc_file_write")
        PLUGIN_FILE_DELETE_PERMISSION -> NativeLocale.t("perm_desc_file_delete")
        else -> NativeLocale.t("perm_desc_file_read")
    }

    
    internal fun requestMosaicPluginPermissions(
        onResult: ((MosaicPermissionResult) -> Unit)? = null,
    ) {
        handler.post {
            try {
                val loader = reactApplicationContext.classLoader
                val managerClass = Class.forName(HOST_PLUGIN_MANAGER_CLASS, false, loader)
                val manager = managerClass.methods.firstOrNull {
                    it.name == "getInstance" && it.parameterCount == 0
                }?.invoke(null) ?: run {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: host manager unavailable")
                    onResult?.invoke(MosaicPermissionResult.DENIED)
                    return@post
                }
                val getPluginApp = managerClass.methods.firstOrNull {
                    it.name == "getPluginApp" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: run {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: getPluginApp unavailable")
                    onResult?.invoke(MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                    return@post
                }
                val mosaicApp = getPluginApp.invoke(manager, MOSAIC_PLUGIN_ID) ?: run {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: plugin not installed")
                    onResult?.invoke(MosaicPermissionResult.PLUGIN_UNAVAILABLE)
                    return@post
                }

                requestHostPluginPermission(
                    managerClass,
                    manager,
                    mosaicApp,
                    PLUGIN_FILE_READ_PERMISSION,
                    NativeLocale.t("mosaic_permission_request"),
                ) { readGranted ->
                    if (!readGranted) {
                        onResult?.invoke(MosaicPermissionResult.DENIED)
                        return@requestHostPluginPermission
                    }
                    requestHostPluginPermission(
                        managerClass,
                        manager,
                        mosaicApp,
                        PLUGIN_FILE_WRITE_PERMISSION,
                        NativeLocale.t("mosaic_permission_request"),
                    ) { writeGranted ->
                        onResult?.invoke(if (writeGranted) MosaicPermissionResult.GRANTED else MosaicPermissionResult.DENIED)
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.e(TAG, "Mosaic permission check failed: ${e.message}", e)
                }
                onResult?.invoke(MosaicPermissionResult.DENIED)
            }
        }
    }

    private fun requestHostPluginPermission(
        managerClass: Class<*>,
        manager: Any,
        pluginApp: Any,
        permission: String,
        description: String = permissionRequestDesc(permission),
        onResult: (Boolean) -> Unit,
    ) {
        try {
            val hasMethod = managerClass.methods.firstOrNull {
                it.name == "hasPermission" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == String::class.java
            } ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: hasPermission unavailable")
                onResult(false)
                return
            }
            val status = (hasMethod.invoke(manager, pluginApp, permission) as? Number)?.toInt() ?: 0
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "Mosaic permission has $permission status=$status")
            }
            if (status == PLUGIN_PERMISSION_STATUS_ALWAYS || status == PLUGIN_PERMISSION_STATUS_SESSION) {
                onResult(true)
                return
            }

            val requestMethod = managerClass.methods.firstOrNull {
                it.name == "requestPermission" && it.parameterCount == 4 &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == String::class.java
            } ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "Mosaic permission: requestPermission unavailable")
                onResult(false)
                return
            }
            val loader = reactApplicationContext.classLoader
            val callbackClass = Class.forName(HOST_PERMISSION_CALLBACK_CLASS, false, loader)
            val callbackLock = Any()
            var callbackFinished = false
            fun finish(granted: Boolean) {
                var deliver = false
                synchronized(callbackLock) {
                    if (!callbackFinished) {
                        callbackFinished = true
                        deliver = true
                    }
                }
                if (deliver) handler.post { onResult(granted) }
            }
            val callback = Proxy.newProxyInstance(
                loader,
                arrayOf(callbackClass),
                java.lang.reflect.InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onResult" -> {
                            val result = (args?.firstOrNull() as? Number)?.toInt() ?: 0
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "Mosaic permission request $permission status=$result")
                            }
                            finish(
                                result == PLUGIN_PERMISSION_STATUS_ALWAYS ||
                                    result == PLUGIN_PERMISSION_STATUS_SESSION
                            )
                        }
                        "onError" -> {
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.w(TAG, "Mosaic permission request $permission failed: ${args?.firstOrNull()}")
                            }
                            finish(false)
                        }
                    }
                    null
                },
            )
            requestMethod.invoke(manager, pluginApp, permission, description, callback)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.e(TAG, "Mosaic permission request $permission failed: ${e.message}", e)
            }
            onResult(false)
        }
    }

    internal fun showMosaicPermissionReminder(result: MosaicPermissionResult) {
        val messageKey = if (result == MosaicPermissionResult.PLUGIN_UNAVAILABLE) "mosaic_plugin_needed" else "mosaic_permission_needed"
        Dialog.confirmResult(
            reactApplicationContext,
            NativeLocale.t(messageKey),
            NativeLocale.t("cancel"),
            NativeLocale.t("open_settings"),
        ) { openSettings ->
            if (openSettings) openPluginSettingsFor(MOSAIC_PLUGIN_ID)
        }
    }

    
    private fun closeHostPluginView() {
        
        
        callClosePluginView()
    }

    
    private fun requestDirectHostPermission(permission: String, onResult: ((Boolean) -> Unit)?) {
        handler.post {
            try {
                val loader = reactApplicationContext.classLoader
                val managerClass = Class.forName(HOST_PLUGIN_MANAGER_CLASS, false, loader)
                val manager = managerClass.methods.firstOrNull {
                    it.name == "getInstance" && it.parameterCount == 0
                }?.invoke(null) ?: run { onResult?.invoke(false); return@post }
                val getPluginApp = managerClass.methods.firstOrNull {
                    it.name == "getPluginApp" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: run { onResult?.invoke(false); return@post }
                val pluginApp = getPluginApp.invoke(manager, INKLING_PLUGIN_ID)
                    ?: run { onResult?.invoke(false); return@post }
                requestHostPluginPermission(
                    managerClass, manager, pluginApp, permission,
                    permissionRequestDesc(permission),
                ) { granted ->
                    Log.i(TAG, "direct host permission $permission result=$granted")
                    onResult?.invoke(granted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "direct host permission $permission failed: ${e.message}", e)
                onResult?.invoke(false)
            }
        }
    }

    fun requestPluginPermission(
        permission: String,
        onResult: ((Boolean) -> Unit)? = null,
        requestIfMissing: Boolean = true
    ) {
        requestDirectHostPermission(permission, onResult)
        return
        handler.post {
            try {
                val storedStatus = readStoredPermissionStatus(permission)
                if (storedStatus == PLUGIN_PERMISSION_STATUS_ALWAYS) {
                    clearPluginPermissionDenied(permission)
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "requestPluginPermission($permission): stored status=2 (always) granted")
                    }
                    onResult?.invoke(true)
                    return@post
                }
                val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: run {
                    Log.w(TAG, "requestPluginPermission($permission): NativePluginManager not found")
                    onResult?.invoke(false)
                    return@post
                }
                val hasMethod = pm::class.java.methods.firstOrNull {
                    it.name == "hasPermission" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
                } ?: run {
                    Log.w(TAG, "requestPluginPermission($permission): hasPermission(String, Promise) not found")
                    onResult?.invoke(false)
                    return@post
                }
                
                
                
                val requestMethod = if (requestIfMissing) {
                    pm::class.java.methods.firstOrNull {
                        it.name == "requestPermission" && it.parameterCount == 3 && it.parameterTypes[0] == String::class.java
                    } ?: pm::class.java.methods.firstOrNull {
                        it.name == "requestPermission" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
                    } ?: run {
                        Log.w(TAG, "requestPluginPermission($permission): requestPermission(String, [String,] Promise) not found")
                        onResult?.invoke(false)
                        return@post
                    }
                } else {
                    null
                }
                val requestPromise = PromiseImpl(
                    Callback { args ->
                        val status = parsePermissionStatus(args.firstOrNull())
                        val granted = isPluginPermissionGranted(status)
                        if (granted) clearPluginPermissionDenied(permission)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "requestPluginPermission($permission) request resolved: status=$status granted=$granted")
                        handler.post { onResult?.invoke(granted) }
                    },
                    Callback { args ->
                        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "requestPluginPermission($permission) rejected: ${args.joinToString()}")
                        handler.post { onResult?.invoke(false) }
                    }
                )
                fun doRequest() {
                    Log.i(TAG, "requestPluginPermission($permission): invoking requestPermission parameterCount=${requestMethod!!.parameterCount}")
                    if (requestMethod!!.parameterCount == 3) {
                        
                        
                        
                        requestMethod.invoke(pm, permission, permissionRequestDesc(permission), requestPromise)
                    } else {
                        requestMethod.invoke(pm, permission, requestPromise)
                    }
                    Log.i(TAG, "requestPluginPermission($permission): requestPermission invoked")
                }
                val hasPromise = PromiseImpl(
                    Callback { args ->
                        val status = parsePermissionStatus(args.firstOrNull())
                        val alreadyGranted = isPluginPermissionGranted(status)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "requestPluginPermission($permission) hasPermission resolved: status=$status alreadyGranted=$alreadyGranted")
                        if (alreadyGranted) {
                            clearPluginPermissionDenied(permission)
                            handler.post { onResult?.invoke(true) }
                        } else if (requestIfMissing) {
                            doRequest()
                        } else {
                            handler.post { onResult?.invoke(false) }
                        }
                    },
                    Callback { args ->
                        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "requestPluginPermission($permission) hasPermission rejected: ${args.joinToString()}")
                        if (requestIfMissing) {
                            doRequest()
                        } else {
                            handler.post { onResult?.invoke(false) }
                        }
                    }
                )
                hasMethod.invoke(pm, permission, hasPromise)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "requestPluginPermission($permission): ${e.message}", e)
                onResult?.invoke(false)
            }
        }
    }

    fun requestPluginFileReadPermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_READ_PERMISSION, onResult)
    }

    fun requestPluginFileWritePermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_WRITE_PERMISSION, onResult)
    }

    fun requestPluginFileDeletePermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_DELETE_PERMISSION, onResult)
    }

    private fun checkPluginPermission(permission: String, onResult: ((Boolean) -> Unit)? = null) {
        handler.post {
            val storedStatus = readStoredPermissionStatus(permission)
            if (storedStatus == PLUGIN_PERMISSION_STATUS_ALWAYS) {
                
                
                
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "checkPluginPermission($permission): stored status=2 (always) → granted")
                onResult?.invoke(true)
                return@post
            }
            
            
            requestPluginPermission(permission, onResult, requestIfMissing = true)
        }
    }

    private fun readStoredPermissionStatus(permission: String): Int {
        return try {
            val pm = reactApplicationContext.catalystInstance?.getNativeModule("NativePluginManager") ?: return -1
            val pa = findDeclaredField(pm.javaClass, "pluginApp")
                ?.apply { isAccessible = true }?.get(pm) ?: return -1
            val perms = findDeclaredField(pa.javaClass, "usesPermissions")
                ?.apply { isAccessible = true }?.get(pa) as? List<*> ?: return -1
            for (p in perms) {
                if (p == null) continue
                val name = p.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                    ?.invoke(p) as? String ?: continue
                if (name == permission) {
                    val status = p.javaClass.methods.firstOrNull { it.name == "getStatus" && it.parameterCount == 0 }
                        ?.invoke(p) as? Number ?: return -1
                    return status.toInt()
                }
            }
            -1
        } catch (_: Exception) { -1 }
    }

    private fun showScreenshotMessage(key: String) {
        handler.post {
            android.widget.Toast.makeText(
                reactApplicationContext,
                NativeLocale.t(key),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestScreenshotStoragePermissions(
        action: String,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        requestPluginFileReadPermission { readGranted ->
            if (!readGranted) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[CROP-DBG/Kt] $action denied: FILE:READ")
                showScreenshotMessage("file_read_permission_needed")
                openPluginSettingsAfterFileReadDenied()
                onDenied()
                return@requestPluginFileReadPermission
            }
            requestPluginFileWritePermission { writeGranted ->
                if (!writeGranted) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[CROP-DBG/Kt] $action denied: FILE:WRITE")
                    showScreenshotMessage("file_write_permission_needed")
                    openPluginSettingsAfterFileWriteDenied()
                    onDenied()
                    return@requestPluginFileWritePermission
                }
                onGranted()
            }
        }
    }

    fun requestPluginInternetPermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_INTERNET_PERMISSION, onResult)
    }

    @ReactMethod
    fun checkFileReadPermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_READ_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkFileWritePermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_WRITE_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkFileDeletePermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_DELETE_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkInternetPermission(promise: Promise) {
        checkPluginPermission(PLUGIN_INTERNET_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileReadPermission(promise: Promise) {
        requestPluginFileReadPermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileWritePermission(promise: Promise) {
        requestPluginFileWritePermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileDeletePermission(promise: Promise) {
        requestPluginFileDeletePermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestInternetPermission(promise: Promise) {
        requestPluginInternetPermission { granted -> promise.resolve(granted) }
    }

    private fun callShowPluginView() = withNativePluginManager("callShowPluginView") { pm ->
        
        selfShowPluginViewAt = android.os.SystemClock.elapsedRealtime()
        val allMethods = pm::class.java.methods.filter { it.name == "showPluginView" }
        if (allMethods.isEmpty()) return@withNativePluginManager
        val noArg = allMethods.firstOrNull { it.parameterCount == 0 }
        if (noArg != null) { noArg.invoke(pm); return@withNativePluginManager }
        val singleArg = allMethods.firstOrNull { it.parameterCount == 1 }
        if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); return@withNativePluginManager }
    }

    private fun callClosePluginView() = withNativePluginManager("callClosePluginView") { pm ->
        
        
        
        
        
        if (MosaicLink.isBoardVisible()) {
            Log.i(TAG, "callClosePluginView skipped: Mosaic board owns PluginContainer")
            return@withNativePluginManager
        }
        notifySettingsClientClosed(pm)
        for (name in arrayOf("closePluginView", "hidePluginView")) {
            val methods = pm::class.java.methods.filter { it.name == name }
            if (methods.isEmpty()) continue
            val noArg = methods.firstOrNull { it.parameterCount == 0 }
            if (noArg != null) { noArg.invoke(pm); if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name() called"); return@withNativePluginManager }
            
            
            
            val singleArg = methods.firstOrNull {
                it.parameterCount == 1 && (
                    it.parameterTypes[0].isAssignableFrom(PromiseImpl::class.java) ||
                        Promise::class.java.isAssignableFrom(it.parameterTypes[0])
                    )
            }
            if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name(promise) called"); return@withNativePluginManager }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "callClosePluginView: no suitable method found")
    }

    private fun getHostPluginApp(pm: Any): Any? =
        pm.javaClass.methods.firstOrNull {
            it.name == "getPluginApp" && it.parameterCount == 0
        }?.invoke(pm) ?: findDeclaredField(pm.javaClass, "pluginApp")
            ?.apply { isAccessible = true }?.get(pm)

    private fun getHostClientUid(pluginApp: Any): Int =
        (pluginApp.javaClass.methods.firstOrNull {
            it.name == "getClientUid" && it.parameterCount == 0
        }?.invoke(pluginApp) as? Number)?.toInt() ?: -1

    
    private fun notifySettingsClientClosed(pm: Any) {
        try {
            val pa = getHostPluginApp(pm) ?: return
            val clientUid = getHostClientUid(pa)
            if (clientUid < 0) return

            val loader = pa.javaClass.classLoader
            val managerClass = loader.loadClass(
                "com.ratta.supernote.pluginhost.manager.PluginManager"
            )
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val binder = managerClass.getMethod(
                "getCurrentClientBinder",
                Integer.TYPE
            ).invoke(manager, clientUid) ?: return
            val packageName = binder.javaClass.methods.firstOrNull {
                it.name == "getServicePackageName" && it.parameterCount == 0
            }?.invoke(binder) as? String
            if (packageName != "com.ratta.settings") return

            val serviceClass = loader.loadClass(
                "com.ratta.supernote.pluginhost.services.PluginHostService"
            )
            val hostContext = findDeclaredField(serviceClass, "mContext")
                ?.apply { isAccessible = true }?.get(null)
            val service = when {
                serviceClass.isInstance(hostContext) -> hostContext
                hostContext is android.content.ContextWrapper -> hostContext.baseContext
                else -> null
            }
            if (service == null || !serviceClass.isInstance(service)) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "settings close state notify skipped: PluginHostService unavailable")
                }
                return
            }
            serviceClass.getMethod(
                "notifyClientPluginState",
                Integer.TYPE,
                Integer.TYPE
            ).invoke(service, clientUid, 0)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "settings close state notified: uid=$clientUid state=0")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "settings close state notify failed: ${e.message}")
            }
        }
    }

    
    private val autoCollapseRunnable = Runnable { }

    private fun cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private fun isNearEdge(): Boolean {
        val lp = layoutParams ?: return false
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 }
            ?: return false
        refreshScreenDimensions()
        val flushLeft = lp.x <= DOCK_FLUSH_TOLERANCE
        val flushRight = (screenWidth - (lp.x + vw)) <= DOCK_FLUSH_TOLERANCE
        return flushLeft || flushRight
    }

    private fun inferDockSideFromPosition() {
        val lp = layoutParams ?: return
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 } ?: return
        refreshScreenDimensions()
        val distLeft = lp.x
        val distRight = screenWidth - (lp.x + vw)
        dockSide = if (distLeft <= distRight) "left" else "right"
    }

    private fun resetAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private fun switchToCollapsed() {
        if (collapsed && collapsedRoot != null) return
        cancelAutoCollapse()
        val lp = layoutParams as? WindowManager.LayoutParams
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "switchToCollapsed: x=${lp?.x} y=${lp?.y} side=$dockSide screen=${screenWidth}x${screenHeight}")
        inferDockSideFromPosition()
        collapsed = true
        removeAll()
        createCollapsedHandle()
        emitCollapseChange()
    }

    private fun switchToExpanded() {
        if (!collapsed && expandedRoot != null) return
        if (preDockX >= 0) {
            savePositionToPrefs(preDockX, preDockY)
            preDockX = -1; preDockY = -1
        }
        collapsed = false
        removeAll()
        createExpandedToolbar()
        emitCollapseChange()

        expandedRoot?.post { resetAutoCollapse() }
    }

    private fun emitCollapseChange() {
        emitEvent("onToolbarCollapseChange", Arguments.createMap().apply {
            putBoolean("collapsed", collapsed)
            putString("side", dockSide)
        })
    }

    override fun refreshScreenDimensions() {
        try {
            val wm = reactApplicationContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width(); screenHeight = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
                screenWidth = size.x; screenHeight = size.y
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRotation(): Int = try {
        val wm = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    
    private fun toolbarWindowVisible(): Boolean =
        rootView?.let { it.isAttachedToWindow && it.visibility == View.VISIBLE } == true

    
    private fun handleOrientationChange(reason: String) {
        val oldW = screenWidth; val oldH = screenHeight
        val oldRotation = lastDisplayRotation
        refreshScreenDimensions()
        val rotation = readDisplayRotation()
        lastDisplayRotation = rotation
        val displayChanged = oldW != screenWidth || oldH != screenHeight || oldRotation != rotation

        val foreground = if (displayChanged) foregroundActivity() else null
        val supportsHandwriting = foreground?.let {
            (it.packageName == NOTE_PACKAGE && it.activityName == NOTE_INSIDE_PAGES_ACTIVITY) ||
                it.packageName == DOC_PACKAGE
        } == true
        val foregroundKey = foreground?.let { "${it.packageName}/${it.activityName}" }
        val toolbarVisible = toolbarWindowVisible()
        val startRotationSync = displayChanged && oldRotation >= 0 && supportsHandwriting &&
            foregroundKey != null && (rotationEpoch != 0L || toolbarVisible) &&
            FloatingPenGuard.hasActiveRects()
        FloatingPenGuard.onDisplayChanged(
            screenWidth, screenHeight, rotation, reason, rotationPending = startRotationSync
        )
        if (!displayChanged) return
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "orientation changed reason=$reason ${oldW}x${oldH}@${oldRotation} " +
                "→ ${screenWidth}x${screenHeight}@$rotation toolbarVisible=$toolbarVisible")
        }

        if (startRotationSync) {
            rotationEpoch += 1L
            rotationStartForegroundKey = foregroundKey
            monitorHandler.removeCallbacks(rotationDisplaySettledRunnable)
            monitorHandler.removeCallbacks(rotationDialogRetryRunnable)
            if (rotationDialogArmed || rotationDialogShown) {
                SubviewLogMonitor.retargetOwnedRotationDialog(rotationEpoch)
            }
            FloatingPenGuard.beginRotationSync(rotationEpoch, reason)
            monitorHandler.postDelayed(
                rotationDisplaySettledRunnable,
                PenGuardTiming.ROTATION_DISPLAY_SETTLE_MS
            )
            Log.i(TAG, "rotation state DISPLAY_SETTLING epoch=$rotationEpoch foreground=$foregroundKey")
        } else {
            if (rotationEpoch != 0L) {
                cancelRotationSync("orientation changed outside handwriting page")
            } else if (supportsHandwriting && !toolbarVisible) {
                Log.i(TAG, "rotation sync skipped: toolbar window hidden reason=$reason")
            }
        }

        val hadPanelOpen = ToolRegistry.handleRotation()

        if (hadPanelOpen && rootView == null && tools.isNotEmpty()) {
            restoreToolbar()
        }

        val lp = layoutParams
        if (lp != null && collapsed && collapsedRoot != null) {
            val w = collapsedRoot!!.width.takeIf { it > 0 } ?: dpToPx(COLLAPSED_HIT_WIDTH_DP)
            val h = collapsedRoot!!.height.takeIf { it > 0 } ?: dpToPx(COLLAPSED_HEIGHT_DP)
            lp.x = if (dockSide == "left") 0 else screenWidth - w
            lp.y = lp.y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        } else if (lp != null && !collapsed && expandedRoot != null) {
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        }

        FloatingBubbleModule.handleOrientationChange()
        AiBubbleModule.handleOrientationChange()
        PaletteBubbleModule.handleOrientationChange()
        DocChromeProbe.request("display: $reason")
    }

    private fun onRotationDisplaySettled(epoch: Long) {
        if (epoch == 0L || epoch != rotationEpoch) return
        val foreground = foregroundActivity()
        val currentKey = foreground?.let { "${it.packageName}/${it.activityName}" }
        if (currentKey != rotationStartForegroundKey) {
            cancelRotationSync("foreground changed during display settle")
            return
        }
        val viewsReady = FloatingPenGuard.refreshAllForRotation(epoch)
        Log.i(TAG, "display settled epoch=$epoch viewsReady=$viewsReady")
        if (!viewsReady) {
            monitorHandler.postDelayed(
                rotationDisplaySettledRunnable,
                PenGuardTiming.ROTATION_VIEWS_RETRY_MS
            )
            return
        }
        showRotationDialogWhenReady(epoch)
    }

    private fun showRotationDialogWhenReady(epoch: Long) {
        if (epoch == 0L || epoch != rotationEpoch || rotationDialogShown || rotationDialogArmed) return
        if (SubviewLogMonitor.isSubviewOpen() || SubviewLogMonitor.isToolbarMenuOpen() ||
            SubviewLogMonitor.isLassoMenuOpen() || ToolRegistry.anyPanelShowing() ||
            FloatingPenGuard.hasBlockingOwnerForRotation()) {
            monitorHandler.postDelayed(rotationDialogRetryRunnable, PenGuardTiming.ROTATION_DIALOG_RETRY_MS)
            return
        }
        if (!SubviewLogMonitor.armOwnedRotationDialog(epoch)) {
            monitorHandler.postDelayed(rotationDialogRetryRunnable, PenGuardTiming.ROTATION_DIALOG_RETRY_MS)
            return
        }
        rotationDialogArmed = true
        Log.i(TAG, "rotation state WAIT_DIALOG epoch=$epoch")
        me.laumss.notipal.ui_common.Dialog.tip(
            reactApplicationContext,
            NativeLocale.t("rotation_sync_message")
        ) {
            monitorHandler.post {
                if (epoch != rotationEpoch || rotationEpoch == 0L) return@post
                Log.i(TAG, "rotation dialog confirmed epoch=$epoch → destroyAll")
                destroyAll()
            }
        }
    }

    private fun cancelRotationSync(reason: String) {
        val epoch = rotationEpoch
        if (epoch != 0L) SubviewLogMonitor.cancelOwnedRotationDialog(epoch)
        monitorHandler.removeCallbacks(rotationDisplaySettledRunnable)
        monitorHandler.removeCallbacks(rotationDialogRetryRunnable)
        rotationEpoch = 0L
        rotationStartForegroundKey = null
        rotationDialogArmed = false
        rotationDialogShown = false
        FloatingPenGuard.cancelRotationSync(reason)
    }

    private fun createCollapsedHandle() {
        if (regionCaptureActive) return
        val ctx = reactApplicationContext
        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        refreshScreenDimensions()

        val w = dpToPx(COLLAPSED_WIDTH_DP)
        val hitW = dpToPx(COLLAPSED_HIT_WIDTH_DP)
        val h = dpToPx(COLLAPSED_HEIGHT_DP)

        val dockLeft = dockSide == "left"
        val stripe = View(ctx).apply {
            background = object : android.graphics.drawable.Drawable() {
                private val strokeW = dpToPx(3) / 2f
                private val radius = dpToPx(6).toFloat()
                private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.FILL
                    color = Color.WHITE
                }
                private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    color = CLR_BORDER
                    strokeWidth = strokeW
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                private val path = android.graphics.Path()
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = bounds
                    val half = strokeW / 2f
                    
                    val rect = if (dockLeft) {
                        android.graphics.RectF(b.left - radius, b.top + half, b.right - half, b.bottom - half)
                    } else {
                        android.graphics.RectF(b.left + half, b.top + half, b.right + radius, b.bottom - half)
                    }
                    canvas.drawRoundRect(rect, radius, radius, fill)
                    canvas.drawRoundRect(rect, radius, radius, line)

                    val cx = b.exactCenterX() + if (dockLeft) -half else half
                    val cy = b.exactCenterY()
                    val armX = dpToPx(5) / 2f
                    val armY = dpToPx(5).toFloat()
                    val dir = if (dockLeft) 1f else -1f
                    path.reset()
                    path.moveTo(cx - dir * armX, cy - armY)
                    path.lineTo(cx + dir * armX, cy)
                    path.lineTo(cx - dir * armX, cy + armY)
                    canvas.drawPath(path, line)
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
        }

        
        
        collapsedRoot = android.widget.FrameLayout(ctx).apply {
            addView(stripe, android.widget.FrameLayout.LayoutParams(w, h).apply {
                gravity = if (dockLeft) Gravity.START else Gravity.END
            })
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            hitW, h, wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dockSide == "left") 0 else screenWidth - hitW
            y = stickyY.takeIf { it >= 0 } ?: (screenHeight / 2 - h / 2)
            y = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        }

        val SWIPE_THRESHOLD = 30
        val collapsedLongPressRunnable = Runnable {
            longPressTriggered = true
            destroyAll()
        }
        collapsedRoot!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    isDragging = false; longPressTriggered = false
                    handler.postDelayed(collapsedLongPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(event)) return@setOnTouchListener true
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacks(collapsedLongPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    if (!longPressTriggered) {
                        if (isDragging) {
                            val dx = event.rawX - startRawX
                            val inward = if (dockSide == "left") dx > SWIPE_THRESHOLD else dx < -SWIPE_THRESHOLD
                            if (inward) switchToExpanded()
                        } else {
                            
                            
                            switchToExpanded()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    true
                }
                else -> false
            }
        }

        val handle = collapsedRoot ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        try {
            wm.addView(handle, lp)
            rootView = handle
        } catch (t: Throwable) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "collapsed handle addView failed: ${t.message}", t)
            }
            try { wm.removeView(handle) } catch (_: Throwable) {}
            rootView = null
            collapsedRoot = null
            layoutParams = null
            throw t
        }
        rootView?.let { FloatingPenGuard.track("toolbar", it) }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "collapsed handle shown, side=$dockSide")
    }

    
    private fun createExpandedToolbar() {
        if (regionCaptureActive) return
        if (currentInstance !== this) {
            pendingShow = false
            return
        }
        if (configPanelBlocksToolbar()) {
            pendingShow = false
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "createExpandedToolbar: config panel owns screen")
            return
        }
        if (rootView?.isAttachedToWindow == true) {
            pendingShow = false
            return
        }
        if (rootView != null) {
            
            
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "createExpandedToolbar: clearing detached root")
            }
            rootView = null
        }

        pendingShow = true
        if (!isReactContextUsable()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "createExpandedToolbar: React context transitioning, defer build")
            }
            scheduleToolbarRetry("react context transitioning")
            return
        }

        try {
            createExpandedToolbarInternal()
        } catch (t: Throwable) {
            Log.e(TAG, "createExpandedToolbar build failed: ${t.message}", t)
            clearToolbarBuildForRetry()
            scheduleToolbarRetry("view construction failed")
        }
    }

    private fun createExpandedToolbarInternal() {
        if (regionCaptureActive) return
        
        
        val ctx = android.view.ContextThemeWrapper(reactApplicationContext, android.R.style.Theme_DeviceDefault_Light)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "createExpandedToolbar package=${ctx.packageName}")
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        refreshScreenDimensions()

        val borderPx = dpToPx(BORDER_WIDTH)
        val cornerR  = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
        val titleH   = dpToPx(TITLE_ROW_H_DP)
        val titleSep = dpToPx(TITLE_SEP_DP)
        
        
        val showClipboard = true
        
        
        val showTextReceive = mosaicMode || !docMode
        val showGotoNote = docMode && !mosaicMode
        val showReturnNote = false
        val showNoteToMosaic = !docMode && !mosaicMode
        val showMosaicPalette = mosaicMode
        val showCollapse = true
        val hasSidebarItems = showClipboard || showTextReceive ||
            showGotoNote || showReturnNote || showNoteToMosaic || showMosaicPalette || showCollapse
        textReceiveBtn = null
        paletteSidebarBtn = null
        clipIconViews.fill(null)
        pagedClipTools.clear()

        expandedRoot = object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return false
                        startX = lp.x; startY = lp.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!TouchInput.isFinger(ev)) return false
                        if (!isDragging && (abs(ev.rawX - startRawX) > 10 || abs(ev.rawY - startRawY) > 10)) {
                            isDragging = true
                            FloatingDragRefresh.begin(this, startX, startY)
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                        startX = lp.x; startY = lp.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!TouchInput.isFinger(ev)) return true
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                        val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                        if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                            isDragging = true
                            FloatingDragRefresh.begin(this, lp.x, lp.y)
                        }
                        if (isDragging) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            if (!FloatingDragRefresh.move(this, lp.x, lp.y)) {
                                try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            isDragging = false
                            refreshScreenDimensions()
                            val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "dragEnd: x=${lp.x} y=${lp.y} expanded=true")
                            
                            FloatingDragRefresh.end(this)
                            savePositionToPrefs(lp.x, lp.y)
                            emitEvent("onToolbarDragEnd", Arguments.createMap().apply {
                                putInt("x", lp.x)
                                putInt("y", lp.y)
                            })
                        }
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(borderPx, CLR_BORDER)
                cornerRadius = cornerR
            }

            setPadding(borderPx, borderPx, borderPx, borderPx)
            clipToPadding = true
        }

        val clipIconSz = dpToPx(CLIP_ICON_DP)
        val clipIconGap = dpToPx(3)
        fun makeClipIcon(i: Int): TextView {
            val slot = i + 1
            val filled = titleClipFilled.getOrElse(i) { false }
            return TextView(ctx).apply {
                text = slot.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                setTextColor(if (filled) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(CLIP_RADIUS_DP).toFloat()
                    if (filled) {
                        setColor(Color.BLACK)
                    } else {
                        setColor(Color.TRANSPARENT)

                    }
                }
                setOnClickListener {
                    resetAutoCollapse()
                    emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
                }
                setOnLongClickListener {
                    resetAutoCollapse()
                    emitEvent("onTitleClipLongPress", Arguments.createMap().apply { putString("slot", slot.toString()) })
                    true
                }
            }
        }

        val sidebarBtnSz = dpToPx(28)
        fun makeSidebarIconBtn(iconId: String, fallbackText: String, action: () -> Unit): View {
            val iconDrawable = loadIconFromAssets(iconId, sidebarBtnSz, CLR_BTN_FG, dpToPx(3))
            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))
                }
            } else {
                TextView(ctx).apply {
                    text = fallbackText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
                    setTextColor(CLR_BTN_FG)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            view.background = null
            if (iconId == NOTE_TO_MOSAIC_TOOL_ID) {
                view.contentDescription = NativeLocale.t("mosaic_note_import")
                view.tooltipText = NativeLocale.t("mosaic_note_import")
            }
            view.layoutParams = LinearLayout.LayoutParams(sidebarBtnSz, sidebarBtnSz).apply {
                marginEnd = dpToPx(1)
            }
            view.setOnClickListener { resetAutoCollapse(); action() }
            return view
        }

        
        fun makeLatchSidebarBtn(iconId: String, fallbackText: String, topMargin: Int, bottomMargin: Int): View {
            val iconDrawable = loadIconFromAssets(iconId, clipIconSz, Color.BLACK, dpToPx(5))
            val btn: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
                }
            } else {
                TextView(ctx).apply {
                    text = fallbackText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            btn.layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                this.topMargin = topMargin
                this.bottomMargin = bottomMargin
                marginEnd = dpToPx(1)
            }
            return btn
        }

        fun addTextReceiveBtn(parent: LinearLayout, topMargin: Int = 0, bottomMargin: Int = 0) {
            val btn = makeLatchSidebarBtn("insert_text", "Tx", topMargin, bottomMargin)
            
            btn.translationY = -dpToPx(4).toFloat()
            btn.setOnClickListener { resetAutoCollapse(); handleTextReceiveTap() }
            textReceiveBtn = btn
            refreshTextReceiveBtn()
            parent.addView(btn)
        }

        fun makeSidebarPaletteBtn(topMargin: Int = 0, bottomMargin: Int = 0): View {
            val tool = ToolItem(INK_PALETTE_TOOL_ID, NativeLocale.t("config_tool_ink_palette"),
                "Ip", INK_PALETTE_TOOL_ID, nameKey = "config_tool_ink_palette")
            return makeLatchSidebarBtn(INK_PALETTE_TOOL_ID, "Ip", topMargin, bottomMargin).also {
                bindInkPaletteButton(it, tool)
                paletteSidebarBtn = it
                refreshPaletteSidebarBtn()
            }
        }

        fun makeSidebarSwapBtn(sz: Int): View {
            val iconDrawable = loadIconFromAssets(COLLAPSE_TOOL_ID, sz, CLR_BTN_FG, dpToPx(5))
            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
                }
            } else {
                TextView(ctx).apply {
                    text = "⋯"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
                    setTextColor(CLR_BTN_FG)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            view.background = null
            view.layoutParams = LinearLayout.LayoutParams(sz, sz)
            view.setOnClickListener {
                dockToNearestEdge()
            }
            collapseBtn = view
            refreshCollapseIconSide()
            
            view.post { refreshCollapseIconSide() }
            return view
        }

        var dragSpacer: View

        if (orientation == "vertical") {

            val titleColW = dpToPx(CLIP_ICON_DP + 6)
            
            val sidebarTopPadding = dpToPx(3)

            val bodyRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            toolContainer = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val p = dpToPx(BORDER_WIDTH + PANEL_PAD_DP) - borderPx
                setPadding(p, dpToPx(4), dpToPx(2), p)
            }
            bodyRow.addView(toolContainer)

            val titleCol = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setBackgroundColor(CLR_SIDEBAR_BG)
                layoutParams = LinearLayout.LayoutParams(titleColW, LinearLayout.LayoutParams.MATCH_PARENT)
                
                val noteMode = !docMode && !mosaicMode
                setPadding(dpToPx(3), sidebarTopPadding,
                    dpToPx(3), dpToPx(if (noteMode) 4 else 2))
                visibility = if (hasSidebarItems) View.VISIBLE else View.GONE
            }

            dragSpacer = View(ctx)

            val (visibleClipCount, clipOffset) = verticalClipWindow()
            val pageSidebarTools = shouldPageSidebarTools()

            var clipSwipeStartY = 0f
            var clipSwipeStartX = 0f
            var clipSwipeCaptured = false
            val clipCol = object : LinearLayout(ctx) {
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            clipSwipeStartX = ev.rawX
                            clipSwipeStartY = ev.rawY
                            clipSwipeCaptured = false
                            
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = Math.abs(ev.rawX - clipSwipeStartX)
                            val dy = Math.abs(ev.rawY - clipSwipeStartY)
                            if (!clipSwipeCaptured && dy > dpToPx(12) && dy > dx) {
                                clipSwipeCaptured = true
                                return true
                            } else if (!clipSwipeCaptured && dx > dpToPx(12) && dx >= dy) {
                                
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return false
                }
                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_UP -> {
                            if (clipSwipeCaptured) {
                                val dy = ev.rawY - clipSwipeStartY
                                val lastPage = lastVerticalClipPage()
                                if (dy < -dpToPx(15) && clipPage < lastPage) {
                                    clipPage += 1; rebuildClipIcons()
                                } else if (dy > dpToPx(15) && clipPage > 0) {
                                    clipPage -= 1; rebuildClipIcons()
                                }
                            }
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return clipSwipeCaptured || ev.action == android.view.MotionEvent.ACTION_UP
                }
            }.apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (vi in 0 until MAX_VISIBLE_CLIPS) {
                val di = clipOffset + vi
                val tv = makeClipIcon(di).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(CLIP_ICON_DP), dpToPx(CLIP_ICON_DP)).apply {
                        bottomMargin = dpToPx(2)
                    }
                    visibility = if (vi < visibleClipCount) View.VISIBLE else View.GONE
                    isEnabled = vi < visibleClipCount
                }
                clipIconViews[vi] = tv
                clipCol.addView(tv)
            }
            if (showClipboard) titleCol.addView(clipCol)

            val sidebarToolParent = if (pageSidebarTools) clipCol else titleCol
            if (showTextReceive) {
                
                addTextReceiveBtn(sidebarToolParent, topMargin = dpToPx(6),
                    bottomMargin = if (pageSidebarTools && !mosaicMode) 0 else dpToPx(1))
                if (pageSidebarTools) textReceiveBtn?.let {
                    pagedClipTools.add(it)
                    it.visibility = if (clipPage == 0) View.VISIBLE else View.GONE
                }
            }

            if (showNoteToMosaic) {
                val noteButton = makeSidebarIconBtn(NOTE_TO_MOSAIC_TOOL_ID, "Nm") {
                    importCurrentNoteToMosaic()
                }.apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(6)
                }
                if (pageSidebarTools) {
                    pagedClipTools.add(noteButton)
                    noteButton.visibility = if (clipPage == 0) View.VISIBLE else View.GONE
                }
                sidebarToolParent.addView(noteButton)
            }

            if (showMosaicPalette) {
                val paletteButton = makeSidebarPaletteBtn(topMargin = dpToPx(6), bottomMargin = dpToPx(1))
                pagedClipTools.add(paletteButton)
                paletteButton.visibility = if (clipPage == 0) View.VISIBLE else View.GONE
                sidebarToolParent.addView(paletteButton)
            }

            if (showGotoNote) {
                titleCol.addView(makeSidebarIconBtn(GOTO_NOTE_TOOL_ID, "N") {
                    launchNoteApp()
                }.apply {
                    val lp = layoutParams as LinearLayout.LayoutParams
                    lp.topMargin = dpToPx(6)
                    
                    lp.bottomMargin = dpToPx(2)
                })
            }

            if (showCollapse) {
                titleCol.addView(makeSidebarSwapBtn(clipIconSz).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin =
                        if (showTextReceive || showGotoNote || showReturnNote || showNoteToMosaic || showMosaicPalette) dpToPx(6) else dpToPx(1)
                })
            }
            bodyRow.addView(titleCol)

            expandedRoot!!.addView(bodyRow)
            rebuildButtons()

        } else {

            val titleRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, titleH
                )
                setBackgroundColor(CLR_SIDEBAR_BG)
                visibility = if (hasSidebarItems) View.VISIBLE else View.GONE
            }
            val clipRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, titleH
                )
                setPadding(dpToPx(2), 0, dpToPx(1), 0)
            }
            for (i in 0 until horizontalClipCount()) {
                val tv = makeClipIcon(i).apply {
                    layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                        marginEnd = dpToPx(2)
                    }
                }
                clipIconViews[i] = tv
                clipRow.addView(tv)
            }
            if (showClipboard) titleRow.addView(clipRow)

            if (showClipboard && (showTextReceive || showGotoNote || showReturnNote || showNoteToMosaic || showMosaicPalette || showCollapse)) {
                titleRow.addView(View(ctx).apply {
                    setBackgroundColor(CLR_SEP)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(1), (titleH * 0.6f).toInt()).apply {
                        marginStart = dpToPx(2)
                        marginEnd = dpToPx(2)
                    }
                })
            }

            dragSpacer = View(ctx)

            if (showTextReceive) addTextReceiveBtn(titleRow)

            if (showNoteToMosaic) {
                titleRow.addView(makeSidebarIconBtn(NOTE_TO_MOSAIC_TOOL_ID, "Nm") {
                    importCurrentNoteToMosaic()
                })
            }

            if (showMosaicPalette) titleRow.addView(makeSidebarPaletteBtn())

            if (showGotoNote) {
                titleRow.addView(makeSidebarIconBtn(GOTO_NOTE_TOOL_ID, "N") {
                    launchNoteApp()
                })
            }

            titleRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, titleH, 1f)
            })

            if (showCollapse) titleRow.addView(makeSidebarSwapBtn(sidebarBtnSz))
            expandedRoot!!.addView(titleRow)

            toolContainer = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val p = dpToPx(BORDER_WIDTH + PANEL_PAD_DP) - borderPx; setPadding(p, p, p, p)
            }
            expandedRoot!!.addView(toolContainer)
            rebuildButtons()
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

        loadPositionFromPrefs()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            x = if (stickyX >= 0) stickyX else dpToPx(24)
            y = if (stickyY >= 0) stickyY else screenHeight / 2 - dpToPx(80)
        }

        val toolbarRoot = expandedRoot
            ?: throw IllegalStateException("expanded toolbar root missing")
        val toolbarParams = layoutParams
            ?: throw IllegalStateException("expanded toolbar layout params missing")
        val wm = windowManager
            ?: throw IllegalStateException("WindowManager unavailable")
        try {
            wm.addView(toolbarRoot, toolbarParams)
            
            
            rootView = toolbarRoot
        } catch (t: Throwable) {
            Log.w(TAG, "createExpandedToolbar addView failed: ${t.message}", t)
            try { wm.removeView(toolbarRoot) } catch (_: Throwable) {}
            clearToolbarBuildForRetry()
            scheduleToolbarRetry("WindowManager.addView failed", 500L)
            return
        }

        pendingShow = false
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "createExpandedToolbar addView complete type=$wmType " +
                "x=${toolbarParams.x} y=${toolbarParams.y}")
        }
        
        
        try {
            startForegroundMonitor()
        } catch (t: Throwable) {
            Log.w(TAG, "toolbar monitor start deferred: ${t.message}", t)
        }
        try {
            FloatingPenGuard.track("toolbar", toolbarRoot)
        } catch (t: Throwable) {
            Log.w(TAG, "toolbar pen guard tracking deferred: ${t.message}", t)
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "expanded toolbar shown, ${tools.size} tools")

        
        
        scheduleHostCloseAfterToolbarShown()

        
        
        toolbarRoot.post { resetAutoCollapse() }
    }

    
    private fun loadIconFromAssets(toolId: String, sizePx: Int, tintColor: Int, padPx: Int): android.graphics.drawable.Drawable? {
        val assetName = when (toolId) {
            "insert_image"          -> "icons/ic_tool_image.xml"
            
            "insert_doc_screenshot" -> if (docMode) "icons/ic_tool_doc_capture.xml" else "icons/ic_tool_doc.xml"
            INSERT_TEXT_TOOL_ID     -> "icons/ic_tool_text.xml"
            "insert_link"           -> "icons/ic_tool_link.xml"
            AI_RELAY_TOOL_ID         -> "icons/ic_tool_ai_relay.xml"
            INK_PALETTE_TOOL_ID      -> "icons/ic_tool_palette.xml"
            SMART_LASSO_TOOL_ID, "screenshot_ai" -> "icons/ic_tool_lasso_ai.xml"
            "more_vert"             -> "icons/ic_tool_more.xml"
            COLLAPSE_TOOL_ID        -> "icons/ic_tool_collapse.xml"
            GOTO_NOTE_TOOL_ID       -> "icons/ic_edit_to_note.xml"
            NOTE_TO_MOSAIC_TOOL_ID  -> "icons/ic_tool_note_to_mosaic.xml"
            else                    -> return null
        }
        
        val drawPx = (sizePx - padPx * 2).coerceAtLeast(1)
        val icon = me.laumss.notipal.ui_common.UiUtils.loadAssetIcon(
            reactApplicationContext, assetName, drawPx, tintColor, strokeDeltaPx = TOOLBAR_ICON_STROKE_DELTA_PX)
        if (icon == null && BuildConfig.ENABLE_DEBUG) Log.w(TAG, "loadIconFromAssets $assetName failed")
        return icon
    }

    private fun isReactContextUsable(): Boolean = try {
        reactApplicationContext.hasActiveReactInstance()
    } catch (_: Throwable) { false }

    
    private var paletteEntryRequest = 0L

    internal fun openPaletteSettingsFromBubble(view: View) {
        val tool = ToolItem(INK_PALETTE_TOOL_ID, NativeLocale.t("config_tool_ink_palette"),
            "Ip", INK_PALETTE_TOOL_ID, nameKey = "config_tool_ink_palette")
        handleInkPaletteEntry(tool, view, forcePanel = true)
    }

    private fun handleInkPaletteEntry(tool: ToolItem, view: View, forcePanel: Boolean) {
        val request = ++paletteEntryRequest
        resetAutoCollapse()
        if (forcePanel) {
            handleToolTap(tool, view)
            return
        }
        val mosaic = MosaicLink.isBoardSurface()
        if (mosaic) {
            MosaicLink.requestPalette(reactApplicationContext) { result ->
                handler.post {
                    if (request != paletteEntryRequest || !MosaicLink.isBoardSurface()) return@post
                    if (!result.optBoolean("success")) {
                        Dialog.tip(reactApplicationContext, NativeLocale.t("palette_mosaic_retry"))
                    } else if ((result.optJSONArray("strokeIds")?.length() ?: 0) > 0) {
                        showPalettePanel(result.toString())
                    } else {
                        PaletteBubbleModule.toggleStatic()
                    }
                }
            }
            return
        }
        fun resolved(hasSelection: Boolean) {
            handler.post {
                if (request != paletteEntryRequest || MosaicLink.isBoardSurface()) return@post
                if (hasSelection) handleToolTap(tool, view)
                else PaletteBubbleModule.toggleStatic()
            }
        }
        try {
            val comm = reactApplicationContext.getNativeModule(
                com.ratta.supernote.pluginlib.modules.CommAPIModule::class.java)
            if (comm == null) { resolved(false); return }
            comm.getLassoRect(PromiseImpl(
                Callback { args ->
                    val response = args.firstOrNull() as? ReadableMap
                    val selected = response?.hasKey("success") == true && response.getBoolean("success") &&
                        response.hasKey("result") && !response.isNull("result")
                    resolved(selected)
                },
                Callback { resolved(false) },
            ))
        } catch (e: Exception) {
            Log.w(TAG, "palette entry selection lookup failed", e)
            resolved(false)
        }
    }

    private fun bindInkPaletteButton(view: View, tool: ToolItem) {
        val owner = PenGuardOwner.toolbarPress(tool.id)
        view.setOnClickListener { handleInkPaletteEntry(tool, view, forcePanel = false) }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> FloatingPenGuard.setFullScreenActive(true, owner)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.postDelayed({
                    FloatingPenGuard.setFullScreenActive(false, owner)
                }, PenGuardTiming.TOOLBAR_PRESS_RELEASE_MS)
            }
            false
        }
        view.setOnLongClickListener {
            handleInkPaletteEntry(tool, view, forcePanel = true)
            handler.postDelayed(
                { FloatingPenGuard.setFullScreenActive(false, owner) },
                PenGuardTiming.TOOLBAR_LONG_PRESS_RELEASE_MS
            )
            true
        }
    }

    private fun rebuildButtons() {
        val c = toolContainer ?: return
        if (!isReactContextUsable()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "rebuildButtons: React context transitioning, keep current root")
            }
            pendingShow = true
            scheduleToolbarRetry(
                "rebuildButtons: React context transitioning",
                rebuildExistingRoot = rootView != null,
            )
            return
        }
        c.removeAllViews()
        try {
        val ctx = reactApplicationContext
        val btnSz = dpToPx(if (orientation == "vertical") VERTICAL_BTN_SIZE_DP else BTN_SIZE_DP)
        val gap = dpToPx(BTN_GAP_DP)

        val actionTools = tools.filterNot { isToolbarUtilityTool(it.id) }

        fun makeToolButton(idx: Int, tool: ToolItem): View {

            val isActive = (tool.latches && activeModeIds.contains(tool.id)) ||
                activeModeIds.contains(tool.action)
            val activeBg = CLR_BTN_ACT
            val inactiveFg = CLR_BTN_FG

            val iconPad = dpToPx(if (orientation == "vertical") 10 else 12)
            val iconDrawable = loadIconFromAssets(tool.id, btnSz, if (isActive) android.graphics.Color.WHITE else inactiveFg, iconPad)
            if (idx == 0) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "iconLookup: tool=${tool.id} loaded=${iconDrawable != null}")
            }

            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(iconPad, iconPad, iconPad, iconPad)
                    background = GradientDrawable().apply {
                        setColor(if (isActive) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            } else {
                TextView(ctx).apply {
                    text = tool.icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP,
                        sp(if (orientation == "vertical") 18f else BTN_TEXT_SIZE_SP))
                    setTextColor(if (isActive) Color.WHITE else inactiveFg)
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(if (isActive) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            }

            view.layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginStart = gap / 2; marginEnd = gap - gap / 2
            }
            if (tool.action == INK_PALETTE_TOOL_ID) {
                bindInkPaletteButton(view, tool)
                return view
            }
            view.setOnClickListener { handleToolTap(tool, view) }
            val hasLongPressAction = tool.action == "insert_doc_screenshot" ||
                tool.action == SMART_LASSO_TOOL_ID
            val pressGuardOwner = PenGuardOwner.toolbarPress(tool.id)
            if (hasLongPressAction) {
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            FloatingPenGuard.setFullScreenActive(true, pressGuardOwner)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            handler.postDelayed({
                                FloatingPenGuard.setFullScreenActive(false, pressGuardOwner)
                            }, PenGuardTiming.TOOLBAR_PRESS_RELEASE_MS)
                    }
                    false
                }
            }
            view.setOnLongClickListener {
                if (tool.action == "insert_doc_screenshot") {
                    openDocScreenshotPanel()
                } else if (tool.action == SMART_LASSO_TOOL_ID) {
                    handleSendLongPress()
                } else {
                    emitEvent("onToolLongPress", Arguments.createMap().apply {
                        putString("toolId", tool.id); putString("toolName", tool.name)
                    })
                }
                handler.postDelayed({
                    FloatingPenGuard.setFullScreenActive(false, pressGuardOwner)
                }, PenGuardTiming.TOOLBAR_LONG_PRESS_RELEASE_MS)
                true
            }
            return view
        }

        if (orientation == "vertical") {

            val col = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((i, t) in actionTools.withIndex()) {
                col.addView(makeToolButton(i, t).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = gap }
                })
            }
            c.addView(col)
        } else {

            val row = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((i, t) in actionTools.withIndex()) row.addView(makeToolButton(i, t))
            c.addView(row)
        }

        refreshTextReceiveBtn()
        refreshPaletteSidebarBtn()
        try {
            windowManager?.updateViewLayout(rootView, layoutParams)
        } catch (_: Exception) {}
        if (rootView != null) pendingShow = false
        } catch (t: Throwable) {
            
            
            Log.w(TAG, "rebuildButtons deferred: ${t.message}", t)
            pendingShow = true
            scheduleToolbarRetry(
                "rebuildButtons exception",
                rebuildExistingRoot = rootView != null,
            )
        }
    }

    private fun snapToEdge() {
        refreshScreenDimensions()
        val lp = layoutParams ?: return; val root = rootView ?: return
        val vw = root.measuredWidth.takeIf { it > 0 } ?: root.width
        val vh = root.measuredHeight.takeIf { it > 0 } ?: root.height
        if (vw <= 0 || vh <= 0) return
        val cx = lp.x + vw / 2

        if (lp.x < SNAP_THRESHOLD) lp.x = 0
        else if (screenWidth - (lp.x + vw) < SNAP_THRESHOLD) lp.x = screenWidth - vw
        else if (abs(cx - screenWidth / 2) < SNAP_THRESHOLD) lp.x = screenWidth / 2 - vw / 2

        if (lp.y < SNAP_THRESHOLD) lp.y = 0
        else if (screenHeight - (lp.y + vh) < SNAP_THRESHOLD) lp.y = screenHeight - vh

        lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
        try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
    }

    private fun dockToNearestEdge() {
        refreshScreenDimensions()
        val lp = layoutParams ?: return
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 } ?: 0
        val cx = lp.x + vw / 2
        dockSide = if (cx <= screenWidth / 2) "left" else "right"
        preDockX = lp.x; preDockY = lp.y
        val snapX = if (dockSide == "left") 0 else screenWidth - vw
        savePositionToPrefs(snapX, lp.y)
        switchToCollapsed()
    }

    private fun handleTextReceiveTap() {
        val view = textReceiveBtn ?: return
        handleToolTap(
            ToolItem(
                INSERT_TEXT_TOOL_ID,
                NativeLocale.t("config_tool_text"),
                "Tx",
                INSERT_TEXT_TOOL_ID,
                latches = true,
                nameKey = "config_tool_text"
            ),
            view
        )
    }

    private fun refreshTextReceiveBtn() {
        val view = textReceiveBtn ?: return
        applySidebarLatchState(view, activeModeIds.contains(INSERT_TEXT_TOOL_ID))
    }

    
    private fun refreshPaletteSidebarBtn() {
        val view = paletteSidebarBtn ?: return
        applySidebarLatchState(view, activeModeIds.contains(INK_PALETTE_TOOL_ID))
    }

    private fun applySidebarLatchState(view: View, active: Boolean) {
        val fg = if (active) Color.WHITE else Color.BLACK
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(CLIP_RADIUS_DP).toFloat()
            setColor(if (active) Color.BLACK else Color.TRANSPARENT)
        }
        when (view) {
            is ImageView -> view.setColorFilter(fg)
            is TextView -> view.setTextColor(fg)
        }
    }

    private fun handleToolTap(tool: ToolItem, view: View) {
        if (tool.action == GOTO_NOTE_TOOL_ID) {
            launchNoteApp()
            return
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] handleToolTap id=${tool.id} action=${tool.action} latches=${tool.latches}")
        if (tool.latches) {
            if (activeModeIds.contains(tool.id)) {
                activeModeIds.remove(tool.id)
                emitEvent("onToolModeExit", Arguments.createMap().apply {
                    putString("toolId", tool.id)
                    putString("toolAction", tool.action)
                })
                rebuildButtons()
                return
            } else {
                activeModeIds.add(tool.id)
                rebuildButtons()
            }
        } else {
            flashCell(view)
        }

        if (tool.action == INK_PALETTE_TOOL_ID) {
            if (MosaicLink.isBoardSurface()) {
                MosaicLink.requestPalette(reactApplicationContext) { result ->
                    if (result.optBoolean("success")) showPalettePanel(result.toString())
                    else Dialog.tip(reactApplicationContext, NativeLocale.t("palette_mosaic_retry"))
                }
                return
            }
            emitEvent("onToolTap", Arguments.createMap().apply {
                putString("toolId", tool.id)
                putString("toolAction", tool.action)
            })
            return
        }

        
        
        
        if (docMode && !MosaicLink.isBoardSurface() && tool.action == "insert_doc_screenshot") {
            handleDocScreenshotCrop()
            return
        }

        val isNativePanel = tool.action == "lasso_send"

        if (isNativePanel) {
            removeAll()
            when {
                tool.action == "lasso_send" -> {
                    
                    
                    
                    
                    
                    requestPluginFileReadPermission { granted ->
                        handler.post {
                            if (!granted) {
                                android.widget.Toast.makeText(
                                    reactApplicationContext,
                                    NativeLocale.t("file_read_permission_needed"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                openPluginSettingsAfterFileReadDenied()
                                restoreToolbar()
                                return@post
                            }
                            pendingScreen = "nativeSendHelper"
                            openNativePanel("send") {
                                SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
                            }
                            handler.postDelayed({
                                
                                
                                if (pendingScreen != "nativeSendHelper") return@postDelayed
                                callShowPluginView()
                                emitOpenMainWithRetries("nativeSendHelper")
                            }, 150)
                        }
                    }
                }
            }
        } else {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] emit onToolTap id=${tool.id} action=${tool.action}")
            emitEvent("onToolTap", Arguments.createMap().apply {
                putString("toolId", tool.id); putString("toolAction", tool.action)
                putString("toolName", tool.name)
            })
        }
    }

    private fun flashCell(v: View) {
        val bg = v.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor("#0F0F10"))
        if (v is TextView) v.setTextColor(Color.WHITE)
        if (v is ImageView) v.setColorFilter(Color.WHITE)
        handler.postDelayed({
            bg.setColor(Color.TRANSPARENT)
            if (v is TextView) v.setTextColor(CLR_BTN_FG)
            if (v is ImageView) v.setColorFilter(CLR_BTN_FG)
        }, 120L)
    }

    @ReactMethod
    fun queryActiveMode(promise: Promise) {

        val ids = synchronized(activeModeIds) { activeModeIds.toList() }
        val pick = ids.firstOrNull { it == "insert_text" } ?: ids.firstOrNull()
        promise.resolve(pick)
    }

    @ReactMethod
    fun exitActiveMode() {
        UiThreadUtil.runOnUiThread { rebuildButtons() }
        val changed = activeModeIds.removeAll(LATCHING_ACTIONS)
        if (changed) UiThreadUtil.runOnUiThread { rebuildButtons() }
    }

    @ReactMethod
    fun exitMode(toolId: String) {
        if (activeModeIds.remove(toolId)) {
            UiThreadUtil.runOnUiThread { rebuildButtons() }
        }
    }

    @ReactMethod
    fun setActiveModes(json: String) {
        try {
            val arr = org.json.JSONArray(json)
            synchronized(activeModeIds) {

                val keepPalette = INK_PALETTE_TOOL_ID in activeModeIds
                activeModeIds.clear()
                for (i in 0 until arr.length()) {
                    activeModeIds.add(arr.getString(i))
                }
                if (keepPalette) activeModeIds.add(INK_PALETTE_TOOL_ID)
            }
            UiThreadUtil.runOnUiThread { rebuildButtons() }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "setActiveModes: ${e.message}")
        }
    }

    fun setToolActive(toolId: String, active: Boolean) {
        val changed = if (active) activeModeIds.add(toolId) else activeModeIds.remove(toolId)
        if (changed) {
            UiThreadUtil.runOnUiThread {
                if (collapsed || toolContainer == null) return@runOnUiThread
                
                if (tools.any { it.id == toolId || it.action == toolId }) {
                    rebuildButtons()
                } else {
                    refreshTextReceiveBtn()
                    refreshPaletteSidebarBtn()
                }
            }
        }
    }

    @ReactMethod
    fun closeAllForSettings() {
        handler.post {

            ToolRegistry.hideAll()

            removeAll()

            activeModeIds.clear()

            pendingScreen = ""
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "closeAllForSettings: all overlays cleared")
        }
    }

    private fun parseToolEntries(arr: JSONArray): List<Pair<ToolItem, Boolean>> {
        val result = ArrayList<Pair<ToolItem, Boolean>>()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val action = o.optString("action", "")
                if (!isCatalogTool(id, action) || result.any { it.first.id == id }) continue
                result.add(
                    ToolItem(
                        id,
                        o.optString("name", ""),
                        o.optString("icon", "?"),
                        action,
                        action in LATCHING_ACTIONS,
                        defaultToolNameKey(id)
                    ) to o.optBoolean("hidden", false)
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "parseToolEntries: ${e.message}")
        }
        return result
    }

    private fun removeAll() {
        toolbarBuildGeneration += 1L
        toolbarRetryRunnable?.let(handler::removeCallbacks)
        toolbarRetryRunnable = null
        cancelHostCloseIntent()
        pendingShow = false
        handler.removeCallbacks(autoCollapseRunnable)
        if (rootView != null) {
            rootView?.let { FloatingDragRefresh.endNow(it) }
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        expandedRoot = null; toolContainer = null; collapsedRoot = null; layoutParams = null
        textReceiveBtn = null
        paletteSidebarBtn = null

        clipIconViews = arrayOfNulls(MAX_VISIBLE_CLIPS)
        pagedClipTools.clear()
    }

    private fun hideAllNativePanels() = ToolRegistry.hideAll()
    private fun suspendAllNativePanels() {
        if (configPanelBlocksToolbar()) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "suspendAllNativePanels: config panel active, skip")
            return
        }
        ToolRegistry.suspendAll()
    }
    private fun resumeAllNativePanels() {
        if (!areFloatingOverlaysSuspendedForPalette()) ToolRegistry.resumeAll()
    }

    private fun parkAllOverlaysForPalette() {
        paletteOverlaysParked = true
        cancelScheduledRestore()
        markToolbarForRestore()
        if (rootView != null) removeAll()
        suspendAllNativePanels()
        FloatingBubbleModule.hideStatic()
        AiBubbleModule.hideStatic()
        PaletteBubbleModule.hideStatic()
        ScreenshotBubble.hideForPalette()
    }

    private val density: Float get() = reactApplicationContext.resources.displayMetrics.density

    private val toolbarExtraScale: Float get() {
        val dm = reactApplicationContext.resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return if (longSide == 2560 && shortSide == 1920) 1.1f else 1.0f
    }
    private val scaleFactor: Float get() = maxOf(0.86f, me.laumss.notipal.ui_common.ScreenScale.toolbarFactor(reactApplicationContext)) * toolbarExtraScale
    private fun dpToPx(dp: Int): Int = (dp * density * scaleFactor).roundToInt()
    private fun sp(v: Float): Float = v * scaleFactor

    private fun emitOpenMainWithRetries(guardScreen: String) {
        for (delay in RETRY_DELAYS_MS) {
            handler.postDelayed({
                if (pendingScreen == guardScreen) {
                    emitEvent("onToolbarOpenMain", Arguments.createMap())
                }
            }, delay)
        }
    }

    private inline fun withNativePluginManager(label: String, block: (Any) -> Unit) {
        try {
            val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "$label: NativePluginManager not found"); return
            }
            block(pm)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "$label: ${e.message}", e)
        }
    }

    override fun emitEvent(name: String, params: WritableMap) {
        try {
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, params)
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping toolbar alive (rootView=${rootView != null})")


        HostPenSignals.endPaletteApplyGuard()
        super.onCatalystInstanceDestroy()
    }

    private var activeImageInsertId: String? = null

    internal fun prepareForImageReceive(onReady: (Boolean) -> Unit) {
        handler.post {
            if (MosaicLink.isBoardSurface()) {
                onReady(true)
                return@post
            }
            
            clearMultiInsert("incoming image dialog")
            insertNextChainActive = false
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeout = Runnable {
                if (completed.compareAndSet(false, true)) {
                    Log.w(TAG, "[INSERT-DBG/Kt] receive preparation: lasso close timed out")
                    onReady(false)
                }
            }
            fun finish(success: Boolean) {
                handler.post {
                    if (!completed.compareAndSet(false, true)) return@post
                    handler.removeCallbacks(timeout)
                    Log.i(TAG, "[INSERT-DBG/Kt] receive preparation: lasso close success=$success")
                    
                    
                    if (success) handler.postDelayed({ onReady(true) }, 650L)
                    else onReady(false)
                }
            }
            handler.postDelayed(timeout, 2000L)
            try {
                val comm = reactApplicationContext.getNativeModule(
                    com.ratta.supernote.pluginlib.modules.CommAPIModule::class.java)
                if (comm == null) finish(false)
                else comm.setLassoBoxState(2.0, PromiseImpl(
                    Callback { args ->
                        val result = args.firstOrNull() as? ReadableMap
                        finish(result?.hasKey("success") == true && result.getBoolean("success"))
                    },
                    Callback { finish(false) },
                ))
            } catch (e: Exception) {
                Log.w(TAG, "[INSERT-DBG/Kt] receive preparation failed", e)
                finish(false)
            }
        }
    }

    @ReactMethod
    fun prepareImageForInsertion(path: String, promise: Promise) {
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val prepared = me.laumss.notipal.render.InsertImageSource.prepare(
                    path, reactApplicationContext.cacheDir)
                Log.i(TAG, "[INSERT-DBG/Kt] prepared image: $path -> $prepared")
                promise.resolve(prepared)
            } catch (e: Exception) {
                promise.reject("IMAGE_PREPARE_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun imageInsertFinished(requestId: String, path: String, success: Boolean) {
        handler.post {
            if (activeImageInsertId != requestId) return@post
            activeImageInsertId = null
            if (success) return@post 
            synchronized(ImagePanel::class.java) { ImagePanel.pendingReceivedDeletes.remove(path) }
            clearMultiInsert("image insertion failed")
            insertNextChainActive = false
            HostPenSignals.endInsertImageGuard()
            Log.w(TAG, "[INSERT-DBG/Kt] insertion failed; release guard and restore toolbar: $path")
            if (hasToolbarSession()) {
                toolbarRestorePending = true
                scheduleRestore(RESTORE_DELAY_NORMAL, "image insertion failed")
            }
            Dialog.tip(reactApplicationContext, NativeLocale.t("image_insert_failed"))
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun consumeReceivedImageDelete(path: String): Boolean =
        synchronized(ImagePanel::class.java) { ImagePanel.pendingReceivedDeletes.remove(path) }

    fun requestInsertImage(path: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] requestInsertImage: $path (currentInstance=${currentInstance === this})")
        insertPluginViewClosed = false


        
        
        if (!MosaicLink.isBoardSurface()) HostPenSignals.beginInsertImageGuard()

        handler.post {
            val requestId = System.nanoTime().toString()
            activeImageInsertId = requestId
            val retryDelays = longArrayOf(0, 300, 750)
            for (delay in retryDelays) {
                handler.postDelayed({
                    if (activeImageInsertId != requestId) return@postDelayed
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] emit nativeInsertImage (delay=${delay}ms)")
                    emitEvent("nativeInsertImage", Arguments.createMap().apply {
                        putString("path", path)
                        putString("requestId", requestId)
                        if (insertNextChainActive) putBoolean("fromInsertNext", true)
                    })
                }, delay)
            }
        }
    }

    @ReactMethod
    override fun beginPaletteApply() {
        if (paletteApplyInFlight) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "beginPaletteApply ignored: transaction already in flight")
            return
        }
        paletteApplyInFlight = true
        setPaletteSelectionHold(false)
        HostPenSignals.beginPaletteApplyGuard()
        if (Looper.myLooper() == Looper.getMainLooper()) parkAllOverlaysForPalette()
        else handler.post { parkAllOverlaysForPalette() }
    }


    @ReactMethod
    fun paletteApplyFinished() {
        
        
        
        
        handler.post {
            paletteApplyInFlight = false
            if (paletteSelectionHold && docMode) {
                HostPenSignals.armDocLassoHold("palette guard handoff", confirmed = true)
            }
            HostPenSignals.endPaletteApplyGuard()
            if (!paletteSelectionHold) {
                
                
                
                
                HostPenSignals.releaseDocLassoHold("palette apply finished without selection")
            }
            if (!paletteSelectionHold && !SubviewLogMonitor.isLassoMenuOpen()) {
                
                
                
                
                HostPenSignals.scheduleLassoMenuRelease()
                scheduleRestore(RESTORE_DELAY_NORMAL, "palette apply finished")
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "palette-apply guard released by JS")
        }
    }

    
    @ReactMethod
    fun finishPaletteApply() {
        handler.post {
            val panel = PalettePanel.currentInstance
            if (panel?.isShowing == true) {
                panel.hide()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "palette panel hidden after JS apply")
            }
        }
    }

    
    
    
    
    
    @ReactMethod
    fun parkToolbarForLasso(holdForSelection: Boolean) {
        
        
        
        setPaletteSelectionHold(holdForSelection)
        
        
        if (holdForSelection && docMode) {
            HostPenSignals.armDocLassoHold("palette reselected", confirmed = true)
        }
        handler.post {
            parkAllOverlaysForPalette()
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "toolbar parked for palette apply hold=$holdForSelection pending=$toolbarRestorePending")
            }
            if (!holdForSelection) scheduleRestore(RESTORE_DELAY_NORMAL, "palette apply done (no selection)")
        }
    }

    @ReactMethod
    override fun restoreToolbar() {
        handler.post {
            if (currentInstance !== this@FloatingToolbarModule) return@post
            
            
            toolbarRestorePending = toolbarRestorePending || rootView != null || tools.isNotEmpty()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] restoreToolbar: tools=${tools.size} currentInstance=${currentInstance === this} rootView=${rootView != null} collapsed=$collapsed")
            evaluateRestore("restoreToolbar request")
        }
    }

    
    internal fun restoreToolbarWindow() {
        if (regionCaptureActive) return
        if (collapsed) createCollapsedHandle() else createExpandedToolbar()
    }

    @ReactMethod
    fun requestClosePluginView() {
        handler.post {
            callClosePluginView()
        }
    }

    @ReactMethod
    fun setLassoData(text: String, imagePathsJson: String, linkedFilesJson: String?) {
        handler.post {
            val paths = mutableListOf<String>()
            try {
                val arr = JSONArray(imagePathsJson)
                for (i in 0 until arr.length()) paths.add(arr.getString(i))
            } catch (_: Exception) {}

            val linkedFiles = mutableListOf<Triple<String, Int, String>>()
            try {
                val lfArr = JSONArray(linkedFilesJson ?: "[]")
                for (i in 0 until lfArr.length()) {
                    val obj = lfArr.getJSONObject(i)
                    linkedFiles.add(Triple(
                        obj.getString("path"),
                        obj.optInt("linkType", -1),
                        obj.optString("label", "file")
                    ))
                }
            } catch (_: Exception) {}

            val panel = SendPanel.currentInstance
            if (panel != null) {
                panel.updateLassoData(text, paths, linkedFiles)
            } else {
                
                SendPanel.stageLassoData(text, paths, linkedFiles)
            }
            pendingScreen = ""
        }
    }

    
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getNoteForegroundElapsedMs(): Double {
        if (!isInNoteApp()) return -1.0
        val since = noteForegroundSinceMs
        if (since == 0L) return 1e9
        return (android.os.SystemClock.elapsedRealtime() - since).toDouble()
    }

    
    fun startMultiImageInsert(paths: List<String>, fromReceived: Boolean) {
        if (paths.isEmpty()) return
        if (MosaicLink.isBoardSurface()) {
            if (fromReceived) synchronized(ImagePanel::class.java) { ImagePanel.pendingReceivedDeletes.addAll(paths) }
            kotlin.concurrent.thread(isDaemon = true) {
                for (p in paths) {
                    val ok = MosaicLink.enqueueImage(p, "inkling-insert-image")
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] multi enqueue to Mosaic ok=$ok path=$p")
                }
                handler.post { restoreToolbar() }
            }
            return
        }
        clearMultiInsert("new multi insert")
        multiInsertQueue.addAll(paths.drop(1))
        multiInsertFromReceived = fromReceived
        HostPenSignals.beginInsertImageGuard()
        handler.postDelayed({ insertMultiImage(paths.first()) }, 300)
    }

    private fun insertMultiImage(path: String) {
        if (multiInsertFromReceived) {
            
            synchronized(ImagePanel::class.java) { ImagePanel.pendingReceivedDeletes.add(path) }
        }
        multiInsertAwaitingSelection = true
        monitorHandler.removeCallbacks(multiInsertAwaitTimeout)
        monitorHandler.postDelayed(multiInsertAwaitTimeout, MULTI_INSERT_AWAIT_MAX_MS)
        requestInsertImage(path)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun drainReceivedDeletes(): String? {
        synchronized(ImagePanel::class.java) {
            val list = ImagePanel.pendingReceivedDeletes
            if (list.isEmpty()) return null
            val json = org.json.JSONArray(list).toString()
            list.clear()
            return json
        }
    }

    fun startMultiDocLinkInsert(paths: List<String>) {
        if (paths.isEmpty()) return
        clearMultiInsert("new document link insert")
        multiInsertDocLinks = true
        multiInsertFromReceived = false
        multiInsertQueue.addAll(paths.drop(1))
        cancelScheduledRestore()
        markToolbarForRestore()
        if (rootView != null) removeAll()
        HostPenSignals.beginInsertImageGuard()
        handler.postDelayed({ insertMultiDocLink(paths.first()) }, 300L)
    }

    private fun insertMultiDocLink(path: String) {
        if (!multiInsertDocLinks) return
        val requestId = System.nanoTime().toString()
        activeDocLinkRequest = requestId
        multiInsertAwaitingSelection = true
        HostPenSignals.beginInsertImageGuard()
        monitorHandler.removeCallbacks(multiInsertAwaitTimeout)
        monitorHandler.postDelayed(multiInsertAwaitTimeout, MULTI_INSERT_AWAIT_MAX_MS)
        emitEvent("nativeInsertDocLink", Arguments.createMap().apply {
            putString("path", path)
            putString("linkName", java.io.File(path).nameWithoutExtension)
            putString("requestId", requestId)
            putString("targetFile", docLinkTargetFile)
            putInt("targetPage", docLinkTargetPage)
        })
    }

    @ReactMethod
    fun docLinkInsertFinished(requestId: String, selectionReady: Boolean, targetFile: String, targetPage: Int) {
        handler.post {
            if (activeDocLinkRequest != requestId) return@post
            activeDocLinkRequest = null
            if (selectionReady) {
                docLinkTargetFile = targetFile
                docLinkTargetPage = targetPage
                onMultiInsertSelectionShown()
                HostPenSignals.endInsertImageGuard()
                Log.i(TAG, "document link ready; awaiting selection cancellation remaining=${multiInsertQueue.size}")
            } else {
                clearMultiInsert("document link insertion or selection failed")
                HostPenSignals.endInsertImageGuard()
                scheduleRestore(RESTORE_DELAY_NORMAL, "document link insertion ended")
            }
        }
    }

    @ReactMethod
    fun showImagePanel() {
        handler.post {
            
            
            
            markToolbarForRestore()
            removeAll()
            openNativePanel("image") {
                ImagePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
            }
        }
    }

    @ReactMethod
    fun showDocLinkPanel(currentFilePath: String?) {
        handler.post {
            removeAll()
            openNativePanel("doc") {
                DocLinkPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(currentFilePath)
            }
        }
    }

    @ReactMethod
    fun showPalettePanel(infoJson: String) {
        handler.post {



            markToolbarForRestore()
            removeAll()
            openNativePanel("palette") {
                PalettePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(infoJson)
            }
        }
    }

    @ReactMethod
    fun toggleScreenshotBubble(mode: String?) {
        handler.post {
            if (requestedToolbarDocMode(mode)) {
                
                
                setToolbarAppMode(true, "doc toolbar button")
                showToolbarNow(true)
                return@post
            }
            if (ScreenshotBubble.isShowing) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: hiding")
                ScreenshotBubble.pendingReshow = false
                ScreenshotBubble.hide()
            } else {
                if (stitchCommitPending || screenshotCapturePending || ScreenshotBubble.pendingReshow) {
                    
                    
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: ignored, screenshot flow pending")
                    return@post
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: showing")
                
                
                
                startForegroundMonitor()
                isInNoteApp = checkIsNoteAppForeground()
                ScreenshotBubble.show(reactApplicationContext, this@FloatingToolbarModule)
            }
        }
    }

    @ReactMethod
    fun handleDocScreenshotCrop() {
        handler.post {
            if (stitchCommitPending || screenshotCapturePending) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] capture ignored while another screenshot operation is pending")
                return@post
            }
            screenshotCapturePending = true
            markToolbarForRestore()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] handleDocScreenshotCrop: starting screencap")
            removeAll()
            val cacheDir = reactApplicationContext.cacheDir.absolutePath
            kotlin.concurrent.thread(isDaemon = false) {
                try {
                    val ts = System.currentTimeMillis()
                    val outPath = "$cacheDir/screenshot_crop_$ts.png"
                    val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                    val completed = process.waitFor(15, TimeUnit.SECONDS)
                    if (!completed) process.destroyForcibly()
                    val exitCode = if (completed) process.exitValue() else -1
                    val file = java.io.File(outPath)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] screencap completed=$completed exit=$exitCode size=${file.length()}")
                    if (!completed || exitCode != 0 || !file.exists() || file.length() <= 500) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap failed")
                        handler.post {
                            screenshotCapturePending = false
                            showScreenshotMessage("screencap_failed")
                            restoreAfterCropFlow()
                        }
                        return@thread
                    }
                    val dims = DocScreenshotService.getImageDimensions(outPath)
                    if (dims == null) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap decode failed")
                        handler.post {
                            screenshotCapturePending = false
                            showScreenshotMessage("screencap_failed")
                            restoreAfterCropFlow()
                        }
                        return@thread
                    }
                    val imgW = dims.first
                    val imgH = dims.second
                    discardStaleStitchSession("doc crop enter")
                    val mayHaveSession = knownStitchSessionActive && isRecentLongScreenshot()
                    if (!mayHaveSession) {
                        handler.post {
                            screenshotCapturePending = false
                            openCropPanelForDoc(outPath, imgW, imgH)
                        }
                        return@thread
                    }

                    handler.post {
                        requestScreenshotStoragePermissions(
                            action = "save captured stitch image",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val activeSession = DocScreenshotService.loadSession()
                                    if (activeSession == null || activeSession.images.isEmpty()) {
                                        handler.post {
                                            screenshotCapturePending = false
                                            setKnownStitchSessionActive(false)
                                            openCropPanelForDoc(outPath, imgW, imgH)
                                        }
                                        return@thread
                                    }
                                    val updated = DocScreenshotService.addImage(outPath, imgW, imgH)
                                    handler.post {
                                        screenshotCapturePending = false
                                        if (updated != null && updated.images.size >= 2) {
                                            setKnownStitchSessionActive(true)
                                            openStitchPanel(updated)
                                        } else {
                                            showScreenshotMessage("screenshot_save_failed")
                                            restoreAfterCropFlow()
                                        }
                                    }
                                }
                            },
                            onDenied = {
                                screenshotCapturePending = false
                                restoreAfterCropFlow()
                            }
                        )
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap error: ${e.message}", e)
                    handler.post {
                        screenshotCapturePending = false
                        showScreenshotMessage("screencap_failed")
                        restoreAfterCropFlow()
                    }
                } catch (e: OutOfMemoryError) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap out of memory", e)
                    handler.post {
                        screenshotCapturePending = false
                        showScreenshotMessage("screencap_failed")
                        restoreAfterCropFlow()
                    }
                }
            }
        }
    }

    
    fun startDocScreenshotLasso() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startDocScreenshotLasso")
            markToolbarForRestore()
            removeAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            try {
                penLassoOverlay?.dismissSilently()
                penLassoOverlay = null
                val overlay = PenLassoOverlay(reactApplicationContext)
                penLassoOverlay = overlay
                
                overlay.show(
                    actions = listOf(PenLassoOverlay.Action.CONFIRM),
                    autoConfirm = true,
                    onAction = { _, l, t, r, b ->
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        if (stitchCommitPending || screenshotCapturePending) {
                            RegionCaptureFlow.emitCloseAndRestore(
                                this@FloatingToolbarModule, reactApplicationContext, false, null)
                            return@show
                        }
                        screenshotCapturePending = true
                        val cacheDir = reactApplicationContext.cacheDir.absolutePath
                        kotlin.concurrent.thread(isDaemon = false) {
                            try {
                                
                                Thread.sleep(500)
                                val ts = System.currentTimeMillis()
                                val outPath = "$cacheDir/screenshot_crop_$ts.png"
                                val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                                val completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                                if (!completed) process.destroyForcibly()
                                val exitCode = if (completed) process.exitValue() else -1
                                val file = java.io.File(outPath)
                                if (!completed || exitCode != 0 || !file.exists() || file.length() <= 500) {
                                    handler.post {
                                        screenshotCapturePending = false
                                        showScreenshotMessage("screencap_failed")
                                        restoreAfterCropFlow()
                                    }
                                    return@thread
                                }
                                val dims = DocScreenshotService.getImageDimensions(outPath)
                                if (dims == null) {
                                    handler.post {
                                        screenshotCapturePending = false
                                        showScreenshotMessage("screencap_failed")
                                        restoreAfterCropFlow()
                                    }
                                    return@thread
                                }
                                handler.post {
                                    screenshotCapturePending = false
                                    openCropPanelForDoc(
                                        outPath, dims.first, dims.second,
                                        initialCropScreen = android.graphics.Rect(l, t, r, b)
                                    )
                                }
                            } catch (e: Exception) {
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "docScreenshotLasso screencap error: ${e.message}", e)
                                handler.post {
                                    screenshotCapturePending = false
                                    showScreenshotMessage("screencap_failed")
                                    restoreAfterCropFlow()
                                }
                            }
                        }
                    },
                    onCancel = {
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        RegionCaptureFlow.emitCloseAndRestore(
                            this@FloatingToolbarModule, reactApplicationContext, false, null)
                    }
                )
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "startDocScreenshotLasso failed: ${e.message}", e)
                RegionCaptureFlow.emitCloseAndRestore(
                    this@FloatingToolbarModule, reactApplicationContext, false, null)
            }
        }
    }

    
    private fun launchNoteApp() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "launchNoteApp: switching to note")
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(
                    NOTE_PACKAGE,
                    NOTE_INSIDE_PAGES_ACTIVITY
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "launchNoteApp failed: ${e.message}", e)
        }
    }

    private fun restoreAfterCropFlow() {
        ScreenshotBubble.reshowIfPending()
        scheduleRestore(RESTORE_DELAY_SHORT, "crop flow done")
    }

    private fun setKnownStitchSessionActive(active: Boolean) {
        knownStitchSessionActive = active
        val edit = reactApplicationContext
            .getSharedPreferences(SCREENSHOT_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(STITCH_SESSION_ACTIVE_KEY, active)
        if (active) edit.putLong(STITCH_ARMED_AT_KEY, System.currentTimeMillis())
        else edit.remove(STITCH_ARMED_AT_KEY)
        edit.apply()
    }

    private fun stitchArmedAt(): Long =
        reactApplicationContext
            .getSharedPreferences(SCREENSHOT_STATE_PREFS, Context.MODE_PRIVATE)
            .getLong(STITCH_ARMED_AT_KEY, 0L)

    
    private fun isRecentLongScreenshot(): Boolean {
        if (!knownStitchSessionActive) return false
        val armedAt = stitchArmedAt()
        if (armedAt <= 0L) return false
        val age = System.currentTimeMillis() - armedAt
        if (age !in 0 until STITCH_RECENT_MS) return false
        return DocScreenshotService.hasActiveSession()
    }

    private fun discardStaleStitchSession(reason: String) {
        if (isRecentLongScreenshot()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "[CROP-DBG/Kt] keep stitch session reason=$reason ageMs=${System.currentTimeMillis() - stitchArmedAt()}")
            }
            return
        }
        val had = knownStitchSessionActive || DocScreenshotService.hasActiveSession()
        if (!had) return
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "[CROP-DBG/Kt] discard stale stitch session reason=$reason armedAt=${stitchArmedAt()}")
        }
        try { DocScreenshotService.clearSession() } catch (_: Exception) {}
        setKnownStitchSessionActive(false)
    }

    private fun openCropPanelForDoc(
        screenshotPath: String, imgW: Int, imgH: Int,
        fromStitch: Boolean = false,
        initialCropScreen: android.graphics.Rect? = null,
        onShown: ((CropPanel?) -> Unit)? = null
    ) {
        val hasStitch = fromStitch || isRecentLongScreenshot()
        val cropPanel = CropPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
        cropPanel
            .showWithFooter(
                path = screenshotPath,
                hasStitchSession = hasStitch,
                initialCropScreen = initialCropScreen,
                onConfirm = { crop, stayOpen ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] crop confirm (insertNext): ${crop.width}x${crop.height} multi=$stayOpen")
                    requestScreenshotStoragePermissions(
                        action = "stage screenshot for insert",
                        onGranted = {
                            kotlin.concurrent.thread(isDaemon = true) {
                                val result = DocScreenshotService.stageToQueue(screenshotPath, crop, insertNext = true)
                                val clearedSession = result != null && fromStitch && DocScreenshotService.clearSession()
                                handler.post {
                                    if (clearedSession) setKnownStitchSessionActive(false)
                                    if (result == null) showScreenshotMessage("screenshot_save_failed")
                                    if (!stayOpen) restoreAfterCropFlow()
                                }
                            }
                        },
                        onDenied = { if (!stayOpen) restoreAfterCropFlow() }
                    )
                },
                onLongScreenshot = {
                    if (fromStitch) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] long screenshot: session kept, returning to capture")
                        setKnownStitchSessionActive(true)
                        CropPanel.currentInstance?.hide()
                        restoreAfterCropFlow()
                    } else if (!stitchCommitPending) {
                        stitchCommitPending = true
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] long screenshot: committing stitch session")
                        requestScreenshotStoragePermissions(
                            action = "start long screenshot",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val existing = DocScreenshotService.loadSession()
                                    val session = if (existing != null) {
                                        DocScreenshotService.addImage(screenshotPath, imgW, imgH)
                                    } else {
                                        DocScreenshotService.startSession(screenshotPath, imgW, imgH)
                                    }
                                    handler.post {
                                        stitchCommitPending = false
                                        CropPanel.currentInstance?.hide()
                                        if (session != null) {
                                            setKnownStitchSessionActive(true)
                                            if (existing != null && session.images.size >= 2) {
                                                openStitchPanel(session)
                                            } else {
                                                restoreAfterCropFlow()
                                            }
                                        } else {
                                            showScreenshotMessage("screenshot_save_failed")
                                            restoreAfterCropFlow()
                                        }
                                    }
                                }
                            },
                            onDenied = {
                                stitchCommitPending = false
                                CropPanel.currentInstance?.hide()
                                restoreAfterCropFlow()
                            }
                        )
                    }
                },
                onSendToOtherDevices = sendToOtherDevices@ { crop ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] send crop: ${crop.width}x${crop.height}")
                    if (!isWifiConnected()) {
                        me.laumss.notipal.ui_common.Dialog.tip(
                            reactApplicationContext, NativeLocale.t("no_wifi"))
                        restoreAfterCropFlow()
                        return@sendToOtherDevices
                    }
                    if (!LocalSendModule.staticIsRunning) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] auto-start LocalSend")
                        emitEvent("startLocalSendFromNative", Arguments.createMap())
                    }
                    val cameFromScreenshotBubble = ScreenshotBubble.pendingReshow
                    kotlin.concurrent.thread(isDaemon = true) {
                        val croppedPath = "${reactApplicationContext.cacheDir.absolutePath}/doc_crop_send_${System.currentTimeMillis()}.png"
                        val saved = DocScreenshotService.cropAndSave(screenshotPath, crop, croppedPath)
                        if (fromStitch) {
                            val cleared = DocScreenshotService.clearSession()
                            if (cleared) handler.post { setKnownStitchSessionActive(false) }
                        }
                        handler.post {
                            if (saved) {
                                val defaultPeer = LocalSendModule.getDefaultPeer(reactApplicationContext)
                                if (defaultPeer != null) {
                                    kotlin.concurrent.thread(isDaemon = true) {
                                        try {
                                            LocalSendModule.sendFileDirect(
                                                defaultPeer.ip, defaultPeer.port, croppedPath, defaultPeer.useTls)
                                            me.laumss.notipal.ui_common.Dialog.tip(
                                                reactApplicationContext, NativeLocale.t("send_success"))
                                        } catch (e: Exception) {
                                            val msg = if (e.message?.contains("403") == true)
                                                NativeLocale.t("image_send_rejected")
                                            else "${NativeLocale.t("send_failed")}: ${e.message}"
                                            me.laumss.notipal.ui_common.Dialog.tip(reactApplicationContext, msg)
                                        } finally {
                                            try { java.io.File(croppedPath).delete() } catch (_: Exception) {}
                                            handler.post { restoreAfterCropFlow() }
                                        }
                                    }
                                } else {
                                    val sendPanel = SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                                    sendPanel.show(
                                        fromBubble = cameFromScreenshotBubble,
                                        onShowResult = { shown ->
                                            if (shown) {
                                                sendPanel.updateLassoData("", listOf(croppedPath))
                                            } else {
                                                showScreenshotMessage("screenshot_panel_failed")
                                                restoreAfterCropFlow()
                                            }
                                        }
                                    )
                                }
                            } else {
                                showScreenshotMessage("screenshot_save_failed")
                                restoreAfterCropFlow()
                            }
                        }
                    }
                },
                onAddToHistory = { crop, stayOpen ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] add to history: ${crop.width}x${crop.height} multi=$stayOpen")
                    requestScreenshotStoragePermissions(
                        action = "save screenshot history",
                        onGranted = {
                            kotlin.concurrent.thread(isDaemon = true) {
                                val result = DocScreenshotService.saveToHistory(screenshotPath, crop)
                                val clearedSession = result != null && fromStitch && DocScreenshotService.clearSession()
                                handler.post {
                                    if (clearedSession) setKnownStitchSessionActive(false)
                                    if (result == null) showScreenshotMessage("screenshot_save_failed")
                                    if (!stayOpen) restoreAfterCropFlow()
                                }
                            }
                        },
                        onDenied = { if (!stayOpen) restoreAfterCropFlow() }
                    )
                },
                onCancel = {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] crop cancel")
                    if (fromStitch || knownStitchSessionActive) {
                        requestScreenshotStoragePermissions(
                            action = "clear long screenshot",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val cleared = DocScreenshotService.clearSession()
                                    handler.post {
                                        if (cleared) setKnownStitchSessionActive(false)
                                        restoreAfterCropFlow()
                                    }
                                }
                            },
                            onDenied = { restoreAfterCropFlow() }
                        )
                    } else {
                        restoreAfterCropFlow()
                    }
                },
                onShowResult = { shown ->
                    onShown?.invoke(if (shown) cropPanel else null)
                    if (!shown) {
                        showScreenshotMessage("screenshot_panel_failed")
                        restoreAfterCropFlow()
                    }
                }
            )
    }

    
    private fun hideAfterShown(from: PanelBase?, to: PanelBase?) {
        if (from == null) return
        if (to == null) {
            if (from.isShowing) from.hide()
            return
        }
        to.runAfterFirstDraw { if (from.isShowing) from.hide() }
    }

    private fun openStitchPanel(session: DocScreenshotService.StitchSessionData) {
        StitchPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
            .show(
                session = session,
                onConfirm = { finalSession ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] stitch confirm: compositing ${finalSession.images.size} images...")
                    kotlin.concurrent.thread(isDaemon = false) {
                        
                        if (!DocScreenshotService.updateSession(finalSession)) {
                            handler.post {
                                StitchPanel.currentInstance?.hide()
                                showScreenshotMessage("screenshot_save_failed")
                                restoreAfterCropFlow()
                            }
                            return@thread
                        }
                        val outPath = "${reactApplicationContext.cacheDir.absolutePath}/stitch_result_${System.currentTimeMillis()}.png"
                        val size = StitchLayout.render(finalSession, outPath)
                        handler.post {
                            val stitchPanel = StitchPanel.currentInstance
                            if (size != null) {
                                
                                openCropPanelForDoc(outPath, size.first, size.second, fromStitch = true) { crop ->
                                    hideAfterShown(stitchPanel, crop)
                                }
                            } else {
                                stitchPanel?.hide()
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] stitch composite failed")
                                showScreenshotMessage("screenshot_save_failed")
                                restoreAfterCropFlow()
                            }
                        }
                    }
                },
                onContinue = { current ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] stitch continue: ${current.images.size} images kept")
                    kotlin.concurrent.thread(isDaemon = true) {
                        val saved = DocScreenshotService.updateSession(current)
                        handler.post {
                            StitchPanel.currentInstance?.hide()
                            if (saved) {
                                setKnownStitchSessionActive(true)
                            } else {
                                showScreenshotMessage("screenshot_save_failed")
                            }
                            restoreAfterCropFlow()
                        }
                    }
                },
                onCancel = {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] stitch cancel: clearing session")
                    kotlin.concurrent.thread(isDaemon = true) {
                        val cleared = DocScreenshotService.clearSession()
                        handler.post {
                            if (cleared) setKnownStitchSessionActive(false)
                            restoreAfterCropFlow()
                        }
                    }
                },
                onShowResult = { shown ->
                    if (!shown) {
                        showScreenshotMessage("screenshot_panel_failed")
                        restoreAfterCropFlow()
                    }
                }
            )
    }

    @ReactMethod
    fun handleDocScreenshot() {
        handler.post {
            docScreenshotRoutePending = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot route pending=true")
            
            if (!MosaicLink.isBoardSurface()) HostPenSignals.beginInsertImageGuard()
            markToolbarForRestore()
            removeAll()
            kotlin.concurrent.thread(isDaemon = true) {
                val nextFile = try {
                    DocScreenshotService.firstInsertNextFile()
                } catch (e: Exception) {
                    handler.post {
                        docScreenshotRoutePending = false
                        HostPenSignals.endInsertImageGuard()
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] doc screenshot route failed: ${e.message}", e)
                        scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot route failed")
                    }
                    return@thread
                }
                handler.post {
                    if (nextFile != null) {
                        val path = nextFile.absolutePath
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] handleDocScreenshot: insertNext=$path")
                        insertNextChainActive = true
                        if (MosaicLink.isBoardSurface()) {
                            
                            enqueueImageToMosaic(path, "inkling-doc-screenshot") {
                                DocScreenshotService.unmarkInsertNext(nextFile.name)
                                try { nextFile.delete() } catch (_: Exception) {}
                                insertNextChainActive = false
                                docScreenshotRoutePending = false
                            }
                            return@post
                        }
                        kotlin.concurrent.thread(isDaemon = true) {
                            ImagePanel.saveToInsertCacheStatic(path, lastNotePath, lastPageNum)
                        }
                        handler.postDelayed({
                            try {
                                
                                
                                requestInsertImage(path)
                                docScreenshotRoutePending = false
                                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot route handed to insert-image")
                            } catch (e: Exception) {
                                docScreenshotRoutePending = false
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "requestInsertImage failed: ${e.message}")
                                HostPenSignals.endInsertImageGuard()
                                insertNextChainActive = false; restoreToolbar()
                            }
                        }, 500)
                    } else {
                        insertNextChainActive = false
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] handleDocScreenshot: no insertNext, opening panel")
                        openNativePanel("screenshot") {
                            val panel = DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                            try {
                                panel.show("queue") { shown ->
                                    docScreenshotRoutePending = false
                                    if (shown && SubviewLogMonitor.isSubviewOpen()) {
                                        panel.suspendVisibility()
                                    }
                                    HostPenSignals.endInsertImageGuard()
                                    if (BuildConfig.ENABLE_DEBUG) {
                                        Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot panel settled shown=$shown subview=${SubviewLogMonitor.isSubviewOpen()}")
                                    }
                                    if (!shown) scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot panel open failed")
                                }
                            } catch (e: Exception) {
                                docScreenshotRoutePending = false
                                HostPenSignals.endInsertImageGuard()
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] doc screenshot panel open failed: ${e.message}", e)
                                scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot panel open failed")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun handleSendLongPress() {
        handler.post {
            if (!isWifiConnected()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: no wifi")
                me.laumss.notipal.ui_common.Dialog.tip(
                    reactApplicationContext, NativeLocale.t("no_wifi"))
                return@post
            }
            if (!LocalSendModule.staticIsRunning) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: auto-start LocalSend")
                emitEvent("startLocalSendFromNative",
                    Arguments.createMap().apply { putBoolean("openClipboardSync", true) })
                return@post
            }
            val defaultPeer = LocalSendModule.getDefaultPeer(reactApplicationContext)
            if (defaultPeer != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: direct-send to ${defaultPeer.alias}")
                directSendClipboard(defaultPeer)
                return@post
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: LocalSend running, open panel")
            openSendPanelClipboardSync()
        }
    }

    private fun directSendClipboard(peer: LocalSendModule.DiscoveredPeer) {
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val zipPath = LocalSendModule.packageClipboardStatic(reactApplicationContext)
                if (zipPath == null) {
                    me.laumss.notipal.ui_common.Dialog.tip(
                        reactApplicationContext, NativeLocale.t("sync_clipboard_empty"))
                    return@thread
                }
                LocalSendModule.sendFileDirect(peer.ip, peer.port, zipPath, peer.useTls)
                try { java.io.File(zipPath).delete() } catch (_: Exception) {}
                me.laumss.notipal.ui_common.Dialog.tip(
                    reactApplicationContext, NativeLocale.t("send_success"))
            } catch (e: Exception) {
                val msg = if (e.message?.contains("403") == true)
                    NativeLocale.t("sync_rejected")
                else "${NativeLocale.t("send_failed")}: ${e.message}"
                me.laumss.notipal.ui_common.Dialog.tip(reactApplicationContext, msg)
            }
        }
    }

    @ReactMethod
    fun showDocTextSendPanel(text: String) {
        handler.post {
            if (text.isBlank()) return@post
            monitorHandler.removeCallbacks(configFallbackRunnable)
            markToolbarForRestore()
            removeAll()
            openNativePanel("send") {
                val panel = SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                panel.show(allowAutoSend = false) { shown ->
                    if (shown) {
                        panel.updateLassoData(text, emptyList())
                        panel.runAfterFirstDraw { closeHostPluginView() }
                    } else {
                        closeHostPluginView()
                    }
                }
            }
        }
    }

    @ReactMethod
    fun openSendPanelClipboardSync() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openSendPanelClipboardSync")
            removeAll()
            openNativePanel("send") {
                SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(syncClipboard = true)
            }
        }
    }

    fun openDocScreenshotPanel() {
        handler.post {
            
            markToolbarForRestore()
            removeAll()
            openNativePanel("screenshot") {
                DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show("queue")
            }
        }
    }

    
    private fun startRegionCapture(mode: String, fromBubble: Boolean) {
        handler.post {
            if (regionCaptureActive) return@post
            regionCaptureActive = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startRegionCapture mode=$mode fromBubble=$fromBubble")
            
            
            
            parkAllOverlaysForPalette()
            ToolRegistry.suspendAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            try {
                
                
                penLassoOverlay?.dismissSilently()
                penLassoOverlay = null
                val overlay = PenLassoOverlay(reactApplicationContext)
                penLassoOverlay = overlay
                val actions = when (mode) {
                    "send" -> listOf(
                        PenLassoOverlay.Action.SEND_TO_DEVICES,
                        PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS,
                        PenLassoOverlay.Action.CANCEL,
                    )
                    "smart" -> listOf(
                        PenLassoOverlay.Action.SEND_TO_DEVICES,
                        PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS,
                        PenLassoOverlay.Action.OPEN_MOSAIC_CARD,
                        PenLassoOverlay.Action.CANCEL,
                    )
                    else -> listOf(
                        PenLassoOverlay.Action.CONFIRM,
                        PenLassoOverlay.Action.OPEN_IMAGE_PANEL,
                        PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS,
                        PenLassoOverlay.Action.CANCEL,
                    )
                }
                overlay.show(
                    actions = actions,
                    onAction = { action, l, t, r, b ->
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        val captureMode = when (action) {
                            PenLassoOverlay.Action.CONFIRM -> mode
                            PenLassoOverlay.Action.SEND_TO_DEVICES -> "send"
                            PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS -> "docScreenshot"
                            PenLassoOverlay.Action.OPEN_IMAGE_PANEL -> {
                                finishRegionCapture()
                                handler.post { showImagePanel() }
                                return@show
                            }
                            PenLassoOverlay.Action.OPEN_MOSAIC_CARD -> "mosaic"
                            PenLassoOverlay.Action.CANCEL -> return@show
                        }
                        
                        if (action == PenLassoOverlay.Action.OPEN_MOSAIC_CARD && MosaicLink.isBoardSurface()) {
                            RegionCaptureFlow.capture(
                                reactApplicationContext, this@FloatingToolbarModule,
                                captureMode, fromBubble, l, t, r, b)
                            return@show
                        }
                        if (action == PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS ||
                            action == PenLassoOverlay.Action.OPEN_MOSAIC_CARD) {
                            requestScreenshotStoragePermissions(
                                action = if (action == PenLassoOverlay.Action.OPEN_MOSAIC_CARD)
                                    "insert region into Mosaic" else "save region to doc screenshots",
                                onGranted = {
                                    if (action == PenLassoOverlay.Action.OPEN_MOSAIC_CARD) {
                                        requestMosaicPluginPermissions { mosaicResult ->
                                            if (mosaicResult == MosaicPermissionResult.GRANTED) {
                                                RegionCaptureFlow.capture(
                                                    reactApplicationContext, this@FloatingToolbarModule,
                                                    captureMode, fromBubble, l, t, r, b)
                                            } else {
                                                showMosaicPermissionReminder(mosaicResult)
                                                RegionCaptureFlow.emitCloseAndRestore(
                                                    this@FloatingToolbarModule,
                                                    reactApplicationContext,
                                                    fromBubble,
                                                    null,
                                                )
                                            }
                                        }
                                    } else {
                                        RegionCaptureFlow.capture(
                                            reactApplicationContext, this@FloatingToolbarModule,
                                            captureMode, fromBubble, l, t, r, b)
                                    }
                                },
                                onDenied = {
                                    RegionCaptureFlow.emitCloseAndRestore(
                                        this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
                                }
                            )
                        } else {
                            RegionCaptureFlow.capture(
                                reactApplicationContext, this@FloatingToolbarModule,
                                captureMode, fromBubble, l, t, r, b)
                        }
                    },
                    onCancel = {
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        RegionCaptureFlow.emitCloseAndRestore(
                            this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
                    }
                )
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "startRegionCapture failed: ${e.message}", e)
                RegionCaptureFlow.emitCloseAndRestore(
                    this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
            }
        }
    }


    @ReactMethod
    fun showSendPanelFromBubble() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "showSendPanelFromBubble")
            pendingScreen = "nativeSendHelper"
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "send") })
            SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(fromBubble = true, useStaged = true)
            handler.postDelayed({
                
                
                if (pendingScreen != "nativeSendHelper") return@postDelayed
                callShowPluginView()
                emitOpenMainWithRetries("nativeSendHelper")
            }, 150)
        }
    }

    @ReactMethod
    fun showLassoScreenshotPanelFromBubble() {
        startRegionCapture(mode = "ai", fromBubble = true)
    }

    @ReactMethod
    fun showLassoScreenshotPanelForSendFromBubble() {
        startRegionCapture(mode = "send", fromBubble = true)
    }

    
    @ReactMethod
    fun showSmartLassoCapture() {
        startRegionCapture(mode = "smart", fromBubble = false)
    }

    internal fun finishRegionCapture() {
        regionCaptureActive = false
        paletteOverlaysParked = false
    }

    
    private fun importCurrentNoteToMosaic() {
        if (noteImportBusy) return
        if (docMode || mosaicMode) return
        noteImportBusy = true
        val finishFailed = {
            noteImportBusy = false
            handler.post { Dialog.tip(reactApplicationContext, NativeLocale.t("mosaic_note_import_failed")) }
            Unit
        }
        requestScreenshotStoragePermissions(
            action = NativeLocale.t("mosaic_note_import"),
            onGranted = {
                requestMosaicPluginPermissions { mosaicResult ->
                    if (mosaicResult != MosaicPermissionResult.GRANTED) {
                        noteImportBusy = false
                        showMosaicPermissionReminder(mosaicResult)
                        return@requestMosaicPluginPermissions
                    }
                    val pagesFile = MosaicLink.newNoteImportFile()
                        ?: return@requestMosaicPluginPermissions finishFailed()
                    showScreenshotMessage("mosaic_note_importing")
                    NoteMosaicExport.exportCurrentNote(reactApplicationContext, pagesFile,
                        onProgress = { page, total -> handler.post {
                            Dialog.tip(reactApplicationContext, NativeLocale.t("mosaic_note_import_progress", page, total))
                        } },
                    ) { exported, error ->
                        if (exported == null) {
                            Log.w(TAG, "note import export failed: $error")
                            finishFailed()
                            return@exportCurrentNote
                        }
                        val title = java.io.File(exported.notePath).nameWithoutExtension
                        if (!MosaicLink.enqueueNote(pagesFile, title, "inkling-note-import")) {
                            pagesFile.delete()
                            finishFailed()
                            return@exportCurrentNote
                        }
                        noteImportBusy = false
                        Dialog.tip(reactApplicationContext, NativeLocale.t("mosaic_note_import_ready", exported.pageCount))
                        openMosaicPluginForPending()
                    }
                }
            },
            onDenied = { noteImportBusy = false },
        )
    }

    
    internal fun openMosaicPluginForPending() {
        handler.post {
            var opened = false
            try {
                val loader = reactApplicationContext.classLoader
                val eventClass = Class.forName(
                    "com.ratta.supernote.pluginhost.eventbus.PluginShowEvent", false, loader)
                val event = eventClass.getConstructor(String::class.java, Integer.TYPE)
                    .newInstance(MOSAIC_PLUGIN_ID, 1)
                val eventBusClass = Class.forName("org.greenrobot.eventbus.EventBus", false, loader)
                val eventBus = eventBusClass.getMethod("getDefault").invoke(null)
                eventBusClass.getMethod("post", Any::class.java).invoke(eventBus, event)
                opened = true
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "Mosaic opened via PluginShowEvent($MOSAIC_PLUGIN_ID, 1)")
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "Mosaic PluginShowEvent unavailable: ${e.message}")
                }
            }
            if (!opened) {
                try {
                    val loader = reactApplicationContext.classLoader
                    val managerClass = Class.forName(
                        "com.ratta.supernote.pluginhost.manager.PluginManager", false, loader)
                    val manager = managerClass.methods.firstOrNull {
                        it.name == "getInstance" && it.parameterCount == 0
                    }?.invoke(null)
                    val show = managerClass.methods.firstOrNull {
                        it.name == "showPluginView" && it.parameterCount == 2 &&
                            it.parameterTypes[0].isAssignableFrom(String::class.java) &&
                            (it.parameterTypes[1] == Integer.TYPE || it.parameterTypes[1] == Integer::class.java)
                    }
                    if (manager != null && show != null) {
                        show.invoke(manager, MOSAIC_PLUGIN_ID, 1)
                        opened = true
                        if (BuildConfig.ENABLE_DEBUG) {
                            Log.i(TAG, "Mosaic opened via PluginManager.showPluginView($MOSAIC_PLUGIN_ID, 1)")
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.w(TAG, "Mosaic manager showPluginView unavailable: ${e.message}")
                    }
                }
            }
            if (!opened) {
                try {
                    val intent = Intent().apply {
                        component = ComponentName(MOSAIC_PACKAGE, MOSAIC_ACTIVITY)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    reactApplicationContext.startActivity(intent)
                    opened = true
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Mosaic opened via fallback activity")
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.e(TAG, "Unable to open Mosaic: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun showPluginView() {
        handler.post { callShowPluginView() }
    }

    fun closePluginView() {
        handler.post { callClosePluginView() }
    }


    fun cancelPendingScreen() {
        pendingScreen = ""
    }

    fun destroyAll() {
        val destroy = Runnable {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "destroyAll: removing all overlays + closing plugin")

            emitEvent("onToolbarDestroyAll", Arguments.createMap())

            toolbarSessionActive = false
            stopForegroundMonitor()
            pendingScreen = ""
            pendingOpenMain = false
            activeModeIds.clear()
            tools.clear()

            removeAll()

            ToolRegistry.hideAll()
            FloatingBubbleModule.hideAndForget()
            AiBubbleModule.hideAndForget()
            PaletteBubbleModule.hideAndForget()
            ScreenshotBubble.pendingReshow = false
            ScreenshotBubble.hide()

            callClosePluginView()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "destroyAll: done")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) destroy.run() else handler.post(destroy)
    }

    @ReactMethod
    fun destroyAllFromJs() {
        destroyAll()
    }

    
    
    private fun shouldPageSidebarTools(): Boolean =
        mosaicMode || (!docMode && tools.count { !isToolbarUtilityTool(it.id) } == 4)

    private fun horizontalClipCount(): Int = if (docMode || mosaicMode) 4 else MAX_VISIBLE_CLIPS

    private fun verticalClipCapacity(): Int {
        val actionToolCount = tools.count { !isToolbarUtilityTool(it.id) }
        return when {
            docMode || mosaicMode -> 4
            actionToolCount >= 6 -> MAX_VISIBLE_CLIPS
            actionToolCount == 5 -> 4
            else -> 3
        }
    }

    private fun lastVerticalClipPage(): Int =
        if (shouldPageSidebarTools()) 1 else (CLIP_SLOT_COUNT - 1) / verticalClipCapacity()

    
    private fun verticalClipWindow(): Pair<Int, Int> {
        clipPage = clipPage.coerceIn(0, lastVerticalClipPage())
        if (shouldPageSidebarTools()) {
            return if (clipPage == 0) 3 to 0 else (CLIP_SLOT_COUNT - 3) to 3
        }
        val capacity = verticalClipCapacity()
        val offset = if (capacity == MAX_VISIBLE_CLIPS) {
            minOf(clipPage * capacity, CLIP_SLOT_COUNT - capacity)
        } else {
            clipPage * capacity
        }
        return minOf(capacity, CLIP_SLOT_COUNT - offset) to offset
    }

    private fun rebuildClipIcons() {
        val (count, offset) = if (orientation != "vertical") {
            horizontalClipCount() to 0
        } else {
            verticalClipWindow()
        }
        for (vi in clipIconViews.indices) {
            val tv = clipIconViews[vi] ?: continue
            if (vi >= count) {
                tv.visibility = View.GONE
                tv.isEnabled = false
                tv.setOnClickListener(null)
                tv.setOnLongClickListener(null)
                continue
            }
            tv.visibility = View.VISIBLE
            tv.isEnabled = true
            val di = offset + vi
            val filled = titleClipFilled.getOrElse(di) { false }
            val slot = di + 1
            tv.text = slot.toString()
            tv.setTextColor(if (filled) Color.WHITE else Color.BLACK)
            tv.background = if (filled) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(CLIP_RADIUS_DP).toFloat()
                    setColor(Color.BLACK)
                }
            } else {
                null
            }
            tv.setOnClickListener {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CLIP-DBG] onTitleClipTap slot=$slot filled=$filled")
                emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
            }
            tv.setOnLongClickListener {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CLIP-DBG] onTitleClipLongPress slot=$slot")
                emitEvent("onTitleClipLongPress", Arguments.createMap().apply { putString("slot", slot.toString()) })
                true
            }
            tv.invalidate()
        }

        val toolsVisibility = if (clipPage == 0) View.VISIBLE else View.GONE
        pagedClipTools.forEach { it.visibility = toolsVisibility }

        try {
            val rv = rootView; val lp = layoutParams; val wm = windowManager
            if (rv != null && lp != null && wm != null) {
                wm.updateViewLayout(rv, lp)
            }
        } catch (_: Exception) {}
    }


    private fun runMonitorTick() {
        
        val foreground = foregroundActivity() ?: return
        val fgPkg = foreground.packageName
        val noteCanvasForeground = fgPkg == NOTE_PACKAGE &&
            foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY
        val supportsHandwriting = noteCanvasForeground || fgPkg == DOC_PACKAGE
        val supportsScreenshotBubble = supportsHandwriting || fgPkg == WEREAD_PACKAGE
        if (!supportsHandwriting && rotationEpoch != 0L) {
            cancelRotationSync("left handwriting page")
        }

        
        
        
        
        FloatingPenGuard.setFullScreenActive(!supportsHandwriting, PenGuardOwner.FOREIGN_ACTIVITY)
        if (!supportsScreenshotBubble && ScreenshotBubble.isShowing) {
            monitorHandler.post { ScreenshotBubble.hideForInactiveCanvas() }
        }

        
        
        
        
        val fgKey = "$fgPkg/${foreground.activityName}"
        if (fgKey != lastForegroundKey) {
            val previous = lastForegroundKey
            lastForegroundKey = fgKey
            if (previous != null) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "foreground activity changed: $previous → $fgKey")
                }
                HostPenSignals.scheduleForegroundReassert(fgKey)
            }
            DocChromeProbe.request("foreground: $fgKey")
        }

        if (fgPkg == SETTINGS_PACKAGE) {
            if (ScreenshotBubble.isShowing && !ScreenshotBubble.hiddenByInactiveCanvas) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: in ratta settings, hiding ScreenshotBubble")
                monitorHandler.post { ScreenshotBubble.hideForSettings() }
            }
        } else if ((ScreenshotBubble.hiddenBySettings || ScreenshotBubble.hiddenByInactiveCanvas)
            && supportsScreenshotBubble
            && !SubviewLogMonitor.isSubviewOpen() && !isPluginHostWindowShowing) {
            
            
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: safe to restore ScreenshotBubble (fg=$fgPkg)")
            monitorHandler.post {
                if (ScreenshotBubble.hiddenBySettings) {
                    ScreenshotBubble.reshowIfHiddenBySettings()
                } else {
                    ScreenshotBubble.reshowIfHiddenByInactiveCanvas()
                }
            }
        }

        
        
        
        
        
        val inDoc = fgPkg == DOC_PACKAGE || (fgPkg == PLUGIN_PACKAGE && docMode)
        if (noteCanvasForeground || fgPkg == DOC_PACKAGE) {
            val targetDocMode = fgPkg == DOC_PACKAGE
            if (docMode != targetDocMode || loadedDocMode != targetDocMode) {
                switchToolbarAppMode(targetDocMode, "foreground ${if (targetDocMode) "DOC" else "NOTE"}")
            }
        }

        val inNote = fgPkg == PLUGIN_PACKAGE || noteCanvasForeground || inDoc
        if (inNote != isInNoteApp) {
            if (inNote) noteForegroundSinceMs = android.os.SystemClock.elapsedRealtime()
            isInNoteApp = inNote
            
            
            
            emitEvent("onNoteForegroundChanged", com.facebook.react.bridge.Arguments.createMap().apply {
                putBoolean("inNote", inNote)
            })
            if (!inNote) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: left note canvas, hiding overlays")
                markToolbarForRestore(parkGuards = false)
                terminateAfterPluginMenuClose = false
                
                
                
                
                
                FloatingPenGuard.releaseScopes("left note canvas", PenGuardScope.HOST_UI)
                monitorHandler.post {
                    
                    
                    
                    
                    FloatingPenGuard.endPark("left note canvas", flush = true)
                    removeAll()
                    suspendAllNativePanels()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                }
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: returned to note canvas")
                scheduleRestore(RESTORE_DELAY_SHORT, "returned to note canvas")
            }
        }
        syncDocumentTextSelectionGuard()
        syncPluginHostGuard()
    }

    fun startForegroundMonitor() {
        if (foregroundMonitorRunning) return
        foregroundMonitorRunning = true
        isInNoteApp = true



        paletteApplyInFlight = false
        setPaletteSelectionHold(false)
        HostPenSignals.endPaletteApplyGuard()
        HostPenSignals.endInsertImageGuard()

        
        if (windowStateMonitor == null) {
            windowStateMonitor = WindowStateMonitor(reactApplicationContext)
        }
        windowStateMonitor?.start()

        
        
        FloatingPenGuard.claimOwnership(reactApplicationContext.filesDir)

        
        
        FloatingPenGuard.beginHostBootstrap("foreground monitor start")
        SubviewLogMonitor.start(subviewListener)

        monitorHandler.removeCallbacks(staticMonitorRunnable)
        monitorHandler.postDelayed(staticMonitorRunnable, MONITOR_INTERVAL_MS)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor started")
    }

    fun stopForegroundMonitor() {
        cancelRotationSync("foreground monitor stopped")
        foregroundMonitorRunning = false
        monitorHandler.removeCallbacks(staticMonitorRunnable)
        
        
        SubviewLogMonitor.stop()
        paletteApplyInFlight = false
        paletteOverlaysParked = false
        setPaletteSelectionHold(false)
        clearMultiInsert("foreground monitor stopped")
        HostPenSignals.resetForMonitorStop("foreground monitor stopped")
        
        FloatingPenGuard.releaseScopes(
            "foreground monitor stopped",
            PenGuardScope.HOST_UI, PenGuardScope.FLOW, PenGuardScope.PRESS
        )
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.FOREIGN_ACTIVITY)
        FloatingPenGuard.resetHostBaseline("foreground monitor stopped")
        docScreenshotRoutePending = false
        toolbarRestorePending = false
        cancelScheduledRestore()
        FloatingPenGuard.endPark("foreground monitor stopped", flush = true)
        terminateAfterPluginMenuClose = false
    }

    private data class ForegroundActivity(val packageName: String, val activityName: String)

    private val resumedActivityRegex = Regex("""mResumedActivity:.*?(\S+)/(\S+)\s""")

    
    private fun foregroundActivity(): ForegroundActivity? {
        return try {
            val bin = if (java.io.File("/system/bin/dumpsys").exists()) "/system/bin/dumpsys" else "dumpsys"
            val proc = Runtime.getRuntime().exec(arrayOf(bin, "activity", "activities"))
            val reader = proc.inputStream.bufferedReader()
            var matched: MatchResult? = null
            reader.useLines { lines ->
                for (line in lines) {
                    if ("mResumedActivity" in line) {
                        matched = resumedActivityRegex.find(line)
                    }
                }
            }
            proc.waitFor()
            if (matched != null) {
                val pkg = matched!!.groupValues[1]
                val rawActivity = matched!!.groupValues[2]
                val activity = if (rawActivity.startsWith('.')) pkg + rawActivity else rawActivity
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "foreground: $pkg/$activity")
                ForegroundActivity(pkg, activity)
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "foregroundActivity: no mResumedActivity found")
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "foregroundActivity: ${e.message}")
            null
        }
    }

    private fun isNoteCanvasForeground(): Boolean {
        val foreground = foregroundActivity() ?: return false
        return foreground.packageName == NOTE_PACKAGE &&
            foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY
    }

    private fun checkIsNoteAppForeground(): Boolean {
        val foreground = foregroundActivity() ?: return true
        return foreground.packageName == PLUGIN_PACKAGE ||
            (foreground.packageName == NOTE_PACKAGE && foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY)
    }

    private var penSelectionRequest: String? = null
    private var penSelectionView: PenLassoOverlay? = null
    private var penSelectionTimeout: Runnable? = null
    private var penSelectionScreenWidth = 0
    private var penSelectionScreenHeight = 0

    @ReactMethod
    fun beginPenLassoSelection(requestId: String) {
        handler.post {
            if (requestId.isBlank()) return@post
            penSelectionRequest?.let { endPenSelection(it, cancelled = true) }
            penLassoOverlay?.dismissSilently()
            penLassoOverlay = null
            penSelectionRequest = requestId
            penSelectionScreenWidth = screenWidth
            penSelectionScreenHeight = screenHeight
            try {
                val overlay = PenLassoOverlay(reactApplicationContext)
                penSelectionView = overlay
                penLassoOverlay = overlay
                overlay.show(
                    actions = listOf(PenLassoOverlay.Action.CONFIRM, PenLassoOverlay.Action.CANCEL),
                    beforeAction = {
                        if (penSelectionRequest == requestId) {
                            
                            
                            
                            FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.penSelection(requestId))
                            schedulePenSelectionTimeout(requestId, PenGuardTiming.PEN_SELECTION_ACTION_TIMEOUT_MS)
                        }
                    },
                    onAction = { _, l, t, r, b ->
                        if (penLassoOverlay === overlay) penLassoOverlay = null
                        if (penSelectionView === overlay) penSelectionView = null
                        if (penSelectionRequest == requestId) {
                            emitEvent("onPenLassoBbox", Arguments.createMap().apply {
                                putString("requestId", requestId)
                                putInt("left", l); putInt("top", t)
                                putInt("right", r); putInt("bottom", b)
                            })
                        }
                    },
                    onCancel = { endPenSelection(requestId, cancelled = true) },
                )
                if (penSelectionRequest == requestId) {
                    schedulePenSelectionTimeout(requestId, PenGuardTiming.PEN_SELECTION_SESSION_TIMEOUT_MS)
                }
            } catch (error: Exception) {
                Log.e(TAG, "beginPenLassoSelection request=$requestId", error)
                endPenSelection(requestId, cancelled = true)
            }
        }
    }

    private fun schedulePenSelectionTimeout(requestId: String, delayMs: Long) {
        penSelectionTimeout?.let { handler.removeCallbacks(it) }
        penSelectionTimeout = Runnable {
            Log.i(TAG, "pen selection cancelled at deadline request=$requestId")
            endPenSelection(requestId, cancelled = true)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun endPenSelection(requestId: String, cancelled: Boolean): Boolean {
        if (penSelectionRequest != requestId) return false
        penSelectionTimeout?.let { handler.removeCallbacks(it) }
        penSelectionTimeout = null
        penSelectionRequest = null
        val overlay = penSelectionView
        penSelectionView = null
        if (penLassoOverlay === overlay) penLassoOverlay = null
        overlay?.dismissSilently()
        FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.penSelection(requestId))
        if (cancelled) emitEvent("onPenLassoCancel", Arguments.createMap().apply {
            putString("requestId", requestId)
        })
        return true
    }

    @ReactMethod
    fun finishPenLassoSelection(requestId: String, promise: Promise) {
        handler.post {
            val valid = penSelectionRequest == requestId &&
                penSelectionScreenWidth == screenWidth && penSelectionScreenHeight == screenHeight &&
                !MosaicLink.isBoardVisible()
            val ended = endPenSelection(requestId, cancelled = !valid)
            promise.resolve(valid && ended)
        }
    }

    @ReactMethod
    fun cancelPenLassoSelection(requestId: String) {
        handler.post { endPenSelection(requestId, cancelled = true) }
    }

    @ReactMethod
    fun showPenLassoOverlay() {
        
        Log.i(TAG, "showPenLassoOverlay: bridge call received")
        handler.post {
            try {
                Log.i(TAG, "showPenLassoOverlay: creating overlay (prev=${penLassoOverlay != null})")
                penLassoOverlay?.dismissSilently()
                penLassoOverlay = null
                val overlay = PenLassoOverlay(reactApplicationContext)
                penLassoOverlay = overlay
                overlay.show(
                    actions = listOf(
                        PenLassoOverlay.Action.CONFIRM,
                        PenLassoOverlay.Action.CANCEL,
                    ),
                    onAction = { _, l, t, r, b ->
                        handler.post {
                            if (penLassoOverlay === overlay) penLassoOverlay = null
                            emitEvent("onPenLassoBbox", Arguments.createMap().apply {
                                putInt("left", l); putInt("top", t)
                                putInt("right", r); putInt("bottom", b)
                            })
                        }
                    },
                    onCancel = {
                        handler.post {
                            if (penLassoOverlay === overlay) penLassoOverlay = null
                            emitEvent("onPenLassoCancel", Arguments.createMap())
                        }
                    }
                )
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "showPenLassoOverlay failed: ${e.message}", e)
                emitEvent("onPenLassoCancel", Arguments.createMap())
            }
        }
    }

    @ReactMethod
    fun dismissPenLassoOverlay() {
        handler.post {
            penLassoOverlay?.dismiss()
            penLassoOverlay = null
        }
    }

    private var insertTimerRunning = false
    private var insertTimerInterval = 800L
    private val insertTimerRunnable = object : Runnable {
        override fun run() {
            if (!insertTimerRunning) return
            try {
                reactApplicationContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onInsertTimerTick", null)
            } catch (_: Exception) {}
            handler.postDelayed(this, insertTimerInterval)
        }
    }

    @ReactMethod
    fun startInsertTimer(ms: Int) {
        handler.removeCallbacks(insertTimerRunnable)
        insertTimerInterval = ms.toLong().coerceAtLeast(100L)
        insertTimerRunning = true
        handler.postDelayed(insertTimerRunnable, insertTimerInterval)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startInsertTimer intervalMs=$insertTimerInterval")
    }

    @ReactMethod
    fun stopInsertTimer() {
        insertTimerRunning = false
        handler.removeCallbacks(insertTimerRunnable)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
