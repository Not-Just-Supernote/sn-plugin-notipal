package me.laumss.notipal.relay.llm

import android.util.Log
import me.laumss.notipal.relay.net.HttpJson
import me.laumss.notipal.relay.net.RawHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


class SseLlmBridge(
    private val baseUrl: String,  
    
    private val followActive: Boolean = true,
) : LlmBridge {
    companion object {
        private const val TAG = "SseLlmBridge"
        const val DEFAULT_PORT = 8080
        private const val SSE_RECONNECT_DELAY_MS = 3000L
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val SSE_READ_TIMEOUT_MS = 90_000
        
        private const val PREFILL_TIMEOUT_MS = 300_000
    }

    override var onStreamToken: ((String) -> Unit)? = null
    override var onReplyComplete: ((String) -> Unit)? = null
    override var onConnectionChanged: ((Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val currentConversationId = AtomicReference<String?>(null)
    private var sseThread: Thread? = null
    private var globalStreamThread: Thread? = null

    
    private val activeSse = AtomicReference<RawHttp.Response?>(null)
    private val activeGlobalSse = AtomicReference<RawHttp.Response?>(null)

    @Volatile override var isConnected = false; private set

    
    @Volatile private var lastKnownAssistantText = ""
    @Volatile private var wasGenerating = false
    
    @Volatile private var isInitialSseSnapshot = true
    
    @Volatile private var lastDeliveredAssistantText = ""
    
    @Volatile private var deliveredThisRound = false
    
    @Volatile private var roundBaselineText = ""
    
    @Volatile private var queryPending = false
    
    @Volatile private var awaitingRelayQueryResponse = false

    
    
    
    
    
    @Volatile private var prefillStreamActive = false
    @Volatile private var prefillSeed = ""
    
    @Volatile private var prefillLastContent = ""
    
    @Volatile private var prefillBranchOpen = false
    @Volatile private var prefillBranchesDone = 0
    @Volatile private var prefillExpectedCount = 0

    

    override fun start(conversationId: String?) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "start: already running")
            return
        }
        Log.i(TAG, "start: baseUrl=$baseUrl conversationId=$conversationId")

        Thread {
            try {
                val convId = conversationId ?: createConversation()
                if (convId == null) {
                    Log.e(TAG, "start: failed to create conversation")
                    running.set(false)
                    isConnected = false
                    onConnectionChanged?.invoke(false)
                    return@Thread
                }
                currentConversationId.set(convId)
                Log.i(TAG, "start: conversationId=$convId — starting SSE")
                
                
                

                
                
                if (followActive) startGlobalStreamLoop()
                startSseLoop(convId)
            } catch (e: Exception) {
                Log.e(TAG, "start failed: ${e.message}", e)
                running.set(false)
                isConnected = false
                onConnectionChanged?.invoke(false)
            }
        }.also { it.name = "LlmBridge-init"; it.isDaemon = true; it.start() }
    }

    override fun stop() {
        Log.i(TAG, "stop")
        running.set(false)
        
        
        
        closeQuietly(activeSse)
        closeQuietly(activeGlobalSse)
        sseThread?.interrupt()
        sseThread = null
        globalStreamThread?.interrupt()
        globalStreamThread = null
        prefillStreamActive = false  
        isConnected = false
        onConnectionChanged?.invoke(false)
    }

    private fun closeQuietly(ref: AtomicReference<RawHttp.Response?>) {
        ref.getAndSet(null)?.let {
            try { it.close() } catch (_: Exception) {}
        }
    }

    

    override fun sendUserMessage(text: String) {
        if (currentConversationId.get() == null) {
            Log.w(TAG, "sendUserMessage: no conversation, dropping")
            return
        }
        Log.i(TAG, "sendUserMessage: ${text.take(50)}")

        
        
        if (prefillStreamActive) deactivatePrefillStream()  
        wasGenerating = false
        queryPending = true
        awaitingRelayQueryResponse = true
        roundBaselineText = lastKnownAssistantText
        
        

        Thread {
            try {
                
                val activeId = if (followActive) fetchActive()?.first else null
                val convId = if (activeId != null && activeId != currentConversationId.get()) {
                    Log.i(TAG, "sendUserMessage: active conversation changed → $activeId, switching")
                    switchConversation(activeId)
                    activeId
                } else {
                    currentConversationId.get() ?: return@Thread
                }

                
                
                
                
                val body = JSONObject().put("text", text)
                val resp = HttpJson.postJson(
                    "$baseUrl/api/conversations/$convId/relay-query",
                    body,
                    PREFILL_TIMEOUT_MS,
                )
                Log.i(TAG, "sendUserMessage response: ${resp.take(200)}")
                val handledAsPrefill = deliverRelayQueryBranches(resp)
                awaitingRelayQueryResponse = false
                if (!handledAsPrefill) {
                    deliveredThisRound = false  
                    resumeBufferedRelayMessage()
                }
            } catch (e: Exception) {
                awaitingRelayQueryResponse = false
                queryPending = false
                Log.e(TAG, "sendUserMessage failed: ${e.message}", e)
                
            }
        }.also { it.name = "LlmBridge-send"; it.isDaemon = true; it.start() }
    }

    
    private fun deliverRelayQueryBranches(response: String): Boolean {
        val json = try { JSONObject(response) } catch (_: Exception) { return false }
        when (json.optString("mode")) {
            "prefill_branches_stream" -> {
                prefillSeed = json.optString("seed")
                prefillExpectedCount = json.optInt("count", 1)
                prefillStreamActive = true
                prefillLastContent = ""
                prefillBranchOpen = false
                prefillBranchesDone = 0
                queryPending = false
                deliveredThisRound = true
                Log.i(TAG, "relay-query: streaming $prefillExpectedCount prefill branches (seed ${prefillSeed.length} chars)")
                
                
                val buffered = lastKnownAssistantText
                if (buffered.isNotEmpty() && buffered != roundBaselineText) {
                    handlePrefillUpdate(buffered, wasGenerating)
                }
                return true
            }
            "prefill_branches" -> Unit
            else -> return false
        }
        val arr = json.optJSONArray("texts") ?: return true
        val texts = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotEmpty) }
        if (texts.isEmpty()) return true
        Log.i(TAG, "relay-query: ${texts.size} prefill branches")
        texts.lastOrNull()?.let {
            lastDeliveredAssistantText = it
        }
        queryPending = false
        deliveredThisRound = true
        texts.forEach { onReplyComplete?.invoke(it) }
        wasGenerating = false
        return true
    }

    private fun resumeBufferedRelayMessage() {
        val buffered = lastKnownAssistantText
        if (buffered.isEmpty() || buffered == roundBaselineText) return
        if (wasGenerating) {
            Log.i(TAG, "relay-query: replaying buffered SSE text (len=${buffered.length})")
            onStreamToken?.invoke(buffered)
        } else {
            Log.i(TAG, "relay-query: delivering buffered completed reply (len=${buffered.length})")
            deliverReply(buffered)
        }
    }

    

    
    private fun fetchActive(): Pair<String, Boolean>? {
        val json = HttpJson.getJsonOrNull("$baseUrl/api/conversations/active", 3_000) ?: return null
        val id = json.optString("conversationId", "").ifEmpty { null } ?: return null
        return id to json.optBoolean("isOnScreen", false)
    }

    
    private fun switchConversation(conversationId: String) {
        Log.i(TAG, "switchConversation: $conversationId")
        
        
        if (prefillStreamActive) deactivatePrefillStream()
        
        closeQuietly(activeSse)
        sseThread?.interrupt()
        currentConversationId.set(conversationId)
        
        
        lastKnownAssistantText = lastDeliveredAssistantText
        wasGenerating = false

        if (running.get()) {
            Thread { startSseLoop(conversationId) }
                .also { it.name = "LlmBridge-switch"; it.isDaemon = true; it.start() }
        }
    }

    
    
    
    private fun startGlobalStreamLoop() {
        Thread {
            while (running.get()) {
                try {
                    connectGlobalStream()
                } catch (e: InterruptedException) {
                    Log.i(TAG, "global stream interrupted, stopping")
                    break
                } catch (e: Exception) {
                    if (!running.get()) break
                    Log.w(TAG, "global stream error: ${e.message}, reconnect in ${SSE_RECONNECT_DELAY_MS}ms")
                    try { Thread.sleep(SSE_RECONNECT_DELAY_MS) } catch (_: InterruptedException) { break }
                }
            }
        }.also { globalStreamThread = it; it.name = "LlmBridge-globalStream"; it.isDaemon = true; it.start() }
    }

    private fun connectGlobalStream() {
        val conn = openSse("$baseUrl/api/conversations/stream") ?: return
        activeGlobalSse.getAndSet(conn)?.let { try { it.close() } catch (_: Exception) {} }
        try {
            Log.i(TAG, "global conversation stream connected")
            followActiveConversation()

            val reader = BufferedReader(InputStreamReader(conn.body, Charsets.UTF_8))
            var sawEvent = false
            while (running.get()) {
                val line = reader.readLine() ?: break  
                when {
                    
                    line.startsWith("event:") || line.startsWith("data:") -> sawEvent = true
                    line.isEmpty() && sawEvent -> {
                        sawEvent = false
                        followActiveConversation()
                    }
                }
            }
        } finally {
            activeGlobalSse.compareAndSet(conn, null)
            conn.close()
        }
    }

    
    private fun followActiveConversation() {
        val (id, isOnScreen) = fetchActive() ?: return
        if (!isOnScreen) return
        if (id != currentConversationId.get()) {
            Log.i(TAG, "followActive: active conversation → $id, switching")
            switchConversation(id)
        }
    }

    private fun createConversation(): String? {
        try {
            val resp = HttpJson.postJson("$baseUrl/api/conversations", JSONObject(), HTTP_TIMEOUT_MS)
            val json = JSONObject(resp)
            val id = json.optString("conversationId", "").ifEmpty { json.optString("id", "") }
            if (id.isNotEmpty()) {
                Log.i(TAG, "createConversation: id=$id")
                return id
            }
        } catch (e: Exception) {
            Log.w(TAG, "createConversation via POST failed: ${e.message}")
        }
        
        
        val id = java.util.UUID.randomUUID().toString()
        Log.i(TAG, "createConversation: fallback client-side id=$id")
        return id
    }

    

    
    private fun openSse(url: String): RawHttp.Response? {
        val resp = RawHttp.request(
            method = "GET",
            url = HttpJson.sseUrl(url),
            headers = mapOf(
                "Accept" to "text/event-stream",
                "Cache-Control" to "no-cache",
            ),
            connectTimeoutMs = HTTP_TIMEOUT_MS,
            
            
            readTimeoutMs = SSE_READ_TIMEOUT_MS,
        )
        if (resp.status != 200) {
            Log.e(TAG, "SSE connect failed: $url → ${resp.status}")
            resp.close()
            return null
        }
        return resp
    }

    private fun startSseLoop(conversationId: String) {
        while (running.get()) {
            try {
                connectSse(conversationId)
            } catch (e: InterruptedException) {
                Log.i(TAG, "SSE interrupted, stopping")
                break
            } catch (e: Exception) {
                
                
                if (!running.get() || Thread.currentThread().isInterrupted) {
                    Log.i(TAG, "SSE closed during teardown")
                    break
                }
                Log.w(TAG, "SSE error: ${e.message}, reconnecting in ${SSE_RECONNECT_DELAY_MS}ms")
                isConnected = false
                onConnectionChanged?.invoke(false)
                try {
                    Thread.sleep(SSE_RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    Log.i(TAG, "SSE reconnect wait interrupted, stopping")
                    break
                }
            }
        }
    }

    private fun connectSse(conversationId: String) {
        
        isInitialSseSnapshot = true
        val conn = openSse("$baseUrl/api/conversations/$conversationId/stream")
        if (conn == null) {
            
            
            if (isConnected) {
                isConnected = false
                onConnectionChanged?.invoke(false)
            }
            Thread.sleep(SSE_RECONNECT_DELAY_MS)
            return
        }
        sseThread = Thread.currentThread()
        
        activeSse.getAndSet(conn)?.let { try { it.close() } catch (_: Exception) {} }

        try {
            isConnected = true
            onConnectionChanged?.invoke(true)
            Log.i(TAG, "SSE connected for conversation $conversationId")

            val reader = BufferedReader(InputStreamReader(conn.body, Charsets.UTF_8))
            var eventType = ""
            val dataLines = StringBuilder()

            while (running.get()) {
                val line = reader.readLine() ?: break  
                when {
                    line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (dataLines.isNotEmpty()) dataLines.append("\n")
                        dataLines.append(line.removePrefix("data:").trim())
                    }
                    line.isEmpty() && dataLines.isNotEmpty() -> {
                        try {
                            processSseEvent(eventType, dataLines.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE event parse error: ${e.message}")
                        }
                        eventType = ""
                        dataLines.clear()
                    }
                    
                }
            }
        } finally {
            activeSse.compareAndSet(conn, null)
            conn.close()
        }
    }

    
    private fun processSseEvent(event: String, data: String) {
        when (event) {
            "snapshot" -> {
                val conv = JSONObject(data).getJSONObject("conversation")
                val isGenerating = conv.optBoolean("isGenerating", false)
                val assistantText = extractLastAssistantText(conv)
                Log.d(TAG, "SSE snapshot: isGenerating=$isGenerating textLen=${assistantText?.length ?: -1} isInitial=$isInitialSseSnapshot")

                if (isInitialSseSnapshot && !isGenerating) {
                    isInitialSseSnapshot = false
                    
                    
                    
                    if (wasGenerating && assistantText != null && assistantText != lastKnownAssistantText) {
                        Log.i(TAG, "SSE reconnect: reply completed while disconnected, delivering (len=${assistantText.length})")
                        handleAssistantUpdate(assistantText, false)
                        return
                    }
                    
                    
                    assistantText?.let { lastKnownAssistantText = it }
                    Log.i(TAG, "SSE initial snapshot primed: textLen=${assistantText?.length ?: 0}")
                    return
                }
                isInitialSseSnapshot = false
                handleAssistantUpdate(assistantText, isGenerating)
            }
            "node_update" -> {
                val json = JSONObject(data)
                val isGenerating = json.optBoolean("isGenerating", false)
                val node = json.getJSONObject("node")
                val nodeText = extractTextFromNode(node)
                val role = extractRoleFromNode(node)
                Log.d(TAG, "SSE node_update: role=$role isGenerating=$isGenerating textLen=${nodeText?.length ?: -1}")
                if (role == "ASSISTANT") {
                    handleAssistantUpdate(nodeText, isGenerating)
                } else if (!isGenerating && wasGenerating) {
                    
                    Log.i(TAG, "generation ended on non-assistant node, delivering last known text")
                    deliverReply(lastKnownAssistantText)
                    wasGenerating = false
                }
            }
            "error" -> {
                
                
                Log.w(TAG, "SSE error event: ${data.take(200)}")
                if (prefillStreamActive && prefillBranchOpen) {
                    onReplyComplete?.invoke("")
                    prefillBranchOpen = false
                    prefillLastContent = ""
                    prefillBranchesDone++
                    checkPrefillDone()
                }
            }
        }
    }

    private fun handleAssistantUpdate(text: String?, isGenerating: Boolean) {
        if (text == null) {
            if (isGenerating) wasGenerating = true
            return
        }

        Log.d(TAG, "handleAssistantUpdate: isGen=$isGenerating wasGen=$wasGenerating textLen=${text.length} knownLen=${lastKnownAssistantText.length}")

        if (prefillStreamActive) {
            handlePrefillUpdate(text, isGenerating)
            return
        }

        if (awaitingRelayQueryResponse) {
            lastKnownAssistantText = text
            wasGenerating = isGenerating
            Log.d(TAG, "relay-query: buffered SSE update until response mode resolves")
            return
        }

        if (isGenerating) {
            
            if (!wasGenerating) {
                
                
                
                deliveredThisRound = false
                roundBaselineText = lastKnownAssistantText
            }
            if (text.length > lastKnownAssistantText.length && text.startsWith(lastKnownAssistantText)) {
                val delta = text.substring(lastKnownAssistantText.length)
                if (delta.isNotEmpty()) onStreamToken?.invoke(delta)
            } else if (text != lastKnownAssistantText && text.isNotEmpty()) {
                
                onStreamToken?.invoke(text)
            }
            lastKnownAssistantText = text
            wasGenerating = true

        } else if (wasGenerating) {
            
            if (text.isNotEmpty() && text != lastKnownAssistantText &&
                text.length > lastKnownAssistantText.length && text.startsWith(lastKnownAssistantText)
            ) {
                val delta = text.substring(lastKnownAssistantText.length)
                if (delta.isNotEmpty()) onStreamToken?.invoke(delta)
            }
            Log.i(TAG, "reply complete (streamed): len=${text.length}: ${text.take(80)}")
            deliverReply(text)
            lastKnownAssistantText = text
            wasGenerating = false

        } else if (text != lastKnownAssistantText && text.isNotEmpty() && queryPending) {
            
            
            
            
            
            Log.i(TAG, "reply complete (instant): len=${text.length}: ${text.take(80)}")
            deliveredThisRound = false
            deliverReply(text)
            lastKnownAssistantText = text

        } else {
            
            
            if (text != lastKnownAssistantText && text.isNotEmpty() && !queryPending) {
                Log.d(TAG, "text changed without pending query (branch switch/delete?), updating baseline only")
            }
            lastKnownAssistantText = text
        }
    }

    
    private fun deliverReply(text: String) {
        if (text.isEmpty()) return
        if (text == roundBaselineText) {
            
            
            Log.i(TAG, "deliverReply: skip (unchanged since round start — aborted generation?)")
            return
        }
        if (text == lastDeliveredAssistantText) {
            Log.i(TAG, "deliverReply: skip (same as last delivered)")
            return
        }
        if (deliveredThisRound) {
            Log.w(TAG, "deliverReply: skip (already delivered this round)")
            return
        }
        deliveredThisRound = true
        queryPending = false
        lastDeliveredAssistantText = text
        onReplyComplete?.invoke(text)
    }

    

    
    private fun handlePrefillUpdate(text: String, isGenerating: Boolean) {
        val content = if (text.startsWith(prefillSeed)) {
            text.substring(prefillSeed.length)
        } else {
            
            
            Log.w(TAG, "prefill: seed mismatch (seed ${prefillSeed.length} chars, text ${text.length}), raw fallback")
            text
        }

        
        
        if (prefillBranchOpen && !content.startsWith(prefillLastContent)) {
            Log.i(TAG, "prefill: branch boundary after ${prefillLastContent.length} chars")
            onReplyComplete?.invoke("")  
            prefillBranchOpen = false
            prefillLastContent = ""
            prefillBranchesDone++
            checkPrefillDone()
            if (!prefillStreamActive) return
        }

        
        if (content.length > prefillLastContent.length && content.startsWith(prefillLastContent)) {
            val delta = content.substring(prefillLastContent.length)
            if (delta.isNotEmpty()) {
                onStreamToken?.invoke(delta)
                prefillBranchOpen = true
            }
        }
        prefillLastContent = content
        lastKnownAssistantText = text
        wasGenerating = isGenerating

        
        if (!isGenerating && prefillBranchOpen) {
            Log.i(TAG, "prefill: branch complete, ${content.length} chars")
            onReplyComplete?.invoke(content)
            prefillBranchOpen = false
            prefillBranchesDone++
            checkPrefillDone()
        }
    }

    private fun checkPrefillDone() {
        if (prefillBranchesDone >= prefillExpectedCount) deactivatePrefillStream()
    }

    private fun deactivatePrefillStream() {
        if (prefillBranchOpen) {
            onReplyComplete?.invoke(prefillLastContent)
            prefillBranchOpen = false
        }
        prefillStreamActive = false
        
        lastDeliveredAssistantText = lastKnownAssistantText
        deliveredThisRound = true
        queryPending = false
    }

    

    private fun extractLastAssistantText(conv: JSONObject): String? {
        val messages = conv.optJSONArray("messages") ?: return null
        for (i in messages.length() - 1 downTo 0) {
            val node = messages.getJSONObject(i)
            if (extractRoleFromNode(node) == "ASSISTANT") return extractTextFromNode(node)
        }
        return null
    }

    private fun selectedMessage(node: JSONObject): JSONObject? {
        val msgs = node.optJSONArray("messages") ?: return null
        val selectIndex = node.optInt("selectIndex", 0)
        return msgs.optJSONObject(selectIndex) ?: msgs.optJSONObject(0)
    }

    private fun extractRoleFromNode(node: JSONObject): String? =
        selectedMessage(node)?.optString("role")

    private fun extractTextFromNode(node: JSONObject): String? {
        val parts = selectedMessage(node)?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text", ""))
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }
}
