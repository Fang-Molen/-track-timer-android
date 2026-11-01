package cn.tracktimer.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityTest {
    @Test fun opensDiagnosticControlsWithoutStartingHardware() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val views = descendants(activity.window.decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text == "跑道计时" })
            assertTrue(views.filterIsInstance<Button>().any { it.text == "录制 10 秒视频" && it.isEnabled })
            assertTrue(views.filterIsInstance<Button>().any { it.text == "导出诊断报告 ZIP" && it.isEnabled })
            assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("尚不生成跑步成绩") })
            assertTrue(views.filterIsInstance<Button>().any { it.text == "只试听发令音（不录音）" && it.isEnabled })
            assertEquals(AudioManager.STREAM_MUSIC, activity.volumeControlStream)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun deniedCameraPermissionDoesNotStartRecordingAndAllowsRetry() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text == "录制 10 秒视频" }
            button.performClick()
            val request = shadowOf(activity).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
            activity.onRequestPermissionsResult(request.requestCode, arrayOf(Manifest.permission.CAMERA), intArrayOf(PackageManager.PERMISSION_DENIED))
            assertEquals("录制 10 秒视频", button.text.toString())
            assertTrue(button.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun auditionNeedsNoMicrophonePermissionAndRestoresControlsOnMutedMedia() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            val views = descendants(activity.window.decorView)
            val audition = views.filterIsInstance<Button>().first { it.text == "只试听发令音（不录音）" }
            audition.performClick()
            assertNull(shadowOf(activity).lastRequestedPermission)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!audition.isEnabled && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue(audition.isEnabled)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("媒体音量为零") })
            assertTrue(views.filterIsInstance<Button>().first { it.text == "导出诊断报告 ZIP" }.isEnabled)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun leavingForegroundStopsHostAndAllowsRestart() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val button = descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text == "作为起点：开启校准服务" }
            button.performClick()
            assertEquals("关闭起点校准服务", button.text.toString())
            controller.pause()
            assertEquals("作为起点：开启校准服务", button.text.toString())
            controller.resume()
            button.performClick()
            assertEquals("关闭起点校准服务", button.text.toString())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun leavingDuringCalibrationRestoresControlsWithoutStaleCompletion() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val views = descendants(activity.window.decorView)
            views.filterIsInstance<Button>().first { it.text == "作为起点：开启校准服务" }.performClick()
            val hostText = views.filterIsInstance<TextView>().first { it.text.contains("配对码：") }.text.toString()
            val code = Regex("配对码：([0-9]{6})").find(hostText)!!.groupValues[1]
            views.filterIsInstance<EditText>().first { it.hint.contains("IPv4") }.setText("127.0.0.1")
            views.filterIsInstance<EditText>().first { it.hint == "六位配对码" }.setText(code)
            val sync = views.filterIsInstance<Button>().first { it.text == "作为终点：执行 48 次校准" }
            sync.performClick()
            assertFalse(sync.isEnabled)
            controller.pause().resume()
            assertTrue(sync.isEnabled)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().startsWith("校准已中断") })
        } finally { controller.pause().stop().destroy() }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
