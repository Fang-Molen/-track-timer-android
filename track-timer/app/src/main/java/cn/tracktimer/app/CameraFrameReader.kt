package cn.tracktimer.app

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size

/** Opaque PRIVATE buffers: no pixel reads, copies, encoder or preview consumer. */
class CameraFrameReader(size: Size, private val onError: (String) -> Unit) {
    private val drain = FrameDrain(SystemClock::elapsedRealtimeNanos)
    private val thread = HandlerThread("camera-frame-drain").apply { start() }
    private var reader: ImageReader? = null
    val surface get() = checkNotNull(reader).surface
    init {
        try {
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 4).also { source ->
                source.setOnImageAvailableListener({ available ->
                    drain.drain({ available.acquireNextImage() }, { it.timestamp })?.let(onError)
                }, Handler(thread.looper))
            }
        } catch (e: Exception) { reader?.close(); thread.quitSafely(); throw e }
    }
    fun close(): FrameDrain.Snapshot {
        // This lock waits for an in-flight acquisition to release its image before reader.close().
        val result = drain.stop()
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        thread.quitSafely()
        return result
    }
}
