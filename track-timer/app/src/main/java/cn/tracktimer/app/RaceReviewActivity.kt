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
        set(value){field=if(value.isFinite())value.coerceIn(0f,1f) else .5f;invalidate()}
    var onPositionCommitted:((Float)->Unit)?=null
    private var aspect=9f/16f
    private val density=resources.displayMetrics.density
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,54,67);strokeWidth=2*density}
    private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeWidth=4*density;alpha=200}
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f
    private var downY=0f
    private var draggingLine=false
    private var verticalGesture=false
    fun setImageSize(w:Int,h:Int){if(w>0 && h>0){aspect=w.toFloat()/h;invalidate()}}
    private fun imageBounds():RectF {
        val w=minOf(width.toFloat(),height*aspect);val h=if(aspect>0)w/aspect else 0f
        return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
    }
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val b=imageBounds();val x=b.left+b.width()*fraction
        canvas.drawLine(x,b.top,x,b.bottom,halo);canvas.drawLine(x,b.top,x,b.bottom,pen)
        canvas.drawCircle(x,b.top+12*density,4*density,pen);canvas.drawCircle(x,b.bottom-12*density,4*density,pen)
    }
    private fun position(x:Float){val b=imageBounds();if(b.width()>0)fraction=(x-b.left)/b.width()}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!isEnabled)return false
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
                if(!verticalGesture){position(e.x);performClick();onPositionCommitted?.invoke(fraction)}
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
    private var decoding=false
    private var dragging=false
    private var rangeStart=0
    private var rangeEnd=0
    private var fineRange=false
    private lateinit var navigation:LinearLayout
    private lateinit var rangeButton:Button
    private var bitmap:Bitmap?=null
    private var rotation=0
    private var videoWidth=0
    private var videoHeight=0
    override fun onCreate(state:Bundle?){super.onCreate(state);reports=ReportStore(this);races=RaceStore(reports)
        val id=intent.getStringExtra("raceId")
        screen(if(id==null)"历史场次" else "逐帧判线",if(id==null)"保留每次录像与训练成绩" else "找到躯干首次到线的画面，记录成绩")
        if(id==null){history();return}
        val j=runCatching{races.load(id)}.getOrElse{label("场次读取失败：${it.message}");return};race=j
        val videoName=j.optString("videoFile")
        if(j.optBoolean("videoDeleted") || (videoName.isNotBlank() && videoName==File(videoName).name && !File(reports.directory,videoName).isFile)){
            label("原视频已清理或不在手机内",22);caption("成绩和计时记录仍保留，无法继续逐帧复核。")
            saved=label("");showSaved();return
        }
        caption("先拖动全程时间轴找过线位置，再用局部精调和±1帧确认。左右拖红线，上下滑动查看运动员信息。")
        val frame=FrameLayout(this);page.addView(frame,LinearLayout.LayoutParams(-1,dp(280)))
        imageSizer=VideoFrameSizer(frame)
        image=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.BLACK)}
        line=FinishLineView(this).apply{fraction=j.optDouble("finishLineFraction",.5).toFloat()}
        frame.addView(image,FrameLayout.LayoutParams(-1,-1));frame.addView(line,FrameLayout.LayoutParams(-1,-1))
        detail=label("正在检查录像与帧索引…",16,footer)
        navigation=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};footer.addView(navigation)
        val rangeRow=LinearLayout(this);navigation.addView(rangeRow)
        rangeButton=button("全程 ▾").apply{textSize=14f;setOnClickListener{if(frameCount>0){fineRange=!fineRange;updateRange()}}}
        rangeRow.addView(rangeButton,LinearLayout.LayoutParams(0,dp(48),1f))
        rangeRow.addView(button("跳到秒数").apply{textSize=14f;setOnClickListener{jumpDialog()}},LinearLayout.LayoutParams(0,dp(48),1f))
        slider=SeekBar(this).apply{isEnabled=false;navigation.addView(this);progressTintList=android.content.res.ColorStateList.valueOf(accent);thumbTintList=progressTintList}
        val row=LinearLayout(this);navigation.addView(row)
        for(delta in listOf(-10,-1,1,10))row.addView(button(if(delta>0)"+$delta 帧" else "$delta 帧").apply{textSize=14f;setPadding(dp(4),dp(10),dp(4),dp(10));isEnabled=false;stepButtons.add(this);setOnClickListener{select(requested+delta)}},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(3)})
        caption("运动员 / 道次")
        athlete=input("例如 1道 张同学")
        saveButton=action("保存此帧成绩",footer,primary=true){saveResult()}.apply{isEnabled=false}
        saved=label("");showSaved()
        action("本场判线完成"){
            runCatching{VideoLibrary(reports).markReviewed(j.getString("raceId"))}
                .onSuccess{message("已标记完成，可到录像与存储中清理");finish()}.onFailure{message(it.message ?: "无法标记完成")}
        }
        caption("保存所有运动员成绩后再标记完成。清理原视频后不能重新判线。成绩仍以电子发令为零点，声音延迟未标定。")
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user)select(rangeStart+p)}
            override fun onStartTrackingTouch(s:SeekBar){dragging=true;saveButton.isEnabled=false}
            override fun onStopTrackingTouch(s:SeekBar){dragging=false;select(rangeStart+s.progress)}
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
                rangeEnd=frameCount-1;slider.max=rangeEnd;slider.isEnabled=true;stepButtons.forEach{it.isEnabled=frameCount>1}
                val initial=runCatching{timeline!!.indexAtOrAfter(timing(frameCount-1).startLocalNs)}.getOrDefault(0)
                select(initial)
            }}}
                .onFailure{e->main.post{if(!closed)detail.text="无法读取录像：${e.message}\n请导出日志，原始文件仍保留。"}}
        }
    }
    private fun safeFile(name:String,extension:String):File {
        require(name.isNotBlank() && name==File(name).name && name.endsWith(".$extension")){"无效报告文件"}
        return File(reports.directory,name).also{require(it.isFile){"缺少文件：$name"}}
    }
    private fun history(){
        action("录像与存储"){startActivity(Intent(this,StorageActivity::class.java))}
        val entries=races.list()
        if(entries.isEmpty())label("还没有实测场次。请先在终点完成一次录像。")
        for(j in entries){val time=SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(j.optLong("createdAtUnixMs")))
            val state=if(j.optBoolean("videoDeleted"))"原视频已清理" else if(j.optBoolean("reviewCompleted"))"判线完成" else when(j.optString("status")){"saved"->"已保存";"interrupted"->"已中断";else->"待复核"}
            val item=card();label(time,22,item);caption("$state · ${j.optJSONArray("results")?.length() ?: 0}条成绩",item)
            action("打开录像与成绩",item){
                startActivity(Intent(this,RaceReviewActivity::class.java).putExtra("raceId",j.getString("raceId")))}
        }
    }
    // One decode in flight; new seeks replace only the pending destination.
    private fun decodeNext(){
        if(closed || decoding || frameCount<=0)return
        val index=requested;decoding=true
        worker.execute {
            if(closed)return@execute
            val result=runCatching {
                if(Build.VERSION.SDK_INT<28)error("逐帧判线需要Android 9或以上")
                val original=checkNotNull(retriever).getFrameAtIndex(index)?:error("系统未返回该帧")
                if(rotation%180!=0 && original.width==videoWidth && original.height==videoHeight) {
                    val rotated=Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply{postRotate(rotation.toFloat())},true)
                    if(rotated!==original)original.recycle();rotated
                }else original
            }
            main.post {
                decoding=false
                if(closed){result.getOrNull()?.recycle();return@post}
                if(dragging || index==requested){
                    result.onSuccess {b->val previous=bitmap;bitmap=b;image.setImageBitmap(b);imageSizer.setImageSize(b.width,b.height);line.setImageSize(b.width,b.height);previous?.recycle();loaded=index;describe()}
                        .onFailure{detail.text="无法读取第${index+1}帧：${it.message}";saveButton.isEnabled=false}
                }else result.getOrNull()?.recycle()
                if(index!=requested)decodeNext()
            }
        }
    }
    private fun select(index:Int){
        if(frameCount<=0)return;requested=index.coerceIn(0,frameCount-1);saveButton.isEnabled=false
        if(requested !in rangeStart..rangeEnd)updateRange()
        slider.progress=requested-rangeStart
        val time=runCatching{String.format(Locale.CHINA,"发令后 %.3f 秒",timing(requested).elapsedNs/1e9)}.getOrNull()
        detail.text="${if(dragging)"拖动预览" else "正在定位"} · ${time ?: "第${requested+1}/$frameCount 帧"}"
        if(loaded==requested && !decoding && !dragging)describe() else decodeNext()
    }
    private fun updateRange(){
        if(frameCount<=0)return
        if(fineRange){val t=timeline
            rangeStart=t?.indexAtOrAfter(t.timestamp(requested)-1_000_000_000L) ?: (requested-60).coerceAtLeast(0)
            rangeEnd=t?.indexAtOrBefore(t.timestamp(requested)+1_000_000_000L) ?: (requested+60).coerceAtMost(frameCount-1)
        }else{rangeStart=0;rangeEnd=frameCount-1}
        rangeButton.text=if(fineRange)"局部精调 ▾" else "全程 ▾"
        slider.max=rangeEnd-rangeStart;slider.progress=requested-rangeStart
    }
    private fun jumpDialog(){
        val t=timeline ?: run{message("本场缺少可信时间轴，可使用全程/局部和逐帧定位");return}
        val start=runCatching{timing(frameCount-1).startLocalNs}.getOrElse{message("缺少有效发令记录，暂不能按秒跳转");return}
        val input=EditText(this).apply{hint="发令后秒数，例如 12.5";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val dialog=android.app.AlertDialog.Builder(this).setTitle("跳到发令后时间").setView(input).setNegativeButton("取消",null).setPositiveButton("跳转",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val seconds=input.text.toString().toDoubleOrNull();val max=(t.timestamp(t.size-1)-start)/1e9
            if(seconds==null || !seconds.isFinite() || seconds !in 0.0..max){input.error="请输入0到${String.format(Locale.CHINA,"%.2f",max)}秒"}
            else{dragging=false;select(t.indexAtOrAfter(start+(seconds*1e9).toLong()));dialog.dismiss()}
        }};dialog.show()
    }
    override fun onCompactWindow(compact:Boolean){if(::navigation.isInitialized)navigation.visibility=if(compact)View.GONE else View.VISIBLE}
    override fun onResume(){super.onResume();if(intent.getStringExtra("raceId")==null && ::races.isInitialized){page.removeAllViews();history()}}
    private fun timing(index:Int=requested):cn.tracktimer.core.SignalResult {
        val j=checkNotNull(race);val t=checkNotNull(timeline)
        return SignalTiming.calculate(t.timestamp(index),j.getLong("startHostNs"),CalibrationJson.decode(j.getJSONObject("preCalibration")),
            j.optJSONObject("postCalibration")?.let(CalibrationJson::decode),j.getString("hostSession"),j.getString("clientSession"))
    }
    private fun good(result:cn.tracktimer.core.SignalResult)=result.syncCheckPassed && race?.optBoolean("networkCompleted")==true && race?.optBoolean("interrupted")!=true
    private fun describe(){
        if(timeline==null){
            detail.text="第${loaded+1}/$frameCount 帧 · 仅查看录像\n计时不可用：${timingBlockReason ?: "缺少可信时间记录"}\n可继续逐帧查看；请导出终点日志。"
            saveButton.isEnabled=false;return
        }
        val result=runCatching{timing(loaded)}
        detail.text=(if(dragging || loaded!=requested)"拖动预览 · " else "")+"第${loaded+1}/$frameCount 帧"+result.fold({r->
            " · ${String.format(Locale.CHINA,"%.3f",r.elapsedNs/1e9)} 秒\n"+if(good(r))"训练信号成绩 · 对时复核通过" else "仅供复核 · 对时或场次完整性未通过"
        },{"\n${it.message ?: "发令前画面或缺少发令记录"}"})
        saveButton.isEnabled=!dragging && !decoding && loaded==requested && result.isSuccess
    }
    private fun saveResult(){
        if(timeline==null){message("本场计时不可用，不能保存成绩");return}
        if(dragging || decoding || loaded!=requested){message("请等画面加载完成");return}
        val name=athlete.text.toString().trim();if(name.isEmpty()){message("请输入运动员或道次");return}
        runCatching {
            val r=timing();val j=checkNotNull(race);val t=checkNotNull(timeline)
            j.getJSONArray("results").put(JSONObject().put("name",name).put("frameIndex",requested).put("finishRawNs",t.timestamp(requested))
                .put("elapsedNs",r.elapsedNs).put("startLocalNs",r.startLocalNs).put("syncCheckPassed",r.syncCheckPassed)
                .put("quality",if(good(r))"training_signal" else "review_only").put("precisionVerified",false)
                .put("finishLineFraction",line.fraction.toDouble()).put("savedAtUnixMs",System.currentTimeMillis()))
            j.put("finishLineFraction",line.fraction.toDouble()).put("reviewCompleted",false);races.save(j);showSaved();message("已保存 $name")
        }.onFailure{message("保存失败：${it.message}")}
    }
    private fun showSaved(){val a=race?.optJSONArray("results") ?: return
        saved.text=if(a.length()==0)"尚无已保存成绩" else (0 until a.length()).joinToString("\n") {i->val r=a.getJSONObject(i)
            "${r.optString("name")}：${String.format(Locale.CHINA,"%.3f",r.optLong("elapsedNs")/1e9)} 秒 · 第${r.optInt("frameIndex")+1}帧${if(r.optString("quality")=="review_only")"（仅复核）" else ""}"}
    }
    override fun onDestroy(){closed=true
        worker.execute{runCatching{retriever?.release()};retriever=null};worker.shutdown()
        if(::image.isInitialized)image.setImageDrawable(null);bitmap?.recycle();bitmap=null;super.onDestroy()}
}
