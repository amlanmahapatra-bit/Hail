import HailCore
import SwiftUI
import UserNotifications

@main
struct HailApp: App {
    @UIApplicationDelegateAdaptor(NotificationRelay.self) private var relay
    @StateObject private var model = ChatModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ChatView(voice: model.voice)
                .environmentObject(model)
                .onAppear { NotificationRelay.model = model }
                .onOpenURL { _ in Task { await model.resume() } }
                .onChange(of: phase) { _, p in
                    if p == .active {
                        Task { await model.resume() }
                        if let t = PendingSiriRequest.take() { model.send(t) }
                    }
                }
        }
    }
}

/// Receives the screen reader's "Got Uber's prices" notification (tapped, or while Hail is open)
/// and hands the prices to the chat.
final class NotificationRelay: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor static weak var model: ChatModel?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let h = FareHandoff.from(response.notification.request.content.userInfo) else { return }
        await MainActor.run { Task { await Self.model?.receive(h) } }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        if let h = FareHandoff.from(notification.request.content.userInfo) {
            await MainActor.run { Task { await Self.model?.receive(h) } }
            return []
        }
        return [.banner]
    }
}
