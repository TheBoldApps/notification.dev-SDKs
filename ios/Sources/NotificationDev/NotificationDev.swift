import Foundation
import NotificationCore
import UIKit
import UserNotifications

/// All public entry points and streams are main-actor isolated; network I/O remains asynchronous.
@MainActor
public final class NotificationDev {
    public private(set) static var shared: NotificationDev?
    public let config: SdkConfig
    private var bridge: IosBridge!
    private var initialization: Task<Void, Error>?
    private var timer: Task<Void, Never>?
    private var syncTask: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    private var diagnosticObservers: [UUID: AsyncStream<SdkError>.Continuation] = [:]
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var active = true
    private var syncRequested = false

    public static func initialize(_ config: SdkConfig) async throws -> NotificationDev {
        if let existing = shared {
            guard existing.config == config else {
                throw SdkError(
                    code: "CONFIGURATION_CHANGED",
                    message: "SDK already initialized with another configuration")
            }
            try await existing.initialization?.value

            return existing
        }
        guard !config.projectId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SdkError(code: "INVALID_CONFIGURATION", message: "Project ID is required")
        }
        let client = try NotificationDev(config: config)
        shared = client
        let task = Task { try await client.perform("start") }
        client.initialization = task
        do {
            try await task.value
            client.installLifecycle()
            await client.foreground()

            return client
        } catch {
            client.bridge.close()
            shared = nil
            throw error
        }
    }

    private init(config: SdkConfig) throws {
        self.config = config
        let storage = try SecureStorage()
        bridge = try IosBridge(
            projectId: config.projectId, baseUrl: config.baseURL.absoluteString,
            allowHttp: config.allowHTTP, loggingEnabled: config.loggingEnabled,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                ?? "",
            osVersion: UIDevice.current.systemVersion, locale: Locale.current.identifier,
            timezone: TimeZone.current.identifier,
            load: {
                do {
                    let value = try storage.load().map(JSONValue.string) ?? .null
                    return try Self.encode(["value": value])
                } catch {
                    return "{\"error\":\"Storage unavailable\"}"
                }
            },
            save: { value in
                do {
                    try storage.save(value)
                    return nil
                } catch { return "Could not persist SDK state" }
            },
            wake: { [weak self] in self?.wake() },
            schedule: { [weak self] delay in self?.schedule(delay: delay.int64Value) },
            diagnostic: { [weak self] json in
                if let error = try? Self.decode(SdkError.self, json) { self?.emit(error) }
            },
            clearNotifications: { Self.clearDeliveredNotifications() },
            log: { _, message in
                if config.loggingEnabled { NSLog("NotificationDev: %@", message) }
            }
        )
    }

    public var state: SdkState {
        // The bridge and Swift models are built and tested as one versioned artifact.
        try! Self.decode(SdkState.self, bridge.state())
    }

    public var states: AsyncStream<SdkState> { stream("state", SdkState.self) }

    public var pendingOpens: AsyncStream<[NotificationOpen]> {
        stream("opens", [NotificationOpen].self)
    }

    public var notifications: AsyncStream<NotificationEvent> {
        stream("notifications", NotificationEvent.self)
    }

    public var diagnostics: AsyncStream<SdkError> {
        AsyncStream { continuation in
            let id = UUID()
            diagnosticObservers[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.diagnosticObservers.removeValue(forKey: id) }
            }
        }
    }

    public func getTags() -> [String: JSONValue] { state.user.tags }

    public func getEmail() -> EmailSubscription? { state.user.email }
    public func login(_ externalId: String) async throws {
        try await perform("login", ["externalId": .string(externalId)])
    }

    public func logout() async throws { try await perform("logout") }
    public func setPushOptedIn(_ enabled: Bool) async throws {
        try await perform("pushOptIn", ["enabled": .bool(enabled)])
    }

    public func setEmail(_ address: String, optedIn: Bool) async throws {
        try await perform("email", ["address": .string(address), "optedIn": .bool(optedIn)])
    }

    public func removeEmail() async throws { try await perform("removeEmail") }

    public func setEmailOptedIn(_ enabled: Bool) async throws {
        try await perform("emailOptIn", ["enabled": .bool(enabled)])
    }

    public func setTags(_ values: [String: TagValue]) async throws {
        try await perform("tags", ["values": .object(values.mapValues(\.json))])
    }

    public func removeTags(_ keys: Set<String>) async throws {
        try await perform(
            "tags", ["values": .object(Dictionary(uniqueKeysWithValues: keys.map { ($0, .null) }))])
    }

    @discardableResult
    public func track(_ name: String, properties: [String: JSONValue] = [:], eventId: String? = nil)
        async throws -> String
    {
        try await call(
            "track",
            [
                "name": .string(name), "properties": .object(properties),
                "eventId": eventId.map(JSONValue.string) ?? .null,
            ], as: String.self)
    }

    public func refresh() async throws -> SdkState { try await call("refresh", as: SdkState.self) }

    public func acknowledgeOpen(_ interactionId: String) async throws {
        try await perform("acknowledge", ["id": .string(interactionId)])
    }

    /// Forward UIApplicationDelegate's successful registration callback, including repeated tokens.
    public func didRegisterForRemoteNotifications(deviceToken: Data) async throws {
        guard !deviceToken.isEmpty else {
            throw SdkError(code: "INVALID_TOKEN", message: "APNs returned an empty token")
        }
        try await perform("device", ["token": .string(Self.hexToken(deviceToken))])
    }

    /// Raw OS errors are deliberately excluded from persistence and diagnostics.
    public func didFailToRegisterForRemoteNotifications() async throws {
        try await perform("registrationFailed")
    }

    /// nil means this notification belongs to another provider. [] means owned but suppressed.
    public func presentationOptions(for notification: UNNotification) async
        -> UNNotificationPresentationOptions?
    {
        guard let payload = Self.payload(notification.request.content.userInfo) else { return nil }
        do {
            let accepted = try await call("receive", ["payload": payload], as: Bool.self)

            return accepted && config.displayInForeground ? [.banner, .list, .sound] : []
        } catch {
            report(error)
            return []
        }
    }

    /// Returns ownership, even when a stale or malformed SDK notification is rejected.
    public func handleResponse(_ response: UNNotificationResponse) async -> Bool {
        guard let payload = Self.payload(response.notification.request.content.userInfo) else {
            return false
        }
        guard response.actionIdentifier != UNNotificationDismissActionIdentifier else { return true }
        do {
            let _: Bool = try await call("open", ["payload": payload], as: Bool.self)
        } catch { report(error) }

        return true
    }

    private func stream<T: Decodable>(_ kind: String, _ type: T.Type) -> AsyncStream<T> {
        AsyncStream(bufferingPolicy: .bufferingNewest(kind == "notifications" ? 32 : 1)) {
            continuation in
            let handle = bridge.observe(kind: kind) { [weak self] json in
                do { continuation.yield(try Self.decode(type, json)) } catch {
                    self?.report(error)
                    continuation.finish()
                }
            }
            continuation.onTermination = { _ in Task { @MainActor in handle.cancel() } }
        }
    }

    private func call<T: Decodable>(
        _ method: String, _ args: [String: JSONValue] = [:], as type: T.Type
    ) async throws -> T {
        let arguments = try Self.encode(args)
        let cancellation = CallCancellation()
        let result: String = try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                let handle = bridge.call(method: method, arguments: arguments) { value, code, message in
                    if code == "CANCELLED" {
                        continuation.resume(throwing: CancellationError())
                    } else if let code {
                        continuation.resume(throwing: SdkError(code: code, message: message ?? code))
                    } else {
                        continuation.resume(returning: value ?? "null")
                    }
                }
                cancellation.install(handle)
            }
        } onCancel: {
            cancellation.cancel()
        }

        return try Self.decode(type, result)
    }

    func perform(_ method: String, _ args: [String: JSONValue] = [:]) async throws {
        let _: JSONValue = try await call(method, args, as: JSONValue.self)
    }

    private func installLifecycle() {
        active = UIApplication.shared.applicationState != .background
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in await self?.foreground() }
            })
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.background() }
            })
    }

    private func foreground() async {
        active = UIApplication.shared.applicationState != .background
        do {
            try await perform(
                "foreground",
                ["active": .bool(UIApplication.shared.applicationState == .active)])
            try await perform(
                "metadata",
                [
                    "appVersion": .string(
                        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""),
                    "osVersion": .string(UIDevice.current.systemVersion),
                    "locale": .string(Locale.current.identifier),
                    "timezone": .string(TimeZone.current.identifier),
                ])
            _ = try await getPushPermissionStatus()
            try await perform("requestRefresh")
        } catch { report(error) }
        UIApplication.shared.registerForRemoteNotifications()
        wake()
    }

    private func background() {
        active = false
        Task { @MainActor in
            do {
                try await perform(
                    "foreground",
                    ["active": .bool(UIApplication.shared.applicationState == .active)])
            } catch { report(error) }
        }

        timer?.cancel()
        timer = nil
        if syncTask != nil { beginBackgroundTime() }
    }

    private func beginBackgroundTime() {
        guard backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "NotificationDev sync") {
            [weak self] in
            Task { @MainActor in
                self?.syncRequested = false
                self?.syncTask?.cancel()
                self?.endBackgroundTime()
            }
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func wake() {
        guard syncTask == nil else {
            syncRequested = true
            return
        }
        syncRequested = false
        if !active { beginBackgroundTime() }
        syncTask = Task { [weak self] in
            guard let self else { return }
            defer {
                syncTask = nil
                endBackgroundTime()
                if syncRequested { wake() }
            }
            do { try await perform("sync") } catch is CancellationError {} catch { report(error) }
        }
    }

    private func schedule(delay: Int64) {
        timer?.cancel()
        guard active else { return }
        timer = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(min(max(1, delay), 6 * 60 * 60 * 1000)) * 1_000_000)
                self?.wake()
            } catch {}
        }
    }

    private func emit(_ error: SdkError) {
        for observer in diagnosticObservers.values { observer.yield(error) }
    }

    private func report(_ error: Error) {
        emit(error as? SdkError ?? SdkError(code: "SDK_ERROR", message: "Native SDK operation failed"))
    }

    static func hexToken(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    static func payload(_ userInfo: [AnyHashable: Any]) -> JSONValue? {
        guard let raw = userInfo["notification_dev"] else { return nil }
        guard JSONSerialization.isValidJSONObject(raw),
            let data = try? JSONSerialization.data(withJSONObject: raw),
            let value = try? JSONDecoder().decode(JSONValue.self, from: data)
        else { return .null }

        return value
    }

    nonisolated static func ownedNotificationIdentifiers(_ requests: [UNNotificationRequest]) -> [String] {
        requests.filter { $0.content.userInfo["notification_dev"] != nil }.map(\.identifier)
    }

    private static func clearDeliveredNotifications() {
        UNUserNotificationCenter.current().getDeliveredNotifications { notifications in
            let ids = ownedNotificationIdentifiers(notifications.map(\.request))
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    private static func encode<T: Encodable>(_ value: T) throws -> String {
        String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
    }

    private static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }
}

/// Cancellation may originate off the main actor before the bridge returns its handle.
private final class CallCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var handle: IosCancellation?
    private var cancelled = false

    func install(_ handle: IosCancellation) {
        lock.lock()
        self.handle = handle
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { Task { @MainActor in handle.cancel() } }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let handle = handle
        lock.unlock()
        if let handle { Task { @MainActor in handle.cancel() } }
    }
}
