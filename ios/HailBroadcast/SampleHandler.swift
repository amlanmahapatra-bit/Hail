import CoreMedia
import HailCore
import ReplayKit
import UserNotifications
import Vision

/// Screen-reading extension (iOS screen broadcast, started by the user with one tap, red
/// indicator while on). It does nothing unless the Hail app has an active, unexpired check
/// request. Frames are read on the device with Apple's text recognition and dropped at once;
/// only the parsed fares (catalog keys + numbers) are written for the app. Nothing is uploaded.
final class SampleHandler: RPBroadcastSampleHandler {
    private var configs: [String: AppReaderConfig] = [:]
    private let accumulator = FareAccumulator()
    private var accumulatorFor: String?
    private var lastFrame = Date.distantPast
    private var lastHeartbeat = Date.distantPast
    private var busy = false
    private var notified: String?

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        configs = Shared.readerConfigs()
        Shared.write(Date(), to: Shared.heartbeatFile)
    }

    override func broadcastFinished() {
        Shared.write(Optional<Date>.none, to: Shared.heartbeatFile)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video else { return }
        let now = Date()
        if now.timeIntervalSince(lastHeartbeat) > 2 {
            lastHeartbeat = now
            Shared.write(now, to: Shared.heartbeatFile)
        }
        guard !busy, now.timeIntervalSince(lastFrame) >= 0.8 else { return }
        guard let req = Shared.read(Shared.CheckRequest.self, from: Shared.requestFile),
              now < req.expiresAt, notified != req.id,
              let cfg = configs[req.app],
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

        if accumulatorFor != req.id {
            accumulatorFor = req.id
            accumulator.reset()
        }
        switch FareReader.state(nodes, cfg) {
        case .loggedOut:
            finish(req, status: "not_logged_in", body: "\(req.app.capitalized) is logged out. Log in there, then try again.")
        case .fareList:
            let wanted = Set(req.rideTypes)
            accumulator.add(FareReader.parse(nodes, cfg).filter { wanted.contains($0.rideType) }, at: now)
            if accumulator.isStable(at: now, quiet: 1.5) {
                finish(req, status: "ok", body: "Got \(accumulator.fares.count) \(req.app.capitalized) prices. Tap to continue.")
            }
        default:
            break
        }
    }

    private func finish(_ req: Shared.CheckRequest, status: String, body: String) {
        notified = req.id
        let result = Shared.CheckResult(requestId: req.id, app: req.app, status: status,
                                        fares: accumulator.fares, capturedAt: accumulator.lastChange ?? Date())
        Shared.write(result, to: Shared.resultFile)
        let content = UNMutableNotificationContent()
        content.title = "Hail"
        content.body = body
        content.userInfo = ["url": "hail://continue"]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: req.id, content: content, trigger: nil))
    }
}
