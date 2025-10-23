package com.example.sensorapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MagnetometerScreen(onBack: () -> Unit) = ScreenScaffold("Magnetometer", onBack) {
    val context = LocalContext.current

    /* -------- state -------- */
    var headingDeg by remember { mutableStateOf(0f) }            // instant heading
    var smoothedHeading by remember { mutableStateOf(0f) }       // smoothed heading

    // raw magnetic field (low-pass filtered) in device axes (µT)
    var magX by remember { mutableStateOf(0f) }
    var magY by remember { mutableStateOf(0f) }
    var magZ by remember { mutableStateOf(0f) }
    var magMag by remember { mutableStateOf(0f) }                 // |B| magnitude

    var accuracyMag by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }
    var accuracyAcc by remember { mutableStateOf(SensorManager.SENSOR_STATUS_UNRELIABLE) }

    // simple exponential smoothing for heading (wrap-aware)
    fun smoothHeading(prev: Float, next: Float, alpha: Float = 0.12f): Float {
        var diff = next - prev
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        val out = prev + alpha * diff
        return ((out % 360f) + 360f) % 360f
    }

    /* -------- sensors: accel + magnetic -> tilt-compensated azimuth -------- */
    DisposableEffect(Unit) {
        val mgr = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magn  = mgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val gravity = FloatArray(3)
        val geomag  = FloatArray(3)
        var haveG = false
        var haveM = false

        // low-pass filter for raw sensors
        fun lp(prev: Float, cur: Float, alpha: Float = 0.12f) = prev + alpha * (cur - prev)

        val listener = object : SensorEventListener {
            private val R = FloatArray(9)
            private val Rremap = FloatArray(9)
            private val I = FloatArray(9)

            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (!haveG) {
                            gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                            haveG = true
                        } else {
                            gravity[0] = lp(gravity[0], e.values[0])
                            gravity[1] = lp(gravity[1], e.values[1])
                            gravity[2] = lp(gravity[2], e.values[2])
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (!haveM) {
                            geomag[0] = e.values[0]; geomag[1] = e.values[1]; geomag[2] = e.values[2]
                            haveM = true
                        } else {
                            geomag[0] = lp(geomag[0], e.values[0])
                            geomag[1] = lp(geomag[1], e.values[1])
                            geomag[2] = lp(geomag[2], e.values[2])
                        }

                        // expose filtered magnetometer as "raw" readouts (device axes, µT)
                        magX = geomag[0]
                        magY = geomag[1]
                        magZ = geomag[2]
                        magMag = sqrt(magX*magX + magY*magY + magZ*magZ)
                    }
                }

                if (haveG && haveM) {
                    if (SensorManager.getRotationMatrix(R, I, gravity, geomag)) {
                        // Remap to current display rotation so +Y is top of screen, +X is right
                        val rot = context.display?.rotation ?: Surface.ROTATION_0
                        when (rot) {
                            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                                R, SensorManager.AXIS_X, SensorManager.AXIS_Y, Rremap)
                            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                                R, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, Rremap)
                            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                                R, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, Rremap)
                            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                                R, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, Rremap)
                            else -> System.arraycopy(R, 0, Rremap, 0, 9)
                        }

                        // Derive compass heading (0..360, clockwise from North)
                        val azRad = atan2(Rremap[1].toDouble(), Rremap[4].toDouble()).toFloat()
                        var deg = Math.toDegrees(azRad.toDouble()).toFloat()
                        deg = (deg + 360f) % 360f

                        headingDeg = deg
                        smoothedHeading = smoothHeading(smoothedHeading, headingDeg)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                when (sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accuracyAcc = accuracy
                    Sensor.TYPE_MAGNETIC_FIELD -> accuracyMag = accuracy
                }
            }
        }

        if (accel != null) mgr.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        if (magn  != null) mgr.registerListener(listener, magn,  SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            mgr.unregisterListener(listener)
        }
    }

    /* ---------------- UI ---------------- */
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Heading: ${smoothedHeading.toInt()}°", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Compass2D(
            headingDeg = smoothedHeading,
            ringColor = MaterialTheme.colorScheme.outline,
            needleColor = MaterialTheme.colorScheme.primary,
            northColor = Color(0xFFD32F2F)
        )

        Spacer(Modifier.height(12.dp))

        // Raw magnetometer readouts (device axes)
        Text("Magnetic Field (µT)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text("X = ${"%.1f".format(magX)}   Y = ${"%.1f".format(magY)}   Z = ${"%.1f".format(magZ)}",
            style = MaterialTheme.typography.bodyMedium)
        Text("‖B‖ = ${"%.1f".format(magMag)} µT", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(10.dp))

        // Accuracy/help
        val aTxt = when (accuracyAcc) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            else -> "Unreliable"
        }
        val mTxt = when (accuracyMag) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
            else -> "Unreliable"
        }

        Spacer(Modifier.height(6.dp))

    }
}

/* ---------------- Drawing ---------------- */

@Composable
private fun Compass2D(
    headingDeg: Float,
    ringColor: Color,
    needleColor: Color,
    northColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.42f
            val outer = r

            // Outer ring
            drawCircle(
                color = ringColor,
                radius = outer,
                center = Offset(cx, cy),
                style = Stroke(width = 6f)
            )

            // Tick marks every 10°, longer every 30°, longest at cardinals
            for (deg in 0 until 360 step 10) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val isCardinal = deg % 90 == 0
                val isThirty = deg % 30 == 0
                val len = when {
                    isCardinal -> r * 0.18f
                    isThirty -> r * 0.12f
                    else -> r * 0.07f
                }
                val x0 = cx + (outer - len) * sin(rad)
                val y0 = cy - (outer - len) * cos(rad)
                val x1 = cx + outer * sin(rad)
                val y1 = cy - outer * cos(rad)
                drawLine(
                    color = ringColor,
                    start = Offset(x0, y0),
                    end = Offset(x1, y1),
                    strokeWidth = if (isCardinal) 5f else 3f,
                    cap = StrokeCap.Round
                )
            }

            // Labels N/E/S/W
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.GRAY
                textSize = (r * 0.18f)
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText("N", cx, cy - outer + textPaint.textSize, textPaint)
            drawContext.canvas.nativeCanvas.drawText("S", cx, cy + outer - 8f, textPaint)
            drawContext.canvas.nativeCanvas.drawText("E", cx + outer - 14f, cy + textPaint.textSize * 0.35f, textPaint)
            drawContext.canvas.nativeCanvas.drawText("W", cx - outer + 14f, cy + textPaint.textSize * 0.35f, textPaint)

            // Needle points to North in world → rotate by -heading
            val angleRad = (-headingDeg) * (PI.toFloat() / 180f)
            val s = sin(angleRad); val c = cos(angleRad)

            fun rot(dx: Float, dy: Float): Offset {
                val x = dx * c - dy * s
                val y = dx * s + dy * c
                return Offset(cx + x, cy + y)
            }

            val tipLen = r * 0.82f
            val tailLen = r * 0.45f
            val halfWidth = r * 0.06f

            // North (red) triangle
            val north = Path().apply {
                moveTo(rot(0f, -tipLen).x, rot(0f, -tipLen).y)
                lineTo(rot(-halfWidth, 0f).x, rot(-halfWidth, 0f).y)
                lineTo(rot(halfWidth, 0f).x, rot(halfWidth, 0f).y)
                close()
            }
            // South (primary) triangle
            val south = Path().apply {
                moveTo(rot(0f, tailLen).x, rot(0f, tailLen).y)
                lineTo(rot(-halfWidth, 0f).x, rot(-halfWidth, 0f).y)
                lineTo(rot(halfWidth, 0f).x, rot(halfWidth, 0f).y)
                close()
            }

            drawPath(north, color = northColor)
            drawPath(south, color = needleColor)

            // Center hub
            drawCircle(
                color = ringColor,
                radius = r * 0.06f,
                center = Offset(cx, cy)
            )
        }
    }
}
