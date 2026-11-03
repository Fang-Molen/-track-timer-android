package cn.tracktimer.app

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
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

    @Test fun highSpeedUsesDedicatedSessionAndRepeatingBurst() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(120, 120), 120, true, 90, highSpeed = true)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            CameraSessionSetup.open(camera, outputs, request, callback, handler, highSpeed = true)
            assertEquals(SessionConfiguration.SESSION_HIGH_SPEED, RecordingCameraShadow.lastConfiguration!!.sessionType)
            val session = FakeHighSpeedSession(camera)
            CameraSessionSetup.repeat(session, request, object : CameraCaptureSession.CaptureCallback() {}, handler, highSpeed = true)
            assertTrue(session.createdList); assertSame(session.batch, session.submittedBurst)
            assertEquals(0, session.normalRepeatCount)
        }
    }

    @Test @Config(sdk = [26]) fun android8UsesItsDedicatedHighSpeedApi() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(120, 120), 120, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            assertFalse(CameraSessionSetup.open(camera, outputs, request, callback, handler, highSpeed = true))
            assertTrue(RecordingCameraShadow.legacyHighSpeedCalled)
            assertFalse(RecordingCameraShadow.legacyCalled)
        }
    }

    @Test fun encoderOnlyRequestAndSessionDoNotIncludeAPreviewSurface() {
        withCamera { camera, surfaces, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90, previewEnabled = false)
            val outputs = CameraSessionSetup.outputs(c, surfaces[0], null)
            assertEquals(listOf(surfaces[0]), outputs)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            val targets = org.robolectric.util.ReflectionHelpers.callInstanceMethod<Collection<Surface>>(request, "getTargets")
            assertEquals(setOf(surfaces[0]), targets.toSet())
            CameraSessionSetup.open(camera, outputs, request, callback, handler)
            assertEquals(1, RecordingCameraShadow.lastConfiguration!!.outputConfigurations.size)
        }
    }

    @Test fun manualRequestDisablesAeAndSubmitsAllThreeSensorControls() {
        withCamera { camera, outputs, _ ->
            val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90,
                manualSensor = ManualSensorSettings(8_000_000L, 16_666_667L, 800))
            CameraSessionSetup.recordRequest(camera, c, outputs)
            val b = RecordingCameraShadow.lastBuilder!!
            assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, b[CaptureRequest.CONTROL_AE_MODE])
            assertEquals(CaptureRequest.CONTROL_MODE_AUTO, b[CaptureRequest.CONTROL_MODE])
            assertEquals(8_000_000L, b[CaptureRequest.SENSOR_EXPOSURE_TIME])
            assertEquals(16_666_667L, b[CaptureRequest.SENSOR_FRAME_DURATION])
            assertEquals(800, b[CaptureRequest.SENSOR_SENSITIVITY])
        }
    }
    @Test @Config(sdk = [33]) fun streamUseCaseIsOnTheOutputBeforeCreatingTheSession() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, listOf(outputs[0]))
            CameraSessionSetup.open(camera, listOf(outputs[0]), request, callback, handler, streamUseCase = 3L)
            val s = RecordingCameraShadow.lastConfiguration!!
            assertEquals(1, s.outputConfigurations.size)
            assertEquals(3L, s.outputConfigurations.single().streamUseCase)
            assertEquals(SessionConfiguration.SESSION_REGULAR, s.sessionType)
        }
    }
    @Test fun oldApiRejectsExplicitUseCaseInsteadOfIgnoringIt() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(60, 60), 60, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            assertThrows(IllegalArgumentException::class.java) {
                CameraSessionSetup.open(camera, listOf(outputs[0]), request, callback, handler, streamUseCase = 3L)
            }
            assertNull(RecordingCameraShadow.lastConfiguration)
        }
    }
    @Test @Config(sdk = [33]) fun highSpeedDoesNotReceiveTheNormalRecordingUseCase() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280, 720), Range(120, 120), 120, true, 90)
            val request = CameraSessionSetup.recordRequest(camera, c, outputs)
            assertThrows(IllegalArgumentException::class.java) {
                CameraSessionSetup.open(camera, listOf(outputs[0]), request, callback, handler, highSpeed = true, streamUseCase = 3L)
            }
            assertNull(RecordingCameraShadow.lastConfiguration)
        }
    }

    @Test @Config(sdk = [33]) fun clockCalibrationRequestsSensorTimeBaseBeforeHighSpeedSessionCreation() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280,720), Range(240,240),240,true,90)
            val request = CameraSessionSetup.recordRequest(camera,c,listOf(outputs[0]))
            CameraSessionSetup.open(camera,listOf(outputs[0]),request,callback,handler,highSpeed=true,sensorTimestampBase=true)
            val session = RecordingCameraShadow.lastConfiguration!!
            assertEquals(OutputConfiguration.TIMESTAMP_BASE_SENSOR,session.outputConfigurations.single().timestampBase)
            assertEquals(SessionConfiguration.SESSION_HIGH_SPEED,session.sessionType)
        }
    }
    @Test fun oldApiClockProbeUsesDefaultWithoutCallingNewTimestampApi() {
        withCamera { camera, outputs, handler ->
            val c = CameraConfig("0", Size(1280,720), Range(120,120),120,true,90)
            val request = CameraSessionSetup.recordRequest(camera,c,outputs)
            CameraSessionSetup.open(camera,outputs,request,callback,handler,highSpeed=true,sensorTimestampBase=true)
            assertEquals(SessionConfiguration.SESSION_HIGH_SPEED,RecordingCameraShadow.lastConfiguration!!.sessionType)
        }
    }

    private val callback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }

    private fun withCamera(block: (CameraDevice, List<Surface>, Handler) -> Unit) {
        RecordingCameraShadow.lastConfiguration = null; RecordingCameraShadow.legacyCalled = false
        RecordingCameraShadow.legacyHighSpeedCalled = false
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
    @Implementation
    protected fun createConstrainedHighSpeedCaptureSession(outputs: MutableList<Surface>, callback: CameraCaptureSession.StateCallback, handler: Handler) {
        legacyHighSpeedCalled = true
    }
    companion object {
        var lastConfiguration: SessionConfiguration? = null
        var legacyCalled = false
        var lastBuilder: CaptureRequest.Builder? = null
        var legacyHighSpeedCalled = false
    }
}

private class FakeHighSpeedSession(private val camera: CameraDevice) : CameraConstrainedHighSpeedCaptureSession() {
    var createdList = false
    lateinit var batch: List<CaptureRequest>
    var submittedBurst: List<CaptureRequest>? = null
    var normalRepeatCount = 0
    override fun createHighSpeedRequestList(request: CaptureRequest): List<CaptureRequest> {
        createdList = true; batch = listOf(request, request, request, request); return batch
    }
    override fun setRepeatingBurst(requests: MutableList<CaptureRequest>, callback: CaptureCallback?, handler: Handler?): Int { submittedBurst = requests; return 1 }
    override fun setRepeatingRequest(request: CaptureRequest, callback: CaptureCallback?, handler: Handler?): Int { normalRepeatCount++; return 1 }
    override fun getDevice() = camera
    override fun prepare(surface: Surface) = Unit
    override fun finalizeOutputConfigurations(outputs: MutableList<OutputConfiguration>) = Unit
    override fun capture(request: CaptureRequest, callback: CaptureCallback?, handler: Handler?) = 1
    override fun captureBurst(requests: MutableList<CaptureRequest>, callback: CaptureCallback?, handler: Handler?) = 1
    override fun stopRepeating() = Unit
    override fun abortCaptures() = Unit
    override fun isReprocessable() = false
    override fun getInputSurface(): Surface? = null
    override fun close() = Unit
}
