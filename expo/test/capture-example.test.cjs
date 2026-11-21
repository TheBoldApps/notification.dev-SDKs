const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { NotificationDevClient } = require('../build/client');

const source = fs.readFileSync(path.join(__dirname, '../../docs/examples/expo-0.2-notification-capture.ts'), 'utf8');
const context = { exports: {} };
vm.runInNewContext(ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
}).outputText, context);
const { attachNotificationCapture } = context.exports;
const tick = () => new Promise((resolve) => setImmediate(resolve));

async function fixture() {
  const calls = [];
  let callback;
  const state = {
    installationId: 'installation', associationId: 'association',
    availability: 'AVAILABLE', identityChangePending: false,
    pushOptedIn: true, syncing: false, hasPendingChanges: true, lastSyncedAt: 123,
    permission: { areNotificationsEnabled: true, authorizationStatus: 'authorized' },
    pushRegistration: { status: 'registered' }, lastError: { code: 'QUEUE_FULL', message: 'SECRET_MESSAGE' },
    user: { email: { address: 'SECRET_EMAIL' }, tags: { token: 'SECRET_TAG' } },
    externalId: 'SECRET_USER', credential: 'SECRET_CREDENTIAL',
  };
  const client = new NotificationDevClient({
    async invoke(method) { calls.push(method); return method === 'getState' ? JSON.stringify(state) : null; },
    addListener(_name, listener) { callback = listener; return { remove() {} }; },
  });
  await client.initialize();
  return { client, calls, state,
    emit(name, value) { callback({ name, json: JSON.stringify(value) }); },
  };
}

const pending = [{ interactionId: 'open:delivery', openedAt: 456, notification: {
  version: 1, deliveryId: 'delivery', associationId: 'association', expiresAt: 999,
  title: 'SECRET_TITLE', body: 'SECRET_BODY', deepLink: 'SECRET_LINK', data: { token: 'SECRET_TOKEN' },
} }];

test('capture allowlists fields and never acknowledges or sends tracking', async () => {
  const f = await fixture();
  const entries = [];
  const capture = attachNotificationCapture(f.client, (entry) => { entries.push(entry); });
  await tick();
  f.emit('states', f.state);
  f.emit('diagnostics', { code: 'QUEUE_FULL', message: 'SECRET_DIAGNOSTIC' });
  f.emit('pendingOpens', pending);
  assert.equal(entries.find((e) => e.kind === 'state').installationId, 'installation');
  assert.equal(entries.find((e) => e.kind === 'state').notificationsEnabled, true);
  assert.equal(entries.find((e) => e.kind === 'diagnostic').code, 'QUEUE_FULL');
  assert.equal(entries.find((e) => e.kind === 'pendingOpens').opens[0].deliveryId, 'delivery');
  assert.equal(JSON.stringify(entries).includes('SECRET'), false);
  assert.ok(entries.every((e) => Number.isFinite(Date.parse(e.observedAtUtc))));
  assert.deepEqual(f.calls, ['initialize', 'getState']);
  assert.equal(capture.captureFailed, false);
  capture.stop();
});

test('failing journals cannot interrupt navigation observers; stop removes only capture', async () => {
  for (const append of [() => { throw Error('write failed'); }, () => Promise.reject(Error('write failed'))]) {
    const f = await fixture();
    let writes = 0;
    const capture = attachNotificationCapture(f.client, (entry) => { writes++; return append(entry); });
    let navigated = 0;
    const navigation = f.client.addListener('pendingOpens', () => { navigated++; });
    f.emit('pendingOpens', pending);
    await tick();
    assert.equal(navigated, 1);
    assert.equal(capture.captureFailed, true);
    capture.stop();
    capture.stop();
    const before = writes;
    f.emit('pendingOpens', pending);
    await tick();
    assert.equal(navigated, 2);
    assert.equal(writes, before);
    navigation.remove();
  }
});
