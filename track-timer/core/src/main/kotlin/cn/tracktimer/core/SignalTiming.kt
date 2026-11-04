package cn.tracktimer.core

import kotlin.math.abs

object SyncLimits {
    /** Results above this clock bound are 仅供参考 (reference only). */
    const val STRICT_NS = 5_000_000L
    /** Hard limit for the internet relay; beyond this the race cannot be timed from the e-start. */
    const val RELAY_MAX_NS = 50_000_000L
}
data class SignalResult(val startLocalNs:Long,val elapsedNs:Long,val syncCheckPassed:Boolean,val startUncertaintyNs:Long=0)
object SignalTiming {
    /**
     * Converts the start phone's e-start clock reading into the finish phone clock (offset = host - finish).
     * A pre-calibration wider than [STRICT_NS] (allowed only up to [maxUncertaintyNs]) is never "passed".
     */
    fun calculate(finishNs:Long,startHostNs:Long,pre:Calibration,post:Calibration?,host:String,client:String,
        maxUncertaintyNs:Long=SyncLimits.STRICT_NS):SignalResult {
        val local=startLocal(startHostNs,pre,host,client,maxUncertaintyNs)
        val elapsed=Math.subtractExact(finishNs,local)
        require(elapsed>0){"当前画面在发令前，请向后选帧"}
        val good=pre.uncertaintyNs<=SyncLimits.STRICT_NS && post!=null && post.localSession==client && post.remoteSession==host &&
            post.anchorLocalNs>=finishNs && post.anchorLocalNs>=pre.anchorLocalNs &&
            post.uncertaintyNs in 0..SyncLimits.STRICT_NS && abs(post.offsetNs.toDouble()-pre.offsetNs)<=10_000_000
        return SignalResult(local,elapsed,good,pre.uncertaintyNs)
    }
    fun startLocal(startHostNs:Long,pre:Calibration,host:String,client:String,maxUncertaintyNs:Long=SyncLimits.STRICT_NS):Long {
        require(pre.localSession==client && pre.remoteSession==host){"对时记录与场次不匹配"}
        require(startHostNs>0 && pre.uncertaintyNs in 0..maxUncertaintyNs){"发令或对时记录无效"}
        val local=Math.subtractExact(startHostNs,pre.offsetNs)
        require(local-pre.anchorLocalNs in 0..30_000_000_000L){"发令前对时记录已过期"}
        return local
    }
}
