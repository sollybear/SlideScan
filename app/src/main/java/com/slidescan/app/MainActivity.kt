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
    private var scanning = false
    private var count = 0
    private var lastSignature: IntArray? = null
    private var candidateSignature: IntArray? = null
    private var candidateSince = 0L
    private var lastCaptureAt = 0L
    private var armed = true
    private var rearmSince = 0L
    private var imageCapture: ImageCapture? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else binding.statusText.text = "Camera permission is required." }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.startStopButton.setOnClickListener {
            scanning = !scanning
            binding.startStopButton.text = if (scanning) "STOP SCANNING" else "START SCANNING"
            binding.statusText.text = if (scanning) "Watching for the next stable slide…" else "Paused."
            candidateSignature = null; candidateSince = 0L; armed = true; rearmSince = 0L
            if (scanning) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image -> analyze(image) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (!scanning) { image.close(); return }
        val sig = luminanceSignature(image); image.close()
        val previous = lastSignature
        val now = System.currentTimeMillis()

        if (previous == null) {
            if (candidateSignature == null) { candidateSignature = sig; candidateSince = now }
            else if (distance(candidateSignature!!, sig) < 0.012 && now - candidateSince > 300) capture(sig)
            else if (distance(candidateSignature!!, sig) >= 0.012) { candidateSignature = sig; candidateSince = now }
            return
        }

        val slider = binding.sensitivity.progress.coerceIn(0, 20)
        val globalThreshold = 0.018 - (slider * 0.000725)
        val tileThreshold = 0.030 - (slider * 0.0009)
        val globalDiff = distance(previous, sig)
        val changedTileRatio = changedTileRatio(previous, sig, tileThreshold)
        val meaningfulChange = globalDiff >= globalThreshold || changedTileRatio >= 0.035

        // After a capture, don't allow a second capture until the camera has remained close
        // to the captured state for a short period. This rejects exposure/focus settling and
        // transition tails that previously caused double captures.
        if (!armed) {
            val settled = globalDiff < globalThreshold * 0.45 && changedTileRatio < 0.018
            if (settled) {
                if (rearmSince == 0L) rearmSince = now
                if (now - rearmSince >= 650) { armed = true; rearmSince = 0L }
            } else rearmSince = 0L
            return
        }

        if (meaningfulChange && now - lastCaptureAt > 550) {
            val candidate = candidateSignature
            if (candidate == null) {
                candidateSignature = sig; candidateSince = now
                runOnUiThread { binding.statusText.text = "Slide change detected…" }
            } else {
                val stability = distance(candidate, sig)
                if (stability <= 0.012) {
                    if (now - candidateSince >= 240) capture(sig)
                } else {
                    candidateSignature = sig; candidateSince = now
                }
            }
        } else if (!meaningfulChange) {
            candidateSignature = null; candidateSince = 0L
            runOnUiThread { binding.statusText.text = "Watching for the next stable slide…" }
        }
    }

    private fun luminanceSignature(image: ImageProxy): IntArray {
        val plane = image.planes[0]; val buffer = plane.buffer
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val w = image.width; val h = image.height; val gridX = 48; val gridY = 27
        val out = IntArray(gridX * gridY)
        for (gy in 0 until gridY) for (gx in 0 until gridX) {
            val x = ((gx + .5) * w / gridX).toInt().coerceIn(0, w - 1)
            val y = ((gy + .5) * h / gridY).toInt().coerceIn(0, h - 1)
            out[gy * gridX + gx] = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xff
        }
        return out
    }

    private fun distance(a: IntArray, b: IntArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i]) / 255.0
        return sum / a.size
    }

    private fun changedTileRatio(a: IntArray, b: IntArray, threshold: Double): Double {
        // 48x27 samples are grouped into 3x3 blocks => 16x9 spatial tiles.
        // A small text-heavy area can therefore trigger without needing to move the whole-frame average.
        var changed = 0; var tiles = 0
        for (ty in 0 until 9) for (tx in 0 until 16) {
            var sum = 0.0
            for (dy in 0 until 3) for (dx in 0 until 3) {
                val i = (ty * 3 + dy) * 48 + (tx * 3 + dx)
                sum += abs(a[i] - b[i]) / 255.0
            }
            if (sum / 9.0 >= threshold) changed++
            tiles++
        }
        return changed.toDouble() / tiles
    }

    private fun capture(signature: IntArray) {
        val capture = imageCapture ?: return
        lastCaptureAt = System.currentTimeMillis(); candidateSignature = null; candidateSince = 0L; armed = false; rearmSince = 0L
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer; val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); image.close()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val corrected = centerCrop16x9(bitmap, 1920, 1080)
                    saveBitmap(corrected); bitmap.recycle(); if (corrected !== bitmap) corrected.recycle()
                    lastSignature = signature; count++; confirm()
                } catch (e: Exception) { image.close(); armed = true; runOnUiThread { binding.statusText.text = "Capture failed: ${e.message}" } }
            }
        })
    }

    private fun centerCrop16x9(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val targetRatio = 16f / 9f; val srcRatio = src.width.toFloat() / src.height
        val cropW: Int; val cropH: Int
        if (srcRatio > targetRatio) { cropH = src.height; cropW = (cropH * targetRatio).toInt() } else { cropW = src.width; cropH = (cropW / targetRatio).toInt() }
        val cropped = Bitmap.createBitmap(src, (src.width-cropW)/2, (src.height-cropH)/2, cropW, cropH)
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true).also { if (it !== cropped) cropped.recycle() }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "Slide_%03d.jpg".format(count+1)); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SlideScan") }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create image")
        contentResolver.openOutputStream(uri).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it!!) }
    }

    private fun confirm() { runOnUiThread { binding.countText.text = "Captured: $count"; binding.statusText.text = "Slide $count captured ✓ — readying next capture."; ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).startTone(ToneGenerator.TONE_PROP_BEEP, 180); (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)) } }
}
