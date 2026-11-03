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
        fun pair(a: CameraTestMode, b: CameraTestMode, factor: String): JSONObject {
            val first = steps.firstOrNull { it.optString("testMode") == a.name }
            val second = steps.firstOrNull { it.optString("testMode") == b.name }
            fun usable(r: JSONObject) = CameraStatistics.usable(r, "capture") && CameraStatistics.usable(r, "encoded")
            val finding = when {
                first == null || second == null || !usable(first) || !usable(second) -> "data_unavailable"
                reached(first) && reached(second) -> "both_reached"
                reached(second) -> "test_reached_only"
                reached(first) -> "baseline_reached_only"
                else -> "both_below_target"
            }
            return JSONObject().put("size", a.size.toString()).put("factor", factor).put("finding", finding)
                .put("baselineMode", a.name).put("testMode", b.name)
        }
        val pairs = JSONArray()
        for (group in listOf(modes.take(4), modes.drop(4).take(4))) {
            pairs.put(pair(group[0], group[1], "stream_use_case"))
            pairs.put(pair(group[0], group[2], "auto_exposure"))
            pairs.put(pair(group[1], group[3], "auto_exposure"))
            pairs.put(pair(group[2], group[3], "stream_use_case"))
        }
        val normalCount = steps.count(::reached)
        val high = steps.firstOrNull { it.optString("testMode") == CameraTestMode.HD120_RECORD_ONLY.name }
        return JSONObject().put("kind", "camera_comparison").put("comparisonType", "normal60_controls_v2")
            .put("runId", id).put("cameraId", cameraId)
            .put("cancelled", cancelled).put("interruptedMode", interruptedMode ?: JSONObject.NULL)
            .put("completed", !cancelled && steps.size == modes.size).put("plannedSteps", modes.size)
            .put("steps", JSONArray(steps)).put("passedStepCount", normalCount)
            .put("passedStepCountMeaning", "ordinary_sensor_and_consumer_rate_only")
            .put("normalTestCount", 8).put("normalRateReachedCount", normalCount)
            .put("highSpeedVideoRateReached", high?.let(CameraStatistics::videoRateReached) ?: false)
            .put("highSpeedEncodedFps", high?.opt("encodedFps") ?: JSONObject.NULL)
            .put("pairs", pairs).put("rootCauseVerified", false).put("precisionVerified", false)
            .put("note", "不同配置结果只提示关联，光线和温度也可能随测试顺序变化。高速单独报告视频采样帧率；不能凭稀疏元数据证明逐帧时间映射或计时精度。")
    }
    companion object {
        val modes = listOf(CameraTestMode.HD60_RECORD_ONLY, CameraTestMode.HD60_VIDEO_USE_CASE,
            CameraTestMode.HD60_MANUAL, CameraTestMode.HD60_MANUAL_VIDEO_USE_CASE,
            CameraTestMode.FHD60_RECORD_ONLY, CameraTestMode.FHD60_VIDEO_USE_CASE,
            CameraTestMode.FHD60_MANUAL, CameraTestMode.FHD60_MANUAL_VIDEO_USE_CASE, CameraTestMode.HD120_RECORD_ONLY)
        fun reached(r: JSONObject): Boolean = CameraStatistics.normalRateReached(r)
    }
}
