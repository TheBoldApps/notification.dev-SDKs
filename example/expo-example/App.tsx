import React, { useEffect, useState } from 'react';
import { Button, ScrollView, Text, TextInput, View } from 'react-native';
import { NotificationDev, SdkState, Subscription } from '@notification.dev/expo';

export default function App() {
  const [state, setState] = useState<SdkState>();
  const [message, setMessage] = useState('Starting…');
  const [externalId, setExternalId] = useState('');
  const [email, setEmail] = useState('');
  const [opened, setOpened] = useState<string[]>([]);
  const [client, setClient] = useState<Awaited<ReturnType<typeof NotificationDev.initialize>>>();
  useEffect(() => {
    let active = true;
    const subscriptions: Subscription[] = [];
    NotificationDev.initialize().then(sdk => {
      if (!active) return;
      setClient(sdk);
      setMessage('Ready');
      subscriptions.push(sdk.addListener('states', setState));
      subscriptions.push(sdk.addListener('diagnostics', error => setMessage(`${error.code}: ${error.message}`)));
      subscriptions.push(sdk.addListener('pendingOpens', opens => {
        setOpened(opens.map(open => `${open.interactionId}: ${open.notification.title}`));
      }));
    }).catch(error => { if (active) setMessage(String(error)); });
    return () => { active = false; subscriptions.forEach(subscription => subscription.remove()); };
  }, []);
  async function run(action: () => Promise<unknown>) {
    try { const result = await action(); setMessage(JSON.stringify(result) ?? 'Saved locally'); }
    catch (error) { setMessage(String(error)); }
  }
  return <ScrollView contentContainerStyle={{ padding: 24, paddingTop: 64, gap: 12 }}>
    <Text style={{ fontSize: 26, fontWeight: 'bold' }}>notification.dev</Text>
    <Text selectable>{message}</Text>
    {client && <>
      <Button title="Request push permission" onPress={() => run(() => client.requestPushPermission())} />
      <Button title="Open notification settings" onPress={() => run(() => client.openPushSettings())} />
      <TextInput accessibilityLabel="External ID" placeholder="Opaque external ID" value={externalId} onChangeText={setExternalId} />
      <Button title="Log in" onPress={() => run(() => client.login(externalId))} />
      <Button title="Log out" onPress={() => run(() => client.logout())} />
      <TextInput accessibilityLabel="Email" placeholder="Email address" value={email} onChangeText={setEmail} autoCapitalize="none" keyboardType="email-address" />
      <Button title="Subscribe email" onPress={() => run(() => client.setEmail(email, true))} />
      <Button title="Set example tag" onPress={() => run(() => client.setTags({ source: 'expo-example' }))} />
      <Button title="Track example event" onPress={() => run(() => client.track('example.button_clicked', { source: 'expo' }))} />
      <Button title={state?.pushOptedIn ? 'Opt out of push' : 'Opt into push'} onPress={() => run(() => client.setPushOptedIn(!state?.pushOptedIn))} />
      <Button title="Refresh" onPress={() => run(() => client.refresh())} />
      {opened.map(open => <View key={open}>
        <Text>{open}</Text>
        <Button title="Acknowledge handled open" onPress={() => run(() => client.acknowledgeOpen(open.split(':')[0]))} />
      </View>)}
    </>}
    <Text selectable>{JSON.stringify(state, null, 2)}</Text>
  </ScrollView>;
}
