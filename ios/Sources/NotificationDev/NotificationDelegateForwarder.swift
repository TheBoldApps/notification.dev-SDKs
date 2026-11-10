import UserNotifications

internal protocol NotificationCenterDelegating: AnyObject {
    var delegate: UNUserNotificationCenterDelegate? { get set }
}

extension UNUserNotificationCenter: NotificationCenterDelegating {}

/// Owns notification.dev callbacks and forwards unrelated notifications to the previous delegate.
/// Retain this object for the lifetime of the application.
@MainActor
public final class NotificationDelegateForwarder: NSObject, UNUserNotificationCenterDelegate {
    public weak var forwardingDelegate: UNUserNotificationCenterDelegate?
    private var installed = false
    private let client: @MainActor () async throws -> NotificationDev
    private let diagnostic: (Error) -> Void

    public init(
        client: @escaping @MainActor () async throws -> NotificationDev,
        diagnostic: @escaping (Error) -> Void = { _ in }
    ) {
        self.client = client
        self.diagnostic = diagnostic
    }

    public func install(on center: UNUserNotificationCenter = .current()) {
        installDelegate(on: center)
    }

    internal func installDelegate(on center: NotificationCenterDelegating) {
        if installed || center.delegate === self { return }
        installed = true
        forwardingDelegate = center.delegate
        center.delegate = self
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let complete = PresentationCompletion(completionHandler)
        Task { @MainActor in
            if notification.request.content.userInfo["notification_dev"] == nil {
                if let delegate = forwardingDelegate,
                    delegate.responds(to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:willPresent:withCompletionHandler:))) {
                    delegate.userNotificationCenter?(center, willPresent: notification, withCompletionHandler: { complete.call($0) })
                } else { complete.call([.banner, .list, .sound, .badge]) }
                return
            }
            do {
                let sdk = try await client()
                complete.call(await sdk.presentationOptions(for: notification) ?? [])
            }
            catch { diagnostic(error); complete.call([]) }
        }
    }

    public nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let complete = ResponseCompletion(completionHandler)
        Task { @MainActor in
            if response.notification.request.content.userInfo["notification_dev"] == nil {
                if let delegate = forwardingDelegate,
                    delegate.responds(to: #selector(UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:))) {
                    delegate.userNotificationCenter?(center, didReceive: response, withCompletionHandler: { complete.call() })
                } else { complete.call() }
                return
            }
            do { _ = try await client().handleResponse(response) }
            catch { diagnostic(error) }
            complete.call()
        }
    }
}

private final class PresentationCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: ((UNNotificationPresentationOptions) -> Void)?
    init(_ callback: @escaping (UNNotificationPresentationOptions) -> Void) { self.callback = callback }
    func call(_ value: UNNotificationPresentationOptions) {
        lock.lock()
        let callback = callback
        self.callback = nil
        lock.unlock()
        callback?(value)
    }
}

private final class ResponseCompletion: @unchecked Sendable {
    private let completion: PresentationCompletion
    init(_ callback: @escaping () -> Void) { completion = PresentationCompletion { _ in callback() } }
    func call() { completion.call([]) }
}
