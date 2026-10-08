import CryptoKit
import UserNotifications
import XCTest

import class NotificationCore.IosBridge

@testable import NotificationDev

final class StorageTests: XCTestCase {
    func testConfigurationDefaultsToHostedApiAndPreservesOverrides() {
        XCTAssertEqual(SdkConfig(projectId: "project").baseURL.absoluteString, "https://app.notification.dev/")
        let localURL = URL(string: "http://localhost:3000/")!
        XCTAssertEqual(SdkConfig(projectId: "project", baseURL: localURL).baseURL, localURL)
    }

    func testEncryptedSnapshotRoundTripAndCorruption() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = SymmetricKey(size: .bits256)
        let storage = try SecureStorage(directory: directory, key: key)
        XCTAssertNil(try storage.load())
        try storage.save("secret installation credential")
        let recreated = try SecureStorage(directory: directory, key: key)
        XCTAssertEqual(try recreated.load(), "secret installation credential")
        let file = directory.appendingPathComponent("state.aesgcm")
        XCTAssertFalse(
            String(decoding: try Data(contentsOf: file), as: UTF8.self).contains("credential"))
        try recreated.save("replacement")
        XCTAssertEqual(try storage.load(), "replacement")
        try Data("corrupt".utf8).write(to: file)
        XCTAssertThrowsError(try storage.load())
    }

    func testWrongKeyNeverReplacesSnapshot() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storage = try SecureStorage(directory: directory, key: SymmetricKey(size: .bits256))
        try storage.save("identity")
        let wrong = try SecureStorage(directory: directory, key: SymmetricKey(size: .bits256))
        XCTAssertThrowsError(try wrong.load())
        XCTAssertEqual(try storage.load(), "identity")
    }

    func testJSONRoundTripPreservesTypesAndRejectsNonFiniteNumbers() throws {
        let value: JSONValue = .object([
            "bool": .bool(true), "number": .number(2), "nested": .array([.null, .string("text")]),
        ])
        XCTAssertEqual(
            try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value)), value)
        XCTAssertThrowsError(try JSONEncoder().encode(JSONValue.number(.infinity)))
    }

    func testClearingSelectsOnlySDKNotifications() {
        let sdk = UNMutableNotificationContent()
        sdk.userInfo = ["notification_dev": ["version": 1]]
        let host = UNMutableNotificationContent()
        host.userInfo = ["other_provider": "data"]
        let requests = [
            UNNotificationRequest(identifier: "sdk", content: sdk, trigger: nil),
            UNNotificationRequest(identifier: "host", content: host, trigger: nil),
        ]

        XCTAssertEqual(NotificationDev.ownedNotificationIdentifiers(requests), ["sdk"])
    }

    func testPermissionMapping() {
        XCTAssertFalse(IOSPushPermissionStatus.isAllowed(.notDetermined))
        XCTAssertFalse(IOSPushPermissionStatus.isAllowed(.denied))
        XCTAssertTrue(IOSPushPermissionStatus.isAllowed(.authorized))
        XCTAssertTrue(IOSPushPermissionStatus.isAllowed(.provisional))
        XCTAssertTrue(IOSPushPermissionStatus.isAllowed(.ephemeral))
    }
}

@MainActor
final class BridgeTests: XCTestCase {
    private var saved: String?
    private var diagnostics: [String] = []
    private var clears = 0

    private func bridge(loadFailure: Bool = false, saveFailure: Bool = false) throws -> IosBridge {
        try IosBridge(
            projectId: "project", baseUrl: "https://example.com", allowLocalhostHttp: false,
            loggingEnabled: false, appVersion: "1", osVersion: "15", locale: "en", timezone: "UTC",
            load: { [self] in
                if loadFailure { return "{\"error\":\"locked\"}" }
                let data = try! JSONEncoder().encode(["value": saved.map(JSONValue.string) ?? .null])
                return String(decoding: data, as: UTF8.self)
            },
            save: { [self] state in
                if saveFailure { return "unavailable" }
                saved = state
                return nil
            }, wake: {}, schedule: { _ in }, diagnostic: { [self] in diagnostics.append($0) },
            clearNotifications: { [self] in clears += 1 }, log: { _, _ in })
    }

    @discardableResult
    private func call(_ bridge: IosBridge, _ method: String, _ args: String = "{}") async throws
        -> String
    {
        try await withCheckedThrowingContinuation { continuation in
            _ = bridge.call(method: method, arguments: args) { value, code, message in
                if let code {
                    continuation.resume(throwing: SdkError(code: code, message: message ?? code))
                } else {
                    continuation.resume(returning: value ?? "null")
                }
            }
        }
    }

    private func state(_ bridge: IosBridge) throws -> SdkState {
        try JSONDecoder().decode(SdkState.self, from: Data(bridge.state().utf8))
    }

    func testStartupLocalEditsAndStateModelRoundTrip() async throws {
        let client = try bridge()
        defer { client.close() }
        XCTAssertEqual(try state(client).availability, .loading)
        try await call(client, "start")
        try await call(client, "requestRefresh")
        let installation = try state(client).installationId
        XCTAssertNotNil(installation)
        try await call(client, "start")
        XCTAssertEqual(try state(client).installationId, installation)
        try await call(
            client, "tags", "{\"values\":{\"plan\":\"premium\",\"count\":3,\"active\":true}}")
        try await call(client, "email", "{\"address\":\"person@example.com\",\"optedIn\":true}")
        XCTAssertEqual(try state(client).user.tags["plan"], .string("premium"))
        XCTAssertEqual(try state(client).user.email?.address, "person@example.com")
        XCTAssertTrue(try state(client).hasPendingChanges)

        let restored = try bridge()
        defer { restored.close() }
        try await call(restored, "start")
        XCTAssertEqual(try state(restored).user, try state(client).user)
        XCTAssertEqual(try state(restored).installationId, installation)
    }

    func testStorageFailureAndValidationAreSwiftErrors() async throws {
        let failed = try bridge(loadFailure: true)
        defer { failed.close() }
        do {
            try await call(failed, "start")
            XCTFail("Expected initialization error")
        } catch { XCTAssertNotNil(error as? SdkError) }
        XCTAssertEqual(try state(failed).lastError?.code, "INITIALIZATION_FAILED")

        let client = try bridge(saveFailure: true)
        defer { client.close() }
        try await call(client, "start")
        try await call(client, "tags", "{\"values\":{\"plan\":\"local\"}}")
        XCTAssertEqual(try state(client).user.tags["plan"], .string("local"))
        XCTAssertTrue(diagnostics.contains { $0.contains("STORAGE_FAILURE") })
        do {
            try await call(client, "email", "{\"address\":\"invalid\",\"optedIn\":true}")
            XCTFail("Expected validation error")
        } catch { XCTAssertNotNil(error as? SdkError) }
    }

    func testAPNsFailureAndSameTokenRecovery() async throws {
        let client = try bridge()
        defer { client.close() }
        try await call(client, "start")
        try await call(client, "device", "{\"token\":\"00ff\"}")
        try await call(client, "registrationFailed")
        XCTAssertEqual(try state(client).pushRegistration.errorCode, "APNS_REGISTRATION_FAILED")
        try await call(client, "device", "{\"enabled\":true}")
        XCTAssertEqual(try state(client).pushRegistration.status, "failed")
        try await call(client, "device", "{\"token\":\"00ff\"}")
        XCTAssertEqual(try state(client).pushRegistration.status, "registered")
        XCTAssertNil(try state(client).pushRegistration.errorCode)
        XCTAssertEqual(NotificationDev.hexToken(Data([0, 255, 16])), "00ff10")
    }

    func testCancellationBeforeReadinessCompletesOnce() async throws {
        let client = try bridge()
        defer { client.close() }
        let completed = expectation(description: "cancelled")
        completed.assertForOverFulfill = true
        let handle = client.call(method: "refresh", arguments: "{}") { _, code, _ in
            XCTAssertEqual(code, "CANCELLED")
            completed.fulfill()
        }
        handle.cancel()
        await fulfillment(of: [completed], timeout: 2)
    }

    func testObservationInitialValueAndCancellation() async throws {
        let client = try bridge()
        defer { client.close() }
        let emitted = expectation(description: "initial state")
        var values: [String] = []
        let handle = client.observe(kind: "state") { value in
            values.append(value)
            emitted.fulfill()
        }
        await fulfillment(of: [emitted], timeout: 2)
        handle.cancel()
        try await call(client, "start")
        await Task.yield()
        XCTAssertEqual(values.count, 1)
    }

    func testNotificationOwnership() {
        XCTAssertNil(NotificationDev.payload(["other_provider": "value"]))
        XCTAssertEqual(NotificationDev.payload(["notification_dev": "invalid"]), .null)
        XCTAssertEqual(
            NotificationDev.payload(["notification_dev": ["version": 1]]),
            .object(["version": .number(1)]))
    }

    func testNotificationEligibilityAndDurableColdStartOpen() async throws {
        let initial = try bridge()
        try await call(initial, "start")
        initial.close()
        var stored = try JSONSerialization.jsonObject(with: Data(saved!.utf8)) as! [String: Any]
        stored["snapshot"] = [
            "installationId": stored["installationId"]!, "associationId": "association",
            "user": ["id": "user"], "revision": 1, "updatedAt": "2026-01-01T00:00:00Z",
        ]
        saved = String(decoding: try JSONSerialization.data(withJSONObject: stored), as: UTF8.self)
        let client = try bridge()
        try await call(client, "start")
        let payload =
            "{\"payload\":{\"version\":1,\"deliveryId\":\"delivery\",\"associationId\":\"association\",\"expiresAt\":4102444800000,\"title\":\"Title\",\"body\":\"Body\"}}"
        let first = try await call(client, "receive", payload)
        let duplicate = try await call(client, "receive", payload)
        XCTAssertEqual(first, "true")
        XCTAssertEqual(duplicate, "false")
        let wrong = try await call(
            client, "open", payload.replacingOccurrences(of: "association\"", with: "other\""))
        XCTAssertEqual(wrong, "false")
        let opened = try await call(client, "open", payload)
        XCTAssertEqual(opened, "true")
        client.close()

        let restored = try bridge()
        defer { restored.close() }
        try await call(restored, "start")
        let emitted = expectation(description: "durable open")
        let handle = restored.observe(kind: "opens") { json in
            let opens = try! JSONDecoder().decode([NotificationOpen].self, from: Data(json.utf8))
            XCTAssertEqual(opens.map(\.interactionId), ["open:delivery"])
            emitted.fulfill()
        }
        await fulfillment(of: [emitted], timeout: 2)
        handle.cancel()
        try await call(restored, "acknowledge", "{\"id\":\"open:delivery\"}")
        try await call(restored, "pushOptIn", "{\"enabled\":false}")
        let rejected = try await call(
            restored, "receive", payload.replacingOccurrences(of: "delivery\"", with: "another\""))
        XCTAssertEqual(rejected, "false")
        XCTAssertEqual(clears, 1)
        let persisted = try JSONSerialization.jsonObject(with: Data(saved!.utf8)) as! [String: Any]
        XCTAssertEqual((persisted["opens"] as! [Any]).count, 0)
    }
}
