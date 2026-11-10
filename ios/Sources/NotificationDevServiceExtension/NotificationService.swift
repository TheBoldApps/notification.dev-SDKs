import Foundation
import ImageIO
import UserNotifications

/// Link only this product in a Notification Service Extension target.
/// Other providers can be handled by overriding didReceive and forwarding unowned requests.
open class NotificationService: UNNotificationServiceExtension, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var handler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?
    private var session: URLSession?
    private var bytes = Data()
    private var fileExtension: String?
    private static let maximumBytes = 2 * 1024 * 1024

    public static func owns(_ request: UNNotificationRequest) -> Bool {
        request.content.userInfo["notification_dev"] != nil
    }

    open override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        finish()
        guard Self.owns(request),
            let envelope = request.content.userInfo["notification_dev"] as? [String: Any],
            let raw = envelope["imageUrl"] as? String,
            let url = URL(string: raw), ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
            url.user == nil, url.password == nil,
            let copy = request.content.mutableCopy() as? UNMutableNotificationContent
        else {
            contentHandler(request.content)
            return
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        let connection = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        lock.lock()
        handler = contentHandler
        content = copy
        bytes = Data()
        fileExtension = nil
        session = connection
        lock.unlock()
        connection.dataTask(with: url).resume()
    }

    open override func serviceExtensionTimeWillExpire() { finish() }

    public func urlSession(
        _ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        let extensions = ["image/jpeg": "jpg", "image/png": "png", "image/gif": "gif"]
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
            response.expectedContentLength <= Int64(Self.maximumBytes),
            let suffix = extensions[response.mimeType ?? ""]
        else {
            completionHandler(.cancel)
            finish(session: session)
            return
        }
        lock.lock()
        let active = self.session === session && handler != nil
        if active { fileExtension = suffix }
        lock.unlock()
        completionHandler(active ? .allow : .cancel)
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let allowed = self.session === session && handler != nil && bytes.count + data.count <= Self.maximumBytes
        if allowed { bytes.append(data) }
        lock.unlock()
        if !allowed { dataTask.cancel(); finish(session: session) }
    }

    public func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // A redirect is optional enrichment; retain the original alert instead.
        completionHandler(nil)
        finish(session: session)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(session: session, attachImage: error == nil)
    }

    private func finish(session expectedSession: URLSession? = nil, attachImage: Bool = false) {
        lock.lock()
        guard expectedSession == nil || session === expectedSession,
            let handler, let content else { lock.unlock(); return }
        let data = bytes
        let suffix = fileExtension
        let connection = session
        self.handler = nil
        self.content = nil
        session = nil
        bytes = Data()
        lock.unlock()
        connection?.invalidateAndCancel()

        if attachImage, let suffix,
            let source = CGImageSourceCreateWithData(data as CFData, nil),
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
            let width = properties[kCGImagePropertyPixelWidth] as? Int,
            let height = properties[kCGImagePropertyPixelHeight] as? Int,
            width > 0, height > 0, width <= 2048, height <= 2048 {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: directory) }
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let file = directory.appendingPathComponent("image.\(suffix)")
                try data.write(to: file)
                let attachment = try UNNotificationAttachment(identifier: "notification_dev.image", url: file)
                content.attachments.append(attachment)
            } catch { /* Optional image failure preserves the original alert and userInfo. */ }
        }
        handler(content)
    }
}
