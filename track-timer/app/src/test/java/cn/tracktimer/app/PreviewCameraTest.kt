package cn.tracktimer.app
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.TextureView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class PreviewCameraTest {
    @Test fun unavailablePreviewClosesWithoutCreatingARecordingOrReport(){
        val app=RuntimeEnvironment.getApplication();val dir=ReportStore(app).directory
        val before=dir.listFiles().orEmpty().map{it.name}.toSet();var error:String?=null;var released=false
        val p=PreviewCamera(app,TextureView(app),CameraConfig("0",Size(1280,720),Range(120,120),120,true,90),{fail("No texture should not be ready")},{error=it})
        p.start();val deadline=System.nanoTime()+3_000_000_000L
        while(error==null && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertNotNull(error);p.close{released=it}
        while(!released && System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue(released);assertEquals(before,dir.listFiles().orEmpty().map{it.name}.toSet())
    }
}
