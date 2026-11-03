import Foundation
import HailCore

/// Files shared between the app and the screen-reading extension (App Group container).
/// Only a check request, its result (catalog keys + numbers) and a heartbeat live here.
/// No screen images, no screen text.
enum Shared {
    static let groupID = "group.app.hail.ios"

    static var dir: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID)
            ?? FileManager.default.temporaryDirectory
    }

    /// Written by the app before it opens a ride app; the extension reads only while this is live.
    struct CheckRequest: Codable {
        let id: String
        let app: String
        let rideTypes: [String]
        let startedAt: Date
        let expiresAt: Date
    }

    /// Written by the extension: what it read for that request.
    struct CheckResult: Codable {
        let requestId: String
        let app: String
        let status: String  // ok | not_logged_in | failed
        let fares: [Fare]
        let capturedAt: Date
    }

    static func write<T: Encodable>(_ value: T?, to name: String) {
        let url = dir.appendingPathComponent(name)
        guard let value else { try? FileManager.default.removeItem(at: url); return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .secondsSince1970
        try? enc.encode(value).write(to: url, options: .atomic)
    }

    static func read<T: Decodable>(_ type: T.Type, from name: String) -> T? {
        guard let data = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
        let dec = JSONDecoder()
        dec.dateDecodingStrategy = .secondsSince1970
        return try? dec.decode(type, from: data)
    }

    static let requestFile = "check_request.json"
    static let resultFile = "check_result.json"
    static let heartbeatFile = "heartbeat.json"
    static let configFile = "readers_config.json"

    /// Reader config: the copy the app last fetched from the backend, else the bundled one.
    static func readerConfigs() -> [String: AppReaderConfig] {
        if let d = try? Data(contentsOf: dir.appendingPathComponent(configFile)),
           let c = try? ReaderConfigs.parse(d) { return c }
        if let url = Bundle.main.url(forResource: "readers_config", withExtension: "json"),
           let d = try? Data(contentsOf: url), let c = try? ReaderConfigs.parse(d) { return c }
        return [:]
    }

    /// The extension writes this every couple of seconds while screen reading is on.
    static func screenReadingIsOn(now: Date = Date()) -> Bool {
        guard let t = read(Date.self, from: heartbeatFile) else { return false }
        return now.timeIntervalSince(t) < 6
    }
}
