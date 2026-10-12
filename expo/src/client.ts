import type { EmailSubscription, EventMap, JsonObject, PushPermissionResult, PushPermissionStatus, SdkState, Subscription, TagValue } from './types';

export interface NativeBridge {
  invoke(method: string, argumentsJson: string): Promise<string | null>;
  addListener(event: 'onEvent', listener: (event: { name: keyof EventMap; json: string }) => void): Subscription;
}

export class SdkException extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
    this.name = 'SdkException';
  }
}

// Reject lossy JSON conversions (NaN, undefined, Dates, BigInt, and cycles).
function validateJson(value: unknown, ancestors = new Set<object>()): void {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
  if (typeof value === 'number' && Number.isFinite(value)) return;
  if (typeof value !== 'object' || ancestors.has(value)) {
    throw new SdkException('INVALID_ARGUMENT', 'Expected finite JSON values without cycles');
  }
  if (!Array.isArray(value) && Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null) {
    throw new SdkException('INVALID_ARGUMENT', 'Expected plain JSON objects');
  }
  ancestors.add(value);
  for (const item of Array.isArray(value) ? value : Object.values(value)) validateJson(item, ancestors);
  ancestors.delete(value);
}

function frozen<T>(value: T): T {
  if (value && typeof value === 'object') {
    for (const item of Object.values(value)) frozen(item);
    Object.freeze(value);
  }
  return value;
}

/** One client for the process-wide native SDK. Configuration comes from the Expo config plugin. */
export class NotificationDevClient {
  private initialization?: Promise<this>;
  private ready = false;
  private nativeSubscription?: Subscription;
  private listeners = new Map<keyof EventMap, Set<(value: never) => void>>();
  private latest = new Map<keyof EventMap, unknown>();

  constructor(private readonly native: NativeBridge) {}

  initialize(): Promise<this> {
    if (!this.initialization) {
      this.initialization = this.call<void>('initialize').then(() => {
        this.ready = true;
        return this;
      }).catch(error => {
        this.initialization = undefined;
        throw error;
      });
    }
    return this.initialization;
  }

  private async call<T>(method: string, args: JsonObject = {}): Promise<T> {
    if (method !== 'initialize' && !this.ready) throw new SdkException('NOT_INITIALIZED', 'Await NotificationDev.initialize() first');
    validateJson(args);
    try {
      const result = await this.native.invoke(method, JSON.stringify(args));
      return (result === null ? undefined : frozen(JSON.parse(result))) as T;
    } catch (error) {
      if (error instanceof SdkException) throw error;
      const failure = error as { code?: string; message?: string };
      throw new SdkException(failure?.code ?? 'NATIVE_FAILURE', failure?.message ?? 'Native SDK operation failed');
    }
  }

  /** State and pending opens replay their latest values. Other events are live only. */
  addListener<K extends keyof EventMap>(name: K, listener: (value: EventMap[K]) => void): Subscription {
    if (!this.ready) throw new SdkException('NOT_INITIALIZED', 'Await NotificationDev.initialize() first');
    if (!this.listeners.has(name)) this.listeners.set(name, new Set());
    const callback = (value: never) => listener(value);
    const listeners = this.listeners.get(name)!;
    listeners.add(callback);
    let active = true;
    if (this.latest.has(name)) {
      const snapshot = this.latest.get(name);
      queueMicrotask(() => {
        if (active && this.latest.get(name) === snapshot) callback(snapshot as never);
      });
    }
    if (!this.nativeSubscription) {
      this.nativeSubscription = this.native.addListener('onEvent', event => {
        const value = frozen(JSON.parse(event.json));
        if (event.name === 'states' || event.name === 'pendingOpens') this.latest.set(event.name, value);
        for (const handler of [...(this.listeners.get(event.name) ?? [])]) handler(value as never);
      });
    }
    return { remove: () => {
      if (!active) return;
      active = false;
      listeners.delete(callback);
      if ([...this.listeners.values()].every(set => set.size === 0)) {
        this.nativeSubscription?.remove();
        this.nativeSubscription = undefined;
        this.latest.clear();
      }
    } };
  }

  getState = () => this.call<SdkState>('getState');
  getTags = () => this.call<Record<string, TagValue>>('getTags');
  getEmail = () => this.call<EmailSubscription | null>('getEmail');
  login = (externalId: string) => this.call<void>('login', { externalId });
  logout = () => this.call<void>('logout');
  setEmail = (address: string, optedIn: boolean) => this.call<void>('setEmail', { address, optedIn });
  removeEmail = () => this.call<void>('removeEmail');
  setEmailOptedIn = (enabled: boolean) => this.call<void>('setEmailOptedIn', { enabled });
  setPushOptedIn = (enabled: boolean) => this.call<void>('setPushOptedIn', { enabled });
  async setTags(values: Record<string, TagValue>): Promise<void> {
    if (!values || typeof values !== 'object' || Array.isArray(values)) throw new SdkException('INVALID_ARGUMENT', 'Tags must be an object');
    for (const value of Object.values(values)) {
      if (!['string', 'boolean', 'number'].includes(typeof value)) throw new SdkException('INVALID_ARGUMENT', 'Tags must contain strings, booleans, or finite numbers');
    }
    return this.call<void>('setTags', { values });
  }
  removeTags = (keys: string[]) => this.call<void>('removeTags', { keys });
  track = (name: string, properties: JsonObject = {}, eventId?: string) => this.call<string>('track', { name, properties, ...(eventId === undefined ? {} : { eventId }) });
  refresh = () => this.call<SdkState>('refresh');
  acknowledgeOpen = (interactionId: string) => this.call<void>('acknowledgeOpen', { interactionId });
  getPushPermissionStatus = () => this.call<PushPermissionStatus>('getPushPermissionStatus');
  requestPushPermission = (settingsFallback = false) => this.call<PushPermissionResult>('requestPushPermission', { settingsFallback });
  /** Resolves to whether settings opened, not whether permission was granted. */
  openPushSettings = () => this.call<boolean>('openPushSettings');
}
