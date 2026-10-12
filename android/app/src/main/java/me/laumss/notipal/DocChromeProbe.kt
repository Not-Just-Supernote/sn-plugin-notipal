package me.laumss.notipal

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors


internal object DocChromeProbe {
    private const val TAG = "DocChromeProbe"
    private const val SETTLE_MS = 400L
    private const val DOC_ACTIVITY_MARKER = "ACTIVITY com.supernote.document/"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pendingReason = ""
    @Volatile private var probeInFlight = false
    @Volatile private var contextEpoch = 0L
    private var absentMenuToken: Pair<Int, Long>? = null

    
    private val VIEW_LINE =
        Regex("""^( *)([A-Za-z0-9_.$]+)\{[0-9a-f]+ (\S)\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)""")

    private val probeRunnable: Runnable = Runnable {
        val reason = pendingReason
        if (!FloatingToolbarModule.isDocumentContext()) {
            FloatingPenGuard.setHostChromeAreas(emptyList(), "not document: $reason")
            return@Runnable
        }
        if (probeInFlight) return@Runnable
        probeInFlight = true
        val epoch = contextEpoch
        val menuToken = SubviewLogMonitor.toolbarMenuProbeToken()
        worker.execute {
            val snapshot = query()
            main.post {
                probeInFlight = false
                if (epoch != contextEpoch) return@post
                if (snapshot == null) {
                    absentMenuToken = null
                    return@post
                }
                if (FloatingToolbarModule.isDocumentContext()) {
                    FloatingPenGuard.setHostChromeAreas(snapshot.rects, reason)
                    if (menuToken != null && menuToken == SubviewLogMonitor.toolbarMenuProbeToken() &&
                        snapshot.menuVisible == false) {
                        if (absentMenuToken == menuToken) {
                            Log.i(TAG, "document toolbar menus closed generation=${menuToken.first}")
                            SubviewLogMonitor.closeDocumentToolbarMenu(menuToken)
                            absentMenuToken = null
                        } else {
                            absentMenuToken = menuToken
                        }
                    } else {
                        absentMenuToken = null
                    }
                } else {
                    FloatingPenGuard.setHostChromeAreas(emptyList(), "left document: $reason")
                }
            }
        }
    }

    
    fun request(reason: String) {
        pendingReason = reason
        main.removeCallbacks(probeRunnable)
        main.postDelayed(probeRunnable, SETTLE_MS)
    }

    
    fun clear(reason: String) {
        contextEpoch++
        main.removeCallbacks(probeRunnable)
        FloatingPenGuard.setHostChromeAreas(emptyList(), reason)
    }

    private fun query(): Snapshot? = try {
        val proc = ProcessBuilder("dumpsys", "activity", "top")
            .redirectErrorStream(true).start()
        val result = proc.inputStream.bufferedReader().useLines { parseSnapshot(it) }
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

    
    internal data class Snapshot(val rects: List<Rect>, val menuVisible: Boolean?)

    internal fun parse(lines: Sequence<String>): List<Rect> = parseSnapshot(lines).rects

    internal fun parseSnapshot(lines: Sequence<String>): Snapshot {
        val out = mutableListOf<Rect>()
        var inDoc = false
        var resumed = false
        var sawActiveSideBar = false
        var menuVisible = false
        var inHierarchy = false
        var hierarchyIndent = -1
        val stack = ArrayDeque<Node>()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("  ACTIVITY ")) {
                inDoc = line.contains(DOC_ACTIVITY_MARKER)
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
            if (visible && inSideBar && cls.endsWith(".ButtonBoxView") && r > l && b > t) {
                out += Rect(left, top, left + (r - l), top + (b - t))
            }
        }
        return Snapshot(out, if (sawActiveSideBar) menuVisible else null)
    }
}
