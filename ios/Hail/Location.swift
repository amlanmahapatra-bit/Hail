import CoreLocation

/// "Pickup = where I am": one location fix, only when the user sends a request.
@MainActor
final class Here: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var waiting: [(CLLocation?) -> Void] = []

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    func current() async -> [String: Any]? {
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        let loc: CLLocation? = await withCheckedContinuation { cont in
            if let l = manager.location, -l.timestamp.timeIntervalSinceNow < 120 { cont.resume(returning: l); return }
            waiting.append { cont.resume(returning: $0) }
            manager.requestLocation()
        }
        guard let loc else { return nil }
        return ["lat": loc.coordinate.latitude, "lng": loc.coordinate.longitude, "label": "Current location"]
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let l = locations.last
        Task { @MainActor in self.flush(l) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.flush(nil) }
    }

    private func flush(_ l: CLLocation?) {
        let w = waiting
        waiting = []
        w.forEach { $0(l) }
    }
}
