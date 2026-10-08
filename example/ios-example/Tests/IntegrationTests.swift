import NotificationDev
import XCTest

@MainActor
final class IntegrationTests: XCTestCase {
    func testFacadeInitializationAndIndependentStreams() async throws {
        let config = SdkConfig(
            projectId: "ios-swift-facade-tests", baseURL: URL(string: "http://127.0.0.1:1/")!,
            allowLocalhostHTTP: true
        )
        let client = try await NotificationDev.initialize(config)
        let repeated = try await NotificationDev.initialize(config)
        XCTAssertTrue(client === repeated)
        do {
            _ = try await NotificationDev.initialize(SdkConfig(projectId: "different", baseURL: config.baseURL))
            XCTFail("Expected configuration mismatch")
        } catch {
            XCTAssertEqual((error as? SdkError)?.code, "CONFIGURATION_CHANGED")
        }

        try await client.setTags(["swift": .bool(true)])
        XCTAssertEqual(client.getTags()["swift"], .bool(true))
        var first = client.states.makeAsyncIterator()
        var second = client.states.makeAsyncIterator()
        let firstState = await first.next()
        let secondState = await second.next()
        XCTAssertEqual(firstState?.installationId, client.state.installationId)
        XCTAssertEqual(secondState?.user.tags["swift"], .bool(true))
    }

}
