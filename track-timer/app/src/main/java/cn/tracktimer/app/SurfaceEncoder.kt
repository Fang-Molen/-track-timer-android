package cn.tracktimer.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Confined to CameraProbe's worker; owns partial setup and drains a bounded number per poll. */
class SurfaceEncoder(sampleLimit:Int = 6000) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var input: Surface? = null
    private var started = false
    private var muxStarted = false
    private var track = -1
    private var eos = false
    private var closed = false
    private var name: String? = null
    private var finalReport: JSONObject? = null
    private val samples = EncodedSamples(sampleLimit)
    val sampleCount:Int get() = samples.ptsNs.size
    private val info = MediaCodec.BufferInfo()
    val surface: Surface get() = checkNotNull(input)

    fun prepare(config: CameraConfig, file: File) {
        check(codec == null && !closed)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,config.size.width,config.size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,if (config.targetFps >= 240) 32_000_000 else 16_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE,config.targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            // Baseline prevents B-frame reordering; do not silently reorder timestamps in analysis.
            setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("没有声明支持${config.size}/${config.targetFps}帧的AVC Surface编码器")
        val c = MediaCodec.createByCodecName(checkNotNull(name)); codec = c
        c.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = c.createInputSurface()
        val m = MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = m
        m.setOrientationHint(config.sensorOrientation)
        c.start(); started = true
    }

    @Suppress("DEPRECATION")
    fun poll(timeoutUs: Long = 0) {
        val c = checkNotNull(codec)
        repeat(64) {
            if (eos) return
            when (val index = c.dequeueOutputBuffer(info,timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxStarted) { "编码输出格式重复变化" }
                    track = checkNotNull(muxer).addTrack(c.outputFormat)
                    checkNotNull(muxer).start(); muxStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    eos = EncodedOutput.consume(samples,{c.getOutputBuffer(index)},info,SystemClock.elapsedRealtimeNanos(),
                        { data, value ->
                            check(muxStarted) { "视频缓冲区先于编码格式返回" }
                            checkNotNull(muxer).writeSampleData(track,data,value)
                        },{c.releaseOutputBuffer(index,false)})
                }
            }
        }
    }

    fun finish(): JSONObject {
        finalReport?.let { return it }
        var error: String? = null
        fun attempt(action: () -> Unit) { runCatching(action).onFailure { if (error == null) error = it.message ?: it.javaClass.simpleName } }
        if (started) attempt {
            checkNotNull(codec).signalEndOfInputStream()
            val deadline = SystemClock.elapsedRealtimeNanos()+2_000_000_000L
            while (!eos && SystemClock.elapsedRealtimeNanos()<deadline) poll(10_000)
            check(eos) { "等待编码结束超时" }
            samples.checkComplete(eos)
        } else error = "编码器尚未开始"
        if (muxStarted) attempt { checkNotNull(muxer).stop() }
        if (started) attempt { codec?.stop() }
        attempt { codec?.release() }; codec = null
        attempt { input?.release() }; input = null
        attempt { muxer?.release() }; muxer = null
        closed = true
        return JSONObject().put("codecName",name ?: JSONObject.NULL)
            .put("codecPtsNs",JSONArray(samples.ptsNs)).put("codecObservedElapsedNs",JSONArray(samples.observedNs))
            .put("codecEosReached",eos).put("codecError",error ?: JSONObject.NULL)
            .put("codecFrameCount",samples.ptsNs.size)
            .put("muxOriginCodecPtsNs",samples.ptsNs.firstOrNull() ?: JSONObject.NULL)
            .also { finalReport = it }
    }
}
