package cn.tracktimer.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class StandbyPreviewUiTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @Test fun alignmentHasItsOwnPermissionActionWithoutRequiringAHostOrStartingRecording() {
        val c=Robolectric.buildActivity(FinishActivity::class.java).setup()
        try {
            val a=c.get();val buttons=views(a.window.decorView).filterIsInstance<Button>()
            val preview=buttons.firstOrNull{it.text=="先取景对线（不录像）"}
            assertNotNull("Alignment needs a no-recording entry",preview)
            preview!!.performClick();val req=shadowOf(a).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.CAMERA),req.requestedPermissions)
            assertEquals(92,req.requestCode)
            c.pause();a.onRequestPermissionsResult(92,req.requestedPermissions,intArrayOf(PackageManager.PERMISSION_GRANTED))
            assertTrue(ReportStore(a).directory.listFiles().orEmpty().none{it.extension in listOf("mp4","pending") || it.name.startsWith("field_race_")})
            assertTrue(buttons.first{it.text=="连接、对时并开始录像"}.isEnabled)
        }finally{c.stop().destroy()}
    }
}
