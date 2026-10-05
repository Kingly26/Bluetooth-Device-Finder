package io.github.kingly26.btfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

enum class Kind { HEADPHONES, SPEAKER, COMPUTER, PHONE, WATCH, TV, OTHER }

/** Filtro di Kalman 1D: toglie il "rumore" dell'RSSI, che salta facilmente di ±8 dBm. */
class Kalman(private val q: Double = 0.35, private val r: Double = 9.0) {
    private var x = Double.NaN
    private var p = 1.0
    fun update(z: Double): Double {
        if (x.isNaN()) { x = z; return x }
        p += q
        val k = p / (p + r)
        x += k * (z - x)
        p *= (1 - k)
        return x
    }
}

data class BtDevice(
    val address: String,
    val name: String?,
    val kind: Kind,
    val rssi: Int?,              // ultimo valore grezzo
    val smooth: Double?,         // valore filtrato
    val txPower: Int?,           // potenza dichiarata dal dispositivo, se c'è
    val lastSeen: Long,          // 0 = mai visto in questa sessione
    val bonded: Boolean,
    val connected: Boolean,
    val audioConnected: Boolean, // connesso come cuffie/vivavoce: l'unico caso in cui si può far suonare
    val vendor: String?,         // produttore dedotto dai dati BLE (utile per i dispositivi senza nome)
    val viaBle: Boolean,
    val viaClassic: Boolean,
    val history: List<Pair<Long, Double>> = emptyList(),
) {
    /** 0 = lontanissimo/assente, 1 = praticamente attaccato. */
    val proximity: Double
        get() = smooth?.let { ((it + 100.0) / 65.0).coerceIn(0.0, 1.0) } ?: 0.0

    /** Stima in metri (modello log-distance). È indicativa: muri e corpi la falsano. */
    val distanceM: Double?
        get() {
            val s = smooth ?: return null
            val at1m = txPower?.let { it - 41 } ?: -60
            return 10.0.pow((at1m - s) / (10 * 2.5))
        }

    /** >0 ti stai avvicinando, <0 allontanando (dBm di differenza fra ultimi 3s e i 3s prima). */
    val trend: Double
        get() {
            val now = System.currentTimeMillis()
            val a = history.filter { now - it.first <= 3000 }.map { it.second }
            val b = history.filter { now - it.first in 3001..6000 }.map { it.second }
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.average() - b.average()
        }
}

@SuppressLint("MissingPermission")
class BtScanner(private val ctx: Context) {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val filters = HashMap<String, Kalman>()
    private val _devices = MutableStateFlow<Map<String, BtDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BtDevice>> = _devices.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Se false niente discovery classica: più campioni BLE al secondo (utile in modalità ricerca). */
    @Volatile var classicEnabled = true

    private var connectedAddrs = emptySet<String>()
    private var audioAddrs = emptySet<String>()
    private val proxies = HashMap<Int, BluetoothProfile>()

    val isEnabled: Boolean get() = demo || adapter?.isEnabled == true

    // ---------- Demo (solo emulatore) ----------
    // L'emulatore non ha Bluetooth reale: genero dispositivi finti per poter provare l'interfaccia.
    val demo: Boolean = Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")
    /** Direzione attuale del telefono, per simulare un segnale più forte quando si guarda l'oggetto. */
    var demoHeading: () -> Float? = { null }

    private class Fake(val addr: String, val name: String?, val vendor: String?, val kind: Kind,
                       val bearing: Double, val base: Double, val bonded: Boolean, val audio: Boolean)
    private val fakes = listOf(
        Fake("AA:BB:CC:00:00:01", "Pixel Buds Pro", "Google", Kind.HEADPHONES, 40.0, -58.0, true, true),
        Fake("AA:BB:CC:00:00:02", "DESKTOP-K26", null, Kind.COMPUTER, 300.0, -52.0, true, false),
        Fake("AA:BB:CC:00:00:03", "WH-1000XM4", "Sony", Kind.HEADPHONES, 200.0, -72.0, false, false),
        Fake("AA:BB:CC:00:00:04", "Galaxy Watch6", "Samsung", Kind.WATCH, 90.0, -68.0, false, false),
        Fake("AA:BB:CC:00:00:05", null, "Apple", Kind.OTHER, 120.0, -80.0, false, false),
        Fake("AA:BB:CC:00:00:06", "LG TV", null, Kind.TV, 250.0, -86.0, false, false),
        Fake("AA:BB:CC:00:00:07", null, null, Kind.OTHER, 10.0, -92.0, false, false),
    )
    private val demoLoop = object : Runnable {
        override fun run() {
            val h = demoHeading()
            val now = System.currentTimeMillis()
            val map = _devices.value.toMutableMap()
            for (f in fakes) {
                val dir = if (h == null) 0.0 else 7.0 * kotlin.math.cos(Math.toRadians(h - f.bearing))
                val rssi = (f.base + dir + (Math.random() - 0.5) * 6).toInt()
                val s = filters.getOrPut(f.addr) { Kalman() }.update(rssi.toDouble())
                val hist = ((map[f.addr]?.history ?: emptyList()) + (now to s)).filter { now - it.first <= 30_000 }
                map[f.addr] = BtDevice(f.addr, f.name, f.kind, rssi, s, null, now, f.bonded, f.audio, f.audio,
                    f.vendor, viaBle = true, viaClassic = false, history = hist)
            }
            _devices.value = map
            if (_running.value) main.postDelayed(this, 300)
        }
    }

    // ---------- BLE ----------
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val tx = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                ?: result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
            val name = result.scanRecord?.deviceName ?: safeName(result.device)
            val md = result.scanRecord?.manufacturerSpecificData
            val vendor = if (md != null && md.size() > 0) vendorName(md.keyAt(0)) else null
            onSample(result.device, name, result.rssi, tx, ble = true, vendor = vendor)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            // 2 = già avviato / troppe ripartenze: riprova fra poco
            main.postDelayed({ if (_running.value) startBle() }, 5000)
        }
    }

    // ---------- Classic ----------
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    dev ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: safeName(dev)
                    if (rssi != Short.MIN_VALUE.toInt()) onSample(dev, name, rssi, null, ble = false)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_running.value && classicEnabled) main.postDelayed({ startClassic() }, 500)
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (adapter?.isEnabled == true && _running.value) { startBle(); startClassic() }
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; refreshConnected() }
        override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
    }

    // ---------- Inseguimento diretto (GATT) ----------
    // I dispositivi associati spesso non si pubblicizzano: apro un collegamento BLE e leggo l'RSSI da lì.
    private var trackAddr: String? = null
    private var gatt: BluetoothGatt? = null
    private val rssiPoll = object : Runnable {
        override fun run() {
            runCatching { gatt?.readRemoteRssi() }
            main.postDelayed(this, 400)
        }
    }
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.removeCallbacks(rssiPoll)
            if (newState == BluetoothProfile.STATE_CONNECTED) main.post(rssiPoll)
        }
        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) onSample(g.device, safeName(g.device), rssi, null, ble = true)
        }
    }

    fun track(address: String) {
        untrack()
        trackAddr = address
        if (_running.value) openGatt()
    }

    fun untrack() {
        trackAddr = null
        closeGatt()
    }

    private fun openGatt() {
        if (demo) return
        val a = trackAddr ?: return
        val dev = runCatching { adapter?.getRemoteDevice(a) }.getOrNull() ?: return
        // autoConnect: si collega appena il dispositivo entra in portata, senza timeout
        gatt = runCatching { dev.connectGatt(ctx, true, gattCb, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
    }

    private fun closeGatt() {
        main.removeCallbacks(rssiPoll)
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val refresher = object : Runnable {
        override fun run() {
            refreshConnected()
            loadBonded()
            if (_running.value) main.postDelayed(this, 4000)
        }
    }

    fun start() {
        if (_running.value) return
        if (demo) { _running.value = true; main.post(demoLoop); return }
        if (adapter == null) return
        _running.value = true
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach {
            runCatching { adapter?.getProfileProxy(ctx, profileListener, it) }
        }
        loadBonded()
        startBle()
        startClassic()
        openGatt()
        main.post(refresher)
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        main.removeCallbacksAndMessages(null)
        closeGatt()
        runCatching { adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        runCatching { adapter?.cancelDiscovery() }
        runCatching { ctx.unregisterReceiver(receiver) }
        proxies.forEach { (p, proxy) -> runCatching { adapter?.closeProfileProxy(p, proxy) } }
        proxies.clear()
    }

    private fun startBle() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setReportDelay(0)
            .build()
        runCatching { scanner.stopScan(bleCallback) }
        runCatching { scanner.startScan(null, settings, bleCallback) }
    }

    private fun startClassic() {
        if (!classicEnabled || adapter?.isDiscovering == true) return
        runCatching { adapter?.startDiscovery() }
    }

    fun setClassic(enabled: Boolean) {
        classicEnabled = enabled
        if (!_running.value) return
        if (enabled) startClassic() else runCatching { adapter?.cancelDiscovery() }
    }

    private fun safeName(d: BluetoothDevice): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) d.alias ?: d.name else d.name
    }.getOrNull()

    private fun vendorName(id: Int): String? = when (id) {
        0x004C -> "Apple"
        0x0006 -> "Microsoft"
        0x0075 -> "Samsung"
        0x00E0 -> "Google"
        0x0087 -> "Garmin"
        0x012D -> "Sony"
        0x009E -> "Bose"
        0x038F -> "Xiaomi"
        0x027D -> "Huawei"
        else -> null
    }

    /** Parole corte (<=4 lettere) solo come parola intera, per evitare "tv" dentro "activity" ecc. */
    private fun hasWord(n: String, words: List<String>): Boolean {
        val tokens = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { w -> if (w.length <= 4) w in tokens else w in n }
    }

    private fun kindOf(d: BluetoothDevice, name: String?): Kind {
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        when (cls?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> return when (cls.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Kind.HEADPHONES
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Kind.SPEAKER
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> Kind.TV
                else -> Kind.OTHER
            }
            BluetoothClass.Device.Major.COMPUTER -> return Kind.COMPUTER
            BluetoothClass.Device.Major.PHONE -> return Kind.PHONE
            BluetoothClass.Device.Major.WEARABLE -> return Kind.WATCH
        }
        val n = name?.lowercase() ?: return Kind.OTHER
        return when {
            hasWord(n, listOf("buds", "bud", "pods", "airpods", "headphones", "headphone", "earphones", "earbuds", "headset",
                "wh-1000", "wf-1000", "soundcore", "freebuds", "cuffie", "tws", "jabra", "sennheiser")) -> Kind.HEADPHONES
            hasWord(n, listOf("speaker", "soundbar", "boombox", "megaboom", "flip", "charge", "casse")) -> Kind.SPEAKER
            hasWord(n, listOf("laptop", "desktop", "macbook", "imac", "thinkpad", "pc", "windows", "ubuntu", "fedora")) -> Kind.COMPUTER
            hasWord(n, listOf("iphone", "pixel", "galaxy", "phone", "redmi", "xiaomi", "oneplus")) -> Kind.PHONE
            hasWord(n, listOf("watch", "band", "fitbit", "garmin")) -> Kind.WATCH
            hasWord(n, listOf("tv", "bravia", "chromecast", "firetv")) -> Kind.TV
            else -> Kind.OTHER
        }
    }

    @Synchronized
    private fun onSample(dev: BluetoothDevice, name: String?, rssi: Int, tx: Int?, ble: Boolean, vendor: String? = null) {
        val addr = dev.address
        val now = System.currentTimeMillis()
        val s = filters.getOrPut(addr) { Kalman() }.update(rssi.toDouble())
        val old = _devices.value[addr]
        val bonded = runCatching { dev.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        val finalName = name ?: old?.name
        val hist = ((old?.history ?: emptyList()) + (now to s)).filter { now - it.first <= 30_000 }
        val upd = BtDevice(
            address = addr,
            name = finalName,
            kind = old?.kind?.takeIf { it != Kind.OTHER } ?: kindOf(dev, finalName),
            rssi = rssi,
            smooth = s,
            txPower = tx ?: old?.txPower,
            lastSeen = now,
            bonded = bonded,
            connected = addr in connectedAddrs,
            audioConnected = addr in audioAddrs,
            vendor = vendor ?: old?.vendor,
            viaBle = ble || old?.viaBle == true,
            viaClassic = !ble || old?.viaClassic == true,
            history = hist,
        )
        _devices.value = _devices.value + (addr to upd)
    }

    @Synchronized
    private fun loadBonded() {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return
        val map = _devices.value.toMutableMap()
        for (d in bonded) {
            val old = map[d.address]
            val name = safeName(d)
            map[d.address] = if (old != null)
                old.copy(bonded = true, name = name ?: old.name, connected = d.address in connectedAddrs,
                    audioConnected = d.address in audioAddrs)
            else BtDevice(d.address, name, kindOf(d, name), null, null, null, 0, true,
                    d.address in connectedAddrs, d.address in audioAddrs, null, viaBle = false, viaClassic = false)
        }
        _devices.value = map
    }

    @Synchronized
    private fun refreshConnected() {
        val audio = HashSet<String>()
        proxies.values.forEach { p -> runCatching { p.connectedDevices.forEach { audio += it.address } } }
        val set = HashSet<String>(audio)
        runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { set += it.address } }
        connectedAddrs = set
        audioAddrs = audio
        _devices.value = _devices.value.mapValues { (a, d) ->
            d.copy(
                connected = a in set,
                audioConnected = a in audio,
                kind = if (d.kind == Kind.OTHER && a in audio) Kind.HEADPHONES else d.kind,
            )
        }
    }

    /** Rimuove i dispositivi non più visti da 2 minuti (tranne associati). */
    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        _devices.value = _devices.value.filterValues { it.bonded || it.connected || now - it.lastSeen < 120_000 }
    }
}
