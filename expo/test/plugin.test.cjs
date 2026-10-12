const { test } = require('node:test');
const assert = require('node:assert/strict');
const { nativeConfiguration, configurePodfile } = require('../plugin/withNotificationDev');
const projectId = '8d022e2f-41c6-4853-b578-3b56d4e099ab';

test('config validates public configuration and explicit HTTP opt-in', () => {
  assert.equal(nativeConfiguration({ projectId }).baseUrl, 'https://app.notification.dev/');
  assert.equal(nativeConfiguration({ projectId, allowHttp: true, baseUrl: 'http://192.168.1.2:3000' }).allowHttp, true);
  for (const options of [{}, { projectId: 'bad' }, { projectId, baseUrl: 'http://localhost' }, { projectId, baseUrl: 'https://secret@example.com' }, { projectId, loggingEnabled: 'true' }, { projectId, androidChannelId: '' }]) {
    assert.throws(() => nativeConfiguration(options));
  }
});

test('SPM Podfile hook is repeatable and preserves host post-install', () => {
  const podfile = 'target "Example" do\n  post_install do |installer|\n    react_native_post_install(installer)\n  end\nend\n';
  const once = configurePodfile(podfile);
  assert.equal(once, configurePodfile(once));
  assert.ok(once.includes('react_native_post_install(installer)'));
  assert.equal(once.match(/NotificationDevExpo.install_swift_package/g).length, 1);
  assert.ok(once.indexOf('NotificationDevExpo.install_swift_package') > once.indexOf('post_install do'));
  assert.throws(() => configurePodfile('target "Example" do\nend'));
});
