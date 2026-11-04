package cn.tracktimer.app
import org.junit.Assert.*
import org.junit.Test
class PreviewHandoffTest {
    @Test fun recorderWaitsForActualCameraReleaseAndStartsOnlyOnce(){
        var starts=0;var released:((Boolean)->Unit)?=null;val handoff=PreviewHandoff{starts++}
        handoff.afterClose{released=it};assertEquals(0,starts);assertTrue(handoff.pending)
        released!!(true);released!!(true);assertEquals(1,starts);assertFalse(handoff.pending)
    }
    @Test fun pauseAndFailedReleaseRejectLateSuccess(){
        for(cancel in listOf(true,false)){
            var starts=0;var released:((Boolean)->Unit)?=null;val handoff=PreviewHandoff{starts++}
            handoff.afterClose{released=it}
            if(cancel)handoff.cancel() else released!!(false)
            released!!(true);assertEquals(0,starts)
        }
    }
}
