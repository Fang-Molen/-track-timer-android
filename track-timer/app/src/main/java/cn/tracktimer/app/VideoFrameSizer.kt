package cn.tracktimer.app

import android.view.View
import kotlin.math.roundToInt

/** Fill the page width with the complete upright image; the page scrolls to remaining controls. */
class VideoFrameSizer(private val view:View) {
    private var imageWidth=9
    private var imageHeight=16
    init {
        view.addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_->
            if(right-left!=oldRight-oldLeft)resize()
        }
        resize()
    }
    fun setImageSize(width:Int,height:Int) {
        if(width<=0 || height<=0)return
        imageWidth=width;imageHeight=height;resize()
    }
    private fun resize() {
        if(view.width<=0)return
        val height=(view.width.toDouble()*imageHeight/imageWidth).roundToInt().coerceAtLeast(1)
        val params=view.layoutParams ?: return
        if(params.height!=height){params.height=height;view.layoutParams=params}
    }
}
