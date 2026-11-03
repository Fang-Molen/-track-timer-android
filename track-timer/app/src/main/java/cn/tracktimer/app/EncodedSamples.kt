package cn.tracktimer.app

/** Preserve the encoder clock; normalizing for MP4 must never overwrite these observations. */
class EncodedSamples(private val limit: Int = 6000) {
    private val points = ArrayList<Long>()
    private val observations = ArrayList<Long>()
    val ptsNs: List<Long> get() = points
    val observedNs: List<Long> get() = observations

    fun checkComplete(eos: Boolean) {
        check(eos) { "编码未收到结束标志" }
        check(points.size >= 2) { "编码未生成足够的视频帧" }
    }

    fun add(ptsUs: Long, observedNs: Long): Long {
        check(points.size < limit) { "编码时间戳超过诊断存储上限" }
        require(ptsUs > 0 && ptsUs <= Long.MAX_VALUE / 1000 && observedNs > 0) { "无效编码时间戳" }
        val ns = ptsUs * 1000
        require(points.isEmpty() || ns > points.last()) { "编码时间戳重复或倒退" }
        points.add(ns); observations.add(observedNs)
        return (ns - points.first()) / 1000
    }
}
