package cn.tracktimer.app

import org.json.JSONArray
import org.json.JSONObject

/** Recognize Android's expanded high-speed callbacks before looking at encoder timestamps.
 * CameraDeviceImpl derives intermediate SENSOR_TIMESTAMP values, retaining the batch frame number.
 * Only the final callback in each complete, verified batch is an original sensor-clock anchor.
 */
object HighSpeedMetadata {
    data class Selection(val indices:List<Int>,val batchSize:Int,val expanded:Boolean,val valid:Boolean)

    fun select(report:JSONObject):Selection {
        val frames=report.optJSONArray("frames") ?: JSONArray()
        val all=(0 until frames.length()).toList()
        val numbers=all.map{frames.getJSONObject(it).optLong("frameNumber",-1)}
        if(numbers.zipWithNext().none{(a,b)->a==b})return Selection(all,1,false,true)
        val fps=report.optInt("requestedFps")
        val batch=if(fps in listOf(120,240))fps/30 else 0
        fun invalid()=Selection(emptyList(),batch,true,false)
        if(!CameraStatistics.highSpeed(report) || !report.optBoolean("previewEnabled") ||
            report.optInt("outputSurfaceCount")!=2 || batch==0 || frames.length()%batch!=0)return invalid()
        val anchors=mutableListOf<Int>()
        var previous:Long?=null
        for(first in 0 until frames.length() step batch) {
            val end=first+batch-1
            val number=numbers[end]
            if(number<0 || (previous!=null && number-previous!=batch.toLong()))return invalid()
            val anchor=frames.getJSONObject(end).optLong("sensorNs",-1)
            if(anchor<=0)return invalid()
            for(i in first..end) {
                val timestamp=frames.getJSONObject(i).optLong("sensorNs",-1)
                if(numbers[i]!=number || timestamp<=0 ||
                    timestamp!=anchor-(end-i)*1_000_000_000L/fps)return invalid()
            }
            anchors.add(end);previous=number
        }
        return Selection(anchors,batch,true,true)
    }
}
