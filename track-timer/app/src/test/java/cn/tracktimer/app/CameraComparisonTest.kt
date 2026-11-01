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

    @Test fun cameraOnlySuccessDoesNotClaimTheVideoEncoderIsProvenFaulty() {
        val run = CameraComparison("0")
        run.accept(report(CameraTestMode.HD60_CAPTURE_ONLY))
        run.accept(report(CameraTestMode.HD60_RECORD_ONLY, 30.0, 30.0))
        val summary = run.report()
        assertEquals("output_path_difference", summary.getJSONArray("pairs").getJSONObject(0).getString("finding"))
        assertFalse(summary.getBoolean("rootCauseVerified"))
        assertTrue(summary.getString("note").contains("不同"))
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
        assertEquals(CameraTestMode.HD60_CAPTURE_ONLY, run.current)
        assertTrue(run.accept(report(CameraTestMode.HD60_CAPTURE_ONLY, error = "unsupported")))
        assertEquals(CameraTestMode.HD60_RECORD_ONLY, run.current)
        assertFalse(run.accept(report(CameraTestMode.HD60_CAPTURE_ONLY)))
        run.cancel()
        assertNull(run.current)
        assertFalse(run.accept(report(CameraTestMode.HD60_RECORD_ONLY)))
        assertTrue(run.report().getBoolean("cancelled"))
    }
    @Test fun reportsFromAnotherCameraCannotProduceAComparison() {
        val run = CameraComparison("0")
        val wrong = report(CameraTestMode.HD60_CAPTURE_ONLY).put("cameraId", "2")
        assertTrue(run.accept(wrong))
        assertTrue(run.report().getJSONArray("steps").getJSONObject(0).getString("error").contains("相机"))
        assertFalse(CameraComparison.reached(wrong))
    }
    @Test fun completeRunKeepsFiveOrderedStepsAndHighSpeedSeparate() {
        val run = CameraComparison("0")
        repeat(5) {
            val mode = checkNotNull(run.current)
            assertTrue(run.accept(report(mode, mode.fps!!.toDouble(), mode.fps.toDouble())))
        }
        assertNull(run.current)
        val summary = run.report()
        assertEquals(5, summary.getJSONArray("steps").length())
        assertEquals(2, summary.getJSONArray("pairs").length())
        assertTrue(summary.getBoolean("completed"))
        assertFalse(summary.getBoolean("precisionVerified"))
    }
}
