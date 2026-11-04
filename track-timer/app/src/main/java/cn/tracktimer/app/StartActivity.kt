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
    private var relay:RelayLink?=null
    private lateinit var relayMode:CheckBox
    private lateinit var relayUrl:EditText
    private lateinit var flash:CheckBox
    private var flashView:android.view.View?=null
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
                null -> when {
                    relay!=null -> if(relay?.isOpen==false)"服务器连接已断开：${relay?.failure}\n请重新开启，或改用同一局域网" else if(relay?.peerJoined==true)"终点已通过服务器连上，正在对时…" else "等待终点输入配对码（10分钟内有效，只能用一次）"
                    server==null -> "先开启起点服务"
                    else -> "等待终点输入地址和配对码"
                }
            }+soundNote
            handler.postDelayed(this,250)
        }
    }
    override fun onCreate(state:Bundle?){super.onCreate(state);store=ReportStore(this);volumeControlStream=AudioManager.STREAM_MUSIC
        screen("起点 · 发令")
        label("两台手机连同一局域网，或勾选“通过服务器连接”后各用各的网络。起点放在运动员附近，媒体音量调高。保持本页亮屏，切后台将取消本场。")
        val prefs=getPreferences(MODE_PRIVATE)
        relayMode=CheckBox(this).apply{text="通过服务器连接（两台手机可用不同网络）";isChecked=prefs.getBoolean("relay",false);page.addView(this)}
        relayUrl=input("服务器地址，例如 wss://example.com/tt-relay").apply{setText(prefs.getString("relayUrl",BuildConfig.RELAY_URL))}
        flash=CheckBox(this).apply{text="发令时闪屏（让终点录像里也能看到发令）";isChecked=prefs.getBoolean("flash",true);page.addView(this)}
        val address=label("尚未开启",19)
        open=action("开启起点服务"){
            prefs.edit().putBoolean("relay",relayMode.isChecked).putString("relayUrl",relayUrl.text.toString().trim()).putBoolean("flash",flash.isChecked).apply()
            if(relayMode.isChecked){openRelay(address);return@action}
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
    private fun openRelay(address:TextView){
        val url=relayUrl.text.toString().trim()
        if(!url.startsWith("wss://") && !(BuildConfig.DEBUG && url.startsWith("ws://"))){message("服务器地址需以 wss:// 开头");return}
        open.isEnabled=false;relayMode.isEnabled=false;address.text="正在连接服务器…"
        val session=UUID.randomUUID().toString()
        val c=RaceCoordinator(session,SystemClock::elapsedRealtimeNanos,requireCue=true)
        Thread({
            var responder:SyncResponder?=null
            val r=runCatching{RelayLink.host(url,SystemClock::elapsedRealtimeNanos){line,received->responder?.respond(line,received)?.text}}
            r.onSuccess{responder=SyncResponder(it.code,session,SystemClock::elapsedRealtimeNanos,c)}
            handler.post {
                r.onSuccess{link->
                    if(!visible){link.close();return@post}
                    coordinator=c;relay=link
                    address.text="已连接服务器\n配对码：${link.code}\n在终点勾选“通过服务器连接”，输入这个码（10分钟内有效，只能用一次）"
                }.onFailure{address.text="服务器连接失败：${it.message}\n可取消勾选，改用同一局域网";open.isEnabled=true;relayMode.isEnabled=true}
            }
        },"relay-open").apply{isDaemon=true;start()}
    }
    private fun flashOnce(){
        if(!flash.isChecked)return
        val root=window.decorView as? android.widget.FrameLayout ?: return
        val v=flashView ?: android.view.View(this).apply{setBackgroundColor(android.graphics.Color.WHITE);flashView=this
            root.addView(this,android.widget.FrameLayout.LayoutParams(-1,-1))}
        v.visibility=android.view.View.VISIBLE;v.bringToFront()
        handler.postDelayed({v.visibility=android.view.View.GONE},200)
    }
    private fun fireOnce(){
        var fired:RaceSnapshot?=null
        val player=TestTonePlayer(this,48_000)
        runCatching {
            player.prepare();tone=player
            val raceHost=checkNotNull(coordinator)
            fired=raceHost.fire();fire.isEnabled=false
            flashOnce()
            player.play()
            val event=checkNotNull(fired)
            store.saveJson("field_start",JSONObject().put("kind","field_start").put("raceId",event.raceId)
                .put("hostSession",event.hostSession).put("clientSession",event.clientSession).put("startHostNs",event.startNs)
                .put("transport",if(relay!=null)"relay" else "lan").put("flashRequested",flash.isChecked)
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
    override fun onPause(){visible=false;handler.removeCallbacks(tick);coordinator?.cancelActive();server?.close();server=null;relay?.close();relay=null;flashView?.visibility=android.view.View.GONE
        if(::relayMode.isInitialized)relayMode.isEnabled=true;tone?.close();tone=null
        if(::open.isInitialized)open.isEnabled=true;super.onPause()}
}
