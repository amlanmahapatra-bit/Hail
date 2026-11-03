import HailCore
import ReplayKit
import SwiftUI

/// Deliberately bare UI (UX comes later): ride-type picker, chat, cards, favourites, mic.
struct ChatView: View {
    @EnvironmentObject var model: ChatModel
    @ObservedObject var voice: Voice
    @State private var input = ""
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Text("Ride type").foregroundStyle(.secondary)
                    Picker("Ride type", selection: $model.rideType) {
                        Text("Any (ask me)").tag(RideCategory?.none)
                        ForEach(RideCategory.allCases, id: \.self) { Text($0.label).tag(RideCategory?.some($0)) }
                    }
                    Spacer()
                }
                .padding(.horizontal)

                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(model.items) { item in row(item).id(item.id) }
                        }
                        .padding()
                    }
                    .onChange(of: model.items.count) { _, _ in
                        if let last = model.items.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                }

                if let p = model.pending { pendingBar(p) }
                favoritesBar
                HStack {
                    TextField("e.g. to Koramangala, cheapest", text: $input)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(send)
                    Button("Send", action: send).disabled(model.busy)
                    Button { model.startVoice() } label: {
                        Image(systemName: voice.listening ? "waveform" : "mic.fill")
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.busy)
                }
                .padding()
                if voice.listening { Text("🎤 \(voice.partial)").font(.footnote).padding(.bottom, 6) }
            }
            .navigationTitle("Hail")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("New") { model.newSession() } }
                ToolbarItem(placement: .topBarTrailing) { Button("Settings") { showSettings = true } }
            }
            .sheet(isPresented: $showSettings) { SettingsView().environmentObject(model) }
        }
    }

    private func send() {
        model.send(input)
        input = ""
    }

    @ViewBuilder
    private func row(_ item: ChatModel.Item) -> some View {
        switch item.kind {
        case .user(let t): Text("You: \(t)").frame(maxWidth: .infinity, alignment: .trailing)
        case .hail(let t): Text("Hail: \(t)")
        case .step(let t): Text("… \(t)").font(.footnote).foregroundStyle(.secondary)
        case .error(let t): Text(t).foregroundStyle(.red)
        case .ask(let choices):
            HStack {
                ForEach(choices, id: \.self) { c in
                    Button(c.label) { model.send(c.label, category: c) }
                        .buttonStyle(.bordered).font(.footnote)
                        .disabled(model.busy || model.items.last?.id != item.id)
                }
            }
        case .card(let card): CardView(card: card, id: item.id)
        }
    }

    private func pendingBar(_ p: ChatModel.PendingCheck) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(p.hint).font(.footnote)
            HStack {
                if !Shared.screenReadingIsOn() {
                    BroadcastPicker().frame(width: 44, height: 44)
                    Text("Start screen reading").font(.footnote)
                }
                Spacer()
                Button("Open \(RideApps.names[p.app] ?? p.app)") { Task { await model.openForCheck() } }
                    .buttonStyle(.borderedProminent)
                Button("Skip") { Task { await model.skipPending() } }
                Button("Stop", role: .destructive) { Task { await model.stop() } }
            }
        }
        .padding()
        .background(Color.yellow.opacity(0.15))
    }

    private var favoritesBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(Array(model.favorites.enumerated()), id: \.offset) { _, f in
                    Button("♥ \(f["label"] as? String ?? "")") { model.go(to: f) }
                        .buttonStyle(.bordered)
                        .contextMenu { Button("Remove", role: .destructive) { model.removeFavorite(f) } }
                }
            }
            .padding(.horizontal)
        }
    }
}

/// Options for the chosen ride type, best first; top 4 + "Show more"; live time; ♡.
struct CardView: View {
    @EnvironmentObject var model: ChatModel
    let card: ChatModel.Card
    let id: UUID
    @State private var showAll = false

    var body: some View {
        let used = model.usedCards.contains(id)
        VStack(alignment: .leading, spacing: 8) {
            Text("\(card.category) · \(card.options.count) options").font(.headline)
            Text(pricesLine).font(.caption).foregroundStyle(card.live ? .green : .orange)
            Text(card.text).font(.subheadline)
            ForEach(Array(card.options.enumerated()), id: \.offset) { i, o in
                if showAll || i < 4 {
                    Button { model.pick(id, optionId: o["option_id"] as? String ?? "", token: card.token, accept: true) } label: {
                        VStack(alignment: .leading) {
                            Text("\((o["option_id"] as? String) == card.best ? "★ " : "")\(ChatModel.name(o))   \(ChatModel.fare(o))").bold()
                            Text(detail(o)).font(.caption)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.bordered)
                    .disabled(used)
                }
            }
            if !showAll && card.options.count > 4 { Button("Show \(card.options.count - 4) more") { showAll = true } }
            Button("None of these") { model.pick(id, optionId: card.best, token: card.token, accept: false) }
                .disabled(used)
            if let drop = card.drop, let label = drop["label"] as? String, label != "Current location" {
                Button(model.isFavorite(label) ? "♥ Saved to favourites" : "♡ Save \(label) to favourites") {
                    model.toggleFavorite(drop)
                }
            }
        }
        .padding()
        .background(Color.blue.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func detail(_ o: [String: Any]) -> String {
        var bits: [String] = []
        if let eta = o["eta_minutes"] as? Int { bits.append("\(eta) min away") }
        if let s = o["saves"] as? Int { bits.append("save ₹\(s)") }
        return bits.joined(separator: " · ")
    }

    private var pricesLine: String {
        var time: String?
        if let s = card.checkedAt {
            let f = ISO8601DateFormatter()
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let d = f.date(from: s) ?? ISO8601DateFormatter().date(from: s)
            time = d.map { $0.formatted(date: .omitted, time: .standard) }
        }
        if !card.live { return "Test prices, not live" + (time.map { " · \($0)" } ?? "") }
        return time.map { "Live prices · updated \($0)" } ?? "Live prices"
    }
}

/// Apple's own "start screen broadcast" button, pointed at Hail's screen-reading extension.
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        v.preferredExtension = (Bundle.main.bundleIdentifier ?? "app.hail.ios") + ".broadcast"
        v.showsMicrophoneButton = false
        return v
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}

struct SettingsView: View {
    @EnvironmentObject var model: ChatModel
    @Environment(\.dismiss) var dismiss
    @AppStorage("backend_url") var backend = "http://localhost:8000"
    @State private var status = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Backend") { TextField("Backend URL", text: $backend).autocorrectionDisabled().textInputAutocapitalization(.never) }
                Section("Screen reading") {
                    Text("Hail reads prices from Uber, Ola and Rapido only while you check fares, using iPhone screen broadcast. Frames are read on your iPhone and dropped; only prices are kept. Stop it any time from the red indicator.")
                        .font(.footnote)
                    HStack { BroadcastPicker().frame(width: 44, height: 44); Text("Start / stop screen reading") }
                }
                Section("Privacy") {
                    Button("Delete my data", role: .destructive) {
                        Task {
                            do { try await model.api.deleteMyData(); status = "Deleted." } catch { status = "Couldn't reach the server." }
                        }
                    }
                    if !status.isEmpty { Text(status).font(.footnote) }
                }
            }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
