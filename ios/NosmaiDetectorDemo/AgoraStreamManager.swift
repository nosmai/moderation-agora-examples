import AgoraRtcKit
import Combine
import CoreVideo
import NosmaiDetection
import UIKit

// ============================================================================
//  Agora live-streaming + Nosmai moderation.
//
//  Same idea as CameraManager, but the camera frames come from AGORA instead of
//  AVCaptureSession. Agora captures + streams the broadcaster's video; we tap
//  each captured frame (AgoraVideoFrameDelegate) and forward it to the Nosmai
//  SDK with `NosmaiSDK.pushFrame`. The SDK does all detection and reports back
//  through `NosmaiListener` — exactly like the plain-camera screen.
//
//  SETUP (one-time):
//    1. Add the Agora SDK package in Xcode:
//         File ▸ Add Package Dependencies… ▸
//         https://github.com/AgoraIO/AgoraRtcEngine_iOS  (pick "AgoraRtcKit")
//    2. Set `appId` / `channelName` below to your Agora project values.
//       For a testing-mode Agora project leave `token = nil`.
//
//  NOTE: written for the Agora 4.x API. If your installed version differs, the
//  AgoraVideoFrameDelegate method signatures are the only thing that may need a
//  small tweak — the rest (pushFrame wiring, listener) is version-independent.
// ============================================================================

@MainActor
final class AgoraStreamManager: NSObject, ObservableObject, @unchecked Sendable {

    // Detection result from the Nosmai SDK (objects + NSFW), same as the camera screen.
    @Published var latestResult: NosmaiResult?
    @Published var engineReady: Bool = false      // Nosmai SDK initialized
    @Published var isStreaming: Bool = false       // joined the Agora channel
    @Published var status: String = "Idle"
    @Published var isFrontCamera: Bool = true      // Agora's default capture is the front camera

    // MARK: - CONFIG — fill these in -------------------------------------------
    // Must match the viewer (the Agora web demo) so the iOS broadcast shows up
    // there: same App ID + same channel.
    private let appId = "YOUR_AGORA_APP_ID"
    private let channelName = "YOUR_CHANNEL_NAME"
    // Token for channel "moderation_android_1", uid 0, publisher — valid ~24h.
    // Regenerate when it expires (Agora Console temp token, or a token server).
    private let token: String? = nil
    // --------------------------------------------------------------------------

    private let licenseKey = "NOSMAI-XXXX"

    private var agora: AgoraRtcEngineKit?
    /// The view Agora renders the local (broadcaster) video into. SwiftUI hosts it.
    let localVideoView = UIView()

    // Only forward every Nth captured frame to the SDK — the SDK samples again
    // internally, so pushing every frame just wastes a BGRA copy.
    nonisolated(unsafe) private var frameCount = 0
    private let pushEveryN = 5   // ~3 frames/sec to the detector — plenty for moderation

    // MARK: - Lifecycle

    func start() {
        initializeNosmai()  // license + stream + text (off the main thread)
        setupAgora()        // engine + frame tap + join channel
    }

    /// Toggle between the front and back camera (Agora handles the swap).
    func switchCamera() {
        agora?.switchCamera()
        isFrontCamera.toggle()
    }

    func stop() {
        NosmaiSDK.stopStream()
        agora?.stopPreview()
        agora?.leaveChannel(nil)
        AgoraRtcEngineKit.destroy()
        agora = nil
        isStreaming = false
        status = "Stopped"
    }

    // MARK: - Nosmai SDK

    private func initializeNosmai() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            var ok = false
            do {
                try NosmaiSDK.initialize(licenseKey: self.licenseKey,
                                         models: [.objectDetection, .nsfw])
                ok = true
            } catch {
                print("[Agora] NosmaiSDK init failed: \(error.localizedDescription)")
            }
            DispatchQueue.main.async {
                self.engineReady = ok
                if ok { NosmaiSDK.startStream(listener: self) }
            }
            // NOTE: text moderation (the ~106 MB model) is intentionally NOT
            // loaded on this screen — live streaming only needs visual detection,
            // and skipping it saves a lot of memory + load. Add it back (+ a text
            // input) if you want chat moderation during the stream.
        }
    }

    // MARK: - Agora

    private func setupAgora() {
        let config = AgoraRtcEngineConfig()
        config.appId = appId
        let engine = AgoraRtcEngineKit.sharedEngine(with: config, delegate: self)
        agora = engine

        engine.setChannelProfile(.liveBroadcasting)
        engine.setClientRole(.broadcaster)
        engine.enableVideo()
        engine.disableAudio()  // video-only — avoids needing a microphone permission

        // Keep the capture modest. The raw-frame tap forces a per-frame BGRA
        // conversion (the SDK only accepts 32BGRA); at 720p that bogs down the
        // preview. 640x480 @ 15fps is plenty for moderation (the detector runs at
        // 640 internally) and keeps the preview smooth.
        let videoConfig = AgoraVideoEncoderConfiguration(
            size: CGSize(width: 640, height: 480),
            frameRate: 15,
            bitrate: AgoraVideoBitrateStandard,
            orientationMode: .adaptative,
            mirrorMode: .auto)
        engine.setVideoEncoderConfiguration(videoConfig)

        // Tap raw captured frames → forward to Nosmai.
        engine.setVideoFrameDelegate(self)

        // Local preview into our UIView.
        let canvas = AgoraRtcVideoCanvas()
        canvas.uid = 0
        canvas.view = localVideoView
        canvas.renderMode = .hidden
        engine.setupLocalVideo(canvas)
        engine.startPreview()

        // Join the channel as a broadcaster.
        let options = AgoraRtcChannelMediaOptions()
        options.clientRoleType = .broadcaster
        options.publishCameraTrack = true
        options.publishMicrophoneTrack = false
        status = "Joining…"
        engine.joinChannel(byToken: token, channelId: channelName, uid: 0,
                           mediaOptions: options) { [weak self] _, _, _ in
            Task { @MainActor in
                self?.isStreaming = true
                self?.status = "Live"
            }
        }
    }
}

// MARK: - Agora raw video frames → Nosmai SDK
// These run on Agora's capture thread (nonisolated). pushFrame is lock-free /
// thread-safe inside the SDK.
extension AgoraStreamManager: AgoraVideoFrameDelegate {

    nonisolated func getVideoFrameProcessMode() -> AgoraVideoFrameProcessMode { .readOnly }

    // Ask Agora to hand us a BGRA CVPixelBuffer (matches the SDK's camera path).
    // ObjC AgoraVideoFormatCVPixelBGRA (=14) imports to Swift as .cvPixelBGRA.
    nonisolated func getVideoFormatPreference() -> AgoraVideoFormat { .cvPixelBGRA }

    // Deliver upright frames so we can push them with rotationDegrees: 0.
    nonisolated func getRotationApplied() -> Bool { true }
    nonisolated func getMirrorApplied() -> Bool { false }

    nonisolated func onCapture(_ videoFrame: AgoraOutputVideoFrame,
                               sourceType: AgoraVideoSourceType) -> Bool {
        frameCount &+= 1
        if frameCount % pushEveryN == 0, let pixelBuffer = videoFrame.pixelBuffer {
            // Agora already applied rotation → frame is upright → 0 degrees.
            // (If the verdict looks sideways, flip getRotationApplied()/this value.)
            NosmaiSDK.pushFrame(pixelBuffer, rotationDegrees: 0)
        }
        return true  // true = keep the frame in the outgoing stream
    }
}

// MARK: - Nosmai detection results (same as the camera screen)
extension AgoraStreamManager: NosmaiListener {
    nonisolated func nosmaiOnResult(_ result: NosmaiResult) {
        Task { @MainActor [weak self] in self?.latestResult = result }
    }
}

// MARK: - Agora engine events (logging / status)
extension AgoraStreamManager: AgoraRtcEngineDelegate {
    nonisolated func rtcEngine(_ engine: AgoraRtcEngineKit, didOccurError errorCode: AgoraErrorCode) {
        Task { @MainActor [weak self] in self?.status = "Agora error \(errorCode.rawValue)" }
    }
}
