import UserNotifications
import XCTest

@testable import NotificationDev
import NotificationDevServiceExtension

final class AdvancedPushTests: XCTestCase {
    func testRoutingUsesProvisioningEntitlementRatherThanBuildMode() throws {
        for (value, expected) in [("development", ApnsEnvironment.sandbox), ("production", .production)] {
            let plist = try PropertyListSerialization.data(
                fromPropertyList: ["Entitlements": ["aps-environment": value]], format: .xml, options: 0)
            var profile = Data([0, 1, 2])
            profile.append(plist)
            profile.append(Data([3, 4]))
            XCTAssertEqual(NativeRouting.environment(in: profile), expected)
        }
        XCTAssertNil(NativeRouting.environment(in: Optional<Data>.none))
        XCTAssertNil(NativeRouting.environment(in: Data("bad profile".utf8)))
    }

    func testAdvancedPayloadRoundTripPreservesExplicitSilenceAndNestedData() throws {
        let data = Data("""
            {"version":1,"deliveryId":"d","associationId":"a","expiresAt":1900000000000,
            "title":"Title","body":"Body","data":{"nested":[1,true,null]},"deepLink":null,
            "collapseId":"chat","ios":{"sound":null,"badge":0},
            "android":{"channelId":"chat","groupKey":"chat","groupSummary":"Two messages"},
            "contentAvailable":true,"imageUrl":"https://example.com/image.png"}
            """.utf8)
        let payload = try JSONDecoder().decode(NotificationPayload.self, from: data)
        XCTAssertEqual(payload.ios?.hasSound, true)
        XCTAssertNil(payload.ios?.sound)
        XCTAssertEqual(payload.ios?.badge, 0)
        XCTAssertEqual(payload.android?.groupSummary, "Two messages")
        let encoded = try JSONEncoder().encode(payload)
        XCTAssertEqual(try JSONDecoder().decode(NotificationPayload.self, from: encoded), payload)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertTrue((json["ios"] as? [String: Any])?["sound"] is NSNull)
        let omitted = try JSONDecoder().decode(IosNotificationOptions.self, from: Data("{}".utf8))
        XCTAssertFalse(omitted.hasSound)
        XCTAssertEqual(String(decoding: try JSONEncoder().encode(omitted), as: UTF8.self), "{}")
    }

    func testLegacyPayloadHasNoAdvancedOptions() throws {
        let payload = try JSONDecoder().decode(NotificationPayload.self, from: Data("""
            {"version":1,"deliveryId":"d","associationId":"a","expiresAt":1,
            "title":"Title","body":"Body","data":{}}
            """.utf8))
        XCTAssertNil(payload.ios)
        XCTAssertNil(payload.collapseId)
        XCTAssertNil(SdkConfig(projectId: "p").apnsEnvironment)
        XCTAssertEqual(SdkConfig(projectId: "p", apnsEnvironment: .production).apnsEnvironment, .production)
    }

    func testExtensionFallbackPreservesForeignAndMalformedRequestsAndCompletesOnce() {
        for info: [AnyHashable: Any] in [["other_provider": "value"], ["notification_dev": "malformed"]] {
            let content = UNMutableNotificationContent()
            content.title = "Original"
            content.userInfo = info
            let request = UNNotificationRequest(identifier: "request", content: content, trigger: nil)
            let service = NotificationDevServiceExtension.NotificationService()
            var completions = 0
            service.didReceive(request) { result in
                completions += 1
                XCTAssertEqual(result.title, "Original")
                XCTAssertEqual(result.userInfo as NSDictionary, info as NSDictionary)
            }
            service.serviceExtensionTimeWillExpire()
            XCTAssertEqual(completions, 1)
        }
    }

    @MainActor
    func testForwarderCapturesPreviousDelegateOnce() {
        let center = TestNotificationCenter()
        let delegate = HostDelegate()
        center.delegate = delegate
        let forwarder = NotificationDelegateForwarder(client: { throw CancellationError() })
        forwarder.installDelegate(on: center)
        forwarder.installDelegate(on: center)
        XCTAssertTrue(center.delegate === forwarder)
        XCTAssertTrue(forwarder.forwardingDelegate === delegate)
    }
}

private final class HostDelegate: NSObject, UNUserNotificationCenterDelegate {}

private final class TestNotificationCenter: NotificationCenterDelegating {
    weak var delegate: UNUserNotificationCenterDelegate?
}
