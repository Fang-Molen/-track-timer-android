package cn.tracktimer.core

import kotlin.math.abs

data class SignalResult(val startLocalNs:Long,val elapsedNs:Long,val syncCheckPassed:Boolean)
object SignalTiming {
    fun calculate(finishNs:Long,startHostNs:Long,pre:Calibration,post:Calibration?,host:String,client:String):SignalResult {
        require(pre.localSession==client && pre.remoteSession==host){"对时记录与场次不匹配"}
        require(startHostNs>0 && pre.uncertaintyNs in 0..5_000_000L){"发令或对时记录无效"}
        val local=Math.subtractExact(startHostNs,pre.offsetNs)
        require(local-pre.anchorLocalNs in 0..30_000_000_000L){"发令前对时记录已过期"}
        val elapsed=Math.subtractExact(finishNs,local)
        require(elapsed>0){"当前画面在发令前，请向后选帧"}
        val good=post!=null && post.localSession==client && post.remoteSession==host &&
            post.anchorLocalNs>=finishNs && post.anchorLocalNs>=pre.anchorLocalNs &&
            post.uncertaintyNs in 0..5_000_000L && abs(post.offsetNs.toDouble()-pre.offsetNs)<=10_000_000
        return SignalResult(local,elapsed,good)
    }
}
