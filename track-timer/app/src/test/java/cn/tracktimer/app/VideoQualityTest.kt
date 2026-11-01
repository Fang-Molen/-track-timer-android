package cn.tracktimer.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoQualityTest {
    @Test fun sensor60WithSaved30DoesNotMeetTheRequested60fps() {
        val report = report(60.0, 30.0)
        CameraProbe.updateFrameRateStatus(report)
        assertFalse(report.getBoolean("requestedFpsAchieved"))
    }

    @Test fun unavailableEncodedMeasurementCannotPass() {
        val report = report(60.0, null)
        CameraProbe.updateFrameRateStatus(report)
        assertFalse(report.getBoolean("requestedFpsAchieved"))
    }

    @Test fun bothMeasurementsMustMeetTheRequestedRate() {
        val good = report(59.98, 59.97)
        CameraProbe.updateFrameRateStatus(good)
        assertTrue(good.getBoolean("requestedFpsAchieved"))
        val slow = report(30.0, 60.0)
        CameraProbe.updateFrameRateStatus(slow)
        assertFalse(slow.getBoolean("requestedFpsAchieved"))
        val missing = report(null, 60.0)
        CameraProbe.updateFrameRateStatus(missing)
        assertFalse(missing.getBoolean("requestedFpsAchieved"))
    }

    @Test fun shortErroredOrCancelledSamplesNeverSetOverallSuccess() {
        val short = report(60.0, 60.0).put("captureSpanNs", 100_000_000)
        CameraProbe.updateFrameRateStatus(short)
        assertFalse(short.getBoolean("requestedFpsAchieved"))
        val cancelled = report(60.0, 60.0).put("cancelled", true)
        CameraProbe.updateFrameRateStatus(cancelled)
        assertFalse(cancelled.getBoolean("requestedFpsAchieved"))
        val malformed = report(60.0, 60.0).put("timestampError", "non-monotonic")
        CameraProbe.updateFrameRateStatus(malformed)
        assertFalse(malformed.getBoolean("requestedFpsAchieved"))
    }

    private fun report(capture: Double?, encoded: Double?) = JSONObject().put("requestedFps", 60)
        .put("captureSpanNs", 9_000_000_000L).put("encodedSpanNs", 9_000_000_000L)
        .put("captureFps", capture ?: JSONObject.NULL).put("encodedFps", encoded ?: JSONObject.NULL)
}
