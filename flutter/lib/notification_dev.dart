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
