package cn.tracktimer.app

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.Formatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@android.annotation.SuppressLint("SetTextI18n")
class StorageActivity:FieldActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var closed=false
    private lateinit var store:ReportStore
    private lateinit var library:VideoLibrary
    override fun onCreate(state:Bundle?){super.onCreate(state);store=ReportStore(this);library=VideoLibrary(store);screen("录像与存储","手动清理视频，保留成绩与计时记录");refresh()}
    private fun bytes(n:Long)=Formatter.formatFileSize(this,n)
    private fun refresh(){
        page.removeAllViews();footer.removeAllViews();footer.visibility=android.view.View.GONE;label("正在统计录像…")
        worker.execute{val result=runCatching{library.inventory()};runOnUiThread{if(!closed)result.fold(::render,{page.removeAllViews();label("无法读取存储：${it.message}")})}}
    }
    private fun render(items:List<VideoLibrary.Entry>){
        page.removeAllViews();label("录像占用 ${bytes(items.sumOf{it.bytes})}",23);caption("手机可用 ${bytes(store.directory.usableSpace)}")
        caption("先在判线页保存所有成绩，再点“本场判线完成”。批量清理只处理这些已完成场次；未判完和设备测试录像不会自动删除。")
        val completed=items.filter{it.reviewed}
        action("清理已完成判线录像（${completed.size}段）",footer,danger=true){
            confirm("清理 ${completed.size} 段录像？","预计释放 ${bytes(completed.sumOf{it.bytes})}。"){library.deleteReviewed(completed.map{it.name})}
        }.isEnabled=completed.isNotEmpty()
        if(items.isEmpty())label("目前没有可清理的已保存录像。")
        for(item in items){val c=card()
            label(SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(Date(item.modified)),20,c)
            caption("${bytes(item.bytes)} · "+when{!item.canDelete->"仍在保存，暂不能清理";item.reviewed->"已完成判线";item.raceIds.isEmpty()->"设备测试 / 未关联场次";else->"尚未标记判线完成"},c)
            action("删除这段录像",c,danger=true){
                confirm("删除这段录像？","${bytes(item.bytes)}。${if(item.raceIds.isNotEmpty() && !item.reviewed)"本场尚未标记判线完成。" else ""}"){library.deleteVideo(item.name)}
            }.isEnabled=item.canDelete
        }
    }
    private fun confirm(title:String,description:String,delete:()->VideoLibrary.Deleted){
        AlertDialog.Builder(this).setTitle(title).setMessage(description+"\n成绩和计时记录保留。删除后不能重新逐帧判线，此操作不能撤销。")
            .setNegativeButton("保留录像",null).setPositiveButton("删除录像"){_,_->
                page.removeAllViews();footer.removeAllViews();label("正在清理…")
                worker.execute{val result=runCatching(delete);runOnUiThread{if(!closed){result.onSuccess{message("已清理${it.files}段，释放${bytes(it.bytes)}${if(it.warnings>0)"；部分项目未完成，请检查" else ""}")}.onFailure{message("清理未完成：${it.message}")};refresh()}}}
            }.show()
    }
    override fun onDestroy(){closed=true;worker.shutdown();super.onDestroy()}
}
