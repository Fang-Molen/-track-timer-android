package cn.tracktimer.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import cn.tracktimer.core.FrameAnalysis
import cn.tracktimer.core.ResourceGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Owns a single short recording. All camera state is confined to the worker looper. */
class CameraProbe(private val context: Context, private val store: ReportStore,
    private val texture: TextureView, private val onStatus: (String) -> Unit,
    private val onDone: (JSONObject) -> Unit,
    private val mode: CameraTestMode = CameraTestMode.AUTOMATIC) {
    private val worker = HandlerThread("camera-probe").apply { start() }
    private val handler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private val cameraGate = ResourceGate<CameraDevice> { it.close() }
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var preview: Surface? = null
    private var recording = false
    @Volatile private var finished = false
    private var config: CameraConfig? = null
    private var video: File? = null
    private var startedNs = 0L
    private var failedCaptures = 0
    private var sessionParametersSubmitted = false
    private val frames = JSONArray()
    private val times = ArrayList<Long>()
    private val autoStop = Runnable { finish(null) }
    private val startupTimeout = Runnable { finish("等待相机打开或配置超时，请换一个模式并导出日志。") }
    private var configuredOutputCount = 0
    private var requestCaptureIntent: Int? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val surfaceTexture = if (mode.previewEnabled) texture.surfaceTexture else null
        if (mode.previewEnabled && (!texture.isAvailable || surfaceTexture == null)) { stop("预览尚未就绪，请重试。"); return }
        handler.post {
            try {
                if (finished) return@post
                handler.postDelayed(startupTimeout, 8_000)
                check(store.directory.usableSpace > 100L * 1024 * 1024) { "存储空间不足，请至少保留 100 MB。" }
                val c = DeviceReport.cameraConfig(context, mode); config = c
                val file = store.newFile("video", "pending"); video = file
                if (c.previewEnabled) {
                    checkNotNull(surfaceTexture).setDefaultBufferSize(c.size.width, c.size.height)
                    preview = Surface(surfaceTexture)
                }
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                recorder = r
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(file.absolutePath)
                r.setVideoEncodingBitRate(when { c.highSpeed -> 16_000_000; c.size.width >= 1920 -> 12_000_000; c.targetFps >= 60 -> 8_000_000; else -> 4_000_000 })
                r.setVideoFrameRate(c.targetFps)
                r.setVideoSize(c.size.width, c.size.height)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                r.setOrientationHint(c.sensorOrientation)
                r.prepare()
                status("${mode.label}\n正在打开后置相机${if (!c.previewEnabled) "；此模式不显示预览" else ""}")
                context.getSystemService(CameraManager::class.java).openCamera(c.id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!cameraGate.accept(device)) return
                        if (!handler.post { if (!finished) configure(device, c, r) }) cameraGate.close()
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); stop("相机连接中断") }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); stop("相机错误：$error") }
                // The main looper outlives this probe: late onOpened must still close a cancelled device.
                }, main)
            } catch (e: Exception) { finish(e.message ?: "无法打开相机") }
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(device: CameraDevice, c: CameraConfig, r: MediaRecorder) {
        try {
            val outputs = CameraSessionSetup.outputs(c, r.surface, preview)
            configuredOutputCount = outputs.size
            val request = CameraSessionSetup.recordRequest(device, c, outputs)
            requestCaptureIntent = request[CaptureRequest.CONTROL_CAPTURE_INTENT]
            sessionParametersSubmitted = CameraSessionSetup.open(device, outputs, request, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (finished) { s.close(); return }
                    session = s
                    try {
                        CameraSessionSetup.repeat(s, request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                if (!recording || finished) return
                                val sensor = result[CaptureResult.SENSOR_TIMESTAMP]
                                if (sensor != null) times.add(sensor)
                                frames.put(JSONObject().put("frameNumber", result.frameNumber)
                                    .put("sensorNs", sensor ?: JSONObject.NULL)
                                    .put("callbackElapsedNs", SystemClock.elapsedRealtimeNanos())
                                    .put("exposureNs", result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: JSONObject.NULL)
                                    .put("frameDurationNs", result[CaptureResult.SENSOR_FRAME_DURATION] ?: JSONObject.NULL)
                                    .put("aeTargetFpsRange", result[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]?.toString() ?: JSONObject.NULL)
                                    .put("aeMode", result[CaptureResult.CONTROL_AE_MODE] ?: JSONObject.NULL)
                                    .put("controlMode", result[CaptureResult.CONTROL_MODE] ?: JSONObject.NULL)
                                    .put("sceneMode", result[CaptureResult.CONTROL_SCENE_MODE] ?: JSONObject.NULL)
                                    .put("captureIntent", result[CaptureResult.CONTROL_CAPTURE_INTENT] ?: JSONObject.NULL)
                                    .put("videoStabilizationMode", result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE] ?: JSONObject.NULL)
                                    .put("rollingShutterSkewNs", result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW] ?: JSONObject.NULL))
                                if (frames.length() == 1) status("${mode.label}\n正在录制10秒；${if (c.previewEnabled) "已收到相机帧" else "仅录像，不显示预览"}。")
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) { failedCaptures++ }
                        }, handler, c.highSpeed)
                        r.start(); startedNs = SystemClock.elapsedRealtimeNanos(); recording = true
                        handler.removeCallbacks(startupTimeout)
                        handler.postDelayed(autoStop, 10_000)
                    } catch (e: Exception) { finish(e.message ?: "无法开始录像") }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { finish("此分辨率/帧率组合无法启动，请导出设备报告。") }
            }, handler, c.highSpeed)
        } catch (e: Exception) { finish(e.message ?: "录像配置失败") }
    }

    fun stop(reason: String? = null) { handler.post { finish(reason) } }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(autoStop); handler.removeCallbacks(startupTimeout)
        var problem = error
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        if (recording) runCatching { recorder?.stop() }.onFailure { problem = problem ?: "录像未完整保存：${it.message}" }
        else if (problem == null) problem = "录像尚未开始"
        recording = false
        runCatching { session?.close() }; runCatching { cameraGate.close() }
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }; runCatching { preview?.release() }
        val report = JSONObject().put("kind", "camera_probe").put("testMode", mode.name).put("testModeLabel", mode.label)
            .put("requestedFps", config?.targetFps ?: mode.fps ?: JSONObject.NULL)
            .put("size", config?.size?.toString() ?: mode.size?.toString() ?: JSONObject.NULL).put("cameraId", config?.id ?: JSONObject.NULL)
            .put("previewEnabled", mode.previewEnabled).put("outputSurfaceCount", configuredOutputCount)
            .put("sessionType", if (mode.highSpeed) "constrained_high_speed" else "regular")
            .put("highSpeedBatchTimestampsUnverified", mode.highSpeed)
            .put("requestCaptureIntent", requestCaptureIntent ?: JSONObject.NULL)
            .put("cameraClockRealtime", config?.realtime ?: false)
            .put("recordStartCallbackElapsedNs", startedNs).put("failedCaptures", failedCaptures)
            .put("requestedAeFpsRange", config?.range?.toString() ?: JSONObject.NULL)
            .put("sessionParametersSubmitted", sessionParametersSubmitted)
            .put("fpsIsSessionKey", config?.fpsIsSessionKey ?: false)
            .put("previewMinFrameDurationNs", config?.previewMinFrameDurationNs ?: JSONObject.NULL)
            .put("recorderMinFrameDurationNs", config?.recorderMinFrameDurationNs ?: JSONObject.NULL)
            .put("frames", frames).put("precisionVerified", false)
        try {
            val stats = FrameAnalysis.analyze(times, config?.targetFps ?: 60)
            report.put("captureFps", stats.measuredFps ?: JSONObject.NULL)
                .put("maxFrameGapNs", stats.maxGapNs).put("p95FrameGapNs", stats.p95GapNs)
                .put("estimatedMissingCaptureFrames", stats.estimatedMissingFrames)
        } catch (e: Exception) { report.put("timestampError", e.message) }
        video?.let { pending ->
            if (problem == null && pending.length() > 0) {
                val completed = File(pending.parentFile, pending.nameWithoutExtension + ".mp4")
                if (pending.renameTo(completed)) {
                    report.put("videoFile", completed.name)
                    runCatching { inspectEncodedVideo(completed, report) }.onFailure { report.put("encodedInspectionError", it.message) }
                } else problem = "无法保存录像文件"
            }
            // Partial files never masquerade as a completed recording or enter exports.
            if (pending.exists()) pending.delete()
        }
        updateFrameRateStatus(report)
        report.put("error", problem ?: JSONObject.NULL)
        runCatching { store.saveJson("camera", report) }.onFailure { report.put("saveError", it.message) }
        main.post { onDone(report) }
        worker.quitSafely()
    }

    private fun inspectEncodedVideo(file: File, report: JSONObject) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("MP4 中没有视频轨道")
            extractor.selectTrack(track)
            val pts = ArrayList<Long>()
            while (extractor.sampleTime >= 0 && pts.size < 10000) {
                pts.add(extractor.sampleTime * 1000)
                if (!extractor.advance()) break
            }
            val analysis = FrameAnalysis.analyze(pts.sorted(), config?.targetFps ?: 60)
            report.put("encodedFrameCount", pts.size).put("encodedFps", analysis.measuredFps ?: JSONObject.NULL)
                .put("encodedMaxGapNs", analysis.maxGapNs).put("encodedPtsNs", JSONArray(pts))
                .put("captureToEncodedMappingVerified", false)
        } finally { extractor.release() }
    }

    private fun status(message: String) { main.post { onStatus(message) } }

    companion object {
        internal fun updateFrameRateStatus(report: JSONObject) {
            val requested = report.optInt("requestedFps", 0)
            fun reached(key: String): Boolean {
                val measured = report.optDouble(key)
                return requested > 0 && measured.isFinite() && measured >= requested * .9
            }
            val capture = reached("captureFps")
            val encoded = reached("encodedFps")
            report.put("captureFpsAchieved", capture).put("encodedFpsAchieved", encoded)
                .put("requestedFpsAchieved", capture && encoded)
        }
    }
}
