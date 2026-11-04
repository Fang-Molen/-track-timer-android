package cn.tracktimer.app

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class CameraPreviewTransformTest {
    @Test fun sensorOrientedPortraitPreviewIsNotRotatedAgainOrSqueezed() {
        for(sensor in listOf(90,270)) {
            val m=CameraPreviewTransform.matrix(1080,640,1280,720,sensor,0)
            val rect=RectF(0f,0f,1080f,640f);m.mapRect(rect)
            assertEquals(360f,rect.left,.01f);assertEquals(720f,rect.right,.01f)
            assertEquals(0f,rect.top,.01f);assertEquals(640f,rect.bottom,.01f)
            // A 10x10 upright sensor-pixel square must remain square and point right/down.
            val p=floatArrayOf(0f,0f,15f,0f,0f,5f);m.mapPoints(p)
            assertEquals(5f,p[2]-p[0],.01f);assertEquals(5f,p[5]-p[1],.01f)
            assertEquals(p[1],p[3],.01f);assertEquals(p[0],p[4],.01f)
        }
    }
    @Test fun displayRotationAndResizeRemainCenteredWithoutMirroring() {
        val landscape=CameraPreviewTransform.matrix(640,360,1280,720,90,90)
        val p=floatArrayOf(320f,180f,320f,0f,0f,180f);landscape.mapPoints(p)
        assertArrayEquals(floatArrayOf(320f,180f,0f,180f,320f,360f),p,.02f)
        val upside=CameraPreviewTransform.matrix(360,640,1280,720,90,180)
        val q=floatArrayOf(0f,0f,360f,640f);upside.mapPoints(q)
        assertArrayEquals(floatArrayOf(360f,640f,0f,0f),q,.02f)
        val resized=CameraPreviewTransform.matrix(720,1280,1280,720,270,0)
        assertTrue(resized.isIdentity)
    }
}
