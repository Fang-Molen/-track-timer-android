package cn.tracktimer.app
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class TimeNavigationTest {
    @Test fun timeSearchUsesNonuniformRawCaptureTimesAndClampsEdges(){
        val times=listOf(10_000_000_000L,10_008_000_000L,10_024_000_000L,12_000_000_000L)
        val j=JSONObject().put("codecEosReached",true).put("failedCaptures",0).put("codecPtsNs",JSONArray(times))
            .put("encodedPtsNs",JSONArray(listOf(0,1000,2000,3000))).put("codecFrameCount",4).put("encodedFrameCount",4)
            .put("clockCalibration",JSONObject().put("sampledClockAlignmentObserved",true))
        val t=FrameTimeline.from(j,4)
        assertEquals(2,t.indexAtOrAfter(10_010_000_000L));assertEquals(1,t.indexAtOrBefore(10_010_000_000L))
        assertEquals(2,t.indexAtOrAfter(10_024_000_000L));assertEquals(2,t.indexAtOrBefore(10_024_000_000L))
        assertEquals(0,t.indexAtOrAfter(0));assertEquals(0,t.indexAtOrBefore(0));assertEquals(3,t.indexAtOrAfter(Long.MAX_VALUE));assertEquals(3,t.indexAtOrBefore(Long.MAX_VALUE))
    }
}
