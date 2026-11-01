package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test

class FrameAnalysisTest {
    @Test fun measuresActualSixtyFps() {
        val r = FrameAnalysis.analyze(List(61) { it * 16_666_667L }, 60)
        assertEquals(60.0, r.measuredFps!!, 0.001)
        assertEquals(0, r.estimatedMissingFrames)
        assertEquals(16_666_667L, r.maxGapNs)
    }
    @Test fun detectsThirtyFpsDespiteSixtyFpsRequest() {
        val r = FrameAnalysis.analyze(List(31) { it * 33_333_333L }, 60)
        assertEquals(30.0, r.measuredFps!!, 0.001)
        assertEquals(30, r.estimatedMissingFrames)
    }
    @Test fun flagsOneLargeGapWithoutHidingItInAverage() {
        val r = FrameAnalysis.analyze(listOf(0L, 16_666_667, 33_333_334, 83_333_335), 60)
        assertEquals(2, r.estimatedMissingFrames)
        assertEquals(50_000_001L, r.maxGapNs)
    }
    @Test fun missingOrReversedTimestampsAreNotHealthyVideo() {
        assertNull(FrameAnalysis.analyze(emptyList(), 60).measuredFps)
        assertThrows(IllegalArgumentException::class.java) { FrameAnalysis.analyze(listOf(10, 9), 60) }
    }
}
