package cn.tracktimer.core

/** Times are nanoseconds in each device's monotonic timebase; offset is remote minus local. */
data class Exchange(val t1: Long, val t2: Long, val t3: Long, val t4: Long) {
    init {
        require(listOf(t1, t2, t3, t4).all { it >= 0 }) { "Negative timestamp" }
        require(t4 >= t1 && t3 >= t2) { "Clock moved backwards" }
        require(t4 - t1 >= t3 - t2) { "Negative network round trip" }
    }
    val lowerOffsetNs: Long get() = t3 - t4
    val upperOffsetNs: Long get() = t2 - t1
    val networkRoundTripNs: Long get() = (t4 - t1) - (t3 - t2)
    val localMidpointNs: Long get() = t1 + (t4 - t1) / 2
}

data class Calibration(
    val localSession: String, val remoteSession: String,
    val anchorLocalNs: Long, val offsetNs: Long, val uncertaintyNs: Long,
    val lowerOffsetNs: Long, val upperOffsetNs: Long,
    val sampleCount: Int, val selectedCount: Int, val bestRoundTripNs: Long
)

object ClockSync {
    fun estimate(samples: List<Exchange>, localSession: String, remoteSession: String): Calibration {
        require(samples.size >= 8) { "At least eight exchanges required" }
        require(localSession.isNotBlank() && remoteSession.isNotBlank()) { "Missing clock session" }
        require(samples.maxOf { it.lowerOffsetNs } <= samples.minOf { it.upperOffsetNs }) {
            "Contradictory exchange outside selected samples; repeat calibration"
        }
        val selected = samples.sortedBy { it.networkRoundTripNs }.take(8)
        val lower = selected.maxOf { it.lowerOffsetNs }
        val upper = selected.minOf { it.upperOffsetNs }
        require(lower <= upper) { "Inconsistent clock bounds; repeat calibration" }
        val width = upper - lower
        return Calibration(localSession, remoteSession, selected[0].localMidpointNs,
            lower + width / 2, width / 2 + width % 2, lower, upper,
            samples.size, selected.size, selected[0].networkRoundTripNs)
    }

    /** Diagnostic rate estimate only, not a certified correction for offline race results. */
    fun driftPpm(before: Calibration, after: Calibration): Double {
        require(before.localSession == after.localSession && before.remoteSession == after.remoteSession) {
            "Clock session changed"
        }
        val elapsed = after.anchorLocalNs - before.anchorLocalNs
        require(elapsed >= 1_000_000_000L) { "Calibration intervals too close" }
        return (after.offsetNs - before.offsetNs).toDouble() / elapsed * 1_000_000
    }
}
