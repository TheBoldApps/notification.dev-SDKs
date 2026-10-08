import Flutter
import UIKit
import XCTest
import notification_dev

final class RunnerTests: XCTestCase {
    @MainActor
    func testInvalidInitializationReturnsAnActionableError() async {
        let plugin = NotificationDevPlugin()
        let done = expectation(description: "Method result")
        plugin.handle(FlutterMethodCall(methodName: "initialize", arguments: [:])) { result in
            XCTAssertEqual((result as? FlutterError)?.code, "INVALID_ARGUMENT")
            done.fulfill()
        }

        await fulfillment(of: [done], timeout: 5)
    }

    @MainActor
    func testCallsBeforeInitializationFailWithoutTouchingNativeState() async {
        let plugin = NotificationDevPlugin()
        let done = expectation(description: "Method result")
        plugin.handle(FlutterMethodCall(methodName: "getState", arguments: nil)) { result in
            XCTAssertEqual((result as? FlutterError)?.code, "NOT_INITIALIZED")
            done.fulfill()
        }

        await fulfillment(of: [done], timeout: 5)
    }

    @MainActor
    func testUnconfiguredPluginDoesNotClaimNotificationCallbacks() {
        let plugin = NotificationDevPlugin()
        XCTAssertFalse(
            plugin.responds(
                to: NSSelectorFromString(
                    "userNotificationCenter:didReceiveNotificationResponse:withCompletionHandler:")))
        XCTAssertFalse(
            plugin.responds(
                to: NSSelectorFromString(
                    "userNotificationCenter:willPresentNotification:withCompletionHandler:")))
    }
}
