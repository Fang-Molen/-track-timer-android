package cn.tracktimer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.*
import android.view.TextureView
import android.widget.*
import cn.tracktimer.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@android.annotation.SuppressLint("SetTextI18n") // Chinese-only field release.
class FinishActivity:FieldActivity() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var reports:ReportStore
    private lateinit var races:RaceStore
    private lateinit var host:EditText
    private lateinit var code:EditText
    private lateinit var fps:Spinner
    private lateinit var previewEnabled:CheckBox
    private lateinit var texture:TextureView
    private lateinit var status:TextView
    private lateinit var prepare:Button
    private lateinit var stop:Button
    private var receiver:FieldReceiver?=null
    private var camera:CameraProbe?=null
    private var journal:JSONObject?=null
    private var cameraStarted=false
    private var cameraDone=false
    private var networkDone=false
    private var visible=false
    private var busy=false
    private var interrupted=false
    private var stopping=false
    private var startedNs=0L
    private var mode=CameraTestMode.HD120_FIELD

    override fun onCreate(state:Bundle?){super.onCreate(state);reports=ReportStore(this);races=RaceStore(reports)
        screen("终点 · 录像计时")
        label("先连接起点，再持续录像。手机固定在终点线延长线上，画面覆盖跑道；等所有运动员过线后停止并判线。最长180秒（含准备）。")
        host=input("起点IPv4地址，例如192.168.43.1");code=input("起点显示的6位配对码").apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        val prefs=getPreferences(MODE_PRIVATE);host.setText(prefs.getString("host",""));code.setText(prefs.getString("code",""))
        fps=Spinner(this).apply{adapter=ArrayAdapter(this@FinishActivity,android.R.layout.simple_spinner_dropdown_item,listOf("120帧 · 建议先测","240帧"));page.addView(this)}
        previewEnabled=CheckBox(this).apply{text="显示预览（启动失败时可手动关闭重试）";isChecked=true;page.addView(this)}
        texture=TextureView(this).apply{page.addView(this,LinearLayout.LayoutParams(-1,dp(230)))}
        status=label("尚未开始",18)
        prepare=action("连接、对时并开始录像"){
            if(Build.VERSION.SDK_INT<28)message("终点逐帧判线需要Android 9或以上")
            else if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),91)
            else begin()
        }
        stop=action("停止录像并判线"){
            if(startedNs>0 && SystemClock.elapsedRealtimeNanos()-startedNs<6_000_000_000L)message("请至少录满6秒，再停止")
            else stopRace(false)
        }.apply{isEnabled=false}
        action("查看历史场次"){if(!busy)startActivity(Intent(this,RaceReviewActivity::class.java)) else message("请先停止当前录像")}
        label("只请求相机权限。本流程不录麦克风。请保持前台，来电或切后台将把本场标为中断。",14)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(requestCode,permissions,results)
        if(requestCode==91 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED && visible)begin()}
    private fun begin(){
        if(busy || !visible)return
        val ip=host.text.toString().trim();val token=code.text.toString().trim()
        if(!ip.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || ip.split('.').any{it.toInt()>255} || !token.matches(Regex("[0-9]{6}"))){message("请输入有效IPv4地址和6位配对码");return}
        if(previewEnabled.isChecked && !texture.isAvailable){message("预览尚未准备好，请再试一次");return}
        getPreferences(MODE_PRIVATE).edit().putString("host",ip).putString("code",token).apply()
        val high=fps.selectedItemPosition==1
        mode=if(previewEnabled.isChecked){if(high)CameraTestMode.HD240_FIELD else CameraTestMode.HD120_FIELD}else{if(high)CameraTestMode.HD240_CLOCK else CameraTestMode.HD120_CLOCK}
        val id=UUID.randomUUID().toString()
        journal=JSONObject().put("kind","field_race").put("raceId",id).put("clientSession",AppSession.id)
            .put("createdAtUnixMs",System.currentTimeMillis()).put("testMode",mode.name).put("results",JSONArray()).put("status","preparing")
        busy=true;interrupted=false;stopping=false;cameraStarted=false;cameraDone=false;networkDone=false;startedNs=0
        controls(false);stop.isEnabled=true;status.text="正在连接并进行48轮对时…"
        save()
        val r=FieldReceiver(AppSession.id,id,SystemClock::elapsedRealtimeNanos,
            {
                val measured=SyncClient.measure(ip,19119,token,AppSession.id,SystemClock::elapsedRealtimeNanos)
                reports.saveJson("field_sync",JSONObject().put("raceId",id).put("calibration",CalibrationJson.encode(measured.calibration))
                    .put("exchanges",JSONArray(measured.samples.map { e->JSONObject().put("t1",e.t1).put("t2",e.t2).put("t3",e.t3).put("t4",e.t4) })))
                measured.calibration
            },
            {session,verb->RaceClient.request(ip,19119,token,session,AppSession.id,id,verb)},
            {cal->main.post {
                if(!busy || stopping || !visible)return@post
                journal?.put("hostSession",cal.remoteSession)?.put("preCalibration",CalibrationJson.encode(cal));save()
                if(!cameraStarted)startCamera(id)
            }},
            {signal,cal,received->main.post {
                journal?.put("startHostNs",signal.startNs)?.put("signalReceivedLocalNs",received)?.put("preCalibration",CalibrationJson.encode(cal))?.put("status","fired");save()
            }},
            {text->main.post{if(busy && !stopping)status.text=text}},
            {result->main.post {
                networkDone=true;journal?.put("networkCompleted",result.completed)?.put("networkError",result.error ?: JSONObject.NULL)
                result.post?.let{journal?.put("postCalibration",CalibrationJson.encode(it))}
                if(!cameraStarted)cameraDone=true
                if(result.error!=null && !stopping) {status.text="通信结束：${result.error}";stopRace(true)}
                save();maybeFinished()
            }})
        receiver=r;Thread(r,"field-receiver").apply{isDaemon=true;start()}
    }
    private fun startCamera(id:String){
        cameraStarted=true;status.text="对时完成，正在启动${mode.fps}帧录像…"
        camera=CameraProbe(this,reports,texture,{text->if(!stopping)status.text=text},{report->
            camera=null;cameraDone=true;receiver?.recordingStopped=true
            journal?.put("cameraReportFile",report.optString("reportFile"))?.put("videoFile",report.optString("videoFile"))
                ?.put("cameraError",report.opt("error") ?: JSONObject.NULL)
            if(!stopping)stopRace(false)
            save();maybeFinished()
        },mode,id,durationMs=180_000,onReady={
            if(!stopping && visible){startedNs=SystemClock.elapsedRealtimeNanos();receiver?.recordingReady=true
                journal?.put("status","ready");save();status.text="终点已就绪，等待起点发令"}
        })
        if(mode.previewEnabled)runCatching {
            val c=DeviceReport.cameraConfig(this,mode);val rotation=c.sensorOrientation
            val w=texture.width.toFloat();val h=texture.height.toFloat();val bw=c.size.width.toFloat();val bh=c.size.height.toFloat()
            val quarter=rotation%180!=0;val scale=minOf(w/(if(quarter)bh else bw),h/(if(quarter)bw else bh))
            texture.setTransform(Matrix().apply{setScale(scale*bw/w,scale*bh/h,w/2,h/2);postRotate(rotation.toFloat(),w/2,h/2)})
        }
        camera?.start()
    }
    private fun stopRace(aborted:Boolean){
        if(!busy)return
        interrupted=interrupted || aborted;stopping=true;stop.isEnabled=false
        journal?.put("interrupted",interrupted)?.put("status","finishing");save()
        status.text="正在保存录像并复核对时，请稍候…"
        camera?.complete();receiver?.finish(interrupted)
        if(!cameraStarted)cameraDone=true
        maybeFinished()
    }
    private fun maybeFinished(){
        if(!busy || !cameraDone || !networkDone)return
        busy=false;receiver=null;camera=null;controls(true);stop.isEnabled=false
        journal?.put("status",if(interrupted)"interrupted" else "saved");save()
        val id=journal?.optString("raceId")
        status.text="本场已保存。${journal?.optString("networkError")?.takeIf{it!="null"}.orEmpty()}"
        if(visible && !interrupted && !id.isNullOrEmpty())startActivity(Intent(this,RaceReviewActivity::class.java).putExtra("raceId",id))
    }
    private fun controls(enabled:Boolean){prepare.isEnabled=enabled;host.isEnabled=enabled;code.isEnabled=enabled;fps.isEnabled=enabled;previewEnabled.isEnabled=enabled}
    private fun save(){journal?.let{runCatching{races.save(it)}.onFailure{status.text="保存失败：${it.message}";interrupted=true}}}
    override fun onResume(){super.onResume();visible=true}
    override fun onPause(){visible=false;if(busy)stopRace(true);super.onPause()}
}
