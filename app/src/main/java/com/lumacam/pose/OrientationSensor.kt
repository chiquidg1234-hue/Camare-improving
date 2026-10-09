package com.lumacam.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lumacam.core.pose.AngleTracker
import com.lumacam.core.pose.CameraAngle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Ángulo de la cámara a partir del sensor de gravedad (o del acelerómetro filtrado si no hay).
 * La actividad está fija en vertical, así que los ejes del sensor coinciden con los de la pantalla.
 */
class OrientationSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gravity: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor: Sensor? = gravity ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val tracker = AngleTracker(alpha = if (gravity != null) 0.3f else 0.1f)
    private var running = false

    @Volatile
    var frontCamera: Boolean = false

    private val _angle = MutableStateFlow<CameraAngle?>(null)
    val angle: StateFlow<CameraAngle?> = _angle.asStateFlow()

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
    }

    fun stop() {
        if (!running) return
        manager?.unregisterListener(this)
        running = false
        tracker.reset()
        _angle.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val next = tracker.update(v[0], v[1], v[2], frontCamera)
        val prev = _angle.value
        // Sólo publicar cambios visibles (menos recomposiciones de la interfaz).
        if (prev == null || prev.category != next.category ||
            abs(prev.elevationDeg - next.elevationDeg) >= 0.5f ||
            (prev.rollDeg ?: 99f) - (next.rollDeg ?: 99f) !in -0.3f..0.3f
        ) {
            _angle.value = next
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
