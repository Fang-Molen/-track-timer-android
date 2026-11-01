package cn.tracktimer.app

import android.util.Range
import android.util.Size

enum class CameraTestMode(val label: String, val size: Size?, val fps: Int?,
    val previewEnabled: Boolean = true, val highSpeed: Boolean = false) {
    FHD60_PREVIEW("1080p · 请求60帧 · 含预览", Size(1920, 1080), 60),
    HD60_RECORD_ONLY("720p · 请求60帧 · 仅录像", Size(1280, 720), 60, false),
    FHD60_RECORD_ONLY("1080p · 请求60帧 · 仅录像", Size(1920, 1080), 60, false),
    HD60_PREVIEW("720p · 请求60帧 · 含预览（对照）", Size(1280, 720), 60),
    AUTOMATIC("自动选择60或30帧（旧配置）", null, null),
    HD120_HIGH_SPEED("720p · 请求120帧 · 高速实验", Size(1280, 720), 120, true, true)
}

data class CameraConfig(val id: String, val size: Size, val range: Range<Int>,
    val targetFps: Int, val realtime: Boolean, val sensorOrientation: Int,
    val recorderMinFrameDurationNs: Long = 0, val previewMinFrameDurationNs: Long = 0,
    val fpsIsSessionKey: Boolean = false, val previewEnabled: Boolean = true,
    val highSpeed: Boolean = false, val mode: CameraTestMode = CameraTestMode.AUTOMATIC)

data class CameraCapabilities(val id: String, val recorderSizes: Map<Size, Long>,
    val previewSizes: Map<Size, Long>, val normalRanges: List<Range<Int>>,
    val highSpeedRanges: Map<Size, List<Range<Int>>>, val realtime: Boolean,
    val sensorOrientation: Int, val fpsIsSessionKey: Boolean)

object CameraModes {
    /** Null means this explicit mode is not advertised; never substitute a different experiment. */
    fun select(cameras: List<CameraCapabilities>, mode: CameraTestMode): CameraConfig? {
        val options = cameras.flatMap { camera ->
            camera.recorderSizes.keys.filter { size ->
                size.width <= 1920 && size.height <= 1080 && (mode.size == null || size == mode.size) &&
                    (!mode.previewEnabled || size in camera.previewSizes)
            }.flatMap { size ->
                val targets = mode.fps?.let { listOf(it) } ?: listOf(60, 30)
                targets.flatMap { fps ->
                    val ranges = if (mode.highSpeed) camera.highSpeedRanges[size].orEmpty() else camera.normalRanges
                    ranges.filter { range ->
                        if (mode.highSpeed) range.lower == fps && range.upper == fps
                        else range.contains(fps) && range.upper == fps && DeviceReport.supportsFrameRate(fps,
                            camera.recorderSizes.getValue(size), if (mode.previewEnabled) camera.previewSizes.getValue(size) else 0)
                    }.map { range ->
                        CameraConfig(camera.id, size, range, fps, camera.realtime, camera.sensorOrientation,
                            camera.recorderSizes.getValue(size), camera.previewSizes[size] ?: 0,
                            camera.fpsIsSessionKey, mode.previewEnabled, mode.highSpeed, mode)
                    }
                }
            }
        }
        return options.sortedWith(compareByDescending<CameraConfig> { it.targetFps }
            .thenBy { if (it.size == Size(1280, 720)) 0 else 1 }
            .thenByDescending { it.range.lower }.thenByDescending { it.size.width * it.size.height }).firstOrNull()
    }
}
