import Foundation
@_exported import NotificationDevActivityKit

public enum ApnsEnvironment: String, Codable, Sendable {
    case sandbox, production
}

public struct SdkConfig: Equatable, Sendable {
    public let projectId: String
    public let baseURL: URL
    public var allowHTTP: Bool
    public var loggingEnabled: Bool
    public var displayInForeground: Bool
    public var apnsEnvironment: ApnsEnvironment?

    public init(
        projectId: String, baseURL: URL = URL(string: "https://app.notification.dev/")!,
        allowHTTP: Bool = false,
        loggingEnabled: Bool = false, displayInForeground: Bool = true,
        apnsEnvironment: ApnsEnvironment? = nil
    ) {
        self.projectId = projectId
        self.baseURL = baseURL
        self.allowHTTP = allowHTTP
        self.loggingEnabled = loggingEnabled
        self.displayInForeground = displayInForeground
        self.apnsEnvironment = apnsEnvironment
    }
}

public enum TagValue: Equatable, Sendable {
    case string(String)
    case bool(Bool)
    case number(Double)

    var json: JSONValue {
        switch self {
        case .string(let v): return .string(v)
        case .bool(let v): return .bool(v)
        case .number(let v): return .number(v)
        }
    }
}

public struct SdkError: Error, Codable, Equatable, Sendable, LocalizedError {
    public let code: String
    public let message: String
    public var errorDescription: String? { message }
}

public struct EmailSubscription: Codable, Equatable, Sendable {
    public let address: String
    public let optedIn: Bool
    public let suppressed: Bool
}

public struct UserProperties: Codable, Equatable, Sendable {
    public let email: EmailSubscription?
    public let tags: [String: JSONValue]
}

public enum Availability: String, Codable, Sendable {
    case loading = "LOADING"
    case available = "AVAILABLE"
    case unavailable = "UNAVAILABLE"
}

public struct PushRegistration: Codable, Equatable, Sendable {
    public let status: String
    public let errorCode: String?
    public let updatedAt: String?
}

public struct PushPermissionStatus: Codable, Equatable, Sendable {
    public let areNotificationsEnabled: Bool
}

public struct SdkState: Codable, Equatable, Sendable {
    public let availability: Availability
    public let user: UserProperties
    public let installationId: String?
    public let associationId: String?
    public let externalId: String?
    public let lastSyncedAt: Int64?
    public let syncing: Bool
    public let hasPendingChanges: Bool
    public let pushOptedIn: Bool
    public let pushRegistration: PushRegistration
    public let permission: PushPermissionStatus
    public let lastError: SdkError?
    public let identityChangePending: Bool
    public let pushTokenRegistered: Bool
}

public struct NotificationPayload: Codable, Equatable, Sendable {
    public let version: Int
    public let deliveryId: String
    public let associationId: String
    public let expiresAt: Int64
    public let title: String
    public let body: String
    public let data: [String: JSONValue]
    public let deepLink: String?
    public let collapseId: String?
    public let threadId: String?
    public let ttlSeconds: Int?
    public let priority: String?
    public let ios: IosNotificationOptions?
    public let android: AndroidNotificationOptions?
    public let imageUrl: String?
    public let contentAvailable: Bool?
}

public struct NotificationEvent: Codable, Equatable, Sendable {
    public let notification: NotificationPayload
}

public struct NotificationOpen: Codable, Equatable, Sendable, Identifiable {
    public let interactionId: String
    public let notification: NotificationPayload
    public let openedAt: Int64
    public var id: String { interactionId }
}

public struct IosNotificationOptions: Codable, Equatable, Sendable {
    public let hasSound: Bool

    enum CodingKeys: String, CodingKey {
        case sound, badge, interruptionLevel, mutableContent, subtitle
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        hasSound = values.contains(.sound)
        sound = try values.decodeIfPresent(String.self, forKey: .sound)
        badge = try values.decodeIfPresent(Int.self, forKey: .badge)
        interruptionLevel = try values.decodeIfPresent(String.self, forKey: .interruptionLevel)
        mutableContent = try values.decodeIfPresent(Bool.self, forKey: .mutableContent)
        subtitle = try values.decodeIfPresent(String.self, forKey: .subtitle)
    }

    public func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        if hasSound {
            if let sound { try values.encode(sound, forKey: .sound) }
            else { try values.encodeNil(forKey: .sound) }
        }
        try values.encodeIfPresent(badge, forKey: .badge)
        try values.encodeIfPresent(interruptionLevel, forKey: .interruptionLevel)
        try values.encodeIfPresent(mutableContent, forKey: .mutableContent)
        try values.encodeIfPresent(subtitle, forKey: .subtitle)
    }

    public let sound: String?
    public let badge: Int?
    public let interruptionLevel: String?
    public let mutableContent: Bool?
    public let subtitle: String?
}

public struct AndroidNotificationOptions: Codable, Equatable, Sendable {
    public let channelId: String?
    public let groupKey: String?
    public let groupSummary: String?
}
