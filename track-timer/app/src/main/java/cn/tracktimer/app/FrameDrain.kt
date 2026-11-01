package cn.tracktimer.app

/** Consumes bounded batches; every acquired image is released even when timestamp access fails. */
class FrameDrain(private val now: () -> Long) {
    data class Snapshot(val timestamps: List<Long>, val received: List<Long>, val closedImages: Int,
        val omitted: Int, val error: String?)
    private val timestamps = ArrayList<Long>()
    private val received = ArrayList<Long>()
    private var closedImages = 0
    private var omitted = 0
    private var error: String? = null
    private var stopped = false

    @Synchronized fun <T : AutoCloseable> drain(acquire: () -> T?, timestamp: (T) -> Long): String? {
        if (stopped || error != null) return error
        try {
            repeat(8) {
                val frame = acquire() ?: return null
                try {
                    val ns = timestamp(frame)
                    val at = now()
                    if (timestamps.size < 3000) { timestamps.add(ns); received.add(at) } else omitted++
                } finally { frame.close(); closedImages++ }
            }
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        return error
    }
    @Synchronized fun stop(): Snapshot {
        stopped = true
        return Snapshot(timestamps.toList(), received.toList(), closedImages, omitted, error)
    }
}
