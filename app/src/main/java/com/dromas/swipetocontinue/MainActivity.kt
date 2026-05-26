package com.dromas.swipetocontinue

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dromas.swipetocontinue.ui.theme.SwipeToContinueTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sqrt

private const val LINEAR_ACCEL_MOVING_THRESHOLD = 20f
private const val LINEAR_ACCEL_POINT_THRESHOLD = 30f
private const val ACCEL_DELTA_MOVING_THRESHOLD = 20f
private const val ACCEL_DELTA_POINT_THRESHOLD = 30f
private const val GYRO_MOVING_THRESHOLD = 20.0f
private const val GYRO_POINT_THRESHOLD = 48.0f

private const val POINTS_PER_SWIPE = 30

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

    val motionState = rememberMotionCounterState(stepPermissionGranted = hasPermission)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        StepGateScreen(
            hasPermission = hasPermission,
            motionState = motionState,
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
private fun rememberMotionCounterState(stepPermissionGranted: Boolean): MotionCounterState {
    val context = LocalContext.current
    var baselineSteps by remember { mutableFloatStateOf(-1f) }
    var motionPoints by remember { mutableIntStateOf(0) }
    var sensorAvailable by remember { mutableStateOf(true) }
    var isMoving by remember { mutableStateOf(false) }
    var lastMovementAtMillis by remember { mutableLongStateOf(0L) }
    var lastMotionPointAtMillis by remember { mutableLongStateOf(0L) }
    var lastAccelerationMagnitude by remember { mutableFloatStateOf(0f) }
    var motionLevel by remember { mutableFloatStateOf(0f) }
    var sensorMode by remember { mutableStateOf("動作偵測") }

    LaunchedEffect(lastMovementAtMillis) {
        if (lastMovementAtMillis > 0L) {
            isMoving = true
            delay(1_800)
            if (System.currentTimeMillis() - lastMovementAtMillis >= 1_800) {
                isMoving = false
            }
        }
    }

    DisposableEffect(stepPermissionGranted) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepDetector = if (stepPermissionGranted) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        } else {
            null
        }
        val stepCounter = if (stepPermissionGranted) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        } else {
            null
        }
        val motionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sensors = listOfNotNull(stepDetector, stepCounter, motionSensor, gyroscope)

        if (sensors.isEmpty()) {
            sensorAvailable = false
            onDispose { }
        } else {
            sensorAvailable = true
            sensorMode = buildSensorMode(
                hasStepSensor = stepDetector != null || stepCounter != null,
                hasMotionSensor = motionSensor != null,
                hasGyroscope = gyroscope != null
            )

            fun addMotionPoint(now: Long, minGapMillis: Long = 260L) {
                if (now - lastMotionPointAtMillis >= minGapMillis) {
                    motionPoints += 1
                    lastMotionPointAtMillis = now
                    lastMovementAtMillis = now
                }
            }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val now = System.currentTimeMillis()

                    when (event.sensor.type) {
                        Sensor.TYPE_STEP_DETECTOR -> {
                            addMotionPoint(now, minGapMillis = 220L)
                        }

                        Sensor.TYPE_STEP_COUNTER -> {
                            val totalStepsSinceReboot = event.values.firstOrNull() ?: return
                            if (baselineSteps < 0f) {
                                baselineSteps = totalStepsSinceReboot
                            }

                            val currentSteps = (totalStepsSinceReboot - baselineSteps)
                                .toInt()
                                .coerceAtLeast(0)
                            if (currentSteps > motionPoints) {
                                repeat(currentSteps - motionPoints) {
                                    addMotionPoint(now, minGapMillis = 0L)
                                }
                            }
                        }

                        Sensor.TYPE_LINEAR_ACCELERATION -> {
                            val magnitude = magnitudeOf(event.values)
                            motionLevel = magnitude.coerceIn(0f, 8f) / 8f
                            if (magnitude > LINEAR_ACCEL_POINT_THRESHOLD) {
                                addMotionPoint(now)
                            } else if (magnitude > LINEAR_ACCEL_MOVING_THRESHOLD) {
                                lastMovementAtMillis = now
                            }
                        }

                        Sensor.TYPE_ACCELEROMETER -> {
                            val magnitude = magnitudeOf(event.values)
                            val delta = abs(magnitude - lastAccelerationMagnitude)
                            lastAccelerationMagnitude = magnitude
                            motionLevel = delta.coerceIn(0f, 6f) / 6f
                            if (delta > ACCEL_DELTA_POINT_THRESHOLD) {
                                addMotionPoint(now)
                            } else if (delta > ACCEL_DELTA_MOVING_THRESHOLD) {
                                lastMovementAtMillis = now
                            }
                        }

                        Sensor.TYPE_GYROSCOPE -> {
                            val rotationRate = magnitudeOf(event.values)
                            motionLevel = rotationRate.coerceIn(0f, 5f) / 5f
                            if (rotationRate > GYRO_POINT_THRESHOLD) {
                                addMotionPoint(now, minGapMillis = 340L)
                            } else if (rotationRate > GYRO_MOVING_THRESHOLD) {
                                motionLevel = rotationRate.coerceIn(0f, 5f) / 5f
                                lastMovementAtMillis = now
                            }
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensors.forEach { sensor ->
                sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }

            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return MotionCounterState(
        points = motionPoints,
        sensorAvailable = sensorAvailable,
        isMoving = isMoving,
        motionLevel = motionLevel,
        sensorMode = sensorMode
    )
}

@Composable
private fun StepGateScreen(
    hasPermission: Boolean,
    motionState: MotionCounterState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pointsPerSwipe = POINTS_PER_SWIPE
    val videos = remember { loadAssetVideos(context).shuffled() }
    var currentVideoIndex by remember { mutableIntStateOf(0) }
    var pointsAtCurrentVideoStart by remember { mutableIntStateOf(motionState.points) }
    val pointsSinceCurrentVideoStart = (motionState.points - pointsAtCurrentVideoStart)
        .coerceAtLeast(0)
    val hasSwipeReady = pointsSinceCurrentVideoStart >= pointsPerSwipe
    val availableSwipes = if (hasSwipeReady) 1 else 0
    val pointsTowardNextSwipe = pointsSinceCurrentVideoStart.coerceAtMost(pointsPerSwipe)
    val progress = pointsTowardNextSwipe / pointsPerSwipe.toFloat()
    val canSwipeToNext = motionState.isMoving && availableSwipes > 0
    var verticalDragAmount by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Swipe to Continue",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "動起來才能滑短影音",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            MotionGateProgressBanner(
                isMoving = motionState.isMoving,
                availableSwipes = availableSwipes,
                progress = progress,
                pointsInCurrentRound = pointsTowardNextSwipe,
                pointsPerSwipe = pointsPerSwipe
            )

            Spacer(modifier = Modifier.height(16.dp))

            val currentVideo = videos.getOrNull(currentVideoIndex % videos.size.coerceAtLeast(1))
            if (currentVideo == null) {
                EmptyVideoLibrary(modifier = Modifier.fillMaxWidth())
            } else {
                VideoPager(
                    video = currentVideo,
                    videoNumber = currentVideoIndex + 1,
                    locked = !canSwipeToNext,
                    isMoving = motionState.isMoving,
                    availableSwipes = availableSwipes,
                    modifier = Modifier.pointerInput(canSwipeToNext, currentVideoIndex) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                verticalDragAmount = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                verticalDragAmount += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                if (verticalDragAmount < -90f && canSwipeToNext) {
                                    currentVideoIndex += 1
                                    pointsAtCurrentVideoStart = motionState.points
                                }
                                verticalDragAmount = 0f
                            },
                            onDragCancel = {
                                verticalDragAmount = 0f
                            }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("允許步數輔助偵測")
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!motionState.sensorAvailable) {
                Text(
                    text = "這台裝置沒有可用的動作感測器。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                SwipeHint(
                    canSwipeToNext = canSwipeToNext,
                    isMoving = motionState.isMoving,
                    pointsNeeded = pointsPerSwipe - pointsTowardNextSwipe
                )
            }
        }
    }
}

@Composable
private fun MotionGateProgressBanner(
    isMoving: Boolean,
    availableSwipes: Int,
    progress: Float,
    pointsInCurrentRound: Int,
    pointsPerSwipe: Int
) {
    val isUnlocked = availableSwipes > 0
    val displayedProgress = if (isUnlocked) 1f else progress.coerceIn(0f, 1f)
    val fillColor = if (isMoving) Color(0xFF1E8E4D) else Color(0xFF3A3A40)
    val backgroundColor = if (isMoving) Color(0xFF143F29) else Color(0xFF202024)
    val title = when {
        isMoving && isUnlocked -> "已滿，可以上滑"
        isMoving -> "有在動"
        else -> "沒在動"
    }
    val subtitle = when {
        isMoving && isUnlocked -> "上滑切換下一支"
        isMoving -> "$pointsInCurrentRound / $pointsPerSwipe"
        isUnlocked -> "已集滿，動起來即可上滑"
        else -> "動起來累積進度"
    }
    val valueText = if (isUnlocked) "$pointsPerSwipe / $pointsPerSwipe" else "$pointsInCurrentRound / $pointsPerSwipe"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .height(118.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(displayedProgress)
                .height(118.dp)
                .background(fillColor)
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isMoving) Color(0xFFC8F7D5) else Color(0xFF8B8B91))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "目前狀態",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.82f)
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.92f)
            )
        }
    }
}

@Composable
private fun VideoPager(
    video: AssetVideo,
    videoNumber: Int,
    locked: Boolean,
    isMoving: Boolean,
    availableSwipes: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = video,
            transitionSpec = {
                (
                    slideInVertically(
                        animationSpec = tween(420),
                        initialOffsetY = { fullHeight -> fullHeight }
                    ) + fadeIn(animationSpec = tween(220))
                ).togetherWith(
                    slideOutVertically(
                        animationSpec = tween(420),
                        targetOffsetY = { fullHeight -> -fullHeight }
                    ) + fadeOut(animationSpec = tween(180))
                ).using(SizeTransform(clip = true))
            },
            label = "video-swipe-animation"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AssetVideoPlayer(
                    video = it,
                    shouldPlay = isMoving,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.52f))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "第 $videoNumber 支",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            text = when {
                !isMoving -> "動起來後上滑"
                availableSwipes <= 0 -> "累積動作點數"
                else -> "上滑切換下一支"
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AssetVideoPlayer(
    video: AssetVideo,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier
) {
    var playerView by remember { mutableStateOf<AssetVideoTextureView?>(null) }

    AndroidView(
        factory = { context ->
            AssetVideoTextureView(context).also { view ->
                playerView = view
                view.configure(video.assetPath, shouldPlay)
            }
        },
        update = { view ->
            playerView = view
            view.configure(video.assetPath, shouldPlay)
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            playerView?.releasePlayer()
        }
    }
}

@Composable
private fun EmptyVideoLibrary(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "找不到 assets/videos 裡的 mp4",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SwipeHint(
    canSwipeToNext: Boolean,
    isMoving: Boolean,
    pointsNeeded: Int
) {
    val text = when {
        canSwipeToNext -> "上滑影片區切換下一支"
        !isMoving -> "目前沒在動，動起來後才能上滑"
        else -> "再動 $pointsNeeded 點後可以上滑"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (canSwipeToNext) Color(0xFFE0F4E8) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (canSwipeToNext) Color(0xFF155C35) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class AssetVideo(
    val fileName: String,
    val assetPath: String
)

private fun loadAssetVideos(context: Context): List<AssetVideo> {
    return context.assets.list("videos")
        ?.filter { it.endsWith(".mp4", ignoreCase = true) }
        ?.sorted()
        ?.map { fileName -> AssetVideo(fileName = fileName, assetPath = "videos/$fileName") }
        .orEmpty()
}

private class AssetVideoTextureView(context: Context) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var mediaPlayer: MediaPlayer? = null
    private var currentAssetPath: String? = null
    private var pendingAssetPath: String? = null
    private var playbackSurface: android.view.Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var playerPrepared = false
    private var shouldPlay = false

    init {
        surfaceTextureListener = this
    }

    fun configure(assetPath: String, shouldPlay: Boolean) {
        this.shouldPlay = shouldPlay
        if (assetPath == currentAssetPath) {
            updatePlaybackState()
            return
        }
        pendingAssetPath = assetPath
        if (isAvailable) {
            startPendingVideo()
        }
    }

    fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        playbackSurface?.release()
        playbackSurface = null
        currentAssetPath = null
        playerPrepared = false
        videoWidth = 0
        videoHeight = 0
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startPendingVideo()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyFitCenterTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun updatePlaybackState() {
        val player = mediaPlayer ?: return
        if (!playerPrepared) return
        if (shouldPlay) {
            if (!player.isPlaying) {
                player.start()
            }
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    private fun startPendingVideo() {
        val assetPath = pendingAssetPath ?: return
        val texture = surfaceTexture ?: return
        releasePlayer()

        val descriptor = context.assets.openFd(assetPath)
        playbackSurface = android.view.Surface(texture)
        mediaPlayer = MediaPlayer().apply {
            setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            setSurface(playbackSurface)
            isLooping = true
            setOnVideoSizeChangedListener { _, width, height ->
                this@AssetVideoTextureView.videoWidth = width
                this@AssetVideoTextureView.videoHeight = height
                applyFitCenterTransform()
            }
            setOnPreparedListener { player ->
                playerPrepared = true
                this@AssetVideoTextureView.videoWidth = player.videoWidth
                this@AssetVideoTextureView.videoHeight = player.videoHeight
                applyFitCenterTransform()
                updatePlaybackState()
            }
            setOnErrorListener { _, _, _ ->
                true
            }
            prepareAsync()
        }

        currentAssetPath = assetPath
        pendingAssetPath = null
    }

    private fun applyFitCenterTransform() {
        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) return

        val viewAspectRatio = width.toFloat() / height.toFloat()
        val videoAspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float

        if (videoAspectRatio > viewAspectRatio) {
            scaleX = 1f
            scaleY = viewAspectRatio / videoAspectRatio
        } else {
            scaleX = videoAspectRatio / viewAspectRatio
            scaleY = 1f
        }

        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, width / 2f, height / 2f)
            }
        )
    }
}


@Composable
private fun MotionStats(
    points: Int,
    pointsPerSwipe: Int,
    availableSwipes: Int,
    progress: Float,
    isMoving: Boolean,
    motionLevel: Float,
    sensorMode: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatLabel(label = "動作點數", value = points.toString())
            StatLabel(label = "可滑次數", value = availableSwipes.toString())
        }
        MotionPointProgress(
            points = points,
            pointsPerSwipe = pointsPerSwipe,
            availableSwipes = availableSwipes,
            progress = progress
        )
        MovementStatus(
            isMoving = isMoving,
            motionLevel = motionLevel,
            sensorMode = sensorMode
        )
    }
}

@Composable
private fun MotionPointProgress(
    points: Int,
    pointsPerSwipe: Int,
    availableSwipes: Int,
    progress: Float
) {
    val pointsInCurrentRound = points % pointsPerSwipe
    val isUnlocked = availableSwipes > 0
    val displayedProgress = if (isUnlocked) 1f else progress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isUnlocked) "已解鎖，可上滑" else "距離下一次上滑",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isUnlocked) "$pointsPerSwipe / $pointsPerSwipe" else "$pointsInCurrentRound / $pointsPerSwipe",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isUnlocked) Color(0xFF1E8E4D) else MaterialTheme.colorScheme.onSurface
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFD0D0D4))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayedProgress.coerceIn(0f, 1f))
                    .height(18.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isUnlocked) Color(0xFF1E8E4D) else Color(0xFF235789))
            )
        }
        Text(
            text = "每 $pointsPerSwipe 點動作解鎖 1 次滑動",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MovementStatus(
    isMoving: Boolean,
    motionLevel: Float,
    sensorMode: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isMoving) Color(0xFFE0F4E8) else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isMoving) Color(0xFF1E8E4D) else Color(0xFF8B8B91))
            )
            Column {
                Text(
                    text = if (isMoving) "正在動作" else "等待動作",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isMoving) Color(0xFF155C35) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = sensorMode,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LinearProgressIndicator(
            progress = { motionLevel.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(8.dp))
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

private data class MotionCounterState(
    val points: Int,
    val sensorAvailable: Boolean,
    val isMoving: Boolean,
    val motionLevel: Float,
    val sensorMode: String
)

private fun hasActivityRecognitionPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
}

private fun magnitudeOf(values: FloatArray): Float {
    val x = values.getOrNull(0) ?: 0f
    val y = values.getOrNull(1) ?: 0f
    val z = values.getOrNull(2) ?: 0f
    return sqrt(x * x + y * y + z * z)
}

private fun buildSensorMode(
    hasStepSensor: Boolean,
    hasMotionSensor: Boolean,
    hasGyroscope: Boolean
): String {
    return when {
        hasMotionSensor && hasGyroscope && hasStepSensor -> "動作偵測＋陀螺儀＋步數"
        hasMotionSensor && hasGyroscope -> "動作偵測＋陀螺儀"
        hasMotionSensor && hasStepSensor -> "動作偵測＋步數"
        hasMotionSensor -> "動作偵測"
        hasGyroscope -> "陀螺儀偵測"
        hasStepSensor -> "步數偵測"
        else -> "沒有可用感測器"
    }
}

@Preview(showBackground = true)
@Composable
fun StepGateScreenPreview() {
    SwipeToContinueTheme {
        StepGateScreen(
            hasPermission = true,
            motionState = MotionCounterState(
                points = 9,
                sensorAvailable = true,
                isMoving = true,
                motionLevel = 0.65f,
                sensorMode = "動作偵測＋陀螺儀＋步數"
            ),
            onRequestPermission = {}
        )
    }
}
