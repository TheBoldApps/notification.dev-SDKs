# Shared Live Activity widget

This SwiftUI widget is shared by the Flutter and Expo examples. It displays
`attributes.data.name` and `content-state.data.status` for the native
`DefaultLiveActivityAttributes` type. The bridges do not render Flutter/React UI
inside a Live Activity.

The Flutter example includes the target. To attach/re-attach it to either example
from the SDK repository root (Ruby's `xcodeproj` gem is required):

```sh
ruby example/live-activity-widget/attach.rb example/flutter-example/ios/Runner.xcodeproj
ruby example/live-activity-widget/attach.rb example/expo-example/ios/notificationdevExample.xcodeproj
```

For Expo run `npx expo prebuild --platform ios` first and re-run the attachment
command whenever prebuild recreates the project. Its example enables
`iosLiveActivities: true` in the notification.dev config plugin. This sets the
app's `NSSupportsLiveActivities` flag; the attachment helper creates the widget.
The helper only changes contributor example projects; it is not a production
config plugin. Production Expo apps should manage their widget target with a
config plugin or checked-in native project.

The helper uses the released native Swift package at `0.3.0` by default. Its
optional second argument selects a local native package directory. Use
`NOTIFICATION_DEV_SWIFT_PACKAGE_PATH` for the bridge's matching local package too.
Before installing on a device, select the same signing team for app and widget,
check the extension's app-prefixed bundle ID, and match marketing/build versions.
The sample widget targets iOS 16.2 and links only `NotificationDevActivityKit`.

In the app's Live Activities panel, configure a matching APNs provider, obtain a
fresh registration grant from your own authenticated backend, and start locally.
The panel lists current-association activities, including remote starts. Selecting
an entry fills its ID so **End local activity** can end it. Grants are not retained
in app config; server API keys never belong in the example app.

Follow the [facade guide](../../docs/live-activity-facades.md) for API usage, server
payloads, lifecycle behavior and a physical-device checklist.
