package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test

class StartAnchorTest {
    // 120 fps: 8_333_333 ns apart, first frame at 10 s.
    private val ts=LongArray(1200){10_000_000_000L+it*8_333_333L}
    private fun at(ns:Long,src:StartSource=StartSource.ELECTRONIC,unc:Long=1_000_000)=StartAnchor.locate(src,ns,ts.size,unc){ts[it]}

    @Test fun startBetweenFramesPointsToNextFrameWithFraction() {
        val a=at(ts[240]+2_000_000)
        assertEquals(241,a.frameIndex);assertTrue(a.inRange)
        assertEquals(2_000_000.0/8_333_333,a.fraction,1e-9)
    }
    @Test fun startExactlyOnFrame() { val a=at(ts[37]);assertEquals(37,a.frameIndex);assertEquals(1.0,a.fraction,0.0) }
    @Test fun startOutsideVideoIsFlagged() {
        assertFalse(at(ts[0]-1).inRange);assertEquals(0,at(ts[0]-1).frameIndex)
        assertFalse(at(ts.last()+1).inRange)
    }
    @Test fun overlayShowsSignedElapsedAndQuality() {
        val a=at(ts[120])
        assertEquals("距电子发令 +1.000 秒 ±1.0ms",a.overlay(ts[120]+1_000_000_000L))
        assertEquals("距电子发令 −0.050 秒 ±1.0ms",a.overlay(ts[120]-50_000_000L))
        assertTrue(at(ts[120],unc=12_000_000).overlay(ts[121]).endsWith("仅供参考"))
        assertEquals("距手动开始 +0.000 秒 · 仅供参考",at(ts[120],StartSource.MANUAL).overlay(ts[120]))
    }
    @Test fun timelineMarkerPosition() {
        assertEquals(0.0,at(ts[0]).timelinePosition(ts.size),1e-12)
        assertEquals(240.0/1199,at(ts[240]).timelinePosition(ts.size),1e-12)
        assertEquals((240+0.5)/1199,at(ts[240]+8_333_333L/2).timelinePosition(ts.size),1e-6)
    }
}
