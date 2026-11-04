package cn.tracktimer.app
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class VideoLibraryTest {
    private val store get()=ReportStore(RuntimeEnvironment.getApplication())
    private fun race(video:String,status:String="saved",completed:Boolean=false):JSONObject=JSONObject().put("raceId",UUID.randomUUID().toString()).put("status",status)
        .put("videoFile",video).put("reviewCompleted",completed).put("results",JSONArray().put(JSONObject().put("name","A").put("elapsedNs",12_000_000_000L)))
    @Test fun batchRequiresExplicitCompletionAndRetainsEveryTimingRecord(){
        val s=store;val races=RaceStore(s);val lib=VideoLibrary(s)
        val video=s.newFile("video","mp4").apply{writeBytes(ByteArray(1024))};val j=race(video.name);races.save(j)
        val camera=s.saveJson("camera",JSONObject().put("videoFile",video.name));val original=camera.readText()
        assertEquals(0,lib.deleteReviewed().files);assertTrue(video.exists())
        lib.markReviewed(j.getString("raceId"));val result=lib.deleteReviewed()
        assertEquals(1,result.files);assertEquals(1024L,result.bytes);assertFalse(video.exists());assertEquals(original,camera.readText())
        val saved=races.load(j.getString("raceId"));assertTrue(saved.getBoolean("videoDeleted"));assertEquals(12_000_000_000L,saved.getJSONArray("results").getJSONObject(0).getLong("elapsedNs"))
    }
    @Test fun sharedUnreviewedAndActiveRacesBlockCleanup(){
        val s=store;val races=RaceStore(s);val lib=VideoLibrary(s);val video=s.newFile("video","mp4").apply{writeText("video")}
        races.save(race(video.name,completed=true));val other=race(video.name);races.save(other)
        assertEquals(0,lib.deleteReviewed().files)
        other.put("status","finishing");races.save(other)
        assertThrows(IllegalArgumentException::class.java){lib.deleteVideo(video.name)};assertTrue(video.exists())
    }
    @Test fun manualDiagnosticDeletionNeverTouchesPendingAudioJsonOrOutsideFiles(){
        val s=store;val lib=VideoLibrary(s);val diagnostic=s.newFile("video","mp4").apply{writeText("video")}
        val pending=s.newFile("video","pending").apply{writeText("recording")};val audio=s.newFile("audio","wav").apply{writeText("audio")}
        val outside=java.io.File(s.directory.parentFile,"outside.mp4").apply{writeText("outside")}
        val symlink=s.newFile("video","mp4");Files.createSymbolicLink(symlink.toPath(),outside.toPath())
        assertEquals(0,lib.deleteReviewed().files)
        assertThrows(IllegalArgumentException::class.java){lib.deleteVideo("../outside.mp4")}
        assertThrows(IllegalArgumentException::class.java){lib.deleteVideo(symlink.name)}
        assertThrows(IllegalArgumentException::class.java){lib.deleteVideo(pending.name)}
        assertEquals(1,lib.deleteVideo(diagnostic.name).files);assertTrue(pending.exists());assertTrue(audio.exists());assertEquals("outside",outside.readText())
    }
    @Test fun batchDeletesOnlyTheConfirmedSnapshotAndRechecksEligibility(){
        val s=store;val races=RaceStore(s);val lib=VideoLibrary(s)
        val first=s.newFile("video","mp4").apply{writeText("first")};val second=s.newFile("video","mp4").apply{writeText("second")}
        val a=race(first.name,completed=true);races.save(a);races.save(race(second.name,completed=true))
        assertEquals(1,lib.deleteReviewed(listOf(first.name)).files);assertTrue(second.exists())
        val b=races.list().first{it.optString("videoFile")==second.name};b.put("reviewCompleted",false);races.save(b)
        assertEquals(0,lib.deleteReviewed(listOf(second.name)).files);assertTrue(second.exists())
    }
    @Test fun cannotMarkAnEmptyOrActiveTrialAsReviewed(){
        val s=store;val races=RaceStore(s);val lib=VideoLibrary(s)
        for(j in listOf(race("video_none.mp4","fired"),race("video_none.mp4").put("results",JSONArray()))){races.save(j);assertThrows(IllegalArgumentException::class.java){lib.markReviewed(j.getString("raceId"))}}
    }
}
