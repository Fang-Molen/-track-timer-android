package cn.tracktimer.core

import java.io.IOException

data class ReceiverFinal(val post:Calibration?,val completed:Boolean,val error:String?)
/** Worker-thread protocol driver. Camera readiness and stop requests are the only cross-thread inputs. */
class FieldReceiver(private val client:String,private val race:String,private val clock:()->Long,
    private val measure:()->Calibration,private val command:(String,String)->RaceSnapshot,
    private val onCalibration:(Calibration)->Unit,private val onSignal:(RaceSnapshot,Calibration,Long)->Unit,
    private val onStatus:(String)->Unit,private val onFinished:(ReceiverFinal)->Unit):Runnable {
    @Volatile var recordingReady=false
    @Volatile var recordingStopped=false
    @Volatile private var stopping=false
    @Volatile private var interrupted=false
    fun finish(interrupted:Boolean) {if(interrupted)this.interrupted=true;stopping=true}
    override fun run() {
        var pre:Calibration?=null;var post:Calibration?=null;var registered=false
        var signal:RaceSnapshot?=null;var completed=false;var error:String?=null
        fun checked(c:Calibration,host:String?=null):Calibration {
            require(c.localSession==client && (host==null || c.remoteSession==host)){"起点会话改变，请重新准备"}
            require(c.uncertaintyNs in 0..5_000_000L){"对时区间过宽，请靠近路由器或更换网络"};return c
        }
        fun accept(r:RaceSnapshot) {
            require(r.clientSession==client && r.raceId==race){"场次不匹配"}
            check(r.phase!=RacePhase.ABORTED){"起点已取消本场"}
            if(r.startNs>0) {
                check(signal==null || signal!!.startNs==r.startNs){"发令时间改变"}
                if(signal==null) {signal=r;onSignal(r,checkNotNull(pre),clock())}
                command(r.hostSession,"ACK")
            }
        }
        try {
            pre=checked(measure());if(!stopping)onCalibration(pre)
            while(!stopping && !recordingReady)Thread.sleep(50)
            if(!stopping) {
                // Mark before sending: a lost READY response must still be cancelled on exit.
                registered=true
                var readySent=false
                while(!stopping) {
                    try {
                        val p=checkNotNull(pre)
                        if(!readySent) {accept(command(p.remoteSession,"READY"));readySent=true}
                        accept(command(p.remoteSession,"POLL"))
                        if(signal==null && clock()-p.anchorLocalNs>=10_000_000_000L) {
                            val held=command(p.remoteSession,"HOLD");accept(held)
                            if(signal==null) {
                                onStatus("等待发令，正在刷新对时…")
                                pre=checked(measure(),p.remoteSession)
                                if(!stopping) {onCalibration(checkNotNull(pre));accept(command(p.remoteSession,"READY"))}
                            }
                        }
                        onStatus(if(signal==null)"终点录像已就绪，等待起点发令" else "已收到发令，正在录像；全部过线后停止")
                    } catch(e:RaceCommandException) {throw e}
                    catch(e:IOException) {onStatus("连接暂时中断，正在重试；录像继续")}
                    Thread.sleep(250)
                }
                val host=checkNotNull(pre).remoteSession
                if(interrupted)command(host,"CANCEL")
                else {
                    var last:Exception?=null
                    for(attempt in 0..3) {
                        try {
                            accept(command(host,"POLL"))
                            if(signal!=null) {val done=command(host,"DONE");completed=done.phase==RacePhase.DONE && done.acknowledged}
                            else command(host,"CANCEL")
                            last=null;break
                        } catch(e:Exception){last=e;if(e is RaceCommandException)break}
                    }
                    if(last!=null)throw last
                    if(signal!=null) {
                        val deadline=System.nanoTime()+30_000_000_000L
                        while(!recordingStopped && !interrupted && System.nanoTime()<deadline)Thread.sleep(50)
                        check(recordingStopped && !interrupted){"录像尚未保存或已中断，未执行结束对时"}
                        post=checked(measure(),host)
                    }
                }
            }
        } catch(e:Exception) {error=e.message ?: e.javaClass.simpleName}
        finally {
            if(!completed && registered && pre!=null)runCatching{command(pre!!.remoteSession,"CANCEL")}
            onFinished(ReceiverFinal(post,completed && !interrupted,error ?: if(interrupted)"采集中断，本场仅供复核" else null))
        }
    }
}
