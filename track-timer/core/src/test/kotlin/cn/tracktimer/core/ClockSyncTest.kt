package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test

class ClockSyncTest {
    // A sends at 1 s. B is 123 ms ahead; outgoing=2 ms, incoming=4 ms, processing=1 ms.
    private val example = Exchange(1_000_000_000, 1_125_000_000, 1_126_000_000, 1_007_000_000)

    @Test fun boundsContainTrueOffsetEvenWithAsymmetricNetwork() {
        assertEquals(119_000_000L, example.lowerOffsetNs)
        assertEquals(125_000_000L, example.upperOffsetNs)
        assertEquals(6_000_000L, example.networkRoundTripNs)
    }
    @Test fun estimatesOffsetAndKeepsNetworkUncertainty() {
        val result = ClockSync.estimate(List(12) { example }, "local", "remote")
        assertEquals(122_000_000L, result.offsetNs)
        assertEquals(3_000_000L, result.uncertaintyNs)
        assertEquals(12, result.sampleCount)
        assertTrue(123_000_000L in result.lowerOffsetNs..result.upperOffsetNs)
    }
    @Test fun slowOutlierDoesNotDominateEstimate() {
        val samples = List(10) { example } + Exchange(2_000_000_000, 2_223_000_000, 2_224_000_000, 2_201_000_000)
        assertEquals(122_000_000L, ClockSync.estimate(samples, "a", "b").offsetNs)
    }
    @Test fun rejectsTooFewSamples() {
        assertThrows(IllegalArgumentException::class.java) { ClockSync.estimate(listOf(example), "a", "b") }
    }
    @Test fun rejectsImpossibleTimestampOrdering() {
        assertThrows(IllegalArgumentException::class.java) { Exchange(100, 200, 250, 101) }
        assertThrows(IllegalArgumentException::class.java) { Exchange(100, 200, 190, 110) }
        assertThrows(IllegalArgumentException::class.java) { Exchange(-1, 2, 3, 4) }
    }
    @Test fun rejectsInconsistentOffsetIntervals() {
        val other = Exchange(1_000_000_000, 1_525_000_000, 1_526_000_000, 1_007_000_000)
        assertThrows(IllegalArgumentException::class.java) { ClockSync.estimate(List(4) { example } + List(4) { other }, "a", "b") }
    }
    @Test fun slowerContradictorySampleInvalidatesFastSampleEstimate() {
        val samples = List(8) { Exchange(1000, 1001, 1001, 1002) } + Exchange(2000, 2100, 2100, 2010)
        assertThrows(IllegalArgumentException::class.java) { ClockSync.estimate(samples, "a", "b") }
    }
    @Test fun driftRequiresSameClockSessionsAndMeasuresRateChange() {
        val a = ClockSync.estimate(List(8) { example }, "a", "b")
        val b = a.copy(anchorLocalNs = a.anchorLocalNs + 60_000_000_000, offsetNs = a.offsetNs + 1_200_000)
        assertEquals(20.0, ClockSync.driftPpm(a, b), 0.001)
        assertThrows(IllegalArgumentException::class.java) { ClockSync.driftPpm(a, b.copy(remoteSession = "restarted")) }
    }
}
