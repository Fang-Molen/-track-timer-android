package cn.tracktimer.app

import android.app.NotificationManager
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** One short media tone. Playback progress is diagnostic, never proof of an acoustic start. */
class TestTonePlayer(context: Context, private val sampleRate: Int) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var track: AudioTrack? = null
    private var closed = false
    private var focusHeld = false
    private var focusLost = false
    private val data = JSONObject().put("audioUsage", AudioAttributes.USAGE_MEDIA)
        .put("volumeStream", AudioManager.STREAM_MUSIC).put("sampleRate", sampleRate)
        .put("expectedFrames", (sampleRate * .15).toInt()).put("playRequested", false)
        .put("audibleVerified", false).put("playbackHeadFrames", 0L)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes()).setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            synchronized(this) {
                data.put("lastFocusChange", change)
                if (change < 0) {
                    focusLost = true
                    runCatching { updateTrackData(); track?.pause() }
                }
            }
        }, Handler(Looper.getMainLooper())).build()

    @Synchronized fun prepare() {
        check(!closed) { "试听已取消" }
        check(track == null) { "播放器已准备" }
        updateEnvironment()
        check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 && !manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            "媒体音量为零或已静音，请按音量键调高媒体音量后重试。"
        }
        val result = manager.requestAudioFocus(focusRequest)
        data.put("focusRequestResult", result)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        check(focusHeld) { "系统未授予音频焦点，请结束通话或其他音频播放后重试。" }
        val tone = ShortArray((sampleRate * .15).toInt()) {
            val envelope = (it / (sampleRate * .002)).coerceAtMost(1.0)
            (10000 * envelope * sin(2 * PI * 1800 * it / sampleRate)).toInt().toShort()
        }
        val player = AudioTrack.Builder().setAudioAttributes(audioAttributes())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.size * 2).build()
        track = player
        check(player.state == AudioTrack.STATE_NO_STATIC_DATA || player.state == AudioTrack.STATE_INITIALIZED) { "播放器初始化失败" }
        check(player.write(tone, 0, tone.size) == tone.size) { "测试音写入失败" }
        check(player.setVolume(1f) == AudioTrack.SUCCESS) { "无法设置播放器音量" }
        data.put("trackInitialized", player.state == AudioTrack.STATE_INITIALIZED)
    }

    @Synchronized fun play() {
        check(!closed) { "试听已取消" }
        check(!focusLost) { "播放前失去音频焦点，请重试。" }
        val player = checkNotNull(track) { "播放器尚未准备" }
        data.put("playRequestElapsedNs", SystemClock.elapsedRealtimeNanos()).put("playRequested", true)
        player.play()
        updateTrackData()
    }

    @Synchronized fun snapshot(): JSONObject {
        updateEnvironment()
        runCatching { updateTrackData() }.onFailure { data.put("playbackInspectionError", it.message) }
        data.put("focusLost", focusLost).put("closed", closed)
        return JSONObject(data.toString())
    }

    private fun updateEnvironment() {
        data.put("mediaVolume", manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("mediaMaxVolume", manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            .put("mediaMuted", manager.isStreamMute(AudioManager.STREAM_MUSIC))
            .put("systemVolume", manager.getStreamVolume(AudioManager.STREAM_SYSTEM))
            .put("ringerMode", manager.ringerMode).put("audioMode", manager.mode)
            .put("interruptionFilter", notifications.currentInterruptionFilter)
    }

    private fun updateTrackData() {
        val player = track ?: return
        data.put("playState", player.playState)
            .put("playbackHeadFrames", player.playbackHeadPosition.toLong() and 0xffffffffL)
            .put("outputDeviceType", player.routedDevice?.type ?: JSONObject.NULL)
        val timestamp = AudioTimestamp()
        val valid = player.getTimestamp(timestamp)
        data.put("outputTimestampAvailable", valid)
        if (valid) data.put("outputTimestampFrame", timestamp.framePosition)
            .put("outputTimestampNs", timestamp.nanoTime).put("outputTimestampTimebase", "MONOTONIC")
    }

    @Synchronized override fun close() {
        if (closed) return
        runCatching { updateTrackData() }
        closed = true
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        if (focusHeld) { runCatching { manager.abandonAudioFocusRequest(focusRequest) }; focusHeld = false }
    }

    companion object {
        fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    }
}
