package me.laumss.notipal

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors


internal object DocChromeProbe {
    private const val TAG = "DocChromeProbe"
    private const val DOC_ACTIVITY_MARKER = "ACTIVITY com.supernote.document/"
    private const val NOTE_ACTIVITY_MARKER = "ACTIVITY com.ratta.supernote.note/"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pendingReason = ""
    @Volatile private var probeInFlight = false
    @Volatile private var contextEpoch = 0L

    
    private val VIEW_LINE =
        Regex("""^( *)([A-Za-z0-9_.$]+)\{[0-9a-f]+ (\S)\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)""")

    private var probePending = false
    private val probeRunnable: Runnable = Runnable {
        if (probeInFlight) {
            probePending = true
            return@Runnable
        }
        val reason = pendingReason
        val document = FloatingToolbarModule.isDocumentContext()
        val epoch = contextEpoch
        val menuToken = SubviewLogMonitor.toolbarMenuProbeToken()
        probePending = false
        probeInFlight = true
        worker.execute {
            val snapshot = query(document)
            main.post {
                probeInFlight = false
                if (epoch == contextEpoch && snapshot != null &&
                    FloatingToolbarModule.isDocumentContext() == document) {
                    FloatingPenGuard.setHostChromeAreas(snapshot.rects, reason)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                        "probe reason=$reason document=$document menuVisible=${snapshot.menuVisible} token=$menuToken rects=${snapshot.rects}")
                    if (document && menuToken != null &&
                        menuToken == SubviewLogMonitor.toolbarMenuProbeToken() && snapshot.menuVisible == false) {
                        SubviewLogMonitor.closeDocumentToolbarMenu(menuToken)
                    }
                }
                
                if (probePending) request(pendingReason)
            }
        }
    }

    
    fun request(reason: String) {
        pendingReason = reason
        main.removeCallbacks(probeRunnable)
        main.post(probeRunnable)
    }

    fun clear(reason: String) {
        contextEpoch++
        probePending = false
        main.removeCallbacks(probeRunnable)
        FloatingPenGuard.setHostChromeAreas(emptyList(), reason)
    }

    private fun query(document: Boolean): Snapshot? = try {
        val proc = ProcessBuilder("dumpsys", "activity", "top")
            .redirectErrorStream(true).start()
        val result = proc.inputStream.bufferedReader().useLines { parseSnapshot(it, document) }
        if (proc.waitFor() == 0) result else null
    } catch (e: Exception) {
        Log.w(TAG, "dumpsys failed: ${e.message}")
        null
    }

    private class Node(
        val indent: Int,
        val left: Int,
        val top: Int,
        val visible: Boolean,
        val inSideBar: Boolean,
        val isSideBar: Boolean = false,
    )

    
    internal data class Snapshot(val rects: List<Rect>, val menuVisible: Boolean?, val bottomVisible: Boolean = false)

    internal fun parse(lines: Sequence<String>): List<Rect> = parseSnapshot(lines).rects

    internal fun parseSnapshot(lines: Sequence<String>, document: Boolean = true): Snapshot {
        val out = mutableListOf<Rect>()
        var inDoc = false
        var resumed = false
        var sawActiveSideBar = false
        var menuVisible = false
        var bottomVisible = false
        var inHierarchy = false
        var hierarchyIndent = -1
        val stack = ArrayDeque<Node>()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("  ACTIVITY ")) {
                inDoc = line.contains(if (document) DOC_ACTIVITY_MARKER else NOTE_ACTIVITY_MARKER)
                resumed = false
                inHierarchy = false
                stack.clear()
                continue
            }
            if (!inDoc) continue
            if (line.contains("mResumed=true")) resumed = true
            val indent = line.indexOfFirst { it != ' ' }
            if (indent < 0) continue
            if (!inHierarchy) {
                if (line.trim() == "View Hierarchy:") {
                    inHierarchy = true
                    hierarchyIndent = indent
                    stack.clear()
                }
                continue
            }
            if (indent <= hierarchyIndent) {
                inHierarchy = false
                continue
            }
            while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeLast()
            val match = VIEW_LINE.find(line)
            if (match == null) {
                if (line.trimStart().startsWith("DecorView")) {
                    stack.clear()
                    stack.addLast(Node(indent, 0, 0, visible = true, inSideBar = false))
                }
                continue
            }
            val parent = stack.lastOrNull()
            val cls = match.groupValues[2]
            val l = match.groupValues[4].toInt()
            val t = match.groupValues[5].toInt()
            val r = match.groupValues[6].toInt()
            val b = match.groupValues[7].toInt()
            val left = (parent?.left ?: 0) + l
            val top = (parent?.top ?: 0) + t
            val visible = (parent?.visible ?: true) && match.groupValues[3] == "V"
            val inSideBar = (parent?.inSideBar ?: false) || cls.endsWith(".SideBarView")
            val isSideBar = cls.endsWith(".SideBarView")
            stack.addLast(Node(indent, left, top, visible, inSideBar, isSideBar))
            if (isSideBar && resumed) sawActiveSideBar = true
            
            
            val focusable = line.getOrNull(match.groups[3]!!.range.last + 1) == 'F'
            if (resumed && parent?.isSideBar == true && visible && focusable && r > l && b > t) {
                menuVisible = true
            }
            val bottomChrome =
                (line.contains("app:id/ll_page}") || line.contains("app:id/ll_fileName}"))
            if (resumed && visible && r > l && b > t &&
                ((document && inSideBar && cls.endsWith(".ButtonBoxView")) || bottomChrome)) {
                if (bottomChrome) bottomVisible = true
                out += Rect(left, top, left + (r - l), top + (b - t))
            }
        }
        return Snapshot(out, if (sawActiveSideBar) menuVisible else null, bottomVisible)
    }
}
