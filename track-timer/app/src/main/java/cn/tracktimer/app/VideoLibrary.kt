package cn.tracktimer.app

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Manual media management. JSON timing evidence is retained; .pending files are never eligible. */
class VideoLibrary(private val reports:ReportStore) {
    private val races=RaceStore(reports)
    data class Entry(val name:String,val bytes:Long,val modified:Long,val raceIds:List<String>,val canDelete:Boolean,val reviewed:Boolean)
    data class Deleted(val bytes:Long,val files:Int,val warnings:Int=0)
    companion object { private val lock=Any();private val namePattern=Regex("video_[a-fA-F0-9-]{36}\\.mp4") }
    private fun owned(name:String):File {
        require(namePattern.matches(name)){"无效录像文件名"}
        return File(reports.directory,name).also{
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile==reports.directory.canonicalFile){"录像不在应用存储目录内"}
        }
    }
    private fun videoName(j:JSONObject):String? {
        val direct=j.optString("videoFile").takeIf{namePattern.matches(it)}
        if(direct!=null)return direct
        val report=j.optString("cameraReportFile")
        if(report!=File(report).name || !report.startsWith("camera_") || !report.endsWith(".json"))return null
        val file=File(reports.directory,report)
        if(Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile!=reports.directory.canonicalFile)return null
        return runCatching{JSONObject(file.readText()).optString("videoFile").takeIf{namePattern.matches(it)}}.getOrNull()
    }
    fun inventory():List<Entry> = synchronized(lock){
        val refs=races.list().groupBy{videoName(it)}
        reports.directory.listFiles().orEmpty().mapNotNull {f->
            if(!namePattern.matches(f.name) || !f.isFile || runCatching{owned(f.name)}.isFailure)return@mapNotNull null
            val linked=refs[f.name].orEmpty()
            val terminal=linked.all{it.optString("status") in setOf("saved","interrupted")}
            Entry(f.name,f.length(),f.lastModified(),linked.map{it.getString("raceId")},terminal,
                terminal && linked.isNotEmpty() && linked.all{it.optBoolean("reviewCompleted") && (it.optJSONArray("results")?.length() ?: 0)>0})
        }.sortedByDescending{it.modified}
    }
    fun markReviewed(id:String)=synchronized(lock){
        val j=races.load(id)
        require(j.optString("status") in setOf("saved","interrupted")){"本场尚未完成保存"}
        require((j.optJSONArray("results")?.length() ?: 0)>0){"请先保存所有运动员成绩"}
        j.put("reviewCompleted",true).put("reviewCompletedAtUnixMs",System.currentTimeMillis());races.save(j)
    }
    fun deleteVideo(name:String,requireReviewed:Boolean=false):Deleted=synchronized(lock){
        val file=owned(name)
        val item=inventory().firstOrNull{it.name==name} ?: error("录像已不存在或不允许清理")
        require(item.canDelete && (!requireReviewed || item.reviewed)){"本场仍在录制/保存或尚未完成判线"}
        // Existing JSON remains intact even if updating the deletion marker fails.
        val linked=item.raceIds.map{races.load(it)}
        check(file.delete()){ "无法删除录像，请稍后重试" }
        var warnings=0
        linked.forEach{j->runCatching{j.put("videoDeleted",true).put("videoDeletedAtUnixMs",System.currentTimeMillis()).put("videoCleanupState","deleted");races.save(j)}.onFailure{warnings++}}
        Deleted(item.bytes,1,warnings)
    }
    fun deleteReviewed(names:List<String> = inventory().filter{it.reviewed}.map{it.name}):Deleted=synchronized(lock){
        var bytes=0L;var count=0;var warnings=0
        for(name in names.distinct())runCatching{deleteVideo(name,true)}
            .onSuccess{bytes+=it.bytes;count+=it.files;warnings+=it.warnings}.onFailure{warnings++}
        Deleted(bytes,count,warnings)
    }
}
