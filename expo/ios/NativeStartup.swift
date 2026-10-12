import ExpoModulesCore
import Foundation
import NotificationDev
import UIKit
import UserNotifications

enum BridgeFailure: Error {
  case invalidArgument, delegateConflict, notInitialized

  var code: String {
    switch self {
    case .invalidArgument: return "INVALID_ARGUMENT"
    case .delegateConflict: return "NOTIFICATION_DELEGATE_CONFLICT"
    case .notInitialized: return "NOT_INITIALIZED"
    }
  }
  var message: String {
    switch self {
    case .invalidArgument: return "Invalid SDK arguments or config plugin configuration"
    case .delegateConflict: return "notification.dev requires exclusive notification delegate ownership"
    case .notInitialized: return "Await NotificationDev.initialize() first"
    }
  }
}

@MainActor
enum NativeStartup {
  static var task: Task<NotificationDev, Error>?
  static let delegate = NotificationDelegate()

  static func client() async throws -> NotificationDev {
    let center = UNUserNotificationCenter.current()
    if let current = center.delegate, current !== delegate { throw BridgeFailure.delegateConflict }
    center.delegate = delegate
    if task == nil {
      guard let values = Bundle.main.object(forInfoDictionaryKey: "NotificationDev") as? [String: Any],
        let projectId = values["projectId"] as? String,
        let baseUrl = values["baseUrl"] as? String, let url = URL(string: baseUrl),
        let allowHttp = values["allowHttp"] as? Bool,
        let loggingEnabled = values["loggingEnabled"] as? Bool,
        let displayInForeground = values["displayInForeground"] as? Bool
      else { throw BridgeFailure.invalidArgument }
      task = Task {
        try await NotificationDev.initialize(SdkConfig(
          projectId: projectId, baseURL: url, allowHTTP: allowHttp,
          loggingEnabled: loggingEnabled, displayInForeground: displayInForeground))
      }
    }
    do { return try await task!.value }
    catch { task = nil; throw error }
  }
}

public final class NotificationDevAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    Task { @MainActor in _ = try? await NativeStartup.client() }
    return true
  }

  public func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
    Task { @MainActor in
      do { try await NativeStartup.client().didRegisterForRemoteNotifications(deviceToken: token) }
      catch { NotificationCenter.default.post(name: bridgeDiagnostic, object: error) }
    }
  }

  public func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
    Task { @MainActor in
      do { try await NativeStartup.client().didFailToRegisterForRemoteNotifications() }
      catch { NotificationCenter.default.post(name: bridgeDiagnostic, object: error) }
    }
  }
}

let bridgeDiagnostic = Notification.Name("dev.notification.expo.diagnostic")

@MainActor
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
  nonisolated func userNotificationCenter(
    _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    Task { @MainActor in
      do {
        let sdk = try await NativeStartup.client()
        completionHandler(await sdk.presentationOptions(for: notification) ?? [.banner, .sound, .badge])
      } catch {
        NotificationCenter.default.post(name: bridgeDiagnostic, object: error)
        completionHandler(notification.request.content.userInfo["notification_dev"] == nil ? [.banner, .sound, .badge] : [])
      }
    }
  }

  nonisolated func userNotificationCenter(
    _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    Task { @MainActor in
      defer { completionHandler() }
      do { _ = try await NativeStartup.client().handleResponse(response) }
      catch { NotificationCenter.default.post(name: bridgeDiagnostic, object: error) }
    }
  }
}

func encode<T: Encodable>(_ value: T) throws -> String {
  String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
}

func failureDetails(_ error: Error) -> (code: String, message: String) {
  if let error = error as? SdkError { return (error.code, error.message) }
  if let error = error as? BridgeFailure { return (error.code, error.message) }
  if error is DecodingError { return ("INVALID_ARGUMENT", "Invalid SDK arguments") }
  if error is CancellationError { return ("ENGINE_DETACHED", "JavaScript runtime detached") }
  return ("NATIVE_FAILURE", "Native SDK operation failed")
}
