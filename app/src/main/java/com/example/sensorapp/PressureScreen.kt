package com.example.sensorapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
fun PressureScreen(onBack: () -> Unit) = ScreenScaffold("Pressure (Barometer)", onBack) {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val pressureSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) }

    var pressure by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(pressureSensor) {
        if (pressureSensor == null) {
            pressure = null
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                pressure = event.values.firstOrNull()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_UI)

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (pressureSensor == null) {
            Text("No barometric pressure sensor found on this device.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("Go Back") }
            return@Column
        }

        Text("Barometric Pressure", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2.5f

                val minP = 950f
                val maxP = 1050f
                val current = pressure ?: 1013f
                val fraction = ((current - minP) / (maxP - minP)).coerceIn(0f, 1f)

                // Background arc
                drawArc(
                    color = Color.Gray.copy(alpha = 0.3f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    style = Stroke(width = 16f)
                )

                // Progress arc
                drawArc(
                    color = Color(0xFF1976D2),
                    startAngle = 180f,
                    sweepAngle = 180f * fraction,
                    useCenter = false,
                    style = Stroke(width = 16f)
                )

                // Tick marks and labels
                val tickStep = 10f
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 32f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }

                for (p in minP.toInt()..maxP.toInt() step tickStep.toInt()) {
                    val frac = (p - minP) / (maxP - minP)
                    val angleDeg = 180f + 180f * frac
                    val angleRad = Math.toRadians(angleDeg.toDouble())

                    // Tick positions
                    val outer = Offset(
                        (center.x + cos(angleRad) * radius * 1.05f).toFloat(),
                        (center.y + sin(angleRad) * radius * 1.05f).toFloat()
                    )
                    val inner = Offset(
                        (center.x + cos(angleRad) * radius * 0.9f).toFloat(),
                        (center.y + sin(angleRad) * radius * 0.9f).toFloat()
                    )

                    drawLine(color = Color.White, start = inner, end = outer, strokeWidth = 3f)

                    // Label slightly beyond tick
                    val textOffset = Offset(
                        (center.x + cos(angleRad) * radius * 1.25f).toFloat(),
                        (center.y + sin(angleRad) * radius * 1.25f).toFloat()
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        p.toString(),
                        textOffset.x,
                        textOffset.y + 10f, // adjust vertical alignment
                        labelPaint
                    )
                }

                // Needle
                val angleDeg = 180f + 180f * fraction
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val needleLength = radius * 0.9f
                val needleEnd = Offset(
                    (center.x + cos(angleRad) * needleLength).toFloat(),
                    (center.y + sin(angleRad) * needleLength).toFloat()
                )

                drawLine(
                    color = Color.Red,
                    start = center,
                    end = needleEnd,
                    strokeWidth = 4f
                )
                drawCircle(color = Color.Black, radius = 10f, center = center)
            }
        }

        Spacer(Modifier.height(16.dp))
        val pText = pressure?.let { String.format("%.1f", it) } ?: "—"
        Text("Pressure: $pText hPa", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(8.dp))
//        Text(
//            "Standard sea-level pressure ≈ 1013 hPa",
//            style = MaterialTheme.typography.bodySmall,
//            textAlign = TextAlign.Center,
//            color = Color.Gray
//        )
    }
}
