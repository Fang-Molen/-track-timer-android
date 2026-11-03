package cn.tracktimer.app

import android.os.Build
import android.util.Range
import android.util.Size

enum class CameraTestMode(val label: String, val size: Size?, val fps: Int?,
    val previewEnabled: Boolean = true, val highSpeed: Boolean = false, val captureOnly: Boolean = false,
    val videoUseCase: Boolean = false, val manual: Boolean = false, val clockCalibration: Boolean = false) {
    FHD60_PREVIEW("1080p · 请求60帧 · 含预览", Size(1920, 1080), 60),
    HD60_RECORD_ONLY("720p · 请求60帧 · 仅录像", Size(1280, 720), 60, false),
    FHD60_RECORD_ONLY("1080p · 请求60帧 · 仅录像", Size(1920, 1080), 60, false),
    HD60_PREVIEW("720p · 请求60帧 · 含预览（对照）", Size(1280, 720), 60),
    AUTOMATIC("自动选择60或30帧（旧配置）", null, null),
    HD120_HIGH_SPEED("720p · 请求120帧 · 高速实验", Size(1280, 720), 120, true, true),
    HD60_CAPTURE_ONLY("720p · 请求60帧 · 纯采集（不存视频）", Size(1280, 720), 60, false, false, true),
    FHD60_CAPTURE_ONLY("1080p · 请求60帧 · 纯采集（不存视频）", Size(1920, 1080), 60, false, false, true),
    HD120_RECORD_ONLY("720p · 请求120帧 · 高速仅录像", Size(1280, 720), 120, false, true),
    HD60_VIDEO_USE_CASE("720p · 60帧 · 录像用途/自动曝光", Size(1280, 720), 60, false, videoUseCase = true),
    FHD60_VIDEO_USE_CASE("1080p · 60帧 · 录像用途/自动曝光", Size(1920, 1080), 60, false, videoUseCase = true),
    HD60_MANUAL("720p · 60帧 · 默认用途/手动曝光", Size(1280, 720), 60, false, manual = true),
    FHD60_MANUAL("1080p · 60帧 · 默认用途/手动曝光", Size(1920, 1080), 60, false, manual = true),
    HD60_MANUAL_VIDEO_USE_CASE("720p · 60帧 · 录像用途/手动曝光", Size(1280, 720), 60, false, videoUseCase = true, manual = true),
    FHD60_MANUAL_VIDEO_USE_CASE("1080p · 60帧 · 录像用途/手动曝光", Size(1920, 1080), 60, false, videoUseCase = true, manual = true),
    HD120_CLOCK("720p · 120帧 · 时钟校准", Size(1280,720),120,false,true,clockCalibration=true),
    HD240_CLOCK("720p · 240帧 · 时钟校准", Size(1280,720),240,false,true,clockCalibration=true)
}

data class ManualSensorSettings(val exposureNs: Long, val frameDurationNs: Long, val sensitivityIso: Int) {
    init { require(exposureNs > 0 && exposureNs <= frameDurationNs && sensitivityIso > 0) }
}

data class CameraConfig(val id: String, val size: Size, val range: Range<Int>,
    val targetFps: Int, val realtime: Boolean, val sensorOrientation: Int,
    val recorderMinFrameDurationNs: Long = 0, val previewMinFrameDurationNs: Long = 0,
    val fpsIsSessionKey: Boolean = false, val previewEnabled: Boolean = true,
    val highSpeed: Boolean = false, val mode: CameraTestMode = CameraTestMode.AUTOMATIC,
    val captureOnly: Boolean = false, val privateMinFrameDurationNs: Long = 0,
    val streamUseCase: Long = 0, val manualSensor: ManualSensorSettings? = null)

data class CameraCapabilities(val id: String, val recorderSizes: Map<Size, Long>,
    val previewSizes: Map<Size, Long>, val normalRanges: List<Range<Int>>,
    val highSpeedRanges: Map<Size, List<Range<Int>>>, val realtime: Boolean,
    val sensorOrientation: Int, val fpsIsSessionKey: Boolean, val privateSizes: Map<Size, Long> = emptyMap(),
    val streamUseCases: Set<Long> = emptySet(), val manualSensorSupported: Boolean = false,
    val exposureRangeNs: Range<Long>? = null, val sensitivityRange: Range<Int>? = null, val maxFrameDurationNs: Long = 0, val encoderSizes: Map<Size, Long> = emptyMap())

object CameraModes {
    /** Null means this explicit mode is not advertised; never substitute a different experiment. */
    fun select(cameras: List<CameraCapabilities>, mode: CameraTestMode, forcedCameraId: String? = null): CameraConfig? {
        fun manualSettings(camera: CameraCapabilities): ManualSensorSettings? {
            val exposure = 8_000_000L
            val period = 16_666_667L
            val iso = camera.sensitivityRange ?: return null
            if (!camera.manualSensorSupported || camera.exposureRangeNs?.contains(exposure) != true ||
                camera.maxFrameDurationNs < period || iso.lower <= 0) return null
            return ManualSensorSettings(exposure, period, 800.coerceIn(iso.lower, iso.upper))
        }
        val options = cameras.filter { forcedCameraId == null || it.id == forcedCameraId }.flatMap { camera ->
            if (mode.videoUseCase && (Build.VERSION.SDK_INT < 33 || 3L !in camera.streamUseCases)) return@flatMap emptyList()
            val manual = if (mode.manual) manualSettings(camera) ?: return@flatMap emptyList() else null
            val sizes = when { mode.clockCalibration -> camera.encoderSizes; mode.captureOnly -> camera.privateSizes; else -> camera.recorderSizes }
            sizes.keys.filter { size ->
                size.width <= 1920 && size.height <= 1080 && (mode.size == null || size == mode.size) &&
                    (!mode.previewEnabled || size in camera.previewSizes)
            }.flatMap { size ->
                val targets = mode.fps?.let { listOf(it) } ?: listOf(60, 30)
                targets.flatMap { fps ->
                    val ranges = if (mode.highSpeed) camera.highSpeedRanges[size].orEmpty() else camera.normalRanges
                    ranges.filter { range ->
                        if (mode.highSpeed) range.lower == fps && range.upper == fps
                        else range.contains(fps) && range.upper == fps && DeviceReport.supportsFrameRate(fps,
                            sizes.getValue(size), if (mode.previewEnabled) camera.previewSizes.getValue(size) else 0)
                    }.map { range ->
                        CameraConfig(camera.id, size, range, fps, camera.realtime, camera.sensorOrientation,
                            camera.recorderSizes[size] ?: 0, camera.previewSizes[size] ?: 0,
                            camera.fpsIsSessionKey, mode.previewEnabled, mode.highSpeed, mode, mode.captureOnly,
                            camera.privateSizes[size] ?: 0, if (mode.videoUseCase) 3L else 0L, manual)
                    }
                }
            }
        }
        return options.sortedWith(compareByDescending<CameraConfig> { it.targetFps }
            .thenBy { if (it.size == Size(1280, 720)) 0 else 1 }
            .thenByDescending { it.range.lower }.thenByDescending { it.size.width * it.size.height }).firstOrNull()
    }
}
