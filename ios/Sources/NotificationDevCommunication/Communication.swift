import Foundation
import CryptoKit
import ImageIO
import Intents
import UserNotifications

public struct CommunicationSender: Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let avatarUrl: String?
}

public struct CommunicationMessage: Codable, Equatable, Sendable {
    public let sender: CommunicationSender
    public let conversationId: String
    enum CodingKeys: String, CodingKey { case sender, conversationId }
    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        sender = try values.decode(CommunicationSender.self, forKey: .sender)
        conversationId = try values.decode(String.self, forKey: .conversationId)
        guard [sender.id, sender.name, conversationId].allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.unicodeScalars.count <= 255 }),
            sender.avatarUrl.map({ $0.unicodeScalars.count <= 2048 && ImageDownload.url($0) != nil }) ?? true
        else { throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid communication metadata")) }
    }

    public static func read(_ content: UNNotificationContent) -> CommunicationMessage? {
        guard let envelope = content.userInfo["notification_dev"] as? [String: Any],
            let raw = envelope["communication"], JSONSerialization.isValidJSONObject(raw),
            let data = try? JSONSerialization.data(withJSONObject: raw),
            let message = try? JSONDecoder().decode(Self.self, from: data),
            [message.sender.id, message.sender.name, message.conversationId].allSatisfy({
                !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.count <= 255
            }) else { return nil }
        if let avatar = message.sender.avatarUrl,
            (avatar.count > 2048 || ImageDownload.url(avatar) == nil) { return nil }
        return message
    }
}

/// Optional enrichment only. Every failure returns original content once.
public final class CommunicationEnrichment: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: ((UNNotificationContent) -> Void)?
    private let original: UNNotificationContent
    private var download: ImageDownload?
    private var timeout: DispatchWorkItem?
    private let configuration: URLSessionConfiguration
    internal var isCurrentAssociation: (String) -> Bool = { CommunicationAssociationScope.matches($0) }
    internal var deadlineInterval: TimeInterval = 7
    private var started = false
    internal var donateInteraction: (INInteraction, @escaping (Error?) -> Void) -> Void = { $0.donate(completion: $1) }
    internal var updateContent: (UNNotificationContent, INSendMessageIntent) throws -> UNNotificationContent = { try $0.updating(from: $1) }
    public static let marker = "notification_dev.communication.applied"

    public init(content: UNNotificationContent, configuration: URLSessionConfiguration = .ephemeral,
                completion: @escaping (UNNotificationContent) -> Void) {
        original = content
        self.configuration = configuration
        callback = completion
    }

    private var unexpired: Bool {
        guard let envelope = original.userInfo["notification_dev"] as? [String: Any],
            let expires = envelope["expiresAt"] as? NSNumber else { return false }
        return expires.doubleValue.isFinite && expires.doubleValue > Date().timeIntervalSince1970 * 1000
    }

    public func start() {
        lock.lock(); let active = callback != nil && !started; started = true; lock.unlock()
        guard active else { return }
        guard unexpired else { finish(original); return }
        guard original.userInfo[Self.marker] == nil,
            let message = CommunicationMessage.read(original),
            let envelope = original.userInfo["notification_dev"] as? [String: Any],
            let association = envelope["associationId"] as? String,
            isCurrentAssociation(association) else { finish(original); return }
        let deadline = DispatchWorkItem { [weak self] in self?.cancel() }
        lock.lock(); timeout = deadline; lock.unlock()
        DispatchQueue.global().asyncAfter(deadline: .now() + deadlineInterval, execute: deadline)
        if let raw = message.sender.avatarUrl, let url = ImageDownload.url(raw) {
            let task = ImageDownload(configuration: configuration) { [weak self] image in
                self?.donate(message, avatar: image?.data)
            }
            lock.lock(); download = task; let active = callback != nil; lock.unlock()
            if active { task.start(url) }
        } else { donate(message, avatar: nil) }
    }

    public func cancel() { finish(original) }
    public static func identifier(_ association: String, _ id: String) -> String {
        SHA256.hash(data: Data((association + "\u{0}" + id).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    public static func group(_ association: String) -> String { "notification.dev." + identifier(association, "group") }
    public static func clear(associationId: String) {
        INInteraction.delete(with: group(associationId)) { _ in }
    }

    private func donate(_ message: CommunicationMessage, avatar: Data?) {
        lock.lock(); let active = callback != nil; lock.unlock()
        guard active else { return }
        guard let envelope = original.userInfo["notification_dev"] as? [String: Any],
              let association = envelope["associationId"] as? String, !association.isEmpty,
              let delivery = envelope["deliveryId"] as? String, !delivery.isEmpty
        else { finish(original); return }
        guard unexpired && isCurrentAssociation(association) else { finish(original); return }
        let senderId = Self.identifier(association, message.sender.id)
        let sender = INPerson(personHandle: INPersonHandle(value: senderId, type: .unknown),
                              nameComponents: nil, displayName: message.sender.name,
                              image: avatar.map { INImage(imageData: $0) },
                              contactIdentifier: nil, customIdentifier: senderId)
        let intent = INSendMessageIntent(recipients: nil, outgoingMessageType: .outgoingMessageText,
                                        content: original.body, speakableGroupName: nil,
                                        conversationIdentifier: Self.identifier(association, message.conversationId),
                                        serviceName: nil, sender: sender, attachments: nil)
        let interaction = INInteraction(intent: intent, response: nil)
        interaction.identifier = Self.identifier(association, delivery)
        interaction.groupIdentifier = Self.group(association)
        interaction.direction = .incoming
        interaction.identifier = Self.identifier(association, delivery)
        interaction.groupIdentifier = Self.group(association)
        donateInteraction(interaction) { [weak self] error in
            guard let self else { return }
            self.lock.lock(); let active = self.callback != nil; self.lock.unlock()
            guard active else { INInteraction.delete(with: [Self.identifier(association, delivery)]) { _ in }; return }
            guard self.unexpired && self.isCurrentAssociation(association) else {
                Self.clear(associationId: association)
                self.finish(self.original); return
            }
            guard error == nil,
                let updated = try? self.updateContent(self.original, intent).mutableCopy() as? UNMutableNotificationContent
            else { self.finish(self.original); return }
            // Siri styling must never remove the SDK envelope or navigation/tracking IDs.
            updated.userInfo = self.original.userInfo
            updated.userInfo[Self.marker] = true
            updated.body = self.original.body
            updated.attachments = self.original.attachments
            self.finish(updated)
        }
    }

    private func finish(_ content: UNNotificationContent) {
        lock.lock()
        let completion = callback; callback = nil
        let task = download; download = nil
        let timer = timeout; timeout = nil
        lock.unlock()
        timer?.cancel(); task?.cancel()
        completion?(content)
    }
}

public struct NotificationImage: Sendable {
    public let data: Data
    public let suffix: String
}

/// Bounded shared image transport for attachments and sender avatars.
public final class ImageDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private var callback: ((NotificationImage?) -> Void)?
    private var session: URLSession?
    private var data = Data()
    private var suffix: String?
    public init(configuration: URLSessionConfiguration, completion: @escaping (NotificationImage?) -> Void) {
        self.configuration = configuration; callback = completion
    }
    public static func url(_ raw: String) -> URL? {
        guard let url = URL(string: raw), ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
            url.host != nil, url.user == nil, url.password == nil else { return nil }
        return url
    }
    public func start(_ url: URL) {
        let config = configuration.copy() as! URLSessionConfiguration
        config.timeoutIntervalForRequest = 5; config.timeoutIntervalForResource = 5
        config.urlCache = nil; config.httpCookieStorage = nil
        let connection = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        lock.lock(); session = connection; let active = callback != nil; lock.unlock()
        if active { connection.dataTask(with: url).resume() } else { connection.invalidateAndCancel() }
    }
    public func cancel() { complete(false) }
    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                           completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let types = ["image/jpeg": "jpg", "image/png": "png", "image/gif": "gif"]
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
            response.expectedContentLength <= 2 * 1024 * 1024, let ext = types[response.mimeType ?? ""]
        else { completionHandler(.cancel); complete(false); return }
        lock.lock(); suffix = ext; let active = callback != nil; lock.unlock()
        completionHandler(active ? .allow : .cancel)
    }
    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        lock.lock(); let allowed = callback != nil && data.count + bytes.count <= 2 * 1024 * 1024
        if allowed { data.append(bytes) }; lock.unlock()
        if !allowed { complete(false) }
    }
    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil); complete(false)
    }
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { complete(error == nil) }
    private func complete(_ success: Bool) {
        lock.lock(); let completion = callback; callback = nil
        let bytes = data; data = Data(); let ext = suffix; let connection = session; session = nil; lock.unlock()
        connection?.invalidateAndCancel()
        var result: NotificationImage?
        if success, let ext, let source = CGImageSourceCreateWithData(bytes as CFData, nil),
            let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let width = props[kCGImagePropertyPixelWidth] as? Int,
            let height = props[kCGImagePropertyPixelHeight] as? Int,
            width > 0, height > 0, width <= 2048, height <= 2048 {
            result = NotificationImage(data: bytes, suffix: ext)
        }
        completion?(result)
    }
}

/// Shared public association only; no tokens, payloads or credentials enter the App Group.
public enum CommunicationAssociationScope {
    public static let infoKey = "NotificationDevCommunicationAppGroup"
    private static var file: URL? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: infoKey) as? String,
            group.hasPrefix("group."),
            let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        return directory.appendingPathComponent("notification-dev-communication-association")
    }
    public static func matches(_ association: String) -> Bool {
        guard let file else { return false }
        return read(file) == association
    }
    public static func publish(_ association: String?) {
        guard let file else { return }
        let previous = read(file)
        do { try write(association, to: file) }
        catch { try? FileManager.default.removeItem(at: file) }
        if let previous, previous != association { CommunicationEnrichment.clear(associationId: previous) }
    }
    internal static func read(_ url: URL) -> String? {
        guard let data = try? Data(contentsOf: url), data.count <= 1024,
            let value = String(data: data, encoding: .utf8), !value.isEmpty else { return nil }
        return value
    }
    internal static func write(_ value: String?, to url: URL) throws {
        try Data((value ?? "").utf8).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
