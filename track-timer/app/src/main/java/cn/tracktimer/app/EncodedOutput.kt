package cn.tracktimer.app

import android.media.MediaCodec
import java.nio.ByteBuffer

/** The output buffer is released even if acquiring it, accounting, or writing fails. */
object EncodedOutput {
    fun consume(samples: EncodedSamples, buffer: () -> ByteBuffer?, info: MediaCodec.BufferInfo,
        observedNs: Long, write: (ByteBuffer, MediaCodec.BufferInfo) -> Unit, release: () -> Unit): Boolean {
        try {
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val data = checkNotNull(buffer()) { "编码器返回空视频缓冲区" }
                check(info.offset >= 0 && info.size <= data.capacity()-info.offset) { "编码缓冲区范围无效" }
                data.position(info.offset); data.limit(info.offset+info.size)
                val relativeUs = samples.add(info.presentationTimeUs, observedNs)
                val muxInfo = MediaCodec.BufferInfo().apply { set(info.offset,info.size,relativeUs,info.flags) }
                write(data,muxInfo)
            }
            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        } finally { release() }
    }
}
