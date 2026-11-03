import Foundation
import UIKit

/// Opening ride apps on iPhone. iOS only allows opening an app through a link it supports:
/// Uber (public docs) and Ola (public docs) take the trip's coordinates. Rapido publishes no
/// link format, so Hail copies the destination and the user opens Rapido themselves.
enum RideApps {
    static let names = ["uber": "Uber", "ola": "Ola", "rapido": "Rapido"]

    static func deepLink(app: String, pickup: [String: Any], drop: [String: Any], config: [String: Any]?) -> URL? {
        guard let apps = config?["apps"] as? [String: Any], let a = apps[app] as? [String: Any],
              let template = a["deep_link"] as? String,
              let plat = pickup["lat"] as? Double, let plng = pickup["lng"] as? Double,
              let dlat = drop["lat"] as? Double, let dlng = drop["lng"] as? Double else { return nil }
        func enc(_ s: Any?) -> String {
            (s as? String ?? "").addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        }
        let url = template
            .replacingOccurrences(of: "{pickup_lat}", with: "\(plat)").replacingOccurrences(of: "{pickup_lng}", with: "\(plng)")
            .replacingOccurrences(of: "{pickup_label}", with: enc(pickup["label"]))
            .replacingOccurrences(of: "{drop_lat}", with: "\(dlat)").replacingOccurrences(of: "{drop_lng}", with: "\(dlng)")
            .replacingOccurrences(of: "{drop_label}", with: enc(drop["label"]))
        return URL(string: url)
    }

    /// Opens the app on the trip if it has a link; otherwise copies the destination for pasting.
    /// Returns a line telling the user what to do.
    @MainActor
    static func open(app: String, pickup: [String: Any], drop: [String: Any], config: [String: Any]?) async -> (opened: Bool, hint: String) {
        let name = names[app] ?? app.capitalized
        let place = drop["label"] as? String ?? "your destination"
        if let url = deepLink(app: app, pickup: pickup, drop: drop, config: config),
           await UIApplication.shared.open(url) {
            return (true, "Opened \(name) on your trip.")
        }
        UIPasteboard.general.string = place
        return (false, "Open \(name) and paste \"\(place)\" as the destination (it's copied).")
    }
}
