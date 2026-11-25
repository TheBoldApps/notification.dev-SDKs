import type { EventMap, SdkState, Subscription } from '@notification.dev/expo';

type CaptureClient = {
  getState(): Promise<SdkState>;
  addListener<K extends 'states' | 'diagnostics' | 'pendingOpens'>(
    name: K, listener: (value: EventMap[K]) => void,
  ): Subscription;
};

// Pass the initialized shared client and your existing bounded, QA-only journal.
// This observes notifications; it never routes, acknowledges, refreshes, or tracks.
export function attachNotificationCapture(
  sdk: CaptureClient,
  append: (entry: Record<string, unknown>) => void | Promise<void>,
) {
  let active = true;
  let failed = false;
  const record = (kind: string, details: Record<string, unknown>) => {
    if (!active) return;
    try {
      void Promise.resolve(append({ observedAtUtc: new Date().toISOString(), kind, ...details }))
        .catch(() => { failed = true; });
    } catch { failed = true; }
  };
  const state = (s: SdkState) => record('state', {
    installationId: s.installationId, associationId: s.associationId,
    availability: s.availability, identityChangePending: s.identityChangePending,
    pushOptedIn: s.pushOptedIn, syncing: s.syncing,
    hasPendingChanges: s.hasPendingChanges, lastSyncedAt: s.lastSyncedAt,
    lastErrorCode: s.lastError?.code,
    notificationsEnabled: s.permission.areNotificationsEnabled,
    authorizationStatus: s.permission.authorizationStatus,
    runtimePermission: s.permission.runtimePermission,
    channelStatus: s.permission.channelStatus,
    pushRegistrationStatus: s.pushRegistration.status,
    pushRegistrationErrorCode: s.pushRegistration.errorCode,
  });
  const subscriptions = [
    sdk.addListener('diagnostics', ({ code }) => record('diagnostic', { code })),
    sdk.addListener('states', state),
    sdk.addListener('pendingOpens', (pending) => record('pendingOpens', {
      opens: pending.map(({ interactionId, openedAt, notification: n }) => ({
        interactionId, openedAt, deliveryId: n.deliveryId,
        associationId: n.associationId, version: n.version, expiresAt: n.expiresAt,
      })),
    })),
  ];
  void sdk.getState().then(state).catch((error: unknown) => record('snapshotError', {
    code: error && typeof error === 'object' && 'code' in error && typeof error.code === 'string'
      ? error.code : 'NATIVE_FAILURE',
  }));

  return {
    get captureFailed() { return failed; },
    stop() {
      active = false;
      subscriptions.forEach((subscription) => subscription.remove());
    },
  };
}
