package com.example.nosmaidetectiondemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.nosmaidetectiondemo.ui.theme.NosmaiDetectionDemoTheme
import com.nosmai.detection.NosmaiListener
import com.nosmai.detection.NosmaiStreamOptions
import com.nosmai.detection.NosmaiNsfwVerdict
import com.nosmai.detection.NosmaiResult
import com.nosmai.detection.NosmaiModel
import com.nosmai.detection.NosmaiSDK
import com.nosmai.detection.NosmaiTextResult
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Live camera demo — keep the screen awake while the app is visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            NosmaiDetectionDemoTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = screen != "home") { screen = "home" }
                when (screen) {
                    "home" -> HomeScreen(
                        onDetector = { screen = "detector" },
                        onAgora = { screen = "agora" },
                    )
                    "detector" -> DetectorScreen()
                    "agora" -> AgoraScreen(onBack = { screen = "home" })
                }
            }
        }
    }
}

@Composable
fun HomeScreen(onDetector: () -> Unit, onAgora: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nosmai Detector",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(32.dp))
        Button(
            onClick = onDetector,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
        ) { Text("Live Camera Detection") }
        Spacer(Modifier.size(16.dp))
        Button(
            onClick = onAgora,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6657FA)),
        ) { Text("Agora Live Stream + Moderation") }
    }
}

@Composable
fun DetectorScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var sdkReady by remember { mutableStateOf(false) }
    var textReady by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NosmaiResult?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
        // Model load + engine init off the main thread.
        val initOk = withContext(Dispatchers.IO) {
            NosmaiSDK.init(
                context.applicationContext,
                "NOSMAI-XXXX",
                NosmaiModel.OBJECT_DETECTION, NosmaiModel.NSFW,
            )
        }
        if (!initOk) {
            // License/init failed (e.g. invalid key or no network on first launch).
            // Skip the stream so the app doesn't crash; the banner stays "engine
            // not ready".
            return@LaunchedEffect
        }
        NosmaiSDK.startStream(
            object : NosmaiListener {
                override fun onResult(result_: NosmaiResult) {
                    result = result_
                }
            },
            // Process (nearly) every frame instead of the default every-10th, so
            // detection keeps up with the camera.
            NosmaiStreamOptions(sampleEveryNFrames = 1),
        )
        sdkReady = true
        // The text model is large (~106 MB); load it after the camera is live.
        withContext(Dispatchers.IO) {
            NosmaiSDK.initText(context.applicationContext)
        }
        textReady = true
    }

    DisposableEffect(Unit) {
        onDispose { NosmaiSDK.stopStream() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            permissionDenied -> PermissionDeniedView()
            hasPermission -> {
                val executor = remember { Executors.newSingleThreadExecutor() }
                DisposableEffect(Unit) {
                    onDispose { executor.shutdown() }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val analysis = ImageAnalysis.Builder()
                                // 720p frames (matches the iOS demo) so distant
                                // objects are large enough to detect; the default
                                // ImageAnalysis resolution (~640x480) only catches
                                // close objects.
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            )
                                        )
                                        .build()
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                // CameraX delivers an already-upright frame (rotation
                                // done in HW/GPU), so the SDK no longer does a costly
                                // per-frame software rotate. rotationDegrees becomes 0.
                                .setOutputImageRotationEnabled(true)
                                .build()
                                .also {
                                    // The entire detection pipeline is one SDK call.
                                    it.setAnalyzer(executor) { proxy ->
                                        NosmaiSDK.pushFrame(proxy)
                                    }
                                }

                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                )

                Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    TextModerationCard(
                        ready = textReady,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    StatusBanner(
                        result = result,
                        ready = sdkReady,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Footer(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 40.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TextModerationCard(ready: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<NosmaiTextResult?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        val msg = input.trim()
        if (msg.isEmpty() || !ready || checking) return
        checking = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { NosmaiSDK.moderateText(msg) }
            verdict = r
            checking = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Text moderation",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message to check…") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { check() },
                ),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.08f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                    cursorColor = Color.White,
                    focusedIndicatorColor = Color.White.copy(alpha = 0.6f),
                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
                ),
            )
            Button(
                onClick = { check() },
                enabled = ready && !checking,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Check")
                }
            }
        }
        val v = verdict
        Text(
            text = when {
                !ready -> "Loading text model…"
                v == null -> "Enter text to check it against the moderation engine."
                v.blocked -> "BLOCKED · ${v.category.name.lowercase()}" +
                    (if (v.matchedWord.isNotEmpty()) " · \"${v.matchedWord}\"" else "") +
                    " (${(v.score * 100).toInt()}%)"
                else -> "ALLOWED · safe"
            },
            color = when {
                !ready || v == null -> Color.White.copy(alpha = 0.7f)
                v.blocked -> Color(0xFFFF8A80)
                else -> Color(0xFF80E27E)
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StatusBanner(result: NosmaiResult?, ready: Boolean, modifier: Modifier = Modifier) {
    val unsafe = result?.isUnsafe == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (unsafe) Color(0xE6D32F2F) else Color(0xD9388E3C),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (unsafe) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = if (unsafe) "UNSAFE" else "SAFE",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    !ready -> "Initializing engine..."
                    result == null -> "Nothing detected"
                    else -> {
                        // One unified result: object detections + NSFW verdict.
                        val parts = result.detections.map {
                            "${it.category.name.lowercase()} (${(it.confidence * 100).toInt()}%)"
                        }.toMutableList()
                        when (result.nsfw) {
                            NosmaiNsfwVerdict.BLOCK ->
                                parts.add("NSFW: explicit (${(result.nsfwScores.explicit * 100).toInt()}%)")
                            NosmaiNsfwVerdict.WARN ->
                                parts.add("NSFW: suggestive (${(result.nsfwScores.sexy * 100).toInt()}%)")
                            else -> {}
                        }
                        if (parts.isEmpty()) "Nothing detected" else parts.joinToString()
                    }
                },
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun Footer(modifier: Modifier = Modifier) {
    Text(
        text = "Nosmai Detector SDK  ·  Weapon / Drug / Cigarette / Alcohol  ·  NSFW",
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
fun PermissionDeniedView() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(60.dp),
        )
        Text(
            text = "Camera access denied",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Enable camera in Settings to use this app.",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
