package cn.tracktimer.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.tracktimer.core.ToneDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioProbe(private val context: Context, private val store: ReportStore,
    private val onStatus: (String) -> Unit, private val onDone: (JSONObject) -> Unit,
    private val playbackOnly: Boolean = false) {
    @Volatile private var cancelled = false
    @Volatile private var player: TestTonePlayer? = null
    private val main = Handler(Looper.getMainLooper())
    fun cancel() { cancelled = true; player?.close() }

    fun start() {
        Thread({
            val report = JSONObject().put("kind", if (playbackOnly) "playback_probe" else "audio_probe")
                .put("precisionVerified", false)
            try {
                if (cancelled) return@Thread
                val sampleRate = if (playbackOnly) 48_000 else listOf(48_000, 44_100).firstOrNull {
                    AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
                } ?: error("没有可用的单声道 PCM 录音配置")
                val tone = TestTonePlayer(context, sampleRate)
                player = tone
                if (cancelled) return@Thread
                tone.prepare()
                if (cancelled) return@Thread
                if (playbackOnly) {
                    status("仅播放短提示音，不使用麦克风。请留意是否听到声音。")
                    tone.play()
                    val deadline = SystemClock.elapsedRealtime() + 1000
                    while (!cancelled && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                } else record(tone, sampleRate, report)
                val playback = tone.snapshot()
                if (!cancelled && playback.optBoolean("playRequested")) {
                    if (playback.optBoolean("focusLost")) report.put("error", "测试中失去音频焦点，播放已暂停，请重试。")
                    else if (playback.optLong("playbackHeadFrames") == 0L)
                        report.put("error", "已请求播放，但系统未报告播放进展；请导出诊断报告。")
                }
            } catch (e: Exception) { report.put("error", e.message ?: e.javaClass.simpleName) }
            finally {
                player?.let { tone ->
                    runCatching {
                        val playback = tone.snapshot()
                        report.put("playback", playback)
                            .put("playRequestElapsedNs", playback.opt("playRequestElapsedNs") ?: JSONObject.NULL)
                            .put("outputDeviceType", playback.opt("outputDeviceType") ?: JSONObject.NULL)
                            .put("musicVolume", playback.opt("mediaVolume") ?: JSONObject.NULL)
                    }.onFailure { report.put("playbackInspectionError", it.message) }
                    tone.close()
                }
                report.put("cancelled", cancelled)
                runCatching { store.saveJson(if (playbackOnly) "playback" else "audio", report) }.onFailure { report.put("saveError", it.message) }
                main.post { onDone(report) }
            }
        }, "audio-probe").start()
    }

    @SuppressLint("MissingPermission")
    private fun record(tone: TestTonePlayer, sampleRate: Int, report: JSONObject) {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val manager = context.getSystemService(AudioManager::class.java)
        val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        var recorder: AudioRecord? = null
        val pcm = ShortArray(sampleRate * 5)
        val anchors = ArrayList<Pair<Long, Long>>()
        val timestampLog = JSONArray()
        var total = 0
        var completed = false
        report.put("sampleRate", sampleRate).put("audioSource", source).put("unprocessed", unprocessed)
        try {
            val input = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(sampleRate / 5 * 2)).build()
            recorder = input
            check(input.state == AudioRecord.STATE_INITIALIZED) { "录音初始化失败" }
            if (cancelled) return
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音" }
            status("正在录音 5 秒 · 稍后播放短提示音\n请保持安静，并保持 App 在前台。")
            report.put("recordStartCallbackElapsedNs", SystemClock.elapsedRealtimeNanos())
            var played = false
            while (total < pcm.size && !cancelled) {
                val read = input.read(pcm, total, minOf(sampleRate / 50, pcm.size - total), AudioRecord.READ_BLOCKING)
                check(read > 0) { "录音读取失败：$read" }
                total += read
                val timestamp = AudioTimestamp()
                val result = input.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                timestampLog.put(JSONObject().put("readFrames", total).put("status", result)
                    .put("framePosition", if (result == AudioRecord.SUCCESS) timestamp.framePosition else JSONObject.NULL)
                    .put("timestampNs", if (result == AudioRecord.SUCCESS) timestamp.nanoTime else JSONObject.NULL)
                    .put("callbackElapsedNs", SystemClock.elapsedRealtimeNanos()))
                if (result == AudioRecord.SUCCESS && timestamp.nanoTime > 0 && timestamp.framePosition >= 0)
                    anchors.add(timestamp.framePosition to timestamp.nanoTime)
                if (!played && total >= sampleRate * 3 / 2 && !cancelled) { tone.play(); played = true }
            }
            completed = total == pcm.size && !cancelled
        } finally {
            report.put("inputDeviceType", runCatching { recorder?.routedDevice?.type }.getOrNull() ?: JSONObject.NULL)
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            // Preserve partial samples and their timestamps even if a later read or playback fails.
            val captured = pcm.copyOf(total)
            val candidate = if (completed && !cancelled) ToneDetector.detect(captured, sampleRate, 1800.0) else null
            val anchor = candidate?.let { c -> anchors.minByOrNull { abs(it.first - c.sampleIndex.toLong()) } }
            val candidateNs = if (candidate != null && anchor != null)
                anchor.second + (candidate.sampleIndex.toLong() - anchor.first) * 1_000_000_000L / sampleRate else null
            report.put("recordedFrames", total).put("hardwareTimestampCount", anchors.size).put("timestamps", timestampLog)
                .put("candidateSample", candidate?.sampleIndex ?: JSONObject.NULL)
                .put("candidateCaptureElapsedNs", candidateNs ?: JSONObject.NULL)
                .put("candidateCoherence", candidate?.coherence ?: JSONObject.NULL)
                .put("candidateIsVerifiedStart", false)
                .put("note", "声信号候选仅供验证；尚未测定声学传播/采集偏差，也未排除环境中同频声音。")
            if (total > 0) runCatching {
                val pending = store.newFile("audio", "pending")
                writeWav(pending, captured, sampleRate)
                val wav = File(pending.parentFile, pending.nameWithoutExtension + ".wav")
                check(pending.renameTo(wav)) { "无法保存 WAV 文件" }
                report.put("audioFile", wav.name)
            }.onFailure { report.put("audioSaveError", it.message) }
        }
    }

    private fun writeWav(file: File, pcm: ShortArray, sampleRate: Int) {
        val bytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes)
        FileOutputStream(file).use { output ->
            output.write(header.array())
            val data = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { data.putShort(it) }; output.write(data.array()); output.fd.sync()
        }
    }
    private fun status(message: String) { main.post { onStatus(message) } }
}
