import Foundation

/// Collects fares across screen frames while the user looks at (and scrolls) a ride app's list.
/// "Stable" = something was read and nothing new or changed for `quiet` seconds.
public final class FareAccumulator {
    public private(set) var fares: [Fare] = []
    public private(set) var lastChange: Date?

    public init() {}

    public func reset() {
        fares = []
        lastChange = nil
    }

    /// Returns true if this frame added or changed anything.
    @discardableResult
    public func add(_ new: [Fare], at now: Date) -> Bool {
        var changed = false
        for f in new {
            if let i = fares.firstIndex(where: { $0.productKey == f.productKey }) {
                if fares[i] != f { fares[i] = f; changed = true }
            } else {
                fares.append(f); changed = true
            }
        }
        if changed { lastChange = now }
        return changed
    }

    public func isStable(at now: Date, quiet: TimeInterval = 2) -> Bool {
        guard let t = lastChange, !fares.isEmpty else { return false }
        return now.timeIntervalSince(t) >= quiet
    }
}

/// The observation JSON the backend expects in a tool_result (docs/protocol.md).
public enum Observations {
    public static func json(app: String, fares: [Fare], capturedAt: Date, confidence: Double,
                            rideTypes: Set<String>) -> [[String: Any]] {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fares.filter { rideTypes.contains($0.rideType) }.prefix(30).map { f in
            var o: [String: Any] = [
                "app": app, "ride_type": f.rideType, "product": f.productKey,
                "fare_min": f.fareMin, "fare_max": f.fareMax, "currency": "INR",
                "surge_flag": false, "captured_at": iso.string(from: capturedAt),
                "confidence": confidence, "screen_signature": "\(app).ios_ocr.draft1",
            ]
            o["eta_minutes"] = f.etaMinutes.map { $0 as Any } ?? NSNull()
            return o
        }
    }
}
