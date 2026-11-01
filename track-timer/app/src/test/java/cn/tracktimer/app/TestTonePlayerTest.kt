package cn.tracktimer.app

import android.app.Application
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TestTonePlayerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val manager: AudioManager get() = app.getSystemService(AudioManager::class.java)

    @Test fun testToneUsesTheSameMediaStreamAsTheVolumeControls() {
        assertEquals(AudioManager.STREAM_MUSIC, TestTonePlayer.audioAttributes().volumeControlStream)
    }

    @Test fun zeroMediaVolumeStopsBeforeStartingPlaybackAndPreservesTheVolume() {
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        TestTonePlayer(app, 48_000).use { player ->
            val failure = runCatching { player.prepare() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("媒体音量"))
            assertEquals(0, player.snapshot().getInt("mediaVolume"))
            assertFalse(player.snapshot().getBoolean("playRequested"))
            assertNull(shadowOf(manager).lastAudioFocusRequest)
        }
        assertEquals(0, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun deniedFocusIsReportedBeforeAnyAudioTrackIsStarted() {
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        TestTonePlayer(app, 48_000).use { player ->
            val failure = runCatching { player.prepare() }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("音频焦点"))
            val report = player.snapshot()
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, report.getInt("focusRequestResult"))
            assertFalse(report.getBoolean("playRequested"))
        }
    }

    @Test fun cancelledBeforePreparationCannotStartSoundOrAcquireFocus() {
        val player = TestTonePlayer(app, 48_000)
        player.close()
        assertTrue(runCatching { player.prepare() }.isFailure)
        assertTrue(runCatching { player.play() }.isFailure)
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        assertFalse(player.snapshot().getBoolean("playRequested"))
    }
}
