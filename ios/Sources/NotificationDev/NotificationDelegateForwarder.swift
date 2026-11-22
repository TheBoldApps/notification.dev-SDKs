import UserNotifications
import NotificationDevCommunication

internal protocol NotificationCenterDelegating: AnyObject {
    var delegate: UNUserNotificationCenterDelegate? { get set }
}

extension UNUserNotificationCenter: NotificationCenterDelegating {}

/// Owns notification.dev callbacks and forwards unrelated notifications to the previous delegate.
/// Retain this object for the lifetime of the application.
@MainActor
public final class NotificationDelegateForwarder: NSObject, UNUserNotificationCenterDelegate {
    private let localPresentations = ForegroundCommunicationPresentations()
    private var communications: [String: CommunicationEnrichment] = [:]
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
        if center.delegate === self { return }
        if installed {
            diagnostic(SdkError(code: "NOTIFICATION_DELEGATE_REPLACED",
                message: "The notification delegate changed after SDK setup; forward notification.dev responses to handleResponse."))
            return
        }
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
            if localPresentations.contains(notification.request.identifier) {
                let current = try? await client()
                complete.call(localPresentations.take(notification.request.identifier, association: current?.state.identityChangePending == false ? current?.state.associationId : nil) ?? [])
                return
            }
            do {
                let sdk = try await client()
                let options = await sdk.presentationOptions(for: notification) ?? []
                guard !options.isEmpty,
                    notification.request.content.userInfo[CommunicationEnrichment.marker] == nil,
                    CommunicationMessage.read(notification.request.content) != nil else { complete.call(options); return }
                let association = sdk.state.associationId
                let identifier = UUID().uuidString
                let localIdentifier = notification.request.identifier + ".communication." + identifier
                let operation = CommunicationEnrichment(content: notification.request.content) { [weak self] content in
                    Task { @MainActor in
                        self?.communications.removeValue(forKey: identifier)
                        guard sdk.state.associationId == association, !sdk.state.identityChangePending else {
                            if let association { CommunicationEnrichment.clear(associationId: association) }
                            complete.call([]); return
                        }
                        guard content.userInfo[CommunicationEnrichment.marker] != nil else { complete.call(options); return }
                        do {
                            guard let association, self?.localPresentations.store(localIdentifier, association: association, options: options) == true else { complete.call(options); return }
                            try await center.add(UNNotificationRequest(identifier: localIdentifier, content: content, trigger: nil))
                            complete.call([])
                        } catch { self?.localPresentations.remove(localIdentifier); complete.call(options) }
                    }
                }
                communications[identifier] = operation
                operation.start()
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

/// Only locally registered identifiers can skip the SDK receive path. Payload markers cannot.
@MainActor
internal final class ForegroundCommunicationPresentations {
    private struct Entry { let association: String; let options: UNNotificationPresentationOptions; let expires: Date }
    private var entries: [String: Entry] = [:]
    private let lifetime: TimeInterval
    init(lifetime: TimeInterval = 30) { self.lifetime = lifetime }
    func contains(_ id: String) -> Bool { entries[id] != nil }
    func store(_ id: String, association: String, options: UNNotificationPresentationOptions) -> Bool {
        entries = entries.filter { $0.value.expires > Date() }
        guard entries.count < 128 else { return false }
        entries[id] = Entry(association: association, options: options, expires: Date().addingTimeInterval(lifetime))
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(0, lifetime) * 1_000_000_000))
            self?.entries.removeValue(forKey: id)
        }
        return true
    }
    func take(_ id: String, association: String?) -> UNNotificationPresentationOptions? {
        guard let entry = entries.removeValue(forKey: id), entry.expires > Date(), entry.association == association else { return nil }
        return entry.options
    }
    func remove(_ id: String) { entries.removeValue(forKey: id) }
}
