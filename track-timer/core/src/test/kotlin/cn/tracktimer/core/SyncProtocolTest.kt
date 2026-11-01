package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.Socket
import kotlin.math.abs

class SyncProtocolTest {
    @Test fun realSocketExchangeFindsKnownOffsetAndPeerSession() {
        SyncServer("123456", "host-session", { System.nanoTime() + 123_000_000 }).use { server ->
            val port = server.start(0)
            val result = SyncClient.measure("127.0.0.1", port, "123456", "client-session", System::nanoTime, 16)
            assertEquals("host-session", result.calibration.remoteSession)
            assertEquals(16, result.samples.size)
            assertTrue(abs(result.calibration.offsetNs - 123_000_000) < 10_000_000)
            assertTrue(123_000_000L in result.calibration.lowerOffsetNs..result.calibration.upperOffsetNs)
        }
    }
    @Test fun wrongPairingCodeReturnsNoTimestamp() {
        SyncServer("123456", "host", System::nanoTime).use { server ->
            val port = server.start(0)
            assertThrows(IOException::class.java) {
                SyncClient.measure("127.0.0.1", port, "999999", "client", System::nanoTime, 8)
            }
        }
    }
    @Test fun oversizedLineIsRejectedAndHostRemainsUsable() {
        SyncServer("123456", "host", System::nanoTime).use { server ->
            val port = server.start(0)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(("X".repeat(1200) + "\n").toByteArray())
                try { assertEquals(-1, socket.getInputStream().read()) } catch (_: java.net.SocketException) { }
            }
            val result = SyncClient.measure("127.0.0.1", port, "123456", "client", System::nanoTime, 8)
            assertEquals(8, result.samples.size)
        }
    }
    @Test fun idleClientTimesOutWithoutBlockingOtherClient() {
        SyncServer("123456", "host", System::nanoTime).use { server ->
            val port = server.start(0)
            Socket("127.0.0.1", port).use { idle ->
                idle.soTimeout = 4000
                val result = SyncClient.measure("127.0.0.1", port, "123456", "client", System::nanoTime, 8)
                assertEquals(8, result.samples.size)
                assertEquals(-1, idle.getInputStream().read())
            }
        }
    }
    @Test fun closingHostClosesActiveConnections() {
        val server = SyncServer("123456", "host", System::nanoTime)
        val port = server.start(0)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("PROBE|123456|1\n".toByteArray())
            val reader = socket.getInputStream().bufferedReader()
            assertTrue(reader.readLine().startsWith("STAMP|1|host|"))
            server.close()
            assertNull(reader.readLine())
        }
    }
    @Test fun trickleClientsCannotOccupyAllWorkersIndefinitely() {
        SyncServer("123456", "host", System::nanoTime).use { server ->
            val port = server.start(0)
            val sockets = List(2) { Socket("127.0.0.1", port).apply { tcpNoDelay = true } }
            val started = java.util.concurrent.CountDownLatch(2)
            val writers = sockets.map { socket ->
                Thread {
                    try {
                        repeat(30) {
                            socket.getOutputStream().write('P'.code)
                            if (it == 0) started.countDown()
                            Thread.sleep(200)
                        }
                    } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }
            }
            try {
                assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
                val result = SyncClient.measure("127.0.0.1", port, "123456", "client", System::nanoTime, 8)
                assertEquals(8, result.samples.size)
            } finally {
                sockets.forEach { it.close() }; writers.forEach { it.interrupt(); it.join(1000) }
            }
        }
    }
}
