package cn.tracktimer.app

/** Frame availability and the operator's alignment confirmation are separate readiness conditions. */
class FinishAlignment(private val publishReady:(Float)->Boolean) {
    private var framesAvailable=false
    private var confirmed=false
    private var cancelled=false
    val canConfirm:Boolean get()=framesAvailable && !confirmed && !cancelled
    fun onFramesAvailable(){if(!cancelled)framesAvailable=true}
    fun confirm(fraction:Float):Boolean {
        if(!canConfirm || !fraction.isFinite() || fraction !in 0f..1f)return false
        if(!publishReady(fraction))return false
        confirmed=true;return true
    }
    fun cancel(){cancelled=true}
}
