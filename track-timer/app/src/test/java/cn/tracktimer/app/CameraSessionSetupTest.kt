package cn.tracktimer.app

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraDeviceImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [RecordingCameraShadow::class])
class CameraSessionSetupTest {
    @Test fun a30fpsPreviewMustNotBeSelectedAsA60fpsCombination() {
        assertFalse(DeviceReport.supportsFrameRate(60, 16_666_666, 33_333_333))
        assertFalse(DeviceReport.supportsFrameRate(60, 33_333_333, 16_666_666))
        assertTrue(DeviceReport.supportsFrameRate(60, 16_666_666, 16_666_666))
        assertTrue(DeviceReport.supportsFrameRate(30, 16_666_666, 33_333_333))
    }

    @Test fun modernSessionReceivesThe60fpsRequestBeforeConfiguration() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            assertTrue(CameraSessionSetup.open(camera, outputs, request, callback, handler))
            val session = RecordingCameraShadow.lastConfiguration!!
            assertSame(request, session.sessionParameters)
            // Robolectric keeps set/get on the builder; native metadata is not copied by build().
            assertEquals(Range(60, 60), RecordingCameraShadow.lastBuilder!![CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE])
            assertEquals(2, session.outputConfigurations.size)
            assertFalse(RecordingCameraShadow.legacyCalled)
        }
    }

    @Test @Config(sdk = [26]) fun android8UsesTheCompatibleSessionPath() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(30, 30), 30, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            assertFalse(CameraSessionSetup.open(camera, outputs, request, callback, handler))
            assertTrue(RecordingCameraShadow.legacyCalled)
            assertNull(RecordingCameraShadow.lastConfiguration)
        }
    }

    @Test fun lateSessionCallbackStillRunsAfterTheCameraWorkerStops() {
        withCamera { camera, outputs, _ ->
            val worker = HandlerThread("late-camera-test").apply { start() }
            try {
                val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90)
                val request = CameraSessionSetup.recordRequest(camera, c, outputs)
                CameraSessionSetup.open(camera, outputs, request, callback, Handler(worker.looper))
                worker.quitSafely(); worker.join(2000)
                assertFalse(worker.isAlive)
                var called = false
                RecordingCameraShadow.lastConfiguration!!.executor.execute { called = true }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(called)
            } finally { worker.quitSafely() }
        }
    }

    private val callback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }

    private fun withCamera(block: (CameraDevice, List<Surface>, Handler) -> Unit) {
        RecordingCameraShadow.lastConfiguration = null; RecordingCameraShadow.legacyCalled = false
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val manager = app.getSystemService(CameraManager::class.java)
        shadowOf(manager).addCamera("0", ShadowCameraCharacteristics.newCameraCharacteristics())
        var device: CameraDevice? = null
        val handler = Handler(Looper.getMainLooper())
        manager.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera }
            override fun onDisconnected(camera: CameraDevice) = Unit
            override fun onError(camera: CameraDevice, error: Int) = Unit
        }, handler)
        shadowOf(Looper.getMainLooper()).idle()
        val textures = listOf(SurfaceTexture(0), SurfaceTexture(1))
        val outputs = textures.map { Surface(it) }
        try { block(checkNotNull(device), outputs, handler) }
        finally { device?.close(); outputs.forEach { it.release() }; textures.forEach { it.release() } }
    }
}

@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
class RecordingCameraShadow : ShadowCameraDeviceImpl() {
    @Implementation
    override fun createCaptureRequest(templateType: Int): CaptureRequest.Builder =
        super.createCaptureRequest(templateType).also { lastBuilder = it }
    @Implementation(minSdk = 28)
    override fun createCaptureSession(config: SessionConfiguration) { lastConfiguration = config }
    @Implementation
    override fun createCaptureSession(outputs: MutableList<Surface>, callback: CameraCaptureSession.StateCallback, handler: Handler) {
        legacyCalled = true
    }
    companion object {
        var lastConfiguration: SessionConfiguration? = null
        var legacyCalled = false
        var lastBuilder: CaptureRequest.Builder? = null
    }
}
