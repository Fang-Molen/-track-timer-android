package cn.tracktimer.core

import kotlin.math.ceil
import kotlin.math.roundToInt

data class FrameReport(val count: Int, val measuredFps: Double?, val maxGapNs: Long,
    val p95GapNs: Long, val estimatedMissingFrames: Int)

object FrameAnalysis {
    fun analyze(timestampsNs: List<Long>, targetFps: Int): FrameReport {
        require(targetFps > 0)
        require(timestampsNs.all { it >= 0 })
        if (timestampsNs.size < 2) return FrameReport(timestampsNs.size, null, 0, 0, 0)
        val gaps = timestampsNs.zipWithNext { a, b ->
            require(b > a) { "Non-monotonic camera timestamps" }; b - a
        }
        val period = 1e9 / targetFps
        val sorted = gaps.sorted()
        return FrameReport(timestampsNs.size,
            gaps.size * 1e9 / (timestampsNs.last() - timestampsNs.first()),
            sorted.last(), sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)],
            gaps.sumOf { (it / period).roundToInt().minus(1).coerceAtLeast(0) })
    }
}
