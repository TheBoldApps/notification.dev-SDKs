import 'dart:collection';

typedef JsonMap = Map<String, Object?>;

/// Only public configuration is persisted for native background startup.
class SdkConfig {
  const SdkConfig({
    required this.projectId,
    this.baseUrl = 'https://app.notification.dev/',
    this.androidSmallIcon = 'ic_notification',
    this.androidChannelId = 'notification_dev',
    this.androidChannelName = 'Notifications',
    this.displayInForeground = true,
    this.allowHttp = false,
    this.loggingEnabled = false,
    this.automaticIntegration = true,
    this.iosApnsEnvironment,
  });

  final String projectId;
  final String baseUrl;
  final String androidSmallIcon;
  final String androidChannelId;
  final String androidChannelName;
  final bool displayInForeground;
  final bool allowHttp;
  final bool loggingEnabled;
  final bool automaticIntegration;
  final String? iosApnsEnvironment;

  JsonMap toJson() => {
    'projectId': projectId,
    'baseUrl': baseUrl,
    'androidSmallIcon': androidSmallIcon,
    'androidChannelId': androidChannelId,
    'androidChannelName': androidChannelName,
    'displayInForeground': displayInForeground,
    'allowHttp': allowHttp,
    'loggingEnabled': loggingEnabled,
    'automaticIntegration': automaticIntegration,
    if (iosApnsEnvironment != null) 'iosApnsEnvironment': iosApnsEnvironment,
  };
}

class SdkException implements Exception {
  const SdkException(this.code, this.message);

  factory SdkException.fromJson(JsonMap json) =>
      SdkException(json['code'] as String, json['message'] as String);

  final String code;
  final String message;

  @override
  String toString() => 'SdkException($code): $message';
}

class EmailSubscription {
  EmailSubscription.fromJson(JsonMap json)
    : address = json['address'] as String,
      optedIn = json['optedIn'] as bool,
      suppressed = json['suppressed'] as bool? ?? false;

  final String address;
  final bool optedIn;
  final bool suppressed;
}

class UserProperties {
  UserProperties.fromJson(JsonMap json)
    : email = json['email'] == null
          ? null
          : EmailSubscription.fromJson(json['email'] as JsonMap),
      tags = immutableJson(json['tags'] as JsonMap? ?? {});

  final EmailSubscription? email;
  final JsonMap tags;
}

enum Availability { loading, available, unavailable }

class PushRegistration {
  PushRegistration.fromJson(JsonMap json)
    : status = json['status'] as String? ?? 'unknown',
      errorCode = json['errorCode'] as String?,
      updatedAt = json['updatedAt'] as String?;

  final String status;
  final String? errorCode;
  final String? updatedAt;
}

/// Native details use the platform's named status values.
class PushPermissionStatus {
  PushPermissionStatus.fromJson(JsonMap json)
    : areNotificationsEnabled =
          json['areNotificationsEnabled'] as bool? ?? false,
      runtimePermission = json['runtimePermission'] as String?,
      channelStatus = json['channelStatus'] as String?,
      authorizationStatus = json['authorizationStatus'] as String?,
      alertSetting = json['alertSetting'] as String?,
      soundSetting = json['soundSetting'] as String?,
      badgeSetting = json['badgeSetting'] as String?;

  final bool areNotificationsEnabled;
  final String? runtimePermission;
  final String? channelStatus;
  final String? authorizationStatus;
  final String? alertSetting;
  final String? soundSetting;
  final String? badgeSetting;
}

enum PushPermissionOutcome {
  granted,
  denied,
  settingsRequired,
  settingsOpened,
  failed,
}

class PushPermissionResult {
  PushPermissionResult.fromJson(JsonMap json)
    : outcome = PushPermissionOutcome.values.byName(json['outcome'] as String),
      errorCode = json['errorCode'] as String?;

  final PushPermissionOutcome outcome;
  final String? errorCode;
}

class SdkState {
  SdkState.fromJson(JsonMap json)
    : availability = Availability.values.byName(
        (json['availability'] as String? ?? 'LOADING').toLowerCase(),
      ),
      user = UserProperties.fromJson(json['user'] as JsonMap? ?? {}),
      installationId = json['installationId'] as String?,
      associationId = json['associationId'] as String?,
      externalId = json['externalId'] as String?,
      lastSyncedAt = json['lastSyncedAt'] as int?,
      syncing = json['syncing'] as bool? ?? false,
      hasPendingChanges = json['hasPendingChanges'] as bool? ?? false,
      pushOptedIn = json['pushOptedIn'] as bool? ?? true,
      pushRegistration = PushRegistration.fromJson(
        json['pushRegistration'] as JsonMap? ?? {},
      ),
      permission = PushPermissionStatus.fromJson(
        json['permission'] as JsonMap? ?? {},
      ),
      lastError = json['lastError'] == null
          ? null
          : SdkException.fromJson(json['lastError'] as JsonMap),
      identityChangePending = json['identityChangePending'] as bool? ?? false,
      pushTokenRegistered = json['pushTokenRegistered'] as bool? ?? false;

  final Availability availability;
  final UserProperties user;
  final String? installationId;
  final String? associationId;
  final String? externalId;

  /// Milliseconds since Unix epoch, matching the native SDK.
  final int? lastSyncedAt;
  final bool syncing;
  final bool hasPendingChanges;
  final bool pushOptedIn;
  final PushRegistration pushRegistration;
  final PushPermissionStatus permission;
  final SdkException? lastError;
  final bool identityChangePending;
  final bool pushTokenRegistered;
}

class NotificationPayload {
  NotificationPayload.fromJson(JsonMap json)
    : version = json['version'] as int? ?? 1,
      deliveryId = json['deliveryId'] as String,
      associationId = json['associationId'] as String,
      expiresAt = json['expiresAt'] as int,
      title = json['title'] as String,
      body = json['body'] as String,
      data = immutableJson(json['data'] as JsonMap? ?? {}),
      deepLink = json['deepLink'] as String?,
      collapseId = json['collapseId'] as String?,
      threadId = json['threadId'] as String?,
      ttlSeconds = json['ttlSeconds'] as int?,
      priority = json['priority'] as String?,
      ios = json['ios'] == null
          ? null
          : IosNotificationOptions.fromJson(json['ios'] as JsonMap),
      android = json['android'] == null
          ? null
          : AndroidNotificationOptions.fromJson(json['android'] as JsonMap),
      imageUrl = json['imageUrl'] as String?,
      contentAvailable = json['contentAvailable'] as bool?;

  final int version;
  final String deliveryId;
  final String associationId;
  final int expiresAt;
  final String title;
  final String body;
  final JsonMap data;
  final String? deepLink;
  final String? collapseId;
  final String? threadId;
  final int? ttlSeconds;
  final String? priority;
  final IosNotificationOptions? ios;
  final AndroidNotificationOptions? android;
  final String? imageUrl;
  final bool? contentAvailable;
}

class NotificationEvent {
  NotificationEvent.fromJson(JsonMap json)
    : notification = NotificationPayload.fromJson(
        json['notification'] as JsonMap,
      );

  final NotificationPayload notification;
}

class NotificationOpen {
  NotificationOpen.fromJson(JsonMap json)
    : interactionId = json['interactionId'] as String,
      notification = NotificationPayload.fromJson(
        json['notification'] as JsonMap,
      ),
      openedAt = json['openedAt'] as int;

  final String interactionId;
  final NotificationPayload notification;
  final int openedAt;
}

JsonMap immutableJson(JsonMap value) => UnmodifiableMapView(
  value.map((key, value) => MapEntry(key, _freeze(value))),
);

Object? _freeze(Object? value) {
  if (value is Map<String, Object?>) {
    return immutableJson(value);
  }
  if (value is List) {
    return List<Object?>.unmodifiable(value.map(_freeze));
  }

  return value;
}

class IosNotificationOptions {
  IosNotificationOptions.fromJson(JsonMap json)
    : sound = json['sound'] as String?,
      hasSound = json.containsKey('sound'),
      badge = json['badge'] as int?,
      interruptionLevel = json['interruptionLevel'] as String?,
      mutableContent = json['mutableContent'] as bool?,
      subtitle = json['subtitle'] as String?;

  final String? sound;
  final bool hasSound;
  final int? badge;
  final String? interruptionLevel;
  final bool? mutableContent;
  final String? subtitle;
}

class AndroidNotificationOptions {
  AndroidNotificationOptions.fromJson(JsonMap json)
    : channelId = json['channelId'] as String?,
      groupKey = json['groupKey'] as String?,
      groupSummary = json['groupSummary'] as String?;

  final String? channelId;
  final String? groupKey;
  final String? groupSummary;
}

/// Public startup configuration for the default iOS activity type.
class LiveActivityConfiguration {
  const LiveActivityConfiguration({
    required this.providerConfigurationId,
    this.enablePushToStart = true,
    this.enablePushToUpdate = true,
  });

  final String providerConfigurationId;
  final bool enablePushToStart;
  final bool enablePushToUpdate;

  JsonMap toJson() => {
    'providerConfigurationId': providerConfigurationId,
    'enablePushToStart': enablePushToStart,
    'enablePushToUpdate': enablePushToUpdate,
  };
}

/// OS support and permission for ActivityKit, independent of ordinary push opt-in.
class LiveActivityCapabilities {
  LiveActivityCapabilities.fromJson(JsonMap json)
    : supported = json['supported'] as bool,
      activitiesEnabled = json['activitiesEnabled'] as bool,
      pushToStartSupported = json['pushToStartSupported'] as bool;

  final bool supported;
  final bool activitiesEnabled;
  final bool pushToStartSupported;
}

/// A credential-free snapshot of an activity belonging to the current association.
class LiveActivityState {
  LiveActivityState.fromJson(JsonMap json)
    : activityId = json['activityId'] as String,
      nativeActivityId = json['nativeActivityId'] as String,
      state = json['state'] as String,
      attributes = immutableJson(json['attributes'] as JsonMap),
      content = immutableJson(json['content'] as JsonMap);

  final String activityId;
  final String nativeActivityId;
  final String state;
  final JsonMap attributes;
  final JsonMap content;
}
