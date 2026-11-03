package cn.tracktimer.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.tracktimer.core.Calibration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.time.Duration
import java.util.UUID

@Implements(MediaMetadataRetriever::class)
class ExactFrameShadow:ShadowMediaMetadataRetriever() {
    @Implementation fun getFrameAtIndex(index:Int):Bitmap=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply{eraseColor(index+1)}
}
@RunWith(RobolectricTestRunner::class) @Config(sdk=[28],shadows=[ExactFrameShadow::class])
class RaceReviewActivityTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @Test fun exactSelectedFrameAndHistoricalCalibrationPersistTwoTiedAthletes() {
        val context=RuntimeEnvironment.getApplication();val reports=ReportStore(context);val races=RaceStore(reports)
        val id=UUID.randomUUID().toString();val video=reports.newFile("video","mp4").apply{writeText("mock video")}
        ShadowMediaMetadataRetriever.addMetadata(video.path,MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,"3")
        val camera=JSONObject().put("runId",id).put("videoFile",video.name).put("codecEosReached",true).put("failedCaptures",0)
            .put("codecPtsNs",JSONArray(listOf(48_000_000_000L,48_008_333_000L,48_016_666_000L)))
            .put("encodedPtsNs",JSONArray(listOf(0,8_330_000,16_660_000))).put("codecFrameCount",3).put("encodedFrameCount",3)
            .put("clockCalibration",JSONObject().put("sampledClockAlignmentObserved",true))
        val cameraFile=reports.saveJson("camera",camera);camera.put("clockSession","old-finish");cameraFile.writeText(camera.toString())
        val pre=Calibration("old-finish","old-host",45_000_000_000L,2_000_000_000L,1_000_000L,1_999_000_000L,2_001_000_000L,48,8,2_000_000L)
        races.save(JSONObject().put("raceId",id).put("clientSession","old-finish").put("hostSession","old-host")
            .put("cameraReportFile",cameraFile.name).put("startHostNs",50_000_000_000L)
            .put("preCalibration",CalibrationJson.encode(pre)).put("postCalibration",CalibrationJson.encode(pre.copy(anchorLocalNs=49_000_000_000L)))
            .put("networkCompleted",true).put("results",JSONArray()))
        val c=Robolectric.buildActivity(RaceReviewActivity::class.java,Intent(context,RaceReviewActivity::class.java).putExtra("raceId",id)).setup()
        try {
            val v=views(c.get().window.decorView);val slider=v.filterIsInstance<SeekBar>().single();val save=v.filterIsInstance<Button>().first{it.text=="保存此帧成绩"}
            fun waitFor(test:()->Boolean) {val until=System.nanoTime()+5_000_000_000L
                while(!test() && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));Thread.sleep(10)};assertTrue(test())}
            waitFor{slider.isEnabled};assertFalse(save.isEnabled)
            v.filterIsInstance<Button>().first{it.text=="+1 帧"}.performClick();assertFalse(save.isEnabled)
            waitFor{save.isEnabled}
            val name=v.filterIsInstance<EditText>().single()
            for(athlete in listOf("1道 A","2道 B 遮挡并列")){name.setText(athlete);save.performClick()}
            val results=races.load(id).getJSONArray("results");assertEquals(2,results.length())
            for(i in 0..1){assertEquals(1,results.getJSONObject(i).getInt("frameIndex"));assertEquals(8_333_000L,results.getJSONObject(i).getLong("elapsedNs"));assertEquals("training_signal",results.getJSONObject(i).getString("quality"))}
        }finally{c.pause().stop().destroy()}
    }
}
