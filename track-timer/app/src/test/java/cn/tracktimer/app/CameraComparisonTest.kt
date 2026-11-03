package cn.tracktimer.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraComparisonTest {
    private fun report(mode: CameraTestMode, capture: Double = 60.0, consumer: Double = 60.0,
        span: Long = 9_000_000_000L, error: String? = null) = JSONObject()
        .put("testMode", mode.name).put("cameraId", "0").put("captureOnly", mode.captureOnly)
        .put("captureFps", capture).put(if (mode.captureOnly) "imageFps" else "encodedFps", consumer)
        .put("captureSpanNs", span).put(if (mode.captureOnly) "imageSpanNs" else "encodedSpanNs", span)
        .put("requestedFps", mode.fps).put("error", error ?: JSONObject.NULL)

    @Test fun singleFactorImprovementIsRecordedWithoutClaimingARootCause() {
        val run = CameraComparison("0")
        run.accept(report(CameraTestMode.HD60_RECORD_ONLY, 30.0, 30.0))
        run.accept(report(CameraTestMode.HD60_VIDEO_USE_CASE))
        val summary = run.report()
        val pair = summary.getJSONArray("pairs").getJSONObject(0)
        assertEquals("stream_use_case", pair.getString("factor"))
        assertEquals("test_reached_only", pair.getString("finding"))
        assertFalse(summary.getBoolean("rootCauseVerified"))
    }
    @Test fun shortMissingAndFailedMeasurementsCannotPass() {
        val good = report(CameraTestMode.HD60_CAPTURE_ONLY)
        assertTrue(CameraComparison.reached(good))
        assertFalse(CameraComparison.reached(report(CameraTestMode.HD60_CAPTURE_ONLY, span = 100_000_000)))
        assertFalse(CameraComparison.reached(report(CameraTestMode.HD60_CAPTURE_ONLY, error = "cancelled")))
        assertFalse(CameraComparison.reached(report(CameraTestMode.HD60_CAPTURE_ONLY, consumer = 30.0)))
        good.remove("imageFps"); assertFalse(CameraComparison.reached(good))
    }
    @Test fun failuresAdvanceButCancelledOrDuplicateCallbacksNeverAdvance() {
        val run = CameraComparison("0")
        assertEquals(CameraTestMode.HD60_RECORD_ONLY, run.current)
        assertTrue(run.accept(report(CameraTestMode.HD60_RECORD_ONLY, error = "unsupported")))
        assertEquals(CameraTestMode.HD60_VIDEO_USE_CASE, run.current)
        assertFalse(run.accept(report(CameraTestMode.HD60_RECORD_ONLY)))
        run.cancel()
        assertNull(run.current)
        assertFalse(run.accept(report(CameraTestMode.HD60_VIDEO_USE_CASE)))
        assertTrue(run.report().getBoolean("cancelled"))
    }
    @Test fun reportsFromAnotherCameraCannotProduceAComparison() {
        val run = CameraComparison("0")
        val wrong = report(CameraTestMode.HD60_RECORD_ONLY).put("cameraId", "2")
        assertTrue(run.accept(wrong))
        assertTrue(run.report().getJSONArray("steps").getJSONObject(0).getString("error").contains("相机"))
        assertFalse(CameraComparison.reached(wrong))
    }
    @Test fun nineStepRunSeparatesEightOrdinaryExperimentsFromHighSpeedVideoEvidence() {
        val run = CameraComparison("0")
        val expected = listOf(CameraTestMode.HD60_RECORD_ONLY, CameraTestMode.HD60_VIDEO_USE_CASE,
            CameraTestMode.HD60_MANUAL, CameraTestMode.HD60_MANUAL_VIDEO_USE_CASE,
            CameraTestMode.FHD60_RECORD_ONLY, CameraTestMode.FHD60_VIDEO_USE_CASE,
            CameraTestMode.FHD60_MANUAL, CameraTestMode.FHD60_MANUAL_VIDEO_USE_CASE, CameraTestMode.HD120_RECORD_ONLY)
        expected.forEach { mode ->
            assertEquals(mode, run.current)
            val r = report(mode, 30.0, if (mode.highSpeed) 119.72 else 30.0)
                .put("sessionType", if (mode.highSpeed) "constrained_high_speed" else "regular")
            assertTrue(run.accept(r))
        }
        assertNull(run.current)
        val summary = run.report()
        assertEquals(9, summary.getJSONArray("steps").length())
        assertEquals(8, summary.getInt("normalTestCount"))
        assertEquals(0, summary.getInt("normalRateReachedCount"))
        assertTrue(summary.getBoolean("highSpeedVideoRateReached"))
        assertEquals(8, summary.getJSONArray("pairs").length())
        assertTrue(summary.getBoolean("completed")); assertFalse(summary.getBoolean("precisionVerified"))
    }
}
