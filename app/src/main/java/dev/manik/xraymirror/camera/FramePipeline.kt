package dev.manik.xraymirror.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import dev.manik.xraymirror.pose.PoseFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything the renderer needs for one displayed frame. The camera image and the pose come from
 * the *same* camera frame, so the anatomy never lags behind the picture.
 */
class FrameBundle(
    val slot: Int,
    val bitmap: Bitmap,
    /** Clockwise degrees needed to make the bitmap upright. */
    val rotation: Int,
    val frontCamera: Boolean,
    val pose: PoseFrame?,
    /** Person mask, one byte per pixel, upright orientation, or null. */
    val mask: ByteBuffer?,
    val maskWidth: Int,
    val maskHeight: Int,
    /** 1 = one byte per pixel, 4 = RGBA8 with the value in red. */
    val maskChannels: Int = 1,
)

/**
 * CameraX analysis → MediaPipe Pose Landmarker (live stream, GPU when available) → renderer.
 * Camera bitmaps live in a small ring of reusable slots so we don't allocate 3–4 MB per frame.
 */
class FramePipeline(private val context: Context, private val onFrame: (FrameBundle) -> Unit) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var landmarker: PoseLandmarker? = null
    var usingGpu = false; private set
    var inferenceMs = 0f; private set

    private class Slot { var bitmap: Bitmap? = null; var mpBitmap: Bitmap? = null; @Volatile var state = FREE; @Volatile var ts = 0L; var rotation = 0; var front = false; var sentAt = 0L }
    private val slots = Array(5) { Slot() }
    private val lastTs = AtomicInteger(0)
    private var camera: ProcessCameraProvider? = null
    @Volatile private var front = true
    private val mpErrors = AtomicReference<String?>(null)
    private var lastDbg = 0L
    private var dumped = false
    private var copyMs = 0L

    fun start(owner: LifecycleOwner, frontCamera: Boolean) {
        front = frontCamera
        if (landmarker == null) landmarker = createLandmarker()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            camera = provider
            bind(owner, provider)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(owner: LifecycleOwner, frontCamera: Boolean) {
        if (front == frontCamera) return
        front = frontCamera
        camera?.let { bind(owner, it) }
    }

    private fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider) {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        val cam = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        try {
            provider.bindToLifecycle(owner, cam, analysis)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed, trying the other camera", e)
            front = !front
            provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }
    }

    private var testTimer: java.util.concurrent.ScheduledExecutorService? = null

    /**
     * Developer hook: runs a still image through the exact live pipeline (slots, MediaPipe,
     * renderer) at ~15 fps instead of the camera. Used to test tracking on an emulator.
     */
    fun startTestImage(path: String, frontCamera: Boolean) {
        front = frontCamera
        if (landmarker == null) landmarker = createLandmarker()
        val src = android.graphics.BitmapFactory.decodeFile(path) ?: error("can't read $path")
        val bmp = src.copy(Bitmap.Config.ARGB_8888, false)
        testTimer = Executors.newSingleThreadScheduledExecutor().also { ex ->
            ex.scheduleWithFixedDelay({ feedStill(bmp) }, 200, 66, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private val mpPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /**
     * MediaPipe gets an upright, half-size copy. Its own rotation option turned out to rotate the
     * opposite way on device, so we rotate ourselves; landmarks and mask then come back upright.
     */
    private fun uprightSmall(slot: Slot, src: Bitmap, rotation: Int): Bitmap {
        val upright = rotation % 180 != 0
        val uw = (if (upright) src.height else src.width) / 2
        val uh = (if (upright) src.width else src.height) / 2
        var dst = slot.mpBitmap
        if (dst == null || dst.width != uw || dst.height != uh) {
            dst = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            slot.mpBitmap = dst
        }
        val m = android.graphics.Matrix()
        m.postRotate(rotation.toFloat(), src.width / 2f, src.height / 2f)
        // After rotating about the centre, shift so the upright image starts at (0, 0), then halve.
        val fw = if (upright) src.height else src.width
        val fh = if (upright) src.width else src.height
        m.postTranslate((fw - src.width) / 2f, (fh - src.height) / 2f)
        m.postScale(0.5f, 0.5f)
        android.graphics.Canvas(dst!!).drawBitmap(src, m, mpPaint)
        return dst
    }

    private fun feedStill(src: Bitmap) {
        val lm = landmarker ?: return
        val now = SystemClock.uptimeMillis()
        val slotIndex = slots.indices.firstOrNull { slots[it].state == FREE }
            ?: slots.indices.firstOrNull { slots[it].state == PENDING && now - slots[it].sentAt > 400 } ?: return
        val slot = slots[slotIndex]
        if (slot.bitmap == null || slot.bitmap!!.width != src.width || slot.bitmap!!.height != src.height) {
            slot.bitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        }
        android.graphics.Canvas(slot.bitmap!!).drawBitmap(src, 0f, 0f, null)
        val ts = now
        lastTs.set(ts.toInt())
        slot.ts = ts; slot.rotation = 0; slot.front = front; slot.sentAt = now; slot.state = PENDING
        lm.detectAsync(BitmapImageBuilder(uprightSmall(slot, slot.bitmap!!, 0)).build(), ts)
    }

    private fun createLandmarker(): PoseLandmarker {
        fun build(delegate: Delegate): PoseLandmarker {
            val base = BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").setDelegate(delegate).build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputSegmentationMasks(true)
                .setResultListener(::onResult)
                .setErrorListener { e -> mpErrors.set(e.message); Log.e(TAG, "mediapipe", e) }
                .build()
            return PoseLandmarker.createFromOptions(context, options)
        }
        return try {
            build(Delegate.GPU).also { usingGpu = true }
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate unavailable, using CPU", e)
            usingGpu = false
            build(Delegate.CPU)
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val now = SystemClock.uptimeMillis()
            // Reclaim slots whose frame MediaPipe silently dropped.
            // At most two frames inside MediaPipe: more only adds latency, not throughput.
            if (slots.count { it.state == PENDING && now - it.sentAt <= 400 } >= 2) return
            val slotIndex = slots.indices.firstOrNull { slots[it].state == FREE }
                ?: slots.indices.firstOrNull { slots[it].state == PENDING && now - slots[it].sentAt > 400 }
                ?: return
            val slot = slots[slotIndex]
            val w = image.width; val h = image.height
            val plane = image.planes[0]
            var bmp = slot.bitmap
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                slot.bitmap = bmp
            }
            val c0 = SystemClock.uptimeMillis()
            if (plane.rowStride == w * 4) {
                plane.buffer.rewind()
                bmp!!.copyPixelsFromBuffer(plane.buffer)
            } else {
                val full = image.toBitmap()
                android.graphics.Canvas(bmp!!).drawBitmap(full, 0f, 0f, null)
                full.recycle()
            }
            copyMs = SystemClock.uptimeMillis() - c0
            // MediaPipe needs strictly increasing timestamps.
            var ts = image.imageInfo.timestamp / 1_000_000
            val prev = lastTs.get()
            if (ts.toInt() <= prev) ts = (prev + 1).toLong()
            lastTs.set(ts.toInt())
            slot.ts = ts
            slot.rotation = image.imageInfo.rotationDegrees
            slot.front = front
            slot.sentAt = now
            slot.state = PENDING
            lm.detectAsync(BitmapImageBuilder(uprightSmall(slot, bmp!!, slot.rotation)).build(), ts)
        } catch (e: Throwable) {
            Log.e(TAG, "analyze", e)
        } finally {
            image.close()
        }
    }

    private fun onResult(result: PoseLandmarkerResult, input: MPImage) {
        val ts = result.timestampMs()
        val idx = slots.indexOfFirst { it.state == PENDING && it.ts == ts }
        if (idx < 0) return
        val slot = slots[idx]
        inferenceMs = (SystemClock.uptimeMillis() - slot.sentAt).toFloat()
        val bmp = slot.bitmap ?: return
        val upright = slot.rotation % 180 != 0
        val fw = if (upright) bmp.height else bmp.width
        val fh = if (upright) bmp.width else bmp.height

        var pose: PoseFrame? = null
        var mask: ByteBuffer? = null
        var mw = 0; var mh = 0; var maskChannels = 1
        val lms = result.landmarks()
        val world = result.worldLandmarks()
        if (lms.isNotEmpty() && world.isNotEmpty()) {
            val l = lms[0]; val wl = world[0]
            val img = FloatArray(66); val vis = FloatArray(33); val wd = FloatArray(99)
            for (i in 0 until 33) {
                img[i * 2] = l[i].x(); img[i * 2 + 1] = l[i].y()
                vis[i] = l[i].visibility().orElse(1f)
                wd[i * 3] = wl[i].x(); wd[i * 3 + 1] = wl[i].y(); wd[i * 3 + 2] = wl[i].z()
            }
            pose = PoseFrame(img, vis, wd, fw, fh, ts * 1_000_000)
            val masks = result.segmentationMasks()
            if (masks.isPresent && masks.get().isNotEmpty()) {
                val m = masks.get()[0]
                mw = m.width; mh = m.height
                val raw = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder())
                raw.rewind()
                if (raw.capacity() >= mw * mh * 4 && looksLikeRgba(raw)) {
                    // GPU path: RGBA8 with the probability in red. Upload as-is (no per-pixel work).
                    val out = maskBuffer(mw * mh * 4)
                    out.clear(); raw.limit(mw * mh * 4); out.put(raw); out.rewind()
                    mask = out; maskChannels = 4
                } else {
                    // CPU path: one float per pixel.
                    val fb = raw.asFloatBuffer()
                    val out = maskBuffer(mw * mh)
                    for (i in 0 until mw * mh) out.put(i, (fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
                    mask = out; maskChannels = 1
                }
                if (Log.isLoggable("XrayDebug", Log.DEBUG) && SystemClock.uptimeMillis() - lastDbg > 1000) {
                    lastDbg = SystemClock.uptimeMillis()
                    if (maskChannels == 4) {
                        val rx = l[0].x(); val ry = l[0].y()
                        fun r(x: Float, y: Float) = raw.get((((y.coerceIn(0f, .999f) * mh).toInt()) * mw + (x.coerceIn(0f, .999f) * mw).toInt()) * 4).toInt() and 0xff
                        // nose vs. a point far outside the body (top corner in raw space)
                        Log.d(TAG, "maskAtNose raw=${r(rx, ry)} flipY=${r(rx, 1 - ry)} flipX=${r(1 - rx, ry)} both=${r(1 - rx, 1 - ry)} corner=${r(0.02f, 0.02f)}")
                    }
                    Log.d(TAG, "mask ${mw}x$mh ch=$maskChannels copy=${copyMs}ms inflight=${slots.count { it.state == PENDING }} latency=${inferenceMs}ms")
                }
            }
        }
        if (lms.isNotEmpty() && Log.isLoggable("XrayDump", Log.DEBUG) && !dumped) {
            dumped = true
            runCatching {
                val dir = context.getExternalFilesDir(null)!!
                java.io.File(dir, "frame.png").outputStream().use { (slot.mpBitmap ?: bmp).compress(Bitmap.CompressFormat.PNG, 100, it) }
                val l = lms[0]
                java.io.File(dir, "frame.txt").writeText("rot=${slot.rotation} w=${bmp.width} h=${bmp.height}\n" +
                    (0 until 33).joinToString("\n") { "$it ${l[it].x()} ${l[it].y()} ${l[it].visibility().orElse(0f)}" })
                Log.d(TAG, "dumped frame to $dir")
            }
        }
        slot.state = READY
        onFrame(FrameBundle(idx, bmp, slot.rotation, slot.front, pose, mask, mw, mh, maskChannels))
    }

    /** A float mask holds values in [0, 1]; an RGBA8 mask read as floats gives huge/NaN numbers. */
    private fun looksLikeRgba(b: ByteBuffer): Boolean {
        val f = b.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = f.capacity()
        var i = 0
        while (i < n) {
            val v = f.get(i)
            if (v.isNaN() || v < -0.01f || v > 1.01f) return true
            i += 997
        }
        return false
    }

    // Two mask buffers alternate so the renderer can read one while the next is written.
    private val maskBuffers = arrayOfNulls<ByteBuffer>(2)
    private var maskFlip = 0
    private fun maskBuffer(size: Int): ByteBuffer {
        maskFlip = 1 - maskFlip
        var b = maskBuffers[maskFlip]
        if (b == null || b.capacity() != size) {
            b = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            maskBuffers[maskFlip] = b
        }
        return b!!
    }

    /** Called by the renderer once it has uploaded a bundle's bitmap. */
    fun release(slot: Int) { slots[slot].state = FREE }

    val lastError: String? get() = mpErrors.get()

    fun close() {
        testTimer?.shutdownNow()
        camera?.unbindAll()
        analysisExecutor.shutdown()
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val TAG = "FramePipeline"
        private const val FREE = 0
        private const val PENDING = 1
        private const val READY = 2
    }
}
