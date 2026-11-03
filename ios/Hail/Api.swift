import Foundation

/// Backend client: the same protocol as Android (docs/protocol.md). The device token lives in
/// the Keychain.
final class Api {
    var baseURL: URL {
        URL(string: UserDefaults.standard.string(forKey: "backend_url") ?? "http://localhost:8000")!
    }

    struct HTTPError: Error { let status: Int }

    private var token: String? {
        get { Keychain.get("device_token") }
        set { if let newValue { Keychain.set("device_token", newValue) } else { Keychain.delete("device_token") } }
    }

    func ensureRegistered() async throws {
        if token != nil { return }
        // iPhone: Hail reads prices through screen reading while the user flips through the apps.
        let body: [String: Any] = ["platform": "ios", "capabilities": ["can_check_fares": true, "can_book": false]]
        let res = try await request("POST", "/v1/devices", body, auth: false)
        token = res["token"] as? String
    }

    func post(session: String, _ body: [String: Any]) async throws -> [String: Any] {
        try await ensureRegistered()
        do {
            return try await request("POST", "/v1/sessions/\(session)/messages", body)
        } catch let e as HTTPError where e.status == 401 {
            token = nil
            try await ensureRegistered()
            return try await request("POST", "/v1/sessions/\(session)/messages", body)
        }
    }

    func readersConfig() async throws -> Data {
        let (data, resp) = try await URLSession.shared.data(from: baseURL.appendingPathComponent("v1/config/readers"))
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw HTTPError(status: 0) }
        return data
    }

    func favorites() async throws -> [[String: Any]] {
        try await ensureRegistered()
        return try await request("GET", "/v1/favorites", nil)["favorites"] as? [[String: Any]] ?? []
    }

    func addFavorite(label: String, lat: Double?, lng: Double?) async throws -> [String: Any] {
        try await ensureRegistered()
        var body: [String: Any] = ["label": label]
        if let lat, let lng { body["lat"] = lat; body["lng"] = lng }
        return try await request("POST", "/v1/favorites", body)
    }

    func removeFavorite(id: Int) async throws {
        try await ensureRegistered()
        _ = try await request("DELETE", "/v1/favorites/\(id)", nil)
    }

    func deleteMyData() async throws {
        guard token != nil else { return }
        _ = try await request("DELETE", "/v1/me", nil)
        token = nil
    }

    private func request(_ method: String, _ path: String, _ body: [String: Any]?, auth: Bool = true) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: path, relativeTo: baseURL)!)
        req.httpMethod = method
        req.timeoutInterval = 90
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if auth, let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw HTTPError(status: status) }
        if data.isEmpty { return [:] }
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
}

/// Minimal Keychain wrapper for the device token.
enum Keychain {
    static func set(_ key: String, _ value: String) {
        delete(key)
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                kSecValueData as String: Data(value.utf8),
                                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func delete(_ key: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: key]
        SecItemDelete(q as CFDictionary)
    }
}
