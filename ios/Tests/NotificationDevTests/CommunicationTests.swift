import XCTest
import UserNotifications
import Intents
@testable import NotificationDev
@testable import NotificationDevCommunication
@testable import NotificationDevServiceExtension

final class CommunicationTests: XCTestCase {
    private func content(association: String = "association-one") -> UNMutableNotificationContent {
        let value = UNMutableNotificationContent()
        value.title = "Fallback title"; value.body = "Private message"
        value.userInfo = ["notification_dev": ["deliveryId": "delivery-one", "associationId": association, "expiresAt": Int64(Date().timeIntervalSince1970 * 1000) + 600000,
            "communication": ["sender": ["id": "person", "name": "Anna"], "conversationId": "thread"]], "other": "preserved"]
        return value
    }

    func testCommunicationIntentScopesIdentityAndPreservesEnvelope() {
        let original = content()
        var results: [UNNotificationContent] = []
        let operation = CommunicationEnrichment(content: original) { results.append($0) }
        operation.isCurrentAssociation = { _ in true }
        operation.donateInteraction = { interaction, completion in
            XCTAssertEqual(interaction.direction, .incoming)
            XCTAssertEqual(interaction.groupIdentifier, CommunicationEnrichment.group("association-one"))
            XCTAssertEqual(interaction.identifier, CommunicationEnrichment.identifier("association-one", "delivery-one"))
            let intent = interaction.intent as! INSendMessageIntent
            XCTAssertEqual(intent.sender?.displayName, "Anna")
            XCTAssertEqual(intent.sender?.personHandle?.value, CommunicationEnrichment.identifier("association-one", "person"))
            XCTAssertEqual(intent.conversationIdentifier, CommunicationEnrichment.identifier("association-one", "thread"))
            completion(nil)
        }
        operation.updateContent = { content, _ in
            let copy = content.mutableCopy() as! UNMutableNotificationContent
            copy.title = "Anna"
            return copy
        }
        operation.start(); operation.cancel()
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].title, "Anna")
        XCTAssertEqual(results[0].body, original.body)
        XCTAssertEqual(results[0].userInfo["notification_dev"] as? NSDictionary, original.userInfo["notification_dev"] as? NSDictionary)
        XCTAssertEqual(results[0].userInfo[CommunicationEnrichment.marker] as? Bool, true)
        XCTAssertNotEqual(CommunicationEnrichment.identifier("association-one", "person"), CommunicationEnrichment.identifier("association-two", "person"))
        XCTAssertEqual(CommunicationEnrichment.identifier("association-one", String(repeating: "x", count: 255)).count, 64)
    }

    func testCancellationCompletesOnceAndIgnoresLateDonation() {
        let original = content()
        var callbacks: [(Error?) -> Void] = []
        var results: [UNNotificationContent] = []
        let operation = CommunicationEnrichment(content: original) { results.append($0) }
        operation.isCurrentAssociation = { _ in true }
        operation.donateInteraction = { _, done in callbacks.append(done) }
        operation.updateContent = { _, _ in XCTFail("Cancelled operation must not update presentation"); return original }
        operation.start(); operation.cancel(); callbacks[0](nil); operation.cancel()
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].title, original.title)
        XCTAssertEqual(results[0].userInfo as NSDictionary, original.userInfo as NSDictionary)
    }

    func testInvalidSenderAndDonationFailureUseReadableFallback() {
        let original = content()
        let operation = CommunicationEnrichment(content: original) {
            XCTAssertEqual($0.title, original.title)
            XCTAssertNil($0.userInfo[CommunicationEnrichment.marker])
        }
        operation.isCurrentAssociation = { _ in true }
        operation.donateInteraction = { _, completion in completion(NSError(domain: "fixture", code: 1)) }
        operation.start()
        var envelope = original.userInfo["notification_dev"] as! [String: Any]
        envelope["communication"] = ["sender": ["id": " ", "name": "Anna"], "conversationId": "thread"]
        original.userInfo["notification_dev"] = envelope
        XCTAssertNil(CommunicationMessage.read(original))
    }

    @MainActor
    func testPrivateForegroundReplayIsConsumedOnceAndRejectsOtherIdentities() {
        let values = ForegroundCommunicationPresentations()
        XCTAssertFalse(values.contains("remote-supplied-identifier"))
        XCTAssertTrue(values.store("private-local-id", association: "a", options: [.banner]))
        XCTAssertNil(values.take("private-local-id", association: "b"))
        XCTAssertFalse(values.contains("private-local-id"))
        XCTAssertTrue(values.store("private-local-id", association: "a", options: [.banner]))
        XCTAssertEqual(values.take("private-local-id", association: "a"), [.banner])
        XCTAssertNil(values.take("private-local-id", association: "a"))
    }
    func testNSEUnconfiguredScopeAndExpiryRetainReadableEnvelopeOnce() {
        let original = content()
        var envelope = original.userInfo["notification_dev"] as! [String: Any]
        envelope["communication"] = ["sender": ["id": "person", "name": "Anna", "avatarUrl": "https://communication.test/stalled"], "conversationId": "thread"]
        original.userInfo["notification_dev"] = envelope
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CommunicationFixtureProtocol.self]
        let service = NotificationDevServiceExtension.NotificationService(configuration: config)
        var results: [UNNotificationContent] = []
        service.didReceive(UNNotificationRequest(identifier: "request", content: original, trigger: nil)) { results.append($0) }
        service.serviceExtensionTimeWillExpire(); service.serviceExtensionTimeWillExpire()
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].userInfo as NSDictionary, original.userInfo as NSDictionary)
        XCTAssertEqual(results[0].body, original.body)
    }

    @MainActor
    func testPrivateForegroundReplayExpiresAndCapacityIsBounded() async throws {
        let expired = ForegroundCommunicationPresentations(lifetime: 0.01)
        XCTAssertTrue(expired.store("expiring", association: "a", options: [.banner]))
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertFalse(expired.contains("expiring"))
        let bounded = ForegroundCommunicationPresentations()
        for i in 0..<128 { XCTAssertTrue(bounded.store(String(i), association: "a", options: [.banner])) }
        XCTAssertFalse(bounded.store("overflow", association: "a", options: [.banner]))
    }

    func testMalformedCommunicationDoesNotDiscardLegacyDelivery() throws {
        let raw = Data("""
        {"version":1,"deliveryId":"delivery","associationId":"a","expiresAt":1,
        "title":"Title","body":"Body","data":{},"communication":{"sender":{"id":" ","name":"A"},"conversationId":"c"}}
        """.utf8)
        let payload = try JSONDecoder().decode(NotificationPayload.self, from: raw)
        XCTAssertNil(payload.communication)
        XCTAssertEqual(payload.deliveryId, "delivery")
        XCTAssertEqual(payload.title, "Title")
    }
    func testExpiredCommunicationDoesNotDonate() {
        let original = content()
        var envelope = original.userInfo["notification_dev"] as! [String: Any]
        envelope["expiresAt"] = 1
        original.userInfo["notification_dev"] = envelope
        var count = 0
        let operation = CommunicationEnrichment(content: original) { result in
            count += 1; XCTAssertEqual(result.body, original.body)
        }
        operation.isCurrentAssociation = { _ in true }
        operation.donateInteraction = { _, _ in XCTFail("Expired notifications must not donate") }
        operation.start()
        XCTAssertEqual(count, 1)
    }

    func testAvatarTransportAcceptsValidImagesAndRejectsOversizeOrUnsupportedTypes() async {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CommunicationFixtureProtocol.self]
        for path in ["good", "oversize", "type"] {
            let completed = expectation(description: path)
            let task = ImageDownload(configuration: config) { result in
                if path == "good" { XCTAssertEqual(result?.suffix, "png"); XCTAssertNotNil(result?.data) }
                else { XCTAssertNil(result) }
                completed.fulfill()
            }
            task.start(URL(string: "https://communication.test/" + path)!)
            await fulfillment(of: [completed], timeout: 2)
        }
    }

    func testScopeRevocationPreventsDelayedDonationAndSharedFileIsFailClosed() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        XCTAssertNil(CommunicationAssociationScope.read(file))
        try CommunicationAssociationScope.write("a", to: file)
        XCTAssertEqual(CommunicationAssociationScope.read(file), "a")
        try CommunicationAssociationScope.write(nil, to: file)
        XCTAssertNil(CommunicationAssociationScope.read(file))
        let original = content()
        var completion: ((Error?) -> Void)?
        var active = true
        var results: [UNNotificationContent] = []
        let task = CommunicationEnrichment(content: original) { results.append($0) }
        task.isCurrentAssociation = { _ in active }
        task.donateInteraction = { _, done in completion = done }
        task.updateContent = { _, _ in XCTFail("Revoked identity must not update avatar content"); return original }
        task.start(); active = false; completion?(nil)
        XCTAssertEqual(results.count, 1)
        XCTAssertNil(results[0].userInfo[CommunicationEnrichment.marker])
    }

    @MainActor
    func testForegroundReplayEntriesExpire() async throws {
        let values = ForegroundCommunicationPresentations(lifetime: 0.01)
        XCTAssertTrue(values.store("private-id", association: "a", options: [.banner]))
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertFalse(values.contains("private-id"))
        XCTAssertNil(values.take("private-id", association: "a"))
    }

    func testEnrichmentTimeoutReturnsOriginalAndIgnoresLateSuccess() async {
        let original = content()
        let completed = expectation(description: "Bounded enrichment")
        var done: ((Error?) -> Void)?
        var count = 0
        let task = CommunicationEnrichment(content: original) { result in
            count += 1; XCTAssertEqual(result.title, original.title); completed.fulfill()
        }
        task.deadlineInterval = 0.01
        task.isCurrentAssociation = { _ in true }
        task.donateInteraction = { _, callback in done = callback }
        task.start()
        await fulfillment(of: [completed], timeout: 1)
        done?(nil)
        XCTAssertEqual(count, 1)
    }

}


private final class CommunicationFixtureProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "communication.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url, url.lastPathComponent != "stalled" else { return }
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=")!
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: [
            "Content-Type": url.lastPathComponent == "type" ? "text/html" : "image/png",
            "Content-Length": url.lastPathComponent == "oversize" ? "3000000" : String(png.count)
        ])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: png)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

}
