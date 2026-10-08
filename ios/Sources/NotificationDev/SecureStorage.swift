import CryptoKit
import Foundation
import Security

/// A single process owns this file. Extensions must not instantiate the SDK.
final class SecureStorage {
    private let file: URL
    private let key: SymmetricKey

    init(directory: URL? = nil, key suppliedKey: SymmetricKey? = nil) throws {
        let root =
            try directory
            ?? FileManager.default.url(
                for: .applicationSupportDirectory, in: .userDomainMask,
                appropriateFor: nil, create: true
            ).appendingPathComponent("dev.notification.sdk", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var excluded = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        file = root.appendingPathComponent("state.aesgcm")
        key =
            try suppliedKey
            ?? Self.loadKey(existingSnapshot: FileManager.default.fileExists(atPath: file.path))
    }

    func load() throws -> String? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let box = try AES.GCM.SealedBox(combined: Data(contentsOf: file))
        let data = try AES.GCM.open(box, using: key)
        guard let value = String(data: data, encoding: .utf8) else {
            throw SdkError(code: "STORAGE_FAILURE", message: "Stored state is not UTF-8")
        }

        return value
    }

    func save(_ state: String) throws {
        let box = try AES.GCM.seal(Data(state.utf8), using: key)
        guard let data = box.combined else {
            throw SdkError(code: "STORAGE_FAILURE", message: "Could not encrypt SDK state")
        }
        try data.write(
            to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func loadKey(existingSnapshot: Bool) throws -> SymmetricKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "dev.notification.sdk.storage",
            kSecAttrAccount as String: Bundle.main.bundleIdentifier ?? "default",
        ]
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &item)
        if status == errSecSuccess, let data = item as? Data, data.count == 32 {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound, !existingSnapshot else {
            throw SdkError(code: "STORAGE_UNAVAILABLE", message: "SDK encryption key is unavailable")
        }

        let key = SymmetricKey(size: .bits256)
        var insertion = query
        insertion[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        insertion[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(insertion as CFDictionary, nil)
        guard added == errSecSuccess else {
            throw SdkError(code: "STORAGE_UNAVAILABLE", message: "Could not save SDK encryption key")
        }

        return key
    }
}
