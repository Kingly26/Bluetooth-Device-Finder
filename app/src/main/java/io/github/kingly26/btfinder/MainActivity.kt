package io.github.kingly26.btfinder

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Bg = Color(0xFF0E1216)
private val Card = Color(0xFF182027)
private val Grid = Color(0xFF2B3640)
private val Accent = Color(0xFF4FE3B0)
private val Cold = Color(0xFF4A90E2)
private val Hot = Color(0xFFFF5A3C)
private val Muted = Color(0xFF8A99A6)

// Su Android 12+ serve anche la posizione: senza, la scansione non restituisce i dispositivi.
private val perms: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

class MainActivity : ComponentActivity() {
    private lateinit var scanner: BtScanner
    private lateinit var sounder: Sounder
    private lateinit var compass: Compass
    private val ready = mutableStateOf(false)
    private val locationOn = mutableStateOf(true)

    private fun hasPerms() = perms
        .filter { it != Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BtScanner(applicationContext)
        sounder = Sounder(applicationContext)
        compass = Compass(applicationContext)
        scanner.demoHeading = { compass.heading.value }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC // i tasti volume regolano il bip
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    App(scanner, sounder, compass, ready, locationOn, ::hasPerms, onReady = { tryStart() })
                }
            }
        }
    }

    private fun tryStart() {
        ready.value = hasPerms() && scanner.isEnabled
        locationOn.value = getSystemService(LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
        if (ready.value) scanner.start()
    }

    override fun onResume() { super.onResume(); tryStart() }
    override fun onPause() { super.onPause(); scanner.stop(); sounder.stopRing() }
    override fun onDestroy() { super.onDestroy(); sounder.release() }
}

@Composable
private fun App(
    scanner: BtScanner, sounder: Sounder, compass: Compass, ready: State<Boolean>, locationOn: State<Boolean>,
    hasPerms: () -> Boolean, onReady: () -> Unit,
) {
    if (!ready.value) { Setup(scanner, hasPerms, onReady); return }

    val devices by scanner.devices.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }

    // tick ogni secondo per i "visto 5s fa" + pulizia periodica
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) { delay(1000); now = System.currentTimeMillis(); if (++n % 15 == 0) scanner.prune() }
    }

    var radarMode by remember { mutableStateOf(false) }
    // Mappe del segnale per direzione e numeri dei pallini: condivise fra radar generale e ricerca singola.
    val finders = remember { HashMap<String, DirectionFinder>() }
    val numbers = remember { HashMap<String, Int>() }

    val sel = selected
    if (sel == null) {
        DeviceList(devices.values.toList(), now, locationOn.value, radarMode, { radarMode = it }, compass, finders, numbers) { selected = it.address }
    } else {
        BackHandler { selected = null }
        val d = devices[sel]
        if (d == null) selected = null
        else Tracker(d, now, scanner, sounder, compass, finders.getOrPut(sel) { DirectionFinder() }) { selected = null }
    }
}

// ---------------------------------------------------------------- Setup
@Composable
private fun Setup(scanner: BtScanner, hasPerms: () -> Boolean, onReady: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerms(); onReady() }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onReady() }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Radar, null, tint = Accent, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (!granted) R.string.setup_perms else R.string.setup_bt), color = Muted)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (!granted) permLauncher.launch(perms)
            else if (!scanner.isEnabled) btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else onReady()
        }) { Text(stringResource(if (!granted) R.string.btn_grant else R.string.btn_enable_bt)) }
        if (!granted) TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }) { Text(stringResource(R.string.btn_open_settings)) }
    }
}

// ---------------------------------------------------------------- Lista
@Composable
private fun DeviceList(
    all: List<BtDevice>, now: Long, locationOn: Boolean, radarMode: Boolean, onMode: (Boolean) -> Unit,
    compass: Compass, finders: HashMap<String, DirectionFinder>, numbers: HashMap<String, Int>,
    onPick: (BtDevice) -> Unit,
) {
    val ctx = LocalContext.current
    val filtered = all
    fun fresh(d: BtDevice) = now - d.lastSeen < 15_000
    val live = filtered.filter { fresh(it) }.sortedByDescending { it.smooth ?: -200.0 }
    val known = filtered.filter { !fresh(it) && (it.bonded || it.connected) }.sortedByDescending { it.connected }
    val lost = filtered.filter { !fresh(it) && !it.bonded && !it.connected }.sortedByDescending { it.lastSeen }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
            PulseDot()
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.count_detected, all.count { fresh(it) }), color = Muted, fontSize = 13.sp)
        }
        if (!locationOn) Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Hot.copy(alpha = 0.18f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.location_off), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text(stringResource(R.string.btn_turn_on)) }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp),
        ) {
            for ((label, value) in listOf(stringResource(R.string.mode_list) to false, stringResource(R.string.mode_radar) to true)) {
                val on = radarMode == value
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Accent else Color.Transparent)
                        .clickable { onMode(value) }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (on) Bg else Muted, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (radarMode) { RadarAll(live, compass, finders, numbers, onPick); return@Column }
        val secLive = stringResource(R.string.sec_live)
        val secKnown = stringResource(R.string.sec_known)
        val secLost = stringResource(R.string.sec_lost)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section(secLive, live, now, onPick)
            section(secKnown, known, now, onPick)
            section(secLost, lost, now, onPick)
            if (filtered.isEmpty()) item {
                Text(stringResource(R.string.empty_listening), color = Muted, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Radar generale
private const val TILT = 0.62f // schiacciamento verticale del disco: dà l'effetto "inclinato"

private class Blip(val d: BtDevice, val num: Int, val rel: Float?, val angle: Float, val f: Float)

/** Angolo rispetto a "davanti" + distanza 0..1 -> punto sul disco inclinato. */
private fun discPos(angle: Float, f: Float, c: Offset, r: Float): Offset {
    val t = Math.toRadians(angle.toDouble())
    return Offset(c.x + f * r * sin(t).toFloat(), c.y - f * r * TILT * cos(t).toFloat())
}

@Composable
private fun dirWord(rel: Float?): String = stringResource(when {
    rel == null -> R.string.dir_unknown
    abs(rel) <= 30f -> R.string.dir_front
    abs(rel) >= 150f -> R.string.dir_behind
    rel > 0 -> R.string.dir_right
    else -> R.string.dir_left
})

/** Nome da mostrare: quello del dispositivo, altrimenti il produttore, altrimenti "sconosciuto". */
@Composable
private fun label(d: BtDevice): String =
    d.name?.takeIf { it.isNotBlank() }
        ?: d.vendor?.let { stringResource(R.string.unnamed_vendor, it) }
        ?: stringResource(R.string.unknown_device)

@Composable
private fun RadarAll(
    devs: List<BtDevice>, compass: Compass, finders: HashMap<String, DirectionFinder>,
    numbers: HashMap<String, Int>, onPick: (BtDevice) -> Unit,
) {
    val heading by compass.heading.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        compass.start()
        onPauseOrDispose { compass.stop() }
    }

    // Ogni nuova lettura di ogni dispositivo viene abbinata alla direzione attuale del telefono.
    val fed = remember { HashMap<String, Long>() }
    val h = heading
    val nowMs = System.currentTimeMillis()
    val blips = devs.filter { it.smooth != null }.map { d ->
        val num = numbers.getOrPut(d.address) { numbers.size + 1 }
        val finder = finders.getOrPut(d.address) { DirectionFinder() }
        val r = d.rssi
        if (h != null && r != null && fed[d.address] != d.lastSeen) {
            fed[d.address] = d.lastSeen
            finder.add(h, r.toDouble(), d.lastSeen)
        }
        val rel = if (h == null) null else finder.snapshot(nowMs).bearing?.let { wrap180(it - h) }
        // direzione ignota: angolo fisso qualsiasi, e il pallino resta vuoto
        val angle = rel ?: (((d.address.hashCode() * -1640531527) ushr 16) / 65535f * 360f)
        Blip(d, num, rel, angle, 0.14f + 0.80f * (1f - d.proximity.toFloat()))
    }.sortedBy { it.num }

    val current by rememberUpdatedState(blips)
    val tm = rememberTextMeasurer()
    val numStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(290.dp).pointerInput(Unit) {
                detectTapGestures { off ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = min(size.width / 2f, size.height / 2f / TILT) * 0.94f
                    val hit = current.minByOrNull { (discPos(it.angle, it.f, c, r) - off).getDistance() }
                    if (hit != null && (discPos(hit.angle, hit.f, c, r) - off).getDistance() < 40.dp.toPx()) onPick(hit.d)
                }
            },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = min(size.width / 2f, size.height / 2f / TILT) * 0.94f
            fun oval(f: Float, color: Color, stroke: Boolean) = drawOval(
                color, Offset(c.x - r * f, c.y - r * f * TILT), androidx.compose.ui.geometry.Size(2 * r * f, 2 * r * f * TILT),
                style = if (stroke) Stroke(2f) else androidx.compose.ui.graphics.drawscope.Fill,
            )
            oval(1f, Card, false)
            // settore "davanti a te"
            val front = Path().apply {
                moveTo(c.x, c.y)
                for (a in -25..25 step 5) { val p = discPos(a.toFloat(), 1f, c, r); lineTo(p.x, p.y) }
                close()
            }
            drawPath(front, Accent.copy(alpha = 0.12f))
            for (f in listOf(0.34f, 0.67f, 1f)) oval(f, Grid, true)
            drawLine(Grid, discPos(-90f, 1f, c, r), discPos(90f, 1f, c, r), 2f)
            drawLine(Grid, discPos(0f, 1f, c, r), discPos(180f, 1f, c, r), 2f)
            drawCircle(Color.White, 9f, c)

            val rad = 13.dp.toPx()
            // prima i lontani, così i vicini restano sopra
            for (b in current.sortedByDescending { it.f }) {
                val p = discPos(b.angle, b.f, c, r)
                val col = lerp(Cold, Hot, b.d.proximity.toFloat())
                if (b.rel != null) drawCircle(col, rad, p)
                else { drawCircle(Bg, rad, p); drawCircle(col, rad, p, style = Stroke(3f)) }
                val layout = tm.measure(b.num.toString(), numStyle)
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
        }
        Text(
            stringResource(if (h == null) R.string.radar_no_compass else R.string.radar_hint),
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        if (blips.isEmpty()) Text(stringResource(R.string.radar_empty), color = Muted, modifier = Modifier.padding(top = 24.dp))
        for (b in blips) {
            val col = lerp(Cold, Hot, b.d.proximity.toFloat())
            Row(
                Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                    .clickable { onPick(b.d) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                    Text(b.num.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label(b.d), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${dirWord(b.rel)} · ${fmtDist(b.d.distanceM)}", color = Muted, fontSize = 12.sp)
                }
                SignalBars(b.d.proximity)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun LazyListScope.section(title: String, list: List<BtDevice>, now: Long, onPick: (BtDevice) -> Unit) {
    if (list.isEmpty()) return
    item { Text(title.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
    items(list, key = { it.address }) { DeviceRow(it, now, onPick) }
}

private fun iconFor(k: Kind): ImageVector = when (k) {
    Kind.HEADPHONES -> Icons.Default.Headphones
    Kind.SPEAKER -> Icons.Default.Speaker
    Kind.COMPUTER -> Icons.Default.Computer
    Kind.PHONE -> Icons.Default.Smartphone
    Kind.WATCH -> Icons.Default.Watch
    Kind.TV -> Icons.Default.Tv
    Kind.OTHER -> Icons.Default.Bluetooth
}

@Composable
private fun ago(now: Long, t: Long): String {
    if (t == 0L) return stringResource(R.string.ago_never)
    val s = ((now - t) / 1000).toInt()
    return when {
        s < 3 -> stringResource(R.string.ago_now)
        s < 60 -> stringResource(R.string.ago_seconds, s)
        else -> stringResource(R.string.ago_minutes, s / 60)
    }
}

private fun fmtDist(m: Double?): String = when {
    m == null -> "—"
    m < 1 -> "< 1 m"
    m < 10 -> String.format(Locale.getDefault(), "~%.1f m", m)
    else -> "~${m.toInt()} m"
}

@Composable
private fun DeviceRow(d: BtDevice, now: Long, onPick: (BtDevice) -> Unit) {
    val fresh = now - d.lastSeen < 15_000
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).clickable { onPick(d) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Bg), contentAlignment = Alignment.Center) {
            Icon(iconFor(d.kind), null, tint = if (fresh || d.connected) Accent else Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label(d), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val state = if (d.connected) stringResource(R.string.state_connected) else if (d.bonded) stringResource(R.string.state_bonded) else null
            val seen = ago(now, d.lastSeen)
            val extra = buildList {
                if (state != null) add(state)
                add(seen)
                if (fresh) add(fmtDist(d.distanceM))
                if (fresh) d.smooth?.let { add("${it.toInt()} dBm") }
            }.joinToString(" · ")
            Text(extra, color = Muted, fontSize = 12.sp)
            if (d.name == null) Text(d.address, color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        SignalBars(if (fresh) d.proximity else 0.0)
    }
}

@Composable
private fun SignalBars(p: Double) {
    val n = when { p <= 0.0 -> 0; p < 0.25 -> 1; p < 0.5 -> 2; p < 0.75 -> 3; else -> 4 }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) Box(Modifier.width(5.dp).height((6 * i).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= n) Accent else Grid))
    }
}

@Composable
private fun PulseDot() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(700); on = !on } }
    val a by animateFloatAsState(if (on) 1f else 0.25f, tween(600), label = "pulse")
    Box(Modifier.size(9.dp).clip(CircleShape).background(Accent.copy(alpha = a)))
}

// ---------------------------------------------------------------- Ricerca
@Composable
private fun heatLabel(p: Double): String = stringResource(when {
    p < 0.30 -> R.string.heat_cold
    p < 0.55 -> R.string.heat_warm
    p < 0.80 -> R.string.heat_hot
    else -> R.string.heat_here
})

private const val FOV = 60f // il cono mostra ±60° rispetto a dove punta il telefono

@Composable
private fun Tracker(d: BtDevice, now: Long, scanner: BtScanner, sounder: Sounder, compass: Compass, finder: DirectionFinder, onBack: () -> Unit) {
    val fresh = now - d.lastSeen < 6_000
    val p = if (fresh) d.proximity else 0.0
    val animP by animateFloatAsState(p.toFloat(), tween(400), label = "p")
    val color by animateColorAsState(if (fresh) lerp(Cold, Hot, animP) else Muted, tween(400), label = "c")
    var beepOn by remember { mutableStateOf(true) }
    var ringing by remember { mutableStateOf(false) }
    var ringError by remember { mutableStateOf(false) }

    val heading by compass.heading.collectAsStateWithLifecycle()

    DisposableEffect(d.address) {
        // In ricerca BLE la discovery classica rallenta i campioni: la spengo se non serve.
        scanner.setClassic(!(d.viaBle && !d.viaClassic))
        // Gli associati spesso non si pubblicizzano: provo a leggerne il segnale con un collegamento diretto.
        if (d.bonded) scanner.track(d.address)
        onDispose { scanner.untrack(); scanner.setClassic(true); sounder.stopRing() }
    }
    LifecycleResumeEffect(d.address) {
        compass.start()
        onPauseOrDispose { compass.stop() }
    }

    // Ogni nuova lettura del segnale viene abbinata alla direzione in cui punta il telefono in quel momento.
    LaunchedEffect(d.lastSeen) {
        val h = compass.heading.value
        val r = d.rssi
        if (h != null && r != null && d.lastSeen != 0L) finder.add(h, r.toDouble(), d.lastSeen)
    }
    val reading = finder.snapshot(now)
    val rel = reading.bearing?.let { b -> heading?.let { wrap180(b - it) } }

    // Bip tipo contatore Geiger: più ti avvicini, più è rapido.
    val latest by rememberUpdatedState(d)
    LaunchedEffect(d.address, beepOn) {
        while (beepOn) {
            val dev = latest
            val recent = System.currentTimeMillis() - dev.lastSeen < 6_000
            val prox = if (recent) dev.proximity else 0.0
            if (recent) {
                sounder.beep(prox)
                if (prox > 0.85) sounder.buzz(40)
            }
            delay((1400 - 1290 * prox).toLong().coerceAtLeast(110))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.padding(top = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Icon(iconFor(d.kind), null, tint = Accent)
            Spacer(Modifier.width(8.dp))
            Text(label(d), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (fresh) heatLabel(p) else stringResource(R.string.listening), color = color, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            if (fresh) Text(fmtDist(d.distanceM), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        ConeRadar(reading, finder.binWidth, heading, rel, animP, fresh, color)

        // Direzione
        val dirText = when {
            !fresh -> if (d.lastSeen == 0L) stringResource(R.string.track_not_seen) else stringResource(R.string.track_lost, ago(now, d.lastSeen))
            heading == null -> stringResource(R.string.track_no_compass)
            rel == null -> stringResource(R.string.track_spin, (reading.coverage * 100).toInt())
            abs(rel) <= 15f -> stringResource(R.string.track_ahead)
            abs(rel) >= 150f -> stringResource(R.string.track_behind)
            rel > 0 -> stringResource(R.string.track_right, rel.toInt())
            else -> stringResource(R.string.track_left, (-rel).toInt())
        }
        Text(dirText, color = if (rel != null && fresh && abs(rel) <= 15f) Accent else Color.White,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))

        val tr = d.trend
        val trendText = when {
            !fresh -> ""
            tr > 2.0 -> stringResource(R.string.trend_closer)
            tr < -2.0 -> stringResource(R.string.trend_away)
            else -> stringResource(R.string.trend_stable)
        }
        Text(trendText, color = if (tr > 2) Accent else if (tr < -2) Hot else Muted, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))

        Spacer(Modifier.height(12.dp))
        HistoryGraph(d.history, now, color)

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.stat_filtered), d.smooth?.let { "${it.toInt()} dBm" } ?: "—")
            Stat(stringResource(R.string.stat_raw), d.rssi?.let { "$it dBm" } ?: "—")
            Stat(stringResource(R.string.stat_via),listOfNotNull("BLE".takeIf { d.viaBle }, "Classic".takeIf { d.viaClassic }).joinToString("+").ifEmpty { "—" })
        }

        if (d.audioConnected) {
            Button(
                onClick = {
                    if (ringing) { sounder.stopRing(); ringing = false }
                    else { ringing = sounder.ringHeadphones(d.address); ringError = !ringing }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (ringing) Hot else Accent),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(if (ringing) Icons.Default.Stop else Icons.Default.NotificationsActive, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (ringing) R.string.ring_stop else R.string.ring_start), fontSize = 17.sp)
            }
            Text(stringResource(R.string.ring_warning), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (ringError) Text(stringResource(R.string.ring_error), color = Hot, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card)
                .clickable { beepOn = !beepOn }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (beepOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, null, tint = if (beepOn) Accent else Muted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.beep_title), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.beep_desc), color = Muted, fontSize = 12.sp)
            }
            Switch(checked = beepOn, onCheckedChange = { beepOn = it })
        }
        Spacer(Modifier.height(10.dp))

        Tips(d, fresh)
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Radar "inclinato": un ventaglio in prospettiva che parte da te (in basso) e guarda in avanti.
 * Ogni spicchio è una direzione già misurata: più è lungo e caldo, più lì il segnale è forte.
 */
@Composable
private fun ConeRadar(
    reading: DirectionFinder.Reading, binWidth: Float, heading: Float?, rel: Float?,
    prox: Float, fresh: Boolean, liveColor: Color,
) {
    Canvas(Modifier.fillMaxWidth().height(330.dp).padding(top = 8.dp)) {
        val apex = Offset(size.width / 2f, size.height * 0.97f)
        val hMax = size.height * 0.92f
        val wMax = size.width / 2f / 1.1f
        val k = 0.6f // forza della prospettiva

        // angolo rispetto a "dritto davanti" + distanza 0..1 -> punto sullo schermo
        fun proj(deg: Float, f: Float): Offset {
            val t = Math.toRadians(deg.toDouble())
            val x = f * sin(t).toFloat()
            val z = f * cos(t).toFloat()
            val s = (1f + k) / (1f + k * z)
            return Offset(apex.x + x * wMax * s, apex.y - hMax * z * s)
        }

        fun wedge(a0: Float, a1: Float, f0: Float, f1: Float): Path {
            val path = Path()
            val steps = max(1, ((a1 - a0) / 4f).toInt())
            for (i in 0..steps) {
                val o = proj(a0 + (a1 - a0) * i / steps, f1)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            for (i in steps downTo 0) {
                val o = proj(a0 + (a1 - a0) * i / steps, f0)
                path.lineTo(o.x, o.y)
            }
            path.close()
            return path
        }

        // fondo del cono, anelli di distanza e raggi
        drawPath(wedge(-FOV, FOV, 0.03f, 1f), Card)
        for (f in listOf(0.34f, 0.67f, 1f)) drawPath(wedge(-FOV, FOV, f, f), Grid, style = Stroke(2f))
        for (a in listOf(-60f, -30f, 0f, 30f, 60f)) drawLine(Grid, proj(a, 0.03f), proj(a, 1f), 2f)

        if (heading == null) {
            // senza bussola: un solo fascio centrale che si allunga col segnale
            if (fresh) drawPath(wedge(-12f, 12f, 0.03f, 0.15f + 0.85f * prox), liveColor.copy(alpha = 0.85f))
        } else {
            val span = reading.hi - reading.lo
            reading.sectors.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val c = wrap180(i * binWidth - heading)
                val a0 = max(c - binWidth / 2f, -FOV)
                val a1 = min(c + binWidth / 2f, FOV)
                if (a0 >= a1) return@forEachIndexed
                val relStrength = if (span < 3.0) 0.5f else ((v - reading.lo) / span).toFloat()
                val absStrength = ((v + 100.0) / 65.0).coerceIn(0.0, 1.0).toFloat()
                val col = lerp(Cold, Hot, relStrength * 0.6f + absStrength * 0.4f)
                drawPath(wedge(a0, a1, 0.03f, 0.2f + 0.8f * relStrength), col.copy(alpha = 0.35f + 0.5f * relStrength))
            }
            // lettura in tempo reale, dritta davanti
            if (fresh) drawPath(wedge(-3f, 3f, 0.03f, 0.15f + 0.85f * prox), Color.White.copy(alpha = 0.55f))

            // bersaglio: direzione stimata, tanto più vicino a te quanto più forte è il segnale
            if (rel != null && fresh) {
                if (abs(rel) <= FOV) {
                    val pos = proj(rel, 0.12f + 0.83f * (1f - prox))
                    drawCircle(Accent.copy(alpha = 0.25f), 34f, pos)
                    drawCircle(Accent, 16f, pos)
                    drawCircle(Color.White, 6f, pos)
                } else {
                    // fuori dal cono: freccia sul bordo dal lato in cui girare
                    val side = if (rel > 0) 1f else -1f
                    val tip = proj(side * FOV, 0.75f)
                    val arrow = Path().apply {
                        moveTo(tip.x + side * 34f, tip.y)
                        lineTo(tip.x - side * 6f, tip.y - 26f)
                        lineTo(tip.x - side * 6f, tip.y + 26f)
                        close()
                    }
                    drawPath(arrow, Accent)
                }
            }
        }
        drawCircle(Color.White, 10f, apex)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun HistoryGraph(history: List<Pair<Long, Double>>, now: Long, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)).background(Card)) {
        val window = 30_000f
        fun y(v: Double) = size.height * (1f - ((v + 100) / 65.0).coerceIn(0.0, 1.0).toFloat())
        for (lvl in listOf(-90.0, -70.0, -50.0)) drawLine(Grid, Offset(0f, y(lvl)), Offset(size.width, y(lvl)), 1f)
        if (history.size < 2) return@Canvas
        val path = Path()
        history.forEachIndexed { i, (t, v) ->
            val x = size.width * (1f - (now - t) / window)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun Tips(d: BtDevice, fresh: Boolean) {
    val tips = buildList {
        add(R.string.tip_spin)
        add(R.string.tip_walk)
        add(R.string.tip_estimate)
        if (d.bonded && !fresh) add(R.string.tip_bonded)
        if (d.audioConnected) add(R.string.tip_audio)
        if (!fresh && d.kind == Kind.HEADPHONES) add(R.string.tip_headphones)
        if (!fresh && d.kind == Kind.COMPUTER) add(R.string.tip_pc)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tips_title), color = Color.White, fontWeight = FontWeight.SemiBold)
        tips.forEach { Text("• ${stringResource(it)}", color = Muted, fontSize = 13.sp) }
    }
}
