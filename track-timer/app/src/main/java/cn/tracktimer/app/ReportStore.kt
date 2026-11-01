package cn.tracktimer.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppSession { val id: String = UUID.randomUUID().toString() }

class ReportStore(context: Context) {
    val directory = File(context.filesDir, "reports").apply { mkdirs() }
    fun newFile(prefix: String, extension: String): File = File(directory, "${prefix}_${UUID.randomUUID()}.$extension")

    @Synchronized fun saveJson(prefix: String, json: JSONObject): File {
        json.put("schemaVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("clockSession", AppSession.id).put("createdAtUnixMs", System.currentTimeMillis())
        val file = newFile(prefix, "json")
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(json.toString(2).toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        return file
    }

    @Synchronized fun export(output: OutputStream) {
        val files = directory.listFiles().orEmpty().filter {
            it.isFile && it.extension in setOf("json", "csv", "mp4", "wav")
        }.sortedBy { it.name }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("说明.txt"))
            zip.write("跑道计时 ${BuildConfig.VERSION_NAME} 设备验证报告\n这是设备能力与采集诊断，不是已标定的田径成绩。\n时间单位以字段名为准；时间戳属于报告内标识的时钟。播放输出时间戳标记为 MONOTONIC，不能直接与 BOOTTIME 采集时间相减。\n音频含麦克风原始录音，视频含测试画面。升级前的报告仍保留各自版本。\n".toByteArray())
            zip.closeEntry()
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    fun reportCount(): Int = directory.listFiles().orEmpty().count { it.extension == "json" }
}
