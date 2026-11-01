package cn.tracktimer.core

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private fun readBoundedLine(socket: Socket, timeoutMs: Int): String {
    val input = socket.getInputStream()
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    val bytes = ArrayList<Byte>(128)
    while (bytes.size < 512) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Request cancelled")
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw java.net.SocketTimeoutException("Whole request deadline exceeded")
        socket.soTimeout = ((remaining + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
        val b = input.read()
        if (b < 0) throw EOFException("Peer disconnected")
        if (b == 10) return bytes.toByteArray().toString(Charsets.US_ASCII)
        if (b !in 32..126) throw IOException("Invalid protocol character")
        bytes.add(b.toByte())
    }
    throw IOException("Message too long")
}

class SyncServer(private val token: String, private val sessionId: String,
    private val clock: () -> Long) : Closeable {
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(4),
        { r -> Thread(r, "sync-peer").apply { isDaemon = true } })
    @Volatile private var running = false
    private var listener: ServerSocket? = null

    init { require(token.matches(Regex("[0-9]{6}"))); require(sessionId.matches(Regex("[A-Za-z0-9-]{1,64}"))) }

    @Synchronized fun start(port: Int): Int {
        check(listener == null) { "Host already started" }
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        listener = server; running = true
        Thread({
            while (running) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                if (!running) { socket.close(); sockets.remove(socket); break }
                try { workers.execute { serve(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); socket.close() }
            }
        }, "sync-listener").apply { isDaemon = true; start() }
        return server.localPort
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                it.soTimeout = 1500; it.tcpNoDelay = true
                val output = it.getOutputStream()
                repeat(256) {
                    val line = readBoundedLine(socket, 1500)
                    val received = clock()
                    val parts = line.split('|')
                    if (parts.size != 3 || parts[0] != "PROBE" || parts[1] != token ||
                        !parts[2].matches(Regex("[0-9]{1,4}"))) {
                        output.write("ERROR|Pairing or protocol error\n".toByteArray()); output.flush(); return
                    }
                    val sent = clock()
                    output.write("STAMP|${parts[2]}|$sessionId|$received|$sent\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            }
        } catch (_: IOException) { /* Disconnection or invalid input closes just this peer. */ }
        finally { sockets.remove(socket) }
    }

    @Synchronized override fun close() {
        running = false
        runCatching { listener?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear(); workers.shutdownNow()
    }
}

data class SyncMeasurement(val calibration: Calibration, val samples: List<Exchange>)

object SyncClient {
    fun measure(host: String, port: Int, token: String, localSession: String,
        clock: () -> Long, count: Int = 48): SyncMeasurement {
        require(count in 8..128)
        require(token.matches(Regex("[0-9]{6}"))) { "Pairing code must be six digits" }
        val samples = ArrayList<Exchange>()
        var peer = ""
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000; socket.tcpNoDelay = true
            val output = socket.getOutputStream()
            repeat(count) { index ->
                if (Thread.currentThread().isInterrupted) throw IOException("Calibration cancelled")
                val request = "PROBE|$token|$index\n".toByteArray(Charsets.US_ASCII)
                val t1 = clock()
                output.write(request); output.flush()
                val line = readBoundedLine(socket, 3000)
                val t4 = clock()
                val parts = line.split('|')
                if (parts.size != 5 || parts[0] != "STAMP" || parts[1] != index.toString() ||
                    !parts[2].matches(Regex("[A-Za-z0-9-]{1,64}"))) throw IOException("Pairing failed or invalid reply")
                if (peer.isNotEmpty() && peer != parts[2]) throw IOException("Host restarted")
                peer = parts[2]
                val t2 = parts[3].toLongOrNull() ?: throw IOException("Invalid receive timestamp")
                val t3 = parts[4].toLongOrNull() ?: throw IOException("Invalid send timestamp")
                samples.add(Exchange(t1, t2, t3, t4))
                if (index + 1 < count) Thread.sleep(10)
            }
        }
        return SyncMeasurement(ClockSync.estimate(samples, localSession, peer), samples)
    }
}
