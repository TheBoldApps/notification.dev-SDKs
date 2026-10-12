export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
export type TagValue = string | boolean | number;
export interface SdkError { code: string; message: string }
export interface EmailSubscription { address: string; optedIn: boolean; suppressed: boolean }
export interface PushPermissionStatus {
  areNotificationsEnabled: boolean;
  runtimePermission?: string;
  channelStatus?: string;
  authorizationStatus?: string;
  alertSetting?: string;
  soundSetting?: string;
  badgeSetting?: string;
}
export interface PushPermissionResult {
  outcome: 'granted' | 'denied' | 'settingsRequired' | 'settingsOpened' | 'failed';
  errorCode?: string;
}
export interface SdkState {
  availability: 'LOADING' | 'AVAILABLE' | 'UNAVAILABLE';
  user: { email?: EmailSubscription | null; tags: Record<string, TagValue> };
  installationId?: string | null;
  associationId?: string | null;
  externalId?: string | null;
  /** Milliseconds since Unix epoch. */
  lastSyncedAt?: number | null;
  syncing: boolean;
  hasPendingChanges: boolean;
  pushOptedIn: boolean;
  pushRegistration: { status: string; errorCode?: string | null; updatedAt?: string | null };
  permission: PushPermissionStatus;
  lastError?: SdkError | null;
  identityChangePending: boolean;
}
export interface NotificationPayload {
  version: number;
  deliveryId: string;
  associationId: string;
  expiresAt: number;
  title: string;
  body: string;
  data: JsonObject;
  deepLink?: string | null;
}
export interface NotificationOpen {
  interactionId: string;
  notification: NotificationPayload;
  openedAt: number;
}
export interface NotificationEvent { notification: NotificationPayload }
export interface EventMap {
  states: SdkState;
  pendingOpens: NotificationOpen[];
  notifications: NotificationEvent;
  diagnostics: SdkError;
}
export interface Subscription { remove(): void }
