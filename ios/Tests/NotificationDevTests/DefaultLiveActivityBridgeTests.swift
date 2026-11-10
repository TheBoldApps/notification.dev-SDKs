import XCTest

@testable import NotificationDev

@MainActor
final class DefaultLiveActivityBridgeTests: XCTestCase {
    private let provider = "00000000-0000-0000-0000-000000000001"

    private func manager() -> LiveActivities {
        LiveActivities(perform: { _, _ in }, states: { AsyncStream { $0.finish() } }, report: { _ in })
    }

    func testPublicConfigurationRestoresBeforeJavaScriptOrDartLaunch() throws {
        guard #available(iOS 16.1, *) else { return }
        let name = "dev.notification.test.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let config = DefaultLiveActivityConfiguration(providerConfigurationId: provider, enablePushToStart: false)
        let original = DefaultLiveActivityBridge(
            manager: manager(), scope: "project|api", defaults: defaults, report: { _ in XCTFail() })
        try original.configure(config)

        let restoredManager = manager()
        let restored = DefaultLiveActivityBridge(
            manager: restoredManager, scope: "project|api", defaults: defaults, report: { _ in XCTFail() })
        restored.restoreConfiguration()
        try restoredManager.setupDefault(providerConfigurationId: provider, options: .init(enablePushToStart: false))
        XCTAssertThrowsError(
            try restoredManager.setupDefault(providerConfigurationId: provider, options: .init(enablePushToStart: true))
        )
        XCTAssertEqual(
            defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix("dev.notification.sdk.") },
            ["dev.notification.sdk.defaultLiveActivityConfiguration"])
        let data = defaults.data(forKey: "dev.notification.sdk.defaultLiveActivityConfiguration")!
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertFalse(json.contains("registrationToken"))
        XCTAssertFalse(json.contains("pushToken"))
    }

    func testAnotherProjectDoesNotRestoreThePreviousProvider() throws {
        guard #available(iOS 16.1, *) else { return }
        let name = "dev.notification.test.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        try DefaultLiveActivityBridge(manager: manager(), scope: "old", defaults: defaults, report: { _ in }).configure(
            .init(providerConfigurationId: provider))
        let newManager = manager()
        DefaultLiveActivityBridge(manager: newManager, scope: "new", defaults: defaults, report: { _ in XCTFail() })
            .restoreConfiguration()
        try newManager.setupDefault(providerConfigurationId: "00000000-0000-0000-0000-000000000002")
    }

    func testBadConfigurationDoesNotReplaceWorkingStartupSettings() throws {
        guard #available(iOS 16.1, *) else { return }
        let name = "dev.notification.test.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let bridge = DefaultLiveActivityBridge(manager: manager(), scope: "scope", defaults: defaults, report: { _ in })
        try bridge.configure(.init(providerConfigurationId: provider))
        let saved = defaults.data(forKey: "dev.notification.sdk.defaultLiveActivityConfiguration")
        XCTAssertThrowsError(try bridge.configure(.init(providerConfigurationId: "bad")))
        XCTAssertEqual(defaults.data(forKey: "dev.notification.sdk.defaultLiveActivityConfiguration"), saved)
    }

    func testChangedConfigurationCanApplyOnNextNativeLaunchWithoutReinstall() throws {
        guard #available(iOS 16.1, *) else { return }
        let name = "dev.notification.test.\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let bridge = DefaultLiveActivityBridge(manager: manager(), scope: "scope", defaults: defaults, report: { _ in })
        try bridge.configure(.init(providerConfigurationId: provider))
        let next = "00000000-0000-0000-0000-000000000002"
        XCTAssertThrowsError(try bridge.configure(.init(providerConfigurationId: next, enablePushToStart: false))) {
            XCTAssertEqual(($0 as? SdkError)?.code, "LIVE_ACTIVITY_RESTART_REQUIRED")
        }
        let restoredManager = manager()
        let restored = DefaultLiveActivityBridge(
            manager: restoredManager, scope: "scope", defaults: defaults, report: { _ in XCTFail() })
        restored.restoreConfiguration()
        try restoredManager.setupDefault(providerConfigurationId: next, options: .init(enablePushToStart: false))
    }

    func testUnknownActivityCannotBeEndedOrExposed() async throws {
        guard #available(iOS 16.1, *) else { return }
        let bridge = DefaultLiveActivityBridge(manager: manager(), scope: "test", defaults: .standard, report: { _ in })
        let list = try await bridge.invoke("liveActivities.getActivities", arguments: [:])
        XCTAssertEqual(list, .array([]))
        do {
            _ = try await bridge.invoke(
                "liveActivities.end", arguments: ["activityId": .string("not-owned"), "content": .object([:])])
            XCTFail("Must not end unknown or other-association activities")
        } catch let error as SdkError {
            XCTAssertEqual(error.code, "LIVE_ACTIVITY_NOT_FOUND")
        }
        do {
            _ = try await bridge.invoke(
                "liveActivities.end",
                arguments: [
                    "activityId": .string("not-owned"), "content": .object([:]), "dismissalDate": .string("bad"),
                ])
            XCTFail("Must validate timestamps")
        } catch let error as SdkError { XCTAssertEqual(error.code, "INVALID_ARGUMENT") }
    }

    func testConcurrentStartsCannotCreateDuplicateActivities() async throws {
        guard #available(iOS 16.1, *) else { return }
        var pending: CheckedContinuation<Void, Error>?
        var blockFirst = true
        let manager = LiveActivities(
            perform: { _, _ in
                if blockFirst {
                    blockFirst = false
                    try await withCheckedThrowingContinuation { pending = $0 }
                } else {
                    throw CancellationError()
                }
            }, states: { AsyncStream { $0.finish() } }, report: { _ in })
        try manager.setupDefault(providerConfigurationId: provider)
        let bridge = DefaultLiveActivityBridge(manager: manager, scope: "test", report: { _ in })
        let args: [String: JSONValue] = [
            "activityId": .string("concurrent"), "attributes": .object([:]),
            "content": .object([:]), "registrationToken": .string(String(repeating: "g", count: 43)),
        ]
        let first = Task { try await bridge.invoke("liveActivities.start", arguments: args) }
        for _ in 0..<1000 {
            if pending != nil { break }
            await Task.yield()
        }
        guard let gate = pending else {
            first.cancel()
            return XCTFail("Start did not reach the binding fence")
        }
        do {
            _ = try await bridge.invoke("liveActivities.start", arguments: args)
            XCTFail("Concurrent start must not request a second ActivityKit UI")
        } catch let error as SdkError { XCTAssertEqual(error.code, "LIVE_ACTIVITY_ALREADY_EXISTS") }
        gate.resume(throwing: CancellationError())
        do {
            _ = try await first.value
            XCTFail()
        } catch is CancellationError {}
        // Failed starts release the fence so callers can retry after fixing their error.
        do {
            _ = try await bridge.invoke("liveActivities.start", arguments: args)
            XCTFail()
        } catch is CancellationError {}
    }

    func testCapabilitiesAreIndependentOfPushPermissions() async throws {
        let bridge = DefaultLiveActivityBridge(manager: manager(), scope: "test", report: { _ in })
        guard case .object(let result) = try await bridge.invoke("liveActivities.getCapabilities", arguments: [:])
        else { return XCTFail() }
        XCTAssertEqual(Set(result.keys), Set(["supported", "activitiesEnabled", "pushToStartSupported"]))
        if #available(iOS 17.2, *) {
            XCTAssertEqual(result["supported"], .bool(true))
            XCTAssertEqual(result["pushToStartSupported"], .bool(true))
        }
    }
}
