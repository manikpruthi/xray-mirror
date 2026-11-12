package dev.manik.xraymirror.ui

import android.content.Context
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.manik.xraymirror.Fit
import dev.manik.xraymirror.Layer
import dev.manik.xraymirror.Look
import dev.manik.xraymirror.Reveal
import dev.manik.xraymirror.Settings
import dev.manik.xraymirror.Sex
import dev.manik.xraymirror.gl.Stats
import kotlinx.coroutines.delay

/** UI-only state written by the activity / renderer callbacks. */
class UiState {
    var loaded by mutableStateOf(false)
    var demo by mutableStateOf(false)
    var cameraDenied by mutableStateOf(false)
    var stats by mutableStateOf<Stats?>(null)
    var error by mutableStateOf<String?>(null)
    var topInset = 0f
    var bottomInset = 0f
}

private val Panel = Color(0xE6101722)
private val PanelBorder = Color(0x332A9DFF)
private val Accent = Color(0xFF7FD8FF)
private val Muted = Color(0xFF9AA8B8)

private val LayerColor = mapOf(
    Layer.SKELETON to Color(0xFFCFE8FF), Layer.MUSCLES to Color(0xFFFF6B5E), Layer.ARTERIES to Color(0xFFFF3B30),
    Layer.VEINS to Color(0xFF4D7CFF), Layer.ORGANS to Color(0xFFFFB38A), Layer.BRAIN to Color(0xFFFFA6C9),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MirrorScreen(
    ui: UiState,
    settings: Settings,
    onSettings: (Settings) -> Unit,
    pickedTitle: Triple<String, String, String>?,
    recordingSince: Long,
    surface: (Context) -> SurfaceView,
    onTap: (Float, Float) -> Unit,
    onDrag: (x: Float, y: Float, dx: Float, dy: Float, zoom: Float, fingers: Int) -> Unit,
    onDoubleTap: () -> Unit,
    onClosePick: () -> Unit,
    onPhoto: () -> Unit,
    onRecord: () -> Unit,
    onDemo: (Boolean) -> Unit,
    onInsets: (Float, Float) -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    var topPx by remember { mutableFloatStateOf(0f) }
    var bottomPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(topPx, bottomPx) { onInsets(topPx, bottomPx) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = surface, modifier = Modifier.fillMaxSize())
            // Gestures live on a plain Compose layer above the SurfaceView, so the platform view
            // can never swallow them.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { onTap(it.x, it.y) }, onDoubleTap = { onDoubleTap() }) }
                    .pointerInput(Unit) {
                        // Own detector so we know the finger count: 1 finger turns/pans, 2 fingers zoom about the pinch.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var moved = false
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid(useCurrent = true)
                                if (!moved && (pan.getDistance() > viewConfiguration.touchSlop || zoom != 1f)) moved = true
                                if (moved && fingers > 0 && (zoom != 1f || pan != Offset.Zero) && c.isSpecified) {
                                    onDrag(c.x, c.y, pan.x, pan.y, zoom, fingers)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            )

            // Top bar.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                    .onGloballyPositioned { topPx = it.size.height.toFloat() + it.positionInWindow().y },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Filled.Cameraswitch, "Switch camera", enabled = !ui.demo) {
                    onSettings(settings.copy(frontCamera = !settings.frontCamera))
                }
                Spacer(Modifier.weight(1f))
                Segmented(listOf("♂ Male", "♀ Female"), if (settings.sex == Sex.MALE) 0 else 1) {
                    onSettings(settings.copy(sex = if (it == 0) Sex.MALE else Sex.FEMALE))
                }
                Spacer(Modifier.width(8.dp))
                Segmented(listOf("X-ray", "Solid"), if (settings.look == Look.XRAY) 0 else 1) {
                    onSettings(settings.copy(look = if (it == 0) Look.XRAY else Look.SOLID))
                }
            }

            // Status hint.
            val tracking = ui.stats?.tracking == true
            AnimatedVisibility(ui.loaded && !tracking && !ui.demo, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (settings.frontCamera) "Step back so your body is in view" else "Point the camera at a person", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("Full body works best, about 2–3 m away", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { onDemo(true) }) { Text("Explore without a person") }
                }
            }
            if (!ui.loaded) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading 1,700 anatomical structures…", color = Muted, fontSize = 13.sp)
                }
            }
            ui.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center).padding(24.dp)) }

            // Bottom: info card + layer chips.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)
                    .onGloballyPositioned { bottomPx = it.positionInWindow().y },
            ) {
                AnimatedVisibility(pickedTitle != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                    pickedTitle?.let { (title, sub, fact) -> InfoCard(title, sub, fact, onClosePick) }
                }
                if (ui.demo) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Explore · 1 finger turns · 2 fingers zoom & move · tap a part", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (!ui.cameraDenied) TextButton(onClick = { onDemo(false) }) { Text("Back to mirror") }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Layer.entries.forEach { l ->
                        val on = l in settings.layers
                        LayerChip(l.label, LayerColor.getValue(l), on) {
                            onSettings(settings.copy(layers = if (on) settings.layers - l else settings.layers + l))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Filled.Tune, "More settings") { showSheet = true }
                    Spacer(Modifier.weight(1f))
                    Shutter(onPhoto)
                    Spacer(Modifier.weight(1f))
                    val rec = recordingSince > 0
                    Box(contentAlignment = Alignment.Center) {
                        RoundButton(if (rec) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, if (rec) "Stop recording" else "Record video",
                            tint = Color(0xFFFF4D4D), onClick = onRecord)
                        if (rec) Box(Modifier.offset(y = (-38).dp)) { RecordingTimer(recordingSince) }
                    }
                }
            }

            if (settings.showTracking) ui.stats?.let { s ->
                Text(
                    "%.0f fps · pose %.0f ms · %s · %s".format(s.fps, s.inferenceMs, if (s.gpu) "GPU" else "CPU", if (s.tracking) "tracking" else "no person"),
                    color = Color(0xFF55FF8A), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 14.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp),
                )
            }
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF0D131C), scrimColor = Color(0x33000000)) {
                SettingsPanel(settings, onSettings, ui.demo, onDemo)
            }
        }
    }
}

@Composable
private fun SettingsPanel(s: Settings, on: (Settings) -> Unit, demo: Boolean, onDemo: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Section("Layers")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Preset("Skeleton") { on(s.copy(layers = setOf(Layer.SKELETON))) }
            Preset("Muscles") { on(s.copy(layers = setOf(Layer.MUSCLES))) }
            Preset("Circulation") { on(s.copy(layers = setOf(Layer.ARTERIES, Layer.VEINS, Layer.ORGANS))) }
            Preset("Organs") { on(s.copy(layers = setOf(Layer.ORGANS, Layer.SKELETON))) }
            Preset("Everything") { on(s.copy(layers = Layer.entries.toSet())) }
        }
        Toggle("Show labels", s.showLabels) { on(s.copy(showLabels = it)) }
        Text("Tap any structure to see its name and a fact.", color = Muted, fontSize = 12.sp)

        Section("Look")
        Slider2("Brightness", s.brightness, 0.3f..2f) { on(s.copy(brightness = it)) }

        Section("Mixed reality")
        Slider2("Real me showing through", s.realMe, 0f..1f) { on(s.copy(realMe = it)) }
        Slider2("Edge feather", s.feather, 0f..1f) { on(s.copy(feather = it)) }
        Toggle("Keep anatomy inside my outline", s.clipToBody) { on(s.copy(clipToBody = it)) }
        Text("Reveal", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Reveal.entries.forEach { r -> LayerChip(r.label, Accent, s.reveal == r) { on(s.copy(reveal = r)) } }
        }
        if (s.reveal != Reveal.BODY) Slider2("Lens size", s.lensSize, 0f..1f) { on(s.copy(lensSize = it)) }

        Section("Fit to my body")
        Slider2("Overall size", s.fit.size, 0.7f..1.4f, pct = true) { on(s.copy(fit = s.fit.copy(size = it))) }
        Slider2("Width", s.fit.width, 0.7f..1.4f, pct = true) { on(s.copy(fit = s.fit.copy(width = it))) }
        Slider2("Height", s.fit.height, 0.7f..1.4f, pct = true) { on(s.copy(fit = s.fit.copy(height = it))) }
        Slider2("Depth", s.fit.depth, 0.6f..1.6f, pct = true) { on(s.copy(fit = s.fit.copy(depth = it))) }
        Slider2("Left / right", s.fit.dx, -0.3f..0.3f) { on(s.copy(fit = s.fit.copy(dx = it))) }
        Slider2("Up / down", s.fit.dy, -0.3f..0.3f) { on(s.copy(fit = s.fit.copy(dy = it))) }
        TextButton(onClick = { on(s.copy(fit = Fit())) }) { Text("Reset fit") }
        Text("Tip: pick the Solid look, set \"Real me showing through\" to about half, then line the skull up with your face.",
            color = Muted, fontSize = 12.sp)

        Section("More")
        Toggle("Show tracking data", s.showTracking) { on(s.copy(showTracking = it)) }
        Toggle("Explore mode (no camera)", demo) { onDemo(it) }
        Spacer(Modifier.height(12.dp))
        Text(
            "The anatomy is one adult 3D atlas (Z-Anatomy, CC BY-SA 4.0, derived from BodyParts3D), stretched to your pose. " +
                "It shows where structures typically sit. It is not a scan of you and not a medical tool. " +
                "Female organs are simplified models added for this app. Everything runs on this phone; nothing is uploaded.",
            color = Muted, fontSize = 11.sp, lineHeight = 15.sp,
        )
    }
}

@Composable private fun Section(t: String) =
    Text(t.uppercase(), color = Accent, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))

@Composable
private fun Toggle(label: String, value: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { on(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = on)
    }
}

@Composable
private fun Slider2(label: String, value: Float, range: ClosedFloatingPointRange<Float>, pct: Boolean = false, on: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (pct) "${(value * 100).toInt()}%" else "%.2f".format(value), color = Muted, fontSize = 12.sp)
        }
        Slider(value = value, onValueChange = on, valueRange = range, modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun Preset(label: String, on: () -> Unit) {
    Text(label, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B2533)).clickable(onClick = on).padding(horizontal = 12.dp, vertical = 8.dp))
}

@Composable
private fun LayerChip(label: String, color: Color, on: Boolean, click: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) color.copy(alpha = 0.22f) else Panel)
            .border(1.dp, if (on) color.copy(alpha = 0.8f) else PanelBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = click).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) color else color.copy(alpha = 0.35f)))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) Color.White else Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, on: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp, PanelBorder, RoundedCornerShape(20.dp)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Text(o, color = if (i == selected) Color(0xFF04121C) else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(17.dp)).background(if (i == selected) Accent else Color.Transparent)
                    .clickable { on(i) }.padding(horizontal = 11.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, tint: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(Panel).border(1.dp, PanelBorder, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else tint.copy(alpha = 0.3f)) }
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(
        Modifier.size(64.dp).clip(CircleShape).border(3.dp, Color.White, CircleShape).padding(6.dp)
            .clip(CircleShape).background(Color.White.copy(alpha = 0.92f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.CameraAlt, "Take photo", tint = Color(0xFF0D131C)) }
}

@Composable
private fun RecordingTimer(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val s = ((now - since) / 1000).toInt()
    Text("%d:%02d".format(s / 60, s % 60), color = Color(0xFFFF4D4D), fontSize = 12.sp,
        modifier = Modifier.background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun InfoCard(title: String, sub: String, fact: String, close: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Panel).border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Accent, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(fact, color = Color(0xFFDDE6EE), fontSize = 14.sp, lineHeight = 19.sp)
        }
        Icon(Icons.Filled.Close, "Close", tint = Muted, modifier = Modifier.size(22.dp).clickable(onClick = close))
    }
}

@Suppress("unused")
@Composable
private fun dpToPx(v: Float) = with(LocalDensity.current) { v.dp.toPx() }
