package cn.tracktimer.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Main-thread state machine. Duplicate/stale completions cannot advance a run. */
class CameraComparison(val cameraId: String, val id: String = UUID.randomUUID().toString()) {
    private val steps = ArrayList<JSONObject>()
    private var cancelled = false
    private var interruptedMode: String? = null
    val current: CameraTestMode? get() = if (cancelled) null else modes.getOrNull(steps.size)
    val stepNumber get() = steps.size + 1

    fun accept(report: JSONObject): Boolean {
        if (current == null || report.optString("testMode") != current!!.name) return false
        if (report.isNull("error") && report.optString("cameraId") != cameraId)
            report.put("error", "相机身份与本次对照不一致，不能比较")
        steps.add(report)
        return true
    }
    fun cancel() { interruptedMode = current?.name; cancelled = true }

    fun report(): JSONObject {
        fun pair(a: CameraTestMode, b: CameraTestMode): JSONObject {
            val first = steps.firstOrNull { it.optString("testMode") == a.name }
            val second = steps.firstOrNull { it.optString("testMode") == b.name }
            val finding = when {
                first == null || second == null || !usable(first) || !usable(second) -> "data_unavailable"
                reached(first) && reached(second) -> "both_reached"
                reached(first) -> "output_path_difference"
                reached(second) -> "camera_only_lower"
                else -> "both_below_target"
            }
            return JSONObject().put("size", a.size.toString()).put("finding", finding)
                .put("captureOnlyMode", a.name).put("recordingMode", b.name)
        }
        return JSONObject().put("kind", "camera_comparison").put("runId", id).put("cameraId", cameraId)
            .put("cancelled", cancelled).put("interruptedMode", interruptedMode ?: JSONObject.NULL)
            .put("completed", !cancelled && steps.size == modes.size).put("plannedSteps", modes.size)
            .put("steps", JSONArray(steps)).put("passedStepCount", steps.count(::reached))
            .put("pairs", JSONArray(listOf(pair(CameraTestMode.HD60_CAPTURE_ONLY, CameraTestMode.HD60_RECORD_ONLY),
                pair(CameraTestMode.FHD60_CAPTURE_ONLY, CameraTestMode.FHD60_RECORD_ONLY))))
            .put("rootCauseVerified", false).put("precisionVerified", false)
            .put("note", "不同Surface消费者可能触发不同相机管线；差异只能指向输出通道/编码相关分支，不能单凭结果认定编码器故障。120帧结果不证明计时精度或批量时间戳映射。")
    }
    companion object {
        val modes = listOf(CameraTestMode.HD60_CAPTURE_ONLY, CameraTestMode.HD60_RECORD_ONLY,
            CameraTestMode.FHD60_CAPTURE_ONLY, CameraTestMode.FHD60_RECORD_ONLY, CameraTestMode.HD120_RECORD_ONLY)
        private fun consumer(report: JSONObject) = if (report.optBoolean("captureOnly")) "image" else "encoded"
        private fun usable(r: JSONObject): Boolean {
            if (listOf("error", "imageReaderError", "timestampError", "encodedInspectionError", "imageTimestampError").any { !r.isNull(it) }) return false
            if (r.optBoolean("cancelled") || r.optInt("imageTimestampsOmitted", 0) > 0) return false
            return listOf("capture", consumer(r)).all {
                r.optDouble(it + "Fps").let { fps -> fps.isFinite() && fps > 0 } && r.optLong(it + "SpanNs", 0) >= 5_000_000_000L
            }
        }
        fun reached(r: JSONObject): Boolean = usable(r) && r.optInt("requestedFps", 0) > 0 &&
            listOf("capture", consumer(r)).all { r.optDouble(it + "Fps") >= r.optInt("requestedFps") * .9 }
    }
}
