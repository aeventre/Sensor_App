package com.example.sensorapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

@Composable
fun LocationScreen(onBack: () -> Unit) = ScreenScaffold("Location", onBack) {
    val context = LocalContext.current

    /* ---- Permission ---- */
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    /* ---- Location state ---- */
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var acc by remember { mutableStateOf<Float?>(null) }
    var provider by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            lat = null; lon = null; acc = null; provider = null
        }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose { }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun updateFrom(loc: Location?) {
            if (loc != null) {
                lat = loc.latitude
                lon = loc.longitude
                acc = if (loc.hasAccuracy()) loc.accuracy else null
                provider = loc.provider
            }
        }

        @SuppressLint("MissingPermission")
        fun seedLastKnown() {
            val candidates = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var best: Location? = null
            for (p in candidates) {
                val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
                if (l != null) {
                    best = when {
                        best == null -> l
                        l.time > best!!.time -> l
                        else -> best
                    }
                }
            }
            updateFrom(best)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                updateFrom(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        @SuppressLint("MissingPermission")
        fun startUpdates() {
            try {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L, 2f, listener
                    )
                }
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L, 2f, listener
                    )
                }
            } catch (_: SecurityException) { }
        }

        seedLastKnown()
        startUpdates()

        onDispose {
            try { lm.removeUpdates(listener) } catch (_: Exception) {}
        }
    }

    /* ---- UI ---- */
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermission) {
            Text("Location access is required to show your position.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                Text("Grant Location Permission")
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                // --- STATIC WORLD MAP IMAGE ---
                Image(
                    painter = painterResource(id = R.drawable.earth),
                    contentDescription = "Earth map (NASA Blue Marble)",
                    modifier = Modifier.fillMaxSize()
                )

                // --- MARKER OVERLAY ---
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height

                    val latVal = lat
                    val lonVal = lon
                    if (latVal != null && lonVal != null) {
                        val x = ((lonVal + 180.0) / 360.0 * width).toFloat()
                        val y = ((90.0 - latVal) / 180.0 * height).toFloat()

                        val markerRadius = max(6f, width * 0.008f)
                        val accMeters = acc
                        if (accMeters != null) {
                            val degLat = accMeters / 111_320f
                            val pxPerDegLat = height / 180f
                            val accPx = (degLat * pxPerDegLat).coerceAtMost(min(width, height) * 0.45f)
                            drawCircle(
                                color = Color(0x663197F3),
                                radius = accPx,
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = Color(0xFF1976D2),
                                radius = accPx,
                                center = Offset(x, y),
                                style = Stroke(width = 2f)
                            )
                        }

                        drawCircle(
                            color = Color(0xFFFF3D00),
                            radius = markerRadius,
                            center = Offset(x, y)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = markerRadius * 0.45f,
                            center = Offset(x, y)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val latStr = lat?.let { String.format("%.5f", it) } ?: "—"
            val lonStr = lon?.let { String.format("%.5f", it) } ?: "—"
            val accStr = acc?.let { String.format("%.0f m", it) } ?: "—"
            val provStr = provider ?: "—"

            Text("Lat: $latStr   Lon: $lonStr", style = MaterialTheme.typography.titleMedium)
            Text("Accuracy: $accStr   Provider: $provStr", style = MaterialTheme.typography.labelMedium)

            Spacer(Modifier.height(10.dp))

            Text(
                "Image credit: NASA/Goddard Space Flight Center Scientific Visualization Studio\n" +
                        "The Blue Marble Next Generation data is courtesy of Reto Stockli (NASA/GSFC) and NASA's Earth Observatory.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )

        }
    }
}
