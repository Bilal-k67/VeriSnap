package com.verisnap.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * Monitors device accelerometer and gyroscope to detect natural hand movement.
 * Prevents emulator/tripod usage by requiring micro-tremors consistent with human hand motion.
 */
class DeviceSensorManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Sensor data buffers (last 30 readings)
    private val accelBuffer = mutableListOf<FloatArray>()
    private val gyroBuffer = mutableListOf<FloatArray>()
    private val maxBufferSize = 30

    // Thresholds for natural movement detection
    private val minAccelVariance = 0.001f // Minimum variance to detect motion
    private val maxAccelVariance = 2.0f  // Maximum before motion too extreme
    private val minGyroVariance = 0.0001f
    private val maxGyroVariance = 3.0f

    private val _isDeviceMovingNaturally = MutableStateFlow(false)
    val isDeviceMovingNaturally: StateFlow<Boolean> = _isDeviceMovingNaturally

    private val _sensorQuality = MutableStateFlow(SensorQuality.UNSTABLE)
    val sensorQuality: StateFlow<SensorQuality> = _sensorQuality

    private val _gyroVariance = MutableStateFlow(0f)
    val gyroVariance: StateFlow<Float> = _gyroVariance

    private val _accelVariance = MutableStateFlow(0f)
    val accelVariance: StateFlow<Float> = _accelVariance

    enum class SensorQuality {
        UNSTABLE,      // Red - Stationary or perfect values (emulator)
        SEARCHING,     // Yellow - Some noise but not enough
        LOCKED         // Green - Natural movement detected
    }

    fun startListening() {
        Log.d(TAG, "Starting sensor monitoring")
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopListening() {
        Log.d(TAG, "Stopping sensor monitoring")
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val accelData = floatArrayOf(event.values[0], event.values[1], event.values[2])
                accelBuffer.add(accelData)
                if (accelBuffer.size > maxBufferSize) {
                    accelBuffer.removeAt(0)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroData = floatArrayOf(event.values[0], event.values[1], event.values[2])
                gyroBuffer.add(gyroData)
                if (gyroBuffer.size > maxBufferSize) {
                    gyroBuffer.removeAt(0)
                }
            }
        }

        // Update quality assessment when we have enough data
        if (accelBuffer.size >= 10 && gyroBuffer.size >= 10) {
            updateSensorQuality()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private fun updateSensorQuality() {
        val accelVar = calculateVariance(accelBuffer)
        val gyroVar = calculateVariance(gyroBuffer)

        _accelVariance.value = accelVar
        _gyroVariance.value = gyroVar

        Log.d(TAG, "Accel Variance: $accelVar, Gyro Variance: $gyroVar")

        // Detect perfect static values (emulator or tripod)
        val allNearZero = accelBuffer.all { data ->
            sqrt(data[0] * data[0] + data[1] * data[1] + data[2] * data[2]) < 0.1f
        }

        val quality = when {
            // Perfect zero or near-zero values = UNSTABLE (emulator/tripod)
            allNearZero -> {
                Log.w(TAG, "⚠️ Detected static sensors - possible emulator/tripod")
                SensorQuality.UNSTABLE
            }
            // Both variances in natural range
            accelVar in minAccelVariance..maxAccelVariance &&
            gyroVar in minGyroVariance..maxGyroVariance -> {
                Log.i(TAG, "✅ Natural movement detected")
                SensorQuality.LOCKED
            }
            // Some movement but not optimal
            accelVar > minAccelVariance || gyroVar > minGyroVariance -> {
                Log.d(TAG, "🔍 Searching for stable movement...")
                SensorQuality.SEARCHING
            }
            else -> SensorQuality.UNSTABLE
        }

        _sensorQuality.value = quality
        _isDeviceMovingNaturally.value = quality == SensorQuality.LOCKED
    }

    private fun calculateVariance(dataBuffer: List<FloatArray>): Float {
        if (dataBuffer.size < 2) return 0f

        // Calculate magnitude of each reading
        val magnitudes = dataBuffer.map { data ->
            sqrt(data[0] * data[0] + data[1] * data[1] + data[2] * data[2])
        }

        val mean = magnitudes.average()
        val variance = magnitudes.map { (it - mean) * (it - mean) }.average()

        return variance.toFloat()
    }

    companion object {
        private const val TAG = "DeviceSensorManager"
    }
}