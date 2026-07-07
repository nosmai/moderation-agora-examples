import SwiftUI

/// Landing screen — pick the input source. Both run the SAME Nosmai detection;
/// only where the frames come from differs (device camera vs Agora live stream).
struct HomeView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Spacer()
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 54)).foregroundStyle(.tint)
                Text("Nosmai Detector").font(.largeTitle.bold())
                Text("Choose an input source").foregroundColor(.secondary)
                Spacer()

                NavigationLink { ContentView() } label: {
                    MenuButton(title: "Camera Detection",
                               subtitle: "Local camera · weapon / drug / NSFW + text",
                               systemImage: "camera.fill", color: .blue)
                }
                NavigationLink { AgoraStreamView() } label: {
                    MenuButton(title: "Live Streaming (Agora)",
                               subtitle: "Moderate the broadcast frames in real time",
                               systemImage: "dot.radiowaves.left.and.right", color: .pink)
                }

                Spacer()
            }
            .padding(24)
            .navigationBarHidden(true)
        }
    }
}

private struct MenuButton: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let color: Color

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: systemImage)
                .font(.title2).foregroundColor(.white)
                .frame(width: 52, height: 52)
                .background(color)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline).foregroundColor(.primary)
                Text(subtitle).font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundColor(.secondary)
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
