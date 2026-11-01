package cn.tracktimer.app

import org.junit.Assert.*
import org.junit.Test

class FrameDrainTest {
    private class Frame(val ns: Long, val bad: Boolean = false) : AutoCloseable {
        var closed = false
        fun time(): Long { check(!bad) { "timestamp failed" }; return ns }
        override fun close() { closed = true }
    }
    @Test fun drainClosesEveryFrameWithoutCopyingOrSkippingItsTimestamp() {
        val frames = listOf(Frame(100), Frame(200), Frame(300))
        val queue = ArrayDeque(frames)
        val drain = FrameDrain { 400L }
        drain.drain({ queue.removeFirstOrNull() }, Frame::time)
        val result = drain.stop()
        assertEquals(listOf(100L, 200L, 300L), result.timestamps)
        assertEquals(listOf(400L, 400L, 400L), result.received)
        assertTrue(frames.all { it.closed })
        assertNull(result.error)
    }
    @Test fun badTimestampStillClosesImageAndRecordsFailure() {
        val frame = Frame(100, bad = true)
        var next: Frame? = frame
        val drain = FrameDrain { 400L }
        drain.drain({ next.also { next = null } }, Frame::time)
        assertTrue(frame.closed)
        assertTrue(drain.stop().error!!.contains("timestamp failed"))
    }
    @Test fun stopPreventsLateCallbacksFromAcquiringImages() {
        val drain = FrameDrain { 400L }
        drain.stop()
        var acquired = false
        drain.drain({ acquired = true; Frame(100) }, Frame::time)
        assertFalse(acquired)
    }
    @Test fun callbacksAndStorageAreBoundedButAllAcquiredImagesAreReleased() {
        val drain = FrameDrain { 400L }
        var count = 0; var closed = 0
        repeat(400) {
            drain.drain({ count++; object : AutoCloseable { override fun close() { closed++ } } }, { count.toLong() })
        }
        val result = drain.stop()
        assertEquals(3200, count); assertEquals(count, closed)
        assertEquals(3000, result.timestamps.size); assertEquals(200, result.omitted)
    }
}
