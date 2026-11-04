package cn.tracktimer.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HighSpeedBatchCalibrationTest {
    // Android's expanded callbacks: one original endpoint plus nominal-FPS derived timestamps.
    // Codec intervals deliberately differ from nominal FPS, as in the handset evidence.
    private fun fixture(fps:Int=120):JSONObject {
        val batch=fps/30
        val period=if(fps==120)8_353_000L else 4_180_000L
        val raw=(0 until batch*300).map{400_000_000_000L+it*period}
        val frames=JSONArray()
        for(g in 0 until 300) {
            val last=(g+1)*batch-1;val sensor=raw[last]+30
            for(i in 0 until batch) frames.put(JSONObject().put("frameNumber",last)
                .put("sensorNs",sensor-(batch-1-i)*1_000_000_000L/fps)
                .put("callbackElapsedNs",sensor+30_000_000+i*1000))
        }
        return JSONObject().put("requestedFps",fps).put("cameraClockRealtime",true)
            .put("sessionType","constrained_high_speed").put("previewEnabled",true).put("outputSurfaceCount",2)
            .put("codecEosReached",true).put("failedCaptures",0).put("clockCalibrationRequested",true)
            .put("codecPtsNs",JSONArray(raw)).put("codecObservedElapsedNs",JSONArray(raw.map{it+35_000_000}))
            .put("encodedPtsNs",JSONArray(raw.map{it-raw.first()})).put("encodedFps",1e9/period)
            .put("encodedSpanNs",raw.last()-raw.first()).put("codecFrameCount",raw.size)
            .put("encodedFrameCount",raw.size).put("frames",frames)
    }
    private fun result(r:JSONObject):JSONObject {HighSpeedCalibration.annotate(r);return r.getJSONObject("clockCalibration")}
    private fun rejected(r:JSONObject) {assertFalse(result(r).getBoolean("sampledClockAlignmentObserved"))}

    @Test fun expanded120And240PreviewBatchesUseOnlyUnmodifiedEndpoints() {
        for(fps in listOf(120,240)) {
            val r=fixture(fps);val original=r.getJSONArray("frames").toString();val c=result(r)
            assertTrue(c.getBoolean("sampledClockAlignmentObserved"))
            assertEquals(300,c.getInt("metadataAnchorCount"))
            assertEquals(if(fps==120)900 else 2100,c.getInt("metadataDerivedCount"))
            assertEquals(300,c.getInt("matchedMetadataCount"));assertEquals(30L,c.getLong("maxMatchedResidualNs"))
            assertEquals(if(fps==120)3 else 7,c.getJSONArray("matches").getJSONObject(0).getInt("codecIndex"))
            assertEquals(original,r.getJSONArray("frames").toString())
            assertEquals(400_000_000_000L,FrameTimeline.from(r,if(fps==120)1200 else 2400).timestamp(0))
            assertFalse(r.getBoolean("precisionVerified"))
        }
    }
    @Test fun historicalBatchRecheckPreservesOriginalReportAndExactTimeline() {
        val r=fixture().put("clockCalibration",JSONObject().put("sampledClockAlignmentObserved",false)
            .put("sensorMetadataSequenceValid",false))
        val original=r.toString();val timeline=FrameTimeline.from(r,1200)
        assertEquals(400_008_353_000L,timeline.timestamp(1));assertEquals(1200,timeline.size)
        assertEquals(original,r.toString())
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(r,1199)}
        r.put("cameraClockRealtime",false)
        assertThrows(IllegalArgumentException::class.java){FrameTimeline.from(r,1200)}
    }
    @Test fun repeatedNumbersRequireExplicitPreviewHighSpeedProvenance() {
        for(key in listOf("sessionType","previewEnabled","outputSurfaceCount")) {
            val r=fixture();r.remove(key);rejected(r)
        }
        rejected(fixture().put("requestedFps",60))
    }
    @Test fun malformedOrPartialBatchesAndDerivedCallbackFailuresStayRejected() {
        for(problem in listOf("partial","timestamp","endpoint","callback","late_callback","number","negative","repeat_group")) {
            val r=fixture();val f=r.getJSONArray("frames")
            when(problem) {
                "partial"->f.remove(4)
                "timestamp"->f.getJSONObject(0).put("sensorNs",400_000_000_000L)
                "endpoint"->f.getJSONObject(3).put("sensorNs",f.getJSONObject(3).getLong("sensorNs")+1)
                "callback"->f.getJSONObject(1).put("callbackElapsedNs",0)
                "late_callback"->f.getJSONObject(1).put("callbackElapsedNs",500_000_000_000L)
                "number"->f.getJSONObject(2).put("frameNumber",2)
                "negative"->(0..3).forEach{f.getJSONObject(it).put("frameNumber",-1)}
                "repeat_group"->(4..7).forEach{f.getJSONObject(it).put("frameNumber",3)}
            }
            rejected(r)
        }
    }
    @Test fun anchorsStillNeedDirectClockAgreementSpanCoverageAndHealthyVideo() {
        val shifted=fixture();val raw=shifted.getJSONArray("codecPtsNs")
        for(i in 0 until raw.length())raw.put(i,raw.getLong(i)+1_000_000L)
        rejected(shifted)
        val short=fixture();val f=short.getJSONArray("frames");while(f.length()>400)f.remove(f.length()-1);rejected(short)
        val coverage=fixture();val fc=coverage.getJSONArray("frames");repeat(400){fc.remove(0)};rejected(coverage)
        rejected(fixture().put("failedCaptures",1));rejected(fixture().put("codecEosReached",false))
        rejected(fixture().put("cameraClockRealtime",false));rejected(fixture().put("encodedFps",30))
    }
}
