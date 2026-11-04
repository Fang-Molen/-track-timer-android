package cn.tracktimer.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView

/** Preview only: owns no encoder, recorder, report store or media file. */
class PreviewCamera(private val context:Context,private val texture:TextureView,private val config:CameraConfig,
    private val onReady:()->Unit,private val onError:(String)->Unit) {
    private val thread=HandlerThread("alignment-preview").apply{start()}
    private val worker=Handler(thread.looper)
    private val main=Handler(Looper.getMainLooper())
    private var device:CameraDevice?=null
    private var session:CameraCaptureSession?=null
    private var surface:Surface?=null
    private var opening=false
    @Volatile private var closing=false
    @Volatile private var closed=false
    private var ready=false
    private var errorReported=false
    private val closedListeners=mutableListOf<(Boolean)->Unit>()
    private val timeout=Runnable{fail("取景相机启动超时，请返回重试")}
    private val closeTimeout=Runnable{val listeners=closedListeners.toList();closedListeners.clear();main.post{listeners.forEach{it(false)}}}

    @SuppressLint("MissingPermission")
    fun start() {
        val buffer=texture.surfaceTexture
        worker.post {
            if(closing)return@post
            try {
                check(buffer!=null && texture.isAvailable){"取景区域尚未就绪，请重试"}
                buffer.setDefaultBufferSize(config.size.width,config.size.height);surface=Surface(buffer)
                worker.postDelayed(timeout,8_000)
                val manager=context.getSystemService(CameraManager::class.java)
                opening=true
                try{manager.openCamera(config.id,object:CameraDevice.StateCallback(){
                    override fun onOpened(d:CameraDevice){opening=false;device=d;if(closing)d.close() else configure(d,manager)}
                    override fun onDisconnected(d:CameraDevice){opening=false;device=d;fail("取景相机断开")}
                    override fun onError(d:CameraDevice,error:Int){opening=false;device=d;fail("取景相机错误：$error")}
                    override fun onClosed(d:CameraDevice){if(device===d)device=null;opening=false;if(closing)finishClose()}
                },worker)}catch(e:Exception){opening=false;throw e}
            }catch(e:Exception){fail(e.message ?: "无法启动取景")}
        }
    }
    @Suppress("DEPRECATION")
    private fun configure(d:CameraDevice,manager:CameraManager) {
        try {
            val output=checkNotNull(surface)
            val ranges=manager.getCameraCharacteristics(config.id)[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
            val range=ranges.filter{it.upper<=30}.maxWithOrNull(compareBy<android.util.Range<Int>>{it.upper}.thenBy{it.lower})
            val request=d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{
                addTarget(output);range?.let{set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,it)}
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()
            CameraSessionSetup.open(d,listOf(output),request,object:CameraCaptureSession.StateCallback(){
                override fun onConfigured(s:CameraCaptureSession){
                    if(closing){s.close();return};session=s
                    try{s.setRepeatingRequest(request,object:CameraCaptureSession.CaptureCallback(){
                        override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult){
                            if(!ready && !closing){ready=true;worker.removeCallbacks(timeout);main.post{if(!closing)onReady()}}
                        }
                    },worker)}catch(e:Exception){fail(e.message ?: "取景请求失败")}
                }
                override fun onConfigureFailed(s:CameraCaptureSession){s.close();fail("此机型未能启动独立取景，可返回后直接录像对线")}
            },worker)
        }catch(e:Exception){fail(e.message ?: "取景配置失败")}
    }
    private fun fail(message:String){
        if(!errorReported && !closing){errorReported=true;main.post{onError(message)}}
        closeOnWorker()
    }
    /** True only after CameraDevice.onClosed (or before any camera was opened). */
    fun close(done:(Boolean)->Unit={}) {
        closing=true
        if(closed){main.post{done(true)};return}
        if(!worker.post{if(closed)main.post{done(true)} else {closedListeners.add(done);closeOnWorker()}})main.post{done(closed)}
    }
    private fun closeOnWorker(){
        closing=true;worker.removeCallbacks(timeout)
        runCatching{session?.stopRepeating()};runCatching{session?.close()};session=null
        if(device!=null)runCatching{device?.close()}
        if(!opening && device==null)finishClose()
        else {worker.removeCallbacks(closeTimeout);worker.postDelayed(closeTimeout,8_000)}
    }
    private fun finishClose(){
        if(closed)return;closed=true;worker.removeCallbacks(closeTimeout)
        runCatching{surface?.release()};surface=null
        val callbacks=closedListeners.toList();closedListeners.clear()
        main.post{callbacks.forEach{it(true)}};thread.quitSafely()
    }
}

/** Main-thread handoff: failed/late release or leaving the page cannot start a recorder. */
class PreviewHandoff(private val begin:()->Unit) {
    private var generation=0
    var pending=false;private set
    fun afterClose(close:((Boolean)->Unit)->Unit){
        if(pending)return
        pending=true;val ticket=++generation
        close {released->if(ticket==generation && pending){pending=false;if(released)begin()}}
    }
    fun cancel(){generation++;pending=false}
}
