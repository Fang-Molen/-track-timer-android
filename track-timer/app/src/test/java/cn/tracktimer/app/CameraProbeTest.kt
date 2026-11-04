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
    @Test fun recordingOwnershipLastsThroughTheDoneCallbackAndReleasesAfterPublication() {
        val app=RuntimeEnvironment.getApplication();val store=ReportStore(app);val library=VideoLibrary(store)
        var callbackSeen=false;var protectedInCallback=false
        lateinit var completed:java.io.File
        val probe=CameraProbe(app,store,TextureView(app),{}, {
            protectedInCallback=!library.inventory().first{it.name==completed.name}.canDelete
            callbackSeen=true
        },CameraTestMode.HD240_CLOCK)
        val pending=probe.reserveVideo()
        completed=java.io.File(pending.parentFile,pending.nameWithoutExtension+".mp4").apply{writeText("simulated rename before publication")}
        assertTrue(ActiveVideoFiles.isActive(completed))
        probe.stop()
        val deadline=System.nanoTime()+3_000_000_000L
        while(!callbackSeen && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(callbackSeen);assertTrue("Done callback must still own the video until references are published",protectedInCallback)
        assertFalse(ActiveVideoFiles.isActive(completed));assertTrue(library.inventory().first{it.name==completed.name}.canDelete)
    }
    @Test fun cancelledClockProbeKeepsConsumerIdentityAndCannotPassClockMapping() {
        val app = RuntimeEnvironment.getApplication()
        var report: JSONObject? = null
        val probe = CameraProbe(app,ReportStore(app),TextureView(app),{}, { report=it },CameraTestMode.HD240_CLOCK)
        probe.stop()
        val deadline=System.nanoTime()+3_000_000_000L
        while (report==null && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        val r=checkNotNull(report)
        assertTrue(r.getBoolean("cancelled"));assertEquals("MediaCodec_surface",r.getString("consumerType"))
        assertFalse(r.getJSONObject("clockCalibration").getBoolean("fileClockMappingObserved"))
        assertFalse(r.has("videoFile"))
    }

    @Test fun manualStopRecordsCancellationEvenWithoutAnExplicitReason() {
        val app = RuntimeEnvironment.getApplication()
        var report: JSONObject? = null
        val probe = CameraProbe(app, ReportStore(app), TextureView(app), {}, { report = it }, CameraTestMode.HD60_CAPTURE_ONLY)
        probe.stop()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (report == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        val result = checkNotNull(report)
        assertTrue(result.optBoolean("cancelled"))
        assertFalse(result.getBoolean("requestedFpsAchieved"))
        assertFalse(result.getBoolean("diagnosticFpsReached"))
    }
    @Test fun captureOnlyFailureHasNoVideoAndKeepsRunAndPinnedCameraIdentity() {
        val app = RuntimeEnvironment.getApplication()
        val store = ReportStore(app)
        val before = store.directory.listFiles().orEmpty().filter { it.extension in listOf("mp4", "pending") }.map { it.name }.toSet()
        var report: JSONObject? = null
        val probe = CameraProbe(app, store, TextureView(app), {}, { report = it }, CameraTestMode.HD60_CAPTURE_ONLY,
            runId = "trial", forcedCameraId = "0")
        try {
            probe.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (report == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            val r = checkNotNull(report)
            assertTrue(r.getBoolean("captureOnly")); assertEquals("trial", r.getString("runId"))
            assertEquals("0", r.getString("requestedCameraId"))
            assertFalse(r.has("videoFile")); assertFalse(r.has("encodedFps"))
            val after = store.directory.listFiles().orEmpty().filter { it.extension in listOf("mp4", "pending") }.map { it.name }.toSet()
            assertEquals(before, after)
        } finally { probe.stop("test complete") }
    }
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
