package it.klab.ritrova

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
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

enum class Kind { HEADPHONES, COMPUTER, PHONE, WATCH, TV, OTHER }

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
    val viaBle: Boolean,
    val viaClassic: Boolean,
    val history: List<Pair<Long, Double>> = emptyList(),
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "Sconosciuto"

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
    private val proxies = HashMap<Int, BluetoothProfile>()

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------- BLE ----------
    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val tx = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                ?: result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
            val name = result.scanRecord?.deviceName ?: safeName(result.device)
            onSample(result.device, name, result.rssi, tx, ble = true)
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

    private val refresher = object : Runnable {
        override fun run() {
            refreshConnected()
            loadBonded()
            if (_running.value) main.postDelayed(this, 4000)
        }
    }

    fun start() {
        if (_running.value || adapter == null) return
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
        main.post(refresher)
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        main.removeCallbacksAndMessages(null)
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

    private fun kindOf(d: BluetoothDevice, name: String?): Kind {
        val major = runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()
        when (major) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> return Kind.HEADPHONES
            BluetoothClass.Device.Major.COMPUTER -> return Kind.COMPUTER
            BluetoothClass.Device.Major.PHONE -> return Kind.PHONE
            BluetoothClass.Device.Major.WEARABLE -> return Kind.WATCH
        }
        val n = name?.lowercase() ?: return Kind.OTHER
        return when {
            listOf("bud", "pods", "headphone", "earphone", "wh-", "wf-", "jbl", "airpod", "soundcore",
                "beats", "bose", "sennheiser", "cuffie", "freebuds", "galaxy buds", "pixel buds", "tws").any { it in n } -> Kind.HEADPHONES
            listOf("pc", "laptop", "desktop", "macbook", "imac", "thinkpad", "-win", "windows", "linux", "ubuntu", "fedora").any { it in n } -> Kind.COMPUTER
            listOf("phone", "pixel", "galaxy", "iphone", "redmi", "xiaomi", "oneplus", "moto").any { it in n } -> Kind.PHONE
            listOf("watch", "band", "fit").any { it in n } -> Kind.WATCH
            listOf("tv", "bravia", "chromecast", "fire").any { it in n } -> Kind.TV
            else -> Kind.OTHER
        }
    }

    @Synchronized
    private fun onSample(dev: BluetoothDevice, name: String?, rssi: Int, tx: Int?, ble: Boolean) {
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
                old.copy(bonded = true, name = name ?: old.name, connected = d.address in connectedAddrs)
            else BtDevice(d.address, name, kindOf(d, name), null, null, null, 0, true,
                    d.address in connectedAddrs, viaBle = false, viaClassic = false)
        }
        _devices.value = map
    }

    @Synchronized
    private fun refreshConnected() {
        val set = HashSet<String>()
        proxies.values.forEach { p -> runCatching { p.connectedDevices.forEach { set += it.address } } }
        runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { set += it.address } }
        connectedAddrs = set
        _devices.value = _devices.value.mapValues { (a, d) -> d.copy(connected = a in set) }
    }

    /** Rimuove i dispositivi non più visti da 2 minuti (tranne associati). */
    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        _devices.value = _devices.value.filterValues { it.bonded || it.connected || now - it.lastSeen < 120_000 }
    }
}
