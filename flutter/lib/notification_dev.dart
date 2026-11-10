import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

import 'src/models.dart';

export 'src/models.dart' hide immutableJson;

/// A thin bridge to the process-wide native notification.dev client.
class NotificationDev {
  NotificationDev._();

  static const _methods = MethodChannel('dev.notification/sdk');
  static final _instance = NotificationDev._();

  /// The iOS Live Activity facade for dictionary-based SwiftUI widgets.
  final liveActivities = LiveActivities._();

  /// Completes after local initialization, without waiting for server registration.
  static Future<NotificationDev> initialize(SdkConfig config) async {
    await _invoke<void>('initialize', config.toJson());

    return _instance;
  }

  late final Stream<SdkState> states = _replay(
    _events('states').map((value) => SdkState.fromJson(value as JsonMap)),
  );
  late final Stream<List<NotificationOpen>> pendingOpens = _replay(
    _events('pendingOpens').map(
      (value) => List<NotificationOpen>.unmodifiable(
        (value as List).map(
          (open) => NotificationOpen.fromJson(open as JsonMap),
        ),
      ),
    ),
  );
  late final Stream<NotificationEvent> notifications = _events(
    'notifications',
  ).map((value) => NotificationEvent.fromJson(value as JsonMap));
  late final Stream<SdkException> diagnostics = _events(
    'diagnostics',
  ).map((value) => SdkException.fromJson(value as JsonMap));

  Future<SdkState> getState() async =>
      SdkState.fromJson(await _json('getState') as JsonMap);

  Future<JsonMap> getTags() async =>
      immutableJson(await _json('getTags') as JsonMap);

  Future<EmailSubscription?> getEmail() async {
    final value = await _json('getEmail');

    return value == null ? null : EmailSubscription.fromJson(value as JsonMap);
  }

  Future<void> login(String externalId) =>
      _invoke('login', {'externalId': externalId});

  /// Hides foreground alerts whose custom data matches every supplied field.
  ///
  /// Pass `null` to clear the filter. Login, logout, and process restart also
  /// clear it. Receipts remain enabled; iOS background alerts are unaffected.
  /// Throws [SdkException] for empty filters or non-finite JSON values.
  Future<void> suppressForegroundNotifications({JsonMap? matchingData}) async {
    if (matchingData != null && matchingData.isEmpty) {
      throw const SdkException(
        'INVALID_ARGUMENT',
        'Provide a nonempty data filter or null',
      );
    }
    String? encoded;
    try {
      if (matchingData != null) encoded = jsonEncode(matchingData);
    } on JsonUnsupportedObjectError {
      throw const SdkException(
        'INVALID_ARGUMENT',
        'Matching data must contain finite JSON values without cycles',
      );
    }

    await _invoke<void>('suppressForegroundNotifications', {
      'matchingData': encoded,
    });
  }

  Future<void> logout() => _invoke('logout');

  Future<void> setEmail(String address, {required bool optedIn}) =>
      _invoke('setEmail', {'address': address, 'optedIn': optedIn});

  Future<void> removeEmail() => _invoke('removeEmail');

  Future<void> setEmailOptedIn(bool enabled) =>
      _invoke('setEmailOptedIn', {'enabled': enabled});

  Future<void> setTags(Map<String, Object> values) {
    for (final value in values.values) {
      if (value is! String && value is! bool && value is! num ||
          value is num && !value.isFinite) {
        throw const SdkException(
          'INVALID_ARGUMENT',
          'Tags must contain strings, booleans, or finite numbers',
        );
      }
    }

    return _invoke('setTags', {'values': jsonEncode(values)});
  }

  Future<void> removeTags(Set<String> keys) =>
      _invoke('removeTags', {'keys': keys.toList()});

  Future<void> setPushOptedIn(bool enabled) =>
      _invoke('setPushOptedIn', {'enabled': enabled});

  Future<String> track(
    String name, {
    JsonMap properties = const {},
    String? eventId,
  }) async {
    String encoded;
    try {
      encoded = jsonEncode(properties);
    } on JsonUnsupportedObjectError {
      throw const SdkException(
        'INVALID_ARGUMENT',
        'Event properties must contain finite JSON values',
      );
    }

    return (await _invoke<String>('track', {
      'name': name,
      'properties': encoded,
      'eventId': eventId,
    }))!;
  }

  Future<SdkState> refresh() async =>
      SdkState.fromJson(await _json('refresh') as JsonMap);

  Future<void> acknowledgeOpen(String interactionId) =>
      _invoke('acknowledgeOpen', {'interactionId': interactionId});

  Future<PushPermissionStatus> getPushPermissionStatus() async =>
      PushPermissionStatus.fromJson(
        await _json('getPushPermissionStatus') as JsonMap,
      );

  Future<PushPermissionResult> requestPushPermission({
    bool settingsFallback = false,
  }) async => PushPermissionResult.fromJson(
    await _json('requestPushPermission', {'settingsFallback': settingsFallback})
        as JsonMap,
  );

  /// Returns whether settings opened, not whether permission was granted.
  Future<bool> openPushSettings() async =>
      (await _invoke<bool>('openPushSettings'))!;

  static Future<T?> _invoke<T>(String method, [JsonMap? arguments]) async {
    try {
      return await _methods.invokeMethod<T>(method, arguments);
    } on PlatformException catch (error) {
      throw SdkException(error.code, error.message ?? 'Native SDK call failed');
    } on MissingPluginException {
      throw const SdkException(
        'UNSUPPORTED_PLATFORM',
        'notification.dev requires Android or iOS with its plugin registered',
      );
    }
  }

  static Future<Object?> _json(String method, [JsonMap? arguments]) async =>
      jsonDecode((await _invoke<String>(method, arguments))!);

  static Stream<Object?> _events(String name) =>
      EventChannel(
        'dev.notification/sdk/$name',
      ).receiveBroadcastStream().transform(
        StreamTransformer<dynamic, Object?>.fromHandlers(
          handleData: (value, sink) => sink.add(jsonDecode(value as String)),
          handleError: (error, stack, sink) {
            sink.addError(
              error is PlatformException
                  ? SdkException(
                      error.code,
                      error.message ?? 'Native stream failed',
                    )
                  : error,
              stack,
            );
          },
        ),
      );
}

/// Native state streams emit a snapshot on their first listener. While subscribed,
/// retain only the latest snapshot for additional Dart listeners.
Stream<T> _replay<T>(Stream<T> source) {
  T? latest;
  var listeners = 0;
  var hasValue = false;

  return Stream<T>.multi((controller) {
    if (listeners > 0 && hasValue) {
      controller.add(latest as T);
    }
    listeners++;
    final subscription = source.listen(
      (value) {
        latest = value;
        hasValue = true;
        controller.add(value);
      },
      onError: controller.addError,
      onDone: controller.close,
    );
    controller.onCancel = () async {
      listeners--;
      await subscription.cancel();
    };
  }, isBroadcast: true);
}

/// Manages dictionary-based iOS Live Activities through the native SDK.
///
/// The host must embed a SwiftUI widget extension. Android supports capability
/// detection and empty activity lists; mutations throw `UNSUPPORTED_PLATFORM`.
class LiveActivities {
  LiveActivities._();

  /// The activity type used when requesting grants or sending from the backend.
  static const activityType = 'DefaultLiveActivityAttributes';

  /// Replays current-association activities and emits content or lifecycle changes.
  late final Stream<List<LiveActivityState>> states = _replay(
    NotificationDev._events('liveActivities').map(
      (value) => List<LiveActivityState>.unmodifiable(
        (value as List).map(
          (item) => LiveActivityState.fromJson(item as JsonMap),
        ),
      ),
    ),
  );

  /// Reports OS support and the user's Live Activity permission independently.
  Future<LiveActivityCapabilities> getCapabilities() async =>
      LiveActivityCapabilities.fromJson(
        await _call('getCapabilities') as JsonMap,
      );

  /// Persists public configuration so observers resume on native background launch.
  ///
  /// Valid changes to an existing configuration are saved for the next native
  /// launch and report `LIVE_ACTIVITY_RESTART_REQUIRED` until the app restarts.
  Future<void> configure(LiveActivityConfiguration configuration) async {
    await _call('configure', configuration.toJson());
  }

  /// Starts locally using a fresh grant from the app's authenticated backend.
  ///
  /// The grant must match [activityId], this installation, its current association,
  /// the configured provider, and [activityType]. Never pass a server API key.
  Future<LiveActivityState> start({
    required String activityId,
    required JsonMap attributes,
    required JsonMap content,
    required String registrationToken,
  }) async => LiveActivityState.fromJson(
    await _call('start', {
          'activityId': activityId,
          'attributes': attributes,
          'content': content,
          'registrationToken': registrationToken,
        })
        as JsonMap,
  );

  /// Lists active or stale activities belonging to the current association.
  Future<List<LiveActivityState>> getActivities() async =>
      List<LiveActivityState>.unmodifiable(
        (await _call('getActivities') as List).map(
          (item) => LiveActivityState.fromJson(item as JsonMap),
        ),
      );

  /// Ends the local activity and queues unregistration from backend updates.
  ///
  /// Omitting [dismissalDate] removes the UI immediately. Remote updates and end
  /// events remain server operations. Success does not confirm backend upload.
  Future<void> end(
    String activityId, {
    JsonMap content = const {},
    DateTime? dismissalDate,
  }) async {
    await _call('end', {
      'activityId': activityId,
      'content': content,
      if (dismissalDate != null)
        'dismissalDate': dismissalDate.millisecondsSinceEpoch,
    });
  }

  Future<Object?> _call(String method, [JsonMap arguments = const {}]) async {
    final String encoded;
    try {
      encoded = jsonEncode(arguments);
    } on JsonUnsupportedObjectError {
      throw const SdkException(
        'INVALID_ARGUMENT',
        'Live Activity arguments must contain finite JSON values without cycles',
      );
    }
    return NotificationDev._json('liveActivities.$method', {
      'argumentsJson': encoded,
    });
  }
}
