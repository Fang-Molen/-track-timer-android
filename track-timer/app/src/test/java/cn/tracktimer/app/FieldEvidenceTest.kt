package cn.tracktimer.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class FieldEvidenceTest {
    private fun report()=JSONObject().put("videoFile","video_test.mp4").put("codecPtsNs",JSONArray(listOf(40_000_000_000L,40_004_180_000L,40_008_360_000L)))
        .put("encodedPtsNs",JSONArray(listOf(0,4_180_000,8_350_000))).put("encodedFrameCount",3).put("codecFrameCount",3)
        .put("codecEosReached",true).put("failedCaptures",0).put("clockCalibration",JSONObject().put("sampledClockAlignmentObserved",true))
    @Test fun frameIndexUsesOriginalClockDespiteMuxRounding() {
        val t=FrameTimeline.from(report(),3)
        assertEquals(40_008_360_000L,t.timestamp(2));assertEquals(3,t.size)
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(report(),2)}
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(report().put("codecEosReached",false),3)}
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(report().put("encodedPtsNs",JSONArray(listOf(0,0,8))),3)}
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(report().put("clockCalibration",JSONObject()),3)}
    }
    @Test fun recordingBudgetCoversThreeMinutesAt240WithoutSilentClipping() {
        val budget=CaptureBudget(180_000,240)
        val s=EncodedSamples(budget.sampleLimit)
        repeat(43_200){s.add(1_000_000L+it*4167L,100_000_000_000L)}
        assertEquals(43_200,s.ptsNs.size)
        assertTrue(budget.requiredBytes>=720_000_000L+100*1024*1024L)
    }
    @Test fun savedRaceRetainsHistoricalSessionsAndMultipleTiedResults() {
        val store=RaceStore(ReportStore(RuntimeEnvironment.getApplication()))
        val id=java.util.UUID.randomUUID().toString()
        val j=JSONObject().put("raceId",id).put("clientSession","previous-process").put("hostSession","previous-host")
            .put("results",JSONArray().put(JSONObject().put("name","A").put("frameIndex",2)).put(JSONObject().put("name","B").put("frameIndex",2)))
        store.save(j)
        val again=RaceStore(ReportStore(RuntimeEnvironment.getApplication())).load(id)
        assertEquals("previous-process",again.getString("clientSession"));assertEquals(2,again.getJSONArray("results").length())
        assertThrows(IllegalArgumentException::class.java){store.load("../escape")}
    }
}
