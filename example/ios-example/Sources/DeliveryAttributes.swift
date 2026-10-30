import NotificationDevActivityKit

@available(iOS 16.1, *)
struct DeliveryAttributes: NotificationDevLiveActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String
        var minutesRemaining: Int
    }

    var orderId: String
    // swift-format-ignore: AlwaysUseLowerCamelCase
    var notification_dev: LiveActivityRegistrationMetadata?
}
