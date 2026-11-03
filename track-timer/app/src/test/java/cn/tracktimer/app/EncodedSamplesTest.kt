package cn.tracktimer.app

import org.junit.Assert.*
import org.junit.Test

class EncodedSamplesTest {
    @Test fun completionRequiresActualVideoSamplesAndEndOfStream() {
        val s=EncodedSamples()
        assertThrows(IllegalStateException::class.java){s.checkComplete(true)}
        s.add(100,200_000);s.add(200,300_000)
        assertThrows(IllegalStateException::class.java){s.checkComplete(false)}
        s.checkComplete(true)
    }
    @Test fun keepsRawClockButReturnsRelativeMuxTime() {
        val s=EncodedSamples()
        assertEquals(0L,s.add(400_000_000L,400_010_000_000L))
        assertEquals(8333L,s.add(400_008_333L,400_020_000_000L))
        assertEquals(listOf(400_000_000_000L,400_008_333_000L),s.ptsNs)
        assertEquals(listOf(400_010_000_000L,400_020_000_000L),s.observedNs)
    }
    @Test fun boundedCollectionRejectsOverflowWithoutAddingAnotherSample() {
        val s=EncodedSamples(limit=2);s.add(10,20_000);s.add(20,30_000)
        assertThrows(IllegalStateException::class.java){s.add(30,40_000)}
        assertEquals(2,s.ptsNs.size)
    }
    @Test fun rejectsDuplicatesBackwardsZeroAndOverflowingTime() {
        for (v in listOf(0L,-1L,Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java){EncodedSamples().add(v,100)}
        val s=EncodedSamples();s.add(100,200_000)
        for (v in listOf(100L,99L)) assertThrows(IllegalArgumentException::class.java){s.add(v,300_000)}
        assertEquals(1,s.ptsNs.size)
    }
}
