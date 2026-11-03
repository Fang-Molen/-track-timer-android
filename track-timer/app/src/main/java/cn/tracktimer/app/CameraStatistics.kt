package cn.tracktimer.app

import org.json.JSONArray
import org.json.JSONObject

/** Video sample cadence is evidence about the file, not proof of per-frame sensor time mapping. */
object CameraStatistics {
    fun highSpeed(r: JSONObject): Boolean = r.optString("sessionType") == "constrained_high_speed"

    private fun healthy(r: JSONObject): Boolean = !r.optBoolean("cancelled") &&
        r.optInt("imageTimestampsOmitted", 0) == 0 &&
        listOf("error", "imageReaderError", "timestampError", "encodedInspectionError", "imageTimestampError").all { r.isNull(it) }

    internal fun usable(r: JSONObject, prefix: String): Boolean = healthy(r) &&
        r.optLong(prefix + "SpanNs", 0) >= 5_000_000_000L &&
        r.optDouble(prefix + "Fps").let { it.isFinite() && it > 0 }

    private fun reached(r: JSONObject, prefix: String): Boolean = usable(r, prefix) &&
        r.optInt("requestedFps", 0) > 0 && r.optDouble(prefix + "Fps") >= r.optInt("requestedFps") * .9

    fun videoRateReached(r: JSONObject): Boolean = !r.optBoolean("captureOnly") && reached(r, "encoded")
    fun normalRateReached(r: JSONObject): Boolean = !highSpeed(r) && reached(r, "capture") &&
        reached(r, if (r.optBoolean("captureOnly")) "image" else "encoded")

    fun annotate(r: JSONObject) {
        val high = highSpeed(r)
        r.put("metadataSampleFps", r.opt("captureFps") ?: JSONObject.NULL)
            .put("captureFpsMeaning", "metadata_samples_per_second")
            .put("sensorFps", if (high) JSONObject.NULL else r.opt("captureFps") ?: JSONObject.NULL)
        val frames = r.optJSONArray("frames") ?: JSONArray()
        val counts = linkedMapOf<Long, Int>()
        var valid = frames.length() >= 2
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            if (f.optLong("frameNumber", -1) < 0 || f.optLong("sensorNs", -1) < 0) valid = false
            if (i > 0) {
                val before = frames.getJSONObject(i - 1)
                val step = f.optLong("frameNumber", -1) - before.optLong("frameNumber", -1)
                if (step <= 0 || f.optLong("sensorNs", -1) <= before.optLong("sensorNs", -1)) valid = false
                counts[step] = (counts[step] ?: 0) + 1
            }
        }
        val estimate = if (valid) {
            val first = frames.getJSONObject(0); val last = frames.getJSONObject(frames.length() - 1)
            (last.getLong("frameNumber") - first.getLong("frameNumber")) * 1e9 /
                (last.getLong("sensorNs") - first.getLong("sensorNs"))
        } else null
        r.put("metadataSampleCount", frames.length()).put("frameNumberStepCounts", JSONObject(counts.mapKeys { it.key.toString() }))
            .put("metadataFrameNumberSequenceValid", valid)
            .put("frameNumberSpanFpsEstimate", estimate ?: JSONObject.NULL)
            .put("metadataMayBeBatched", high)
        if (high) r.put("estimatedMissingCaptureFrames", JSONObject.NULL).put("captureToEncodedMappingVerified", false)
        val normal = normalRateReached(r)
        val video = videoRateReached(r)
        r.put("captureFpsAchieved", if (high) JSONObject.NULL else reached(r, "capture"))
            .put("encodedFpsAchieved", video).put("videoRateReached", video)
            .put("imageFpsAchieved", r.optBoolean("captureOnly") && reached(r, "image"))
            .put("requestedFpsAchieved", if (high) JSONObject.NULL else normal)
            .put("diagnosticFpsReached", if (high) JSONObject.NULL else normal)
            .put("rateStatus", if (high) {
                if (video) "video_rate_reached_mapping_unverified" else "video_rate_not_confirmed"
            } else if (normal) "normal_rate_reached" else "normal_rate_not_confirmed")
    }
}
