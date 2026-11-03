import Foundation
import HailCore
import SwiftUI
import UserNotifications

/// One conversation with the backend. Same protocol as Android; the difference is how a fare
/// check runs on iPhone: Hail opens the ride app, the screen-reading extension reads the prices
/// while the user looks at the list, and the user taps the notification to come back.
@MainActor
final class ChatModel: ObservableObject {
    struct Item: Identifiable {
        enum Kind { case user(String), hail(String), step(String), ask([RideCategory]), card(Card), error(String) }
        let id = UUID()
        let kind: Kind
    }

    struct Card {
        let raw: [String: Any]
        var category: String { RideCategory(rawValue: raw["ride_category"] as? String ?? "")?.label ?? "Rides" }
        var options: [[String: Any]] { raw["options"] as? [[String: Any]] ?? [] }
        var best: String { raw["recommended_option_id"] as? String ?? "" }
        var token: String { (raw["confirmation"] as? [String: Any])?["token"] as? String ?? "" }
        var text: String { raw["text"] as? String ?? "" }
        var drop: [String: Any]? { raw["drop"] as? [String: Any] }
        var live: Bool { raw["live"] as? Bool ?? true }
        var checkedAt: String? { raw["prices_checked_at"] as? String }
    }

    /// A fare check waiting for the user to look at a ride app.
    struct PendingCheck {
        let actionId: String
        let requestId: String
        let app: String
        let rideTypes: Set<String>
        var hint: String
    }

    @Published var items: [Item] = []
    @Published var busy = false
    @Published var pending: PendingCheck?
    @Published var usedCards: Set<UUID> = []
    @Published var favorites: [[String: Any]] = []
    @Published var rideType: RideCategory? = RideCategory(rawValue: UserDefaults.standard.string(forKey: "ride_category") ?? "") {
        didSet { UserDefaults.standard.set(rideType?.rawValue, forKey: "ride_category") }
    }

    let api = Api()
    let voice = Voice()
    private let here = Here()
    private var session = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    private var config: [String: Any]?
    private var voiceMode = false

    init() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        items.append(Item(kind: .hail("Where to? Tap 🎤 and say \"Koramangala, cheapest\", or type it.")))
        Task { await loadConfig(); await refreshFavorites() }
    }

    // MARK: sending

    func send(_ text: String, spoken: Bool = false, category: RideCategory? = nil, destination: [String: Any]? = nil) {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, !busy else { return }
        voiceMode = spoken
        items.append(Item(kind: .user(t)))
        // Words win ("rickshaw", "sedan"), then the picker; the last type used is remembered.
        let cat = category ?? RideCategory.detect(t) ?? rideType
        if let cat, cat != rideType { rideType = cat }
        Task {
            var body: [String: Any] = ["type": "user_message", "text": t, "input_mode": spoken ? "voice" : "text"]
            if let cat { body["ride_category"] = cat.rawValue }
            if let destination { body["destination"] = destination }
            if let loc = await here.current() { body["location"] = loc }
            await run(body)
        }
    }

    func newSession() {
        session = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        pending = nil
        Shared.write(Optional<Shared.CheckRequest>.none, to: Shared.requestFile)
        items = [Item(kind: .hail("New request. Where to?"))]
    }

    private func run(_ body: [String: Any]) async {
        busy = true
        do {
            let out = try await api.post(session: session, body)
            await handle(out)
        } catch {
            busy = false
            say(.error("Can't reach Hail. Check the backend address in Settings."))
        }
    }

    private func handle(_ out: [String: Any]) async {
        switch out["type"] as? String {
        case "action_request":
            await startCheck(out)
        case "handoff_request":
            await handoff(out)
        case "recommendation":
            busy = false
            say(.card(Card(raw: out)))
            if voiceMode { speakOptions(Card(raw: out)) }
        case "assistant_message":
            busy = false
            let text = out["text"] as? String ?? ""
            if let ask = out["ask"] as? [String: Any], let choices = ask["choices"] as? [String] {
                say(.hail(text))
                say(.ask(choices.compactMap { RideCategory(rawValue: $0) }))
                if voiceMode { voice.speak(text) { self.listenForRideType() } }
            } else {
                say(.hail(text))
                if voiceMode { voice.speak(text) }
            }
        default:
            busy = false
            let msg = out["message"] as? String ?? "Something went wrong."
            say(.error(msg))
            if voiceMode { voice.speak(msg) }
        }
    }

    // MARK: the guided fare check

    private func startCheck(_ out: [String: Any]) async {
        guard let action = out["action"] as? [String: Any], let app = action["app"] as? String,
              let actionId = out["action_id"] as? String else { return }
        let rideTypes = action["ride_types"] as? [String] ?? ["cab", "auto", "bike"]
        let name = RideApps.names[app] ?? app
        lastActionRaw = action
        let req = Shared.CheckRequest(id: UUID().uuidString, app: app, rideTypes: rideTypes,
                                      startedAt: Date(), expiresAt: Date().addingTimeInterval(180))
        Shared.write(Optional<Shared.CheckResult>.none, to: Shared.resultFile)
        Shared.write(req, to: Shared.requestFile)
        pending = PendingCheck(actionId: actionId, requestId: req.id, app: app, rideTypes: Set(rideTypes),
                               hint: "Checking \(name)…")
        if !Shared.screenReadingIsOn() {
            pending?.hint = "Turn on screen reading (button below), then tap Open \(name)."
            return
        }
        await openForCheck()
    }

    /// Opens the pending app; the extension reads its fare list while the user looks at it.
    func openForCheck() async {
        guard let p = pending, let action = lastAction else { return }
        let r = await RideApps.open(app: p.app, pickup: action["pickup"] as? [String: Any] ?? [:],
                                    drop: action["drop"] as? [String: Any] ?? [:], config: config)
        pending?.hint = r.hint + " When the prices show, wait for Hail's notification and tap it."
        say(.step("\(RideApps.names[p.app] ?? p.app): \(r.hint)"))
    }

    /// Called when Hail comes back to the front (notification tap or app switch).
    func resume() async {
        guard let p = pending, let result = Shared.read(Shared.CheckResult.self, from: Shared.resultFile),
              result.requestId == p.requestId else { return }
        pending = nil
        Shared.write(Optional<Shared.CheckRequest>.none, to: Shared.requestFile)
        var body: [String: Any] = ["type": "tool_result", "action_id": p.actionId, "status": result.status]
        if result.status == "ok" {
            body["observations"] = Observations.json(app: p.app, fares: result.fares, capturedAt: result.capturedAt,
                                                     confidence: 0.85, rideTypes: p.rideTypes)
        }
        await run(body)
    }

    /// "Skip Rapido": not installed, logged out, or the user doesn't want it checked.
    func skipPending() async {
        guard let p = pending else { return }
        pending = nil
        Shared.write(Optional<Shared.CheckRequest>.none, to: Shared.requestFile)
        await run(["type": "tool_result", "action_id": p.actionId, "status": "failed"])
    }

    func stop() async {
        guard let p = pending else { return }
        pending = nil
        Shared.write(Optional<Shared.CheckRequest>.none, to: Shared.requestFile)
        await run(["type": "tool_result", "action_id": p.actionId, "status": "stopped_by_user"])
    }

    // MARK: picking a ride

    func pick(_ card: UUID, optionId: String, token: String, accept: Bool, via: String = "tap") {
        guard !usedCards.contains(card) else { return }
        usedCards.insert(card)
        voice.stopSpeaking()
        Task {
            await run(["type": "confirmation", "option_id": optionId, "token": token, "accept": accept, "via": via])
        }
    }

    /// The winning app opens on the same trip; the user taps Book there. Hail never books.
    private func handoff(_ out: [String: Any]) async {
        guard let action = out["action"] as? [String: Any], let app = action["app"] as? String,
              let actionId = out["action_id"] as? String else { return }
        let r = await RideApps.open(app: app, pickup: action["pickup"] as? [String: Any] ?? [:],
                                    drop: action["drop"] as? [String: Any] ?? [:], config: config)
        if let ride = action["product_name"] as? String { say(.step("Pick \(ride) in \(RideApps.names[app] ?? app) and tap Book.")) }
        if !r.opened { say(.step(r.hint)) }
        await run(["type": "tool_result", "action_id": actionId, "status": "ok", "handoff": ["opened": true]])
    }

    // MARK: voice

    func startVoice() {
        guard !busy else { return }
        voice.listen { [weak self] text in
            guard let self, let text else { return }
            self.send(text, spoken: true)
        }
    }

    private func listenForRideType() {
        voice.listen { [weak self] text in
            guard let self, let text, let c = RideCategory.detect(text) else { return }
            self.send(text, spoken: true, category: c)
        }
    }

    private func speakOptions(_ card: Card) {
        let top = card.options.prefix(3).map { o -> String in
            let price = Int(((o["fare_min"] as? Double) ?? 0).rounded())
            return "\(Self.name(o)), \(price) rupees"
        }.joined(separator: ". ")
        let id = items.last?.id
        voice.speak("\(card.text) Top options: \(top). Which one?") { [weak self] in
            self?.voice.listen { text in
                guard let self, let text, let id else { return }
                let opts = card.options.map {
                    SpokenOption(id: $0["option_id"] as? String ?? "", app: $0["app"] as? String ?? "",
                                 product: $0["product_name"] as? String ?? "", rideType: $0["ride_type"] as? String ?? "")
                }
                switch VoicePick.match(text, opts, recommendedId: card.best) {
                case .choose(let o): self.pick(id, optionId: o, token: card.token, accept: true, via: "voice")
                case .decline: self.pick(id, optionId: card.best, token: card.token, accept: false, via: "voice")
                case .unclear: self.voice.speak("Sorry, tap the ride you want.")
                }
            }
        }
    }

    // MARK: favourites

    func refreshFavorites() async {
        if let f = try? await api.favorites() { favorites = f }
    }

    func toggleFavorite(_ drop: [String: Any]) {
        let label = drop["label"] as? String ?? ""
        Task {
            if let existing = favorites.first(where: { ($0["label"] as? String)?.caseInsensitiveCompare(label) == .orderedSame }),
               let id = existing["id"] as? Int {
                try? await api.removeFavorite(id: id)
            } else {
                _ = try? await api.addFavorite(label: label, lat: drop["lat"] as? Double, lng: drop["lng"] as? Double)
            }
            await refreshFavorites()
        }
    }

    func removeFavorite(_ f: [String: Any]) {
        guard let id = f["id"] as? Int else { return }
        Task { try? await api.removeFavorite(id: id); await refreshFavorites() }
    }

    func go(to f: [String: Any]) {
        let label = f["label"] as? String ?? ""
        send("to \(label)", destination: ["label": label, "lat": f["lat"] ?? NSNull(), "lng": f["lng"] ?? NSNull()])
    }

    func isFavorite(_ label: String) -> Bool {
        favorites.contains { ($0["label"] as? String)?.caseInsensitiveCompare(label) == .orderedSame }
    }

    // MARK: helpers

    private var lastAction: [String: Any]? { lastActionRaw }
    private var lastActionRaw: [String: Any]?

    private func say(_ kind: Item.Kind) {
        items.append(Item(kind: kind))
    }

    private func loadConfig() async {
        if let data = try? await api.readersConfig() {
            try? data.write(to: Shared.dir.appendingPathComponent(Shared.configFile), options: .atomic)
            config = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        } else if let url = Bundle.main.url(forResource: "readers_config", withExtension: "json"),
                  let d = try? Data(contentsOf: url) {
            config = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
        }
    }

    static func name(_ o: [String: Any]) -> String {
        let app = RideApps.names[o["app"] as? String ?? ""] ?? ""
        let product = o["product_name"] as? String ?? o["ride_type"] as? String ?? ""
        return product.lowercased().hasPrefix(app.lowercased()) ? product : "\(app) \(product)"
    }

    static func fare(_ o: [String: Any]) -> String {
        func rs(_ v: Double) -> String { v == v.rounded() ? "₹\(Int(v))" : String(format: "₹%.2f", v) }
        let lo = o["fare_min"] as? Double ?? 0, hi = o["fare_max"] as? Double ?? lo
        return lo == hi ? rs(lo) : "\(rs(lo))–\(rs(hi))"
    }
}
