import Foundation
@_exported import NotificationDevActivityKit

public struct SdkConfig: Equatable, Sendable {
    public let projectId: String
    public let baseURL: URL
    public var allowHTTP: Bool
    public var loggingEnabled: Bool
    public var displayInForeground: Bool

    public init(
        projectId: String, baseURL: URL = URL(string: "https://app.notification.dev/")!,
        allowHTTP: Bool = false,
        loggingEnabled: Bool = false, displayInForeground: Bool = true
    ) {
        self.projectId = projectId
        self.baseURL = baseURL
        self.allowHTTP = allowHTTP
        self.loggingEnabled = loggingEnabled
        self.displayInForeground = displayInForeground
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
