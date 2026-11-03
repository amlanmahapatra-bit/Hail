import Foundation

/// Same categories and words as the backend (app/categories.py) and Android (RideCategories.kt).
public enum RideCategory: String, CaseIterable, Codable {
    case auto, bike, cab, premium, cab_xl

    public var label: String {
        switch self {
        case .auto: return "Auto"
        case .bike: return "Bike"
        case .cab: return "Cab"
        case .premium: return "Premium"
        case .cab_xl: return "Cab XL"
        }
    }

    private static let patterns: [(RideCategory, String)] = [
        (.cab_xl, #"\bxl\b|\bsuv\b|\bbig(?:ger)? (?:cab|car|gaadi)\b|\blarge (?:cab|car)\b|\b(?:6|six)[ -]?seater\b|\binnova\b|\bxuv\b|\bbadi gaadi\b"#),
        (.premium, #"\bpremium\b|\bsedan\b|\bluxury\b|\bblack\b|\bprime\b|\bpremier\b"#),
        (.auto, #"\bauto\b|\brick?shaw\b|\briksha\b|\brikshaw\b|\btuk ?tuk\b"#),
        (.bike, #"\bbike\b|\bscooty\b|\bscooter\b|\btwo[ -]?wheeler\b|\bmoto\b"#),
        (.cab, #"\bcab\b|\bcar\b|\btaxi\b|\bgaadi\b|\bgadi\b"#),
    ]

    /// "rickshaw" -> .auto, "big cab" -> .cab_xl, "sedan" -> .premium, anything else -> nil.
    public static func detect(_ text: String) -> RideCategory? {
        let t = text.lowercased()
        return patterns.first { t.range(of: $0.1, options: .regularExpression) != nil }?.0
    }
}
