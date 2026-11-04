package cn.tracktimer.app

import android.content.Intent
import android.os.Bundle

class HomeActivity:FieldActivity() {
    override fun onCreate(state:Bundle?){super.onCreate(state)
        screen("跑道计时","两台手机，一次完整的训练记录",home=true)
        val start=card();caption("01  /  起点手机",start);label("准备好，再发令",24,start)
        caption("开启连接，等待终点确认机位后发令。",start)
        action("起点发令",start,primary=true){startActivity(Intent(this,StartActivity::class.java))}
        val finish=card();caption("02  /  终点手机",finish);label("对准终点，记录每一帧",24,finish)
        caption("不录像对线 → 开始录像 → 发令 → 判线",finish)
        action("终点录像计时",finish,primary=true){startActivity(Intent(this,FinishActivity::class.java))}
        action("历史场次与判线"){startActivity(Intent(this,RaceReviewActivity::class.java))}
        action("录像与存储"){startActivity(Intent(this,StorageActivity::class.java))}
        action("设备与日志"){startActivity(Intent(this,MainActivity::class.java))}
        caption("两台手机连接同一 Wi-Fi 或热点。建议先用120帧测试。")
        caption("${BuildConfig.VERSION_NAME}  ·  训练信号计时\n声音延迟与整套精度尚未标定。")
    }
}
