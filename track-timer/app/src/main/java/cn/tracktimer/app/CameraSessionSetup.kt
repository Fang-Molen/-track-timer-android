package cn.tracktimer.app

import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.Executor

object CameraSessionSetup {
    fun recordRequest(device: CameraDevice, config: CameraConfig, outputs: List<Surface>): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            outputs.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.range)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()

    /** Returns whether initial session parameters were submitted, not whether hardware honored them. */
    @Suppress("DEPRECATION")
    fun open(device: CameraDevice, outputs: List<Surface>, request: CaptureRequest,
        callback: CameraCaptureSession.StateCallback, handler: Handler): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val executor = Executor { command ->
                // Late onConfigured must reach CameraProbe's finished check and close its session.
                if (!handler.post(command)) Handler(Looper.getMainLooper()).post(command)
            }
            val session = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                outputs.map { OutputConfiguration(it) }, executor, callback)
            session.setSessionParameters(request)
            device.createCaptureSession(session)
            return true
        }
        device.createCaptureSession(outputs, callback, handler)
        return false
    }
}
