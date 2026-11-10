import React, { useEffect, useState } from 'react';
import { Button, Text, TextInput, View } from 'react-native';
import { LiveActivityCapabilities, LiveActivityState, NotificationDev } from '@notification.dev/expo';

type Client = Awaited<ReturnType<typeof NotificationDev.initialize>>;
export function LiveActivityPanel({ client }: { client: Client }) {
  const [capabilities, setCapabilities] = useState<LiveActivityCapabilities>();
  const [activities, setActivities] = useState<LiveActivityState[]>([]);
  const [provider, setProvider] = useState(process.env.EXPO_PUBLIC_LIVE_ACTIVITY_PROVIDER_ID ?? '');
  const [activityId, setActivityId] = useState('delivery-example');
  const [grant, setGrant] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    client.liveActivities.getCapabilities().then(value => { if (active) setCapabilities(value); })
      .catch(error => { if (active) setMessage(String(error)); });
    const listener = client.liveActivities.addListener(setActivities);
    return () => { active = false; listener.remove(); };
  }, [client]);
  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    try { await action(); setMessage('Done'); }
    catch (error) { setMessage(String(error)); }
    finally { setBusy(false); }
  }
  const disabled = busy || !capabilities?.supported;
  return <View style={{ gap: 12 }}>
    <Text style={{ fontSize: 20 }}>Live Activities</Text>
    <Text>{capabilities?.supported ? `Allowed: ${capabilities.activitiesEnabled}; remote start supported: ${capabilities.pushToStartSupported}` : 'Live Activities require iOS 16.1 or later.'}</Text>
    <Text>{message}</Text>
    <TextInput accessibilityLabel="APNs provider UUID" placeholder="APNs provider UUID" value={provider} onChangeText={setProvider} autoCapitalize="none" />
    <Button title="Configure Live Activities" disabled={disabled} onPress={() => run(() => client.liveActivities.configure({ providerConfigurationId: provider.trim() }))} />
    <TextInput accessibilityLabel="Activity ID" placeholder="Activity ID" value={activityId} onChangeText={setActivityId} autoCapitalize="none" />
    <TextInput accessibilityLabel="Fresh backend registration grant" placeholder="Fresh backend registration grant" value={grant} onChangeText={setGrant} secureTextEntry autoCapitalize="none" autoCorrect={false} />
    <Text>Embed the example SwiftUI widget first. Your backend issues grants for this installation and association; do not paste a project API key.</Text>
    <Button title="Start local activity" disabled={disabled} onPress={() => run(async () => {
      await client.liveActivities.start({ activityId: activityId.trim(), attributes: { name: 'Delivery test' }, content: { status: 'Preparing' }, registrationToken: grant.trim() });
      setGrant('');
    })} />
    <Button title="End local activity" disabled={disabled} onPress={() => run(() => client.liveActivities.end(activityId.trim(), { status: 'Delivered' }))} />
    {activities.map(activity => <View key={activity.nativeActivityId}>
      <Text selectable>{activity.activityId}: {activity.state} — {JSON.stringify(activity.content)}</Text>
      <Button title={`Select ${activity.activityId}`} onPress={() => setActivityId(activity.activityId)} />
    </View>)}
  </View>;
}
