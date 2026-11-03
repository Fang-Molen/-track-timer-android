package cn.tracktimer.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*

open class FieldActivity:Activity() {
    protected lateinit var page:LinearLayout
    protected fun screen(title:String) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(244,247,245));isFillViewport=true}
        page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(20),dp(18),dp(28))}
        scroll.addView(page);setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view,insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom);insets
        }
        label(title,26);label("训练信号计时 · ${BuildConfig.VERSION_NAME}",13)
    }
    protected fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    protected fun label(value:String,size:Int=16)=TextView(this).apply {
        text=value;textSize=size.toFloat();setTextColor(Color.rgb(23,42,38));setPadding(0,dp(8),0,dp(8));page.addView(this)
    }
    protected fun action(value:String,block:()->Unit)=Button(this).apply{text=value;isAllCaps=false;page.addView(this);setOnClickListener{block()}}
    protected fun input(hintText:String)=EditText(this).apply{hint=hintText;isSingleLine=true;page.addView(this)}
    protected fun message(value:String){Toast.makeText(this,value,Toast.LENGTH_LONG).show()}
}
