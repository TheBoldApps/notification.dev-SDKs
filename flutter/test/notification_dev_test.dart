import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:notification_dev/notification_dev.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dev.notification/sdk');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  const config = SdkConfig(
    projectId: 'project',
    baseUrl: 'https://api.example/',
  );
  final fixture = <String, Object?>{
    'availability': 'AVAILABLE',
    'user': {
      'email': {
        'address': 'user@example.com',
        'optedIn': true,
        'suppressed': false,
      },
      'tags': {'plan': 'premium', 'active': true, 'count': 42},
    },
    'installationId': 'installation',
    'associationId': 'association',
    'lastSyncedAt': 1750000000000,
    'hasPendingChanges': true,
    'pushRegistration': {'status': 'registered', 'errorCode': null},
    'permission': {'areNotificationsEnabled': true},
  };
  late NotificationDev sdk;

  setUp(() async {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getState':
        case 'refresh':
          return jsonEncode(fixture);
        case 'getTags':
          return jsonEncode((fixture['user'] as Map)['tags']);
        case 'getEmail':
          return jsonEncode((fixture['user'] as Map)['email']);
        case 'track':
          return (call.arguments as Map)['eventId'] ?? 'generated-event-id';
        case 'requestPushPermission':
          return jsonEncode({'outcome': 'settingsOpened'});
        case 'openPushSettings':
          return true;
      }
      return null;
    });
    sdk = await NotificationDev.initialize(config);
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'initialization delegates defaults and returns a stable client',
    () async {
      expect(await NotificationDev.initialize(config), same(sdk));
      expect(calls.first.arguments, config.toJson());
      expect((calls.first.arguments as Map)['automaticIntegration'], true);
    },
  );

  test('hosted API default and overrides cross the native bridge', () async {
    const hostedConfig = SdkConfig(projectId: 'project');
    await NotificationDev.initialize(hostedConfig);
    expect(
      (calls.last.arguments as Map)['baseUrl'],
      'https://app.notification.dev/',
    );

    await NotificationDev.initialize(config);
    expect((calls.last.arguments as Map)['baseUrl'], 'https://api.example/');
  });

  test('HTTP opt-in crosses the bridge using the new key', () async {
    expect(config.toJson()['allowHttp'], false);
    const local = SdkConfig(
      projectId: 'project',
      baseUrl: 'http://192.168.2.20:5173/',
      allowHttp: true,
    );
    await NotificationDev.initialize(local);
    expect((calls.last.arguments as Map)['allowHttp'], true);
    expect((calls.last.arguments as Map)['baseUrl'], local.baseUrl);
  });

  test('local getters decode native state without refreshing', () async {
    final state = await sdk.getState();
    expect(state.availability, Availability.available);
    expect(state.lastSyncedAt, 1750000000000);
    expect(state.hasPendingChanges, true);
    expect(state.permission.areNotificationsEnabled, true);
    expect(state.user.email?.address, 'user@example.com');
    expect(state.user.tags['active'], true);
    expect(state.user.tags['count'], 42);
    expect(state.user.tags['count'], isA<int>());
    expect(() => state.user.tags['plan'] = 'changed', throwsUnsupportedError);
    expect(await sdk.getTags(), state.user.tags);
    expect((await sdk.getEmail())?.optedIn, true);
    expect(calls.where((call) => call.method == 'refresh'), isEmpty);
  });

  test('all user operations forward values to the native SDK', () async {
    await sdk.login('customer');
    await sdk.setEmail('user@example.com', optedIn: false);
    await sdk.setEmailOptedIn(true);
    await sdk.removeEmail();
    await sdk.setPushOptedIn(false);
    await sdk.setTags({'text': 'value', 'boolean': true, 'number': 12.5});
    await sdk.removeTags({'text'});
    await sdk.acknowledgeOpen('interaction');
    await sdk.logout();
    expect(calls.map((call) => call.method), [
      'initialize',
      'login',
      'setEmail',
      'setEmailOptedIn',
      'removeEmail',
      'setPushOptedIn',
      'setTags',
      'removeTags',
      'acknowledgeOpen',
      'logout',
    ]);
    expect(calls[2].arguments, {
      'address': 'user@example.com',
      'optedIn': false,
    });
    expect(jsonDecode((calls[6].arguments as Map)['values'] as String), {
      'text': 'value',
      'boolean': true,
      'number': 12.5,
    });
  });

  test(
    'events preserve recursive JSON and return the native event ID',
    () async {
      final properties = <String, Object?>{
        'nested': {
          'items': [null, false, 3, 'hello'],
        },
      };
      expect(
        await sdk.track('checkout', properties: properties, eventId: 'dedupe'),
        'dedupe',
      );
      expect(
        jsonDecode((calls.last.arguments as Map)['properties'] as String),
        properties,
      );
      expect(await sdk.track('generated'), 'generated-event-id');
    },
  );

  test(
    'non-JSON and invalid tag values fail before crossing the bridge',
    () async {
      expect(
        () => sdk.setTags({'invalid': double.infinity}),
        throwsA(isA<SdkException>()),
      );
      expect(
        () => sdk.setTags({'invalid': <String>[]}),
        throwsA(isA<SdkException>()),
      );
      await expectLater(
        sdk.track('invalid', properties: {'x': Object()}),
        throwsA(isA<SdkException>()),
      );
      expect(calls.length, 1);
    },
  );

  test('permission outcomes do not treat settings as granted', () async {
    final result = await sdk.requestPushPermission(settingsFallback: true);
    expect(result.outcome, PushPermissionOutcome.settingsOpened);
    expect((calls.last.arguments as Map)['settingsFallback'], true);
    expect(await sdk.openPushSettings(), true);
  });

  test('native errors retain sanitized codes and messages', () async {
    messenger.setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(
        code: 'CONFIGURATION_CHANGED',
        message: 'Configuration changed',
      );
    });
    await expectLater(
      sdk.refresh(),
      throwsA(
        isA<SdkException>().having(
          (error) => error.code,
          'code',
          'CONFIGURATION_CHANGED',
        ),
      ),
    );
    await expectLater(
      NotificationDev.initialize(config),
      throwsA(isA<SdkException>()),
    );
  });

  test(
    'missing plugin produces an actionable unsupported-platform error',
    () async {
      messenger.setMockMethodCallHandler(channel, null);
      await expectLater(
        sdk.getState(),
        throwsA(
          isA<SdkException>().having(
            (error) => error.code,
            'code',
            'UNSUPPORTED_PLATFORM',
          ),
        ),
      );
    },
  );

  test(
    'state replays for additional listeners and reattaches after cancellation',
    () async {
      const name = 'dev.notification/sdk/states';
      const eventChannel = MethodChannel(name);
      var nativeListens = 0;
      var nativeCancels = 0;
      messenger.setMockMethodCallHandler(eventChannel, (call) async {
        if (call.method == 'listen') nativeListens++;
        if (call.method == 'cancel') nativeCancels++;
        return null;
      });
      Future<void> emit(Object value) async {
        final done = Completer<void>();
        messenger.handlePlatformMessage(
          name,
          const StandardMethodCodec().encodeSuccessEnvelope(jsonEncode(value)),
          (_) => done.complete(),
        );
        await done.future;
        await Future<void>.delayed(Duration.zero);
      }

      final first = <SdkState>[];
      final second = <SdkState>[];
      final sub1 = sdk.states.listen(first.add);
      await Future<void>.delayed(Duration.zero);
      await emit(fixture);
      final sub2 = sdk.states.listen(second.add);
      await Future<void>.delayed(Duration.zero);
      expect(first.length, 1);
      expect(second.single.availability, Availability.available);
      expect(nativeListens, 1);
      await emit({...fixture, 'syncing': true});
      expect(first.last.syncing, true);
      expect(second.last.syncing, true);
      await sub1.cancel();
      await sub2.cancel();
      await Future<void>.delayed(Duration.zero);
      expect(nativeCancels, 1);

      final reattached = <SdkState>[];
      final sub3 = sdk.states.listen(reattached.add);
      await Future<void>.delayed(Duration.zero);
      expect(
        reattached,
        isEmpty,
      ); // No stale snapshot when no upstream listener was active.
      await emit({...fixture, 'syncing': false});
      expect(reattached.single.syncing, false);
      expect(nativeListens, 2);
      await sub3.cancel();
      messenger.setMockMethodCallHandler(eventChannel, null);
    },
  );

  test(
    'notifications do not replay and opens remain until native acknowledgement',
    () async {
      const notificationName = 'dev.notification/sdk/notifications';
      const opensName = 'dev.notification/sdk/pendingOpens';
      for (final name in [notificationName, opensName]) {
        messenger.setMockMethodCallHandler(
          MethodChannel(name),
          (_) async => null,
        );
      }
      final payload = {
        'deliveryId': 'delivery',
        'associationId': 'association',
        'expiresAt': 1750000000000,
        'title': 'Title',
        'body': 'Body',
        'data': {
          'nested': [1, true, null],
        },
        'deepLink': '/inbox',
      };
      final notificationEvents = <NotificationEvent>[];
      final openEvents = <List<NotificationOpen>>[];
      final sub1 = sdk.notifications.listen(notificationEvents.add);
      final sub2 = sdk.pendingOpens.listen(openEvents.add);
      await Future<void>.delayed(Duration.zero);
      messenger.handlePlatformMessage(
        notificationName,
        const StandardMethodCodec().encodeSuccessEnvelope(
          jsonEncode({'notification': payload}),
        ),
        (_) {},
      );
      messenger.handlePlatformMessage(
        opensName,
        const StandardMethodCodec().encodeSuccessEnvelope(
          jsonEncode([
            {
              'interactionId': 'open',
              'notification': payload,
              'openedAt': 1750000000000,
            },
          ]),
        ),
        (_) {},
      );
      await Future<void>.delayed(Duration.zero);
      final lateNotifications = <NotificationEvent>[];
      final lateOpens = <List<NotificationOpen>>[];
      final sub3 = sdk.notifications.listen(lateNotifications.add);
      final sub4 = sdk.pendingOpens.listen(lateOpens.add);
      await Future<void>.delayed(Duration.zero);
      expect(lateNotifications, isEmpty);
      expect(lateOpens.single.single.interactionId, 'open');
      expect(notificationEvents.single.notification.data['nested'], [
        1,
        true,
        null,
      ]);
      expect(
        () => (notificationEvents.single.notification.data['nested'] as List)
            .add(2),
        throwsUnsupportedError,
      );
      await sdk.acknowledgeOpen('open');
      expect(
        openEvents.last.single.interactionId,
        'open',
      ); // Dart does not remove durable native state.
      for (final subscription in [sub1, sub2, sub3, sub4]) {
        await subscription.cancel();
      }
      for (final name in [notificationName, opensName]) {
        messenger.setMockMethodCallHandler(MethodChannel(name), null);
      }
    },
  );
}
