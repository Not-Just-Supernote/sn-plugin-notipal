package me.laumss.notipal

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


internal object FloatingPenGuard {
    const val NOTE_APP_NAME = "note_notipal"
    const val DOC_APP_NAME = "doc_notipal"
    @Volatile private var appName = NOTE_APP_NAME
    private const val PAD_PX = 3
    private const val TAG = "FloatingPenGuard"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val pendingAppClears = ConcurrentLinkedQueue<String>()
    private val rects = linkedMapOf<String, Rect>()
    
    
    private val logicalRects = linkedMapOf<String, Rect>()
    private val views = linkedMapOf<String, WeakReference<View>>()
    private val viewRotationEpochs = linkedMapOf<String, Long>()
    private val generation = AtomicLong()

    private data class PendingSend(
        val clear: Boolean,
        val payload: List<Rect>,
        val appName: String,
        val logLine: String,
        val attempt: Int = 1
    )
    private val pendingSend = AtomicReference<PendingSend?>(null)

    private val pendingRemovals = linkedMapOf<String, Runnable>()

    private var parkActive = false
    private val parkedKeys = linkedSetOf<String>()
    private var followerRunning = false
    
    private val fullScreenOwners = CopyOnWriteArraySet<String>()
    
    private val ownerMaxHoldTimers = HashMap<String, Runnable>()
    private var screenWidth = 1920
    private var screenHeight = 2560

    
    private val lastGeometry = HashMap<String, IntArray>()
    private var geometryEpoch = 0

    
    
    private val ownershipToken = "${android.os.Process.myPid()}-${System.nanoTime()}"
    @Volatile private var ownershipFile: java.io.File? = null
    @Volatile private var ownershipLost = false
    
    @Volatile private var ownershipCheckedModified = 0L

    fun claimOwnership(dir: java.io.File) {
        val file = java.io.File(dir, "pen_guard_owner")
        try {
            file.writeText(ownershipToken)
        } catch (e: Exception) {
            Log.e(TAG, "ownership claim failed: ${e.message}")
        }
        ownershipCheckedModified = file.lastModified()
        ownershipFile = file
        ownershipLost = false
        queueAppContextClear(NOTE_APP_NAME)
        queueAppContextClear(DOC_APP_NAME)
        markHostTableDirty()
        Log.i(TAG, "ownership claimed token=$ownershipToken app=$appName")
    }

    fun setAppContext(document: Boolean) {
        val next = if (document) DOC_APP_NAME else NOTE_APP_NAME
        if (next == appName) return
        val previous = appName
        if (document) {
            
            
            resetHostBaseline("enter document")
            pendingSend.set(null)
        }
        appName = next
        
        queueAppContextClear(previous)
        markHostTableDirty()
        Log.i(TAG, "pen-area app context changed: $previous -> $next")
        val hasSnapshot = synchronized(rects) {
            lastSnapshot.isNotEmpty() || rects.isNotEmpty() || fullScreenOwners.isNotEmpty()
        }
        if (hasSnapshot) submitSnapshot()
    }

    private fun queueAppContextClear(name: String) {
        pendingAppClears.add(name)
        worker.execute {
            if (isOwner()) clearQueuedAppContexts()
        }
    }

    private fun clearQueuedAppContexts() {
        while (true) {
            val name = pendingAppClears.poll() ?: return
            PenDisableClient.clearDisableAreas(name)
        }
    }

    private fun isOwner(): Boolean {
        if (ownershipLost) return false
        val file = ownershipFile ?: return true
        val modified = file.lastModified()
        if (modified != 0L && modified == ownershipCheckedModified) return true
        val current = try { file.readText() } catch (_: Exception) { return true }
        if (current == ownershipToken) {
            ownershipCheckedModified = modified
            return true
        }
        ownershipLost = true
        Log.e(TAG, "ownership lost to $current; this instance stops writing pen areas")
        main.post {
            fullScreenOwners.clear()
            ownerMaxHoldTimers.values.forEach(main::removeCallbacks)
            ownerMaxHoldTimers.clear()
            main.removeCallbacks(hostBootstrapFallback)
        }
        return false
    }

    private var logicalScreenWidth = 1920
    private var logicalScreenHeight = 2560
    private var displayRotation = Surface.ROTATION_0
    private var displayStateReady = false
    private var activeRotationEpoch = 0L
    private val hostBootstrapFallback: Runnable = Runnable {
        val useLocalFallback = synchronized(rects) {
            if (hostBaselineReady) {
                false
            } else {
                
                hostBaselineReady = true
                true
            }
        }
        Log.i(TAG, "host bootstrap safety release localFallback=$useLocalFallback ${debugState()}")
        setFullScreenActive(false, PenGuardOwner.HOST_BOOTSTRAP)
    }

    private fun startHostBootstrapSafety(reason: String) {
        main.removeCallbacks(hostBootstrapFallback)
        Log.i(TAG, "host bootstrap safety start reason=$reason " +
            "delayMs=${PenGuardTiming.HOST_BOOTSTRAP_FALLBACK_MS}")
        setFullScreenActive(true, PenGuardOwner.HOST_BOOTSTRAP)
        main.postDelayed(hostBootstrapFallback, PenGuardTiming.HOST_BOOTSTRAP_FALLBACK_MS)
    }

    
    fun onDisplayChanged(
        logicalWidth: Int,
        logicalHeight: Int,
        rotation: Int,
        reason: String,
        rotationPending: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onDisplayChanged(logicalWidth, logicalHeight, rotation, reason, rotationPending) }
            return
        }
        if (logicalWidth <= 0 || logicalHeight <= 0) return

        val normalizedRotation = rotation.takeIf {
            it in Surface.ROTATION_0..Surface.ROTATION_270
        } ?: Surface.ROTATION_0
        val naturalWidth = if (normalizedRotation == Surface.ROTATION_90 ||
            normalizedRotation == Surface.ROTATION_270) logicalHeight else logicalWidth
        val naturalHeight = if (normalizedRotation == Surface.ROTATION_90 ||
            normalizedRotation == Surface.ROTATION_270) logicalWidth else logicalHeight
        val changed = logicalScreenWidth != logicalWidth ||
            logicalScreenHeight != logicalHeight ||
            displayRotation != normalizedRotation ||
            !displayStateReady

        logicalScreenWidth = logicalWidth
        logicalScreenHeight = logicalHeight
        displayRotation = normalizedRotation
        screenWidth = naturalWidth
        screenHeight = naturalHeight
        displayStateReady = true
        if (changed) {
            
            geometryEpoch++
            generation.incrementAndGet()
            pendingSend.set(null)
            synchronized(rects) {
                hostSnapshot.clear()
                
                hostChromeLogical.clear()
                hostBaselineReady = false
                lastSentSnapshot.clear()
                hostTableDirty = true
            }
            
            if (parkedKeys.isNotEmpty()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "display change: drop parked=$parkedKeys")
                synchronized(rects) { parkedKeys.forEach { rects.remove(it); logicalRects.remove(it) } }
                parkedKeys.clear()
            }
            parkActive = false
            if (activeRotationEpoch == 0L && !rotationPending &&
                (views.isNotEmpty() || rects.isNotEmpty())) {
                startHostBootstrapSafety("display change: $reason")
            }
        }

        Log.i(TAG, "display state reason=$reason changed=$changed " +
            "logical=${logicalWidth}x${logicalHeight} rotation=$normalizedRotation " +
            "natural=${naturalWidth}x${naturalHeight} baselineReset=$changed " +
            "rotationPending=$rotationPending tracked=${views.keys}")

        
        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
        }
        if (views.isEmpty() && fullScreenOwners.isNotEmpty() && changed) submitSnapshot()
    }

    
    private val lastSnapshot = mutableListOf<Rect>()

    
    private val hostSnapshot = mutableListOf<Rect>()

    
    private val hostChromeLogical = mutableListOf<Rect>()

    
    private val lastSentSnapshot = mutableListOf<Rect>()

    
    private val lastQueuedSnapshot = mutableListOf<Rect>()
    private var lastQueuedAppName: String? = null

    
    private var hostTableDirty = true

    private var hostBaselineReady = false

    private fun markHostTableDirty() {
        synchronized(rects) { hostTableDirty = true }
    }

    
    fun noteHostTableRewritten(): Unit = markHostTableDirty()

    fun hasActiveRects(): Boolean = synchronized(rects) {
        fullScreenOwners.isNotEmpty() || rects.isNotEmpty()
    }

    
    fun dropIdleGuardState(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dropIdleGuardState(reason) }
            return
        }
        synchronized(rects) {
            
            if (rects.isNotEmpty() || fullScreenOwners.isNotEmpty()) return
            if (lastSnapshot.isEmpty()) return
            lastSnapshot.clear()
            Log.i(TAG, "idle guard dropped reason=$reason ${debugStateLocked()}")
        }
    }

    fun hasBlockingOwnerForRotation(): Boolean =
        fullScreenOwners.any { it != PenGuardOwner.ROTATION_SYNC }

    fun beginHostBootstrap(reason: String) {
        val reused = synchronized(rects) {
            if (hostSnapshot.isEmpty()) {
                hostBaselineReady = false
                false
            } else {
                hostBaselineReady = true
                true
            }
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host bootstrap ${if (reused) "reusing baseline" else "pending"} " +
                "reason=$reason ${debugState()}")
        }
        if (reused) {
            main.removeCallbacks(hostBootstrapFallback)
            main.post {
                setFullScreenActive(false, PenGuardOwner.HOST_BOOTSTRAP)
                submitSnapshot()
            }
        } else {
            startHostBootstrapSafety(reason)
        }
    }

    
    fun resetHostBaseline(reason: String) {
        synchronized(rects) {
            hostSnapshot.clear()
            hostChromeLogical.clear()
            hostBaselineReady = false
            hostTableDirty = true
        }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host baseline reset reason=$reason")
    }

    
    fun setHostChromeAreas(logical: List<Rect>, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val copy = logical.map(::Rect)
            main.post { setHostChromeAreas(copy, reason) }
            return
        }
        val next = PenAreaGeometry.normalize(logical, keepClearSentinel = false)
        val changed = synchronized(rects) {
            if (next == hostChromeLogical) false else {
                hostChromeLogical.clear()
                hostChromeLogical.addAll(next.map(::Rect))
                true
            }
        }
        if (!changed) return
        Log.i(TAG, "host chrome areas=$next reason=$reason")
        if (hasActiveRects()) submitSnapshot()
    }

    
    fun updateHostPenAreas(incoming: List<Rect>, commitBaseline: Boolean = true): Boolean {
        val raw = PenAreaGeometry.normalize(incoming, keepClearSentinel = true)
        var transientFullScreen = false
        var writableReset = false
        var baselineCommitted = false
        var becameReady = false
        var baselineInvalidated = false
        val accepted = synchronized(rects) {
            if (PenAreaGeometry.sameRects(raw, lastSentSnapshot)) return@synchronized false
            hostTableDirty = true
            writableReset = PenAreaGeometry.isWritableReset(raw)
            if (writableReset) {
                
                
                
                hostSnapshot.clear()
                hostBaselineReady = false
                baselineInvalidated = true
            }
            val normalized = raw.filterNot(PenAreaGeometry::isClearSentinel)
            transientFullScreen = normalized.size == 1 && isScreenCovering(normalized[0])
            if (commitBaseline && !writableReset && !transientFullScreen && normalized.isNotEmpty()) {
                
                
                if (appName != DOC_APP_NAME && displayRotation == Surface.ROTATION_0 &&
                    normalized.size == 1 &&
                    hostSnapshot.contains(normalized[0]) && hostChromeLogical.isEmpty()) {
                    val bottom = hostSnapshot.filter {
                        it.top >= logicalScreenHeight * 4 / 5 &&
                            it.bottom <= logicalScreenHeight && it.height() > 0
                    }
                    hostChromeLogical.addAll(bottom.map(::Rect))
                }
                hostSnapshot.clear()
                hostSnapshot.addAll(normalized.map(::Rect))
                baselineCommitted = true
                becameReady = !hostBaselineReady
                hostBaselineReady = true
            }
            true
        }
        if (baselineInvalidated) {
            
            
            
            pendingSend.set(null)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host baseline invalidated by writable reset")
            }
        }
        if (BuildConfig.ENABLE_DEBUG) {
            val status = when {
                !accepted -> "ignored-self-echo"
                writableReset -> "accepted-writable-reset"
                transientFullScreen -> "accepted-transient-full-screen"
                !commitBaseline -> "accepted-transient-ui"
                baselineCommitted -> "accepted-baseline"
                else -> "accepted-no-baseline"
            }
            Log.i(TAG, "host snapshot $status incoming=$raw ${debugState()}")
        }
        if (becameReady) {
            main.post {
                
                main.removeCallbacks(hostBootstrapFallback)
                cancelMaxHold(PenGuardOwner.HOST_BOOTSTRAP)
                fullScreenOwners.remove(PenGuardOwner.HOST_BOOTSTRAP)
                submitSnapshot()
            }
        }
        return accepted
    }

    fun debugState(): String = synchronized(rects) { debugStateLocked() }

    private fun debugStateLocked(): String =
        "generation=${generation.get()} hostReady=$hostBaselineReady dirty=$hostTableDirty " +
            "owners=$fullScreenOwners " +
            "tracked=${rects.mapValues { Rect(it.value) }} " +
            "inkling=${lastSnapshot.map(::Rect)} host=${hostSnapshot.map(::Rect)} " +
            "chrome=${hostChromeLogical.map(::Rect)} " +
            "lastSent=${lastSentSnapshot.map(::Rect)}"

    
    fun reassertDocumentAreas(reason: String) {
        if (appName == DOC_APP_NAME && hasActiveRects()) reassert(reason)
    }

    private fun canPublishDocumentLocalAreas(inkling: List<Rect>): Boolean =
        appName == DOC_APP_NAME && inkling.isNotEmpty()

    fun reassert(reason: String) {
        if (!hasActiveRects()) {
            dropIdleGuardState("reassert:$reason")
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert skipped: no live Inkling rects reason=$reason ${debugState()}")
            }
            return
        }
        val state = synchronized(rects) {
            val inkling = lastSnapshot.map(::Rect)
            Triple(inkling, mergeWithHost(inkling), hostBaselineReady)
        }
        val inklingSnapshot = state.first
        val outgoing = state.second
        val baselineReady = state.third
        if (inklingSnapshot.isEmpty()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert skipped: empty Inkling snapshot reason=$reason ${debugState()}")
            }
            return
        }
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot) &&
            !canPublishDocumentLocalAreas(inklingSnapshot)) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert deferred: host baseline pending reason=$reason ${debugState()}")
            }
            return
        }
        val version = generation.incrementAndGet()
        Log.i(TAG, "reassert queued reason=$reason version=$version " +
            "inkling=$inklingSnapshot outgoing=$outgoing")
        enqueueSend(PendingSend(clear = false, payload = outgoing, appName = appName,
            logLine = "reassert sending reason=$reason version=$version outgoing=$outgoing"))
    }

    private fun enqueueSend(send: PendingSend) {
        synchronized(rects) {
            lastQueuedSnapshot.clear()
            lastQueuedSnapshot.addAll(send.payload.map(::Rect))
            lastQueuedAppName = send.appName
            hostTableDirty = false
        }
        pendingSend.set(send)
        worker.execute { drainPendingSend() }
    }

    
    private fun drainPendingSend() {
        val current = pendingSend.getAndSet(null)
        if (current == null) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "send coalesced: slot already drained")
            return
        }
        if (!isOwner()) return
        clearQueuedAppContexts()
        synchronized(rects) {
            lastSentSnapshot.clear()
            lastSentSnapshot.addAll(current.payload.map(::Rect))
        }
        Log.i(TAG, if (current.attempt == 1) current.logLine
            else "retry attempt=${current.attempt} ${current.logLine}")
        val result = try {
            if (current.clear) PenDisableClient.clearDisableAreas(current.appName)
            else PenDisableClient.sendDisableAreas(current.appName, current.payload)
        } catch (e: Exception) {
            Log.e(TAG, "send failed: ${e.message}")
            PenDisableClient.Result.FAILED
        }
        when (result) {
            PenDisableClient.Result.OK -> Unit
            PenDisableClient.Result.NO_SERVICE -> {
                markHostTableDirty()
                
                val retry = current.copy(attempt = current.attempt + 1)
                if (current.attempt < PenGuardTiming.BINDER_RETRY_ATTEMPTS &&
                    pendingSend.compareAndSet(null, retry)) {
                    worker.schedule(Runnable { drainPendingSend() },
                        PenGuardTiming.BINDER_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                } else if (current.attempt >= PenGuardTiming.BINDER_RETRY_ATTEMPTS) {
                    Log.e(TAG, "send abandoned after ${current.attempt} attempts: drawPath service unavailable")
                }
            }
            PenDisableClient.Result.FAILED -> markHostTableDirty()
        }
    }

    fun beginRotationSync(epoch: Long, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginRotationSync(epoch, reason) }
            return
        }
        activeRotationEpoch = epoch
        main.removeCallbacks(hostBootstrapFallback)
        cancelMaxHold(PenGuardOwner.HOST_BOOTSTRAP)
        fullScreenOwners.remove(PenGuardOwner.HOST_BOOTSTRAP)
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation enter epoch=$epoch reason=$reason")
        val changed = fullScreenOwners.add(PenGuardOwner.ROTATION_SYNC)
        Log.i(TAG, "fullScreen=true owner=${PenGuardOwner.ROTATION_SYNC} active=true owners=$fullScreenOwners")
        if (changed) submitSnapshot()
    }

    fun refreshAllForRotation(epoch: Long): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (activeRotationEpoch != epoch) return false
        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refreshInternal(key, it, epoch) }
                ?: removeIfCurrent(key, reference)
        }
        val ready = views.keys.all { viewRotationEpochs[it] == epoch }
        Log.i(TAG, "views ready epoch=$epoch ready=$ready tracked=${views.keys} " +
            "viewEpochs=$viewRotationEpochs")
        return ready
    }

    fun cancelRotationSync(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancelRotationSync(reason) }
            return
        }
        if (activeRotationEpoch == 0L && PenGuardOwner.ROTATION_SYNC !in fullScreenOwners) return
        val epoch = activeRotationEpoch
        activeRotationEpoch = 0L
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation cancelled epoch=$epoch reason=$reason")
        setFullScreenActive(false, PenGuardOwner.ROTATION_SYNC)
    }

    private fun mergeWithHost(inkling: List<Rect>): List<Rect> =
        PenAreaGeometry.normalize(
            hostSnapshot.map(::hostRectToNatural) +
                hostChromeLogical.map {
                    PenAreaGeometry.toNaturalDisplayRect(it, displayRotation, screenWidth, screenHeight)
                } +
                inkling,
            keepClearSentinel = false
        )

    
    private fun hostRectToNatural(r: Rect): Rect {
        if (displayRotation == Surface.ROTATION_0) return Rect(r)
        val fitsNatural = r.right <= screenWidth && r.bottom <= screenHeight
        val fitsLogical = r.right <= logicalScreenWidth && r.bottom <= logicalScreenHeight
        if (!fitsLogical && fitsNatural) return Rect(r)
        return PenAreaGeometry.toNaturalDisplayRect(r, displayRotation, screenWidth, screenHeight)
    }

    private fun isFullScreenSnapshot(snapshot: List<Rect>): Boolean =
        snapshot.size == 1 && isScreenCovering(snapshot[0])

    private fun isScreenCovering(rect: Rect): Boolean =
        PenAreaGeometry.isScreenCovering(rect, screenWidth, screenHeight)

    private val follower = object : Runnable {
        override fun run() {
            if (views.isEmpty()) {
                followerRunning = false
                return
            }
            views.toMap().forEach { (key, reference) ->
                reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
            }
            main.postDelayed(this, PenGuardTiming.FOLLOW_INTERVAL_MS)
        }
    }

    fun track(key: String, view: View) {
        views[key] = WeakReference(view)
        viewRotationEpochs.remove(key)
        
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
            lastGeometry.remove(key)
        } else main.post {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
            lastGeometry.remove(key)
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh(key, v)
            override fun onViewDetachedFromWindow(v: View) = removeIfCurrent(key, v)
        }
        view.addOnAttachStateChangeListener(listener)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> refresh(key, v) }
        view.post { refresh(key, view) }
        if (!followerRunning) {
            followerRunning = true
            main.post(follower)
        }
    }

    fun refresh(key: String, view: View) = refreshInternal(key, view, null)

    private fun refreshInternal(key: String, view: View, rotationEpoch: Long?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshInternal(key, view, rotationEpoch) }
            return
        }
        if (views[key]?.get() !== view) return
        
        if (!view.isAttachedToWindow) return
        
        if (!view.isShown) {
            releaseRect(key)
            return
        }
        pendingRemovals.remove(key)?.let(main::removeCallbacks)
        parkedKeys.remove(key)
        val screen = IntArray(2).also(view::getLocationOnScreen)

        
        if (rotationEpoch == null && displayStateReady) {
            val cached = lastGeometry[key]
            if (cached != null && cached[0] == screen[0] && cached[1] == screen[1] &&
                cached[2] == view.width && cached[3] == view.height &&
                cached[4] == geometryEpoch && synchronized(rects) { rects.containsKey(key) }) {
                return
            }
        }

        val metrics = view.resources.displayMetrics
        val display = view.display
        val effectiveLogicalWidth = if (displayStateReady) logicalScreenWidth else metrics.widthPixels
        val effectiveLogicalHeight = if (displayStateReady) logicalScreenHeight else metrics.heightPixels
        val rotation = if (displayStateReady) displayRotation
            else display?.rotation ?: readUserRotation(view)
        val effectiveNaturalWidth = if (displayStateReady) screenWidth else metrics.widthPixels
        val effectiveNaturalHeight = if (displayStateReady) screenHeight else metrics.heightPixels
        if (!displayStateReady) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        if (view.width <= 0 || view.height <= 0) return

        
        val logicalRect = Rect(
            (screen[0] - PAD_PX).coerceAtLeast(0),
            (screen[1] - PAD_PX).coerceAtLeast(0),
            (screen[0] + view.width + PAD_PX).coerceAtMost(effectiveLogicalWidth),
            (screen[1] + view.height + PAD_PX).coerceAtMost(effectiveLogicalHeight)
        )
        if (logicalRect.isEmpty) return
        val drawPathRect = PenAreaGeometry.toNaturalDisplayRect(
            logicalRect,
            rotation,
            effectiveNaturalWidth,
            effectiveNaturalHeight
        )
        if (drawPathRect.isEmpty) return
        if (views[key]?.get() !== view) return
        val changed = synchronized(rects) {
            logicalRects[key] = Rect(logicalRect)
            if (rects[key] == drawPathRect) false else {
                rects[key] = Rect(drawPathRect)
                true
            }
        }
        lastGeometry[key] = intArrayOf(screen[0], screen[1], view.width, view.height, geometryEpoch)
        if (rotationEpoch != null && activeRotationEpoch == rotationEpoch) {
            viewRotationEpochs[key] = rotationEpoch
        }
        if (!changed) return
        if (key == "toolbar") MosaicLink.publishToolbarRect(view.context, logicalRect)
        if (BuildConfig.ENABLE_DEBUG) {
            val window = IntArray(2).also(view::getLocationInWindow)
            Log.i(TAG, "refresh key=$key screen=${screen[0]},${screen[1]} " +
                "window=${window[0]},${window[1]} size=${view.width}x${view.height} " +
                "logicalDisplay=${effectiveLogicalWidth}x${effectiveLogicalHeight} " +
                "naturalDisplay=${effectiveNaturalWidth}x${effectiveNaturalHeight} " +
                "displayRotation=${display?.rotation} drawPathRotation=$rotation " +
                "logicalRect=$logicalRect drawPathRect=$drawPathRect")
        }
        submitSnapshot()
    }

    
    private fun readUserRotation(view: View): Int = try {
        Settings.System.getInt(
            view.context.contentResolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_0
        )
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    fun remove(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { remove(key) }
            return
        }
        views.remove(key)
        viewRotationEpochs.remove(key)
        lastGeometry.remove(key)
        releaseRect(key)
    }

    
    private fun releaseRect(key: String) {
        if (key == "toolbar") MosaicLink.publishToolbarRect(null, null)
        if (!synchronized(rects) { rects.containsKey(key) }) return
        if (parkActive) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.add(key)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "remove key=$key parked")
            return
        }
        if (pendingRemovals.containsKey(key)) return
        val r = Runnable {
            pendingRemovals.remove(key)
            val changed = synchronized(rects) {
                logicalRects.remove(key)
                rects.remove(key) != null
            }
            if (!changed) return@Runnable
            Log.i(TAG, "remove key=$key")
            submitSnapshot()
        }
        pendingRemovals[key] = r
        main.postDelayed(r, PenGuardTiming.REMOVE_LINGER_MS)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "remove key=$key linger=${PenGuardTiming.REMOVE_LINGER_MS}ms")
        }
    }

    
    fun beginPark(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginPark(reason) }
            return
        }
        if (!parkActive && BuildConfig.ENABLE_DEBUG) Log.i(TAG, "park begin reason=$reason")
        parkActive = true
        
        for ((key, r) in pendingRemovals) { main.removeCallbacks(r); parkedKeys.add(key) }
        pendingRemovals.clear()
    }

    
    fun endPark(reason: String, flush: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { endPark(reason, flush) }
            return
        }
        if (!parkActive && parkedKeys.isEmpty()) return
        parkActive = false
        val released = parkedKeys.filter { flush || views[it]?.get() == null }
        parkedKeys.clear()
        if (released.isEmpty()) return
        val changed = synchronized(rects) {
            released.count { rects.remove(it).also { _ -> logicalRects.remove(it) } != null }
        } > 0
        Log.i(TAG, "park end reason=$reason flush=$flush released=$released")
        if (changed) submitSnapshot()
    }

    private fun removeIfCurrent(key: String, view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { removeIfCurrent(key, view) }
            return
        }
        if (views[key]?.get() !== view) return
        remove(key)
    }

    private fun removeIfCurrent(key: String, reference: WeakReference<View>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { removeIfCurrent(key, reference) }
            return
        }
        if (views[key] !== reference) return
        remove(key)
    }

    
    fun setFullScreenActive(active: Boolean, owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setFullScreenActive(active, owner) }
            return
        }
        if (active) armMaxHold(owner) else cancelMaxHold(owner)
        val changed = if (active) fullScreenOwners.add(owner) else fullScreenOwners.remove(owner)
        if (!changed) return
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} owner=$owner active=$active owners=$fullScreenOwners")
        submitSnapshot(restoringHost = !active && owner == PenGuardOwner.PLUGIN_HOST)
    }

    
    fun releaseScopes(reason: String, vararg scopes: PenGuardScope) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val copy = scopes.copyOf()
            main.post { releaseScopes(reason, *copy) }
            return
        }
        val released = fullScreenOwners.filter { PenGuardOwner.scopeOf(it) in scopes }
        if (released.isEmpty()) return
        released.forEach {
            cancelMaxHold(it)
            fullScreenOwners.remove(it)
        }
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} released=$released " +
            "scopes=${scopes.joinToString()} reason=$reason owners=$fullScreenOwners")
        submitSnapshot()
    }

    private fun armMaxHold(owner: String) {
        val limit = PenGuardOwner.maxHoldMs(owner) ?: return
        ownerMaxHoldTimers.remove(owner)?.let(main::removeCallbacks)
        val timer = Runnable {
            ownerMaxHoldTimers.remove(owner)
            if (owner !in fullScreenOwners) return@Runnable
            Log.w(TAG, "fullScreen owner=$owner exceeded max hold ${limit}ms; releasing")
            setFullScreenActive(false, owner)
        }
        ownerMaxHoldTimers[owner] = timer
        main.postDelayed(timer, limit)
    }

    private fun cancelMaxHold(owner: String) {
        ownerMaxHoldTimers.remove(owner)?.let(main::removeCallbacks)
    }

    
    private fun publishOverlayRectsToMosaic(force: Boolean = false) {
        val out = synchronized(rects) {
            val hasFullScreen = fullScreenOwners.any { it != PenGuardOwner.ROTATION_SYNC }
            if (hasFullScreen) {
                listOf(Rect(0, 0, logicalScreenWidth, logicalScreenHeight))
            } else {
                logicalRects.entries
                    .filter { it.key != "toolbar" }
                    .map { Rect(it.value) }
                    .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            }
        }
        MosaicLink.publishOverlayRects(null, out, force = force)
    }

    
    internal fun republishMosaicSnapshot(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { republishMosaicSnapshot(reason) }
            return
        }
        if (!MosaicLink.isBoardVisible()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "Mosaic snapshot republish skipped: board hidden reason=$reason")
            }
            return
        }

        val toolbarRect = synchronized(rects) {
            logicalRects["toolbar"]?.let(::Rect)
        }
        MosaicLink.publishToolbarRect(null, toolbarRect, force = true)
        publishOverlayRectsToMosaic(force = true)
        Log.i(TAG, "Mosaic snapshot republished reason=$reason toolbar=$toolbarRect")
    }

    private fun submitSnapshot(restoringHost: Boolean = false) {
        val state = synchronized(rects) {
            
            
            val hadFullScreen = isFullScreenSnapshot(lastSnapshot) ||
                lastSentSnapshot.any(::isScreenCovering)
            val inkling = if (fullScreenOwners.isNotEmpty()) {
                listOf(Rect(0, 0, screenWidth, screenHeight))
            } else {
                PenAreaGeometry.normalize(rects.values.map(::Rect), keepClearSentinel = false)
            }
            lastSnapshot.clear()
            lastSnapshot.addAll(inkling.map(::Rect))
            Pair(
                Triple(inkling, mergeWithHost(inkling), hostBaselineReady),
                hadFullScreen
            )
        }
        
        
        
        if (MosaicLink.isBoardVisible()) publishOverlayRectsToMosaic()

        val inklingSnapshot = state.first.first
        val outgoing = state.first.second
        val baselineReady = state.first.third
        val leavingFullScreen = !isFullScreenSnapshot(inklingSnapshot) && state.second
        if (restoringHost && !isFullScreenSnapshot(inklingSnapshot) &&
            (inklingSnapshot.isEmpty() || !baselineReady)) {
            
            
            pendingSend.set(null)
            synchronized(rects) {
                lastQueuedSnapshot.clear()
                lastSentSnapshot.clear()
                hostTableDirty = true
            }
            Log.i(TAG, "host restore handoff inkling=$inklingSnapshot baselineReady=$baselineReady")
            return
        }
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot) && !leavingFullScreen &&
            !canPublishDocumentLocalAreas(inklingSnapshot)) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "snapshot deferred: host baseline pending " +
                    "inkling=$inklingSnapshot outgoing=$outgoing ${debugState()}")
            }
            return
        }
        
        
        val clear = outgoing.isEmpty()
        val payload = if (clear) listOf(PenAreaGeometry.clearSentinel()) else outgoing
        val sendAppName = appName
        
        
        val duplicate = synchronized(rects) {
            !hostTableDirty && lastQueuedAppName == sendAppName &&
                PenAreaGeometry.sameRects(payload, lastQueuedSnapshot)
        }
        if (duplicate) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "snapshot unchanged, send skipped outgoing=$outgoing")
            }
            return
        }
        val version = generation.incrementAndGet()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "snapshot queued version=$version inkling=$inklingSnapshot " +
                "outgoing=$outgoing leavingFullScreen=$leavingFullScreen")
        }
        val mode = when {
            leavingFullScreen && !clear -> "exit-full-screen-local"
            !baselineReady && canPublishDocumentLocalAreas(inklingSnapshot) -> "document-local"
            outgoing.isEmpty() -> "clear"
            inklingSnapshot.isEmpty() -> "host-only"
            else -> "merged"
        }
        enqueueSend(PendingSend(clear = clear, payload = payload, appName = sendAppName,
            logLine = "snapshot sending version=$version mode=$mode " +
                "inkling=$inklingSnapshot outgoing=$outgoing payload=$payload"))
    }
}
