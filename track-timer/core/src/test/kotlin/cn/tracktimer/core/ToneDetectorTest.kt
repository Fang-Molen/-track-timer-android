package cn.tracktimer.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ToneDetectorTest {
    @Test fun findsKnownToneOnsetWithoutTreatingSilenceAsEvent() {
        val pcm = ShortArray(96_000)
        for (i in 48_000 until 55_200) pcm[i] = (8000 * sin(2 * PI * 1800 * (i - 48_000) / 48_000)).toInt().toShort()
        val event = ToneDetector.detect(pcm, 48_000, 1800.0)
        assertNotNull(event)
        assertTrue("candidate=${event!!.sampleIndex}", event.sampleIndex in 47_760..48_240)
    }
    @Test fun rejectsSilenceAndBroadbandNoise() {
        assertNull(ToneDetector.detect(ShortArray(48_000), 48_000, 1800.0))
        val random = Random(17)
        assertNull(ToneDetector.detect(ShortArray(48_000) { random.nextInt(-3000, 3000).toShort() }, 48_000, 1800.0))
    }
    @Test fun rejectsDifferentFrequencyAndSingleClick() {
        val other = ShortArray(48_000) { (7000 * sin(2 * PI * 440 * it / 48_000)).toInt().toShort() }
        assertNull(ToneDetector.detect(other, 48_000, 1800.0))
        val click = ShortArray(48_000); click[24_000] = 30_000
        assertNull(ToneDetector.detect(click, 48_000, 1800.0))
    }
}
