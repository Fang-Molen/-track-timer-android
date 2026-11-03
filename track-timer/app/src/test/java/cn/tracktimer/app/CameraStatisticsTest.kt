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
class CameraStatisticsTest {
    private fun highSpeed() = JSONObject().put("sessionType", "constrained_high_speed").put("requestedFps", 120)
        .put("captureFps", 30.0).put("encodedFps", 119.72)
        .put("captureSpanNs", 10_000_000_000L).put("encodedSpanNs", 10_000_000_000L)
        .put("estimatedMissingCaptureFrames", 900).put("error", JSONObject.NULL)
        .put("frames", JSONArray((0..300).map { i -> JSONObject().put("frameNumber", 3 + 4*i)
            .put("sensorNs", 10_000_000_000L + (i * 1e9 / 30).toLong()) }))

    @Test fun sparseHighSpeedMetadataIsNotPresentedAs30fpsVideoOrProvenSensorRate() {
        val r = highSpeed(); CameraStatistics.annotate(r)
        assertEquals(30.0, r.getDouble("metadataSampleFps"), 0.001)
        assertEquals(120.0, r.getDouble("frameNumberSpanFpsEstimate"), 0.001)
        assertEquals(300, r.getJSONObject("frameNumberStepCounts").getInt("4"))
        assertTrue(r.getBoolean("videoRateReached"))
        assertTrue(r.isNull("sensorFps")); assertTrue(r.isNull("captureFpsAchieved"))
        assertTrue(r.isNull("requestedFpsAchieved")); assertTrue(r.isNull("estimatedMissingCaptureFrames"))
        assertFalse(r.getBoolean("captureToEncodedMappingVerified"))
        assertEquals("video_rate_reached_mapping_unverified", r.getString("rateStatus"))
    }
    @Test fun shortCancelledMissingOrErroredVideoDoesNotPassEvenWith120InItsRateField() {
        val reports = listOf(highSpeed().put("encodedSpanNs", 1_000_000_000L),
            highSpeed().put("cancelled", true), highSpeed().put("encodedFps", JSONObject.NULL),
            highSpeed().put("encodedInspectionError", "bad video"), highSpeed().put("error", "failed"))
        reports.forEach { r -> CameraStatistics.annotate(r); assertFalse(r.getBoolean("videoRateReached")) }
    }
    @Test fun frameNumberDiscontinuityDoesNotGenerateAPhysicalRateEstimate() {
        val r = highSpeed()
        r.getJSONArray("frames").getJSONObject(2).put("frameNumber", 0)
        CameraStatistics.annotate(r)
        assertTrue(r.isNull("frameNumberSpanFpsEstimate"))
        assertFalse(r.getBoolean("metadataFrameNumberSequenceValid"))
    }
    @Test fun normal60StillRequiresBothSensorAndVideoForAtLeastFiveSeconds() {
        val r = highSpeed().put("sessionType", "regular").put("requestedFps", 60)
            .put("captureFps", 30.0).put("encodedFps", 60.0)
        CameraStatistics.annotate(r); assertFalse(r.getBoolean("requestedFpsAchieved"))
        r.put("captureFps", 60.0); CameraStatistics.annotate(r)
        assertTrue(r.getBoolean("requestedFpsAchieved")); assertEquals(60.0, r.getDouble("sensorFps"), .001)
    }

    @Test fun captureFailureInvalidatesNormalSuccessEvenWhenAverageRatesReachTarget() {
        val r = highSpeed().put("sessionType", "regular").put("requestedFps", 60)
            .put("captureFps", 59.9).put("encodedFps", 60.0).put("failedCaptures", 1)
        CameraStatistics.annotate(r)
        assertFalse(r.getBoolean("diagnosticFpsReached"))
        assertFalse(r.getBoolean("captureFpsAchieved"))
        assertFalse(r.getBoolean("videoRateReached"))
        assertFalse(CameraComparison.reached(r))
        assertFalse(CameraStatistics.usable(r, "capture"))
    }

    @Test fun captureFailureInvalidatesHighSpeedVideoSuccessEvenWhenVideoRateReachesTarget() {
        val r = highSpeed().put("failedCaptures", 1)
        CameraStatistics.annotate(r)
        assertFalse(r.getBoolean("videoRateReached"))
        assertFalse(r.getBoolean("encodedFpsAchieved"))
        assertEquals("video_rate_not_confirmed", r.getString("rateStatus"))
    }
}
