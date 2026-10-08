import 'dart:async';

import 'package:flutter/material.dart';
import 'package:notification_dev/notification_dev.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: ExampleApp()));
}

class ExampleApp extends StatefulWidget {
  const ExampleApp({super.key});

  @override
  State<ExampleApp> createState() => _ExampleAppState();
}

class _ExampleAppState extends State<ExampleApp> {
  final _externalId = TextEditingController(text: 'flutter-example-user');
  final _email = TextEditingController();
  final _tag = TextEditingController(text: 'premium');
  final _eventName = TextEditingController();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  NotificationDev? _sdk;
  SdkState? _state;
  List<NotificationOpen> _opens = [];
  String _message = 'Starting…';
  bool _emailOptedIn = true;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final sdk = await NotificationDev.initialize(
        const SdkConfig(
          // Replace this placeholder with your notification.dev project UUID.
          projectId: 'YOUR_PUBLIC_PROJECT_UUID',
          allowLocalhostHttp: true,
          loggingEnabled: true,
        ),
      );
      if (!mounted) {
        return;
      }

      setState(() {
        _sdk = sdk;
        _message = 'Initialized';
      });
      _subscriptions.addAll([
        sdk.states.listen((state) {
          if (mounted) {
            setState(() => _state = state);
          }
        }, onError: _showError),
        sdk.pendingOpens.listen((opens) {
          if (mounted) {
            setState(() => _opens = opens);
          }
        }, onError: _showError),
        sdk.notifications.listen((event) {
          if (mounted) {
            setState(() => _message = 'Received: ${event.notification.title}');
          }
        }, onError: _showError),
        sdk.diagnostics.listen(_showError, onError: _showError),
      ]);
    } catch (error) {
      _showError(error);
    }
  }

  void _showError(Object error) {
    if (mounted) {
      setState(() => _message = error.toString());
    }
  }

  Future<void> _showResult(Future<Object?> operation) async {
    try {
      final result = await operation;

      if (mounted) {
        setState(
          () => _message = result?.toString() ?? 'Local operation completed',
        );
      }
    } catch (error) {
      _showError(error);
    }
  }

  Future<void> _trackEvent(NotificationDev sdk) async {
    final formKey = GlobalKey<FormState>();
    final eventName = await showDialog<String>(
      context: context,
      builder: (dialogContext) {
        void submit() {
          if (formKey.currentState!.validate()) {
            Navigator.of(dialogContext).pop(_eventName.text.trim());
          }
        }

        return AlertDialog(
          title: const Text('Track event'),
          content: Form(
            key: formKey,
            child: TextFormField(
              controller: _eventName,
              autofocus: true,
              decoration: const InputDecoration(
                labelText: 'Event name',
                hintText: 'flutter_example',
              ),
              textInputAction: TextInputAction.done,
              validator: (value) => value == null || value.trim().isEmpty
                  ? 'Enter an event name'
                  : null,
              onFieldSubmitted: (_) => submit(),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Cancel'),
            ),
            TextButton(onPressed: submit, child: const Text('Track')),
          ],
        );
      },
    );

    if (!mounted || eventName == null) {
      return;
    }

    await _showResult(
      sdk.track(eventName, properties: {'platform': 'flutter'}),
    );
  }

  Future<void> _navigate(NotificationOpen open) async {
    // Treat payload data as untrusted input. This sample displays it without opening URLs.
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => Scaffold(
          appBar: AppBar(title: Text(open.notification.title)),
          body: Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              '${open.notification.body}\n'
              '${open.notification.deepLink ?? ''}\n${open.notification.data}',
            ),
          ),
        ),
      ),
    );

    final sdk = _sdk;

    if (mounted && sdk != null) {
      await _showResult(sdk.acknowledgeOpen(open.interactionId));
    }
  }

  @override
  void dispose() {
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
    _externalId.dispose();
    _email.dispose();
    _tag.dispose();
    _eventName.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('notification.dev')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        SelectableText(_message),
        const SizedBox(height: 16),
        if (_state case final state?) ...[
          Text('Availability: ${state.availability.name}'),
          Text('User: ${state.externalId ?? 'anonymous'}'),
          Text(
            'Syncing: ${state.syncing}; pending: ${state.hasPendingChanges}',
          ),
          Text(
            'Push: ${state.pushOptedIn}; permission: ${state.permission.areNotificationsEnabled}',
          ),
          Text('Registration: ${state.pushRegistration.status}'),
          Text('Email: ${state.user.email?.address ?? 'none'}'),
          Text('Tags: ${state.user.tags}'),
          if (state.lastError != null) Text('${state.lastError}'),
        ],
        if (_sdk case final sdk?) ...[
          TextField(
            controller: _externalId,
            decoration: const InputDecoration(labelText: 'External ID'),
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => _showResult(sdk.login(_externalId.text)),
                child: const Text('Log in'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.logout()),
                child: const Text('Log out'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.refresh()),
                child: const Text('Refresh'),
              ),
            ],
          ),
          TextField(
            controller: _email,
            decoration: const InputDecoration(labelText: 'Email'),
          ),
          SwitchListTile(
            title: const Text('Email opt-in'),
            value: _emailOptedIn,
            onChanged: (value) => setState(() => _emailOptedIn = value),
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => _showResult(
                  sdk.setEmail(_email.text, optedIn: _emailOptedIn),
                ),
                child: const Text('Set email'),
              ),
              TextButton(
                onPressed: () =>
                    _showResult(sdk.setEmailOptedIn(_emailOptedIn)),
                child: const Text('Update preference'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.removeEmail()),
                child: const Text('Remove email'),
              ),
            ],
          ),
          TextField(
            controller: _tag,
            decoration: const InputDecoration(labelText: 'Plan tag'),
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => _showResult(sdk.setTags({'plan': _tag.text})),
                child: const Text('Set tag'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.removeTags({'plan'})),
                child: const Text('Remove tag'),
              ),
            ],
          ),
          Wrap(
            spacing: 8,
            children: [
              TextButton(
                onPressed: () => _showResult(
                  sdk
                      .requestPushPermission(settingsFallback: true)
                      .then((result) => result.outcome.name),
                ),
                child: const Text('Request permission'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.openPushSettings()),
                child: const Text('Open settings'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.setPushOptedIn(true)),
                child: const Text('Push opt-in'),
              ),
              TextButton(
                onPressed: () => _showResult(sdk.setPushOptedIn(false)),
                child: const Text('Push opt-out'),
              ),
              TextButton(
                onPressed: () => _trackEvent(sdk),
                child: const Text('Track event'),
              ),
            ],
          ),
        ],
        const Text('Pending opens'),
        for (final open in _opens)
          ListTile(
            title: Text(open.notification.title),
            subtitle: Text(open.interactionId),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _navigate(open),
          ),
      ],
    ),
  );
}
