package cn.tracktimer.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class FinishAlignmentUiTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    @Test fun finishShowsReferenceAndCannotConfirmBeforeCameraFrames() {
        val c=Robolectric.buildActivity(FinishActivity::class.java).setup()
        try {
            val v=views(c.get().window.decorView)
            assertEquals("Live finish preview needs a reference line",1,v.filterIsInstance<FinishLineView>().size)
            val confirm=v.filterIsInstance<Button>().firstOrNull{it.text=="对线完成，允许发令"}
            assertNotNull("Alignment confirmation must be explicit",confirm)
            assertFalse(confirm!!.isEnabled)
        }finally{c.pause().stop().destroy()}
    }
    @Test fun previewRestoresTheSavedReferencePosition() {
        val c=Robolectric.buildActivity(FinishActivity::class.java)
        c.get().getPreferences(Context.MODE_PRIVATE).edit().putFloat("finish_line_fraction",.31f).commit()
        c.setup()
        try {
            val line=views(c.get().window.decorView).filterIsInstance<FinishLineView>().firstOrNull()
            assertNotNull(line);assertEquals(.31f,line!!.fraction,.001f)
        }finally{c.pause().stop().destroy()}
    }
}
