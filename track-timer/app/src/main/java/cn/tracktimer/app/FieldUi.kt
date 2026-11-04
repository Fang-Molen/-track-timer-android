package cn.tracktimer.app

import android.app.Activity
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Scrollable field content with permanently reachable primary actions. */
@android.annotation.SuppressLint("SetTextI18n")
open class FieldActivity:Activity() {
    protected val ink=Color.rgb(20,42,48)
    protected val muted=Color.rgb(91,111,114)
    protected val accent=Color.rgb(0,113,94)
    protected lateinit var page:LinearLayout
    protected lateinit var footer:LinearLayout
    protected lateinit var bodyScroll:ScrollView
    protected fun screen(title:String,subtitle:String="双机训练计时",home:Boolean=false) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(243,247,246))}
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12))}
        root.addView(header)
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};header.addView(top)
        val brand=TextView(this).apply{text=if(home)"TRACK TIMER" else "‹  跑道计时";textSize=12f;setTextColor(accent);typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(6),0,dp(6));minimumHeight=dp(40)
            if(!home)setOnClickListener{onBackPressed()}}
        top.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(TextView(this).apply{text="训练版";textSize=11f;setTextColor(accent);setPadding(dp(10),dp(5),dp(10),dp(5));background=shape(Color.rgb(222,240,234),20)})
        label(title,28,header).setPadding(0,0,0,dp(2))
        label(subtitle,13,header).apply{setTextColor(muted);setPadding(0,0,0,0)}
        bodyScroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
        page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(4),dp(20),dp(20))}
        bodyScroll.addView(page);root.addView(bodyScroll,LinearLayout.LayoutParams(-1,0,1f))
        footer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12));setBackgroundColor(Color.WHITE);elevation=dp(5).toFloat();visibility=View.GONE}
        root.addView(footer,LinearLayout.LayoutParams(-1,-2));setContentView(root)
        // Keyboard resizing (or a short window) must leave a scrollable athlete/input area.
        fun fitWindow(v:View){
            val compact=v.height-v.paddingTop-v.paddingBottom<dp(520)
            val state=if(compact)View.GONE else View.VISIBLE
            if(header.visibility!=state)header.visibility=state
            onCompactWindow(compact)
        }
        root.addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->fitWindow(v)}
        root.setOnApplyWindowInsetsListener{v,i->
            @Suppress("DEPRECATION")
            v.setPadding(i.systemWindowInsetLeft,i.systemWindowInsetTop,i.systemWindowInsetRight,i.systemWindowInsetBottom);fitWindow(v);i
        }
    }
    protected open fun onCompactWindow(compact:Boolean){}
    protected fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    protected fun shape(color:Int,radius:Int=16,stroke:Int?=null)=GradientDrawable().apply{
        setColor(color);cornerRadius=dp(radius).toFloat();stroke?.let{setStroke(dp(1),it)}
    }
    protected fun label(value:String,size:Int=16,parent:LinearLayout=page)=TextView(this).apply{
        text=value;textSize=size.toFloat();setTextColor(ink);setPadding(0,dp(6),0,dp(6));setLineSpacing(dp(2).toFloat(),1f)
        if(size>=20)typeface=Typeface.DEFAULT_BOLD
        parent.addView(this)
    }
    protected fun caption(value:String,parent:LinearLayout=page)=label(value,13,parent).apply{setTextColor(muted)}
    protected fun card():LinearLayout=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(16));background=shape(Color.WHITE,18,Color.rgb(222,232,229))
        page.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
    }
    protected fun button(value:String,primary:Boolean=false,danger:Boolean=false)=Button(this).apply{
        text=value;isAllCaps=false;textSize=16f;typeface=Typeface.DEFAULT_BOLD;minimumHeight=dp(52);minHeight=dp(52)
        setPadding(dp(14),dp(10),dp(14),dp(10));stateListAnimator=null
        val fill=if(danger)Color.rgb(172,45,51) else if(primary)accent else Color.WHITE
        val backgrounds=StateListDrawable().apply{
            addState(intArrayOf(-android.R.attr.state_enabled),shape(Color.rgb(230,236,234),12))
            addState(intArrayOf(),shape(fill,12,if(primary||danger)null else Color.rgb(202,217,212)))
        }
        background=RippleDrawable(ColorStateList.valueOf(0x22000000),backgrounds,null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.rgb(111,128,123),if(primary||danger)Color.WHITE else ink)))
    }
    protected fun action(value:String,parent:LinearLayout=page,primary:Boolean=false,danger:Boolean=false,block:()->Unit)=button(value,primary,danger).apply{
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        if(parent===footer)footer.visibility=View.VISIBLE
        setOnClickListener{block()}
    }
    protected fun input(hintText:String,parent:LinearLayout=page)=EditText(this).apply{
        hint=hintText;isSingleLine=true;textSize=16f;setTextColor(ink);setHintTextColor(muted)
        setPadding(dp(12),dp(12),dp(12),dp(12));minimumHeight=dp(50)
        background=shape(Color.rgb(247,250,249),10,Color.rgb(212,225,220))
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
    }
    protected fun message(value:String){Toast.makeText(this,value,Toast.LENGTH_LONG).show()}
}
