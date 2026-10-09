import Flutter
import Foundation
import NotificationDev
import UIKit
import UserNotifications

private typealias NativeSdk = NotificationDev

private struct PluginConfiguration: Codable, Equatable {
    let projectId: String
    let baseUrl: String
    let displayInForeground: Bool
    let allowHttp: Bool
    let loggingEnabled: Bool
    let automaticIntegration: Bool

    var native: SdkConfig {
        get throws {
            guard let url = URL(string: baseUrl) else { throw BridgeFailure.invalidArgument }

            return SdkConfig(
                projectId: projectId, baseURL: url, allowHTTP: allowHttp,
                loggingEnabled: loggingEnabled, displayInForeground: displayInForeground)
        }
    }
}

private enum BridgeFailure: Error {
    case invalidArgument, notInitialized, configurationChanged, delegateConflict

    var code: String {
        switch self {
        case .invalidArgument: return "INVALID_ARGUMENT"
        case .notInitialized: return "NOT_INITIALIZED"
        case .configurationChanged: return "CONFIGURATION_CHANGED"
        case .delegateConflict: return "NOTIFICATION_DELEGATE_CONFLICT"
        }
    }

    var message: String {
        switch self {
        case .invalidArgument: return "Invalid SDK arguments or configuration"
        case .notInitialized: return "Initialize the Flutter SDK first"
        case .configurationChanged: return "SDK already initialized with different configuration"
        case .delegateConflict:
            return
                "Automatic integration requires FlutterAppDelegate notification forwarding; use manual integration for a custom delegate"
        }
    }
}

/// Process-wide startup survives Flutter engine attachment changes.
@MainActor
private enum NativeStartup {
    static var configuration: PluginConfiguration?
    static var task: Task<NativeSdk, Error>?
    static var restoreError: Error?

    static func file() throws -> URL {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var directory = base.appendingPathComponent("notification-flutter", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)

        return directory.appendingPathComponent("config.json")
    }

    static func restore() {
        guard configuration == nil else { return }

        do {
            let url = try file()
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            let config = try JSONDecoder().decode(PluginConfiguration.self, from: Data(contentsOf: url))
            guard config.automaticIntegration else { return }
            try start(config)
        } catch { restoreError = error }
    }

    static func start(_ config: PluginConfiguration) throws {
        if let existing = configuration {
            guard existing == config else { throw BridgeFailure.configurationChanged }
            return
        }
        let nativeConfig = try config.native
        configuration = config
        task = Task {
            do { return try await NativeSdk.initialize(nativeConfig) } catch {
                configuration = nil
                task = nil
                throw error
            }
        }
    }

    static func client() async throws -> NativeSdk {
        guard let task else { throw restoreError ?? BridgeFailure.notInitialized }

        return try await task.value
    }

    static func save(_ config: PluginConfiguration) throws {
        try JSONEncoder().encode(config).write(
            to: file(), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        restoreError = nil
    }
}

@MainActor
public final class NotificationDevPlugin: NSObject, @preconcurrency FlutterPlugin,
    @preconcurrency FlutterApplicationLifeCycleDelegate
{
    private var streams: [String: NativeStream] = [:]
    private var channels: [FlutterEventChannel] = []
    private var initialized = false
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private weak var appDelegate: (NSObjectProtocol & UNUserNotificationCenterDelegate)?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = NotificationDevPlugin()
        let channel = FlutterMethodChannel(name: "dev.notification/sdk", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: channel)
        registrar.addApplicationDelegate(instance)
        instance.appDelegate = UIApplication.shared.delegate as? (NSObjectProtocol & UNUserNotificationCenterDelegate)
        for name in ["states", "pendingOpens", "notifications", "diagnostics"] {
            let stream = NativeStream(name: name)
            instance.streams[name] = stream
            let events = FlutterEventChannel(
                name: "dev.notification/sdk/\(name)", binaryMessenger: registrar.messenger())
            events.setStreamHandler(stream)
            instance.channels.append(events)
        }
        NativeStartup.restore()
        if NativeStartup.configuration?.automaticIntegration == true {
            do { try instance.installNotificationDelegate() } catch {
                NativeStartup.restoreError = error
            }
        }
    }

    private func installNotificationDelegate() throws {
        let center = UNUserNotificationCenter.current()
        guard let appDelegate else { throw BridgeFailure.delegateConflict }
        if let current = center.delegate, current !== appDelegate {
            throw BridgeFailure.delegateConflict
        }
        center.delegate = appDelegate
    }

    // Flutter broadcasts notification-center callbacks to every responding plugin.
    // In manual mode, omit these selectors so the host owns completion handlers.
    public override func responds(to selector: Selector!) -> Bool {
        let callbacks: Set<String> = [
            "application:didRegisterForRemoteNotificationsWithDeviceToken:",
            "application:didFailToRegisterForRemoteNotificationsWithError:",
            "userNotificationCenter:willPresentNotification:withCompletionHandler:",
            "userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:",
        ]
        if callbacks.contains(NSStringFromSelector(selector)),
            NativeStartup.configuration?.automaticIntegration != true
        {
            return false
        }

        return super.responds(to: selector)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let id = UUID()
        tasks[id] = Task {
            defer { tasks.removeValue(forKey: id) }
            do {
                let arguments = call.arguments as? [String: Any] ?? [:]
                if call.method == "initialize" {
                    let data = try JSONSerialization.data(withJSONObject: arguments)
                    let config = try JSONDecoder().decode(PluginConfiguration.self, from: data)
                    if config.automaticIntegration { try installNotificationDelegate() }
                    try NativeStartup.start(config)
                    _ = try await NativeStartup.client()
                    try NativeStartup.save(config)
                    initialized = true
                    for stream in streams.values {
                        stream.initialized = true
                    }
                    result(nil)
                    return
                }
                guard initialized else { throw BridgeFailure.notInitialized }
                let sdk = try await NativeStartup.client()
                switch call.method {
                case "getState": result(try encode(sdk.state))
                case "getTags": result(try encode(sdk.getTags()))
                case "getEmail": result(try encode(sdk.getEmail()))
                case "login":
                    try await sdk.login(try string(arguments, "externalId"))
                    result(nil)
                case "logout":
                    try await sdk.logout()
                    result(nil)
                case "setEmail":
                    try await sdk.setEmail(try string(arguments, "address"), optedIn: boolean(arguments, "optedIn"))
                    result(nil)
                case "removeEmail":
                    try await sdk.removeEmail()
                    result(nil)
                case "setEmailOptedIn":
                    try await sdk.setEmailOptedIn(try boolean(arguments, "enabled"))
                    result(nil)
                case "setPushOptedIn":
                    try await sdk.setPushOptedIn(try boolean(arguments, "enabled"))
                    result(nil)
                case "setTags":
                    let values = try decode([String: JSONValue].self, string(arguments, "values"))
                    let tags = try values.mapValues { value -> TagValue in
                        switch value {
                        case .string(let value): return .string(value)
                        case .bool(let value): return .bool(value)
                        case .number(let value) where value.isFinite: return .number(value)
                        default: throw BridgeFailure.invalidArgument
                        }
                    }
                    try await sdk.setTags(tags)
                    result(nil)
                case "removeTags":
                    guard let keys = arguments["keys"] as? [String] else { throw BridgeFailure.invalidArgument }
                    try await sdk.removeTags(Set(keys))
                    result(nil)
                case "track":
                    let properties = try decode([String: JSONValue].self, string(arguments, "properties"))
                    result(
                        try await sdk.track(
                            try string(arguments, "name"), properties: properties,
                            eventId: arguments["eventId"] as? String))
                case "refresh": result(try encode(await sdk.refresh()))
                case "acknowledgeOpen":
                    try await sdk.acknowledgeOpen(try string(arguments, "interactionId"))
                    result(nil)
                case "getPushPermissionStatus":
                    let status = try await sdk.getPushPermissionStatus()
                    result(
                        try encode([
                            "areNotificationsEnabled": JSONValue.bool(status.areNotificationsEnabled),
                            "authorizationStatus": .string(authorizationName(status.authorizationStatus)),
                            "alertSetting": .string(settingName(status.alertSetting)),
                            "soundSetting": .string(settingName(status.soundSetting)),
                            "badgeSetting": .string(settingName(status.badgeSetting)),
                        ]))
                case "requestPushPermission":
                    let permission = try await sdk.requestPushPermission(
                        settingsFallback: boolean(arguments, "settingsFallback"))
                    let outcome: String
                    switch permission {
                    case .granted: outcome = "granted"
                    case .denied: outcome = "denied"
                    case .settingsRequired: outcome = "settingsRequired"
                    case .settingsOpened: outcome = "settingsOpened"
                    case .settingsUnavailable: outcome = "failed"
                    }
                    var value = ["outcome": outcome]
                    if permission == .settingsUnavailable { value["errorCode"] = "SETTINGS_UNAVAILABLE" }
                    result(try encode(value))
                case "openPushSettings": result(await sdk.openPushSettings())
                default: result(FlutterMethodNotImplemented)
                }
            } catch { result(flutterError(error)) }
        }
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        for task in tasks.values {
            task.cancel()
        }
        tasks.removeAll()
        for stream in streams.values {
            stream.close()
        }
        for channel in channels {
            channel.setStreamHandler(nil)
        }
        channels.removeAll()
    }

    public func application(
        _ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        guard NativeStartup.configuration?.automaticIntegration == true else { return }
        Task {
            do { try await NativeStartup.client().didRegisterForRemoteNotifications(deviceToken: deviceToken) } catch {
                streams["diagnostics"]?.report(error)
            }
        }
    }

    public func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error)
    {
        guard NativeStartup.configuration?.automaticIntegration == true else { return }
        Task {
            do { try await NativeStartup.client().didFailToRegisterForRemoteNotifications() } catch {
                streams["diagnostics"]?.report(error)
            }
        }
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        Task { @MainActor in
            guard NativeStartup.configuration?.automaticIntegration == true else {
                completionHandler([])
                return
            }
            do {
                let sdk = try await NativeStartup.client()
                let options = await sdk.presentationOptions(for: notification)
                completionHandler(options ?? [.banner, .sound, .badge])
            } catch {
                streams["diagnostics"]?.report(error)
                let owned = notification.request.content.userInfo["notification_dev"] != nil
                completionHandler(owned ? [] : [.banner, .sound, .badge])
            }
        }
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            defer { completionHandler() }
            guard NativeStartup.configuration?.automaticIntegration == true else { return }
            do { _ = try await NativeStartup.client().handleResponse(response) } catch {
                streams["diagnostics"]?.report(error)
            }
        }
    }
}

@MainActor
private final class NativeStream: NSObject, @preconcurrency FlutterStreamHandler {
    let name: String
    var initialized = false
    private var task: Task<Void, Never>?
    private var sink: FlutterEventSink?

    init(name: String) { self.name = name }

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        guard initialized else { return flutterError(BridgeFailure.notInitialized) }
        sink = events
        task?.cancel()
        task = Task {
            do {
                let sdk = try await NativeStartup.client()
                switch name {
                case "states": await collect(sdk.states, events)
                case "pendingOpens": await collect(sdk.pendingOpens, events)
                case "notifications": await collect(sdk.notifications, events)
                case "diagnostics": await collect(sdk.diagnostics, events)
                default: break
                }
            } catch { events(flutterError(error)) }
        }

        return nil
    }

    private func collect<T: Encodable>(_ stream: AsyncStream<T>, _ events: @escaping FlutterEventSink) async {
        for await value in stream {
            if Task.isCancelled { return }
            do { events(try encode(value)) } catch { events(flutterError(error)) }
        }
    }

    func report(_ error: Error) {
        let failure = flutterError(error)
        // Diagnostics are values, rather than stream transport errors.
        sink?(try? encode(["code": failure.code, "message": failure.message ?? "Native SDK operation failed"]))
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        close()
        return nil
    }

    func close() {
        task?.cancel()
        task = nil
        sink = nil
    }
}

private func encode<T: Encodable>(_ value: T) throws -> String {
    String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
}

private func decode<T: Decodable>(_ type: T.Type, _ value: String) throws -> T {
    try JSONDecoder().decode(type, from: Data(value.utf8))
}

private func string(_ arguments: [String: Any], _ key: String) throws -> String {
    guard let value = arguments[key] as? String else { throw BridgeFailure.invalidArgument }
    return value
}

private func boolean(_ arguments: [String: Any], _ key: String) throws -> Bool {
    guard let value = arguments[key] as? Bool else { throw BridgeFailure.invalidArgument }
    return value
}

private func flutterError(_ error: Error) -> FlutterError {
    if let error = error as? SdkError { return FlutterError(code: error.code, message: error.message, details: nil) }
    if let error = error as? BridgeFailure {
        return FlutterError(code: error.code, message: error.message, details: nil)
    }
    if error is CancellationError {
        return FlutterError(code: "ENGINE_DETACHED", message: "Flutter engine detached", details: nil)
    }
    if error is DecodingError {
        return FlutterError(code: "INVALID_ARGUMENT", message: "Invalid SDK arguments", details: nil)
    }
    return FlutterError(code: "NATIVE_FAILURE", message: "Native SDK operation failed", details: nil)
}

private func authorizationName(_ status: UNAuthorizationStatus) -> String {
    switch status {
    case .notDetermined: return "notDetermined"
    case .denied: return "denied"
    case .authorized: return "authorized"
    case .provisional: return "provisional"
    case .ephemeral: return "ephemeral"
    @unknown default: return "unknown"
    }
}

private func settingName(_ setting: UNNotificationSetting) -> String {
    switch setting {
    case .notSupported: return "notSupported"
    case .disabled: return "disabled"
    case .enabled: return "enabled"
    @unknown default: return "unknown"
    }
}
