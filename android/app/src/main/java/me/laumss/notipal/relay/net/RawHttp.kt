package me.laumss.notipal.relay.net

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI


object RawHttp {
    private const val TAG = "RawHttp"
    private const val CRLF = "\r\n"

    
    class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: InputStream,
        private val socket: Socket,
    ) : AutoCloseable {
        val isSuccess: Boolean get() = status in 200..299

        override fun close() {
            try { body.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private data class Target(val host: String, val port: Int, val path: String)

    
    private fun parse(url: String): Target {
        val uri = URI(url)
        require(uri.scheme == null || uri.scheme.equals("http", ignoreCase = true)) {
            "RawHttp only speaks plain http, got: ${uri.scheme}"
        }
        val host = uri.host ?: throw IOException("No host in URL: $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        return Target(host, port, path)
    }

    
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 30_000,
    ): Response {
        val target = parse(url)
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            writeRequest(socket.getOutputStream(), method, target, headers, body)

            val input = socket.getInputStream().buffered()
            val (status, respHeaders) = readHead(input)
            val stream = wrapBody(input, respHeaders, method, status)
            return Response(status, respHeaders, stream, socket)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun writeRequest(
        out: OutputStream,
        method: String,
        target: Target,
        headers: Map<String, String>,
        body: ByteArray?,
    ) {
        val head = StringBuilder()
        head.append(method).append(' ').append(target.path).append(" HTTP/1.1").append(CRLF)
        
        
        val hostHeader =
            if (target.port == 80) target.host else "${target.host}:${target.port}"
        head.append("Host: ").append(hostHeader).append(CRLF)
        
        
        head.append("Connection: close").append(CRLF)
        
        head.append("Accept-Encoding: identity").append(CRLF)
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append(CRLF) }
        if (body != null) {
            head.append("Content-Length: ").append(body.size).append(CRLF)
        }
        head.append(CRLF)

        out.write(head.toString().toByteArray(Charsets.UTF_8))
        body?.let { out.write(it) }
        out.flush()
    }

    
    private fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
        val statusLine = readLine(input) ?: throw IOException("Empty response")
        
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) throw IOException("Malformed status line: $statusLine")
        val status = parts[1].toIntOrNull()
            ?: throw IOException("Malformed status code: $statusLine")

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            
            val name = line.substring(0, idx).trim().lowercase()
            headers[name] = line.substring(idx + 1).trim()
        }
        return status to headers
    }

    
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, end, Charsets.UTF_8)
            }
            buf.write(b)
        }
    }

    private fun wrapBody(
        input: InputStream,
        headers: Map<String, String>,
        method: String,
        status: Int,
    ): InputStream {
        
        if (method == "HEAD" || status == 204 || status == 304 || status in 100..199) {
            return EmptyStream
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return ChunkedInputStream(input)
        }
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        
        
        return if (length != null) FixedLengthInputStream(input, length) else input
    }

    private object EmptyStream : InputStream() {
        override fun read(): Int = -1
    }

    
    private class FixedLengthInputStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
    }

    
    private class ChunkedInputStream(private val source: InputStream) : InputStream() {
        private var chunkRemaining = 0L
        private var finished = false
        
        private var sawFirstChunk = false

        
        private fun ensureChunk(): Boolean {
            if (finished) return false
            if (chunkRemaining > 0) return true

            
            
            if (sawFirstChunk) readLine(source)

            val sizeLine = readLine(source) ?: run { finished = true; return false }
            
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
            if (size == null) {
                Log.w(TAG, "Malformed chunk size: $sizeLine")
                finished = true
                return false
            }
            sawFirstChunk = true
            if (size == 0L) {
                
                while (true) {
                    val line = readLine(source) ?: break
                    if (line.isEmpty()) break
                }
                finished = true
                return false
            }
            chunkRemaining = size
            return true
        }

        override fun read(): Int {
            if (!ensureChunk()) return -1
            val b = source.read()
            if (b >= 0) chunkRemaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!ensureChunk()) return -1
            val toRead = minOf(len.toLong(), chunkRemaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) chunkRemaining -= n
            return n
        }

        override fun available(): Int =
            minOf(source.available().toLong(), chunkRemaining).toInt()
    }
}
