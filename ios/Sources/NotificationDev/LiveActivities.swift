import ActivityKit
import Foundation

public struct LiveActivitySetupOptions: Equatable, Sendable {
    public var enablePushToStart: Bool
    public var enablePushToUpdate: Bool

    public init(enablePushToStart: Bool = true, enablePushToUpdate: Bool = true) {
        self.enablePushToStart = enablePushToStart
        self.enablePushToUpdate = enablePushToUpdate
    }
}

/// Manages ActivityKit observers. Uploads use the SDK's encrypted queue and retry scheduler.
@MainActor
public final class LiveActivities {
    private let perform: (String, [String: JSONValue]) async throws -> Void
    private let states: () -> AsyncStream<SdkState>
    private let report: (Error) -> Void
    private var configurations: [String: Configuration] = [:]
    private var observations: [String: Observation] = [:]
    private var selectedActivities: [String: String] = [:]
    private var identityTask: Task<Void, Never>?
    private var changeSubscribers: [UUID: AsyncStream<Void>.Continuation] = [:]

    var activityChanges: AsyncStream<Void> {
        AsyncStream { continuation in
            let id = UUID()
            changeSubscribers[id] = continuation
            continuation.yield(())
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.changeSubscribers.removeValue(forKey: id) }
            }
        }
    }

    private func changed() {
        for continuation in changeSubscribers.values { continuation.yield(()) }
    }

    func ownsActivity(_ nativeId: String, activityId: String) -> Bool {
        ready && selectedActivities[activityId] == nativeId
    }

    func validateOwnedActivity(_ activityId: String) async throws {
        guard ready, selectedActivities[activityId] != nil else { throw failure("LIVE_ACTIVITY_NOT_FOUND") }
        try await bind(activityId)
    }

    private var associationId: String?
    func isCurrentAssociation(_ id: String) -> Bool { ready && associationId == id }

    private var ready = false

    init(
        perform: @escaping (String, [String: JSONValue]) async throws -> Void,
        states: @escaping () -> AsyncStream<SdkState>, report: @escaping (Error) -> Void
    ) {
        self.perform = perform
        self.states = states
        self.report = report
    }

    deinit {
        identityTask?.cancel()
        for config in configurations.values { for task in config.tasks { task.cancel() } }
        for observation in observations.values { for task in observation.tasks { task.cancel() } }
    }

    /// Call at every app launch, including background launches, after SDK initialization.
    @available(iOS 16.1, *)
    public func setup<Attributes: NotificationDevLiveActivityAttributes>(
        _ activityType: Attributes.Type, providerConfigurationId: String,
        options: LiveActivitySetupOptions = .init()
    ) throws {
        try setup(
            source: .activityKit(activityType), providerConfigurationId: providerConfigurationId, options: options)
    }

    @available(iOS 16.1, *)
    public func setupDefault(
        providerConfigurationId: String, options: LiveActivitySetupOptions = .init()
    ) throws {
        try setup(
            DefaultLiveActivityAttributes.self, providerConfigurationId: providerConfigurationId, options: options)
    }

    /// Requires a fresh grant from your own authenticated backend. Success does not confirm upload.
    @available(iOS 16.1, *)
    @discardableResult
    public func startDefault(
        _ activityId: String, attributes: [String: JSONValue], content: [String: JSONValue],
        registrationToken: String
    ) async throws -> Activity<DefaultLiveActivityAttributes> {
        guard configurations["DefaultLiveActivityAttributes"] != nil else {
            throw failure("LIVE_ACTIVITY_SETUP_REQUIRED")
        }
        guard registrationToken.count == 43 else { throw failure("INVALID_LIVE_ACTIVITY") }
        _ = try JSONEncoder().encode(attributes)
        _ = try JSONEncoder().encode(content)
        try await bind(activityId)
        let attributes = DefaultLiveActivityAttributes(
            data: attributes,
            notification_dev: .create(activityId: activityId, registrationToken: registrationToken))
        let state = DefaultLiveActivityAttributes.ContentState(data: content)
        let activity: Activity<DefaultLiveActivityAttributes>
        if #available(iOS 16.2, *) {
            activity = try Activity.request(
                attributes: attributes, content: ActivityContent(state: state, staleDate: nil), pushType: .token)
        } else {
            activity = try Activity.request(attributes: attributes, contentState: state, pushType: .token)
        }
        await attach(.activityKit(activity), options: configurations["DefaultLiveActivityAttributes"]!.options)
        return activity
    }

    /// Queue an update token. Initial registration requires a server-issued registration token.
    public func enter(_ activityId: String, withToken token: String, registrationToken: String? = nil) async throws {
        var body: [String: JSONValue] = ["token": .string(token.lowercased())]
        if let registrationToken { body["registrationToken"] = .string(registrationToken) }
        try await send("enter", target: activityId, body: body)
    }

    /// Stops server updates and automatic registration. End the ActivityKit UI separately.
    public func exit(_ activityId: String) async throws {
        try await send("exit", target: activityId, body: [:])
        if let nativeId = selectedActivities.removeValue(forKey: activityId) {
            for task in observations[nativeId]?.tasks ?? [] { task.cancel() }
            observations.removeValue(forKey: nativeId)
        }
        changed()
    }

    public func setPushToStartToken(
        _ activityType: String, withToken token: String, providerConfigurationId: String,
        enabled: Bool = true
    ) async throws {
        try await startToken(
            activityType, token: token.lowercased(), provider: providerConfigurationId, enabled: enabled)
    }

    public func removePushToStartToken(_ activityType: String, providerConfigurationId: String) async throws {
        try await startToken(activityType, token: nil, provider: providerConfigurationId, enabled: true)
    }

    @available(iOS 17.2, *)
    public func setPushToStartToken<Attributes: ActivityAttributes>(
        _ activityType: Attributes.Type, withToken token: String, providerConfigurationId: String,
        enabled: Bool = true
    ) async throws {
        try await setPushToStartToken(
            String(describing: activityType), withToken: token,
            providerConfigurationId: providerConfigurationId, enabled: enabled)
    }

    @available(iOS 17.2, *)
    public func removePushToStartToken<Attributes: ActivityAttributes>(
        _ activityType: Attributes.Type, providerConfigurationId: String
    ) async throws {
        try await removePushToStartToken(
            String(describing: activityType), providerConfigurationId: providerConfigurationId)
    }

    private func send(_ kind: String, target: String, body: [String: JSONValue]) async throws {
        try await perform("liveActivity", ["kind": .string(kind), "target": .string(target), "body": .object(body)])
    }

    private func bind(_ activityId: String) async throws {
        try await perform("liveActivityBind", ["activityId": .string(activityId)])
    }

    private func startToken(_ type: String, token: String?, provider: String, enabled: Bool) async throws {
        try await send(
            "start", target: "\(provider):\(type)",
            body: [
                "activityType": .string(type), "providerConfigurationId": .string(provider),
                "enabled": .bool(enabled), "token": token.map(JSONValue.string) ?? .null,
            ])
    }

    private func failure(_ code: String) -> SdkError {
        SdkError(code: code, message: "Live Activity operation failed: \(code)")
    }

    // The adapter makes asynchronous OS sequences controllable in lifecycle tests.
    func setup(source: LiveActivitySource, providerConfigurationId: String, options: LiveActivitySetupOptions) throws {
        guard UUID(uuidString: providerConfigurationId) != nil else { throw failure("INVALID_CONFIGURATION") }
        if let existing = configurations[source.type] {
            guard existing.provider == providerConfigurationId && existing.options == options else {
                throw failure("CONFIGURATION_CHANGED")
            }
            return
        }
        let config = Configuration(source: source, provider: providerConfigurationId, options: options)
        configurations[source.type] = config
        config.tasks = [
            Task { [weak self] in
                for await activity in source.incoming() {
                    guard !Task.isCancelled else { break }
                    await self?.attach(activity, options: options)
                }
            },
            Task { [weak self] in
                for await _ in source.authorizationUpdates() {
                    guard !Task.isCancelled else { break }
                    await self?.reconcile(config)
                }
            },
        ]
        if options.enablePushToStart {
            config.tasks.append(
                Task { [weak self] in
                    for await _ in source.startTokens() {
                        guard !Task.isCancelled else { break }
                        await self?.reconcile(config)
                    }
                })
        }
        if identityTask == nil {
            let stream = states()
            identityTask = Task { [weak self] in
                var previousAssociation: String?
                var wasReady = false
                for await state in stream {
                    guard !Task.isCancelled, let self else { break }
                    let isReady = state.associationId != nil && !state.identityChangePending
                    self.ready = isReady
                    self.associationId = state.associationId
                    if !isReady || state.associationId != previousAssociation {
                        for observation in self.observations.values { for task in observation.tasks { task.cancel() } }
                        self.observations.removeAll()
                        self.selectedActivities.removeAll()
                        self.changed()
                    }
                    if isReady && (!wasReady || state.associationId != previousAssociation) {
                        for config in self.configurations.values { await self.reconcile(config) }
                    }
                    previousAssociation = state.associationId
                    wasReady = isReady
                }
            }
        }
        if ready { config.tasks.append(Task { [weak self] in await self?.reconcile(config) }) }
    }

    private func reconcile(_ config: Configuration) async {
        guard ready, !Task.isCancelled else { return }
        do {
            try await startToken(
                config.source.type,
                token: config.options.enablePushToStart ? config.source.currentStartToken() : nil,
                provider: config.provider, enabled: config.source.enabled())
        } catch { if !Task.isCancelled { report(error) } }
        for activity in config.source.existing() { await attach(activity, options: config.options) }
    }

    private func attach(_ activity: LiveActivityInstance, options: LiveActivitySetupOptions) async {
        guard ready, !Task.isCancelled, let metadata = activity.metadata,
            observations[activity.id] == nil
        else { return }
        // Insert before awaiting the bridge so enumeration and incoming events cannot attach twice.
        let observation = Observation()
        observations[activity.id] = observation
        do {
            try await bind(metadata.activityId)
            guard ready, !Task.isCancelled, observations[activity.id] === observation else { return }
            if let oldId = selectedActivities[metadata.activityId], oldId != activity.id {
                for task in observations.removeValue(forKey: oldId)?.tasks ?? [] { task.cancel() }
            }
            selectedActivities[metadata.activityId] = activity.id
            changed()
            if activity.ended() {
                try await exit(metadata.activityId)
                return
            }
            // Subscribe before reading current values, covering changes during attachment.
            let stateUpdates = activity.ends()
            if options.enablePushToUpdate {
                let tokenUpdates = activity.tokens()
                observation.tasks.append(
                    Task { [weak self] in
                        if let token = activity.currentToken() {
                            await self?.upload(token, metadata: metadata)
                        }
                        for await token in tokenUpdates {
                            guard !Task.isCancelled else { break }
                            await self?.upload(token, metadata: metadata)
                        }
                    })
            }
            observation.tasks.append(
                Task { [weak self] in
                    for await ended in stateUpdates {
                        guard !Task.isCancelled else { break }
                        if ended {
                            do { try await self?.exit(metadata.activityId) } catch { self?.report(error) }
                            break
                        }
                    }
                })
        } catch {
            observations.removeValue(forKey: activity.id)
            if !Task.isCancelled { report(error) }
        }
    }

    private func upload(_ token: Data, metadata: LiveActivityRegistrationMetadata) async {
        do {
            try Task.checkCancellation()
            try await enter(
                metadata.activityId, withToken: NotificationDev.hexToken(token),
                registrationToken: metadata.registrationToken)
        } catch { if !Task.isCancelled { report(error) } }
    }

    private final class Configuration {
        let source: LiveActivitySource
        let provider: String
        let options: LiveActivitySetupOptions
        var tasks: [Task<Void, Never>] = []
        init(source: LiveActivitySource, provider: String, options: LiveActivitySetupOptions) {
            self.source = source
            self.provider = provider
            self.options = options
        }
    }

    private final class Observation {
        var tasks: [Task<Void, Never>] = []
    }
}
