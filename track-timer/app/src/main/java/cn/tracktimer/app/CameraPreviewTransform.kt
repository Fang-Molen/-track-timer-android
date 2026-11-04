package cn.tracktimer.app

import android.graphics.Matrix

/** Back-camera TextureView buffers already carry the sensor's mounting transform.
 * Correct their aspect ratio and compensate DISPLAY rotation only, never mount rotation twice.
 * Boundary follows Camera2Basic's configureTransform + aspect-aware TextureView sizing.
 */
object CameraPreviewTransform {
    fun matrix(viewWidth:Int,viewHeight:Int,bufferWidth:Int,bufferHeight:Int,
        sensorOrientation:Int,displayRotationDegrees:Int):Matrix {
        require(listOf(viewWidth,viewHeight,bufferWidth,bufferHeight).all{it>0})
        require(sensorOrientation in listOf(0,90,180,270) && displayRotationDegrees in listOf(0,90,180,270))
        val naturalWidth=if(sensorOrientation%180==0)bufferWidth else bufferHeight
        val naturalHeight=if(sensorOrientation%180==0)bufferHeight else bufferWidth
        val quarter=displayRotationDegrees%180!=0
        val outputWidth=if(quarter)naturalHeight else naturalWidth
        val outputHeight=if(quarter)naturalWidth else naturalHeight
        val scale=minOf(viewWidth.toFloat()/outputWidth,viewHeight.toFloat()/outputHeight)
        val cx=viewWidth/2f;val cy=viewHeight/2f
        return Matrix().apply {
            setScale(scale*naturalWidth/viewWidth,scale*naturalHeight/viewHeight,cx,cy)
            postRotate(-displayRotationDegrees.toFloat(),cx,cy)
        }
    }
}
