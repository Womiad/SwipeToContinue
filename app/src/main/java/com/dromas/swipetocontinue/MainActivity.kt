package com.dromas.swipetocontinue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dromas.swipetocontinue.ui.theme.SwipeToContinueTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwipeToContinueTheme {
                StepGateApp()
            }
        }
    }
}

@Composable
fun StepGateApp() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasActivityRecognitionPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    val stepState = rememberStepCounterState(enabled = hasPermission)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        StepGateScreen(
            hasPermission = hasPermission,
            stepState = stepState,
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun rememberStepCounterState(enabled: Boolean): StepCounterState {
    val context = LocalContext.current
    var baselineSteps by remember { mutableFloatStateOf(-1f) }
    var sessionSteps by remember { mutableIntStateOf(0) }
    var sensorAvailable by remember { mutableStateOf(true) }

    DisposableEffect(enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

            if (stepCounter == null) {
                sensorAvailable = false
                onDispose { }
            } else {
                sensorAvailable = true
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val totalStepsSinceReboot = event.values.firstOrNull() ?: return
                        if (baselineSteps < 0f) {
                            baselineSteps = totalStepsSinceReboot
                        }
                        sessionSteps = (totalStepsSinceReboot - baselineSteps).toInt().coerceAtLeast(0)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                sensorManager.registerListener(
                    listener,
                    stepCounter,
                    SensorManager.SENSOR_DELAY_NORMAL
                )

                onDispose {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
    }

    return StepCounterState(
        steps = sessionSteps,
        sensorAvailable = sensorAvailable
    )
}

@Composable
private fun StepGateScreen(
    hasPermission: Boolean,
    stepState: StepCounterState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stepsPerSwipe = 20
    var watchedCount by remember { mutableIntStateOf(0) }
    val earnedSwipes = stepState.steps / stepsPerSwipe
    val availableSwipes = (earnedSwipes - watchedCount).coerceAtLeast(0)
    val stepsTowardNextSwipe = stepState.steps % stepsPerSwipe
    val progress = stepsTowardNextSwipe / stepsPerSwipe.toFloat()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Swipe to Continue",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "走路換下一支短影音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))

            VideoPlaceholder(
                locked = availableSwipes == 0,
                watchedCount = watchedCount
            )

            Spacer(modifier = Modifier.height(24.dp))

            StepStats(
                steps = stepState.steps,
                stepsPerSwipe = stepsPerSwipe,
                availableSwipes = availableSwipes,
                progress = progress
            )

            Spacer(modifier = Modifier.height(20.dp))

            when {
                !hasPermission -> {
                    Text(
                        text = "需要允許身體活動權限才能偵測步數。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) {
                        Text("允許步數偵測")
                    }
                }

                !stepState.sensorAvailable -> {
                    Text(
                        text = "這台裝置沒有支援硬體步數感測器。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                else -> {
                    Button(
                        onClick = { watchedCount += 1 },
                        enabled = availableSwipes > 0,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (availableSwipes > 0) "滑下一支" else "再走 ${stepsPerSwipe - stepsTowardNextSwipe} 步")
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlaceholder(locked: Boolean, watchedCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (locked) Color(0xFF1B1B1F) else Color(0xFF173D35)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (locked) Color(0xFF44444A) else Color(0xFF58D39A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (locked) "LOCK" else "PLAY",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (locked) "走路後才能繼續" else "第 ${watchedCount + 1} 支短影音",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StepStats(
    steps: Int,
    stepsPerSwipe: Int,
    availableSwipes: Int,
    progress: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatLabel(label = "本次步數", value = steps.toString())
            StatLabel(label = "可滑次數", value = availableSwipes.toString())
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Text(
            text = "每 $stepsPerSwipe 步解鎖 1 次滑動",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class StepCounterState(
    val steps: Int,
    val sensorAvailable: Boolean
)

private fun hasActivityRecognitionPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
}

@Preview(showBackground = true)
@Composable
fun StepGateScreenPreview() {
    SwipeToContinueTheme {
        StepGateScreen(
            hasPermission = true,
            stepState = StepCounterState(steps = 37, sensorAvailable = true),
            onRequestPermission = {}
        )
    }
}
