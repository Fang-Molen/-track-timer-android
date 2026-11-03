package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test

class RaceProtocolTest {
    @Test fun readyLeaseAndHoldPreventAnUnpreparedStart() {
        var now=10_000_000_000L; val c=RaceCoordinator("host",{now})
        assertThrows(IllegalStateException::class.java){c.fire()}
        c.handle("host","finish","run","READY");assertTrue(c.current()!!.readyFresh)
        now+=2_500_000_001L;assertThrows(IllegalStateException::class.java){c.fire()}
        c.handle("host","finish","run","POLL");assertTrue(c.current()!!.readyFresh)
        c.handle("host","finish","run","HOLD");c.handle("host","finish","run","POLL")
        assertFalse(c.current()!!.readyFresh);assertThrows(IllegalStateException::class.java){c.fire()}
        c.handle("host","finish","run","READY");assertEquals(now,c.fire().startNs)
    }
    @Test fun retriesPreserveOneStartAndDoneRequiresAcknowledgment() {
        var now=10_000_000_000L;val c=RaceCoordinator("host",{now})
        c.handle("host","finish","run","READY");val first=c.fire();now+=1_000_000_000L
        assertThrows(IllegalStateException::class.java){c.fire()}
        assertEquals(first.startNs,c.handle("host","finish","run","READY").startNs)
        assertThrows(IllegalArgumentException::class.java){c.handle("host","finish","run","DONE")}
        repeat(2){assertTrue(c.handle("host","finish","run","ACK").acknowledged)}
        assertEquals(RacePhase.DONE,c.handle("host","finish","run","DONE").phase)
        c.handle("host","finish","next","READY")
        assertEquals(first.startNs,c.handle("host","finish","run","POLL").startNs)
    }
    @Test fun sessionsAndRaceOwnershipCannotBeSubstituted() {
        val c=RaceCoordinator("host",{10_000_000_000L})
        c.handle("host","finish","run","READY")
        for(args in listOf(listOf("wrong","finish","run","POLL"),listOf("host","other","run","POLL"),listOf("host","finish","next","READY")))
            assertThrows(IllegalArgumentException::class.java){c.handle(args[0],args[1],args[2],args[3])}
        c.cancelActive();assertEquals(RacePhase.ABORTED,c.current()!!.phase)
        assertThrows(IllegalStateException::class.java){c.fire()}
        c.handle("host","finish","next","READY");assertTrue(c.current()!!.readyFresh)
    }
    @Test fun realTcpPairingAndCalibrationShareTheSameHostSession() {
        val clock={System.nanoTime()};val c=RaceCoordinator("host",clock)
        SyncServer("123456","host",clock,c).use { server ->
            val port=server.start(0)
            val measurement=SyncClient.measure("127.0.0.1",port,"123456","finish",clock,8)
            assertEquals("host",measurement.calibration.remoteSession)
            val ready=RaceClient.request("127.0.0.1",port,"123456","host","finish","run","READY")
            assertEquals(RacePhase.READY,ready.phase)
            val fired=c.fire()
            val received=RaceClient.request("127.0.0.1",port,"123456","host","finish","run","POLL")
            assertEquals(fired.startNs,received.startNs)
            assertTrue(RaceClient.request("127.0.0.1",port,"123456","host","finish","run","ACK").acknowledged)
            assertThrows(RaceCommandException::class.java){RaceClient.request("127.0.0.1",port,"000000","host","finish","run","POLL")}
            assertThrows(RaceCommandException::class.java){RaceClient.request("127.0.0.1",port,"123456","old","finish","run","POLL")}
        }
    }
}
