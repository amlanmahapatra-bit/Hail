import AVFoundation
import Speech

/// iPhone voice: Apple's speech recognition (on the device when supported) and speech output.
/// Audio never leaves the phone for Hail's servers; only the recognised text is sent.
@MainActor
final class Voice: NSObject, ObservableObject {
    @Published var listening = false
    @Published var partial = ""
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-IN"))
    private let engine = AVAudioEngine()
    private var task: SFSpeechRecognitionTask?
    private let synth = AVSpeechSynthesizer()
    private var onSpoken: (() -> Void)?

    override init() {
        super.init()
        synth.delegate = self
    }

    func listen(_ done: @escaping (String?) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            Task { @MainActor in
                guard status == .authorized else { done(nil); return }
                self.start(done)
            }
        }
    }

    private func start(_ done: @escaping (String?) -> Void) {
        stopSpeaking()
        guard let recognizer, recognizer.isAvailable else { done(nil); return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
        try? session.setActive(true, options: .notifyOthersOnDeactivation)
        let input = engine.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buffer, _ in
            request.append(buffer)
        }
        engine.prepare()
        do { try engine.start() } catch { done(nil); return }
        listening = true
        partial = ""
        var finished = false
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            Task { @MainActor in
                guard let self, !finished else { return }
                if let result { self.partial = result.bestTranscription.formattedString }
                if error != nil || result?.isFinal == true {
                    finished = true
                    self.stopListening()
                    let text = self.partial.trimmingCharacters(in: .whitespaces)
                    done(text.isEmpty ? nil : text)
                }
            }
        }
        // End of utterance: stop after a short pause in speech.
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self, self.listening else { return }
            request.endAudio()
        }
    }

    func stopListening() {
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        task?.cancel()
        task = nil
        listening = false
    }

    func speak(_ text: String, then: (() -> Void)? = nil) {
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-IN")
        onSpoken = then
        synth.speak(u)
    }

    func stopSpeaking() {
        synth.stopSpeaking(at: .immediate)
    }
}

extension Voice: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in
            let next = self.onSpoken
            self.onSpoken = nil
            next?()
        }
    }
}
