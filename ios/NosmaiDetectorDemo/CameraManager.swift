import AVFoundation
import Combine
import CoreVideo
import NosmaiDetection
import UIKit

/// Camera session owner. All detection (sampling, inference, debounce,
/// thresholds) now lives inside the Nosmai Detection SDK — this class only
/// runs the camera and forwards frames with `NosmaiSDK.pushFrame`.
@MainActor
final class CameraManager: NSObject, ObservableObject, @unchecked Sendable {

    @Published var latestResult: NosmaiResult?
    @Published var isRunning: Bool = false
    @Published var permissionDenied: Bool = false
    @Published var engineReady: Bool = false
    @Published var textReady: Bool = false

    nonisolated let session = AVCaptureSession()

    nonisolated private let videoOutput = AVCaptureVideoDataOutput()
    nonisolated private let sessionQueue = DispatchQueue(label: "camera.session.queue")
    nonisolated private let videoQueue = DispatchQueue(label: "camera.video.queue")

    func start() {
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            Task { @MainActor in
                if !granted {
                    self.permissionDenied = true
                    return
                }
                self.initializeSDK()
                self.configureAndRun()
            }
        }
    }

    func stop() {
        NosmaiSDK.stopStream()
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
        isRunning = false
    }

    /// Model load takes a few hundred ms — keep it off the main thread.
    private func initializeSDK() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var ok = false
            do {
                try NosmaiSDK.initialize(licenseKey: "NOSMAI-XXXX",
                                         models: [.objectDetection, .nsfw])
                ok = true
            } catch {
                // Shows the exact reason, e.g. "license verification failed
                // (invalid-key)" / "(network-required)".
                print("[CameraManager] NosmaiSDK init failed: \(error.localizedDescription)")
            }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.engineReady = ok
                if ok {
                    NosmaiSDK.startStream(listener: self)
                }
            }
            // The text model is large (~106 MB); load it after the camera is up.
            let textOK = (try? NosmaiSDK.initializeText()) != nil
            DispatchQueue.main.async { [weak self] in
                self?.textReady = textOK
                if !textOK { print("[CameraManager] text moderation init failed") }
            }
        }
    }

    /// Moderates a chat message off the main thread. Returns nil if the text
    /// engine is not ready.
    nonisolated func moderate(_ message: String) async -> NosmaiTextResult? {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: NosmaiSDK.moderateText(message))
            }
        }
    }

    private func configureAndRun() {
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.configureSession()
            self.session.startRunning()
            Task { @MainActor in self.isRunning = true }
        }
    }

    nonisolated private func configureSession() {
        session.beginConfiguration()
        session.sessionPreset = .hd1280x720

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)

        if session.canAddOutput(videoOutput) {
            session.addOutput(videoOutput)
        }

        session.commitConfiguration()
    }
}

extension CameraManager: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(_ output: AVCaptureOutput,
                                   didOutput sampleBuffer: CMSampleBuffer,
                                   from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        // Back camera in portrait: sensor-native buffers need 90° CW to be
        // upright. Sampling/debounce/inference all happen inside the SDK.
        NosmaiSDK.pushFrame(pixelBuffer, rotationDegrees: 90)
    }
}

extension CameraManager: NosmaiListener {
    // SDK delivers on the main queue; hop explicitly to satisfy isolation.
    nonisolated func nosmaiOnResult(_ result: NosmaiResult) {
        Task { @MainActor [weak self] in
            self?.latestResult = result
        }
    }
}
