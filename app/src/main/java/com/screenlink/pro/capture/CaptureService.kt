package com.screenlink.pro.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.screenlink.pro.R
import com.screenlink.pro.network.ScreenServer
import com.screenlink.pro.util.Pairing

class CaptureService : Service() {
    companion object { const val START="start"; const val STOP="stop"; const val CODE="code"; const val DATA="data"; const val RESULT="result"; private const val CHANNEL="screenlink" }
    private var projection: MediaProjection?=null; private var display: android.hardware.display.VirtualDisplay?=null; private var codec: MediaCodec?=null; private var server: ScreenServer?=null; private var thread: Thread?=null; @Volatile private var running=false
    override fun onCreate(){ super.onCreate(); if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Screen sharing",NotificationManager.IMPORTANCE_LOW)) }
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int { if(i?.action==STOP){stopAll();stopSelfResult(id);return START_NOT_STICKY}; if(i?.action==START) startCapture(i); return START_NOT_STICKY }
    private fun startCapture(i:Intent){ if(running)return; try {
        val result=i.getIntExtra(RESULT,Activity.RESULT_CANCELED)
        val data: Intent? = if(Build.VERSION.SDK_INT>=33) {
            i.getParcelableExtra(DATA,Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(DATA)
        }
        if(result!=Activity.RESULT_OK||data==null)throw IllegalStateException("Screen permission was not granted")
        val pm=getSystemService(MediaProjectionManager::class.java); val p=pm.getMediaProjection(result,data)?:error("Projection unavailable"); projection=p; p.registerCallback(object:MediaProjection.Callback(){override fun onStop(){stopAll();stopSelf()}},null); foreground()
        val m=android.util.DisplayMetrics(); @Suppress("DEPRECATION") (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m); var w=m.widthPixels;var h=m.heightPixels; val scale=1280f/maxOf(w,h);if(scale<1){w=(w*scale).toInt();h=(h*scale).toInt()};w=w and 1.inv();h=h and 1.inv();
        val f=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,w,h).apply{setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);setInteger(MediaFormat.KEY_BIT_RATE,4_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,30);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2)}; val c=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);c.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);val surface=c.createInputSurface();c.start();codec=c;display=p.createVirtualDisplay("ScreenLink",w,h,m.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);val s=ScreenServer(47821,i.getStringExtra(CODE)?:Pairing.generate(),w,h);s.start();server=s;running=true;thread=Thread{val bi=MediaCodec.BufferInfo();while(running)try{val x=c.dequeueOutputBuffer(bi,10000);if(x>=0){c.getOutputBuffer(x)?.let{b->if(bi.size>0){val a=ByteArray(bi.size);b.position(bi.offset);b.limit(bi.offset+bi.size);b.get(a);s.send(a)}};c.releaseOutputBuffer(x,false)}}catch(_:Exception){}}.also{it.start()}
    }catch(e:Exception){stopAll();stopSelf()} }
    private fun foreground(){val n=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle(getString(R.string.capture_title)).setContentText(getString(R.string.capture_text)).setOngoing(true).build();if(Build.VERSION.SDK_INT>=29)startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(7,n)}
    private fun stopAll(){running=false;thread?.interrupt();try{display?.release()}catch(_:Exception){};try{codec?.stop()}catch(_:Exception){};try{codec?.release()}catch(_:Exception){};try{server?.stop()}catch(_:Exception){};try{projection?.stop()}catch(_:Exception){};display=null;codec=null;server=null;projection=null;stopForeground(STOP_FOREGROUND_REMOVE)}
    override fun onDestroy(){stopAll();super.onDestroy()};override fun onBind(i:Intent?):IBinder?=null
}
