package com.example.sensorapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.sensorapp.ui.theme.SensorAppTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextAlign

/* -------------------------- ROUTES & MODEL -------------------------- */

private object Routes {
    const val HOME = "home"
    const val SENSOR = "sensor/{screenId}"
    const val ACK = "ack"
}

sealed class SensorScreen(val id: String, val title: String) {
    data object Orientation      : SensorScreen("orientation", "Orientation")
    data object Magnetometer     : SensorScreen("magnetometer", "Magnetometer")
    data object Pressure         : SensorScreen("pressure", "Barometer")
    data object LightMeter       : SensorScreen("light", "Light Meter")
    data object MicrophoneVolume : SensorScreen("mic_volume", "Microphone")
    data object Location         : SensorScreen("location", "GPS")

    companion object {
        val all: List<SensorScreen> = listOf(
            Orientation, Magnetometer, Pressure, LightMeter, MicrophoneVolume, Location
        )
        fun byId(id: String): SensorScreen = all.first { it.id == id }
    }
}

private data class RowItem(val title: String, val screenId: String)
private val AllRows = SensorScreen.all.map { RowItem(it.title, it.id) }

/* ------------------------------ ACTIVITY ------------------------------- */

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SensorApp() }
    }
}

/* ------------------------------- APP UI -------------------------------- */

@Composable
private fun SensorApp() {
    val nav = rememberNavController()
    SensorAppTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {}
            }
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        items = AllRows,
                        onOpen = { screenId -> nav.navigate("sensor/$screenId") },
                        onAbout = { nav.navigate(Routes.ACK) }
                    )
                }
                composable(
                    route = Routes.SENSOR,
                    arguments = listOf(navArgument("screenId") { type = NavType.StringType })
                ) { backStack ->
                    val screenId = backStack.arguments!!.getString("screenId")!!
                    val screen = SensorScreen.byId(screenId)
                    SensorScreenDispatcher(
                        screen = screen,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(Routes.ACK) {
                    AcknowledgmentsScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    items: List<RowItem>,
    onOpen: (screenId: String) -> Unit,
    onAbout: () -> Unit = {}
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.logo),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .size(60.dp)
                        .padding(end = 8.dp)
                )
                Column {
                    Text("Sensor App", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("by Alec Ventresca", style = MaterialTheme.typography.titleSmall)
                }
            }
            TextButton(onClick = onAbout) { Text("Acknowledgments") }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { _, item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item.screenId) }
                        .padding(12.dp)
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
    }
}


@Composable
private fun SensorScreenDispatcher(
    screen: SensorScreen,
    onBack: () -> Unit
) {
    when (screen) {
        SensorScreen.Orientation      -> OrientationScreen(onBack)
        SensorScreen.Magnetometer     -> MagnetometerScreen(onBack)
        SensorScreen.Pressure         -> PressureScreen(onBack)
        SensorScreen.LightMeter       -> LightMeterScreen(onBack)      // <-- updated
        SensorScreen.MicrophoneVolume -> MicrophoneVolumeScreen(onBack)
        SensorScreen.Location         -> LocationScreen(onBack)
    }
}


@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomePreview() {
    MaterialTheme { HomeScreen(items = AllRows, onOpen = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun AckPreview() {
    MaterialTheme { AcknowledgmentsScreen(onBack = {}) }
}


@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    showBackButton: Boolean = false, // keep off unless you want it
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (showBackButton) {
            Button(onClick = onBack) { Text("Back") }
            Spacer(Modifier.height(12.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun AcknowledgmentsScreen(onBack: () -> Unit) {
    ScreenScaffold("Acknowledgments", onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text("Tools & Frameworks", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Android SensorManager for accelerometer, gyroscope, magnetometer, and pressure sensors\n" +
                        "• AudioRecord API for microphone input\n" +
                        "• LocationManager for GPS and network-based location\n" +
                        "• Jetpack Compose and Material 3 for the UI\n" +
                        "• Navigation Compose for screen transitions\n" +
                        "• Kotlin standard library and Android SDK utilities",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("Images & Data", style = MaterialTheme.typography.titleMedium)
            Text(
                "The static world map image (earth.jpg) is used for location visualization.\n\n" +
                        "Image credit: NASA/Goddard Space Flight Center Scientific Visualization Studio.\n" +
                        "The Blue Marble Next Generation data is courtesy of Reto Stockli (NASA/GSFC) and NASA’s Earth Observatory.",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("Open Source Libraries", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Fast Fourier Transform implementation adapted from jnalon’s open-source repository:\n" +
                        "  https://github.com/jnalon/fast-fourier-transform",
                style = MaterialTheme.typography.bodyMedium
            )

            Text("References", style = MaterialTheme.typography.titleMedium)
            Text(
                "• Official Android documentation: https://developer.android.com/docs\n" +
                        "• Jetpack Compose documentation: https://developer.android.com/jetpack/compose\n" +
                        "• Material Design 3 components: https://m3.material.io\n" +
                        "• SensorManager API reference: https://developer.android.com/reference/android/hardware/SensorManager\n" +
                        "• LocationManager API reference: https://developer.android.com/reference/android/location/LocationManager\n" +
                        "• AudioRecord class reference: https://developer.android.com/reference/android/media/AudioRecord\n" +
                        "• Kotlin language reference: https://kotlinlang.org/docs/reference/\n" +
                        "• Compose Canvas & Drawing: https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/drawscope/DrawScope",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

