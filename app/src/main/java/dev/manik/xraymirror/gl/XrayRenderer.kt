package dev.manik.xraymirror.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.manik.xraymirror.Layer
import dev.manik.xraymirror.Look
import dev.manik.xraymirror.Reveal
import dev.manik.xraymirror.Settings
import dev.manik.xraymirror.camera.FrameBundle
import dev.manik.xraymirror.pose.BodyFit
import dev.manik.xraymirror.pose.M3
import dev.manik.xraymirror.pose.V3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Live numbers for the "Show tracking data" panel. */
data class Stats(val fps: Float, val inferenceMs: Float, val tracking: Boolean, val gpu: Boolean, val scale: Float)

/**
 * Owns the GL thread. Per frame: camera + body mask → background, skinned anatomy → on top,
 * callouts → on top, all into an offscreen framebuffer that is then shown on screen and, while
 * recording, copied into the video encoder's surface.
 */
class XrayRenderer(
    private val context: Context,
    private val data: AnatomyData,
    private val facts: Facts,
    private val releaseSlot: (Int) -> Unit,
    private val onStats: (Stats) -> Unit,
    private val onPick: (Int) -> Unit,
) {
    private val thread = HandlerThread("xray-gl").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile var settings = Settings()
        set(v) { field = v; requestDraw() }
    @Volatile var topInset = 0f
    /** Y (px from top) where the bottom controls start; labels stay above it. */
    @Volatile var bottomInset = 0f
    @Volatile var inferenceMs = 0f
    @Volatile var usingGpu = false
    /** Demo mannequin (no camera / no person): yaw in radians and zoom. */
    @Volatile var demoYaw = 0f
    @Volatile var demoZoom = 1f
    /** Explore-mode pan of the figure, in screen pixels (y down). */
    @Volatile var demoPanX = 0f
    @Volatile var demoPanY = 0f
    /** Mirror-mode digital zoom (1 = whole frame) and view centre, frame-normalised. */
    @Volatile var viewZoom = 1f
    private var viewCx = 0.5f
    private var viewCy = 0.5f
    @Volatile private var labelHits: List<LabelRenderer.Hit> = emptyList()
    @Volatile var demo = false
        set(v) { field = v; requestDraw() }

    private val pending = AtomicReference<FrameBundle?>(null)
    private var egl: EglCore? = null
    private var screen: EGLSurface = EGL14.EGL_NO_SURFACE
    private var sw = 0; private var sh = 0
    private var recSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recW = 0; private var recH = 0

    // GL resources (created on the GL thread).
    private lateinit var model: GpuModel
    private lateinit var bg: Prog
    private lateinit var xray: Prog
    private lateinit var solid: Prog
    private lateinit var pickProg: Prog
    private lateinit var blit: Prog
    private lateinit var composite: Prog
    private var anatFbo = 0; private var anatTex = 0; private var anatDepth = 0; private var anatW = 0; private var anatH = 0
    private var halfFloat = false
    private lateinit var labels: LabelRenderer
    private lateinit var lines: LabelRenderer
    private var camTex = 0; private var maskTex = 0
    private var camW = 0; private var camH = 0; private var camRot = 0; private var camFront = true; private var hasCam = false
    private var maskCh = 1
    private var maskW = 0; private var maskH = 0; private var maskRot = 0; private var hasMask = false
    private var fbo = 0; private var fboTex = 0; private var fboDepth = 0; private var fboW = 0; private var fboH = 0
    private var pickFbo = 0; private var pickTex = 0; private var pickDepth = 0
    private var emptyVao = 0

    private val fit = BodyFit(data.rig)
    private var frameW = 720; private var frameH = 1280
    private var lastPoseAt = 0L
    private var lastLog = 0L
    private var touchLens: FloatArray? = null
    private var pendingPick: FloatArray? = null
    private var pendingPhoto: ((Bitmap) -> Unit)? = null
    private var frames = 0; private var fpsAt = SystemClock.uptimeMillis(); private var fps = 0f
    private var drawQueued = false

    // ------------------------------------------------------------------ public API (any thread)
    fun attach(surface: Surface, w: Int, h: Int) = gl.post {
        val first = egl == null
        if (first) egl = EglCore()
        if (screen != EGL14.EGL_NO_SURFACE) egl!!.release(screen)
        screen = egl!!.createWindowSurface(surface)
        sw = w; sh = h
        egl!!.makeCurrent(screen)
        if (first) initGl()
        drawFrame()
    }

    fun resize(w: Int, h: Int) = gl.post { sw = w; sh = h; drawFrame() }

    fun detach() = gl.post {
        egl?.let { if (screen != EGL14.EGL_NO_SURFACE) { it.makeNothingCurrent(); it.release(screen) } }
        screen = EGL14.EGL_NO_SURFACE
    }

    fun onFrame(b: FrameBundle) {
        pending.getAndSet(b)?.let { releaseSlot(it.slot) }
        requestDraw()
    }

    fun requestDraw() {
        synchronized(this) { if (drawQueued) return; drawQueued = true }
        gl.post { synchronized(this) { drawQueued = false }; if (screen != EGL14.EGL_NO_SURFACE) drawFrame() }
    }

    /** Screen pixel → lens centre (for "Lens follows finger"). */
    fun setTouch(x: Float, y: Float) = gl.post { touchLens = floatArrayOf(x, y); drawFrame() }
    fun pick(x: Float, y: Float) = gl.post { pendingPick = floatArrayOf(x, y); drawFrame() }

    /**
     * Pinch/drag: zoom by [zoomFactor] keeping the point under ([cx], [cy]) fixed, then pan by
     * ([dxPx], [dyPx]) screen pixels. Explore mode moves the figure; mirror mode moves the view.
     */
    fun zoomPan(zoomFactor: Float, dxPx: Float, dyPx: Float, cx: Float, cy: Float) = gl.post {
        if (demo) {
            val oldZ = demoZoom
            demoZoom = (demoZoom * zoomFactor).coerceIn(0.5f, 6f)
            val zf = demoZoom / oldZ
            // Figure centre in screen pixels (y down), scaled about the pinch point.
            val ox = sw / 2f + demoPanX; val oy = sh * 0.46f + demoPanY
            demoPanX = cx + (ox - cx) * zf - sw / 2f + dxPx
            demoPanY = cy + (oy - cy) * zf - sh * 0.46f + dyPx
            return@post drawFrame()
        }
        val mirror = if (camFront) -1f else 1f
        val fa = frameW.toFloat() / frameH; val sa = sw.toFloat() / max(sh, 1)
        fun vis(z: Float) = ((if (fa > sa) frameH * sa else frameW.toFloat()) / z) to ((if (fa > sa) frameH.toFloat() else frameW / sa) / z)
        val (w0, h0) = vis(viewZoom)
        val u = cx / max(sw, 1) - 0.5f; val v = 0.5f - cy / max(sh, 1)
        val fx = viewCx + mirror * u * w0 / frameW; val fy = viewCy + v * h0 / frameH
        viewZoom = (viewZoom * zoomFactor).coerceIn(1f, 4f)
        val (w1, h1) = vis(viewZoom)
        viewCx = fx - mirror * u * w1 / frameW - mirror * dxPx / max(sw, 1) * w1 / frameW
        viewCy = fy - v * h1 / frameH + dyPx / max(sh, 1) * h1 / frameH
        drawFrame()
    }

    fun resetZoom() = gl.post { viewZoom = 1f; viewCx = 0.5f; viewCy = 0.5f; drawFrame() }
    fun capturePhoto(cb: (Bitmap) -> Unit) = gl.post { pendingPhoto = cb; drawFrame() }

    fun startRecording(surface: Surface, w: Int, h: Int) = gl.post {
        val e = egl ?: return@post
        recSurface = e.createWindowSurface(surface)
        recW = w; recH = h
    }

    fun stopRecording(done: () -> Unit) = gl.post {
        egl?.let { if (recSurface != EGL14.EGL_NO_SURFACE) it.release(recSurface) }
        recSurface = EGL14.EGL_NO_SURFACE
        if (screen != EGL14.EGL_NO_SURFACE) egl?.makeCurrent(screen)
        main.post(done)
    }

    fun screenSize() = sw to sh

    fun release() {
        gl.post {
            egl?.let { e ->
                if (recSurface != EGL14.EGL_NO_SURFACE) e.release(recSurface)
                if (screen != EGL14.EGL_NO_SURFACE) e.release(screen)
                e.destroy()
            }
            egl = null
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ GL thread
    private fun initGl() {
        model = GpuModel(data)
        bg = Prog(Shaders.QUAD_VS, Shaders.BG_FS)
        xray = Prog(Shaders.ANATOMY_VS, Shaders.XRAY_FS)
        solid = Prog(Shaders.ANATOMY_VS, Shaders.SOLID_FS)
        pickProg = Prog(Shaders.ANATOMY_VS, Shaders.PICK_FS)
        blit = Prog(Shaders.QUAD_VS, Shaders.BLIT_FS)
        composite = Prog(Shaders.QUAD_VS, Shaders.COMPOSITE_FS)
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        halfFloat = "color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
        val density = context.resources.displayMetrics.density
        labels = LabelRenderer(density)
        lines = labels
        val t = IntArray(2)
        glGenTextures(2, t, 0)
        camTex = t[0]; maskTex = t[1]
        for (id in t) {
            glBindTexture(GL_TEXTURE_2D, id)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        val v = IntArray(1)
        glGenVertexArrays(1, v, 0)
        emptyVao = v[0]
    }

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            glDeleteFramebuffers(2, intArrayOf(fbo, pickFbo), 0)
            glDeleteTextures(2, intArrayOf(fboTex, pickTex), 0)
            glDeleteRenderbuffers(2, intArrayOf(fboDepth, pickDepth), 0)
        }
        fun make(): IntArray {
            val f = IntArray(1); val tx = IntArray(1); val rb = IntArray(1)
            glGenFramebuffers(1, f, 0); glGenTextures(1, tx, 0); glGenRenderbuffers(1, rb, 0)
            glBindTexture(GL_TEXTURE_2D, tx[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glBindRenderbuffer(GL_RENDERBUFFER, rb[0])
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h)
            glBindFramebuffer(GL_FRAMEBUFFER, f[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tx[0], 0)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[0])
            return intArrayOf(f[0], tx[0], rb[0])
        }
        val a = make(); fbo = a[0]; fboTex = a[1]; fboDepth = a[2]
        val b = make(); pickFbo = b[0]; pickTex = b[1]; pickDepth = b[2]
        fboW = w; fboH = h
        labels.forgetSmoothing()
        // Anatomy buffer at 75 % resolution: the glow tolerates upscaling and it saves fill rate.
        if (anatFbo != 0) {
            glDeleteFramebuffers(1, intArrayOf(anatFbo), 0); glDeleteTextures(1, intArrayOf(anatTex), 0)
            glDeleteRenderbuffers(1, intArrayOf(anatDepth), 0)
        }
        anatW = (w * ANAT_SCALE).toInt(); anatH = (h * ANAT_SCALE).toInt()
        val f = IntArray(1); val tx = IntArray(1); val rb = IntArray(1)
        glGenFramebuffers(1, f, 0); glGenTextures(1, tx, 0); glGenRenderbuffers(1, rb, 0)
        glBindTexture(GL_TEXTURE_2D, tx[0])
        if (halfFloat) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, anatW, anatH, 0, GL_RGBA, GL_HALF_FLOAT, null)
        else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, anatW, anatH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glBindRenderbuffer(GL_RENDERBUFFER, rb[0])
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, anatW, anatH)
        glBindFramebuffer(GL_FRAMEBUFFER, f[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tx[0], 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rb[0])
        if (halfFloat && glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            halfFloat = false
            glBindTexture(GL_TEXTURE_2D, tx[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, anatW, anatH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        }
        anatFbo = f[0]; anatTex = tx[0]; anatDepth = rb[0]
    }

    private fun uploadBundle(b: FrameBundle) {
        val bmp = b.bitmap
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glBindTexture(GL_TEXTURE_2D, camTex)
        if (bmp.width != camW || bmp.height != camH) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
            camW = bmp.width; camH = bmp.height
        } else {
            GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        releaseSlot(b.slot)
        camRot = b.rotation
        if (camFront != b.frontCamera) fit.reset()
        camFront = b.frontCamera
        hasCam = true
        val upright = camRot % 180 != 0
        frameW = if (upright) camH else camW
        frameH = if (upright) camW else camH
        val m = b.mask
        if (m != null && b.maskWidth > 0) {
            glBindTexture(GL_TEXTURE_2D, maskTex)
            m.rewind()
            val fmt = if (b.maskChannels == 4) GL_RGBA else GL_RED
            val ifmt = if (b.maskChannels == 4) GL_RGBA8 else GL_R8
            if (b.maskWidth != maskW || b.maskHeight != maskH || b.maskChannels != maskCh) {
                glTexImage2D(GL_TEXTURE_2D, 0, ifmt, b.maskWidth, b.maskHeight, 0, fmt, GL_UNSIGNED_BYTE, m)
                maskW = b.maskWidth; maskH = b.maskHeight; maskCh = b.maskChannels
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, maskW, maskH, fmt, GL_UNSIGNED_BYTE, m)
            }
            maskRot = if ((maskH > maskW) == (frameH > frameW)) 0 else camRot
            hasMask = true
        } else hasMask = false
        val pose = b.pose
        if (pose != null) {
            fit.update(pose, settings.fit)
            if ((settings.showTracking || Log.isLoggable("XrayDebug", Log.DEBUG)) && SystemClock.uptimeMillis() - lastLog > 1000) {
                lastLog = SystemClock.uptimeMillis()
                val p = fit.points
                Log.d(TAG, "mask=${maskW}x$maskH maskRot=$maskRot frame=${frameW}x$frameH rot=$camRot k=${fit.scale} nose=${p[0]} earL=${p[7]} earR=${p[8]} shL=${p[11]} shR=${p[12]} hipL=${p[23]} hipR=${p[24]} vis=${fit.visibility.joinToString(",") { "%.1f".format(it) }}")
            }
            lastPoseAt = SystemClock.uptimeMillis()
        }
    }

    /** Places the rest-pose landmarks as a standing figure turned by [demoYaw] — no camera needed. */
    private fun updateDemo() {
        hasCam = false; hasMask = false
        frameW = max(sw, 1); frameH = max(sh, 1)
        val k = frameH * 0.78f / 1.8f * demoZoom
        val rot = M3.axisAngle(V3(0f, 1f, 0f), demoYaw)
        // Zoom pivots around the middle of the body (~0.9 m up), drawn slightly above screen centre.
        val pivot = V3(0f, 0.9f, 0f)
        val center = V3(frameW / 2f + demoPanX, frameH * 0.54f - demoPanY, 0f)
        val pts = FloatArray(99)
        for ((i, p) in data.rig.landmarks) {
            val q = rot * (p - pivot) * k + center
            pts[i * 3] = q.x; pts[i * 3 + 1] = q.y; pts[i * 3 + 2] = q.z
        }
        // Landmarks the model lacks (mouth, inner/outer eyes) are unused by the fit; copy the nose.
        for (i in 0 until 33) if (!data.rig.landmarks.containsKey(i)) {
            val n = data.rig.lm(0); val q = rot * (n - pivot) * k + center
            pts[i * 3] = q.x; pts[i * 3 + 1] = q.y; pts[i * 3 + 2] = q.z
        }
        fit.updateDirect(pts, settings.fit)
        lastPoseAt = SystemClock.uptimeMillis()
    }

    private fun drawFrame() {
        val e = egl ?: return
        if (screen == EGL14.EGL_NO_SURFACE || sw == 0) return
        e.makeCurrent(screen)
        val s = settings
        pending.getAndSet(null)?.let { if (!demo) uploadBundle(it) else releaseSlot(it.slot) }
        if (demo) updateDemo()
        model.updateParts(s.sex)
        ensureFbo(sw, sh)

        val tracking = fit.valid && SystemClock.uptimeMillis() - lastPoseAt < 700
        val mirror = if (!demo && camFront) -1f else 1f
        val fa = frameW.toFloat() / frameH; val sa = sw.toFloat() / sh
        val zoom = if (demo) 1f else viewZoom.coerceIn(1f, 4f)
        val visW = (if (fa > sa) frameH * sa else frameW.toFloat()) / zoom
        val visH = (if (fa > sa) frameH.toFloat() else frameW / sa) / zoom
        if (demo || zoom <= 1.001f) { viewCx = 0.5f; viewCy = 0.5f }
        val hw = visW / frameW / 2f; val hh = visH / frameH / 2f
        viewCx = viewCx.coerceIn(minOf(hw, 0.5f), maxOf(1f - hw, 0.5f))
        viewCy = viewCy.coerceIn(minOf(hh, 0.5f), maxOf(1f - hh, 0.5f))

        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, sw, sh)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)

        val lens = lensUniform(s, mirror, visW, visH, tracking)

        // 1. Background.
        bg.use()
        bindMaskUniforms(bg, s, lens, if (tracking) 1f else 0f)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, camTex)
        glUniform1i(bg.u("uCam"), 0)
        glUniform1i(bg.u("uCamRot"), camRot)
        glUniform4f(bg.u("uView"), mirror, visW / frameW, visH / frameH, 0f)
        glUniform2f(bg.u("uCenter"), viewCx, viewCy)
        glUniform1f(bg.u("uRealMe"), if (tracking) s.realMe else 1f)
        glUniform1f(bg.u("uBgDim"), if (tracking) 0.55f else 0.8f)
        glUniform1f(bg.u("uHasCam"), if (hasCam) 1f else 0f)
        glUniform1f(bg.u("uShowMask"), if (s.showTracking || Log.isLoggable("XrayDebug", Log.DEBUG)) 1f else 0f)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // 2. Anatomy.
        val proj = projection(mirror, visW, visH)
        if (tracking) drawAnatomy(s, proj, lens)

        // 3. Callouts and tracking overlay.
        labelHits = if (tracking && (s.showLabels || selectedPart >= 0))
            labels.draw(callouts(s, mirror, visW, visH, lens), sw, sh, topInset, if (bottomInset > 0) bottomInset else sh.toFloat())
        else emptyList()
        if (s.showTracking && tracking && !demo) drawSkeletonOverlay(mirror, visW, visH)

        // Tap-to-identify.
        pendingPick?.let { p ->
            pendingPick = null
            val hit = labelHits.lastOrNull { p[0] in it.left..it.right && p[1] in it.top..it.bottom }
            when {
                hit != null -> { selectedPart = hit.partId; main.post { onPick(hit.partId) }; requestDraw() }
                tracking -> { doPick(p[0], p[1], s, proj, lens); requestDraw() }
                else -> main.post { onPick(-1) }
            }
        }

        // Photo.
        pendingPhoto?.let { cb ->
            pendingPhoto = null
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            val buf = ByteBuffer.allocateDirect(sw * sh * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, sw, sh, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val flipped = Bitmap.createBitmap(bmp, 0, 0, sw, sh, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
            bmp.recycle()
            main.post { cb(flipped) }
        }

        // 4. Present.
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        present(sw, sh, 0f, 0f, 1f, 1f)
        e.swap(screen)
        if (recSurface != EGL14.EGL_NO_SURFACE) {
            e.makeCurrent(recSurface)
            val ra = recW.toFloat() / recH
            val (ox, oy, sx, sy) = if (ra > sa) listOf(0f, (1 - sa / ra) / 2, 1f, sa / ra) else listOf((1 - ra / sa) / 2, 0f, ra / sa, 1f)
            present(recW, recH, ox, oy, sx, sy)
            e.setPresentationTime(recSurface, System.nanoTime())
            e.swap(recSurface)
            e.makeCurrent(screen)
        }

        frames++
        val now = SystemClock.uptimeMillis()
        if (now - fpsAt >= 1000) {
            fps = frames * 1000f / (now - fpsAt); frames = 0; fpsAt = now
            val st = Stats(fps, inferenceMs, tracking, usingGpu, fit.scale)
            main.post { onStats(st) }
        }
        if (demo) requestDraw() // keep spinning smoothly while dragging
    }

    private fun present(w: Int, h: Int, ox: Float, oy: Float, sx: Float, sy: Float) {
        glViewport(0, 0, w, h)
        glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND)
        blit.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, fboTex)
        glUniform1i(blit.u("uTex"), 0)
        glUniform4f(blit.u("uCrop"), ox, oy, sx, sy)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Ortho projection: frame pixels (Y up, Z toward viewer) → clip space with centre-crop fill. */
    private fun projection(mirror: Float, visW: Float, visH: Float): FloatArray {
        val zr = frameH * 3f
        return floatArrayOf(
            mirror * 2f / visW, 0f, 0f, 0f,
            0f, 2f / visH, 0f, 0f,
            0f, 0f, -1f / zr, 0f,
            -mirror * 2f * viewCx * frameW / visW, -2f * viewCy * frameH / visH, 0f, 1f,
        )
    }

    private fun frameToScreen(p: V3, mirror: Float, visW: Float, visH: Float): Pair<Float, Float> {
        val u = 0.5f + mirror * (p.x / frameW - viewCx) * frameW / visW
        val v = 0.5f + (p.y / frameH - viewCy) * frameH / visH
        return u * sw to (1f - v) * sh
    }

    private fun screenToFrame(x: Float, y: Float, mirror: Float, visW: Float, visH: Float): Pair<Float, Float> {
        val u = x / sw; val v = 1f - y / sh
        val fx = viewCx + mirror * (u - 0.5f) * visW / frameW
        val fy = viewCy + (v - 0.5f) * visH / frameH
        return fx to fy
    }

    /** (centreX, centreY, radius) in frame-normalised units, or radius < 0 for whole body. */
    private fun lensUniform(s: Settings, mirror: Float, visW: Float, visH: Float, tracking: Boolean): FloatArray {
        val r = 0.08f + s.lensSize * 0.3f
        return when (s.reveal) {
            Reveal.BODY -> floatArrayOf(0f, 0f, -1f)
            Reveal.HAND_LENS -> {
                if (!tracking) return floatArrayOf(0.5f, 0.5f, r)
                val i = if (fit.visibility[19] >= fit.visibility[20]) 19 else 20
                val p = fit.points[i]
                floatArrayOf(p.x / frameW, p.y / frameH, r)
            }
            Reveal.TOUCH_LENS -> {
                val t = touchLens ?: return floatArrayOf(0.5f, 0.55f, r)
                val (fx, fy) = screenToFrame(t[0], t[1], mirror, visW, visH)
                floatArrayOf(fx, fy, r)
            }
        }
    }

    private fun bindMaskUniforms(p: Prog, s: Settings, lens: FloatArray, maskOn: Float) {
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, maskTex)
        glUniform1i(p.u("uMask"), 1)
        glUniform1i(p.u("uMaskRot"), maskRot)
        glUniform1f(p.u("uHasMask"), if (hasMask) maskOn else 0f)
        glUniform1f(p.u("uFeather"), 0.002f + s.feather * 0.02f)
        glUniform3f(p.u("uLens"), lens[0], lens[1], lens[2])
        glUniform1f(p.u("uAspect"), frameW.toFloat() / frameH)
    }

    private fun bindAnatomyCommon(p: Prog, s: Settings, proj: FloatArray, lens: FloatArray, selected: Int) {
        p.use()
        glUniformMatrix4fv(p.u("uBones"), fit.boneCount, false, fit.boneMatrices, 0)
        glUniformMatrix4fv(p.u("uProj"), 1, false, proj, 0)
        glUniform2f(p.u("uFrame"), frameW.toFloat(), frameH.toFloat())
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, model.partTex)
        glUniform1i(p.u("uParts"), 2)
        glUniform1i(p.u("uSelected"), selected)
        bindMaskUniforms(p, s, lens, 1f)
        glUniform1f(p.u("uClip"), if (s.clipToBody && hasMask) 1f else 0f)
    }

    var selectedPart = -1

    private fun drawAnatomy(s: Settings, proj: FloatArray, lens: FloatArray) {
        glBindFramebuffer(GL_FRAMEBUFFER, anatFbo)
        glViewport(0, 0, anatW, anatH)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        renderLayers(s, proj, lens)
        // Composite onto the full-resolution frame.
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, sw, sh)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        if (s.look == Look.XRAY) glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_COLOR) else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        composite.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, anatTex)
        glUniform1i(composite.u("uAnat"), 0)
        glUniform1f(composite.u("uSolid"), if (s.look == Look.SOLID) 1f else 0f)
        glUniform1f(composite.u("uExposure"), 1.5f * s.brightness * (if (halfFloat) 1f else 1.6f))
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glDisable(GL_BLEND)
    }

    private fun renderLayers(s: Settings, proj: FloatArray, lens: FloatArray) {
        if (s.look == Look.XRAY) {
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE)
            glDisable(GL_DEPTH_TEST)
            bindAnatomyCommon(xray, s, proj, lens, selectedPart)
            for (layer in Layer.entries) if (layer in s.layers) {
                val (tint, mix, core, rim) = XRAY_STYLE.getValue(layer)
                glUniform3f(xray.u("uTint"), tint[0], tint[1], tint[2])
                glUniform1f(xray.u("uPartMix"), mix[0])
                glUniform1f(xray.u("uCore"), core[0])
                glUniform1f(xray.u("uRim"), rim[0])
                glUniform1f(xray.u("uGain"), if (halfFloat) 1f else 0.6f)
                model.draw(layer)
            }
            glDisable(GL_BLEND)
        } else {
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true)
            glClear(GL_DEPTH_BUFFER_BIT)
            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            bindAnatomyCommon(solid, s, proj, lens, selectedPart)
            glUniform1f(solid.u("uGain"), s.brightness.coerceAtMost(1.4f))
            for (layer in Layer.entries) if (layer in s.layers) model.draw(layer)
            glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST)
        }
    }

    private fun doPick(x: Float, y: Float, s: Settings, proj: FloatArray, lens: FloatArray) {
        glBindFramebuffer(GL_FRAMEBUFFER, pickFbo)
        glViewport(0, 0, sw, sh)
        val r = (14 * context.resources.displayMetrics.density).toInt()
        val gx = x.toInt(); val gy = sh - y.toInt()
        val x0 = (gx - r).coerceIn(0, sw - 1); val y0 = (gy - r).coerceIn(0, sh - 1)
        val w = minOf(2 * r, sw - x0); val h = minOf(2 * r, sh - y0)
        glEnable(GL_SCISSOR_TEST); glScissor(x0, y0, w, h)
        glClearColor(0f, 0f, 0f, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS); glDisable(GL_BLEND)
        bindAnatomyCommon(pickProg, s, proj, lens, -1)
        for (layer in Layer.entries) if (layer in s.layers) model.draw(layer)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        glReadPixels(x0, y0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)
        glDisable(GL_SCISSOR_TEST); glDisable(GL_DEPTH_TEST)
        var best = -1; var bestD = Float.MAX_VALUE
        for (j in 0 until h) for (i in 0 until w) {
            val o = (j * w + i) * 4
            val id = (buf.get(o).toInt() and 0xff) or ((buf.get(o + 1).toInt() and 0xff) shl 8)
            if (id == 0 || id > data.parts.size) continue
            val dx = x0 + i - gx; val dy = y0 + j - gy
            val d = (dx * dx + dy * dy).toFloat()
            if (d < bestD) { bestD = d; best = id - 1 }
        }
        selectedPart = best
        main.post { onPick(best) }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
    }

    private fun callouts(s: Settings, mirror: Float, visW: Float, visH: Float, lens: FloatArray): List<LabelRenderer.Callout> {
        val out = ArrayList<LabelRenderer.Callout>()
        val max = if (s.reveal == Reveal.BODY) 12 else 6
        val selEntry = data.parts.getOrNull(selectedPart)?.let { part ->
            val e = facts.forPart(part.id)
            val p = fit.skin(part.anchor, part.anchorBones, part.anchorWeights)
            val (sx, sy) = frameToScreen(p, mirror, visW, visH)
            out += LabelRenderer.Callout("sel", e?.title ?: part.displayName, 0xFF5CF2E6.toInt(), sx, sy, part.id, selected = true)
            e
        }
        if (!s.showLabels) return out
        for ((entry, part) in facts.labels) {
            if (entry.layer !in s.layers || !part.visibleFor(s.sex) || entry === selEntry) continue
            val p = fit.skin(part.anchor, part.anchorBones, part.anchorWeights)
            if (lens[2] > 0f) {
                val dx = (p.x / frameW - lens[0]) * frameW / frameH; val dy = p.y / frameH - lens[1]
                if (dx * dx + dy * dy > lens[2] * lens[2] * 0.8f) continue
            }
            val (sx, sy) = frameToScreen(p, mirror, visW, visH)
            if (sx < 0 || sx > sw || sy < topInset || (bottomInset > 0 && sy > bottomInset)) continue
            out += LabelRenderer.Callout(entry.title, entry.title, LAYER_DOT.getValue(entry.layer), sx, sy, part.id)
            if (out.size >= max) break
        }
        return out
    }

    private fun drawSkeletonOverlay(mirror: Float, visW: Float, visH: Float) {
        val pairs = listOf(11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16, 11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28, 27 to 31, 28 to 32, 7 to 8)
        val callouts = pairs.mapIndexed { i, (a, b) ->
            val (ax, ay) = frameToScreen(fit.points[a], mirror, visW, visH)
            val (bx, by) = frameToScreen(fit.points[b], mirror, visW, visH)
            floatArrayOf(ax, ay, bx, by)
        }
        lines.drawSegments(callouts, sw, sh)
    }

    companion object {
        private const val ANAT_SCALE = 0.75f
        private val XRAY_STYLE: Map<Layer, List<FloatArray>> = mapOf(
            // tint, part-colour mix, core glow, rim glow
            Layer.SKELETON to listOf(floatArrayOf(0.80f, 0.92f, 1.0f), floatArrayOf(0f), floatArrayOf(0.10f), floatArrayOf(0.85f)),
            Layer.MUSCLES to listOf(floatArrayOf(1.0f, 0.32f, 0.28f), floatArrayOf(0.2f), floatArrayOf(0.03f), floatArrayOf(0.42f)),
            Layer.ARTERIES to listOf(floatArrayOf(1.0f, 0.16f, 0.14f), floatArrayOf(0f), floatArrayOf(0.30f), floatArrayOf(0.9f)),
            Layer.VEINS to listOf(floatArrayOf(0.28f, 0.48f, 1.0f), floatArrayOf(0f), floatArrayOf(0.30f), floatArrayOf(0.9f)),
            Layer.ORGANS to listOf(floatArrayOf(1.0f, 0.62f, 0.5f), floatArrayOf(0.75f), floatArrayOf(0.08f), floatArrayOf(0.8f)),
            Layer.BRAIN to listOf(floatArrayOf(1.0f, 0.72f, 0.82f), floatArrayOf(0.3f), floatArrayOf(0.05f), floatArrayOf(0.7f)),
        )
        val LAYER_DOT: Map<Layer, Int> = mapOf(
            Layer.SKELETON to 0xFFCFE8FF.toInt(), Layer.MUSCLES to 0xFFFF6B5E.toInt(), Layer.ARTERIES to 0xFFFF3B30.toInt(),
            Layer.VEINS to 0xFF4D7CFF.toInt(), Layer.ORGANS to 0xFFFFB38A.toInt(), Layer.BRAIN to 0xFFFFA6C9.toInt(),
        )
        @Suppress("unused") private const val TAG = "XrayRenderer"
        @Suppress("unused") private fun log(m: String) = Log.d(TAG, m)
    }
}
