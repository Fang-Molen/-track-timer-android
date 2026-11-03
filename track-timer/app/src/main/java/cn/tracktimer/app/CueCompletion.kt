package cn.tracktimer.app

import org.json.JSONObject

/** Digital playback completion only. This does not establish physical audibility or acoustic latency. */
object CueCompletion {
    fun complete(report:JSONObject):Boolean = report.optBoolean("playRequested") && !report.optBoolean("focusLost") &&
        report.isNull("playbackInspectionError") && report.optLong("expectedFrames")>0 &&
        report.optLong("playbackHeadFrames")>=report.optLong("expectedFrames")
}
