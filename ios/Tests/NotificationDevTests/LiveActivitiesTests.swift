import ActivityKit
import XCTest

@testable import NotificationDev

@MainActor
final class LiveActivitiesTests: XCTestCase {
    private let provider = "00000000-0000-0000-0000-000000000001"

    private func state(_ association: String?, pending: Bool = false) -> SdkState {
        SdkState(
            availability: .available, user: .init(email: nil, tags: [:]), installationId: "installation",
            associationId: association, externalId: nil, lastSyncedAt: nil, syncing: false,
            hasPendingChanges: false, pushOptedIn: true,
            pushRegistration: .init(status: "unknown", errorCode: nil, updatedAt: nil),
            permission: .init(areNotificationsEnabled: false), lastError: nil, identityChangePending: pending)
    }

    private func settle(_ condition: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<1000 {
            if condition() { return }
            await Task.yield()
        }
        XCTFail("Asynchronous condition did not complete", file: file, line: line)
    }

    func testDefaultAttributesMatchRemotePayloadAndOptionalMetadata() throws {
        guard #available(iOS 16.1, *) else { return }
        let json = """
            {"data":{"orderId":"order-1"},"notification_dev":{"activityId":"order-1","registrationToken":"grant"}}
            """
        let attributes = try JSONDecoder().decode(DefaultLiveActivityAttributes.self, from: Data(json.utf8))
        XCTAssertEqual(attributes.data["orderId"], .string("order-1"))
        XCTAssertEqual(attributes.notification_dev?.activityId, "order-1")
        let local = try JSONDecoder().decode(DefaultLiveActivityAttributes.self, from: Data("{\"data\":{}}".utf8))
        XCTAssertNil(local.notification_dev)
        let content = DefaultLiveActivityAttributes.ContentState(data: ["status": .string("Preparing")])
        XCTAssertEqual(
            try JSONDecoder().decode(
                DefaultLiveActivityAttributes.ContentState.self,
                from: JSONEncoder().encode(content)), content)
        XCTAssertEqual(Set([JSONValue.object(["status": .string("Preparing")])]).count, 1)
    }

    func testManualMethodsPreserveRequiredBackendArguments() async throws {
        var calls: [(String, [String: JSONValue])] = []
        let manager = LiveActivities(
            perform: { calls.append(($0, $1)) }, states: { AsyncStream { $0.finish() } }, report: { _ in })
        try await manager.enter("order-1", withToken: "AA00", registrationToken: "grant")
        try await manager.exit("order-1")
        try await manager.setPushToStartToken(
            "DeliveryAttributes", withToken: "FF11", providerConfigurationId: provider, enabled: false)
        try await manager.removePushToStartToken("DeliveryAttributes", providerConfigurationId: provider)
        XCTAssertEqual(calls[0].1["body"], .object(["token": .string("aa00"), "registrationToken": .string("grant")]))
        XCTAssertEqual(calls[1].1["kind"], .string("exit"))
        XCTAssertEqual(
            calls[2].1["body"],
            .object([
                "token": .string("ff11"), "enabled": .bool(false), "activityType": .string("DeliveryAttributes"),
                "providerConfigurationId": .string(provider),
            ]))
        guard case .object(let cleared) = calls[3].1["body"] else { return XCTFail("Missing clear body") }
        XCTAssertEqual(cleared["token"], .null)
    }

    func testAutomaticReconciliationRotationPermissionAndEnd() async throws {
        let states = AsyncStream<SdkState>.makeStream()
        let incoming = AsyncStream<LiveActivityInstance>.makeStream()
        let tokens = AsyncStream<Data>.makeStream()
        let ends = AsyncStream<Bool>.makeStream()
        let permissions = AsyncStream<Void>.makeStream()
        let starts = AsyncStream<Void>.makeStream()
        var enabled = true
        var startToken = "0011"
        var calls: [[String: JSONValue]] = []
        var binds = 0
        var errors: [Error] = []
        let activity = LiveActivityInstance(
            id: "native-1",
            metadata: .create(activityId: "order-1", registrationToken: String(repeating: "g", count: 43)),
            currentToken: { Data([0, 255]) }, ended: { false }, tokens: { tokens.stream }, ends: { ends.stream })
        let source = LiveActivitySource(
            type: "DeliveryAttributes", existing: { [activity] }, incoming: { incoming.stream },
            currentStartToken: { startToken }, startTokens: { starts.stream }, enabled: { enabled },
            authorizationUpdates: { permissions.stream })
        var manager: LiveActivities? = LiveActivities(
            perform: { method, args in
                if method == "liveActivityBind" { binds += 1 } else { calls.append(args) }
            }, states: { states.stream }, report: { errors.append($0) })
        try manager!.setup(source: source, providerConfigurationId: provider, options: .init())
        try manager!.setup(source: source, providerConfigurationId: provider, options: .init())
        XCTAssertThrowsError(
            try manager!.setup(
                source: source, providerConfigurationId: provider,
                options: .init(enablePushToUpdate: false)))
        states.continuation.yield(state("association"))
        await settle { calls.contains { $0["kind"] == .string("enter") } }
        incoming.continuation.yield(activity)
        tokens.continuation.yield(Data([1, 2]))
        await settle {
            calls.contains {
                $0["body"]
                    == .object([
                        "token": .string("0102"), "registrationToken": .string(String(repeating: "g", count: 43)),
                    ])
            }
        }
        XCTAssertEqual(binds, 1)
        enabled = false
        permissions.continuation.yield(())
        await settle {
            calls.contains { args in
                guard case .object(let body) = args["body"] else { return false }
                return body["enabled"] == .bool(false)
            }
        }
        startToken = "0022"
        starts.continuation.yield(())
        await settle {
            calls.contains { args in
                guard case .object(let body) = args["body"] else { return false }
                return body["token"] == .string("0022")
            }
        }
        ends.continuation.yield(true)
        await settle { calls.contains { $0["kind"] == .string("exit") } }
        let before = calls.filter { $0["kind"] == .string("enter") }.count
        tokens.continuation.yield(Data([3, 4]))
        await Task.yield()
        XCTAssertEqual(calls.filter { $0["kind"] == .string("enter") }.count, before)
        XCTAssertTrue(errors.isEmpty)
        weak var weakManager = manager
        manager = nil
        await settle { weakManager == nil }
    }

    func testDisabledOptionsDoNotSubscribeToTokenStreams() async throws {
        let states = AsyncStream<SdkState>.makeStream()
        var tokenSubscriptions = 0
        var startSubscriptions = 0
        var stateSubscriptions = 0
        var startBodies: [JSONValue] = []
        let activity = LiveActivityInstance(
            id: "native", metadata: .create(activityId: "order", registrationToken: "grant"),
            currentToken: { Data([1]) }, ended: { false },
            tokens: {
                tokenSubscriptions += 1
                return AsyncStream { $0.finish() }
            },
            ends: {
                stateSubscriptions += 1
                return AsyncStream { $0.finish() }
            })
        let source = LiveActivitySource(
            type: "Type", existing: { [activity] }, incoming: { AsyncStream { $0.finish() } },
            currentStartToken: { "0011" },
            startTokens: {
                startSubscriptions += 1
                return AsyncStream { $0.finish() }
            },
            enabled: { true }, authorizationUpdates: { AsyncStream { $0.finish() } })
        let manager = LiveActivities(
            perform: { _, args in
                if args["kind"] == .string("start"), let body = args["body"] { startBodies.append(body) }
            }, states: { states.stream }, report: { _ in })
        try manager.setup(
            source: source, providerConfigurationId: provider,
            options: .init(enablePushToStart: false, enablePushToUpdate: false))
        states.continuation.yield(state("association"))
        await settle { stateSubscriptions == 1 }
        XCTAssertEqual(tokenSubscriptions, 0)
        XCTAssertEqual(startSubscriptions, 0)
        guard case .object(let body) = startBodies.first else { return XCTFail("Missing start body") }
        XCTAssertEqual(body["token"], .null)
        withExtendedLifetime(manager) {}
    }

    func testStartDefaultRequiresSetupBeforeCreatingActivity() async throws {
        guard #available(iOS 16.1, *) else { return }
        let manager = LiveActivities(
            perform: { _, _ in XCTFail("Must not enqueue") },
            states: { AsyncStream { $0.finish() } }, report: { _ in })
        do {
            try await manager.startDefault("order", attributes: [:], content: [:], registrationToken: "grant")
            XCTFail("Missing setup must fail")
        } catch let error as SdkError {
            XCTAssertEqual(error.code, "LIVE_ACTIVITY_SETUP_REQUIRED")
        }
    }

    func testIdentityTransitionCancelsOldTokenObservers() async throws {
        let states = AsyncStream<SdkState>.makeStream()
        let tokens = AsyncStream<Data>.makeStream()
        var calls = 0
        var cancelled = false
        tokens.continuation.onTermination = { _ in Task { @MainActor in cancelled = true } }
        let activity = LiveActivityInstance(
            id: "native", metadata: .create(activityId: "order", registrationToken: "grant"),
            currentToken: { Data([1]) }, ended: { false },
            tokens: { tokens.stream }, ends: { AsyncStream { _ in } })
        let manager = LiveActivities(perform: { _, _ in calls += 1 }, states: { states.stream }, report: { _ in })
        let source = LiveActivitySource(
            type: "Type", existing: { [activity] }, incoming: { AsyncStream { _ in } },
            currentStartToken: { nil }, startTokens: { AsyncStream { $0.finish() } },
            enabled: { true }, authorizationUpdates: { AsyncStream { _ in } })
        try manager.setup(source: source, providerConfigurationId: provider, options: .init())
        states.continuation.yield(state("old"))
        await settle { calls >= 3 }
        states.continuation.yield(state(nil, pending: true))
        await settle { cancelled }
        withExtendedLifetime(manager) {}
    }
}
