package cn.tracktimer.app

import cn.tracktimer.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class CueCompletionTest {
    private fun snapshot(frames:Long=7200,focusLost:Boolean=false)=JSONObject().put("playRequested",true).put("expectedFrames",7200)
        .put("playbackHeadFrames",frames).put("focusLost",focusLost)
    @Test fun interruptedIncompleteOrMissingPlaybackCannotCompleteCue() {
        assertFalse(CueCompletion.complete(snapshot(100,true)))
        assertFalse(CueCompletion.complete(snapshot(100)))
        assertFalse(CueCompletion.complete(JSONObject()))
        assertFalse(CueCompletion.complete(snapshot().put("playbackInspectionError","failed")))
        assertTrue(CueCompletion.complete(snapshot()))
    }
}
