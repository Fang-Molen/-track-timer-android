package cn.tracktimer.app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import cn.tracktimer.core.SignalTiming
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class FinishLineView(context:Context):View(context) {
    var fraction=.5f
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.RED;strokeWidth=3f}
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f
    private var draggingLine=false
    private var verticalGesture=false
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);canvas.drawLine(width*fraction,0f,width*fraction,height.toFloat(),pen)}
    private fun position(x:Float){if(width>0){fraction=(x/width).coerceIn(0f,1f);invalidate()}}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;draggingLine=false;verticalGesture=false}
            MotionEvent.ACTION_MOVE->{
                if(!draggingLine && !verticalGesture) {
                    val dx=kotlin.math.abs(e.x-downX);val dy=kotlin.math.abs(e.y-downY)
                    if(dy>slop && dy>dx)verticalGesture=true
                    else if(dx>slop){draggingLine=true;parent?.requestDisallowInterceptTouchEvent(true)}
                }
                if(draggingLine)position(e.x)
            }
            MotionEvent.ACTION_UP->{
                if(!verticalGesture){position(e.x);performClick()}
                parent?.requestDisallowInterceptTouchEvent(false);draggingLine=false
            }
            MotionEvent.ACTION_CANCEL->{parent?.requestDisallowInterceptTouchEvent(false);draggingLine=false;verticalGesture=true}
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}

/** Exact frame-index retrieval. Rendering requests are serialized and stale results are discarded. */
@android.annotation.SuppressLint("SetTextI18n") // Chinese-only field release.
class RaceReviewActivity:FieldActivity() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var closed=false
    private var retriever:MediaMetadataRetriever?=null
    private var timeline:FrameTimeline?=null
    private var frameCount=0
    private var timingBlockReason:String?=null
    private val stepButtons=mutableListOf<Button>()
    private data class LoadedVideo(val count:Int,val timeline:FrameTimeline?,val timingIssue:String?)
    private var race:JSONObject?=null
    private lateinit var races:RaceStore
    private lateinit var reports:ReportStore
    private lateinit var image:ImageView
    private lateinit var imageSizer:VideoFrameSizer
    private lateinit var line:FinishLineView
    private lateinit var slider:SeekBar
    private lateinit var detail:TextView
    private lateinit var saved:TextView
    private lateinit var athlete:EditText
    private lateinit var saveButton:Button
    private var requested=0
    private var loaded=-1
    @Volatile private var generation=0
    private var bitmap:Bitmap?=null
    private var rotation=0
    private var videoWidth=0
    private var videoHeight=0
    override fun onCreate(state:Bundle?){super.onCreate(state);reports=ReportStore(this);races=RaceStore(reports);screen("历史场次 · 逐帧判线")
        val id=intent.getStringExtra("raceId")
        if(id==null){history();return}
        val j=runCatching{races.load(id)}.getOrElse{label("场次读取失败：${it.message}");return};race=j
        label("左右拖动红线对齐终点线，上下滑动可找到操作按钮。逐帧选择躯干第一次到达终点线的画面，再保存运动员成绩。",15)
        val frame=FrameLayout(this);page.addView(frame,LinearLayout.LayoutParams(-1,dp(280)))
        imageSizer=VideoFrameSizer(frame)
        image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.BLACK)}
        line=FinishLineView(this).apply{fraction=j.optDouble("finishLineFraction",.5).toFloat()}
        frame.addView(image,FrameLayout.LayoutParams(-1,-1));frame.addView(line,FrameLayout.LayoutParams(-1,-1))
        detail=label("正在检查录像与帧索引…")
        slider=SeekBar(this).apply{isEnabled=false;page.addView(this)}
        val row=LinearLayout(this);page.addView(row)
        for(delta in listOf(-10,-1,1,10))row.addView(Button(this).apply{text=if(delta>0)"+$delta 帧" else "$delta 帧";isEnabled=false;stepButtons.add(this);setOnClickListener{select(requested+delta)}},LinearLayout.LayoutParams(0,-2,1f))
        athlete=input("运动员 / 道次，例如1道 张同学")
        saveButton=action("保存此帧成绩"){saveResult()}.apply{isEnabled=false}
        saved=label("");showSaved()
        label("成绩以电子发令为零点，包含运动员反应时间；提示音延迟未校准。帧间隔不是整套系统误差。请保留原始录像和日志。",13)
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user)select(p)}
            override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){}
        })
        if(Build.VERSION.SDK_INT<28){detail.text="逐帧判线需要Android 9或以上";return}
        worker.execute {
            runCatching {
                // Viewing a completed video does not require trustworthy timing metadata.
                val reportRead=runCatching {JSONObject(safeFile(j.getString("cameraReportFile"),"json").readText())}
                val report=reportRead.getOrNull()
                val identityMatches=report?.optString("runId")==j.optString("raceId") &&
                    report?.optString("clockSession")==j.optString("clientSession")
                val journalVideo=j.optString("videoFile").takeIf{it.isNotBlank() && it!="null"}
                val videoName=journalVideo ?: if(identityMatches)report?.optString("videoFile") else null
                val video=safeFile(videoName ?: error("没有找到本场保存的录像文件"),"mp4")
                val r=MediaMetadataRetriever();retriever=r;r.setDataSource(video.absolutePath)
                val count=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                    ?.takeIf{it>0} ?: error("系统未提供可用的逐帧数量")
                rotation=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                videoWidth=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                videoHeight=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val timing=runCatching {
                    val evidence=report ?: error("相机时间报告缺失或无法读取，请导出日志")
                    require(identityMatches){"录像与场次时钟会话不匹配"}
                    require(evidence.getString("videoFile")==video.name){"录像与相机时间报告指向不同文件"}
                    FrameTimeline.from(evidence,count)
                }
                LoadedVideo(count,timing.getOrNull(),timing.exceptionOrNull()?.message)
            }.onSuccess {loadedVideo->main.post{if(!closed){
                frameCount=loadedVideo.count;timeline=loadedVideo.timeline;timingBlockReason=loadedVideo.timingIssue
                slider.max=frameCount-1;slider.isEnabled=true;stepButtons.forEach{it.isEnabled=frameCount>1};select(0)
            }}}
                .onFailure{e->main.post{if(!closed)detail.text="无法读取录像：${e.message}\n请导出日志，原始文件仍保留。"}}
        }
    }
    private fun safeFile(name:String,extension:String):File {
        require(name.isNotBlank() && name==File(name).name && name.endsWith(".$extension")){"无效报告文件"}
        return File(reports.directory,name).also{require(it.isFile){"缺少文件：$name"}}
    }
    private fun history(){
        val entries=races.list()
        if(entries.isEmpty())label("还没有实测场次。请先在终点完成一次录像。")
        for(j in entries){val time=SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(j.optLong("createdAtUnixMs")))
            action("$time · ${j.optString("status")} · ${j.optJSONArray("results")?.length() ?: 0}人成绩"){
                startActivity(Intent(this,RaceReviewActivity::class.java).putExtra("raceId",j.getString("raceId")))}
        }
    }
    private val decode=Runnable {
        val index=requested;val version=generation
        worker.execute {
            if(closed || version!=generation)return@execute
            val result=runCatching {
                if(Build.VERSION.SDK_INT<28)error("逐帧判线需要Android 9或以上")
                val original=checkNotNull(retriever).getFrameAtIndex(index)?:error("系统未返回该帧")
                // Some implementations already apply rotation. Rotate only unrotated dimensions.
                if(rotation%180!=0 && original.width==videoWidth && original.height==videoHeight) {
                    val rotated=Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply{postRotate(rotation.toFloat())},true)
                    if(rotated!==original)original.recycle();rotated
                }else original
            }
            main.post {
                if(closed || version!=generation){result.getOrNull()?.recycle();return@post}
                result.onSuccess {b->val previous=bitmap;bitmap=b;image.setImageBitmap(b);imageSizer.setImageSize(b.width,b.height);previous?.recycle();loaded=index;describe()}
                    .onFailure{detail.text="无法读取第${index+1}帧：${it.message}";saveButton.isEnabled=false}
            }
        }
    }
    private fun select(index:Int){
        if(frameCount<=0)return;requested=index.coerceIn(0,frameCount-1);generation++;loaded=-1;saveButton.isEnabled=false
        slider.progress=requested;detail.text="正在读取第${requested+1}/${frameCount}帧…"
        main.removeCallbacks(decode);main.postDelayed(decode,80)
    }
    private fun timing():cn.tracktimer.core.SignalResult {
        val j=checkNotNull(race);val t=checkNotNull(timeline)
        return SignalTiming.calculate(t.timestamp(requested),j.getLong("startHostNs"),CalibrationJson.decode(j.getJSONObject("preCalibration")),
            j.optJSONObject("postCalibration")?.let(CalibrationJson::decode),j.getString("hostSession"),j.getString("clientSession"))
    }
    private fun good(result:cn.tracktimer.core.SignalResult)=result.syncCheckPassed && race?.optBoolean("networkCompleted")==true && race?.optBoolean("interrupted")!=true
    private fun describe(){
        if(timeline==null){
            detail.text="第${requested+1}/$frameCount 帧 · 仅查看录像\n计时不可用：${timingBlockReason ?: "缺少可信时间记录"}\n可继续逐帧查看；请导出终点日志。"
            saveButton.isEnabled=false;return
        }
        val result=runCatching{timing()}
        detail.text="第${requested+1}/$frameCount 帧"+result.fold({r->
            " · ${String.format(Locale.CHINA,"%.3f",r.elapsedNs/1e9)} 秒\n"+if(good(r))"训练信号成绩 · 对时复核通过" else "仅供复核 · 对时或场次完整性未通过"
        },{"\n${it.message ?: "发令前画面或缺少发令记录"}"})
        saveButton.isEnabled=loaded==requested && result.isSuccess
    }
    private fun saveResult(){
        if(timeline==null){message("本场计时不可用，不能保存成绩");return}
        if(loaded!=requested){message("请等画面加载完成");return}
        val name=athlete.text.toString().trim();if(name.isEmpty()){message("请输入运动员或道次");return}
        runCatching {
            val r=timing();val j=checkNotNull(race);val t=checkNotNull(timeline)
            j.getJSONArray("results").put(JSONObject().put("name",name).put("frameIndex",requested).put("finishRawNs",t.timestamp(requested))
                .put("elapsedNs",r.elapsedNs).put("startLocalNs",r.startLocalNs).put("syncCheckPassed",r.syncCheckPassed)
                .put("quality",if(good(r))"training_signal" else "review_only").put("precisionVerified",false)
                .put("finishLineFraction",line.fraction.toDouble()).put("savedAtUnixMs",System.currentTimeMillis()))
            j.put("finishLineFraction",line.fraction.toDouble());races.save(j);showSaved();message("已保存 $name")
        }.onFailure{message("保存失败：${it.message}")}
    }
    private fun showSaved(){val a=race?.optJSONArray("results") ?: return
        saved.text=if(a.length()==0)"尚无已保存成绩" else (0 until a.length()).joinToString("\n") {i->val r=a.getJSONObject(i)
            "${r.optString("name")}：${String.format(Locale.CHINA,"%.3f",r.optLong("elapsedNs")/1e9)} 秒 · 第${r.optInt("frameIndex")+1}帧${if(r.optString("quality")=="review_only")"（仅复核）" else ""}"}
    }
    override fun onDestroy(){closed=true;generation++;main.removeCallbacks(decode)
        worker.execute{runCatching{retriever?.release()};retriever=null};worker.shutdown()
        if(::image.isInitialized)image.setImageDrawable(null);bitmap?.recycle();bitmap=null;super.onDestroy()}
}
