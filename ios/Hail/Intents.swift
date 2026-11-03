import AppIntents
import Foundation

/// "Hey Siri, find a ride with Hail" -> Siri asks where to -> Hail opens and starts the request.
struct FindRideIntent: AppIntent {
    static var title: LocalizedStringResource = "Find a ride"
    static var description = IntentDescription("Compare Uber, Ola and Rapido for a trip.")
    static var openAppWhenRun = true

    @Parameter(title: "Where to?")
    var destination: String

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingSiriRequest.put("to \(destination)")
        return .result()
    }
}

struct HailShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: FindRideIntent(),
                    phrases: ["Find a ride with \(.applicationName)", "Book a cab with \(.applicationName)"],
                    shortTitle: "Find a ride", systemImageName: "car.fill")
    }
}

/// Hands the Siri request to the chat when the app comes to the front.
enum PendingSiriRequest {
    static func put(_ text: String) { UserDefaults.standard.set(text, forKey: "siri_request") }
    static func take() -> String? {
        let t = UserDefaults.standard.string(forKey: "siri_request")
        UserDefaults.standard.removeObject(forKey: "siri_request")
        return t
    }
}
