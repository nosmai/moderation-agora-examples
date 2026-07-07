import NosmaiDetection
import SwiftUI
import UIKit

/// Live-streaming screen: Agora broadcasts the camera, and the Nosmai SDK
/// moderates the same frames. The detection banner is identical to the plain
/// camera screen — only the frame source changed.
struct AgoraStreamView: View {
    @StateObject private var stream = AgoraStreamManager()

    var body: some View {
        ZStack {
            AgoraVideoView(view: stream.localVideoView)
                .ignoresSafeArea()

            VStack(spacing: 10) {
                // Reuses StatusBanner from ContentView.swift — same verdict UI.
                StatusBanner(result: stream.latestResult, ready: stream.engineReady)
                    .padding(.top, 60)
                    .padding(.horizontal, 16)
                Spacer()

                HStack {
                    Spacer()
                    Button { stream.switchCamera() } label: {
                        Image(systemName: "arrow.triangle.2.circlepath.camera.fill")
                            .font(.title2)
                            .foregroundColor(.white)
                            .padding(15)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 8)

                footer
                    .padding(.bottom, 40)
            }
        }
        .onAppear {
            stream.start()
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            stream.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .navigationTitle("Live Streaming")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var footer: some View {
        Text("Agora live stream  ·  moderated by Nosmai  ·  \(stream.status)")
            .font(.caption)
            .foregroundColor(.white.opacity(0.75))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.4))
            .clipShape(Capsule())
    }
}

/// Hosts the UIView that Agora renders the local video into.
struct AgoraVideoView: UIViewRepresentable {
    let view: UIView
    func makeUIView(context: Context) -> UIView { view }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
