import ActivityKit

/// Metadata supplied by a remote start, or by your authenticated backend for a local start.
public struct LiveActivityRegistrationMetadata: Codable, Hashable, Sendable {
    public var activityId: String
    public var registrationToken: String

    public init(activityId: String, registrationToken: String) {
        self.activityId = activityId
        self.registrationToken = registrationToken
    }

    public static func create(activityId: String, registrationToken: String) -> Self {
        Self(activityId: activityId, registrationToken: registrationToken)
    }
}

/// Share this conformance between your app and widget extension targets.
@available(iOS 16.1, *)
public protocol NotificationDevLiveActivityAttributes: ActivityAttributes {
    // swift-format-ignore: AlwaysUseLowerCamelCase
    var notification_dev: LiveActivityRegistrationMetadata? { get }
}

/// Dictionary-based attributes for apps that don't need a custom Swift schema.
@available(iOS 16.1, *)
public struct DefaultLiveActivityAttributes: NotificationDevLiveActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var data: [String: JSONValue]

        public init(data: [String: JSONValue]) { self.data = data }
    }

    public var data: [String: JSONValue]
    // swift-format-ignore: AlwaysUseLowerCamelCase
    public var notification_dev: LiveActivityRegistrationMetadata?

    public init(data: [String: JSONValue], notification_dev: LiveActivityRegistrationMetadata?) {
        self.data = data
        self.notification_dev = notification_dev
    }
}
