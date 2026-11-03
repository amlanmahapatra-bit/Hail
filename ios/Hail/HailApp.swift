import SwiftUI

@main
struct HailApp: App {
    @StateObject private var model = ChatModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ChatView(voice: model.voice)
                .environmentObject(model)
                .onOpenURL { _ in Task { await model.resume() } }   // hail://continue
                .onChange(of: phase) { _, p in
                    if p == .active {
                        Task { await model.resume() }
                        if let t = PendingSiriRequest.take() { model.send(t) }
                    }
                }
        }
    }
}
