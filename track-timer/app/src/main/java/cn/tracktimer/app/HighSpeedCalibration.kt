package cn.tracktimer.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Observed agreement of sparse samples, not verification of every optical exposure. */
object HighSpeedCalibration {
    private const val TOLERANCE_NS = 2_000L
    private fun longs(r: JSONObject, key: String): List<Long> = r.optJSONArray(key)?.let { a ->
        (0 until a.length()).map { a.optLong(it, -1L) }
    }.orEmpty()
    private fun increasing(a: List<Long>, allowZero: Boolean = false): Boolean =
        a.size >= 2 && a.all { if (allowZero) it >= 0 else it > 0 } && a.zipWithNext().all { (a,b) -> b > a }

    fun annotate(r: JSONObject) {
        val raw = longs(r, "codecPtsNs")
        val observed = longs(r, "codecObservedElapsedNs")
        val file = longs(r, "encodedPtsNs")
        val frames = r.optJSONArray("frames") ?: JSONArray()
        val sensors = (0 until frames.length()).map { frames.getJSONObject(it).optLong("sensorNs", -1L) }
        val numbers = (0 until frames.length()).map { frames.getJSONObject(it).optLong("frameNumber", -1L) }
        val callbacks = (0 until frames.length()).map { frames.getJSONObject(it).optLong("callbackElapsedNs", -1L) }
        val rawValid = increasing(raw)
        val sensorValid = increasing(sensors) && increasing(numbers, true) && sensors.indices.all {
            callbacks[it] >= sensors[it] && callbacks[it] - sensors[it] <= 2_000_000_000L
        }
        val codecClockPlausible = rawValid && observed.size == raw.size && raw.indices.all {
            observed[it] >= raw[it] && observed[it] - raw[it] <= 2_000_000_000L
        }
        val matches = JSONArray()
        var eligible = 0
        var maxResidual = 0L
        var firstMatched: Long? = null
        var lastMatched: Long? = null
        if (rawValid && sensorValid) {
            eligible = sensors.count { it >= raw.first()-TOLERANCE_NS && it <= raw.last()+TOLERANCE_NS }
            var cursor = 0
            for (sensor in sensors) {
                if (sensor < raw.first() - TOLERANCE_NS || sensor > raw.last() + TOLERANCE_NS) continue
                if (cursor == raw.size) continue
                while (cursor+1 < raw.size && abs(raw[cursor+1]-sensor) < abs(raw[cursor]-sensor)) cursor++
                val residual = sensor - raw[cursor]
                if (abs(residual) <= TOLERANCE_NS) {
                    matches.put(JSONObject().put("sensorNs",sensor).put("codecIndex",cursor)
                        .put("codecPtsNs",raw[cursor]).put("sensorMinusCodecNs",residual))
                    if (firstMatched == null) firstMatched = sensor
                    lastMatched = sensor
                    maxResidual = maxOf(maxResidual,abs(residual))
                    cursor++ // Each encoded sample can be used only once.
                }
            }
        }
        val span = if (rawValid) raw.last()-raw.first() else 0L
        val matchedSpan = if (firstMatched != null && lastMatched != null) lastMatched-firstMatched else 0L
        val coverage = if (span > 0) matchedSpan.toDouble()/span else 0.0
        val fileMatches = rawValid && increasing(file, true) && raw.size == file.size && raw.indices.all {
            abs((raw[it]-raw.first())-file[it]) <= TOLERANCE_NS
        }
        val healthy = CameraStatistics.videoRateReached(r) && r.optBoolean("codecEosReached") && r.isNull("codecError")
        val aligned = healthy && r.optBoolean("cameraClockRealtime") && codecClockPlausible && sensorValid &&
            matches.length() >= 30 && matches.length() == eligible && matchedSpan >= 5_000_000_000L && coverage >= .8
        val mapped = aligned && fileMatches
        val c = JSONObject().put("method","direct_sensor_encoder_pts_no_offset_fit")
            .put("toleranceNs",TOLERANCE_NS).put("rawTimestampSequenceValid",rawValid)
            .put("sensorMetadataSequenceValid",sensorValid).put("codecClockPlausible",codecClockPlausible)
            .put("metadataCount",sensors.size).put("eligibleMetadataCount",eligible)
            .put("matchedMetadataCount",matches.length()).put("matchedSpanNs",matchedSpan).put("overlapCoverage",coverage)
            .put("matches",matches).put("maxMatchedResidualNs",if (matches.length()>0) maxResidual else JSONObject.NULL)
            .put("rawCodecFps",if (span>0) (raw.size-1)*1e9/span else JSONObject.NULL)
            .put("rawCodecMaxGapNs",if (rawValid) raw.zipWithNext().maxOf { (a,b)->b-a } else JSONObject.NULL)
            .put("filePtsPreserved",fileMatches).put("sampledClockAlignmentObserved",aligned)
            .put("fileClockMappingObserved",mapped)
            .put("fileZeroLocalElapsedNs",if (mapped) raw.first() else JSONObject.NULL)
            .put("note","仅检查返回的采样时间戳与编码时间戳；不证明每帧独立曝光、光学时刻或整套计时精度。未拟合偏移来制造通过。")
        r.put("clockCalibration",c).put("captureToEncodedMappingVerified",false).put("precisionVerified",false)
    }
}
