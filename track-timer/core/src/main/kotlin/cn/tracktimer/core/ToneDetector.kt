package cn.tracktimer.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ToneCandidate(val sampleIndex: Int, val coherence: Double)

/** Finds a test-tone candidate. This is deliberately not a verified acoustic start event. */
object ToneDetector {
    fun detect(samples: ShortArray, sampleRate: Int, frequency: Double): ToneCandidate? {
        require(sampleRate > 0 && frequency > 0 && frequency < sampleRate / 2.0)
        val window = (sampleRate * .004).toInt().coerceAtLeast(16)
        val hop = (sampleRate * .001).toInt().coerceAtLeast(1)
        val sine = DoubleArray(window) { sin(2 * PI * frequency * it / sampleRate) }
        val cosine = DoubleArray(window) { cos(2 * PI * frequency * it / sampleRate) }
        var streak = 0
        var first = 0
        for (start in 0..(samples.size - window) step hop) {
            var energy = 0.0; var s = 0.0; var c = 0.0
            for (i in 0 until window) {
                val v = samples[start + i] / 32768.0
                energy += v * v; s += v * sine[i]; c += v * cosine[i]
            }
            val coherence = if (energy > 0) 2 * (s * s + c * c) / (window * energy) else 0.0
            if (sqrt(energy / window) >= .025 && coherence >= .65) {
                if (streak == 0) first = start
                streak++
                if (streak >= 4) return ToneCandidate(first, coherence.coerceAtMost(1.0))
            } else streak = 0
        }
        return null
    }
}
