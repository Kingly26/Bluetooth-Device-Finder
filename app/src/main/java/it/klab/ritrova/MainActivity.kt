package it.klab.ritrova

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
import androidx.compose.ui.platform.LocalContext
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

    val sel = selected
    if (sel == null) {
        DeviceList(devices.values.toList(), now, locationOn.value) { selected = it.address }
    } else {
        BackHandler { selected = null }
        val d = devices[sel]
        if (d == null) selected = null
        else Tracker(d, now, scanner, sounder, compass) { selected = null }
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
        Text("Bluetooth Device Finder", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!granted) "Servono due permessi: \"Dispositivi nelle vicinanze\" e \"Posizione\" (scegli Precisa). " +
                "Android li richiede entrambi per vedere i dispositivi Bluetooth intorno a te. Nessun dato esce dal telefono."
            else "Accendi il Bluetooth per iniziare la ricerca.",
            color = Muted,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (!granted) permLauncher.launch(perms)
            else if (!scanner.isEnabled) btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else onReady()
        }) { Text(if (!granted) "Concedi permessi" else "Attiva Bluetooth") }
        if (!granted) TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }) { Text("Se il pulsante non fa nulla: apri le impostazioni dell'app") }
    }
}

// ---------------------------------------------------------------- Lista
@Composable
private fun DeviceList(all: List<BtDevice>, now: Long, locationOn: Boolean, onPick: (BtDevice) -> Unit) {
    val ctx = LocalContext.current
    val filtered = all
    fun fresh(d: BtDevice) = now - d.lastSeen < 15_000
    val live = filtered.filter { fresh(it) }.sortedByDescending { it.smooth ?: -200.0 }
    val known = filtered.filter { !fresh(it) && (it.bonded || it.connected) }.sortedByDescending { it.connected }
    val lost = filtered.filter { !fresh(it) && !it.bonded && !it.connected }.sortedByDescending { it.lastSeen }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BT Finder", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            PulseDot()
            Spacer(Modifier.width(6.dp))
            Text("${all.count { fresh(it) }} rilevati", color = Muted, fontSize = 13.sp)
        }
        if (!locationOn) Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Hot.copy(alpha = 0.18f)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("La posizione del telefono è spenta: Android non mostra i dispositivi Bluetooth finché non la accendi.",
                color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Accendi") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section("Rilevati ora — dal più vicino", live, now, onPick)
            section("Associati, non rilevati al momento", known, now, onPick)
            section("Visti poco fa", lost, now, onPick)
            if (filtered.isEmpty()) item {
                Text("Sto ascoltando… Accendi o apri l'oggetto che cerchi (le cuffie spesso trasmettono solo fuori dalla custodia).",
                    color = Muted, modifier = Modifier.padding(top = 32.dp))
            }
        }
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

private fun ago(now: Long, t: Long): String {
    if (t == 0L) return "non rilevato"
    val s = (now - t) / 1000
    return when { s < 3 -> "adesso"; s < 60 -> "${s}s fa"; else -> "${s / 60} min fa" }
}

private fun fmtDist(m: Double?): String = when {
    m == null -> "—"
    m < 1 -> "< 1 m"
    m < 10 -> String.format(Locale.ITALY, "~%.1f m", m)
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
            Text(d.label, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val extra = buildList {
                if (d.connected) add("connesso") else if (d.bonded) add("associato")
                add(ago(now, d.lastSeen))
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
private fun heatLabel(p: Double): String = when {
    p < 0.30 -> "Acqua"
    p < 0.55 -> "Fuochino"
    p < 0.80 -> "Fuoco"
    else -> "Ci sei sopra!"
}

private const val FOV = 60f // il cono mostra ±60° rispetto a dove punta il telefono

@Composable
private fun Tracker(d: BtDevice, now: Long, scanner: BtScanner, sounder: Sounder, compass: Compass, onBack: () -> Unit) {
    val fresh = now - d.lastSeen < 6_000
    val p = if (fresh) d.proximity else 0.0
    val animP by animateFloatAsState(p.toFloat(), tween(400), label = "p")
    val color by animateColorAsState(if (fresh) lerp(Cold, Hot, animP) else Muted, tween(400), label = "c")
    var beepOn by remember { mutableStateOf(true) }
    var ringing by remember { mutableStateOf(false) }
    var ringError by remember { mutableStateOf(false) }

    val heading by compass.heading.collectAsStateWithLifecycle()
    val finder = remember(d.address) { DirectionFinder() }

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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White) }
            Icon(iconFor(d.kind), null, tint = Accent)
            Spacer(Modifier.width(8.dp))
            Text(d.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(if (fresh) heatLabel(p) else "In ascolto…", color = color, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            if (fresh) Text(fmtDist(d.distanceM), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        ConeRadar(reading, finder.binWidth, heading, rel, animP, fresh, color)

        // Direzione
        val dirText = when {
            !fresh -> if (d.lastSeen == 0L) "Non ancora rilevato: avvicinati o accendi l'oggetto" else "Segnale perso ${ago(now, d.lastSeen)}"
            heading == null -> "Bussola non disponibile: segui caldo/freddo"
            rel == null -> "Fai un giro lento su te stesso per trovare la direzione (${(reading.coverage * 100).toInt()}%)"
            abs(rel) <= 15f -> "⬆ Dritto davanti a te"
            abs(rel) >= 150f -> "⬇ È alle tue spalle: girati"
            rel > 0 -> "➡ Gira a destra di ${rel.toInt()}°"
            else -> "⬅ Gira a sinistra di ${(-rel).toInt()}°"
        }
        Text(dirText, color = if (rel != null && fresh && abs(rel) <= 15f) Accent else Color.White,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))

        val tr = d.trend
        val trendText = when {
            !fresh -> ""
            tr > 2.0 -> "▲ Ti stai avvicinando"
            tr < -2.0 -> "▼ Ti stai allontanando"
            else -> "● Segnale stabile"
        }
        Text(trendText, color = if (tr > 2) Accent else if (tr < -2) Hot else Muted, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))

        Spacer(Modifier.height(12.dp))
        HistoryGraph(d.history, now, color)

        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("Filtrato", d.smooth?.let { "${it.toInt()} dBm" } ?: "—")
            Stat("Grezzo", d.rssi?.let { "$it dBm" } ?: "—")
            Stat("Via", listOfNotNull("BLE".takeIf { d.viaBle }, "Classic".takeIf { d.viaClassic }).joinToString("+").ifEmpty { "—" })
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
                Text(if (ringing) "Ferma suono" else "Fai suonare", fontSize = 17.sp)
            }
            Text("⚠️ Suona a volume massimo: non farlo con le cuffie indossate.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (ringError) Text("Nessuna uscita audio Bluetooth attiva.", color = Hot, fontSize = 13.sp)
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
                Text("Bip di avvicinamento", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Più rapido quando ti avvicini. Volume: tasti del telefono.", color = Muted, fontSize = 12.sp)
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
        add("Tieni il telefono piatto davanti al petto e fai un giro lento su te stesso (10–15 secondi): il tuo corpo scherma il segnale da dietro, così il cono capisce da che parte è più forte.")
        add("Poi cammina nella direzione indicata per qualche metro e rifai il giro: a ogni giro la stima migliora.")
        add("La direzione è una stima: muri, mobili e riflessi la possono spostare. La distanza conta più dell'angolo.")
        if (d.bonded && !fresh) add("È un dispositivo associato che non si annuncia: sto provando un collegamento diretto per leggerne il segnale. Funziona solo se è acceso e supporta Bluetooth LE.")
        if (d.audioConnected) add("Le cuffie connesse di solito non trasmettono pubblicità BLE: usa \"Fai suonare\" e cerca a orecchio.")
        if (!fresh && d.kind == Kind.HEADPHONES) add("Molte cuffie trasmettono solo quando sono fuori dalla custodia o con la custodia aperta. Se sono scariche non c'è segnale.")
        if (!fresh && d.kind == Kind.COMPUTER) add("Il PC deve avere il Bluetooth acceso; su Windows apri Impostazioni › Bluetooth per renderlo visibile.")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Consigli", color = Color.White, fontWeight = FontWeight.SemiBold)
        tips.forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
    }
}
