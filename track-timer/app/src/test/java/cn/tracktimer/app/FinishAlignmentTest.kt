package cn.tracktimer.app

import org.junit.Assert.*
import org.junit.Test

class FinishAlignmentTest {
    @Test fun starterRemainsBlockedUntilOperatorConfirmsTheLiveReference() {
        val host=cn.tracktimer.core.RaceCoordinator("host",{100_000_000_000L})
        val gate=FinishAlignment {host.handle("host","finish","trial","READY");true}
        gate.onFramesAvailable()
        assertThrows(IllegalStateException::class.java){host.fire()}
        assertTrue(gate.confirm(.4f));assertTrue(host.current()!!.readyFresh)
        assertEquals(cn.tracktimer.core.RacePhase.FIRED,host.fire().phase)
    }
    @Test fun framesAloneNeverPublishReadyAndConfirmationPublishesExactlyOnce() {
        val published=mutableListOf<Float>();val gate=FinishAlignment {published.add(it);true}
        assertFalse(gate.confirm(.3f));gate.onFramesAvailable();assertTrue(published.isEmpty())
        assertTrue(gate.confirm(.3f));assertEquals(listOf(.3f),published)
        assertFalse(gate.confirm(.8f));assertEquals(1,published.size)
    }
    @Test fun cancellationBlocksLateFramesAndConfirmation() {
        var calls=0;val gate=FinishAlignment{calls++;true}
        gate.cancel();gate.onFramesAvailable();assertFalse(gate.confirm(.5f));assertEquals(0,calls)
    }
    @Test fun invalidPositionAndFailedPersistenceNeverArmTheSession() {
        var durable=false;val gate=FinishAlignment{durable};gate.onFramesAvailable()
        for(f in listOf(Float.NaN,-.1f,1.1f))assertFalse(gate.confirm(f))
        assertFalse(gate.confirm(.5f));assertTrue(gate.canConfirm)
        durable=true;assertTrue(gate.confirm(.5f));assertFalse(gate.canConfirm)
    }
}
