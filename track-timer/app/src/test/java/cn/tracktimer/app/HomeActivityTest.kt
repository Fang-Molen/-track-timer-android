package cn.tracktimer.app

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class HomeActivityTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @Test fun homeRoutesRolesHistoryAndDiagnosticsWithoutRequestingPermissions() {
        val c=Robolectric.buildActivity(HomeActivity::class.java).setup()
        try {
            val a=c.get();val buttons=views(a.window.decorView).filterIsInstance<Button>()
            for((name,target) in listOf("起点发令" to StartActivity::class.java,"终点录像计时" to FinishActivity::class.java,
                "历史场次与判线" to RaceReviewActivity::class.java,"设备与日志" to MainActivity::class.java)) {
                buttons.first{it.text==name}.performClick();assertEquals(target.name,Shadows.shadowOf(a).nextStartedActivity.component!!.className)
            }
            assertNull(Shadows.shadowOf(a).lastRequestedPermission)
            assertEquals(HomeActivity::class.java.name,a.packageManager.getLaunchIntentForPackage(a.packageName)!!.component!!.className)
        }finally{c.pause().stop().destroy()}
    }
}
