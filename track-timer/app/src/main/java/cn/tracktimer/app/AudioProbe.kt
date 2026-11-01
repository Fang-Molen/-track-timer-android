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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioProbe(private val context: Context, private val store: ReportStore,
    private val onStatus: (String) -> Unit, private val onDone: (JSONObject) -> Unit) {
    @Volatile private var cancelled = false
    private val main = Handler(Looper.getMainLooper())
    fun cancel() { cancelled = true }

    @SuppressLint("MissingPermission")
    fun start() {
        Thread({
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            val report = JSONObject().put("kind", "audio_probe").put("precisionVerified", false)
            try {
                val sampleRate = listOf(48_000, 44_100).firstOrNull {
                    AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
                } ?: error("没有可用的单声道 PCM 录音配置")
                val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val manager = context.getSystemService(AudioManager::class.java)
                val unprocessed = manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
                recorder = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes((minBuffer * 4).coerceAtLeast(sampleRate / 5 * 2)).build()
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "录音初始化失败" }
                val tone = ShortArray((sampleRate * .15).toInt()) {
                    // A short fade avoids a loud click while retaining a narrow onset region.
                    val envelope = (it / (sampleRate * .002)).coerceAtMost(1.0)
                    (10000 * envelope * sin(2 * PI * 1800 * it / sampleRate)).toInt().toShort()
                }
                player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.size * 2).build()
                check(player.state == AudioTrack.STATE_NO_STATIC_DATA || player.state == AudioTrack.STATE_INITIALIZED)
                check(player.write(tone, 0, tone.size) == tone.size) { "测试音写入失败" }
                val pcm = ShortArray(sampleRate * 5)
                val anchors = ArrayList<Pair<Long, Long>>()
                val timestampLog = JSONArray()
                var total = 0; var played = false; var requestNs: Long? = null
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风未开始录音" }
                status("正在录音 5 秒 · 稍后播放短提示音\n请保持安静，并保持 App 在前台。")
                report.put("recordStartCallbackElapsedNs", SystemClock.elapsedRealtimeNanos())
                while (total < pcm.size && !cancelled) {
                    val read = recorder.read(pcm, total, minOf(sampleRate / 50, pcm.size - total), AudioRecord.READ_BLOCKING)
                    check(read > 0) { "录音读取失败：$read" }
                    total += read
                    val timestamp = AudioTimestamp()
                    val result = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                    timestampLog.put(JSONObject().put("readFrames", total).put("status", result)
                        .put("framePosition", if (result == AudioRecord.SUCCESS) timestamp.framePosition else JSONObject.NULL)
                        .put("timestampNs", if (result == AudioRecord.SUCCESS) timestamp.nanoTime else JSONObject.NULL)
                        .put("callbackElapsedNs", SystemClock.elapsedRealtimeNanos()))
                    if (result == AudioRecord.SUCCESS && timestamp.nanoTime > 0 && timestamp.framePosition >= 0)
                        anchors.add(timestamp.framePosition to timestamp.nanoTime)
                    if (!played && total >= sampleRate * 3 / 2 && !cancelled) {
                        requestNs = SystemClock.elapsedRealtimeNanos(); player.play(); played = true
                    }
                }
                val captured = pcm.copyOf(total)
                val candidate = if (cancelled) null else ToneDetector.detect(captured, sampleRate, 1800.0)
                val anchor = candidate?.let { c -> anchors.minByOrNull { abs(it.first - c.sampleIndex.toLong()) } }
                val candidateNs = if (candidate != null && anchor != null)
                    anchor.second + (candidate.sampleIndex.toLong() - anchor.first) * 1_000_000_000L / sampleRate else null
                val pending = store.newFile("audio", "pending")
                writeWav(pending, captured, sampleRate)
                val wav = File(pending.parentFile, pending.nameWithoutExtension + ".wav")
                check(pending.renameTo(wav)) { "无法保存 WAV 文件" }
                report.put("sampleRate", sampleRate).put("recordedFrames", total).put("audioFile", wav.name)
                    .put("audioSource", source).put("unprocessed", unprocessed)
                    .put("playRequestElapsedNs", requestNs ?: JSONObject.NULL)
                    .put("hardwareTimestampCount", anchors.size).put("timestamps", timestampLog)
                    .put("candidateSample", candidate?.sampleIndex ?: JSONObject.NULL)
                    .put("candidateCaptureElapsedNs", candidateNs ?: JSONObject.NULL)
                    .put("candidateCoherence", candidate?.coherence ?: JSONObject.NULL)
                    .put("candidateIsVerifiedStart", false).put("cancelled", cancelled)
                    .put("inputDeviceType", recorder.routedDevice?.type ?: JSONObject.NULL)
                    .put("outputDeviceType", player.routedDevice?.type ?: JSONObject.NULL)
                    .put("musicVolume", manager.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("note", "声信号候选仅供验证；尚未测定声学传播/采集偏差，也未排除环境中同频声音。")
            } catch (e: Exception) { report.put("error", e.message ?: e.javaClass.simpleName) }
            finally {
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }
                runCatching { player?.stop() }; runCatching { player?.release() }
                runCatching { store.saveJson("audio", report) }.onFailure { report.put("saveError", it.message) }
                main.post { onDone(report) }
            }
        }, "audio-probe").start()
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
