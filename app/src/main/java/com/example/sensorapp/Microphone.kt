package com.example.sensorapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

private const val LN10 = 2.302585092994046f

@Composable
fun MicrophoneVolumeScreen(onBack: () -> Unit) = ScreenScaffold("Microphone Spectrum", onBack) {
    val context = LocalContext.current

    /* ---------- Permission ---------- */
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    /* ---------- User-selectable FFT size via slider ---------- */
    val fftChoices = listOf(512, 1024, 2048, 4096, 8192)
    var fftIdx by remember { mutableStateOf(2) } // default to 2048
    var fftSize by remember { mutableStateOf(fftChoices[fftIdx]) }

    /* ---------- Audio / FFT settings ---------- */
    val sampleRate = remember { pickSampleRate() } // tries 44100 first
    val hopMs = 50L    // ~20 FPS UI update

    // Hann window + coherent gain (corrects amplitude loss). Recomputed when fftSize changes.
    val hann = remember(fftSize) {
        FloatArray(fftSize) { i ->
            (0.5f * (1f - cos((2f * Math.PI * i) / (fftSize - 1)).toFloat()))
        }
    }
    val hannCoherentGain = remember(hann) { hann.sum() / fftSize.toFloat() } // ~0.5 for Hann

    /* ---------- UI state ---------- */
    var spectrumDb by remember(fftSize) { mutableStateOf(FloatArray(fftSize / 2)) }
    var isRecording by remember { mutableStateOf(false) }
    var rmsDb by remember { mutableStateOf(-120f) }  // live overall magnitude in dBFS

    /* ---------- Start/stop audio thread (restarts when fftSize changes) ---------- */
    DisposableEffect(isRecording, hasPermission, sampleRate, fftSize) {
        var audioThread: Thread? = null
        var running = false

        if (isRecording && hasPermission) {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recBuf = max(minBuf, fftSize * 2)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recBuf
            )

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
                running = true
                audioThread = thread(start = true, name = "MicFFT") {
                    val pcm = ShortArray(fftSize)
                    val real = FloatArray(fftSize)
                    val imag = FloatArray(fftSize)
                    var smooth = FloatArray(fftSize / 2)
                    var first = true

                    while (running) {
                        // Read exactly fftSize samples
                        var readTotal = 0
                        while (readTotal < fftSize && running) {
                            val n = recorder.read(pcm, readTotal, fftSize - readTotal)
                            if (n <= 0) break
                            readTotal += n
                        }
                        if (!running) break
                        if (readTotal < fftSize) continue

                        // --- Overall RMS dBFS (live magnitude) ---
                        var acc = 0.0
                        for (i in 0 until fftSize) {
                            val x = pcm[i] / 32768.0
                            acc += x * x
                        }
                        val rms = kotlin.math.sqrt(acc / fftSize)
                        val floor = 1e-12
                        val dbRms = (20.0 * kotlin.math.log10(kotlin.math.max(rms, floor))).toFloat()

                        // light smoothing for the display readout
                        rmsDb = if (first) dbRms else 0.2f * dbRms + 0.8f * rmsDb

                        // Convert to float & apply Hann for FFT
                        for (i in 0 until fftSize) {
                            real[i] = (pcm[i] / 32768f) * hann[i]
                            imag[i] = 0f
                        }

                        // FFT in-place
                        fft(real, imag)

                        // --- Window-corrected, single-sided amplitude spectrum in dBFS ---
                        val nF = fftSize.toFloat()
                        val cg = hannCoherentGain

                        val magsDb = FloatArray(fftSize / 2)
                        for (k in 0 until (fftSize / 2)) {
                            val re = real[k]; val im = imag[k]
                            var mag = sqrt(re * re + im * im) // |X[k]|

                            // Scale to amplitude: divide by N, correct window, single-sided doubling except DC
                            mag = (mag / nF) / cg
                            if (k != 0) mag *= 2f

                            // Convert to dBFS (0 = digital full-scale)
                            val db = 20f * (ln(kotlin.math.max(mag, 1e-12f)) / LN10)
                            magsDb[k] = db.coerceAtLeast(-150f)
                        }

                        // Smooth a bit for display
                        if (first) {
                            smooth = magsDb.copyOf()
                            first = false
                        } else {
                            val alpha = 0.3f
                            for (i in magsDb.indices) {
                                smooth[i] = alpha * magsDb[i] + (1 - alpha) * smooth[i]
                            }
                        }

                        spectrumDb = smooth.copyOf()

                        try { Thread.sleep(hopMs) } catch (_: InterruptedException) {}
                    }

                    try { recorder.stop() } catch (_: Exception) {}
                    recorder.release()
                }
            }
        }

        onDispose {
            running = false
            audioThread?.interrupt()
        }
    }

    /* ---------- UI ---------- */
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermission) {
            Text("Microphone access is required to show the spectrum.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text("Grant Microphone Permission")
            }
        } else {
            // Start/Stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { isRecording = !isRecording }) {
                    Text(if (isRecording) "Stop" else "Start")
                }
                Spacer(Modifier.width(12.dp))
                Text("Rate: $sampleRate Hz", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(12.dp))
            Text("Live Frequency Spectrum (dBFS)", style = MaterialTheme.typography.titleMedium)

            SpectrumBars(
                spectrumDb = spectrumDb,
                sampleRate = sampleRate,
                axisColor = MaterialTheme.colorScheme.outline,
                gradientColors = listOf(
                    Color(0xFF41DA26), // teal-ish at the bottom
                    Color(0xFFC2A457), // purple mid
                    Color(0xFFFC0000)  // warm at the top
                ),
                barTopStroke = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )

            // ---- dB magnitude readout under the graph ----
            Spacer(Modifier.height(6.dp))

            // ---- FFT size slider under the graph ----
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("FFT Size: ${fftChoices[fftIdx]}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = fftIdx.toFloat(),
                    onValueChange = { v ->
                        val idx = v.roundToStep(0f, (fftChoices.lastIndex).toFloat())
                        if (idx != fftIdx.toFloat()) {
                            fftIdx = idx.toInt()
                            fftSize = fftChoices[fftIdx] // triggers recompute & thread restart
                        }
                    },
                    valueRange = 0f..fftChoices.lastIndex.toFloat(),
                    steps = fftChoices.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("512", style = MaterialTheme.typography.labelSmall)
                    Text("1024", style = MaterialTheme.typography.labelSmall)
                    Text("2048", style = MaterialTheme.typography.labelSmall)
                    Text("4096", style = MaterialTheme.typography.labelSmall)
                    Text("8192", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Level: ${"%.1f".format(rmsDb)} dBFS",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/* ================= Utilities ================= */

private fun Float.roundToStep(min: Float, max: Float): Float {
    val clamped = this.coerceIn(min, max)
    return round(clamped)
}

private fun pickSampleRate(): Int {
    // Try common rates; return first with a valid buffer size
    val candidates = intArrayOf(44100, 48000, 22050, 16000)
    for (sr in candidates) {
        val min = AudioRecord.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min > 0) return sr
    }
    return 44100
}

// In-place radix-2 Cooley–Tukey FFT (complex)
private fun fft(real: FloatArray, imag: FloatArray) {
    val n = real.size
    require(n == imag.size && (n and (n - 1)) == 0) { "FFT size must be power of 2" }

    // Bit-reversal permutation
    var j = 0
    for (i in 1 until n - 1) {
        var bit = n shr 1
        while (j >= bit) {
            j -= bit
            bit = bit shr 1
        }
        j += bit
        if (i < j) {
            val tr = real[i]; real[i] = real[j]; real[j] = tr
            val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
        }
    }

    // Butterfly stages
    var len = 2
    while (len <= n) {
        val ang = (-2.0 * Math.PI / len)
        val wlenCos = cos(ang).toFloat()
        val wlenSin = sin(ang).toFloat()
        for (i in 0 until n step len) {
            var wr = 1f
            var wi = 0f
            val half = len shr 1
            for (k in 0 until half) {
                val uR = real[i + k]
                val uI = imag[i + k]
                val vR = real[i + k + half] * wr - imag[i + k + half] * wi
                val vI = real[i + k + half] * wi + imag[i + k + half] * wr

                real[i + k] = uR + vR
                imag[i + k] = uI + vI
                real[i + k + half] = uR - vR
                imag[i + k + half] = uI - vI

                val nxtWr = wr * wlenCos - wi * wlenSin
                val nxtWi = wr * wlenSin + wi * wlenCos
                wr = nxtWr; wi = nxtWi
            }
        }
        len = len shl 1
    }
}

/* =============== Bars Drawing =============== */

@Composable
private fun SpectrumBars(
    spectrumDb: FloatArray,
    sampleRate: Int,
    axisColor: Color,
    gradientColors: List<Color>,
    barTopStroke: Color
) {
    val minDb = -120f
    val maxDb = 0f
    val nyquist = sampleRate / 2f
    val targetBars = 64

    Canvas(Modifier.fillMaxWidth().height(340.dp)) {
        val w = size.width
        val h = size.height
        val paddingLeft = 112f
        val yTitleLeft = 24f
        val paddingRight = 12f
        val paddingTop = 12f
        val paddingBottomTicks = 30f
        val paddingBottomTitle = 24f
        val paddingBottom = paddingBottomTicks + paddingBottomTitle
        val plotW = w - paddingLeft - paddingRight
        val plotH = h - paddingTop - paddingBottom
        val origin = Offset(paddingLeft, paddingTop + plotH)

        // axes
        drawLine(axisColor, Offset(paddingLeft, paddingTop), Offset(paddingLeft, paddingTop + plotH), 2f)
        drawLine(axisColor, origin, Offset(paddingLeft + plotW, origin.y), 2f)

        // Y ticks
        val tickPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 26f
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        val xTickPaint = android.graphics.Paint(tickPaint).apply { textAlign = android.graphics.Paint.Align.CENTER }
        val titlePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val yTicks = listOf(0f, -20f, -40f, -60f, -80f, -100f, -120f)
        for (tick in yTicks) {
            val y = paddingTop + mapDbToY(tick, plotH, minDb, maxDb)
            drawLine(axisColor, Offset(paddingLeft - 6f, y), Offset(paddingLeft, y), 1.5f)
            drawContext.canvas.nativeCanvas.drawText("${tick.toInt()} dB", paddingLeft - 14f, y + 8f, tickPaint)
        }

        // ----- Log-scaled bars with vertical gradient fill -----
        val n = spectrumDb.size
        if (n >= 2) {
            val fMin = 20f
            val fMax = nyquist
            val logMin = ln(fMin)
            val logMax = ln(fMax)
            val freqs = FloatArray(targetBars + 1) { i ->
                exp(logMin + (logMax - logMin) * i / targetBars)
            }

            val binHz = nyquist / n
            val barGap = 2f
            for (b in 0 until targetBars) {
                val fStart = freqs[b]
                val fEnd = freqs[b + 1]
                val binStart = (fStart / binHz).toInt().coerceAtMost(n - 1)
                val binEnd = (fEnd / binHz).toInt().coerceAtMost(n - 1)
                if (binEnd <= binStart) continue

                // find max in range
                var v = -150f
                for (k in binStart until binEnd) v = max(v, spectrumDb[k])

                val xStart = paddingLeft + ((ln(fStart) - logMin) / (logMax - logMin)) * plotW
                val xEnd = paddingLeft + ((ln(fEnd) - logMin) / (logMax - logMin)) * plotW
                val barW = (xEnd - xStart - barGap).coerceAtLeast(1f)
                val yTop = paddingTop + mapDbToY(v, plotH, minDb, maxDb)

                // Gradient from bottom (quiet color) to top (hot color)
                val brush = Brush.verticalGradient(
                    colors = gradientColors,
                    startY = yTop,
                    endY = origin.y
                )

                drawRect(
                    brush = brush,
                    topLeft = Offset(xStart + barGap / 2f, yTop),
                    size = androidx.compose.ui.geometry.Size(barW, origin.y - yTop)
                )
                // subtle highlight at the bar peak
                drawLine(
                    color = barTopStroke,
                    start = Offset(xStart, yTop),
                    end = Offset(xEnd, yTop),
                    strokeWidth = 2f
                )
            }
        }

        // X ticks (log scale)
        val xTicks = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
            .filter { it <= nyquist }
        for (hz in xTicks) {
            val x = paddingLeft + ((ln(hz) - ln(20f)) / (ln(nyquist) - ln(20f))) * plotW
            drawLine(axisColor, Offset(x, origin.y), Offset(x, origin.y + 6f), 1.5f)
            val label = if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else "${hz.toInt()}"
            drawContext.canvas.nativeCanvas.drawText(label, x, origin.y + 20f, xTickPaint)
        }

        // Axis titles
        drawContext.canvas.nativeCanvas.drawText(
            "Frequency (Hz)",
            paddingLeft + plotW / 2,
            origin.y + 60f,
            titlePaint
        )
        drawContext.canvas.nativeCanvas.save()
        val yTitleCenterY = paddingTop + plotH / 2
        drawContext.canvas.nativeCanvas.rotate(-90f, yTitleLeft - 20, yTitleCenterY)
        drawContext.canvas.nativeCanvas.drawText("Amplitude (dBFS)", yTitleLeft, yTitleCenterY, titlePaint)
        drawContext.canvas.nativeCanvas.restore()
    }
}

private fun mapDbToY(db: Float, height: Float, minDb: Float, maxDb: Float): Float {
    val clamped = db.coerceIn(minDb, maxDb)
    val t = (clamped - minDb) / (maxDb - minDb) // 0..1
    return height * (1f - t)
}
