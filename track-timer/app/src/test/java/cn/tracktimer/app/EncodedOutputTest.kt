package cn.tracktimer.app

import android.media.MediaCodec
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EncodedOutputTest {
    @Test fun writesRelativeTimeAndAlwaysReleasesTheOutputBuffer() {
        val samples=EncodedSamples(); var releases=0; val written=mutableListOf<Long>()
        for (time in listOf(400_000_000L,400_008_333L)) {
            val info=MediaCodec.BufferInfo().apply { set(1,2,time,MediaCodec.BUFFER_FLAG_KEY_FRAME) }
            EncodedOutput.consume(samples,{ByteBuffer.wrap(byteArrayOf(9,1,2,9))},info,400_100_000_000L,
                { buffer, value -> assertEquals(1,buffer.position());assertEquals(3,buffer.limit());written.add(value.presentationTimeUs) },{ releases++ })
        }
        assertEquals(listOf(0L,8333L),written);assertEquals(2,releases)
        assertEquals(400_008_333_000L,samples.ptsNs.last())
    }
    @Test fun sinkFailureOrMissingBufferStillReleasesExactlyOnce() {
        for (missing in listOf(false,true)) {
            var released=0; val info=MediaCodec.BufferInfo().apply{set(0,1,100,0)}
            assertThrows(IllegalStateException::class.java) {
                EncodedOutput.consume(EncodedSamples(),{if(missing)null else ByteBuffer.allocate(1)},info,200_000,
                    { _,_->error("disk full") },{ released++ })
            }
            assertEquals(1,released)
        }
    }
    @Test fun configAndEmptyEosDoNotBecomeVideoFrames() {
        val s=EncodedSamples();var writes=0;var releases=0
        for (flag in listOf(MediaCodec.BUFFER_FLAG_CODEC_CONFIG,MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
            val info=MediaCodec.BufferInfo().apply {set(0,0,0,flag)}
            val eos=EncodedOutput.consume(s,{null},info,100,{_,_->writes++},{releases++})
            assertEquals(flag==MediaCodec.BUFFER_FLAG_END_OF_STREAM,eos)
        }
        assertEquals(0,writes);assertEquals(0,s.ptsNs.size);assertEquals(2,releases)
    }
}
