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
    private val onDone: (JSONObject) -> Unit) {
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

    @SuppressLint("MissingPermission")
    fun start() {
        val surfaceTexture = texture.surfaceTexture
        if (!texture.isAvailable || surfaceTexture == null) { stop("预览尚未就绪，请重试。"); return }
        handler.post {
            try {
                check(store.directory.usableSpace > 100L * 1024 * 1024) { "存储空间不足，请至少保留 100 MB。" }
                val c = DeviceReport.cameraConfig(context); config = c
                val file = store.newFile("video", "pending"); video = file
                surfaceTexture.setDefaultBufferSize(c.size.width, c.size.height)
                preview = Surface(surfaceTexture)
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                recorder = r
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setOutputFile(file.absolutePath)
                r.setVideoEncodingBitRate(if (c.targetFps >= 60) 8_000_000 else 4_000_000)
                r.setVideoFrameRate(c.targetFps)
                r.setVideoSize(c.size.width, c.size.height)
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                r.setOrientationHint(c.sensorOrientation)
                r.prepare()
                status("正在打开后置相机 · 请求 ${c.targetFps} fps")
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
            val outputs = listOf(preview!!, r.surface)
            val request = CameraSessionSetup.recordRequest(device, c, outputs)
            sessionParametersSubmitted = CameraSessionSetup.open(device, outputs, request, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (finished) { s.close(); return }
                    session = s
                    try {
                        s.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
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
                                    .put("rollingShutterSkewNs", result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW] ?: JSONObject.NULL))
                                if (frames.length() == 1) status("正在录制 10 秒 · 请求 ${c.targetFps} fps\n已收到真实相机帧；可同时测试发令音。")
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) { failedCaptures++ }
                        }, handler)
                        r.start(); startedNs = SystemClock.elapsedRealtimeNanos(); recording = true
                        handler.postDelayed(autoStop, 10_000)
                    } catch (e: Exception) { finish(e.message ?: "无法开始录像") }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { finish("此分辨率/帧率组合无法启动，请导出设备报告。") }
            }, handler)
        } catch (e: Exception) { finish(e.message ?: "录像配置失败") }
    }

    fun stop(reason: String? = null) { handler.post { finish(reason) } }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(autoStop)
        var problem = error
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        if (recording) runCatching { recorder?.stop() }.onFailure { problem = problem ?: "录像未完整保存：${it.message}" }
        else if (problem == null) problem = "录像尚未开始"
        recording = false
        runCatching { session?.close() }; runCatching { cameraGate.close() }
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }; runCatching { preview?.release() }
        val report = JSONObject().put("kind", "camera_probe").put("requestedFps", config?.targetFps)
            .put("size", config?.size?.toString()).put("cameraId", config?.id)
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
