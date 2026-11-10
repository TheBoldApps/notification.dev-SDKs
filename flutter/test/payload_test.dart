import 'package:flutter_test/flutter_test.dart';
import 'package:notification_dev/notification_dev.dart';

void main() {
  final legacy = <String, Object?>{
    'deliveryId': 'delivery',
    'associationId': 'association',
    'expiresAt': 1900000000000,
    'title': 'Title',
    'body': 'Body',
    'data': {
      'nested': [1, true, null],
    },
  };

  test('legacy payloads retain defaults', () {
    final payload = NotificationPayload.fromJson(legacy);
    expect(payload.version, 1);
    expect(payload.ios, isNull);
    expect(payload.collapseId, isNull);
    expect(payload.data['nested'], [1, true, null]);
  });

  test('advanced payloads preserve options and explicit silence', () {
    final payload = NotificationPayload.fromJson({
      ...legacy,
      'collapseId': 'chat:1',
      'threadId': 'chat:1',
      'ttlSeconds': 60,
      'priority': 'normal',
      'ios': {'sound': null, 'badge': 0, 'mutableContent': true},
      'android': {
        'channelId': 'chat',
        'groupKey': 'chat:1',
        'groupSummary': 'Two messages',
      },
      'imageUrl': 'https://example.com/image.png',
      'contentAvailable': true,
    });
    expect(payload.collapseId, 'chat:1');
    expect(payload.ios!.hasSound, isTrue);
    expect(payload.ios!.sound, isNull);
    expect(payload.ios!.badge, 0);
    expect(payload.android!.groupSummary, 'Two messages');
    expect(payload.contentAvailable, isTrue);
    expect(IosNotificationOptions.fromJson({}).hasSound, isFalse);
    expect(() => payload.data['new'] = true, throwsUnsupportedError);
  });

  test('APNs environment is optional native startup configuration', () {
    expect(
      const SdkConfig(
        projectId: 'p',
      ).toJson().containsKey('iosApnsEnvironment'),
      isFalse,
    );
    expect(
      const SdkConfig(
        projectId: 'p',
        iosApnsEnvironment: 'production',
      ).toJson()['iosApnsEnvironment'],
      'production',
    );
  });
}
