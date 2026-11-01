package cn.tracktimer.app

import android.util.Range
import android.util.Size
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameraModesTest {
    private val hd = Size(1280, 720)
    private val full = Size(1920, 1080)
    private fun caps(preview: Map<Size, Long> = mapOf(hd to 16_666_666L, full to 16_666_666L),
        ranges: List<Range<Int>> = listOf(Range(30, 30), Range(15, 60), Range(60, 60)),
        highSpeed: Map<Size, List<Range<Int>>> = emptyMap()) = CameraCapabilities("0",
        mapOf(hd to 16_666_666L, full to 16_666_666L), preview, ranges, highSpeed, true, 90, true)

    @Test fun cameraOnlyUsesPrivateCapabilitiesWithoutRecorderOrPreview() {
        val device = caps(preview = emptyMap()).copy(recorderSizes = emptyMap(), privateSizes = mapOf(hd to 16_666_666L))
        val c = CameraModes.select(listOf(device), CameraTestMode.HD60_CAPTURE_ONLY)!!
        assertTrue(c.captureOnly); assertFalse(c.previewEnabled); assertEquals(hd, c.size)
        assertNull(CameraModes.select(listOf(device), CameraTestMode.HD60_RECORD_ONLY))
    }
    @Test fun cameraOnlyRejectsSlowOrAbsentPrivateOutputWithoutSubstitution() {
        assertNull(CameraModes.select(listOf(caps()), CameraTestMode.HD60_CAPTURE_ONLY))
        assertNull(CameraModes.select(listOf(caps().copy(privateSizes = mapOf(hd to 33_333_333L))), CameraTestMode.HD60_CAPTURE_ONLY))
        assertNull(CameraModes.select(listOf(caps().copy(privateSizes = mapOf(hd to 16_666_666L))), CameraTestMode.FHD60_CAPTURE_ONLY))
    }
    @Test fun comparisonKeepsThePinnedCameraEvenIfAnotherSupportsMoreModes() {
        val first = caps().copy(id = "0", privateSizes = emptyMap())
        val other = caps().copy(id = "2", privateSizes = mapOf(hd to 16_666_666L))
        assertNull(CameraModes.select(listOf(first, other), CameraTestMode.HD60_CAPTURE_ONLY, "0"))
        assertEquals("2", CameraModes.select(listOf(first, other), CameraTestMode.HD60_CAPTURE_ONLY, "2")!!.id)
    }
    @Test fun explicit1080pDoesNotGetReplacedWithPreferred720p() {
        val config = CameraModes.select(listOf(caps()), CameraTestMode.FHD60_PREVIEW)!!
        assertEquals(full, config.size); assertEquals(Range(60, 60), config.range)
        assertTrue(config.previewEnabled); assertFalse(config.highSpeed)
    }
    @Test fun recordOnlyModeIgnoresAbsentOrSlowPreview() {
        val device = caps(preview = emptyMap())
        assertNull(CameraModes.select(listOf(device), CameraTestMode.HD60_PREVIEW))
        val config = CameraModes.select(listOf(device), CameraTestMode.HD60_RECORD_ONLY)!!
        assertEquals(hd, config.size); assertEquals(60, config.targetFps); assertFalse(config.previewEnabled)
    }
    @Test fun explicit60NeverSilentlyFallsBackTo30() {
        val device = caps(ranges = listOf(Range(30, 30)))
        assertNull(CameraModes.select(listOf(device), CameraTestMode.FHD60_PREVIEW))
        assertEquals(30, CameraModes.select(listOf(device), CameraTestMode.AUTOMATIC)!!.targetFps)
    }
    @Test fun automaticRetains720pPreferenceAnd60fpsPreference() {
        val config = CameraModes.select(listOf(caps()), CameraTestMode.AUTOMATIC)!!
        assertEquals(hd, config.size); assertEquals(Range(60, 60), config.range)
    }
    @Test fun highSpeedNeedsItsExactAdvertisedSizeAndFixedRange() {
        assertNull(CameraModes.select(listOf(caps()), CameraTestMode.HD120_HIGH_SPEED))
        assertNull(CameraModes.select(listOf(caps(highSpeed = mapOf(hd to listOf(Range(30, 120))))), CameraTestMode.HD120_HIGH_SPEED))
        val device = caps(highSpeed = mapOf(hd to listOf(Range(30, 120), Range(120, 120))))
        val config = CameraModes.select(listOf(device), CameraTestMode.HD120_HIGH_SPEED)!!
        assertTrue(config.highSpeed); assertEquals(120, config.targetFps)
        assertEquals(Range(120, 120), config.range)
    }
    @Test fun previewLimitsAreEnforcedWhenPreviewIsUsed() {
        val device = caps(preview = mapOf(hd to 33_333_333L, full to 33_333_333L))
        assertNull(CameraModes.select(listOf(device), CameraTestMode.HD60_PREVIEW))
        assertNotNull(CameraModes.select(listOf(device), CameraTestMode.HD60_RECORD_ONLY))
        assertEquals(30, CameraModes.select(listOf(device), CameraTestMode.AUTOMATIC)!!.targetFps)
    }
}
