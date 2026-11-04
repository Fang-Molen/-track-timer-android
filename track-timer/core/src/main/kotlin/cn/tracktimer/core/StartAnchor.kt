package cn.tracktimer.core

import java.util.Locale

enum class StartSource(val label:String) { ELECTRONIC("电子发令"), MANUAL("手动开始") }
/**
 * Where a start moment (finish-phone clock) falls inside the recorded video.
 * [frameIndex] is the first frame at or after the start; [fraction] is the position between
 * the previous frame (0) and [frameIndex] (1). [inRange] is false when the start is outside the video.
 */
data class StartAnchor(val source:StartSource,val startLocalNs:Long,val frameIndex:Int,val fraction:Double,
    val inRange:Boolean,val uncertaintyNs:Long) {
    val referenceOnly:Boolean get()=source==StartSource.MANUAL || uncertaintyNs>SyncLimits.STRICT_NS
    companion object {
        fun locate(source:StartSource,startLocalNs:Long,count:Int,uncertaintyNs:Long,timestamp:(Int)->Long):StartAnchor {
            require(count>=1)
            if(startLocalNs<=timestamp(0))return StartAnchor(source,startLocalNs,0,1.0,startLocalNs==timestamp(0),uncertaintyNs)
            if(startLocalNs>timestamp(count-1))return StartAnchor(source,startLocalNs,count-1,1.0,false,uncertaintyNs)
            var lo=1;var hi=count-1            // first index with timestamp >= start
            while(lo<hi){val mid=(lo+hi) ushr 1;if(timestamp(mid)>=startLocalNs)hi=mid else lo=mid+1}
            val a=timestamp(lo-1);val b=timestamp(lo)
            return StartAnchor(source,startLocalNs,lo,(startLocalNs-a).toDouble()/(b-a),true,uncertaintyNs)
        }
    }
    /** Overlay text for a frame captured at [frameNs] (finish-phone clock). */
    fun overlay(frameNs:Long):String {
        val d=(frameNs-startLocalNs)/1e9
        val sign=if(d>=0)"+" else "−"
        val tail=if(referenceOnly)" · 仅供参考" else ""
        val unc=if(source==StartSource.ELECTRONIC)" ±${String.format(Locale.CHINA,"%.1f",uncertaintyNs/1e6)}ms" else ""
        return "距${source.label} $sign${String.format(Locale.CHINA,"%.3f",kotlin.math.abs(d))} 秒$unc$tail"
    }
    /** Marker position along a 0..1 timeline for a video of [count] frames. */
    fun timelinePosition(count:Int):Double = if(count<=1) 0.0 else ((frameIndex-1+fraction).coerceIn(0.0,(count-1).toDouble()))/(count-1)
}
