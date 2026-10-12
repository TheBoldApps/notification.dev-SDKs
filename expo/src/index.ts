import { requireOptionalNativeModule } from 'expo';
import { NativeBridge, NotificationDevClient, SdkException } from './client';

export * from './types';
export { SdkException } from './client';

let client: NotificationDevClient | undefined;
export const NotificationDev = {
  async initialize(): Promise<NotificationDevClient> {
    if (!client) {
      const native = requireOptionalNativeModule<NativeBridge>('NotificationDev');
      if (!native) throw new SdkException('UNSUPPORTED_PLATFORM', 'notification.dev requires an Android or iOS development/production build with its config plugin');
      client = new NotificationDevClient(native);
    }
    return client.initialize();
  },
};
