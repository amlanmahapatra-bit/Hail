import Foundation

/// Swift port of the Android FareReader: screen text in, typed fares out. On iPhone the "nodes"
/// come from on-device text recognition of the screen broadcast (see `ScreenNode.fromVision`).
/// Text read here is untrusted; it is only matched against the catalog and number patterns.
public struct ScreenNode: Equatable {
    public var text: String?
    public var desc: String?
    public var left, top, right, bottom: Int

    public init(_ text: String?, desc: String? = nil, left: Int, top: Int, right: Int, bottom: Int) {
        self.text = text; self.desc = desc
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }

    public var label: String? {
        if let t = text, !t.trimmingCharacters(in: .whitespaces).isEmpty { return t }
        if let d = desc, !d.trimmingCharacters(in: .whitespaces).isEmpty { return d }
        return nil
    }
    public var centerY: Int { (top + bottom) / 2 }
    public var height: Int { max(1, bottom - top) }

    /// Vision's boxes are normalised with the origin at the bottom-left; screens use top-left.
    public static func fromVision(_ text: String, x: Double, y: Double, w: Double, h: Double,
                                  width: Int, height: Int) -> ScreenNode {
        let W = Double(width), H = Double(height)
        return ScreenNode(text, left: Int(x * W), top: Int((1 - y - h) * H),
                          right: Int((x + w) * W), bottom: Int((1 - y) * H))
    }
}

public struct ProductSpec: Equatable {
    public let name: String, key: String, rideType: String?, exclude: String?
}

public struct ScreenMatch {
    public let allText: [String], anyText: [String]
    public func matches(_ labels: [String]) -> Bool {
        if allText.isEmpty && anyText.isEmpty { return false }
        func present(_ m: String) -> Bool { labels.contains { $0.range(of: m, options: .caseInsensitive) != nil } }
        return allText.allSatisfy(present) && (anyText.isEmpty || anyText.contains(where: present))
    }
}

public struct AppReaderConfig {
    public let app: String
    public let fareList, loading, loggedOut, rideInProgress: ScreenMatch
    public let etaPatterns: [NSRegularExpression]
    public let products: [ProductSpec]
    public let pricePattern: NSRegularExpression
}

public enum ScreenState: Equatable { case loggedOut, rideInProgress, loading, fareList, unknown }

public struct Fare: Equatable, Codable {
    public let productKey: String, rideType: String
    public let fareMin: Double, fareMax: Double
    public let etaMinutes: Int?
}

public enum FareReader {
    public static func state(_ nodes: [ScreenNode], _ cfg: AppReaderConfig) -> ScreenState {
        let labels = nodes.compactMap { $0.label }
        if cfg.loggedOut.matches(labels) { return .loggedOut }
        if cfg.rideInProgress.matches(labels) { return .rideInProgress }
        if cfg.loading.matches(labels) { return .loading }
        if cfg.fareList.matches(labels) { return .fareList }
        return .unknown
    }

    /// Every rideable product with a price; excluded and unknown names skipped; first
    /// (topmost) occurrence wins, which de-duplicates repeated sections.
    public static func parse(_ nodes: [ScreenNode], _ cfg: AppReaderConfig) -> [Fare] {
        var byName: [String: ProductSpec] = [:]
        for p in cfg.products { byName[p.name.lowercased()] = p }
        let namesLongestFirst = cfg.products.map { $0.name }.sorted { $0.count > $1.count }
        var out: [Fare] = []
        func add(_ spec: ProductSpec, _ price: (Double, Double), _ eta: Int?) {
            guard spec.exclude == nil, let rt = spec.rideType, !out.contains(where: { $0.productKey == spec.key }) else { return }
            out.append(Fare(productKey: spec.key, rideType: rt, fareMin: price.0, fareMax: price.1, etaMinutes: eta))
        }

        // Mode A: a whole row in one string ("Auto ₹46 1 min").
        for n in nodes.sorted(by: { $0.top < $1.top }) {
            for raw in [n.desc, n.text].compactMap({ $0 }) {
                let s = String(raw.drop(while: { $0 == " " }))
                guard let name = namesLongestFirst.first(where: { startsWithWord(s, $0) }) else { continue }
                if s.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(name) == .orderedSame { continue }
                guard let spec = byName[name.lowercased()],
                      let price = firstPrice(String(s.dropFirst(name.count)), cfg) else { continue }
                add(spec, price, firstEta([s], cfg))
            }
        }

        // Mode B: separate name / ETA / price nodes, grouped into rows by position.
        let anchors = nodes.filter { n in
            guard let l = n.label?.trimmingCharacters(in: .whitespaces).lowercased() else { return false }
            return byName[l] != nil
        }.sorted { $0.top < $1.top }
        guard !anchors.isEmpty else { return out }
        let gaps = zip(anchors, anchors.dropFirst()).map { $1.top - $0.top }.filter { $0 > 0 }.sorted()
        let pitch = gaps.isEmpty ? anchors[0].height * 4 : gaps[gaps.count / 2]
        for (i, a) in anchors.enumerated() {
            guard let spec = byName[a.label!.trimmingCharacters(in: .whitespaces).lowercased()] else { continue }
            let bandTop = a.top - a.height * 6 / 10
            let bandBottom = i + 1 < anchors.count ? anchors[i + 1].top - anchors[i + 1].height * 6 / 10 : a.top + pitch
            let row = nodes.filter { $0 != a && $0.centerY >= bandTop && $0.centerY < bandBottom }
            // Payable price = topmost price in the row; a struck-through original sits below it.
            let price = row.filter { $0.left >= a.left }.sorted { $0.top < $1.top }
                .lazy.compactMap { $0.label.flatMap { firstPrice($0, cfg) } }.first
            guard let p = price else { continue }
            add(spec, p, firstEta(row.compactMap { $0.label }, cfg))
        }
        return out
    }

    static func startsWithWord(_ s: String, _ name: String) -> Bool {
        guard s.lowercased().hasPrefix(name.lowercased()) else { return false }
        guard s.count > name.count else { return true }
        let after = s[s.index(s.startIndex, offsetBy: name.count)]
        return !(after.isLetter || after.isNumber)
    }

    public static func firstPrice(_ s: String, _ cfg: AppReaderConfig) -> (Double, Double)? {
        let ns = s as NSString
        guard let m = cfg.pricePattern.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func group(_ i: Int) -> Double? {
            let r = m.range(at: i)
            guard r.location != NSNotFound, r.length > 0 else { return nil }
            return Double(ns.substring(with: r).replacingOccurrences(of: ",", with: ""))
        }
        guard let lo = group(1) else { return nil }
        let hi = group(2) ?? lo
        guard lo > 0, hi >= lo, hi <= 20000 else { return nil }
        return (lo, hi)
    }

    static func firstEta(_ texts: [String], _ cfg: AppReaderConfig) -> Int? {
        for p in cfg.etaPatterns {
            for t in texts {
                let ns = t as NSString
                if let m = p.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges > 1,
                   let v = Int(ns.substring(with: m.range(at: 1))), (0...180).contains(v) { return v }
            }
        }
        return nil
    }
}
