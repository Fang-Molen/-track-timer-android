package cn.tracktimer.app

import android.util.AtomicFile
import cn.tracktimer.core.Calibration
import org.json.JSONObject
import java.io.File

class CaptureBudget(val durationMs:Long,val fps:Int) {
    init { require(durationMs in 1_000..180_000 && fps in 1..240) }
    val sampleLimit:Int = ((durationMs/1000+5)*fps+1024).toInt()
    val requiredBytes:Long = (if(fps>=240)32_000_000L else 16_000_000L)/8*durationMs/1000+100*1024*1024L
}
/** Index association is checked structurally; use the encoder clock, never MP4 playback time. */
class FrameTimeline private constructor(private val raw:LongArray) {
    val size:Int get()=raw.size
    fun timestamp(index:Int):Long=raw[index]
    companion object {
        fun from(r:JSONObject,decodedFrameCount:Int):FrameTimeline {
            require(r.optBoolean("codecEosReached") && r.isNull("error") && r.isNull("codecError") &&
                r.isNull("encodedInspectionError") && !r.optBoolean("cancelled") && r.optInt("failedCaptures")==0){"录像不完整或存在采集错误"}
            require(r.optJSONObject("clockCalibration")?.optBoolean("sampledClockAlignmentObserved")==true){"相机与本机时钟未通过检查：${HighSpeedCalibration.failureDescription(r)}"}
            val raw=r.getJSONArray("codecPtsNs");val file=r.getJSONArray("encodedPtsNs")
            require(raw.length()>=2 && raw.length()==decodedFrameCount && file.length()==raw.length() &&
                r.getInt("encodedFrameCount")==raw.length() && r.getInt("codecFrameCount")==raw.length()){"视频与原始帧索引数量不一致"}
            val points=LongArray(raw.length()){raw.getLong(it)}
            require(points.indices.all { points[it]>0 && (it==0 || points[it]>points[it-1]) &&
                file.getLong(it)>=0 && (it==0 || file.getLong(it)>file.getLong(it-1)) }){"帧时间重复或倒退"}
            return FrameTimeline(points)
        }
    }
}
class RaceStore(private val reports:ReportStore) {
    private fun file(id:String):File {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))){"Invalid race id"}
        return File(reports.directory,"field_race_$id.json")
    }
    @Synchronized fun save(j:JSONObject) {
        val atomic=AtomicFile(file(j.getString("raceId")))
        j.put("schemaVersion",1).put("appVersion",BuildConfig.VERSION_NAME).put("precisionVerified",false).put("acousticLatencyCalibrated",false)
        val out=atomic.startWrite()
        try {out.write(j.toString(2).toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
    }
    fun load(id:String):JSONObject=JSONObject(AtomicFile(file(id)).openRead().bufferedReader().use{it.readText()})
    fun list():List<JSONObject> = reports.directory.listFiles().orEmpty().filter {it.name.startsWith("field_race_") && it.extension=="json"}
        .mapNotNull {runCatching{load(it.name.removePrefix("field_race_").removeSuffix(".json"))}.getOrNull()}.sortedByDescending{it.optLong("createdAtUnixMs")}
}
object CalibrationJson {
    fun encode(c:Calibration):JSONObject=JSONObject().put("localSession",c.localSession).put("remoteSession",c.remoteSession)
        .put("anchorLocalNs",c.anchorLocalNs).put("offsetNs",c.offsetNs).put("uncertaintyNs",c.uncertaintyNs)
        .put("lowerOffsetNs",c.lowerOffsetNs).put("upperOffsetNs",c.upperOffsetNs).put("sampleCount",c.sampleCount)
        .put("selectedCount",c.selectedCount).put("bestRoundTripNs",c.bestRoundTripNs)
    fun decode(j:JSONObject)=Calibration(j.getString("localSession"),j.getString("remoteSession"),j.getLong("anchorLocalNs"),j.getLong("offsetNs"),j.getLong("uncertaintyNs"),j.getLong("lowerOffsetNs"),j.getLong("upperOffsetNs"),j.getInt("sampleCount"),j.getInt("selectedCount"),j.getLong("bestRoundTripNs"))
}
