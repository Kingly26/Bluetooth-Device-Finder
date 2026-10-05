package io.github.kingly26.btfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Direzione in cui punta il telefono (0 = nord, in gradi), dal sensore di rotazione. */
class Compass(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    private var sx = 0.0
    private var sy = 0.0

    // Più schermate usano la bussola: la spengo solo quando l'ultima ha finito.
    private var users = 0
    fun start() {
        if (users++ == 0) sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
    fun stop() {
        if (users > 0 && --users == 0) sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        val a = ori[0].toDouble()
        // media su seno/coseno: evita il salto 359° -> 0°
        sx = sx * 0.8 + sin(a) * 0.2
        sy = sy * 0.8 + cos(a) * 0.2
        val deg = ((Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0).toFloat()
        val old = _heading.value
        if (old == null || abs(wrap180(deg - old)) >= 1f) _heading.value = deg
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}

/** Porta un angolo in -180..180. */
fun wrap180(a: Float): Float = ((a % 360f) + 540f) % 360f - 180f

/**
 * Mappa del segnale per direzione. Il corpo di chi tiene il telefono scherma il segnale che arriva
 * da dietro: girando su se stessi l'RSSI è più alto quando si guarda verso l'oggetto.
 */
class DirectionFinder(val bins: Int = 24) {
    class Reading(
        val sectors: List<Double?>, // dBm medio per settore (null = mai misurato o troppo vecchio)
        val bearing: Float?,        // direzione stimata in gradi bussola, se i dati bastano
        val coverage: Float,        // quota del giro già misurata, 0..1
        val lo: Double,
        val hi: Double,
    )

    val binWidth: Float get() = 360f / bins
    private val value = DoubleArray(bins) { Double.NaN }
    private val time = LongArray(bins)

    fun add(heading: Float, rssi: Double, now: Long) {
        val i = (((heading / binWidth).roundToInt() % bins) + bins) % bins
        value[i] = if (value[i].isNaN() || now - time[i] > 20_000) rssi else value[i] * 0.7 + rssi * 0.3
        time[i] = now
    }

    fun snapshot(now: Long): Reading {
        val raw = List(bins) { i -> value[i].takeIf { !it.isNaN() && now - time[i] < 90_000 } }
        // media con i due settori vicini per togliere i picchi isolati
        val sm = List(bins) { i ->
            if (raw[i] == null) null else {
                var sum = 0.0
                var w = 0.0
                for ((off, wt) in listOf(-1 to 0.25, 0 to 0.5, 1 to 0.25)) {
                    val v = raw[(i + off + bins) % bins] ?: continue
                    sum += v * wt; w += wt
                }
                sum / w
            }
        }
        val known = sm.filterNotNull()
        val lo = known.minOrNull() ?: 0.0
        val hi = known.maxOrNull() ?: 0.0
        val coverage = known.size.toFloat() / bins
        var bearing: Float? = null
        if (coverage >= 0.33f && hi - lo >= 4.0) {
            var x = 0.0
            var y = 0.0
            sm.forEachIndexed { i, v ->
                if (v != null) {
                    val wgt = (v - lo) * (v - lo)
                    val a = Math.toRadians((i * binWidth).toDouble())
                    x += wgt * sin(a); y += wgt * cos(a)
                }
            }
            bearing = ((Math.toDegrees(atan2(x, y)) + 360.0) % 360.0).toFloat()
        }
        return Reading(sm, bearing, coverage, lo, hi)
    }
}
