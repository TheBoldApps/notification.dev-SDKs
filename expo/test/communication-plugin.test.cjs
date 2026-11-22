const { test } = require('node:test');
const assert = require('node:assert/strict');
const plugin = require('../plugin/withNotificationDev');
const projectId = '11111111-1111-4111-8111-111111111111';
function configured(enabled) { return plugin({ name: 'Test', slug: 'test' }, { projectId, iosCommunicationNotifications: enabled, iosCommunicationAppGroup: "group.test.notifications" }); }
async function mod(config, name, results) { return (await config.mods.ios[name]({ ...config, modRequest: { platform: 'ios', modName: name, projectRoot: '/tmp' }, modResults: results })).modResults; }
test('communication opt-in preserves activities and production APNs signing', async () => {
 const config=configured(true);
 const plist=await mod(config,'infoPlist',{NSUserActivityTypes:['ExistingActivity','INSendMessageIntent']});
 assert.deepEqual(plist.NSUserActivityTypes,['ExistingActivity','INSendMessageIntent']);
 const entitlement=await mod(config,'entitlements',{'aps-environment':'production','existing.entitlement':true});
 assert.equal(entitlement['aps-environment'],'production');
 assert.equal(entitlement['existing.entitlement'],true);
 assert.deepEqual(entitlement['com.apple.security.application-groups'],['group.test.notifications']);
 assert.equal(plist.NotificationDevCommunicationAppGroup,'group.test.notifications');
 assert.equal(entitlement['com.apple.developer.usernotifications.communication'],true);
});
test('communication setup adds message intent once and defaults to disabled',async()=>{
 const config=configured(true);const plist=await mod(config,'infoPlist',{});
 assert.deepEqual(plist.NSUserActivityTypes,['INSendMessageIntent']);
 const plain=configured(undefined);
 assert.equal((await mod(plain,'infoPlist',{})).NSUserActivityTypes,undefined);
 assert.equal((await mod(plain,'entitlements',{}))['com.apple.developer.usernotifications.communication'],undefined);
});
test('opt-out preserves deliberately configured native capabilities',async()=>{
 const config=configured(false);
 assert.deepEqual((await mod(config,'infoPlist',{NSUserActivityTypes:['INSendMessageIntent']})).NSUserActivityTypes,['INSendMessageIntent']);
 assert.equal((await mod(config,'entitlements',{'com.apple.developer.usernotifications.communication':true}))['com.apple.developer.usernotifications.communication'],true);
});
test('invalid communication settings fail before generating a broken native project',async()=>{
 assert.throws(()=>configured('true'),/must be a boolean/);
 assert.throws(()=>plugin({name:'Test',slug:'test'},{projectId,iosCommunicationNotifications:true}),/require iosCommunicationAppGroup/);
 await assert.rejects(()=>mod(configured(true),'infoPlist',{NSUserActivityTypes:'ExistingActivity'}),/array of strings/);
});
