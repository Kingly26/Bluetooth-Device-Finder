package it.klab.ritrova

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF0E1216)
private val Card = Color(0xFF182027)
private val Accent = Color(0xFF4FE3B0)
private val Cold = Color(0xFF4A90E2)
private val Hot = Color(0xFFFF5A3C)
private val Muted = Color(0xFF8A99A6)

private val perms: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

class MainActivity : ComponentActivity() {
    private lateinit var scanner: BtScanner
    private lateinit var sounder: Sounder
    private val ready = mutableStateOf(false)

    private fun hasPerms() = perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = BtScanner(applicationContext)
        sounder = Sounder(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    App(scanner, sounder, ready, ::hasPerms, onReady = { tryStart() })
                }
            }
        }
    }

    private fun tryStart() {
        ready.value = hasPerms() && scanner.isEnabled
        if (ready.value) scanner.start()
    }

    override fun onResume() { super.onResume(); tryStart() }
    override fun onPause() { super.onPause(); scanner.stop(); sounder.stopRing() }
    override fun onDestroy() { super.onDestroy(); sounder.release() }
}

@Composable
private fun App(scanner: BtScanner, sounder: Sounder, ready: State<Boolean>, hasPerms: () -> Boolean, onReady: () -> Unit) {
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
        DeviceList(devices.values.toList(), now) { selected = it.address }
    } else {
        BackHandler { selected = null }
        val d = devices[sel]
        if (d == null) selected = null
        else Tracker(d, now, scanner, sounder) { selected = null }
    }
}

// ---------------------------------------------------------------- Setup
@Composable
private fun Setup(scanner: BtScanner, hasPerms: () -> Boolean, onReady: () -> Unit) {
    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerms(); onReady() }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onReady() }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Radar, null, tint = Accent, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Ritrova", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!granted) "Serve il permesso \"Dispositivi nelle vicinanze\" per ascoltare il segnale Bluetooth degli oggetti."
            else "Accendi il Bluetooth per iniziare la ricerca.",
            color = Muted,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (!granted) permLauncher.launch(perms)
            else if (!scanner.isEnabled) btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else onReady()
        }) { Text(if (!granted) "Concedi permesso" else "Attiva Bluetooth") }
    }
}

// ---------------------------------------------------------------- Lista
private enum class Filter(val label: String) { ALL("Tutti"), HEAD("Cuffie"), PC("PC"), PHONE("Telefoni") }

@Composable
private fun DeviceList(all: List<BtDevice>, now: Long, onPick: (BtDevice) -> Unit) {
    var filter by remember { mutableStateOf(Filter.ALL) }
    var showUnnamed by remember { mutableStateOf(true) }

    val filtered = all.filter {
        (when (filter) {
            Filter.ALL -> true
            Filter.HEAD -> it.kind == Kind.HEADPHONES
            Filter.PC -> it.kind == Kind.COMPUTER
            Filter.PHONE -> it.kind == Kind.PHONE
        }) && (showUnnamed || it.name != null || it.bonded)
    }
    val connected = filtered.filter { it.connected }.sortedByDescending { it.smooth ?: -200.0 }
    val bonded = filtered.filter { it.bonded && !it.connected }.sortedByDescending { if (now - it.lastSeen < 15_000) it.smooth ?: -200.0 else -300.0 }
    val nearby = filtered.filter { !it.bonded && !it.connected }.sortedByDescending { it.smooth ?: -200.0 }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ritrova", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            PulseDot()
            Spacer(Modifier.width(6.dp))
            Text("${all.count { now - it.lastSeen < 15_000 }} in zona", color = Muted, fontSize = 13.sp)
        }
        RadarOverview(filtered.filter { now - it.lastSeen < 15_000 && it.smooth != null }, onPick)
        Text("Ogni punto è un dispositivo: più è vicino al centro, più è vicino a te. Tocca un punto o la lista per inseguirlo.",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mostra anche senza nome", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = showUnnamed, onCheckedChange = { showUnnamed = it })
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section("Connessi ora", connected, now, onPick)
            section("Associati", bonded, now, onPick)
            section("Nelle vicinanze", nearby, now, onPick)
            if (filtered.isEmpty()) item {
                Text("Sto ascoltando… Accendi o apri l'oggetto che cerchi (le cuffie spesso trasmettono solo fuori dalla custodia).",
                    color = Muted, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

/** Posizione del punto: angolo fisso (da indirizzo, la direzione reale non è misurabile), raggio dal segnale. */
private fun blipPos(d: BtDevice, c: Offset, r: Float): Offset {
    val a = (d.address.hashCode() and 0xFFFF) / 65535.0 * 2 * Math.PI
    val rad = r * (0.10f + 0.85f * (1f - d.proximity.toFloat()))
    return Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat())
}

@Composable
private fun RadarOverview(devs: List<BtDevice>, onPick: (BtDevice) -> Unit) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "sw",
    )
    val current by rememberUpdatedState(devs)
    Canvas(
        Modifier.fillMaxWidth().height(300.dp).padding(horizontal = 12.dp).pointerInput(Unit) {
            detectTapGestures { off ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = min(size.width, size.height) / 2f
                val hit = current.minByOrNull { (blipPos(it, c, r) - off).getDistance() }
                if (hit != null && (blipPos(hit, c, r) - off).getDistance() < 56.dp.toPx()) onPick(hit)
            }
        },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f
        for (i in 1..4) drawCircle(Color(0xFF26313B), r * i / 4, c, style = Stroke(2f))
        val rad = Math.toRadians(sweep.toDouble())
        drawLine(Accent.copy(alpha = 0.6f), c, Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat()), 4f)
        for (d in devs) {
            val col = lerp(Cold, Hot, d.proximity.toFloat())
            val p = blipPos(d, c, r)
            drawCircle(col.copy(alpha = 0.25f), 20f, p)
            drawCircle(col, 10f, p)
        }
        drawCircle(Color.White, 8f, c)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<BtDevice>, now: Long, onPick: (BtDevice) -> Unit) {
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
                if (d.connected) add("connesso")
                add(ago(now, d.lastSeen))
                if (fresh) add(fmtDist(d.distanceM))
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
        for (i in 1..4) Box(Modifier.width(5.dp).height((6 * i).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= n) Accent else Color(0xFF2B3640)))
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

@Composable
private fun Tracker(d: BtDevice, now: Long, scanner: BtScanner, sounder: Sounder, onBack: () -> Unit) {
    val fresh = now - d.lastSeen < 6_000
    val p = if (fresh) d.proximity else 0.0
    val animP by animateFloatAsState(p.toFloat(), tween(400), label = "p")
    val color by animateColorAsState(if (fresh) lerp(Cold, Hot, animP) else Muted, tween(400), label = "c")
    var beepOn by remember { mutableStateOf(true) }
    var ringing by remember { mutableStateOf(false) }
    var ringError by remember { mutableStateOf(false) }

    // In ricerca BLE, la discovery classica rallenta i campioni: la spengo se non serve.
    DisposableEffect(d.address) {
        scanner.setClassic(!(d.viaBle && !d.viaClassic))
        onDispose { scanner.setClassic(true); sounder.stopRing() }
    }

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
            IconButton(onClick = { beepOn = !beepOn }) {
                Icon(if (beepOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Bip", tint = Color.White)
            }
        }

        // Radar
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                for (i in 1..4) drawCircle(Color(0xFF26313B), r * i / 4, style = Stroke(2f))
                drawCircle(color.copy(alpha = 0.18f), r * (0.25f + 0.75f * animP))
                drawCircle(color, r * (0.12f + 0.55f * animP))
                // arco "segnale"
                drawArc(color, -90f, 360f * animP, false, style = Stroke(10f, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (fresh) heatLabel(p) else "In ascolto…", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                if (fresh) Text(fmtDist(d.distanceM), color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
            }
        }

        // Tendenza
        val tr = d.trend
        val trendText = when {
            !fresh -> if (d.lastSeen == 0L) "Non ancora rilevato" else "Segnale perso ${ago(now, d.lastSeen)}"
            tr > 2.0 -> "▲ Ti stai avvicinando"
            tr < -2.0 -> "▼ Ti stai allontanando"
            else -> "● Stabile — prova a cambiare stanza o direzione"
        }
        Text(trendText, color = if (tr > 2 && fresh) Accent else if (tr < -2 && fresh) Hot else Muted,
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(14.dp))
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

        Tips(d, fresh)
        Spacer(Modifier.height(32.dp))
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
        for (lvl in listOf(-90.0, -70.0, -50.0)) drawLine(Color(0xFF26313B), Offset(0f, y(lvl)), Offset(size.width, y(lvl)), 1f)
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
        add("Gira lentamente su te stesso: il tuo corpo blocca il segnale, quindi la direzione in cui è più debole è quella alle tue spalle.")
        add("Muoviti di 2–3 passi e aspetta un paio di secondi: il valore filtrato reagisce con un po' di ritardo ma è molto più stabile.")
        if (d.audioConnected) add("Le cuffie connesse di solito non trasmettono pubblicità BLE: usa \"Fai suonare\" e cerca a orecchio.")
        if (!fresh && d.kind == Kind.HEADPHONES) add("Molte cuffie trasmettono solo quando sono fuori dalla custodia o con la custodia aperta. Se sono scariche non c'è segnale.")
        if (!fresh && d.kind == Kind.COMPUTER) add("Il PC deve avere il Bluetooth acceso; su Windows apri Impostazioni › Bluetooth per renderlo visibile, su Linux usa `bluetoothctl discoverable on`.")
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Consigli", color = Color.White, fontWeight = FontWeight.SemiBold)
        tips.forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
    }
}
