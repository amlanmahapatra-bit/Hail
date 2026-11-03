import CoreMedia
import HailCore
import ReplayKit
import UserNotifications
import Vision

/// Screen-reading extension (iPhone screen broadcast: the user starts it with one tap, a red
/// indicator shows while it runs, and it stops itself after a few minutes).
///
/// It reads frames on the iPhone with Apple's text recognition and drops each frame at once.
/// When it sees a ride app's fare list (Uber / Ola / Rapido) settle, it posts a notification
/// carrying only the parsed prices (catalog keys + numbers). Works without App Groups, so it runs
/// with a free Apple ID too. Nothing is stored or uploaded by the extension.
final class SampleHandler: RPBroadcastSampleHandler {
    private let configs: [String: AppReaderConfig] = {
        guard let url = Bundle.main.url(forResource: "readers_config", withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return [:] }
        return (try? ReaderConfigs.parse(data)) ?? [:]
    }()
    private var accumulators: [String: FareAccumulator] = [:]
    private var sent: Set<String> = []
    private var started = Date()
    private var lastFrame = Date.distantPast
    private var busy = false
    private static let maxRun: TimeInterval = 5 * 60  // privacy: never left running

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        started = Date()
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video else { return }
        let now = Date()
        if now.timeIntervalSince(started) > Self.maxRun {
            finishBroadcastWithError(NSError(domain: "Hail", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Hail stopped screen reading (5-minute limit)."]))
            return
        }
        guard !busy, now.timeIntervalSince(lastFrame) >= 0.8,
              let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastFrame = now
        busy = true
        defer { busy = false }

        let width = CVPixelBufferGetWidth(pixels), height = CVPixelBufferGetHeight(pixels)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-IN", "en-US"]
        try? VNImageRequestHandler(cvPixelBuffer: pixels, orientation: .up).perform([request])
        let nodes: [ScreenNode] = (request.results ?? []).compactMap { obs in
            guard let text = obs.topCandidates(1).first?.string else { return nil }
            let b = obs.boundingBox
            return ScreenNode.fromVision(text, x: b.minX, y: b.minY, w: b.width, h: b.height, width: width, height: height)
        }

        guard let (app, state) = AppDetector.detect(nodes, configs), !sent.contains(app),
              let cfg = configs[app] else { return }
        let name = app.prefix(1).uppercased() + app.dropFirst()
        if state == .loggedOut {
            post(FareHandoff(app: app, status: "not_logged_in", fares: [], capturedAt: now),
                 body: "\(name) is logged out. Log in there, then try again.")
            return
        }
        let acc = accumulators[app] ?? FareAccumulator()
        accumulators[app] = acc
        acc.add(FareReader.parse(nodes, cfg), at: now)
        if acc.isStable(at: now, quiet: 1.5) {
            post(FareHandoff(app: app, status: "ok", fares: acc.fares, capturedAt: acc.lastChange ?? now),
                 body: "Got \(acc.fares.count) \(name) prices. Tap to continue.")
        }
    }

    private func post(_ h: FareHandoff, body: String) {
        sent.insert(h.app)
        let content = UNMutableNotificationContent()
        content.title = "Hail"
        content.body = body
        content.userInfo = h.userInfo
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "hail-\(h.app)-\(Int(h.capturedAt.timeIntervalSince1970))",
                                  content: content, trigger: nil))
        if sent.count == 3 {  // all three apps read: stop screen reading
            finishBroadcastWithError(NSError(domain: "Hail", code: 0, userInfo: [
                NSLocalizedDescriptionKey: "Hail has all three prices. Screen reading stopped."]))
        }
    }
}
