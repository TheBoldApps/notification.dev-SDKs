module.exports = {
  expo: {
    name: 'notification.dev Example', slug: 'notification-dev-example',
    ios: { bundleIdentifier: 'dev.notification.expoexample' },
    android: {
      package: process.env.NOTIFICATION_DEV_ANDROID_PACKAGE ?? 'dev.notification.expoexample',
      ...(process.env.GOOGLE_SERVICES_FILE ? { googleServicesFile: process.env.GOOGLE_SERVICES_FILE } : {}),
    },
    plugins: [['@notification.dev/expo', {
      projectId: process.env.NOTIFICATION_DEV_PROJECT_ID ?? '00000000-0000-0000-0000-000000000000',
      loggingEnabled: true,
    }]],
  },
};
