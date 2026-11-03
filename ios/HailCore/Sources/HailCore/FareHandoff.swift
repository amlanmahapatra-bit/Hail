import Foundation

/// How the screen-reading extension hands prices to the app WITHOUT App Groups (which free Apple
/// IDs can't use): the prices ride inside the local notification ("Got 6 Uber prices"). Tapping
/// it, or reopening Hail, delivers them. Only catalog keys and numbers, never screen text.
public struct FareHandoff: Equatable {
    public let app: String
    public let status: String  // ok | not_logged_in
    public let fares: [Fare]
    public let capturedAt: Date

    public init(app: String, status: String, fares: [Fare], capturedAt: Date) {
        self.app = app; self.status = status; self.fares = fares; self.capturedAt = capturedAt
    }

    static let marker = "hail_handoff_v1"

    /// Notification userInfo (property-list types only, small enough for a notification).
    public var userInfo: [String: Any] {
        [
            Self.marker: true,
            "app": app,
            "status": status,
            "captured_at": capturedAt.timeIntervalSince1970,
            "fares": fares.prefix(30).map { f -> [String: Any] in
                var d: [String: Any] = ["k": f.productKey, "t": f.rideType, "lo": f.fareMin, "hi": f.fareMax]
                if let e = f.etaMinutes { d["e"] = e }
                return d
            },
        ]
    }

    /// Decodes and validates a notification's userInfo; nil if it isn't a Hail hand-off.
    public static func from(_ info: [AnyHashable: Any]) -> FareHandoff? {
        guard info[marker] as? Bool == true, let app = info["app"] as? String,
              ["uber", "ola", "rapido"].contains(app),
              let status = info["status"] as? String, ["ok", "not_logged_in"].contains(status),
              let at = (info["captured_at"] as? NSNumber)?.doubleValue else { return nil }
        let raw = info["fares"] as? [[String: Any]] ?? []
        let fares: [Fare] = raw.prefix(30).compactMap { d in
            guard let k = d["k"] as? String, let t = d["t"] as? String,
                  let lo = (d["lo"] as? NSNumber)?.doubleValue, let hi = (d["hi"] as? NSNumber)?.doubleValue,
                  k.range(of: "^[a-z0-9_]{1,40}$", options: .regularExpression) != nil,
                  ["cab", "auto", "bike"].contains(t), lo > 0, hi >= lo, hi <= 20000 else { return nil }
            return Fare(productKey: k, rideType: t, fareMin: lo, fareMax: hi, etaMinutes: (d["e"] as? NSNumber)?.intValue)
        }
        return FareHandoff(app: app, status: status, fares: fares, capturedAt: Date(timeIntervalSince1970: at))
    }
}

/// Which ride app is on screen right now (the extension doesn't know which one Hail opened).
public enum AppDetector {
    public static func detect(_ nodes: [ScreenNode], _ configs: [String: AppReaderConfig]) -> (String, ScreenState)? {
        for app in ["uber", "ola", "rapido"] {
            guard let cfg = configs[app] else { continue }
            let s = FareReader.state(nodes, cfg)
            if s == .fareList || s == .loggedOut { return (app, s) }
        }
        return nil
    }
}
