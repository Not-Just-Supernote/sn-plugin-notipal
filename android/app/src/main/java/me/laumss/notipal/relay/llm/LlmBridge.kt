package me.laumss.notipal.relay.llm


interface LlmBridge {
    val isConnected: Boolean

    
    fun start(conversationId: String? = null)

    fun stop()

    fun sendUserMessage(text: String)

    
    var onStreamToken: ((delta: String) -> Unit)?

    
    var onReplyComplete: ((text: String) -> Unit)?

    var onConnectionChanged: ((connected: Boolean) -> Unit)?
}
