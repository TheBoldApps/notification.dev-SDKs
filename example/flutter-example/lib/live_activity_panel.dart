import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:notification_dev/notification_dev.dart';

class LiveActivityPanel extends StatefulWidget {
  const LiveActivityPanel({required this.sdk, super.key});
  final NotificationDev sdk;
  @override
  State<LiveActivityPanel> createState() => _LiveActivityPanelState();
}

class _LiveActivityPanelState extends State<LiveActivityPanel> {
  final _provider = TextEditingController(
    text: const String.fromEnvironment(
      'NOTIFICATION_DEV_LIVE_ACTIVITY_PROVIDER_ID',
    ),
  );
  final _activity = TextEditingController(text: 'delivery-example');
  final _grant = TextEditingController();
  LiveActivityCapabilities? _capabilities;
  String _message = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _run(() async {
      _capabilities = await widget.sdk.liveActivities.getCapabilities();
    });
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    try {
      await action();
      if (mounted) setState(() => _message = 'Done');
    } catch (error) {
      if (mounted) setState(() => _message = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _provider.dispose();
    _activity.dispose();
    _grant.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final activities = widget.sdk.liveActivities;
    final enabled = _capabilities?.supported == true && !_busy;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Live Activities', style: TextStyle(fontSize: 20)),
        if (_capabilities != null)
          Text(
            _capabilities!.supported
                ? 'Allowed: ${_capabilities!.activitiesEnabled}; remote start supported: ${_capabilities!.pushToStartSupported}'
                : 'Live Activities require iOS 16.1 or later.',
          ),
        Text(_message),
        TextField(
          controller: _provider,
          decoration: const InputDecoration(labelText: 'APNs provider UUID'),
        ),
        TextButton(
          onPressed: enabled
              ? () => _run(
                  () => activities.configure(
                    LiveActivityConfiguration(
                      providerConfigurationId: _provider.text.trim(),
                    ),
                  ),
                )
              : null,
          child: const Text('Configure Live Activities'),
        ),
        TextField(
          controller: _activity,
          decoration: const InputDecoration(labelText: 'Activity ID'),
        ),
        TextField(
          controller: _grant,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(
            labelText: 'Fresh backend registration grant',
          ),
        ),
        const Text(
          'Embed the example SwiftUI widget first. Your backend issues grants for this installation and association; do not paste a project API key.',
        ),
        TextButton(
          onPressed: enabled
              ? () => _run(() async {
                  await activities.start(
                    activityId: _activity.text.trim(),
                    attributes: {'name': 'Delivery test'},
                    content: {'status': 'Preparing'},
                    registrationToken: _grant.text.trim(),
                  );
                  _grant.clear();
                })
              : null,
          child: const Text('Start local activity'),
        ),
        TextButton(
          onPressed: enabled
              ? () => _run(
                  () => activities.end(
                    _activity.text.trim(),
                    content: {'status': 'Delivered'},
                  ),
                )
              : null,
          child: const Text('End local activity'),
        ),
        StreamBuilder<List<LiveActivityState>>(
          stream: activities.states,
          initialData: const [],
          builder: (context, snapshot) {
            if (snapshot.hasError) return Text('${snapshot.error}');
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final activity in snapshot.data ?? <LiveActivityState>[])
                  ListTile(
                    title: Text(activity.activityId),
                    subtitle: Text(
                      '${activity.state}: ${jsonEncode(activity.content)}',
                    ),
                    onTap: () =>
                        setState(() => _activity.text = activity.activityId),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }
}
