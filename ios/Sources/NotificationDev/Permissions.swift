import UIKit
import UserNotifications

public struct IOSPushPermissionStatus: Equatable, Sendable {
    public let authorizationStatus: UNAuthorizationStatus
    public let alertSetting: UNNotificationSetting
    public let soundSetting: UNNotificationSetting
    public let badgeSetting: UNNotificationSetting
    public var areNotificationsEnabled: Bool { Self.isAllowed(authorizationStatus) }

    static func isAllowed(_ status: UNAuthorizationStatus) -> Bool {
        switch status {
        case .authorized, .provisional, .ephemeral: return true
        default: return false
        }
    }
}

public enum PushPermissionResult: Equatable, Sendable {
    case granted, denied, settingsRequired, settingsOpened, settingsUnavailable
}

extension NotificationDev {
    public func getPushPermissionStatus() async throws -> IOSPushPermissionStatus {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let status = IOSPushPermissionStatus(
            authorizationStatus: settings.authorizationStatus,
            alertSetting: settings.alertSetting, soundSetting: settings.soundSetting,
            badgeSetting: settings.badgeSetting
        )
        try await perform("device", ["enabled": .bool(status.areNotificationsEnabled)])

        return status
    }

    public func requestPushPermission(settingsFallback: Bool = false) async throws
        -> PushPermissionResult
    {
        let status = try await getPushPermissionStatus()
        if status.authorizationStatus == .denied {
            guard settingsFallback else { return .settingsRequired }

            return await openPushSettings() ? .settingsOpened : .settingsUnavailable
        }
        if status.areNotificationsEnabled { return .granted }
        _ = try await UNUserNotificationCenter.current().requestAuthorization(options: [
            .alert, .sound, .badge,
        ])
        let updated = try await getPushPermissionStatus()

        return updated.areNotificationsEnabled ? .granted : .denied
    }

    @discardableResult
    public func openPushSettings() async -> Bool {
        let address: String
        if #available(iOS 16.0, *) {
            address = UIApplication.openNotificationSettingsURLString
        } else {
            address = UIApplication.openSettingsURLString
        }
        guard let url = URL(string: address) else { return false }

        return await UIApplication.shared.open(url)
    }
}
