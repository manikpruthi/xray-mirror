package dev.manik.xraymirror

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.manik.xraymirror.camera.FramePipeline
import dev.manik.xraymirror.camera.MediaSaver
import dev.manik.xraymirror.gl.AnatomyData
import dev.manik.xraymirror.gl.Facts
import dev.manik.xraymirror.gl.Stats
import dev.manik.xraymirror.gl.XrayRenderer
import dev.manik.xraymirror.ui.MirrorScreen
import dev.manik.xraymirror.ui.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var renderer: XrayRenderer? = null
    private var pipeline: FramePipeline? = null
    private lateinit var saver: MediaSaver
    private var data: AnatomyData? = null
    private var facts: Facts? = null

    private val ui = UiState()
    private var settings by mutableStateOf(Settings())
    private var recordingSince by mutableStateOf(0L)
    private var pickedId by mutableIntStateOf(-1)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ui.cameraDenied = !granted
        if (granted) startCamera() else setDemo(true)
    }
    private var afterAudioPermission: (() -> Unit)? = null
    private val recordPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterAudioPermission?.invoke(); afterAudioPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saver = MediaSaver(this)

        setContent {
            MirrorScreen(
                ui = ui,
                settings = settings,
                onSettings = ::updateSettings,
                pickedTitle = pickedInfo(),
                recordingSince = recordingSince,
                surface = ::makeSurface,
                onTap = { x, y -> renderer?.pick(x, y) },
                onDrag = { x, y, dx, dy, zoom, fingers ->
                    val r = renderer ?: return@MirrorScreen
                    when {
                        r.demo && fingers == 1 -> { r.demoYaw += dx * 0.01f; r.requestDraw() }
                        r.demo -> r.zoomPan(zoom, dx, dy, x, y)
                        settings.reveal == Reveal.TOUCH_LENS && fingers == 1 -> r.setTouch(x, y)
                        else -> r.zoomPan(zoom, dx, dy, x, y)
                    }
                },
                onDoubleTap = {
                    val r = renderer
                    if (r?.demo == true) { r.demoZoom = 1f; r.demoYaw = 0f; r.demoPanX = 0f; r.demoPanY = 0f; r.requestDraw() } else r?.resetZoom()
                },
                onClosePick = { pickedId = -1; renderer?.selectedPart = -1; renderer?.requestDraw() },
                onPhoto = ::takePhoto,
                onRecord = ::toggleRecording,
                onDemo = ::setDemo,
                onInsets = { top, bottom -> renderer?.topInset = top; renderer?.bottomInset = bottom; ui.topInset = top; ui.bottomInset = bottom },
            )
        }

        lifecycleScope.launch {
            val (d, f) = withContext(Dispatchers.IO) {
                val d = AnatomyData(this@MainActivity)
                d to Facts(this@MainActivity, d)
            }
            data = d; facts = f
            renderer = XrayRenderer(
                this@MainActivity, d, f,
                releaseSlot = { pipeline?.release(it) },
                onStats = { s: Stats -> ui.stats = s },
                onPick = { id -> pickedId = id; if (id < 0) renderer?.selectedPart = -1 },
            ).also { r -> r.settings = settings; r.topInset = ui.topInset; r.bottomInset = ui.bottomInset }
            applyLaunchExtras()
            ui.loaded = true
            pendingSurface?.let { (h, w, ht) -> renderer?.attach(h.surface, w, ht) }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Optional launch extras for scripted screenshots, e.g.
     * adb shell am start -n dev.manik.xraymirror/.MainActivity --es layers skeleton,organs --es look solid
     *   --es sex female --ez demo true --ef yaw 1.57
     */
    private fun applyLaunchExtras() {
        val x = intent?.extras ?: return
        var s = settings
        x.getString("layers")?.let { l -> s = s.copy(layers = l.split(",").mapNotNull { n -> Layer.entries.firstOrNull { it.name.equals(n.trim(), true) } }.toSet()) }
        x.getString("look")?.let { s = s.copy(look = if (it.equals("solid", true)) Look.SOLID else Look.XRAY) }
        x.getString("sex")?.let { s = s.copy(sex = if (it.equals("female", true)) Sex.FEMALE else Sex.MALE) }
        x.getString("reveal")?.let { r -> Reveal.entries.firstOrNull { it.name.equals(r, true) }?.let { s = s.copy(reveal = it) } }
        if (x.containsKey("labels")) s = s.copy(showLabels = x.getBoolean("labels"))
        if (x.containsKey("tracking")) s = s.copy(showTracking = x.getBoolean("tracking"))
        updateSettings(s)
        if (x.getBoolean("demo", false)) setDemo(true)
        if (x.containsKey("yaw")) renderer?.demoYaw = x.getFloat("yaw")
        if (x.containsKey("zoom")) renderer?.demoZoom = x.getFloat("zoom")
    }

    private var pendingSurface: Triple<SurfaceHolder, Int, Int>? = null

    private fun makeSurface(ctx: android.content.Context): SurfaceView = SurfaceView(ctx).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) {
                pendingSurface = Triple(h, w, ht)
                renderer?.attach(h.surface, w, ht)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { pendingSurface = null; renderer?.detach() }
        })
    }

    private fun startCamera() {
        val r = renderer ?: return
        if (pipeline == null) pipeline = FramePipeline(this) { b ->
            r.inferenceMs = pipeline?.inferenceMs ?: 0f
            r.usingGpu = pipeline?.usingGpu ?: false
            r.onFrame(b)
        }
        try {
            val test = intent?.getStringExtra("testImage")
            if (test != null) pipeline!!.startTestImage(test, settings.frontCamera)
            else pipeline!!.start(this, settings.frontCamera)
            ui.cameraDenied = false
        } catch (e: Throwable) {
            ui.error = "Couldn't start camera/pose tracking: ${e.message}"
            setDemo(true)
        }
    }

    private fun updateSettings(s: Settings) {
        val old = settings
        settings = s
        renderer?.settings = s
        if (old.frontCamera != s.frontCamera) pipeline?.switchCamera(this, s.frontCamera)
    }

    private fun setDemo(on: Boolean) {
        ui.demo = on
        renderer?.demo = on
    }

    private fun pickedInfo(): Triple<String, String, String>? {
        val id = pickedId
        val d = data ?: return null
        if (id < 0) return null
        val part = d.parts.getOrNull(id) ?: return null
        val fact = facts?.forPart(id)
        val title = fact?.title ?: part.displayName
        val sub = "${part.displayName} · ${part.layer.label}"
        val text = fact?.fact ?: "Part of the ${part.layer.label.lowercase()} layer."
        return Triple(title, sub, text)
    }

    private fun takePhoto() {
        val r = renderer ?: return
        val go = {
            r.capturePhoto { bmp ->
                lifecycleScope.launch {
                    val uri = withContext(Dispatchers.IO) { runCatching { saver.savePhoto(bmp) }.getOrNull() }
                    bmp.recycle()
                    toast(if (uri != null) "Photo saved to Pictures/X-ray Mirror" else "Couldn't save the photo")
                }
            }
        }
        if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            afterAudioPermission = { if (has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) go() }
            recordPermissions.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else go()
    }

    private fun toggleRecording() {
        val r = renderer ?: return
        if (saver.recording) {
            recordingSince = 0L
            r.stopRecording {
                lifecycleScope.launch {
                    val uri = withContext(Dispatchers.IO) { saver.stopVideo() }
                    toast(if (uri != null) "Video saved to Movies/X-ray Mirror" else "Recording was too short to save")
                }
            }
            return
        }
        val start = {
            try {
                val (sw, sh) = r.screenSize()
                val (w, h) = saver.videoSize(sw, sh)
                val surface = saver.startVideo(w, h, has(Manifest.permission.RECORD_AUDIO))
                r.startRecording(surface, w, h)
                recordingSince = System.currentTimeMillis()
            } catch (e: Throwable) {
                toast("Couldn't start recording: ${e.message}")
            }
        }
        val need = buildList {
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < 29 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (need.isEmpty()) start() else { afterAudioPermission = { start() }; recordPermissions.launch(need.toTypedArray()) }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        if (saver.recording) toggleRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipeline?.close()
        renderer?.release()
    }
}
