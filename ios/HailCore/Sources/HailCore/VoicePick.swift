import Foundation

/// Swift port of Android's VoicePick: maps what the user SAID to one option on the card with
/// fixed rules (no AI). Unclear or off-card -> ask again, never guess. Hail never books either way.
public struct SpokenOption: Equatable {
    public let id: String, app: String, product: String, rideType: String
    public init(id: String, app: String, product: String, rideType: String) {
        self.id = id; self.app = app; self.product = product; self.rideType = rideType
    }
}

public enum Pick: Equatable { case choose(String), decline, unclear }

public enum VoicePick {
    static let apps: Set<String> = ["uber", "ola", "rapido"]
    static let rideWords: [String: String] = [
        "auto": "auto", "rickshaw": "auto", "riksha": "auto", "bike": "bike", "scooty": "bike",
        "scooter": "bike", "cab": "cab", "car": "cab", "taxi": "cab",
    ]
    static let thisOne = ["that one", "this one", "the recommended", "the best", "best one", "wahi", "yehi", "yahi"]
    static let affirm: Set<String> = ["yes", "yeah", "yep", "ok", "okay", "sure", "haan", "han", "ha", "theek",
                                      "thik", "chalo", "recommended", "best", "cheapest", "fastest", "book"]
    static let decline: Set<String> = ["cancel", "none", "no", "nahi", "nahin", "nai", "stop", "neither", "mat"]
    static let filler: Set<String> = ["the", "a", "an", "it", "of", "these", "those", "for", "me", "i", "want",
                                      "please", "pls", "plz", "ride", "option", "wala", "wali", "wale", "chahiye",
                                      "de", "do", "karo", "kar", "lo", "le", "lena", "se", "bhai", "bro", "one",
                                      "with", "take", "get", "book", "let", "lets"]
    static let ordinals: [(Set<String>, Int)] = [
        (["first", "1st", "pehla", "pehli", "pahla", "pahli", "one"], 0),
        (["second", "2nd", "doosra", "doosri", "dusra", "dusri", "two"], 1),
        (["third", "3rd", "teesra", "teesri", "tisra", "three"], 2),
    ]

    public static func tokens(_ s: String) -> [String] {
        s.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
    }

    public static func match(_ transcript: String, _ options: [SpokenOption], recommendedId: String) -> Pick {
        guard !options.isEmpty else { return .unclear }
        let text = transcript.lowercased()
        let words = tokens(transcript)
        guard !words.isEmpty else { return .unclear }
        if thisOne.contains(where: { text.contains($0) }) { return .choose(recommendedId) }

        let app = words.first { apps.contains($0) }
        let ride = words.lazy.compactMap { rideWords[$0] }.first
        let ordinalWords = Set(ordinals.flatMap { $0.0 })
        let productWords = Set(words.filter {
            !apps.contains($0) && rideWords[$0] == nil && !affirm.contains($0) && !decline.contains($0)
                && !filler.contains($0) && !ordinalWords.contains($0)
        })
        var candidates = options
        if let a = app { candidates = candidates.filter { $0.app == a } }
        if let r = ride { candidates = candidates.filter { $0.rideType == r } }
        if app != nil || ride != nil || !productWords.isEmpty {
            let scored = candidates.map { o -> (SpokenOption, Double) in
                let own = Set(tokens(o.product).filter { !apps.contains($0) })
                return (o, own.isEmpty ? 0 : Double(own.filter { productWords.contains($0) }.count) / Double(own.count))
            }
            if let best = scored.map({ $0.1 }).max(), best > 0 {
                return .choose(scored.first { $0.1 == best }!.0.id)
            }
            if app != nil || ride != nil {
                if !productWords.isEmpty || candidates.isEmpty { return .unclear }
                return .choose(candidates[0].id)
            }
        }
        for (set, i) in ordinals where words.contains(where: set.contains) && i < options.count {
            return .choose(options[i].id)
        }
        if words.contains(where: decline.contains) { return .decline }
        if words.contains(where: affirm.contains) { return .choose(recommendedId) }
        return .unclear
    }
}
