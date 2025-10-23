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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LightMeterScreen(onBack: () -> Unit) = ScreenScaffold("Light Meter", onBack) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val lightSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }

    // current reading from the sensor
    var currentLux by remember { mutableStateOf(0f) }

    // live list of recent readings for plotting
    val luxData = remember { mutableStateListOf<Float>() }

    // listen to the sensor (raw readings)
    DisposableEffect(lightSensor) {
        if (lightSensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val raw = event.values.firstOrNull() ?: return
                    currentLux = raw
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_FASTEST)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // background coroutine that keeps pushing new data to the graph
    LaunchedEffect(Unit) {
        while (true) {
            luxData.add(currentLux)
            if (luxData.size > 200) luxData.removeFirst()
            delay(33L) // ~30 FPS (adjust if needed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (lightSensor == null) {
            Text("No ambient light sensor found.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("Back") }
            return@Column
        }

        Text("Ambient Light", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("${"%.1f".format(currentLux)} lx", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(50.dp))

        // ---- Real-Time Graph ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val paddingLeft = 70f
                val paddingBottom = 55f
                val paddingTop = 10f
                val graphWidth = size.width - paddingLeft - 10f
                val graphHeight = size.height - paddingBottom - paddingTop

                if (luxData.size < 2) return@Canvas

                val maxVal = luxData.maxOrNull() ?: 1f
                val minVal = luxData.minOrNull() ?: 0f
                val range = max(1f, maxVal - minVal)

                // map readings to graph space
                val stepX = graphWidth / (luxData.size - 1).coerceAtLeast(1)
                val points = luxData.mapIndexed { i, v ->
                    val x = paddingLeft + i * stepX
                    val y = paddingTop + graphHeight - ((v - minVal) / range) * graphHeight
                    Offset(x, y)
                }

                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.RIGHT
                }

                // horizontal grid + Y labels
                val yTicks = 5
                for (i in 0..yTicks) {
                    val y = paddingTop + graphHeight * i / yTicks
                    val luxVal = maxVal - (range * i / yTicks)
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.25f),
                        start = Offset(paddingLeft, y),
                        end = Offset(size.width - 5f, y),
                        strokeWidth = 1f
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "${luxVal.roundToInt()}",
                        paddingLeft - 8f,
                        y + 8f,
                        labelPaint
                    )
                }

                // vertical grid + X labels
                val xTicks = 4
                val xLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 22f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                for (i in 0..xTicks) {
                    val x = paddingLeft + graphWidth * i / xTicks
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.25f),
                        start = Offset(x, paddingTop),
                        end = Offset(x, paddingTop + graphHeight),
                        strokeWidth = 1f
                    )
                    val sampleIndex = (luxData.size * i / xTicks)
                    drawContext.canvas.nativeCanvas.drawText(
                        "$sampleIndex",
                        x,
                        size.height - 20f,
                        xLabelPaint
                    )
                }

                // axes
                drawLine(
                    color = Color.Black,
                    start = Offset(paddingLeft, paddingTop),
                    end = Offset(paddingLeft, paddingTop + graphHeight),
                    strokeWidth = 2f
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(paddingLeft, paddingTop + graphHeight),
                    end = Offset(size.width - 5f, paddingTop + graphHeight),
                    strokeWidth = 2f
                )

                // line plot
                for (i in 1 until points.size) {
                    drawLine(
                        color = Color(0xFF64B5F6),
                        start = points[i - 1],
                        end = points[i],
                        strokeWidth = 4f
                    )
                }

                // axis labels
                val axisLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 26f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(
                    "Sample Index",
                    paddingLeft + graphWidth / 2,
                    size.height - 5f,
                    axisLabelPaint
                )

                val yLabelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 26f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-90f, 25f, paddingTop + graphHeight / 2)
                drawContext.canvas.nativeCanvas.drawText("Lux (lx)", 25f, paddingTop + graphHeight / 2, yLabelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
    }
}
