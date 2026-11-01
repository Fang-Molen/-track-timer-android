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
    fun outputs(config: CameraConfig, recording: Surface, preview: Surface?): List<Surface> =
        if (config.previewEnabled) listOf(checkNotNull(preview) { "此模式需要预览画面" }, recording) else listOf(recording)

    fun recordRequest(device: CameraDevice, config: CameraConfig, outputs: List<Surface>): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            outputs.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.range)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()

    /** Returns whether initial session parameters were submitted, not whether hardware honored them. */
    @Suppress("DEPRECATION")
    fun open(device: CameraDevice, outputs: List<Surface>, request: CaptureRequest,
        callback: CameraCaptureSession.StateCallback, handler: Handler, highSpeed: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val executor = Executor { command ->
                // Late onConfigured must reach CameraProbe's finished check and close its session.
                if (!handler.post(command)) Handler(Looper.getMainLooper()).post(command)
            }
            val session = SessionConfiguration(if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR,
                outputs.map { OutputConfiguration(it) }, executor, callback)
            session.setSessionParameters(request)
            device.createCaptureSession(session)
            return true
        }
        if (highSpeed) device.createConstrainedHighSpeedCaptureSession(outputs, callback, handler)
        else device.createCaptureSession(outputs, callback, handler)
        return false
    }

    fun repeat(session: CameraCaptureSession, request: CaptureRequest, callback: CameraCaptureSession.CaptureCallback,
        handler: Handler, highSpeed: Boolean = false) {
        if (highSpeed) {
            val highSpeedSession = session as? CameraConstrainedHighSpeedCaptureSession
                ?: error("设备未创建高速录像会话")
            session.setRepeatingBurst(highSpeedSession.createHighSpeedRequestList(request), callback, handler)
        } else session.setRepeatingRequest(request, callback, handler)
    }
}
