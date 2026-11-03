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
class HighSpeedCalibrationTest {
    private fun fixture(fps: Int = 120): JSONObject {
        val origin = 400_000_000_000L
        val raw = (0..fps*10).map { origin + (it*1_000_000L/fps)*1000 }
        val frames = (3..fps*10 step fps/30).map { i -> JSONObject().put("frameNumber", i)
            .put("sensorNs", raw[i]+360).put("callbackElapsedNs", raw[i]+30_000_000) }
        return JSONObject().put("requestedFps", fps).put("cameraClockRealtime", true)
            .put("sessionType", "constrained_high_speed").put("error", JSONObject.NULL)
            .put("codecEosReached", true).put("failedCaptures", 0)
            .put("codecPtsNs", JSONArray(raw)).put("codecObservedElapsedNs", JSONArray(raw.map { it+35_000_000 }))
            .put("encodedPtsNs", JSONArray(raw.map { it-origin })).put("encodedFps", fps)
            .put("encodedSpanNs", 10_000_000_000L).put("frames", JSONArray(frames))
    }
    private fun result(r: JSONObject): JSONObject { HighSpeedCalibration.annotate(r); return r.getJSONObject("clockCalibration") }

    @Test fun sparse120And240MetadataCanAlignWithoutInventingUnsampledSensorTimes() {
        for (fps in listOf(120,240)) {
            val r=fixture(fps); val c=result(r)
            assertTrue(c.getBoolean("sampledClockAlignmentObserved"))
            assertTrue(c.getBoolean("fileClockMappingObserved"))
            assertEquals(400_000_000_000L,c.getLong("fileZeroLocalElapsedNs"))
            assertEquals(360L,c.getLong("maxMatchedResidualNs"))
            assertEquals(300,c.getInt("matchedMetadataCount"))
            assertFalse(r.getBoolean("captureToEncodedMappingVerified"))
            assertFalse(r.getBoolean("precisionVerified"))
        }
    }
    @Test fun shiftedOrNormalizedEncoderClockCannotPassByFittingAnOffset() {
        for (shift in listOf(1_234_567L,400_000_000_000L)) {
            val r=fixture(); val a=r.getJSONArray("codecPtsNs")
            for (i in 0 until a.length()) a.put(i,a.getLong(i)-shift)
            val c=result(r); assertFalse(c.getBoolean("sampledClockAlignmentObserved"))
            assertTrue(c.isNull("fileZeroLocalElapsedNs"))
        }
    }
    @Test fun unknownClockFutureSensorCancelledErrorShortOrNoEosCannotPass() {
        val cases=listOf(fixture().put("cameraClockRealtime",false),fixture().put("cancelled",true),
            fixture().put("error","camera failed"),fixture().put("codecError","EOS timeout"),
            fixture().put("encodedSpanNs",1_000_000_000L),fixture().put("failedCaptures",1),
            fixture().put("codecEosReached",false),fixture().put("encodedFps",30))
        cases.forEach { assertFalse(result(it).getBoolean("fileClockMappingObserved")) }
        val future=fixture();future.getJSONArray("frames").getJSONObject(5).put("callbackElapsedNs",0)
        assertFalse(result(future).getBoolean("sampledClockAlignmentObserved"))
    }
    @Test fun muxedFileMustPreserveEveryTimestampAndCount() {
        val changed=fixture();changed.getJSONArray("encodedPtsNs").put(10,100_000_000L)
        val short=fixture();short.getJSONArray("encodedPtsNs").remove(0)
        for (r in listOf(changed,short)) {
            val c=result(r);assertFalse(c.getBoolean("fileClockMappingObserved"))
            assertTrue(c.isNull("fileZeroLocalElapsedNs"))
        }
    }
    @Test fun duplicateMissingOrDecreasingRawSamplesCannotCreateAlignment() {
        val duplicate=fixture();duplicate.getJSONArray("codecPtsNs").put(8,duplicate.getJSONArray("codecPtsNs").getLong(7))
        val missing=fixture();missing.getJSONArray("codecPtsNs").remove(7)
        val reversed=fixture();reversed.getJSONArray("frames").getJSONObject(5).put("frameNumber",0)
        listOf(duplicate,missing,reversed).forEach { assertFalse(result(it).getBoolean("fileClockMappingObserved")) }
    }
}
