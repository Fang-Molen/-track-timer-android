package cn.tracktimer.app

import android.media.ImageReader
import android.os.Handler
import android.util.Size
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowImageReader

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [FailingListenerReader::class])
class CameraFrameReaderTest {
    @Test fun listenerSetupFailureClosesTheAlreadyAllocatedReader() {
        FailingListenerReader.closed = 0
        val error = assertThrows(IllegalStateException::class.java) { CameraFrameReader(Size(1280, 720)) {} }
        assertEquals("listener setup failed", error.message)
        assertEquals(1, FailingListenerReader.closed)
    }
}

@Implements(ImageReader::class)
class FailingListenerReader : ShadowImageReader() {
    @Implementation protected fun setOnImageAvailableListener(listener: ImageReader.OnImageAvailableListener?, handler: Handler?) {
        if (listener != null) error("listener setup failed")
    }
    @Implementation override fun close() { closed++; super.close() }
    companion object { var closed = 0 }
}
