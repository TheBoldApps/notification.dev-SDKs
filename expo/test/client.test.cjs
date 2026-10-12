const { test } = require('node:test');
const assert = require('node:assert/strict');
const { NotificationDevClient, SdkException } = require('../build/client');

function fixture() {
  const calls = [];
  let callback;
  let attached = 0;
  const native = {
    async invoke(method, json) {
      calls.push([method, JSON.parse(json)]);
      if (method === 'track') return JSON.stringify('event-id');
      if (method === 'getEmail') return 'null';
      if (method === 'openPushSettings') return 'true';
      return null;
    },
    addListener(name, cb) { assert.equal(name, 'onEvent'); callback = cb; attached++; return { remove() { attached--; } }; },
  };
  return {
    client: new NotificationDevClient(native), native, calls,
    emit(name, value) { callback({ name, json: JSON.stringify(value) }); },
    attached: () => attached,
  };
}

test('initialization is shared and failed initialization can be retried', async () => {
  const f = fixture();
  await assert.rejects(f.client.getState(), { code: 'NOT_INITIALIZED' });
  const first = f.client.initialize();
  assert.equal(first, f.client.initialize());
  assert.equal(await first, f.client);
  assert.equal(f.calls.length, 1);
  let attempts = 0;
  f.native.invoke = async () => { if (!attempts++) throw { code: 'INVALID_CONFIGURATION', message: 'Invalid config' }; return null; };
  const retry = new NotificationDevClient(f.native);
  await assert.rejects(retry.initialize(), { code: 'INVALID_CONFIGURATION' });
  await retry.initialize();
  assert.equal(attempts, 2);
});

test('bridge preserves tag/event values and native results', async () => {
  const f = fixture();
  await f.client.initialize();
  await f.client.setTags({ count: 3, premium: true, plan: 'pro' });
  assert.equal(await f.client.track('checkout', { nested: [null, true, { total: 42 }] }), 'event-id');
  assert.equal(await f.client.getEmail(), null);
  assert.equal(await f.client.openPushSettings(), true);
  assert.deepEqual(f.calls[1], ['setTags', { values: { count: 3, premium: true, plan: 'pro' } }]);
  assert.deepEqual(f.calls[2], ['track', { name: 'checkout', properties: { nested: [null, true, { total: 42 }] } }]);
});

test('invalid JSON is rejected before it can be silently changed by serialization', async () => {
  const f = fixture(); await f.client.initialize();
  const cycle = {}; cycle.self = cycle;
  for (const value of [NaN, Infinity, undefined, BigInt(1), new Date(), cycle, () => {}]) {
    await assert.rejects(f.client.track('event', { value }), { code: 'INVALID_ARGUMENT' });
  }
  for (const value of [null, [], {}, NaN]) await assert.rejects(f.client.setTags({ value }), { code: 'INVALID_ARGUMENT' });
  for (const values of [null, [], true]) await assert.rejects(f.client.setTags(values), { code: 'INVALID_ARGUMENT' });
  assert.equal(f.calls.length, 1);
});

test('one native subscription replays immutable state and opens, and cleans up after last removal', async () => {
  const f = fixture(); await f.client.initialize();
  const first = []; const second = []; const opens = []; const diagnostics = [];
  const a = f.client.addListener('states', value => first.push(value));
  f.emit('states', { user: { tags: { plan: 'pro' } } });
  const b = f.client.addListener('states', value => second.push(value));
  await Promise.resolve();
  assert.equal(second[0], first[0]);
  assert.ok(Object.isFrozen(second[0].user.tags));
  f.emit('pendingOpens', [{ interactionId: 'open' }]);
  const c = f.client.addListener('pendingOpens', value => opens.push(value));
  f.emit('diagnostics', { code: 'OLD', message: 'Old diagnostic' });
  const d = f.client.addListener('diagnostics', value => diagnostics.push(value));
  await Promise.resolve();
  assert.equal(opens[0][0].interactionId, 'open');
  assert.deepEqual(diagnostics, []);
  assert.equal(f.attached(), 1);
  a.remove(); a.remove(); b.remove(); c.remove();
  assert.equal(f.attached(), 1);
  d.remove(); assert.equal(f.attached(), 0);
  const e = f.client.addListener('states', () => {});
  assert.equal(f.attached(), 1);
  e.remove();
});

test('replay does not deliver stale state after a newer event or removal', async () => {
  const f = fixture(); await f.client.initialize();
  const a = f.client.addListener('states', () => {});
  f.emit('states', { syncing: false });
  const seen = [];
  const b = f.client.addListener('states', value => seen.push(value));
  f.emit('states', { syncing: true });
  await Promise.resolve();
  assert.deepEqual(seen, [{ syncing: true }]);
  const c = f.client.addListener('states', () => assert.fail('removed listener replayed'));
  c.remove(); await Promise.resolve();
  a.remove(); b.remove();
});

test('registrations using the same callback are independently removable', async () => {
  const f = fixture(); await f.client.initialize();
  const seen = [];
  const listener = value => seen.push(value);
  const first = f.client.addListener('diagnostics', listener);
  const second = f.client.addListener('diagnostics', listener);
  first.remove();
  f.emit('diagnostics', { code: 'LIVE', message: 'Current' });
  assert.equal(seen.length, 1);
  second.remove();
  assert.equal(f.attached(), 0);
});
