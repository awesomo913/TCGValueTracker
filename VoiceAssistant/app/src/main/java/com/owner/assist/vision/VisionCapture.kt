package com.owner.assist.vision

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VisionCapture(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var bound = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun bind() {
        if (bound) return
        if (!hasPermission()) {
            Log.w(TAG, "CAMERA permission not granted")
            return
        }
        withContext(Dispatchers.Main) {
            try {
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                )
                imageCapture = capture
                bound = true
                Log.i(TAG, "camera bound")
            } catch (e: Exception) {
                Log.w(TAG, "bind failed: ${e.message}")
            }
        }
    }

    suspend fun captureJpegBase64(): String {
        if (!bound) bind()
        val capture = imageCapture ?: throw IllegalStateException("camera not bound")
        val tempFile = File.createTempFile("vis_", ".jpg", ctx.cacheDir)
        return suspendCancellableCoroutine { cont ->
            val opts = ImageCapture.OutputFileOptions.Builder(tempFile).build()
            capture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = tempFile.readBytes()
                        tempFile.delete()
                        cont.resume(Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        tempFile.delete()
                        cont.resumeWithException(e)
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    tempFile.delete()
                    cont.resumeWithException(e)
                }
            })
        }
    }

    fun release() {
        imageCapture = null
        bound = false
    }

    companion object {
        private const val TAG = "VisionCapture"
    }
}
