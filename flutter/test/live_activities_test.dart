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
  final snapshot = <String, Object?>{
    'activityId': 'order-1',
    'nativeActivityId': 'native-1',
    'state': 'active',
    'attributes': {'name': 'Delivery'},
    'content': {
      'items': [null, false, 0],
    },
  };
  late LiveActivities activities;

  setUp(() async {
    calls.clear();
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'initialize':
          return null;
        case 'liveActivities.getCapabilities':
          return jsonEncode({
            'supported': false,
            'activitiesEnabled': false,
            'pushToStartSupported': false,
          });
        case 'liveActivities.getActivities':
          return jsonEncode([snapshot]);
        case 'liveActivities.start':
          return jsonEncode(snapshot);
        default:
          return 'null';
      }
    });
    activities = (await NotificationDev.initialize(
      const SdkConfig(projectId: 'project'),
    )).liveActivities;
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'capabilities can report Android without pretending ActivityKit is available',
    () async {
      final result = await activities.getCapabilities();
      expect(result.supported, isFalse);
      expect(result.activitiesEnabled, isFalse);
      expect(result.pushToStartSupported, isFalse);
      expect(LiveActivities.activityType, 'DefaultLiveActivityAttributes');
    },
  );

  test(
    'configure, local start and end preserve grants and recursive JSON',
    () async {
      await activities.configure(
        const LiveActivityConfiguration(providerConfigurationId: 'provider'),
      );
      final started = await activities.start(
        activityId: 'order-1',
        attributes: {'name': 'Delivery'},
        content: {
          'items': [null, false, 0],
        },
        registrationToken: 'g' * 43,
      );
      final listed = await activities.getActivities();
      expect(started.nativeActivityId, 'native-1');
      expect(listed.single.content['items'], [null, false, 0]);
      expect(
        () => (listed.single.content['items'] as List).add(1),
        throwsUnsupportedError,
      );
      await activities.end(
        'order-1',
        content: {'status': 'Delivered'},
        dismissalDate: DateTime.fromMillisecondsSinceEpoch(
          1900000000000,
          isUtc: true,
        ),
      );
      expect(calls.skip(1).map((call) => call.method), [
        'liveActivities.configure',
        'liveActivities.start',
        'liveActivities.getActivities',
        'liveActivities.end',
      ]);
      final arguments = calls
          .skip(1)
          .map(
            (call) =>
                jsonDecode((call.arguments as Map)['argumentsJson'] as String),
          )
          .toList();
      expect(arguments[0], {
        'providerConfigurationId': 'provider',
        'enablePushToStart': true,
        'enablePushToUpdate': true,
      });
      expect(arguments[1], {
        'activityId': 'order-1',
        'attributes': {'name': 'Delivery'},
        'content': {
          'items': [null, false, 0],
        },
        'registrationToken': 'g' * 43,
      });
      expect(arguments[3], {
        'activityId': 'order-1',
        'content': {'status': 'Delivered'},
        'dismissalDate': 1900000000000,
      });
      await activities.end('order-1');
      expect(
        jsonDecode((calls.last.arguments as Map)['argumentsJson'] as String),
        {'activityId': 'order-1', 'content': {}},
      );
    },
  );

  test('invalid JSON never crosses the Live Activity bridge', () async {
    await expectLater(
      activities.start(
        activityId: 'id',
        attributes: {},
        content: {'x': double.nan},
        registrationToken: 'g' * 43,
      ),
      throwsA(
        isA<SdkException>().having((e) => e.code, 'code', 'INVALID_ARGUMENT'),
      ),
    );
    expect(calls, hasLength(1));
  });

  test(
    'native platform and expired-grant errors retain their SDK codes',
    () async {
      for (final code in [
        'UNSUPPORTED_PLATFORM',
        'INVALID_LIVE_ACTIVITY_GRANT',
      ]) {
        messenger.setMockMethodCallHandler(channel, (_) async {
          throw PlatformException(code: code, message: 'Unavailable');
        });
        await expectLater(
          activities.start(
            activityId: 'id',
            attributes: {},
            content: {},
            registrationToken: 'g' * 43,
          ),
          throwsA(isA<SdkException>().having((e) => e.code, 'code', code)),
        );
      }
    },
  );

  test('activity states replay and clear after identity changes', () async {
    const name = 'dev.notification/sdk/liveActivities';
    messenger.setMockMethodCallHandler(
      const MethodChannel(name),
      (_) async => null,
    );
    final first = <List<LiveActivityState>>[],
        second = <List<LiveActivityState>>[];
    final a = activities.states.listen(first.add);
    await Future<void>.delayed(Duration.zero);
    void emit(Object value) => messenger.handlePlatformMessage(
      name,
      const StandardMethodCodec().encodeSuccessEnvelope(jsonEncode(value)),
      (_) {},
    );
    emit([snapshot]);
    await Future<void>.delayed(Duration.zero);
    final b = activities.states.listen(second.add);
    await Future<void>.delayed(Duration.zero);
    expect(second.single.single.activityId, 'order-1');
    expect(
      () => second.single.add(second.single.single),
      throwsUnsupportedError,
    );
    emit([]);
    await Future<void>.delayed(Duration.zero);
    expect(first.last, isEmpty);
    expect(second.last, isEmpty);
    await a.cancel();
    await b.cancel();
    messenger.setMockMethodCallHandler(const MethodChannel(name), null);
  });
}
