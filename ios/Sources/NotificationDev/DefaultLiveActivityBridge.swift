import ActivityKit
import Foundation

/// Public configuration persisted for native startup, without tokens or grants.
public struct DefaultLiveActivityConfiguration: Codable, Equatable, Sendable {
    public let providerConfigurationId: String
    public var enablePushToStart: Bool
    public var enablePushToUpdate: Bool

    public init(providerConfigurationId: String, enablePushToStart: Bool = true, enablePushToUpdate: Bool = true) {
        self.providerConfigurationId = providerConfigurationId
        self.enablePushToStart = enablePushToStart
        self.enablePushToUpdate = enablePushToUpdate
    }
}

/// A snapshot of a current-association activity. Credentials are never included.
public struct DefaultLiveActivityState: Codable, Equatable, Sendable {
    public let activityId: String
    public let nativeActivityId: String
    public let state: String
    public let attributes: [String: JSONValue]
    public let content: [String: JSONValue]

    @available(iOS 16.1, *)
    init(_ activity: Activity<DefaultLiveActivityAttributes>) {
        activityId = activity.attributes.notification_dev?.activityId ?? ""
        nativeActivityId = activity.id
        switch activity.activityState {
        #if compiler(>=6.2)
            case .pending: state = "pending"
        #endif
        case .active: state = "active"
        case .stale: state = "stale"
        case .ended: state = "ended"
        case .dismissed: state = "dismissed"
        @unknown default: state = "unknown"
        }
        attributes = activity.attributes.data
        if #available(iOS 16.2, *) {
            content = activity.content.state.data
        } else {
            content = activity.contentState.data
        }
    }
}

/// JSON-based bridge shared by Flutter and Expo for the default ActivityKit type.
@MainActor
public final class DefaultLiveActivityBridge {
    private let manager: LiveActivities
    private let currentState: () -> SdkState?
    private let scope: String
    private let defaults: UserDefaults
    private let report: (Error) -> Void
    private let key = "dev.notification.sdk.defaultLiveActivityConfiguration"
    private var subscribers: [UUID: AsyncStream<[DefaultLiveActivityState]>.Continuation] = [:]
    private var tasks: [Task<Void, Never>] = []
    private var startingIds = Set<String>()
    private var activityTasks: [String: [Task<Void, Never>]] = [:]

    init(
        manager: LiveActivities, scope: String, defaults: UserDefaults = .standard,
        currentState: @escaping () -> SdkState? = { nil }, report: @escaping (Error) -> Void
    ) {
        self.manager = manager
        self.currentState = currentState
        self.scope = scope
        self.defaults = defaults
        self.report = report
    }

    deinit {
        for task in tasks { task.cancel() }
        for group in activityTasks.values { for task in group { task.cancel() } }
    }

    private struct Saved: Codable {
        let scope: String
        let configuration: DefaultLiveActivityConfiguration
    }

    /// Restore after native SDK initialization on every launch, including background launches.
    public func restoreConfiguration() {
        guard #available(iOS 16.1, *), let data = defaults.data(forKey: key) else { return }
        do {
            let saved = try JSONDecoder().decode(Saved.self, from: data)
            guard saved.scope == scope else { return }
            try apply(saved.configuration)
        } catch {
            report(SdkError(code: "INVALID_CONFIGURATION", message: "Could not restore Live Activity configuration"))
        }
    }

    @available(iOS 16.1, *)
    private func apply(_ configuration: DefaultLiveActivityConfiguration) throws {
        try manager.setupDefault(
            providerConfigurationId: configuration.providerConfigurationId,
            options: .init(
                enablePushToStart: configuration.enablePushToStart, enablePushToUpdate: configuration.enablePushToUpdate
            ))
    }

    public func configure(_ configuration: DefaultLiveActivityConfiguration) throws {
        guard #available(iOS 16.1, *) else { throw unsupported() }
        guard UUID(uuidString: configuration.providerConfigurationId) != nil else {
            throw SdkError(code: "INVALID_CONFIGURATION", message: "Use an APNs provider UUID")
        }
        let saved = try JSONEncoder().encode(Saved(scope: scope, configuration: configuration))
        do {
            try apply(configuration)
        } catch let error as SdkError where error.code == "CONFIGURATION_CHANGED" {
            // Native observers keep their configuration for this process. Stage valid
            // changes for the next native launch instead of trapping the app on the
            // old persisted configuration indefinitely.
            defaults.set(saved, forKey: key)
            throw SdkError(
                code: "LIVE_ACTIVITY_RESTART_REQUIRED",
                message: "Live Activity configuration saved. Restart the app to apply it")
        }
        defaults.set(saved, forKey: key)
    }

    public func getActivities() -> [DefaultLiveActivityState] {
        guard #available(iOS 16.1, *) else { return [] }
        if let state = currentState() {
            guard !state.identityChangePending, let association = state.associationId,
                manager.isCurrentAssociation(association)
            else { return [] }
        }
        return Activity<DefaultLiveActivityAttributes>.activities.compactMap { activity in
            guard let metadata = activity.attributes.notification_dev,
                manager.ownsActivity(activity.id, activityId: metadata.activityId),
                activity.activityState != .ended, activity.activityState != .dismissed
            else { return nil }
            return DefaultLiveActivityState(activity)
        }.sorted { $0.nativeActivityId < $1.nativeActivityId }
    }

    /// Replays the current list and emits changes, including an empty list on identity changes.
    public var states: AsyncStream<[DefaultLiveActivityState]> {
        AsyncStream { continuation in
            let id = UUID()
            subscribers[id] = continuation
            continuation.yield(getActivities())
            if subscribers.count == 1 { startObserving() }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.removeSubscriber(id) }
            }
        }
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
        if subscribers.isEmpty {
            for task in tasks { task.cancel() }
            tasks.removeAll()
            for group in activityTasks.values { for task in group { task.cancel() } }
            activityTasks.removeAll()
        }
    }

    private func emit() {
        let snapshot = getActivities()
        for continuation in subscribers.values { continuation.yield(snapshot) }
    }

    private func startObserving() {
        guard #available(iOS 16.1, *) else { return }
        let changes = manager.activityChanges
        tasks.append(
            Task { [weak self] in
                for await _ in changes {
                    guard !Task.isCancelled else { break }
                    self?.emit()
                }
            })
        tasks.append(
            Task { [weak self] in
                for await activity in Activity<DefaultLiveActivityAttributes>.activityUpdates {
                    guard !Task.isCancelled else { break }
                    self?.observe(activity)
                }
            })
        for activity in Activity<DefaultLiveActivityAttributes>.activities { observe(activity) }
    }

    @available(iOS 16.1, *)
    private func observe(_ activity: Activity<DefaultLiveActivityAttributes>) {
        guard activity.attributes.notification_dev != nil, activityTasks[activity.id] == nil else { return }
        activityTasks[activity.id] = [
            Task { [weak self] in
                for await state in activity.activityStateUpdates {
                    guard !Task.isCancelled else { break }
                    self?.emit()
                    if state == .ended || state == .dismissed {
                        for task in self?.activityTasks.removeValue(forKey: activity.id) ?? [] { task.cancel() }
                        break
                    }
                }
            },
            Task { [weak self] in
                if #available(iOS 16.2, *) {
                    for await _ in activity.contentUpdates {
                        guard !Task.isCancelled else { break }
                        self?.emit()
                    }
                } else {
                    for await _ in activity.contentStateUpdates {
                        guard !Task.isCancelled else { break }
                        self?.emit()
                    }
                }
            },
        ]
        emit()
    }

    /// Handles the same JSON contract for both cross-platform plugins.
    public func invoke(_ method: String, arguments: [String: JSONValue]) async throws -> JSONValue {
        if method == "liveActivities.getCapabilities" {
            var supported = false
            var enabled = false
            var remoteStart = false
            if #available(iOS 16.1, *) {
                supported = true
                enabled = ActivityAuthorizationInfo().areActivitiesEnabled
            }
            if #available(iOS 17.2, *) { remoteStart = true }
            return .object([
                "supported": .bool(supported), "activitiesEnabled": .bool(enabled),
                "pushToStartSupported": .bool(remoteStart),
            ])
        }
        if method == "liveActivities.getActivities" { return try json(getActivities()) }
        guard #available(iOS 16.1, *) else { throw unsupported() }
        switch method {
        case "liveActivities.configure":
            try configure(
                try JSONDecoder().decode(
                    DefaultLiveActivityConfiguration.self, from: JSONEncoder().encode(JSONValue.object(arguments))))
            return .null
        case "liveActivities.start":
            let id = try string(arguments, "activityId")
            guard !startingIds.contains(id),
                !Activity<DefaultLiveActivityAttributes>.activities.contains(where: {
                    $0.attributes.notification_dev?.activityId == id && $0.activityState != .ended
                        && $0.activityState != .dismissed
                })
            else {
                throw SdkError(
                    code: "LIVE_ACTIVITY_ALREADY_EXISTS", message: "Choose a new activity ID for a new activity")
            }
            startingIds.insert(id)
            defer { startingIds.remove(id) }
            let activity = try await manager.startDefault(
                id,
                attributes: object(arguments, "attributes"), content: object(arguments, "content"),
                registrationToken: string(arguments, "registrationToken"))
            return try json(DefaultLiveActivityState(activity))
        case "liveActivities.end":
            let id = try string(arguments, "activityId")
            let content = try object(arguments, "content")
            let date: Date?
            switch arguments["dismissalDate"] {
            case nil, .null: date = nil
            case .number(let milliseconds) where milliseconds.isFinite:
                date = Date(timeIntervalSince1970: milliseconds / 1000)
            default: throw invalid()
            }
            guard getActivities().contains(where: { $0.activityId == id }) else {
                throw SdkError(code: "LIVE_ACTIVITY_NOT_FOUND", message: "No current-association activity with that ID")
            }
            try await manager.validateOwnedActivity(id)
            let ownedIds = Set(getActivities().filter { $0.activityId == id }.map(\.nativeActivityId))
            let activities = Activity<DefaultLiveActivityAttributes>.activities.filter {
                ownedIds.contains($0.id)
            }
            guard !activities.isEmpty else {
                throw SdkError(code: "LIVE_ACTIVITY_NOT_FOUND", message: "No current-association activity with that ID")
            }
            for activity in activities {
                let state = DefaultLiveActivityAttributes.ContentState(data: content)
                let policy: ActivityUIDismissalPolicy = date.map { .after($0) } ?? .immediate
                if #available(iOS 16.2, *) {
                    await activity.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: policy)
                } else {
                    await activity.end(using: state, dismissalPolicy: policy)
                }
            }
            try await manager.exit(id)
            emit()
            return .null
        default: throw invalid()
        }
    }

    private func json<T: Encodable>(_ value: T) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))
    }

    private func string(_ args: [String: JSONValue], _ key: String) throws -> String {
        guard case .string(let value) = args[key], !value.isEmpty else { throw invalid() }
        return value
    }

    private func object(_ args: [String: JSONValue], _ key: String) throws -> [String: JSONValue] {
        guard case .object(let value) = args[key] else { throw invalid() }
        return value
    }

    private func invalid() -> SdkError {
        SdkError(code: "INVALID_ARGUMENT", message: "Invalid Live Activity arguments")
    }
    private func unsupported() -> SdkError {
        SdkError(code: "UNSUPPORTED_PLATFORM", message: "Live Activities require iOS 16.1 or later")
    }
}
