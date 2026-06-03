package com.owner.assist.vision

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pulls a single JPEG frame from an HTTP endpoint.
 * Works with IP Webcam (Android), DroidCam, GoPro, action cams on WiFi.
 *
 * Typical URLs:
 *   IP Webcam:  http://<ip>:8080/shot.jpg
 *   DroidCam:   http://<ip>:4747/shot.jpg
 *   GoPro:      http://10.5.5.9:8080/gopro/camera/shutter/start (different flow)
 */
object BtCameraSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun captureJpegBase64(url: String): String {
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
        val bytes = resp.body?.bytes() ?: throw IOException("empty body from $url")
        if (bytes.size < 100) throw IOException("suspiciously small response: ${bytes.size}B")
        Log.d(TAG, "BT camera frame: ${bytes.size}B from $url")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private const val TAG = "BtCamSource"
}
