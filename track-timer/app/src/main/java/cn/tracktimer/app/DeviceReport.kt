package cn.tracktimer.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

data class CameraConfig(val id: String, val size: Size, val range: Range<Int>,
    val targetFps: Int, val realtime: Boolean, val sensorOrientation: Int)

object DeviceReport {
    fun cameraConfig(context: Context): CameraConfig {
        val manager = context.getSystemService(CameraManager::class.java)
        val candidates = manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return@mapNotNull null
            val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
                .filter { it.width <= 1920 && it.height <= 1080 }
            if (sizes.isEmpty()) return@mapNotNull null
            val ranges = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()
            val options = listOf(60, 30).flatMap { fps ->
                ranges.filter { it.contains(fps) && it.upper <= fps }.flatMap { range ->
                    sizes.filter { size ->
                        val minNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
                        minNs == 0L || minNs <= 1_000_000_000L / fps + 100_000
                    }.map { size ->
                        CameraConfig(id, size, range, fps,
                            c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
                            c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0)
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

    fun collect(context: Context): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = JSONArray()
        manager.cameraIdList.forEach { id ->
            val c = manager.getCameraCharacteristics(id)
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val formats = JSONArray()
            map?.getOutputSizes(MediaRecorder::class.java)?.forEach { size ->
                formats.put(JSONObject().put("width", size.width).put("height", size.height)
                    .put("minFrameDurationNs", map.getOutputMinFrameDuration(MediaRecorder::class.java, size)))
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
                .put("recordingSizes", formats).put("highSpeedModes", highSpeed))
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
            .put("requestedFps", it.targetFps).put("cameraClockRealtime", it.realtime) }
            .onFailure { report.put("cameraSelectionError", it.message) }
        return report
    }
}
