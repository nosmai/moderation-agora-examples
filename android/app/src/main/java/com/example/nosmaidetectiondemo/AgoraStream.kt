package com.example.nosmaidetectiondemo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nosmai.detection.NosmaiListener
import com.nosmai.detection.NosmaiModel
import com.nosmai.detection.NosmaiResult
import com.nosmai.detection.NosmaiSDK
import io.agora.base.VideoFrame
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.IVideoFrameObserver
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Agora live streaming + Nosmai moderation. Mirrors the iOS AgoraStreamManager:
// Agora captures + streams the broadcaster's video; we tap each captured frame
// (IVideoFrameObserver), convert it to a Bitmap and forward it to the Nosmai SDK
// with NosmaiSDK.pushFrame. The SDK does all detection and reports through
// NosmaiListener, exactly like the plain-camera screen.
//
// SETUP: set APP_ID / CHANNEL / TOKEN to your Agora project values. The token
// below is channel-specific and expires (~24h); regenerate it in the Agora
// Console (or use a token server). For a testing-mode project, set TOKEN = null.
//
// NOTE: written for the Agora 4.x API. If your installed version differs, the
// IVideoFrameObserver method signatures are the only thing that may need a small
// tweak; the pushFrame wiring and listener are version-independent.
class AgoraStreamManager(
    private val context: Context,
    private val onResult: (NosmaiResult) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTextReady: (Boolean) -> Unit = {},
) {
    private val scope = MainScope()
    private var engine: RtcEngine? = null

    // Agora project config.
    private val appId = "YOUR_AGORA_APP_ID"
    private val channelName = "YOUR_CHANNEL_NAME"
    private val token: String? =
        "YOUR_AGORA_TOKEN"

    // Android license key (bound to com.example.nosmaidetectiondemo).
    private val licenseKey = "NOSMAI-XXXX"

    // Only forward every Nth captured frame to the SDK; the SDK samples again
    // internally, so pushing every frame just wastes a conversion.
    @Volatile private var frameCount = 0
    private val pushEveryN = 5

    // Frame conversion runs OFF the Agora capture thread (Agora only guarantees
    // callback sequence, not a fixed thread). `converting` is a drop-if-busy gate:
    // new frames are skipped while one is still being processed, so the capture
    // thread never blocks and the preview stays smooth.
    private val convertExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var converting = false

    /** SurfaceView Agora renders the local (broadcaster) preview into. */
    val localView: SurfaceView = SurfaceView(context.applicationContext)

    fun start() {
        initNosmai()
        setupAgora()
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    fun stop() {
        try {
            NosmaiSDK.stopStream()
            engine?.registerVideoFrameObserver(null)
            engine?.stopPreview()
            engine?.leaveChannel()
        } catch (_: Throwable) {
        }
        RtcEngine.destroy()
        engine = null
        convertExecutor.shutdown()
        onStatus("Stopped")
    }

    // MARK: - Nosmai

    private fun initNosmai() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    NosmaiSDK.init(
                        context.applicationContext, licenseKey,
                        NosmaiModel.OBJECT_DETECTION, NosmaiModel.NSFW,
                    )
                } catch (_: Throwable) {
                    false
                }
            }
            if (!ok) {
                onStatus("Nosmai init failed")
                return@launch
            }
            NosmaiSDK.startStream(object : NosmaiListener {
                override fun onResult(result: NosmaiResult) {
                    // Qualified: call the constructor callback, not this method
                    // (same name would recurse).
                    this@AgoraStreamManager.onResult(result)
                }
            })
            // The text model is large (~106 MB); load it after the stream is live.
            withContext(Dispatchers.IO) {
                try {
                    NosmaiSDK.initText(context.applicationContext)
                } catch (_: Throwable) {
                }
            }
            onTextReady(true)
        }
    }

    // MARK: - Agora

    private fun setupAgora() {
        try {
            val appCtx = context.applicationContext
                ?: throw IllegalStateException("null application context")
            val e = RtcEngine.create(appCtx, appId, eventHandler)
            engine = e

            e.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING)
            e.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            e.enableVideo()

            // Modest capture: the raw-frame tap converts per frame, so keep it
            // small. 640x480 @ 15fps is plenty for moderation and keeps the
            // preview smooth.
            e.setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VideoDimensions(640, 480),
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE,
                ),
            )

            // Tap raw captured frames -> forward to Nosmai.
            e.registerVideoFrameObserver(frameObserver)

            // Local preview into our SurfaceView.
            e.setupLocalVideo(VideoCanvas(localView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
            e.startPreview()

            val options = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                publishCameraTrack = true
                publishMicrophoneTrack = false
            }
            onStatus("Joining…")
            e.joinChannel(token, channelName, 0, options)
        } catch (t: Throwable) {
            onStatus("Agora setup failed: ${t.message}")
        }
    }

    private val eventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            onStatus("Live")
        }

        override fun onError(err: Int) {
            onStatus("Agora error $err")
        }
    }

    // MARK: - Raw frames -> Nosmai. The callback thread only grabs the I420 buffer
    // (cheap); the RGB conversion + pushFrame run on convertExecutor so the capture
    // thread never blocks. drop-if-busy skips frames while one is in flight.
    private val frameObserver = object : IVideoFrameObserver {
        override fun onCaptureVideoFrame(sourceType: Int, videoFrame: VideoFrame): Boolean {
            frameCount += 1
            if (frameCount % pushEveryN == 0 && !converting) {
                val i420 = videoFrame.buffer.toI420()
                if (i420 != null) {
                    converting = true
                    convertExecutor.execute {
                        try {
                            // Agora applies rotation (getRotationApplied) so the
                            // frame is upright -> rotationDegrees 0.
                            i420ToBitmap(i420)?.let { NosmaiSDK.pushFrame(it, 0) }
                        } catch (_: Throwable) {
                        } finally {
                            i420.release()
                            converting = false
                        }
                    }
                }
            }
            return true // keep the frame in the outgoing stream
        }

        override fun onPreEncodeVideoFrame(sourceType: Int, videoFrame: VideoFrame): Boolean = true
        override fun onMediaPlayerVideoFrame(videoFrame: VideoFrame, mediaPlayerId: Int): Boolean = true
        override fun onRenderVideoFrame(channelId: String?, uid: Int, videoFrame: VideoFrame): Boolean = true
        override fun getVideoFrameProcessMode(): Int = IVideoFrameObserver.PROCESS_MODE_READ_ONLY
        override fun getVideoFormatPreference(): Int = IVideoFrameObserver.VIDEO_PIXEL_DEFAULT
        override fun getRotationApplied(): Boolean = true
        override fun getMirrorApplied(): Boolean = false
        override fun getObservedFramePosition(): Int = IVideoFrameObserver.POSITION_POST_CAPTURER
    }

    // Convert an I420 buffer directly to an ARGB Bitmap (no JPEG encode/decode).
    // Integer BT.601 YUV->RGB. Runs on convertExecutor; the caller releases the
    // buffer.
    private fun i420ToBitmap(i420: VideoFrame.I420Buffer): Bitmap? {
        return try {
            val w = i420.width
            val h = i420.height
            val y = i420.dataY
            val u = i420.dataU
            val v = i420.dataV
            val sy = i420.strideY
            val su = i420.strideU
            val sv = i420.strideV
            val argb = IntArray(w * h)
            for (j in 0 until h) {
                val yRow = j * sy
                val uvRow = j shr 1
                val uRow = uvRow * su
                val vRow = uvRow * sv
                var idx = j * w
                for (i in 0 until w) {
                    val yy = y.get(yRow + i).toInt() and 0xFF
                    val uvCol = i shr 1
                    val uu = (u.get(uRow + uvCol).toInt() and 0xFF) - 128
                    val vv = (v.get(vRow + uvCol).toInt() and 0xFF) - 128
                    var r = yy + ((359 * vv) shr 8)
                    var g = yy - ((88 * uu) shr 8) - ((183 * vv) shr 8)
                    var b = yy + ((454 * uu) shr 8)
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    argb[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
fun AgoraScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is needed for live streaming.", color = Color.White)
                Spacer(Modifier.size(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera") }
                Spacer(Modifier.size(8.dp))
                Text(
                    "Back",
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.clickable(onClick = onBack),
                )
            }
        }
        return
    }

    var result by remember { mutableStateOf<NosmaiResult?>(null) }
    var status by remember { mutableStateOf("Idle") }
    var ready by remember { mutableStateOf(false) }
    var textReady by remember { mutableStateOf(false) }

    val manager = remember {
        AgoraStreamManager(
            context = context,
            onResult = { result = it; ready = true },
            onStatus = { status = it },
            onTextReady = { textReady = it },
        )
    }

    DisposableEffect(Unit) {
        manager.start()
        onDispose { manager.stop() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { manager.localView })

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(16.dp)) {
            Text("Agora Live · $status", color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.size(8.dp))
            StatusBanner(result = result, ready = ready)
            Spacer(Modifier.size(8.dp))
            TextModerationCard(ready = textReady)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().systemBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Camera switch (front / back).
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1976D2))
                        .clickable { manager.switchCamera() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = "Switch camera",
                        tint = Color.White,
                    )
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }
        }
    }
}
