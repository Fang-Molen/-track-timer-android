package cn.tracktimer.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

data class CameraConfig(val id: String, val size: Size, val range: Range<Int>,
    val targetFps: Int, val realtime: Boolean, val sensorOrientation: Int,
    val recorderMinFrameDurationNs: Long = 0, val previewMinFrameDurationNs: Long = 0,
    val fpsIsSessionKey: Boolean = false)

object DeviceReport {
    fun cameraConfig(context: Context): CameraConfig {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidates = manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return@mapNotNull null
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
            val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
                .filter { it.width <= 1920 && it.height <= 1080 && it in previewSizes }
            if (sizes.isEmpty()) return@mapNotNull null
            val ranges = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()
            val options = listOf(60, 30).flatMap { fps ->
                ranges.filter { it.contains(fps) && it.upper <= fps }.flatMap { range ->
                    sizes.filter { size ->
                        supportsFrameRate(fps, map.getOutputMinFrameDuration(MediaRecorder::class.java, size),
                            map.getOutputMinFrameDuration(SurfaceTexture::class.java, size))
                    }.map { size ->
                        CameraConfig(id, size, range, fps,
                            c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                            c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                            map.getOutputMinFrameDuration(MediaRecorder::class.java, size),
                            map.getOutputMinFrameDuration(SurfaceTexture::class.java, size),
                            Build.VERSION.SDK_INT >= 28 && c.availableSessionKeys?.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) == true)
                    }
                }
            }
            options.sortedWith(compareByDescending<CameraConfig> { it.targetFps }
                .thenBy { if (it.size == Size(1280, 720)) 0 else 1 }
                .thenByDescending { it.range.lower }.thenByDescending { it.size.width * it.size.height }).firstOrNull()
        }
        return candidates.maxByOrNull { it.targetFps }
            ?: error("未找到可用的后置普通录像配置；请先导出设备报告。")
    }

    fun supportsFrameRate(fps: Int, recorderMinNs: Long, previewMinNs: Long): Boolean {
        require(fps > 0 && recorderMinNs >= 0 && previewMinNs >= 0)
        val maxPeriod = 1_000_000_000L / fps + 100_000
        return listOf(recorderMinNs, previewMinNs).all { it == 0L || it <= maxPeriod }
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
                .put("recordingSizes", formats).put("previewSizes", previewFormats).put("highSpeedModes", highSpeed)
                .put("availableSessionKeys", if (Build.VERSION.SDK_INT >= 28)
                    JSONArray(c.availableSessionKeys?.map { it.name }.orEmpty()) else JSONArray()))
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val devices = JSONArray(audio.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).map {
            JSONObject().put("type", it.type).put("isSource", it.isSource).put("name", it.productName.toString())
        })
        val choice = runCatching { cameraConfig(context) }
        val report = JSONObject().put("kind", "device")
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE).put("api", Build.VERSION.SDK_INT)
            .put("cameras", cameras).put("audioDevices", devices)
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
