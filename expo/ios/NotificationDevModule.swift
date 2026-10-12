import ExpoModulesCore
import Foundation
import NotificationDev
import UserNotifications

// All mutable bridge state is accessed by main-actor tasks below.
public final class NotificationDevModule: Module, @unchecked Sendable {
  private var initialized = false
  private var observing = false
  private var observers: [Task<Void, Never>] = []
  private var diagnosticObserver: NSObjectProtocol?
  private var calls: [UUID: Task<Void, Never>] = [:]

  public func definition() -> ModuleDefinition {
    Name("NotificationDev")
    Events("onEvent")
    AsyncFunction("invoke") { (method: String, argumentsJson: String, promise: Promise) in
      Task { @MainActor in
        let id = UUID()
        self.calls[id] = Task { @MainActor in
          defer { self.calls.removeValue(forKey: id) }
          do { promise.resolve(try await self.invoke(method, argumentsJson)) }
          catch {
            let failure = failureDetails(error)
            promise.reject(failure.code, failure.message)
          }
        }
      }
    }
    OnStartObserving {
      Task { @MainActor in self.observing = true; self.startObservers() }
    }
    OnStopObserving {
      Task { @MainActor in self.observing = false; self.stopObservers() }
    }
    OnDestroy {
      Task { @MainActor in
        self.stopObservers()
        for task in self.calls.values { task.cancel() }
        self.calls.removeAll()
      }
    }
  }

  @MainActor
  private func invoke(_ method: String, _ argumentsJson: String) async throws -> String? {
    if method == "initialize" {
      _ = try await NativeStartup.client()
      initialized = true
      startObservers()
      return nil
    }
    guard initialized else { throw BridgeFailure.notInitialized }
    let sdk = try await NativeStartup.client()
    let args = try JSONDecoder().decode([String: JSONValue].self, from: Data(argumentsJson.utf8))
    func string(_ key: String) throws -> String {
      guard case .string(let value) = args[key] else { throw BridgeFailure.invalidArgument }
      return value
    }
    func boolean(_ key: String) throws -> Bool {
      guard case .bool(let value) = args[key] else { throw BridgeFailure.invalidArgument }
      return value
    }
    switch method {
    case "getState": return try encode(sdk.state)
    case "getTags": return try encode(sdk.getTags())
    case "getEmail": return try encode(sdk.getEmail())
    case "login": try await sdk.login(string("externalId"))
    case "logout": try await sdk.logout()
    case "setEmail": try await sdk.setEmail(string("address"), optedIn: boolean("optedIn"))
    case "removeEmail": try await sdk.removeEmail()
    case "setEmailOptedIn": try await sdk.setEmailOptedIn(boolean("enabled"))
    case "setPushOptedIn": try await sdk.setPushOptedIn(boolean("enabled"))
    case "setTags":
      guard case .object(let values) = args["values"] else { throw BridgeFailure.invalidArgument }
      let tags = try values.mapValues { value -> TagValue in
        switch value {
        case .string(let value): return .string(value)
        case .bool(let value): return .bool(value)
        case .number(let value) where value.isFinite: return .number(value)
        default: throw BridgeFailure.invalidArgument
        }
      }
      try await sdk.setTags(tags)
    case "removeTags":
      guard case .array(let values) = args["keys"] else { throw BridgeFailure.invalidArgument }
      let keys = try values.map { value -> String in
        guard case .string(let key) = value else { throw BridgeFailure.invalidArgument }
        return key
      }
      try await sdk.removeTags(Set(keys))
    case "track":
      guard case .object(let properties) = args["properties"] else { throw BridgeFailure.invalidArgument }
      let eventId: String?
      if let value = args["eventId"] {
        guard case .string(let id) = value else { throw BridgeFailure.invalidArgument }
        eventId = id
      } else { eventId = nil }
      return try encode(await sdk.track(string("name"), properties: properties, eventId: eventId))
    case "refresh": return try encode(await sdk.refresh())
    case "acknowledgeOpen": try await sdk.acknowledgeOpen(string("interactionId"))
    case "getPushPermissionStatus":
      let status = try await sdk.getPushPermissionStatus()
      return try encode([
        "areNotificationsEnabled": JSONValue.bool(status.areNotificationsEnabled),
        "authorizationStatus": .string(authorizationName(status.authorizationStatus)),
        "alertSetting": .string(settingName(status.alertSetting)),
        "soundSetting": .string(settingName(status.soundSetting)),
        "badgeSetting": .string(settingName(status.badgeSetting)),
      ])
    case "requestPushPermission":
      let permission = try await sdk.requestPushPermission(settingsFallback: boolean("settingsFallback"))
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
      return try encode(value)
    case "openPushSettings": return try encode(await sdk.openPushSettings())
    default: throw BridgeFailure.invalidArgument
    }
    return nil
  }

  @MainActor
  private func startObservers() {
    guard initialized, observing, observers.isEmpty else { return }
    observers.append(Task { @MainActor in
      do {
        let sdk = try await NativeStartup.client()
        await withTaskGroup(of: Void.self) { group in
          group.addTask { await self.collect("states", sdk.states) }
          group.addTask { await self.collect("pendingOpens", sdk.pendingOpens) }
          group.addTask { await self.collect("notifications", sdk.notifications) }
          group.addTask { await self.collect("diagnostics", sdk.diagnostics) }
        }
      } catch { report(error) }
    })
    diagnosticObserver = NotificationCenter.default.addObserver(forName: bridgeDiagnostic, object: nil, queue: .main) { [weak self] notification in
      if let error = notification.object as? Error {
        Task { @MainActor in self?.report(error) }
      }
    }
  }

  @MainActor
  private func collect<T: Encodable>(_ name: String, _ stream: AsyncStream<T>) async {
    for await value in stream {
      if Task.isCancelled { return }
      do { emit(name, try encode(value)) } catch { report(error) }
    }
  }

  @MainActor
  private func stopObservers() {
    for task in observers { task.cancel() }
    observers.removeAll()
    if let diagnosticObserver { NotificationCenter.default.removeObserver(diagnosticObserver) }
    diagnosticObserver = nil
  }

  @MainActor
  private func report(_ error: Error) {
    let failure = failureDetails(error)
    if let value = try? encode(["code": failure.code, "message": failure.message]) { emit("diagnostics", value) }
  }

  private func emit(_ name: String, _ json: String) { sendEvent("onEvent", ["name": name, "json": json]) }
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
