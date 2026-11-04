package cn.tracktimer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.TextureView
import android.view.View
import android.view.Gravity
import android.graphics.Color
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
    private lateinit var line:FinishLineView
    private lateinit var setup:LinearLayout
    private lateinit var previewCover:TextView
    private lateinit var guide:TextView
    private lateinit var liveBadge:TextView
    private lateinit var confirm:Button
    private lateinit var historyButton:Button
    private lateinit var previewSizer:VideoFrameSizer
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
    private var previewConfig:CameraConfig?=null
    private var mode=CameraTestMode.HD120_FIELD
    private var hasSignal=false
    private var alignment=FinishAlignment(::publishAlignedReference)
    private val tick=object:Runnable {
        override fun run(){if(!visible)return
            if(busy && startedNs>0){val seconds=(SystemClock.elapsedRealtimeNanos()-startedNs)/1_000_000_000
                liveBadge.text="● ${mode.fps}帧模式 · 已录约 ${seconds/60}:${(seconds%60).toString().padStart(2,'0')} / 3:00"}
            main.postDelayed(this,500)
        }
    }

    override fun onCreate(state:Bundle?){super.onCreate(state);reports=ReportStore(this);races=RaceStore(reports)
        screen("终点取景","连接 → 对线确认 → 等待发令 → 停止判线")
        setup=card();label("连接起点手机",20,setup)
        caption("填写起点显示的地址和配对码。两台手机需在同一局域网。",setup)
        host=input("起点IPv4地址，例如192.168.43.1",setup);code=input("起点显示的6位配对码",setup).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        val prefs=getPreferences(MODE_PRIVATE);host.setText(prefs.getString("host",""));code.setText(prefs.getString("code",""))
        caption("录像模式",setup)
        fps=Spinner(this).apply{adapter=ArrayAdapter(this@FinishActivity,android.R.layout.simple_spinner_dropdown_item,listOf("120帧 · 推荐","240帧"));minimumHeight=dp(48);setup.addView(this)}
        previewEnabled=CheckBox(this).apply{text="显示实时预览";isChecked=true;minimumHeight=dp(48);setup.addView(this)}
        caption("连接后即开始录像，对线时间计入3分钟上限。",setup)
        status=label("尚未开始",18)
        liveBadge=caption("终点参考线 · 左右拖动调整")
        val frame=FrameLayout(this).apply{setBackgroundColor(Color.rgb(17,34,40))};page.addView(frame,LinearLayout.LayoutParams(-1,dp(230)))
        texture=TextureView(this).apply{
            frame.addView(this,FrameLayout.LayoutParams(-1,-1))
            addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->updatePreviewTransform() }
        }
        previewCover=TextView(this).apply{text="实时取景\n连接起点后开启相机";textSize=18f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(17,34,40));frame.addView(this,FrameLayout.LayoutParams(-1,-1))}
        line=FinishLineView(this).apply{fraction=prefs.getFloat("finish_line_fraction",.5f);contentDescription="终点参考线，左右拖动调整";frame.addView(this,FrameLayout.LayoutParams(-1,-1))
            onPositionCommitted={f->prefs.edit().putFloat("finish_line_fraction",f).apply();if(busy){journal?.put("finishLineFraction",f);save()}}
        }
        previewSizer=VideoFrameSizer(frame)
        guide=caption("把手机固定在终点线延长线上，调平机位，让跑道终点线靠近起点的一侧与红线重合。")
        prepare=action("连接、对时并开始录像",footer,primary=true){
            if(Build.VERSION.SDK_INT<28)message("终点逐帧判线需要Android 9或以上")
            else if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.CAMERA),91)
            else begin()
        }
        confirm=action("对线完成，允许发令",footer,primary=true){
            if(alignment.confirm(line.fraction)){line.isEnabled=false;confirm.visibility=View.GONE;status.text="机位已确认，正在通知起点";guide.text="红线已锁定，请保持机位不动。等待起点发令。"}
            else message("请等待相机画面就绪；若保存失败，请取消本场重试")
        }.apply{isEnabled=false;visibility=View.GONE}
        stop=action("停止录像并判线",footer,danger=true){
            if(startedNs>0 && SystemClock.elapsedRealtimeNanos()-startedNs<6_000_000_000L)message("请至少录满6秒，再停止")
            else stopRace(!hasSignal)
        }.apply{isEnabled=false;visibility=View.GONE}
        historyButton=action("查看历史场次"){if(!busy)startActivity(Intent(this,RaceReviewActivity::class.java)) else message("请先停止当前录像")}
        caption("请保持本页前台。所有运动员过线后再停止录像。")
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
            .put("finishLineFraction",line.fraction).put("alignmentConfirmed",false)
        busy=true;interrupted=false;stopping=false;cameraStarted=false;cameraDone=false;networkDone=false;startedNs=0
        hasSignal=false;alignment=FinishAlignment(::publishAlignedReference);line.isEnabled=true
        line.visibility=if(mode.previewEnabled)View.VISIBLE else View.INVISIBLE
        previewCover.visibility=View.VISIBLE;previewCover.text=if(mode.previewEnabled)"正在连接起点…" else "预览已关闭\n请先确认机位固定，再允许发令"
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
                hasSignal=true;guide.text="已收到发令。保持手机固定，等所有运动员过线后停止。"
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
        camera=CameraProbe(this,reports,texture,{text->if(!stopping && startedNs==0L)status.text=text},{report->
            camera=null;cameraDone=true;receiver?.recordingStopped=true
            journal?.put("cameraReportFile",report.optString("reportFile"))?.put("videoFile",report.optString("videoFile"))
                ?.put("cameraError",report.opt("error") ?: JSONObject.NULL)
            if(!stopping)stopRace(false)
            save();maybeFinished()
        },mode,id,durationMs=180_000,onReady={
            if(!stopping && visible){startedNs=SystemClock.elapsedRealtimeNanos();alignment.onFramesAvailable();confirm.isEnabled=alignment.canConfirm
                if(mode.previewEnabled)previewCover.visibility=View.GONE
                journal?.put("status","aligning");save();status.text="画面已就绪，请对齐终点线"
                guide.text=if(mode.previewEnabled)"调平机位，让实际终点线与红线重合；左右拖动红线微调，然后点击下方确认。" else "无预览模式：请先确认机位覆盖终点线，再点击下方确认。"
            }
        })
        if(mode.previewEnabled)runCatching {
            previewConfig=DeviceReport.cameraConfig(this,mode)
            updatePreviewTransform()
        }
        camera?.start()
    }
    private fun updatePreviewTransform(){
        val c=previewConfig ?: return
        if(!mode.previewEnabled || texture.width<=0 || texture.height<=0)return
        @Suppress("DEPRECATION")
        val displayRotation=windowManager.defaultDisplay.rotation*90
        val swap=(c.sensorOrientation-displayRotation)%180!=0
        previewSizer.setImageSize(if(swap)c.size.height else c.size.width,if(swap)c.size.width else c.size.height)
        line.setImageSize(if(swap)c.size.height else c.size.width,if(swap)c.size.width else c.size.height)
        texture.setTransform(CameraPreviewTransform.matrix(texture.width,texture.height,c.size.width,c.size.height,c.sensorOrientation,displayRotation))
    }
    private fun stopRace(aborted:Boolean){
        if(!busy)return
        interrupted=interrupted || aborted;stopping=true;stop.isEnabled=false
        alignment.cancel();confirm.isEnabled=false;line.isEnabled=false
        journal?.put("interrupted",interrupted)?.put("status","finishing")?.put("finishLineFraction",line.fraction);save()
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
    private fun publishAlignedReference(fraction:Float):Boolean {
        val r=receiver ?: return false
        if(!busy || stopping || interrupted || !visible)return false
        journal?.put("finishLineFraction",fraction)?.put("alignmentConfirmed",true)?.put("alignmentConfirmedLocalNs",SystemClock.elapsedRealtimeNanos())
        if(!save())return false
        r.recordingReady=true;return true
    }
    private fun controls(enabled:Boolean){prepare.isEnabled=enabled;host.isEnabled=enabled;code.isEnabled=enabled;fps.isEnabled=enabled;previewEnabled.isEnabled=enabled
        setup.visibility=if(enabled)View.VISIBLE else View.GONE;historyButton.visibility=setup.visibility
        prepare.visibility=setup.visibility;stop.visibility=if(enabled)View.GONE else View.VISIBLE
        confirm.visibility=if(enabled)View.GONE else View.VISIBLE;confirm.isEnabled=false
        if(!enabled)bodyScroll.post{bodyScroll.smoothScrollTo(0,0)}
    }
    private fun save():Boolean=journal?.let{runCatching{races.save(it)}.onFailure{status.text="保存失败：${it.message}";interrupted=true}.isSuccess} ?: false
    override fun onResume(){super.onResume();visible=true;main.post(tick)}
    override fun onPause(){visible=false;main.removeCallbacks(tick);if(busy)stopRace(true);super.onPause()}
    @Suppress("DEPRECATION")
    override fun onBackPressed(){if(busy)android.app.AlertDialog.Builder(this).setTitle("结束本场并返回？")
        .setMessage("录像会保留，本场将记为中断。").setNegativeButton("继续录像",null).setPositiveButton("结束并返回"){_,_->stopRace(true);finish()}.show()
        else super.onBackPressed()}
}
