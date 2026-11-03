package cn.tracktimer.app

import android.content.Context
import android.graphics.ImageFormat
import android.os.PowerManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object DeviceReport {
    fun cameraConfig(context: Context, mode: CameraTestMode = CameraTestMode.AUTOMATIC, forcedCameraId: String? = null): CameraConfig =
        CameraModes.select(cameraCapabilities(context), mode, forcedCameraId)
            ?: error("设备未声明支持此模式：${mode.label}。请换一个模式并导出日志。")

    internal fun cameraCapabilities(context: Context): List<CameraCapabilities> {
        val manager = context.getSystemService(CameraManager::class.java)
        return manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return@mapNotNull null
            val recorders = map.getOutputSizes(MediaRecorder::class.java)?.associateWith {
                map.getOutputMinFrameDuration(MediaRecorder::class.java, it)
            }.orEmpty()
            val previews = map.getOutputSizes(SurfaceTexture::class.java)?.associateWith {
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, it)
            }.orEmpty()
            val highSpeed = if (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true)
                map.highSpeedVideoSizes?.associateWith { map.getHighSpeedVideoFpsRangesFor(it).toList() }.orEmpty()
                else emptyMap()
            CameraCapabilities(id, recorders, previews,
                c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty(), highSpeed,
                c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                Build.VERSION.SDK_INT >= 28 && c.availableSessionKeys?.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) == true,
                map.getOutputSizes(ImageFormat.PRIVATE)?.associateWith { map.getOutputMinFrameDuration(ImageFormat.PRIVATE, it) }.orEmpty(),
                if (Build.VERSION.SDK_INT >= 33) c[CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES]?.toSet().orEmpty() else emptySet(),
                c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
                    c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true &&
                    c.availableCaptureRequestKeys.orEmpty().containsAll(listOf(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_FRAME_DURATION, CaptureRequest.SENSOR_SENSITIVITY)),
                c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE], c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE],
                c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION] ?: 0,
                map.getOutputSizes(MediaCodec::class.java)?.associateWith { map.getOutputMinFrameDuration(MediaCodec::class.java,it) }.orEmpty())
        }
    }

    fun supportsFrameRate(fps: Int, recorderMinNs: Long, previewMinNs: Long): Boolean {
        require(fps > 0 && recorderMinNs >= 0 && previewMinNs >= 0)
        val maxPeriod = 1_000_000_000L / fps + 100_000
        return listOf(recorderMinNs, previewMinNs).all { it == 0L || it <= maxPeriod }
    }

    fun runtimeConditions(context: Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("powerSaveMode", power.isPowerSaveMode)
            .put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL)
            .put("batteryTemperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: JSONObject.NULL)
            .put("batteryLevel", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: JSONObject.NULL)
            .put("batteryScale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: JSONObject.NULL)
    }

    fun collect(context: Context): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = JSONArray()
        manager.cameraIdList.forEach { id ->
            val c = manager.getCameraCharacteristics(id)
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val formats = JSONArray()
            val previewFormats = JSONArray()
            map?.getOutputSizes(MediaRecorder::class.java)?.forEach { size ->
                formats.put(JSONObject().put("width", size.width).put("height", size.height)
                    .put("minFrameDurationNs", map.getOutputMinFrameDuration(MediaRecorder::class.java, size)))
            }
            map?.getOutputSizes(SurfaceTexture::class.java)?.forEach { size ->
                previewFormats.put(JSONObject().put("width", size.width).put("height", size.height)
                    .put("minFrameDurationNs", map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)))
            }
            val highSpeed = JSONArray()
            if (c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) == true) {
                map?.highSpeedVideoSizes?.forEach { size ->
                    highSpeed.put(JSONObject().put("size", size.toString())
                        .put("ranges", JSONArray(map.getHighSpeedVideoFpsRangesFor(size).map { it.toString() })))
                }
            }
            cameras.put(JSONObject().put("id", id).put("facing", c[CameraCharacteristics.LENS_FACING])
                .put("hardwareLevel", c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL])
                .put("timestampSource", c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE])
                .put("orientation", c[CameraCharacteristics.SENSOR_ORIENTATION])
                .put("fpsRanges", JSONArray(c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.map { it.toString() }.orEmpty()))
                .put("encoderSizes", JSONArray(map?.getOutputSizes(MediaCodec::class.java)?.map { size ->
                    JSONObject().put("size",size.toString()).put("minFrameDurationNs",map.getOutputMinFrameDuration(MediaCodec::class.java,size))
                }.orEmpty()))
                .put("recordingSizes", formats).put("previewSizes", previewFormats).put("highSpeedModes", highSpeed)
                .put("privateSizes", JSONArray(map?.getOutputSizes(ImageFormat.PRIVATE)?.map { size ->
                    JSONObject().put("size", size.toString()).put("minFrameDurationNs", map.getOutputMinFrameDuration(ImageFormat.PRIVATE, size))
                }.orEmpty()))
                .put("capabilities", JSONArray(c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toList().orEmpty()))
                .put("streamUseCases", if (Build.VERSION.SDK_INT >= 33) JSONArray(c[CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES]?.toList().orEmpty()) else JSONArray())
                .put("exposureTimeRangeNs", c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.toString() ?: JSONObject.NULL)
                .put("sensitivityRange", c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.toString() ?: JSONObject.NULL)
                .put("maxFrameDurationNs", c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION] ?: JSONObject.NULL)
                .put("aeModes", JSONArray(c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]?.toList().orEmpty()))
                .put("availableCaptureRequestKeys", JSONArray(c.availableCaptureRequestKeys?.map { it.name }.orEmpty()))
                .put("availableSessionKeys", if (Build.VERSION.SDK_INT >= 28)
                    JSONArray(c.availableSessionKeys?.map { it.name }.orEmpty()) else JSONArray()))
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val devices = JSONArray(audio.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).map {
            JSONObject().put("type", it.type).put("isSource", it.isSource).put("name", it.productName.toString())
        })
        val capabilities = runCatching { cameraCapabilities(context) }
        val choice = runCatching { CameraModes.select(capabilities.getOrThrow(), CameraTestMode.AUTOMATIC)
            ?: error("未找到自动录像配置") }
        val testModes = JSONArray(CameraTestMode.entries.map { mode ->
            val c = capabilities.getOrNull()?.let { CameraModes.select(it, mode) }
            JSONObject().put("mode", mode.name).put("label", mode.label)
                .put("advertisedSupported", c != null).put("videoUseCaseRequested", mode.videoUseCase).put("manualSensorRequested", mode.manual).put("previewEnabled", mode.previewEnabled)
                .put("clockCalibration",mode.clockCalibration).put("highSpeed", mode.highSpeed).put("captureOnly", mode.captureOnly).put("requestedFps", c?.targetFps ?: mode.fps ?: JSONObject.NULL)
                .put("size", c?.size?.toString() ?: mode.size?.toString() ?: JSONObject.NULL)
        })
        val report = JSONObject().put("kind", "device")
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
            .put("runtimeConditions", runtimeConditions(context)).put("cameras", cameras).put("testModes", testModes).put("audioDevices", devices)
            .put("unprocessedAudioSupported", audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
            .put("h264Encoders", JSONArray(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
                it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", ignoreCase = true) }
            }.map { it.name }))
        choice.onSuccess { report.put("selectedCamera", it.id).put("selectedSize", it.size.toString())
            .put("requestedFps", it.targetFps).put("cameraClockRealtime", it.realtime)
            .put("requestedAeFpsRange", it.range.toString()).put("fpsIsSessionKey", it.fpsIsSessionKey)
            .put("recorderMinFrameDurationNs", it.recorderMinFrameDurationNs)
            .put("previewMinFrameDurationNs", it.previewMinFrameDurationNs) }
            .onFailure { report.put("cameraSelectionError", it.message) }
        return report
    }
}
