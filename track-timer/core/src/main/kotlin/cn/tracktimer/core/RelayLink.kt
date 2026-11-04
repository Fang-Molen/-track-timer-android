package cn.tracktimer.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Flat JSON objects with string / integer values only; enough for the relay protocol. */
internal object FlatJson {
    fun encode(vararg kv: Pair<String, Any>): String = kv.joinToString(",", "{", "}") { (k, v) ->
        "\"${esc(k)}\":" + if (v is Number) v.toString() else "\"${esc(v.toString())}\"" }
    private fun esc(s: String) = buildString { for (ch in s) when {
        ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\")
        ch < ' ' -> append(String.format("\\u%04x", ch.code)); else -> append(ch) } }
    fun decode(s: String): Map<String, String> {
        val out = HashMap<String, String>(); var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun str(): String { require(s[i] == '"'); i++; val b = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; when (s[i]) {
                'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                else -> b.append(s[i]) } } else b.append(s[i]); i++ }
            i++; return b.toString() }
        ws(); require(s[i] == '{'); i++; ws()
        if (s[i] == '}') return out
        while (true) {
            ws(); val k = str(); ws(); require(s[i] == ':'); i++; ws()
            val v = if (s[i] == '"') str() else { val st = i; while (i < s.length && s[i] !in ",}" && !s[i].isWhitespace()) i++; s.substring(st, i) }
            out[k] = v; ws()
            if (s[i] == ',') { i++; continue }; require(s[i] == '}'); return out
        }
    }
}

/** Minimal RFC 6455 client (text frames only). wss:// uses the platform trust store with hostname verification. */
class WsClient private constructor(private val socket: Socket, private val input: InputStream) : Closeable {
    private val out = socket.getOutputStream()
    private val random = SecureRandom()
    @Volatile var closed = false; private set
    companion object {
        fun connect(url: String, timeoutMs: Int = 5000): WsClient {
            val u = URI(url); val secure = when (u.scheme) { "wss" -> true; "ws" -> false; else -> throw IOException("服务器地址需以 wss:// 开头") }
            val host = u.host ?: throw IOException("服务器地址无效"); val port = if (u.port > 0) u.port else if (secure) 443 else 80
            val raw = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs); tcpNoDelay = true; soTimeout = timeoutMs }
            val socket: Socket = if (secure) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).let { s ->
                s as SSLSocket; s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }; s.startHandshake(); s } else raw
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            socket.getOutputStream().apply { write(("GET $path HTTP/1.1\r\nHost: $host${if (u.port > 0) ":$port" else ""}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII)); flush() }
            val input = BufferedInputStream(socket.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) throw EOFException("服务器断开"); head.append(b.toChar()); if (head.length > 4096) throw IOException("握手过长") }
            val lines = head.split("\r\n")
            if (!lines[0].contains(" 101 ")) { socket.close(); throw IOException("服务器拒绝连接：${lines[0]}") }
            val expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            if (lines.none { it.lowercase().startsWith("sec-websocket-accept:") && it.substringAfter(':').trim() == expect }) { socket.close(); throw IOException("握手校验失败") }
            socket.soTimeout = 0
            return WsClient(socket, input)
        }
    }
    @Synchronized private fun frame(op: Int, payload: ByteArray) {
        if (closed) throw IOException("连接已关闭")
        require(payload.size < 65536)
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val header = if (payload.size < 126) byteArrayOf((0x80 or op).toByte(), (0x80 or payload.size).toByte())
            else byteArrayOf((0x80 or op).toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
        val body = ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        out.write(header + mask + body); out.flush()
    }
    fun sendText(text: String) = frame(1, text.toByteArray(Charsets.UTF_8))
    private fun readFully(n: Int): ByteArray { val b = ByteArray(n); var o = 0
        while (o < n) { val r = input.read(b, o, n - o); if (r < 0) throw EOFException("服务器断开"); o += r }; return b }
    /** Blocks for the next text message; answers pings. Returns null when the server closed the connection. */
    fun readText(): String? {
        while (true) {
            val h = readFully(2); val op = h[0].toInt() and 0x0f; var len = (h[1].toInt() and 0x7f).toLong()
            if (len == 126L) { val e = readFully(2); len = ((e[0].toInt() and 0xff) shl 8 or (e[1].toInt() and 0xff)).toLong() }
            else if (len == 127L) throw IOException("消息过长")
            if (len > 65535) throw IOException("消息过长")
            val p = readFully(len.toInt())
            when (op) { 1 -> return String(p, Charsets.UTF_8); 8 -> { close(); return null }; 9 -> frame(10, p); else -> {} }
        }
    }
    override fun close() { if (!closed) { runCatching { frame(8, byteArrayOf(3, -24)) }; closed = true }; runCatching { socket.close() } }
}

/**
 * Internet relay link. The start phone [host]s a room and answers forwarded lines with [SyncResponder];
 * the finish phone [join]s with the 6-digit code and uses the link as a [LineTransport].
 * [receiveClock] stamps each forwarded request as soon as it is read, before any parsing.
 */
class RelayLink private constructor(private val ws: WsClient, val code: String, private val receiveClock: () -> Long) : LineTransport, Closeable {
    private val replies = LinkedBlockingQueue<Map<String, String>>()
    private val nextId = AtomicInteger(1)
    @Volatile var peerJoined = false; private set
    @Volatile var failure: String? = null; private set
    private var handler: ((String, Long) -> String?)? = null

    companion object {
        private fun expect(ws: WsClient, type: String): Map<String, String> {
            val m = FlatJson.decode(ws.readText() ?: throw IOException("服务器断开"))
            if (m["t"] == "error") throw RelayException(m["code"] ?: "ERROR")
            if (m["t"] != type) throw IOException("服务器回复异常")
            return m
        }
        /** Start phone: opens a room; [respond] answers each forwarded request line. */
        fun host(url: String, clock: () -> Long, respond: (String, Long) -> String?): RelayLink {
            val ws = WsClient.connect(url)
            try { ws.sendText(FlatJson.encode("t" to "create")); val m = expect(ws, "created")
                return RelayLink(ws, m.getValue("code"), clock).also { it.handler = respond; it.startReader() }
            } catch (e: Exception) { ws.close(); throw e }
        }
        fun join(url: String, code: String, clock: () -> Long): RelayLink {
            require(code.matches(Regex("[0-9]{6}"))) { "配对码应为6位数字" }
            val ws = WsClient.connect(url)
            try { ws.sendText(FlatJson.encode("t" to "join", "code" to code)); expect(ws, "joined")
                return RelayLink(ws, code, clock).also { it.peerJoined = true; it.startReader() }
            } catch (e: Exception) { ws.close(); throw e }
        }
    }
    val isOpen: Boolean get() = !ws.closed && failure == null
    private fun startReader() = Thread({
        try {
            while (true) {
                val text = ws.readText() ?: break
                val received = receiveClock()
                val m = FlatJson.decode(text)
                when (m["t"]) {
                    "fwd" -> { val h = handler
                        if (h != null) { val reply = h(m["d"] ?: "", received)
                            if (reply != null) ws.sendText(FlatJson.encode("t" to "fwd", "id" to (m["id"]?.toLongOrNull() ?: 0L), "d" to reply)) }
                        else replies.put(m) }
                    "peer" -> peerJoined = m["state"] == "joined"
                    "error" -> replies.put(m)
                    "closed" -> { failure = "对方已离开，本场房间已关闭"; break }
                }
            }
        } catch (e: IOException) { if (!ws.closed) failure = e.message ?: "连接中断" }
        finally { if (failure == null && !ws.closed) failure = "服务器断开"; replies.put(mapOf("t" to "eof")); ws.close() }
    }, "relay-reader").apply { isDaemon = true; start() }

    override fun exchange(line: String, timeoutMs: Int): String {
        val id = nextId.getAndIncrement()
        failure?.let { throw IOException(it) }
        ws.sendText(FlatJson.encode("t" to "fwd", "id" to id, "d" to line))
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) throw java.net.SocketTimeoutException("服务器转发超时")
            val m = replies.poll(left, TimeUnit.NANOSECONDS) ?: continue
            if (m["t"] == "eof") { replies.put(m); throw IOException(failure ?: "连接中断") }
            if (m["t"] == "error" && m["id"] == null) throw RelayException(m["code"] ?: "ERROR")
            if (m["id"] != id.toString()) continue      // late reply of an earlier, timed-out request
            if (m["t"] == "error") throw IOException(if (m["code"] == "NO_PEER") "对方未连接" else "服务器错误：${m["code"]}")
            return m["d"] ?: throw IOException("空回复")
        }
    }
    override fun close() = ws.close()
}
class RelayException(val code: String) : IOException(when (code) {
    "BAD_CODE" -> "配对码错误或已被使用"; "EXPIRED" -> "配对码已过期（10分钟），请在起点重新开启"
    "LOCKED" -> "输错次数过多，请10分钟后再试"; "FULL" -> "服务器繁忙"; else -> "服务器错误：$code" })
