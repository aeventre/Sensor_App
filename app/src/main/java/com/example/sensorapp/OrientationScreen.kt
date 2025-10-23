package com.example.sensorapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.hardware.SensorManager.AXIS_X
import android.hardware.SensorManager.AXIS_Y
import android.hardware.SensorManager.AXIS_MINUS_X
import android.hardware.SensorManager.AXIS_MINUS_Y
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private enum class OrientationMode { ACCEL, GYRO }

@Composable
fun OrientationScreen(onBack: () -> Unit) = ScreenScaffold("Orientation", onBack) {
    val context = LocalContext.current

    /* ----------------------- Colors ---------------------------- */
    val colorX = Color(0xFFFF1744) // bright red
    val colorY = Color(0xFF00E676) // bright green
    val colorZ = Color(0xFF2979FF) // bright blue
    val colorM = Color(0xFFFFC400) // bright amber (magnitude)
    val axisColor = Color(0xFFD5D5D5) // medium gray for axes

    /* -------- Rotation (fused) quaternion for 3D cube ---------- */
    var qw by remember { mutableStateOf(1f) }
    var qx by remember { mutableStateOf(0f) }
    var qy by remember { mutableStateOf(0f) }
    var qz by remember { mutableStateOf(0f) }
    var refQw by remember { mutableStateOf(1f) }
    var refQx by remember { mutableStateOf(0f) }
    var refQy by remember { mutableStateOf(0f) }
    var refQz by remember { mutableStateOf(0f) }
    // Auto-zero once so it starts "pointing up" relative to the first reading
    var firstRefSet by remember { mutableStateOf(false) }

    /* ---------------- Accelerometer state ---------------------- */
    var ax by remember { mutableStateOf(0f) }
    var ay by remember { mutableStateOf(0f) }
    var az by remember { mutableStateOf(0f) }
    var aOffX by remember { mutableStateOf(0f) }
    var aOffY by remember { mutableStateOf(0f) }
    var aOffZ by remember { mutableStateOf(0f) }
    val NA = 160
    val aHistX = remember { FloatArray(NA) }
    val aHistY = remember { FloatArray(NA) }
    val aHistZ = remember { FloatArray(NA) }
    val aHistM = remember { FloatArray(NA) }
    var aRing by remember { mutableStateOf(0) }

    /* ------------------- Gyro state ---------------------------- */
    var gx by remember { mutableStateOf(0f) }
    var gy by remember { mutableStateOf(0f) }
    var gz by remember { mutableStateOf(0f) }
    var gOffX by remember { mutableStateOf(0f) }
    var gOffY by remember { mutableStateOf(0f) }
    var gOffZ by remember { mutableStateOf(0f) }
    val NG = 160
    val gHistX = remember { FloatArray(NG) }
    val gHistY = remember { FloatArray(NG) }
    val gHistZ = remember { FloatArray(NG) }
    val gHistM = remember { FloatArray(NG) }
    var gRing by remember { mutableStateOf(0) }

    /* ---------------- Quaternion helpers ----------------------- */
    fun qMul(a: FloatArray, b: FloatArray): FloatArray {
        val aw=a[0]; val ax=a[1]; val ay=a[2]; val az=a[3]
        val bw=b[0]; val bx=b[1]; val by=b[2]; val bz=b[3]
        return floatArrayOf(
            aw*bw - ax*bx - ay*by - az*bz,
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw
        )
    }
    fun qInv(q: FloatArray): FloatArray {
        val w=q[0]; val x=q[1]; val y=q[2]; val z=q[3]
        val s2 = w*w + x*x + y*y + z*z
        return if (s2 > 0f) floatArrayOf(w/s2, -x/s2, -y/s2, -z/s2) else floatArrayOf(1f,0f,0f,0f)
    }
    fun qNormalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        return if (n > 0f) floatArrayOf(q[0]/n, q[1]/n, q[2]/n, q[3]/n) else floatArrayOf(1f,0f,0f,0f)
    }
    fun qAlignHemisphere(a: FloatArray, b: FloatArray): FloatArray {
        val dot = a[0]*b[0] + a[1]*b[1] + a[2]*b[2] + a[3]*b[3]
        return if (dot < 0f) floatArrayOf(-b[0], -b[1], -b[2], -b[3]) else b
    }
    fun rotateVecByQ(v: FloatArray, q: FloatArray): FloatArray {
        val p = floatArrayOf(0f, v[0], v[1], v[2])
        val qi = qInv(q)
        val t = qMul(q, p)
        val r = qMul(t, qi)
        return floatArrayOf(r[1], r[2], r[3])
    }
    fun quatFromRotationMatrix(R: FloatArray): FloatArray {
        val m00 = R[0]; val m01 = R[1]; val m02 = R[2]
        val m10 = R[3]; val m11 = R[4]; val m12 = R[5]
        val m20 = R[6]; val m21 = R[7]; val m22 = R[8]
        val trace = m00 + m11 + m22
        val qw: Float; val qx: Float; val qy: Float; val qz: Float
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            qw = 0.25f * s
            qx = (m21 - m12) / s
            qy = (m02 - m20) / s
            qz = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            qw = (m21 - m12) / s
            qx = 0.25f * s
            qy = (m01 + m10) / s
            qz = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            qw = (m02 - m20) / s
            qx = (m01 + m10) / s
            qy = 0.25f * s
            qz = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            qw = (m10 - m01) / s
            qx = (m02 + m20) / s
            qy = (m12 + m21) / s
            qz = 0.25f * s
        }
        return qNormalize(floatArrayOf(qw, qx, qy, qz))
    }

    @Composable
    fun OrientationCube(qCur: FloatArray, qRef: FloatArray) {
        // Bigger, clipped canvas with inner inset so strokes don't touch edges
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)          // ⬅ more space
                .clipToBounds()
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            val insetPx = 10f
            val w = size.width - insetPx * 2
            val h = size.height - insetPx * 2
            val cx = insetPx + w / 2f
            val cy = insetPx + h / 2f

            // inv(ref) * cur, with hemisphere alignment
            val curN = qNormalize(qCur)
            val refN = qNormalize(qRef)
            val curAligned = qAlignHemisphere(refN, curN)
            val qRel = qMul(qInv(refN), curAligned)
            val q = qNormalize(qRel)

            val f = w * 0.9f
            val zCam = 4f
            fun project(p: FloatArray): Offset {
                val pr = rotateVecByQ(p, q)
                val z = pr[2] + zCam
                return Offset((pr[0]*f)/z + cx, (-pr[1]*f)/z + cy) // Y up
            }

            val sX=0.8f; val sY=1.6f; val sZ=0.08f
            val verts = arrayOf(
                floatArrayOf(-sX,-sY,-sZ), floatArrayOf(sX,-sY,-sZ),
                floatArrayOf(sX,sY,-sZ),   floatArrayOf(-sX,sY,-sZ),
                floatArrayOf(-sX,-sY,sZ),  floatArrayOf(sX,-sY,sZ),
                floatArrayOf(sX,sY,sZ),    floatArrayOf(-sX,sY,sZ)
            )
            val edges = arrayOf(0 to 1,1 to 2,2 to 3,3 to 0, 4 to 5,5 to 6,6 to 7,7 to 4, 0 to 4,1 to 5,2 to 6,3 to 7)
            val pts = verts.map { project(it) }
            for ((a,b) in edges) drawLine(axisColor, pts[a], pts[b], 3f)

            val p0 = project(floatArrayOf(0f,0f,0f))
            val px = project(floatArrayOf(1.2f,0f,0f))
            val py = project(floatArrayOf(0f,1.2f,0f))
            val pz = project(floatArrayOf(0f,0f,1.2f))
            drawLine(colorX, p0, px, 5f)
            drawLine(colorY, p0, py, 5f)
            drawLine(colorZ, p0, pz, 5f)
        }
    }

    /* -------------------- Mode state ---------------------------- */
    var mode by remember { mutableStateOf(OrientationMode.ACCEL) }

    /* ---------------- Calibrate / zero -------------------------- */
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val ref = qNormalize(floatArrayOf(qw, qx, qy, qz))
            val aligned = qAlignHemisphere(ref, ref)
            refQw = aligned[0]; refQx = aligned[1]; refQy = aligned[2]; refQz = aligned[3]
            firstRefSet = true
            when (mode) {
                OrientationMode.ACCEL -> { aOffX = ax; aOffY = ay; aOffZ = az }
                OrientationMode.GYRO  -> { gOffX = gx; gOffY = gy; gOffZ = gz }
            }
        }) { Text("Calibrate (Zero)") }
    }
    Spacer(Modifier.height(12.dp))

    /* -------------------- 3D cube ------------------------------- */
    OrientationCube(qCur = floatArrayOf(qw,qx,qy,qz), qRef = floatArrayOf(refQw,refQx,refQy,refQz))
    Spacer(Modifier.height(16.dp))

    /* -------------------- DROPDOWN (below) ---------------------- */
    ModeDropdown(
        mode = mode,
        onChange = { mode = it },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    /* --------------- Sensors: rotation, accel, gyro ------------- */
    DisposableEffect(Unit) {
        val mgr = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rot = mgr.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: mgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = mgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro  = mgr.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        val rvListener = object : SensorEventListener {
            private val Rraw = FloatArray(9)
            private val Rremap = FloatArray(9)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(Rraw, e.values)

                // Remap to display rotation AND flip X & Y signs
                val r = context.display?.rotation ?: Surface.ROTATION_0
                when (r) {
                    Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(Rraw, AXIS_MINUS_X, AXIS_MINUS_Y, Rremap)
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(Rraw, AXIS_MINUS_Y, AXIS_X, Rremap)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(Rraw, AXIS_X, AXIS_Y, Rremap)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(Rraw, AXIS_Y, AXIS_MINUS_X, Rremap)
                    else -> System.arraycopy(Rraw, 0, Rremap, 0, 9)
                }
                val q = quatFromRotationMatrix(Rremap)
                qw = q[0]; qx = q[1]; qy = q[2]; qz = q[3]

                // Auto-zero once on first reading so default is "phone pointing up"
                if (!firstRefSet) {
                    val ref = qNormalize(floatArrayOf(qw, qx, qy, qz))
                    val aligned = qAlignHemisphere(ref, ref)
                    refQw = aligned[0]; refQx = aligned[1]; refQy = aligned[2]; refQz = aligned[3]
                    firstRefSet = true
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val aListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                ax = e.values[0]; ay = e.values[1]; az = e.values[2]
                val x = -(ax - aOffX)
                val y = -(ay - aOffY)
                val z =  (az - aOffZ)
                aHistX[aRing] = x; aHistY[aRing] = y; aHistZ[aRing] = z
                aHistM[aRing] = sqrt(x*x + y*y + z*z)
                aRing = (aRing + 1) % NA
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val gListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                gx = e.values[0]; gy = e.values[1]; gz = e.values[2]
                val x = -(gx - gOffX)
                val y = -(gy - gOffY)
                val z =  (gz - gOffZ)
                gHistX[gRing] = x; gHistY[gRing] = y; gHistZ[gRing] = z
                gHistM[gRing] = sqrt(x*x + y*y + z*z)
                gRing = (gRing + 1) % NG
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rot != null) mgr.registerListener(rvListener, rot, SensorManager.SENSOR_DELAY_GAME)
        if (accel != null) mgr.registerListener(aListener, accel, SensorManager.SENSOR_DELAY_UI)
        if (gyro != null)  mgr.registerListener(gListener,  gyro,  SensorManager.SENSOR_DELAY_GAME)

        onDispose {
            mgr.unregisterListener(rvListener)
            mgr.unregisterListener(aListener)
            mgr.unregisterListener(gListener)
        }
    }

    /* ------------ Multi-series chart helper (one plot) ---------- */
    fun DrawScope.drawMultiWithAxes(
        seriesList: List<FloatArray>,
        colors: List<Color>,
        currentIndex: Int,
        axis: Color
    ) {
        val w = size.width
        val h = size.height

        // global min/max across all series
        var gMin = 0f
        var gMax = 0f
        var first = true
        for (arr in seriesList) {
            if (arr.isEmpty()) continue
            val mn = arr.minOrNull() ?: 0f
            val mx = arr.maxOrNull() ?: 0f
            if (first) { gMin = mn; gMax = mx; first = false } else {
                gMin = min(gMin, mn); gMax = max(gMax, mx)
            }
        }
        val minV = min(0f, gMin)
        val maxV = max(0f, gMax)
        val span = (maxV - minV).let { if (it > 1e-6f) it else 1f }

        // Axes box + zero line
        drawLine(axis, Offset(0f, 0f), Offset(0f, h), 2f)
        drawLine(axis, Offset(0f, h), Offset(w, h), 2f)
        if (0f in minV..maxV) {
            val y0 = h - ((0f - minV) / span) * h
            drawLine(axis, Offset(0f, y0), Offset(w, y0), 1.5f)
        }

        // Draw each series
        val count = (seriesList.firstOrNull()?.size ?: 0).coerceAtLeast(1)
        val dx = w / (count - 1).coerceAtLeast(1)
        val startIdx = (currentIndex + 1) % count

        seriesList.forEachIndexed { idx, series ->
            if (series.isEmpty()) return@forEachIndexed
            var lastX = 0f
            var lastY = h - ((series[startIdx] - minV) / span) * h
            for (i in 1 until count) {
                val si = (startIdx + i) % count
                val xPos = i * dx
                val yPos = h - ((series[si] - minV) / span) * h
                drawLine(colors[idx], Offset(lastX, lastY), Offset(xPos, yPos), 4f)
                lastX = xPos; lastY = yPos
            }
        }
    }

    /* --------------- Mode-specific UI (one plot) --------------- */
    when (mode) {
        OrientationMode.ACCEL -> {
            val axNow = -(ax - aOffX)
            val ayNow = -(ay - aOffY)
            val azNow =  (az - aOffZ)
            val amag = sqrt(axNow*axNow + ayNow*ayNow + azNow*azNow)

            Text("Accelerometer", style = MaterialTheme.typography.titleMedium)
            Text("X = %.2f,  Y = %.2f,  Z = %.2f  (m/s²)".format(axNow, ayNow, azNow))
            Text("‖a‖ = %.2f m/s²".format(amag))
            Spacer(Modifier.height(8.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clipToBounds()
            ) {
                drawMultiWithAxes(
                    seriesList = listOf(aHistX, aHistY, aHistZ, aHistM),
                    colors = listOf(colorX, colorY, colorZ, colorM),
                    currentIndex = aRing,
                    axis = axisColor
                )
            }
            LegendRow(
                items = listOf("X" to colorX, "Y" to colorY, "Z" to colorZ, "‖a‖" to colorM),
                unit = "m/s²"
            )
        }
        OrientationMode.GYRO -> {
            val gxNow = -(gx - gOffX)
            val gyNow = -(gy - gOffY)
            val gzNow =  (gz - gOffZ)
            val omega = sqrt(gxNow*gxNow + gyNow*gyNow + gzNow*gzNow)

            Text("Gyroscope", style = MaterialTheme.typography.titleMedium)
            Text("X = %.2f,  Y = %.2f,  Z = %.2f  (rad/s)".format(gxNow, gyNow, gzNow))
            Text("‖ω‖ = %.2f rad/s".format(omega))
            Spacer(Modifier.height(8.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clipToBounds()
            ) {
                drawMultiWithAxes(
                    seriesList = listOf(gHistX, gHistY, gHistZ, gHistM),
                    colors = listOf(colorX, colorY, colorZ, colorM),
                    currentIndex = gRing,
                    axis = axisColor
                )
            }
            LegendRow(
                items = listOf("X" to colorX, "Y" to colorY, "Z" to colorZ, "‖ω‖" to colorM),
                unit = "rad/s"
            )
        }
    }
}

/* -------------------- Mode Dropdown ---------------------------- */
@Composable
private fun ModeDropdown(
    mode: OrientationMode,
    onChange: (OrientationMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val modeLabel = when (mode) {
        OrientationMode.ACCEL -> "Accelerometer"
        OrientationMode.GYRO  -> "Gyro"
    }

    Box(modifier) {
        // Entire text field is tappable
        OutlinedTextField(
            value = modeLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Source") },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Accelerometer") },
                onClick = {
                    onChange(OrientationMode.ACCEL)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Gyro") },
                onClick = {
                    onChange(OrientationMode.GYRO)
                    expanded = false
                }
            )
        }
    }
}

/* ---------------------- Legend Row ----------------------------- */
@Composable
private fun LegendRow(
    items: List<Pair<String, Color>>,
    unit: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (label, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp, 12.dp)
                            .background(color, shape = MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
