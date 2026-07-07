import NosmaiDetection
import SwiftUI

struct ContentView: View {
    @StateObject private var camera = CameraManager()

    var body: some View {
        ZStack {
            if camera.permissionDenied {
                permissionDeniedView
            } else {
                CameraPreviewView(session: camera.session)
                    .ignoresSafeArea()

                VStack(spacing: 10) {
                    TextModerationCard(ready: camera.textReady, moderate: camera.moderate)
                        .padding(.top, 60)
                        .padding(.horizontal, 16)
                    StatusBanner(result: camera.latestResult, ready: camera.engineReady)
                        .padding(.horizontal, 16)
                    Spacer()
                    footer
                        .padding(.bottom, 40)
                }
            }
        }
        .onAppear {
            camera.start()
            // Live camera demo — keep the screen awake while visible.
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            camera.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    private var permissionDeniedView: some View {
        VStack(spacing: 16) {
            Image(systemName: "video.slash")
                .font(.system(size: 60))
            Text("Camera access denied")
                .font(.headline)
            Text("Enable camera in Settings to use this app.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
        }
        .foregroundColor(.white)
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    private var footer: some View {
        Text("Nosmai Detector  ·  Weapon / Drug / Cigarette / Alcohol  ·  NSFW")
            .font(.caption)
            .foregroundColor(.white.opacity(0.7))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.4))
            .clipShape(Capsule())
    }
}

struct TextModerationCard: View {
    let ready: Bool
    let moderate: (String) async -> NosmaiTextResult?

    @State private var input: String = ""
    @State private var verdict: NosmaiTextResult?
    @State private var checking: Bool = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Text moderation")
                .font(.subheadline.bold())
                .foregroundColor(.white)

            HStack(spacing: 8) {
                TextField("", text: $input, prompt:
                    Text("Type a message to check…").foregroundColor(.white.opacity(0.5)))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundColor(.white)
                    .padding(10)
                    .background(Color.white.opacity(0.08))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .focused($focused)
                    .submitLabel(.done)
                    .onSubmit(runCheck)

                Button(action: runCheck) {
                    Group {
                        if checking {
                            ProgressView().tint(.white)
                        } else {
                            Text("Check").bold()
                        }
                    }
                    .frame(minWidth: 44)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background((ready && !checking) ? Color.blue : Color.gray)
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                .disabled(!ready || checking)
            }

            Text(verdictText)
                .font(.caption)
                .foregroundColor(verdictColor)
                .lineLimit(2)
        }
        .padding(12)
        .background(Color.black.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private func runCheck() {
        let msg = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !msg.isEmpty, ready, !checking else { return }
        focused = false
        checking = true
        Task {
            let result = await moderate(msg)
            await MainActor.run {
                verdict = result
                checking = false
            }
        }
    }

    private var verdictText: String {
        guard ready else { return "Loading text model…" }
        guard let v = verdict else {
            return "Enter text to check it against the moderation engine."
        }
        if v.blocked {
            let word = v.matchedWord.isEmpty ? "" : " · \"\(v.matchedWord)\""
            return "BLOCKED · \(v.categoryLabel)\(word) (\(Int(v.score * 100))%)"
        }
        return "ALLOWED · safe"
    }

    private var verdictColor: Color {
        guard ready, let v = verdict else { return .white.opacity(0.7) }
        return v.blocked ? Color(red: 1.0, green: 0.54, blue: 0.5)
                         : Color(red: 0.5, green: 0.89, blue: 0.49)
    }
}

struct StatusBanner: View {
    let result: NosmaiResult?
    let ready: Bool

    private var isUnsafe: Bool { result?.isUnsafe == true }
    // Suggestive (NSFW WARN) — not unsafe, but flag it amber/yellow.
    private var isWarn: Bool { !isUnsafe && result?.nsfw == .warn }

    private var bannerColor: Color {
        if isUnsafe { return Color.red.opacity(0.9) }
        if isWarn { return Color(red: 0.92, green: 0.70, blue: 0.0) }  // yellow
        return Color.green.opacity(0.85)
    }
    private var title: String {
        if isUnsafe { return "UNSAFE" }
        if isWarn { return "WARN" }
        return "SAFE"
    }
    private var icon: String {
        if isUnsafe { return "exclamationmark.triangle.fill" }
        if isWarn { return "exclamationmark.circle.fill" }
        return "checkmark.shield.fill"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(.white)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.title3.bold())
                    .foregroundColor(.white)

                Text(detailText)
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.9))
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(bannerColor)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: .black.opacity(0.25), radius: 8, x: 0, y: 4)
    }

    private var detailText: String {
        guard ready else { return "Initializing engine..." }
        guard let result else { return "Nothing detected" }
        // One unified result: object detections + the NSFW classifier verdict.
        var parts = result.detections.map { "\($0.label) (\(Int($0.confidence * 100))%)" }
        switch result.nsfw {
        case .block: parts.append("NSFW: explicit (\(Int(result.nsfwExplicit * 100))%)")
        case .warn:  parts.append("NSFW: suggestive (\(Int(result.nsfwSexy * 100))%)")
        default: break
        }
        return parts.isEmpty ? "Nothing detected" : parts.joined(separator: ", ")
    }
}
