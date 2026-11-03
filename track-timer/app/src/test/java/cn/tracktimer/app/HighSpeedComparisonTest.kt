package cn.tracktimer.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HighSpeedComparisonTest {
    private fun step(mode: CameraTestMode, error: String? = null) = JSONObject()
        .put("testMode",mode.name).put("cameraId","0").put("requestedFps",mode.fps)
        .put("encodedFps",mode.fps).put("encodedSpanNs",10_000_000_000L)
        .put("clockCalibration",JSONObject().put("fileClockMappingObserved",true))
        .put("error",error ?: JSONObject.NULL)

    @Test fun unsupported240Preserves120ResultAndCompletesWithoutFallback() {
        val run=CameraComparison("0",highSpeedCalibration=true)
        assertEquals(CameraTestMode.HD120_CLOCK,run.current)
        assertTrue(run.accept(step(CameraTestMode.HD120_CLOCK)))
        assertFalse(run.accept(step(CameraTestMode.HD120_CLOCK)))
        assertEquals(CameraTestMode.HD240_CLOCK,run.current)
        assertTrue(run.accept(step(CameraTestMode.HD240_CLOCK,"unsupported")))
        assertNull(run.current)
        val r=run.report();assertTrue(r.getBoolean("completed"))
        assertEquals(2,r.getInt("plannedSteps"));assertEquals(1,r.getInt("videoRateReachedCount"))
        assertEquals(1,r.getInt("clockAlignedCount"));assertFalse(r.getBoolean("precisionVerified"))
    }
    @Test fun cancellationStopsSequenceAndCannotBeCompletedByLateResult() {
        val run=CameraComparison("0",highSpeedCalibration=true)
        run.accept(step(CameraTestMode.HD120_CLOCK));run.cancel()
        assertFalse(run.accept(step(CameraTestMode.HD240_CLOCK)))
        assertTrue(run.report().getBoolean("cancelled"));assertFalse(run.report().getBoolean("completed"))
        assertEquals(1,run.report().getJSONArray("steps").length())
    }
    @Test fun anotherCameraCannotContributeClockSuccess() {
        val run=CameraComparison("0",highSpeedCalibration=true)
        run.accept(step(CameraTestMode.HD120_CLOCK).put("cameraId","1"))
        assertEquals(0,run.report().getInt("clockAlignedCount"))
    }
}
