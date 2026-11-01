package cn.tracktimer.app

import android.os.Looper
import android.view.TextureView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraProbeTest {
    @Test fun encoderOnlyStartGetsPastAnUnavailableTextureAndReportsTheRequestedMode() {
        val app = RuntimeEnvironment.getApplication()
        val texture = TextureView(app)
        assertFalse(texture.isAvailable)
        var report: JSONObject? = null
        val probe = CameraProbe(app, ReportStore(app), texture, {}, { report = it }, CameraTestMode.HD60_RECORD_ONLY)
        try {
            probe.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (report == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            val result = checkNotNull(report)
            // The test environment has no cameras: selection must be reached before this error.
            assertTrue(result.getString("error").contains("未声明支持"))
            assertEquals("HD60_RECORD_ONLY", result.getString("testMode"))
            assertEquals(60, result.getInt("requestedFps"))
            assertFalse(result.getBoolean("previewEnabled"))
            assertFalse(result.getBoolean("requestedFpsAchieved"))
        } finally { probe.stop("test complete") }
    }
}
