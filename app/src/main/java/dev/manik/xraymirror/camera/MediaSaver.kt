package dev.manik.xraymirror.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves photos and screen recordings of the rendered view to the gallery (Pictures/ and Movies/X-ray Mirror). */
class MediaSaver(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var legacyFile: File? = null

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun savePhoto(bmp: Bitmap): Uri? {
        val name = "XrayMirror_${stamp()}.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/X-ray Mirror")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "X-ray Mirror").apply { mkdirs() }
            val f = File(dir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null)
            Uri.fromFile(f)
        }
    }

    /** Picks an encoder-supported size with the screen's aspect ratio, about 720 px wide. */
    fun videoSize(screenW: Int, screenH: Int): Pair<Int, Int> {
        val w = 720
        val h = ((w.toFloat() * screenH / screenW) / 16).toInt() * 16
        val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        return if (caps.any { it.isSizeSupported(w, h) }) w to h else 720 to 1280
    }

    /** Starts a recording and returns the encoder's input surface (render into it). */
    fun startVideo(w: Int, h: Int, withAudio: Boolean): Surface {
        val name = "XrayMirror_${stamp()}.mp4"
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/X-ray Mirror")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
            pendingUri = uri
            pfd = context.contentResolver.openFileDescriptor(uri, "w")
            r.setOutputFile(pfd!!.fileDescriptor)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "X-ray Mirror").apply { mkdirs() }
            legacyFile = File(dir, name)
            r.setOutputFile(legacyFile!!.path)
        }
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(8_000_000)
        if (withAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
        }
        r.prepare()
        recorder = r
        val s = r.surface
        r.start()
        return s
    }

    /** Stops the recording; returns the saved video, or null if nothing usable was written. */
    fun stopVideo(): Uri? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        pfd?.close(); pfd = null
        pendingUri?.let { uri ->
            pendingUri = null
            if (!ok) { context.contentResolver.delete(uri, null, null); return null }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        legacyFile?.let { f ->
            legacyFile = null
            if (!ok) { f.delete(); return null }
            MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null)
            return Uri.fromFile(f)
        }
        return null
    }

    val recording get() = recorder != null
}
