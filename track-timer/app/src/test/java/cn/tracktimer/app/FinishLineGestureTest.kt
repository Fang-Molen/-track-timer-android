package cn.tracktimer.app

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class FinishLineGestureTest {
    private fun screen(test:(ScrollView,FinishLineView)->Unit) {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val context=controller.get();val scroll=ScrollView(context)
            val page=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
            val line=FinishLineView(context)
            page.addView(line,LinearLayout.LayoutParams(-1,640))
            page.addView(Button(context).apply{text="下方操作"},LinearLayout.LayoutParams(-1,120))
            scroll.addView(page);context.setContentView(scroll)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY))
            scroll.layout(0,0,360,500)
            test(scroll,line)
        }finally{controller.pause().stop().destroy()}
    }
    private fun gesture(scroll:ScrollView,points:List<Pair<Float,Float>>) {
        val start=android.os.SystemClock.uptimeMillis()
        points.forEachIndexed { i,(x,y)->
            val action=when(i){0->MotionEvent.ACTION_DOWN;points.lastIndex->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
            val e=MotionEvent.obtain(start,start+i*30L,action,x,y,0)
            scroll.dispatchTouchEvent(e);e.recycle()
        }
    }
    @Test fun verticalSwipeOnLargeImageReachesControlsWithoutMovingFinishLine() = screen { scroll,line ->
        gesture(scroll,listOf(120f to 350f,120f to 310f,120f to 220f,120f to 130f,120f to 100f))
        assertTrue("Vertical image swipes must scroll toward controls",scroll.scrollY>0)
        assertEquals(.5f,line.fraction,.001f)
    }
    @Test fun tapsAndHorizontalDragsStillPositionLineThenReleaseScrolling() = screen { scroll,line ->
        gesture(scroll,listOf(90f to 220f,90f to 220f));assertEquals(.25f,line.fraction,.001f)
        gesture(scroll,listOf(180f to 220f,220f to 221f,270f to 223f,270f to 223f))
        assertEquals(.75f,line.fraction,.001f);assertEquals(0,scroll.scrollY)
        gesture(scroll,listOf(120f to 350f,120f to 310f,120f to 220f,120f to 130f,120f to 100f))
        assertTrue(scroll.scrollY>0);assertEquals(.75f,line.fraction,.001f)
    }
}
