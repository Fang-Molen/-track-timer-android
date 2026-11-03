package cn.tracktimer.app

import android.os.*
import android.media.AudioManager
import android.widget.*
import cn.tracktimer.core.*
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

@android.annotation.SuppressLint("SetTextI18n") // Chinese-only field release.
class StartActivity:FieldActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var coordinator:RaceCoordinator?=null
    private var server:SyncServer?=null
    private var tone:TestTonePlayer?=null
    private lateinit var status:TextView
    private lateinit var fire:Button
    private lateinit var open:Button
    private lateinit var store:ReportStore
    private var visible=false
    private var soundNote=""
    private val tick=object:Runnable {
        override fun run(){if(!visible)return
            val s=coordinator?.current();fire.isEnabled=s?.readyFresh==true
            status.text=when(s?.phase){
                RacePhase.READY -> if(s.readyFresh)"终点已就绪，可以发令" else "等待终点连接或刷新对时，暂不能发令"
                RacePhase.FIRED -> "已发令 · ${if(s.acknowledged)"终点已回执" else "等待终点回执"}\n全部跑完后，在终点停止录像"
                RacePhase.DONE -> "终点正在保存与复核对时，请保持本页，直到终点进入判线"
                RacePhase.ABORTED -> "本场已取消；请在终点重新准备"
                null -> if(server==null)"先开启起点服务" else "等待终点输入地址和配对码"
            }+soundNote
            handler.postDelayed(this,250)
        }
    }
    override fun onCreate(state:Bundle?){super.onCreate(state);store=ReportStore(this);volumeControlStream=AudioManager.STREAM_MUSIC
        screen("起点 · 发令")
        label("两台手机连接同一局域网。起点放在运动员附近，媒体音量调高。保持本页亮屏，切后台将取消本场。")
        val address=label("尚未开启",19)
        open=action("开启起点服务"){
            runCatching {
                val session=UUID.randomUUID().toString();val code=(SecureRandom().nextInt(900000)+100000).toString()
                val c=RaceCoordinator(session,SystemClock::elapsedRealtimeNanos,requireCue=true)
                val s=SyncServer(code,session,SystemClock::elapsedRealtimeNanos,c)
                try{s.start(19119)}catch(e:Exception){s.close();throw e}
                coordinator=c;server=s;open.isEnabled=false
                val ips=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}
                    .filterIsInstance<Inet4Address>().filter{!it.isLoopbackAddress}.mapNotNull{it.hostAddress}
                address.text="起点地址：${ips.joinToString(" / ").ifEmpty{"未找到，请先连接Wi-Fi或开启热点"}}\n端口：19119\n配对码：$code"
            }.onFailure{message("无法开启：${it.message}")}
        }
        status=label("先开启起点服务",20)
        fire=action("发令一次") { fireOnce() }.apply{isEnabled=false}
        action("取消本场") {coordinator?.cancelActive();soundNote=""}
        label("计时零点是点击发令后记录的电子事件，提示音稍后输出。声音延迟尚未校准，成绩用于训练实测，不能当作已验证的全自动电计时。",14)
    }
    private fun fireOnce(){
        var fired:RaceSnapshot?=null
        val player=TestTonePlayer(this,48_000)
        runCatching {
            player.prepare();tone=player
            val raceHost=checkNotNull(coordinator)
            fired=raceHost.fire();fire.isEnabled=false
            player.play()
            val event=checkNotNull(fired)
            store.saveJson("field_start",JSONObject().put("kind","field_start").put("raceId",event.raceId)
                .put("hostSession",event.hostSession).put("clientSession",event.clientSession).put("startHostNs",event.startNs)
                .put("acousticLatencyCalibrated",false).put("precisionVerified",false))
            soundNote="\n提示音已请求播放，请现场确认听到"
            handler.postDelayed({
                val report=player.snapshot().put("raceId",event.raceId).put("startHostNs",event.startNs)
                val complete=CueCompletion.complete(report)
                report.put("cuePlaybackComplete",complete)
                if(!complete)report.put("cueFailure","提示音未完整播放或音频焦点中断")
                val saved=runCatching{store.saveJson("field_cue",report)}.isSuccess
                raceHost.confirmCue(event.raceId,complete && saved)
                if((!complete || !saved) && coordinator===raceHost && raceHost.current()?.raceId==event.raceId) {
                    soundNote="\n提示音或记录失败，本场已取消，请重新准备"
                    message("提示音未完整播放或记录失败，本场已取消")
                }
                player.close();if(tone===player)tone=null
            },700)
        }.onFailure{
            if(fired!=null)coordinator?.cancelActive()
            player.close();tone=null;soundNote="\n发令失败：${it.message}";message("${it.message}")
        }
    }
    override fun onResume(){super.onResume();visible=true;handler.post(tick)}
    override fun onPause(){visible=false;handler.removeCallbacks(tick);coordinator?.cancelActive();server?.close();server=null;tone?.close();tone=null
        if(::open.isInitialized)open.isEnabled=true;super.onPause()}
}
