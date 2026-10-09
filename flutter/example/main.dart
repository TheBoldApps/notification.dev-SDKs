import 'package:flutter/material.dart';
import 'package:notification_dev/notification_dev.dart';

// Configure Firebase (Android) and signing/APNs (iOS) before running.
// See the package README for the required platform setup.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final sdk = await NotificationDev.initialize(
    const SdkConfig(
      projectId: 'YOUR_PUBLIC_PROJECT_UUID',
      androidSmallIcon: 'ic_notification',
    ),
  );
  runApp(NotificationExample(sdk: sdk));
}

class NotificationExample extends StatelessWidget {
  const NotificationExample({super.key, required this.sdk});

  final NotificationDev sdk;

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      appBar: AppBar(title: const Text('notification.dev')),
      body: Builder(
        builder: (context) => Center(
          child: ElevatedButton(
            onPressed: () async {
              try {
                await sdk.requestPushPermission();
              } on SdkException catch (error) {
                if (context.mounted) {
                  ScaffoldMessenger.of(
                    context,
                  ).showSnackBar(SnackBar(content: Text(error.message)));
                }
              }
            },
            child: const Text('Enable notifications'),
          ),
        ),
      ),
    ),
  );
}
