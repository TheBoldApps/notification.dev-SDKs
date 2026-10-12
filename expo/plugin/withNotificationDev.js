const fs = require('node:fs');
const path = require('node:path');
const { AndroidConfig, createRunOncePlugin, withAndroidManifest, withDangerousMod, withEntitlementsPlist, withGradleProperties, withInfoPlist, withPodfile, withProjectBuildGradle } = require('expo/config-plugins');
const { mergeContents } = require(require.resolve('@expo/config-plugins/build/utils/generateCode', { paths: [path.dirname(require.resolve('expo/package.json'))] }));

function nativeConfiguration(options = {}) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(options.projectId ?? '')) {
    throw new Error('notification.dev: projectId must be your public project UUID');
  }
  const url = new URL(options.baseUrl ?? 'https://app.notification.dev/');
  if (url.username || url.password || url.search || url.hash || !['https:', 'http:'].includes(url.protocol) || (url.protocol === 'http:' && options.allowHttp !== true)) {
    throw new Error('notification.dev: use HTTPS, or explicitly enable allowHttp for an HTTP API URL');
  }
  for (const key of ['allowHttp', 'displayInForeground', 'loggingEnabled']) {
    if (options[key] !== undefined && typeof options[key] !== 'boolean') throw new Error(`notification.dev: ${key} must be a boolean`);
  }
  for (const key of ['androidChannelId', 'androidChannelName']) {
    if (options[key] !== undefined && (typeof options[key] !== 'string' || !options[key].trim())) throw new Error(`notification.dev: ${key} must be a nonempty string`);
  }
  return {
    projectId: options.projectId,
    baseUrl: url.toString(),
    androidSmallIcon: 'ic_notification_dev',
    androidChannelId: options.androidChannelId ?? 'notification_dev',
    androidChannelName: options.androidChannelName ?? 'Notifications',
    displayInForeground: options.displayInForeground ?? true,
    allowHttp: options.allowHttp ?? false,
    loggingEnabled: options.loggingEnabled ?? false,
  };
}

function configurePodfile(contents) {
  return mergeContents({
    tag: 'notification-dev-spm', src: contents,
    newSrc: `    require File.join(File.dirname(Pod::Executable.execute_command('node', ['--print', "require.resolve('@notification.dev/expo/package.json')"]).strip), 'plugin', 'with_spm.rb')\n    NotificationDevExpo.install_swift_package(installer)`,
    anchor: /post_install do \|installer\|/, offset: 1, comment: '#',
  }).contents;
}

function withNotificationDev(config, options = {}) {
  const native = nativeConfiguration(options);
  // Both providers claim notification callbacks/services; v1 has exclusive ownership.
  const projectRoot = config._internal?.projectRoot;
  if (projectRoot) {
    const dependencies = JSON.parse(fs.readFileSync(path.join(projectRoot, 'package.json'), 'utf8'));
    const installed = { ...dependencies.dependencies, ...dependencies.devDependencies };
    if (installed['expo-notifications'] || installed['@react-native-firebase/messaging']) {
      throw new Error('notification.dev automatic integration cannot be combined with expo-notifications or @react-native-firebase/messaging');
    }
  }
  config = AndroidConfig.Permissions.withPermissions(config, ['android.permission.POST_NOTIFICATIONS']);
  config = withGradleProperties(config, config => {
    const key = 'android.kotlinVersion';
    const existing = config.modResults.find(item => item.type === 'property' && item.key === key);
    if (existing && existing.value !== '2.3.20') {
      throw new Error('notification.dev: the released Android SDK requires android.kotlinVersion=2.3.20; remove the conflicting Kotlin override');
    }
    if (!existing) config.modResults.push({ type: 'property', key, value: '2.3.20' });
    return config;
  });
  config = withProjectBuildGradle(config, config => {
    config.modResults.contents = mergeContents({
      tag: 'notification-dev-kotlin', src: config.modResults.contents,
      newSrc: "    classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20')",
      anchor: /classpath\(['"]org.jetbrains.kotlin:kotlin-gradle-plugin['"]\)/,
      offset: 1, comment: '//',
    }).contents;
    return config;
  });
  config = withAndroidManifest(config, config => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(config.modResults);
    app['meta-data'] ??= [];
    const name = 'dev.notification.expo.CONFIG';
    app['meta-data'] = app['meta-data'].filter(item => item.$['android:name'] !== name);
    app['meta-data'].push({ $: { 'android:name': name, 'android:value': JSON.stringify(native) } });
    if (native.allowHttp) app.$['android:usesCleartextTraffic'] = 'true';
    return config;
  });
  config = withDangerousMod(config, ['android', async config => {
    const res = path.join(config.modRequest.platformProjectRoot, 'app/src/main/res');
    await fs.promises.mkdir(path.join(res, 'drawable'), { recursive: true });
    await fs.promises.mkdir(path.join(res, 'raw'), { recursive: true });
    const vector = path.join(res, 'drawable/ic_notification_dev.xml');
    const png = path.join(res, 'drawable/ic_notification_dev.png');
    if (options.androidIcon) {
      if (path.extname(options.androidIcon).toLowerCase() !== '.png') throw new Error('notification.dev: androidIcon must be a monochrome transparent PNG');
      await fs.promises.copyFile(path.resolve(config.modRequest.projectRoot, options.androidIcon), png);
      await fs.promises.rm(vector, { force: true });
    } else {
      await fs.promises.writeFile(vector, '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FFFFFFFF" android:pathData="M12,22a2,2 0,0 0,2 -2h-4a2,2 0,0 0,2 2M18,16v-5a6,6 0,0 0,-5 -5.91V4a1,1 0,0 0,-2 0v1.09A6,6 0,0 0,6 11v5l-2,2v1h16v-1z"/></vector>');
      await fs.promises.rm(png, { force: true });
    }
    await fs.promises.writeFile(path.join(res, 'raw/notification_dev_keep.xml'), '<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@drawable/ic_notification_dev" />');
    return config;
  }]);
  config = withInfoPlist(config, config => {
    config.modResults.NotificationDev = native;
    if (native.allowHttp) {
      config.modResults.NSAppTransportSecurity = { ...config.modResults.NSAppTransportSecurity, NSAllowsArbitraryLoads: true };
    }
    return config;
  });
  config = withEntitlementsPlist(config, config => {
    config.modResults['aps-environment'] ??= 'development';
    return config;
  });
  return withPodfile(config, config => {
    config.modResults.contents = configurePodfile(config.modResults.contents);
    return config;
  });
}

module.exports = createRunOncePlugin(withNotificationDev, '@notification.dev/expo', require('../package.json').version);
module.exports.nativeConfiguration = nativeConfiguration;
module.exports.configurePodfile = configurePodfile;
