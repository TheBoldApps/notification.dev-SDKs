import type { NotificationDev } from '@notification.dev/expo';
import { OneSignal } from 'react-native-onesignal';

type NotificationDevClient = Awaited<ReturnType<typeof NotificationDev.initialize>>;

// Example only: the application supplies its authenticated account and remote rollout flag.
// Calls must be serialized with login/logout and other provider-switch requests.
export async function updatePushProvider(
  client: NotificationDevClient, useNotificationDev: boolean, expectedExternalId: string | null,
): Promise<void> {
  if (!useNotificationDev) {
    // Request legacy opt-in first. The application owns confirmation of legacy readiness.
    OneSignal.User.pushSubscription.optIn();
    await client.setPushOptedIn(false);
    await client.refresh();
    return;
  }
  await client.setPushOptedIn(true);
  const state = await client.refresh();
  if (state.availability !== 'AVAILABLE' || state.identityChangePending || state.hasPendingChanges ||
      (state.externalId ?? null) !== expectedExternalId || !state.permission.areNotificationsEnabled ||
      !state.pushOptedIn || !state.pushTokenRegistered || state.pushRegistration.status !== 'registered' || state.lastError) {
    throw new Error('Wait for server-acknowledged identity and push registration before switching providers');
  }
  OneSignal.User.pushSubscription.optOut();
}
