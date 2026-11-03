import Foundation

/// Builds reader configs from the same JSON the backend serves at GET /v1/config/readers.
public enum ReaderConfigs {
    public static func parse(_ data: Data) throws -> [String: AppReaderConfig] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let pattern = root["price_pattern"] as? String,
              let apps = root["apps"] as? [String: Any] else { throw CocoaError(.coderReadCorrupt) }
        let price = try NSRegularExpression(pattern: pattern)
        var out: [String: AppReaderConfig] = [:]
        for (app, v) in apps {
            guard let o = v as? [String: Any] else { continue }
            let screens = o["screens"] as? [String: Any] ?? [:]
            func match(_ k: String) -> ScreenMatch {
                let m = screens[k] as? [String: Any] ?? [:]
                return ScreenMatch(allText: m["all_text"] as? [String] ?? [], anyText: m["any_text"] as? [String] ?? [])
            }
            let products = (o["products"] as? [String: Any] ?? [:]).compactMap { name, raw -> ProductSpec? in
                guard let p = raw as? [String: Any], let key = p["key"] as? String else { return nil }
                return ProductSpec(name: name, key: key, rideType: p["ride_type"] as? String, exclude: p["exclude"] as? String)
            }.sorted { $0.name < $1.name }
            let etas = (o["eta_patterns"] as? [String] ?? []).compactMap {
                try? NSRegularExpression(pattern: $0, options: [.caseInsensitive, .anchorsMatchLines])
            }
            out[app] = AppReaderConfig(app: app, fareList: match("fare_list"), loading: match("loading"),
                                       loggedOut: match("logged_out"), rideInProgress: match("ride_in_progress"),
                                       etaPatterns: etas, products: products, pricePattern: price)
        }
        return out
    }

    /// Display name for a catalog key ("uber_go_ac" -> "Uber Go AC" is "Go AC"'s app + name).
    public static func productName(_ cfg: AppReaderConfig?, key: String) -> String {
        cfg?.products.first { $0.key == key }?.name ?? key
    }
}
