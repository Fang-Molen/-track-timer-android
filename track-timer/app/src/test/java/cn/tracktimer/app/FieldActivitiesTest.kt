package cn.tracktimer.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Robolectric models byte writes but not AudioTrack's static short[] PCM path.
@org.robolectric.annotation.Implements(android.media.AudioTrack::class)
class StaticPcmTrackShadow:org.robolectric.shadows.ShadowAudioTrack() {
    @org.robolectric.annotation.Implementation
    fun native_write_short(data:ShortArray,offset:Int,size:Int,format:Int,blocking:Boolean):Int=size
    // Base shadow calls an AudioFormat method absent on API28; model partial progress directly.
    @org.robolectric.annotation.Implementation override fun getPlaybackHeadPosition():Int=100
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class FieldActivitiesTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @Test fun fieldEntriesLaunchDedicatedRolesWithoutStartingHardware() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {val a=c.get();val v=views(a.window.decorView)
            for((name,clazz) in listOf("起点发令" to StartActivity::class.java,"终点录像计时" to FinishActivity::class.java,"历史场次与判线" to RaceReviewActivity::class.java)) {
                v.filterIsInstance<Button>().first{it.text==name}.performClick()
                assertEquals(clazz.name,shadowOf(a).nextStartedActivity.component!!.className)
            }
        } finally{c.pause().stop().destroy()}
    }
    @Test fun finishPermissionDenialAndBackgroundGrantDoNotBeginNetworkOrCapture() {
        val c=Robolectric.buildActivity(FinishActivity::class.java).setup()
        try{val a=c.get();val b=views(a.window.decorView).filterIsInstance<Button>().first{it.text=="连接、对时并开始录像"}
            b.performClick();val req=shadowOf(a).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.CAMERA),req.requestedPermissions)
            a.onRequestPermissionsResult(91,req.requestedPermissions,intArrayOf(PackageManager.PERMISSION_DENIED));assertTrue(b.isEnabled)
            c.pause();a.onRequestPermissionsResult(91,req.requestedPermissions,intArrayOf(PackageManager.PERMISSION_GRANTED))
            assertTrue(b.isEnabled);assertTrue(views(a.window.decorView).filterIsInstance<TextView>().any{it.text=="尚未开始"})
        }finally{c.stop().destroy()}
    }
    @Test @Config(shadows=[StaticPcmTrackShadow::class]) fun asynchronousCueFocusLossCancelsTheFiredRace() {
        val c=Robolectric.buildActivity(StartActivity::class.java).setup()
        try {
            val a=c.get();val v=views(a.window.decorView)
            val manager=a.getSystemService(android.media.AudioManager::class.java)
            manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,5,0)
            v.filterIsInstance<Button>().first{it.text=="开启起点服务"}.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            val address=v.filterIsInstance<TextView>().first{it.text.contains("配对码：")}.text.toString()
            val token=Regex("配对码：([0-9]{6})").find(address)!!.groupValues[1]
            val cal=cn.tracktimer.core.SyncClient.measure("127.0.0.1",19119,token,"finish",android.os.SystemClock::elapsedRealtimeNanos,8).calibration
            cn.tracktimer.core.RaceClient.request("127.0.0.1",19119,token,cal.remoteSession,"finish","cue-trial","READY")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            val fire=v.filterIsInstance<Button>().first{it.text=="发令一次"};assertTrue(fire.isEnabled);fire.performClick()
            val fired=cn.tracktimer.core.RaceClient.request("127.0.0.1",19119,token,cal.remoteSession,"finish","cue-trial","POLL")
            assertEquals(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.toString(),cn.tracktimer.core.RacePhase.FIRED,fired.phase)
            val focus=checkNotNull(shadowOf(manager).lastAudioFocusRequest).listener
            focus.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
            val reply=cn.tracktimer.core.RaceClient.request("127.0.0.1",19119,token,cal.remoteSession,"finish","cue-trial","POLL")
            assertEquals(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()?.toString(),cn.tracktimer.core.RacePhase.ABORTED,reply.phase)
            assertTrue(ReportStore(a).directory.listFiles().orEmpty().filter{it.name.startsWith("field_cue_")}.any{
                val j=org.json.JSONObject(it.readText());j.optString("raceId")=="cue-trial" && j.optBoolean("focusLost") && !j.optBoolean("cuePlaybackComplete")})
        } finally {c.pause().stop().destroy()}
    }
    @Test fun starterCannotFireWithoutReadyAndReleasesPortOnPause() {
        val c=Robolectric.buildActivity(StartActivity::class.java).setup()
        try {val v=views(c.get().window.decorView);val open=v.filterIsInstance<Button>().first{it.text=="开启起点服务"}
            val fire=v.filterIsInstance<Button>().first{it.text=="发令一次"};assertFalse(fire.isEnabled)
            open.performClick();assertFalse(open.isEnabled);c.pause();assertTrue(open.isEnabled)
            java.net.ServerSocket(19119).close()
        }finally{c.stop().destroy()}
    }
}
