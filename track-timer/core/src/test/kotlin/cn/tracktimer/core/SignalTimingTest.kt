package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test

class SignalTimingTest {
    private fun cal(anchor:Long=47_000_000_000L,offset:Long=2_000_000_000L)=Calibration("finish","host",anchor,offset,1_000_000L,offset-1_000_000,offset+1_000_000,48,8,2_000_000)
    @Test fun convertsHostMinusFinishOffsetWithCorrectSign() {
        val r=SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(),cal(61_000_000_000L),"host","finish")
        assertEquals(48_000_000_000L,r.startLocalNs);assertEquals(12_000_000_000L,r.elapsedNs);assertTrue(r.syncCheckPassed)
    }
    @Test fun missingPostSyncAndLargeDriftRemainReviewOnly() {
        assertFalse(SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(),null,"host","finish").syncCheckPassed)
        assertFalse(SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(),cal(61_000_000_000L,2_020_000_000L),"host","finish").syncCheckPassed)
    }
    @Test fun wrongSessionStaleCalibrationAndPreStartFrameCannotProduceATime() {
        assertThrows(IllegalArgumentException::class.java){SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(),null,"other","finish")}
        assertThrows(IllegalArgumentException::class.java){SignalTiming.calculate(100_000_000_000L,90_000_000_000L,cal(),null,"host","finish")}
        assertThrows(IllegalArgumentException::class.java){SignalTiming.calculate(47_000_000_000L,50_000_000_000L,cal(),null,"host","finish")}
    }
}

class SignalTimingRelayTest {
    private fun cal(unc:Long,anchor:Long=47_000_000_000L,offset:Long=2_000_000_000L)=Calibration("finish","host",anchor,offset,unc,offset-unc,offset+unc,48,8,2*unc)
    @Test fun relayWideBoundIsTimedButReferenceOnly() {
        assertThrows(IllegalArgumentException::class.java){SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(20_000_000),null,"host","finish")}
        val r=SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(20_000_000),cal(20_000_000,61_000_000_000L),"host","finish",SyncLimits.RELAY_MAX_NS)
        assertEquals(12_000_000_000L,r.elapsedNs);assertFalse(r.syncCheckPassed);assertEquals(20_000_000L,r.startUncertaintyNs)
        assertThrows(IllegalArgumentException::class.java){SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(60_000_000),null,"host","finish",SyncLimits.RELAY_MAX_NS)}
    }
    @Test fun relayTightBoundStillPasses() {
        val r=SignalTiming.calculate(60_000_000_000L,50_000_000_000L,cal(3_000_000),cal(3_000_000,61_000_000_000L),"host","finish",SyncLimits.RELAY_MAX_NS)
        assertTrue(r.syncCheckPassed)
    }
}
