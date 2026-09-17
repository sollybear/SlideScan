package com.slidescan.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.slidescan.app.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var scanning = false; private var count = 0
    private var lastSignature: IntArray? = null; private var candidateSignature: IntArray? = null
    private var candidateSince = 0L; private var lastCaptureAt = 0L
    private var armed = true; private var rearmSince = 0L; private var imageCapture: ImageCapture? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else binding.statusText.text = "Camera permission is required." }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.startStopButton.setOnClickListener {
            scanning = !scanning; binding.startStopButton.text = if (scanning) "STOP SCANNING" else "START SCANNING"
            binding.statusText.text = if (scanning) "Watching for the next stable slide…" else "Paused."
            candidateSignature = null; candidateSince = 0L; armed = true; rearmSince = 0L
            if (scanning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ val provider = future.get(); val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build(); analysis.setAnalyzer(executor) { analyze(it) }
            provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (!scanning) { image.close(); return }; val sig = luminanceSignature(image); image.close(); val previous = lastSignature; val now = System.currentTimeMillis()
        if (previous == null) { if (candidateSignature == null) { candidateSignature=sig; candidateSince=now } else if (distance(candidateSignature!!,sig)<0.010 && now-candidateSince>280) capture(sig) else if (distance(candidateSignature!!,sig)>=0.010) { candidateSignature=sig; candidateSince=now }; return }
        val slider=binding.sensitivity.progress.coerceIn(0,20)
        // Whole-frame path catches obvious slide changes. Local paths deliberately go much lower so a
        // title/bullet/small-text change can trigger even when most of the screen remains identical.
        val globalThreshold=0.014-(slider*0.00055)
        val tileThreshold=0.020-(slider*0.00065)
        val globalDiff=distance(previous,sig)
        val tileRatio=changedTileRatio(previous,sig,tileThreshold)
        val peakTile=peakTileChange(previous,sig)
        val meaningfulChange=globalDiff>=globalThreshold || tileRatio>=0.014 || peakTile>=0.040
        if (!armed) {
            val settled=globalDiff<globalThreshold*0.55 && tileRatio<0.010 && peakTile<0.025
            if (settled) { if (rearmSince==0L) rearmSince=now; if(now-rearmSince>=550){armed=true;rearmSince=0L} } else rearmSince=0L
            return
        }
        if (meaningfulChange && now-lastCaptureAt>500) {
            val c=candidateSignature
            if(c==null){candidateSignature=sig;candidateSince=now;runOnUiThread{binding.statusText.text="Slide change detected…"}}
            else { val stability=distance(c,sig); if(stability<=0.010){if(now-candidateSince>=200)capture(sig)} else {candidateSignature=sig;candidateSince=now} }
        } else if(!meaningfulChange){candidateSignature=null;candidateSince=0L;runOnUiThread{binding.statusText.text="Watching for the next stable slide…"}}
    }

    private fun luminanceSignature(image:ImageProxy):IntArray{val p=image.planes[0];val b=p.buffer;val rs=p.rowStride;val ps=p.pixelStride;val w=image.width;val h=image.height;val gx=64;val gy=36;val o=IntArray(gx*gy);for(y0 in 0 until gy)for(x0 in 0 until gx){val x=((x0+.5)*w/gx).toInt().coerceIn(0,w-1);val y=((y0+.5)*h/gy).toInt().coerceIn(0,h-1);o[y0*gx+x0]=b.get(y*rs+x*ps).toInt() and 0xff};return o}
    private fun distance(a:IntArray,b:IntArray):Double{var s=0.0;for(i in a.indices)s+=abs(a[i]-b[i])/255.0;return s/a.size}
    private fun changedTileRatio(a:IntArray,b:IntArray,t:Double):Double{var changed=0;var tiles=0;for(ty in 0 until 9)for(tx in 0 until 16){var s=0.0;for(dy in 0 until 4)for(dx in 0 until 4){val i=(ty*4+dy)*64+(tx*4+dx);s+=abs(a[i]-b[i])/255.0};if(s/16.0>=t)changed++;tiles++};return changed.toDouble()/tiles}
    private fun peakTileChange(a:IntArray,b:IntArray):Double{var peak=0.0;for(ty in 0 until 9)for(tx in 0 until 16){var s=0.0;for(dy in 0 until 4)for(dx in 0 until 4){val i=(ty*4+dy)*64+(tx*4+dx);s+=abs(a[i]-b[i])/255.0};peak=maxOf(peak,s/16.0)};return peak}

    private fun capture(signature:IntArray){val cap=imageCapture?:return;lastCaptureAt=System.currentTimeMillis();candidateSignature=null;candidateSince=0L;armed=false;rearmSince=0L;cap.takePicture(executor,object:ImageCapture.OnImageCapturedCallback(){override fun onCaptureSuccess(image:ImageProxy){try{val b=image.planes[0].buffer;val bytes=ByteArray(b.remaining());b.get(bytes);image.close();val bm=BitmapFactory.decodeByteArray(bytes,0,bytes.size);val corrected=centerCrop16x9(bm,1920,1080);saveBitmap(corrected);bm.recycle();if(corrected!==bm)corrected.recycle();lastSignature=signature;count++;confirm()}catch(e:Exception){image.close();armed=true;runOnUiThread{binding.statusText.text="Capture failed: ${e.message}"}}}})}
    private fun centerCrop16x9(src:Bitmap,targetW:Int,targetH:Int):Bitmap{val tr=16f/9f;val sr=src.width.toFloat()/src.height;val cw:Int;val ch:Int;if(sr>tr){ch=src.height;cw=(ch*tr).toInt()}else{cw=src.width;ch=(cw/tr).toInt()};val c=Bitmap.createBitmap(src,(src.width-cw)/2,(src.height-ch)/2,cw,ch);return Bitmap.createScaledBitmap(c,targetW,targetH,true).also{if(it!==c)c.recycle()}}
    private fun saveBitmap(bitmap:Bitmap){val v=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"Slide_%03d.jpg".format(count+1));put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SlideScan")};val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v)?:error("Could not create image");contentResolver.openOutputStream(uri).use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it!!)}}
    private fun confirm(){runOnUiThread{binding.countText.text="Captured: $count";binding.statusText.text="Slide $count captured ✓ — readying next capture.";ToneGenerator(AudioManager.STREAM_NOTIFICATION,90).startTone(ToneGenerator.TONE_PROP_BEEP,180);(getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(120,VibrationEffect.DEFAULT_AMPLITUDE))}}
}
