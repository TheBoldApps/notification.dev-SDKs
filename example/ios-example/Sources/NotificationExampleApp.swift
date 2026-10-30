import ActivityKit
import NotificationDev
import SwiftUI
import UserNotifications

@main
struct NotificationExampleApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup { ContentView(model: delegate.model) }
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let model = ExampleModel()
    private var startup: Task<NotificationDev, Error>?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // ActivityKit can relaunch the simulator app without simctl's environment.
        // Remember only public configuration; credentials remain in SDK secure storage.
        let defaults = UserDefaults.standard
        for key in [
            "NOTIFICATION_DEV_PROJECT_ID", "NOTIFICATION_DEV_BASE_URL", "NOTIFICATION_DEV_LIVE_ACTIVITY_PROVIDER_ID",
        ] {
            if let value = ProcessInfo.processInfo.environment[key] {
                defaults.set(value, forKey: key)
            }
        }
        startup = Task {
            do {
                let client = try await NotificationDev.initialize(
                    SdkConfig(
                        // Replace this placeholder with your notification.dev project UUID.
                        projectId: defaults.string(forKey: "NOTIFICATION_DEV_PROJECT_ID")
                            ?? "YOUR_PUBLIC_PROJECT_UUID",
                        baseURL: URL(
                            string: defaults.string(forKey: "NOTIFICATION_DEV_BASE_URL")
                                ?? "https://app.notification.dev/")!,
                        allowHTTP: _isDebugAssertConfiguration(),
                        loggingEnabled: _isDebugAssertConfiguration()
                    ))
                if #available(iOS 16.1, *),
                    let provider = defaults.string(forKey: "NOTIFICATION_DEV_LIVE_ACTIVITY_PROVIDER_ID")
                        ?? Bundle.main.object(forInfoDictionaryKey: "LiveActivityProviderConfigurationId") as? String,
                    UUID(uuidString: provider) != nil
                {
                    try client.liveActivities.setupDefault(providerConfigurationId: provider)
                    try client.liveActivities.setup(DeliveryAttributes.self, providerConfigurationId: provider)
                }
                model.attach(client)

                return client
            } catch {
                model.message = "Initialization failed"
                model.showError(error)
                throw error
            }
        }

        return true
    }

    func application(
        _ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task {
            do {
                try await startup?.value.didRegisterForRemoteNotifications(deviceToken: deviceToken)
            } catch { model.showError(error) }
        }
    }

    func application(
        _ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task {
            do { try await startup?.value.didFailToRegisterForRemoteNotifications() } catch {
                model.showError(error)
            }
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        Task { @MainActor in
            let client = try? await startup?.value
            let options = await client?.presentationOptions(for: notification)
            completionHandler(options ?? [.banner, .sound])
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        Task { @MainActor in
            let client = try? await startup?.value
            _ = await client?.handleResponse(response)
            completionHandler()
        }
    }
}

@MainActor
final class ExampleModel: ObservableObject {
    @Published var state: SdkState?
    @Published var opens: [NotificationOpen] = []
    @Published var message = "Starting…"
    @Published var errorMessage: String?
    private(set) var client: NotificationDev?
    private var tasks: [Task<Void, Never>] = []

    func showError(_ error: Error) {
        if let error = error as? SdkError {
            errorMessage = "\(error.code): \(error.message)"
        } else {
            errorMessage = error.localizedDescription
        }
    }

    func attach(_ client: NotificationDev) {
        self.client = client
        message = "Initialized"
        tasks = [
            Task { [weak self] in for await state in client.states { self?.state = state } },
            Task { [weak self] in for await opens in client.pendingOpens { self?.opens = opens } },
            Task { [weak self] in
                for await error in client.diagnostics { self?.showError(error) }
            },
        ]
    }

    func run(_ action: @escaping (NotificationDev) async throws -> Void) {
        guard let client else { return }
        Task {
            do {
                try await action(client)
                message = "Done"
                errorMessage = nil
            } catch { showError(error) }
        }
    }
}

struct ContentView: View {
    @ObservedObject var model: ExampleModel
    @State private var externalId = ""
    @State private var email = ""
    @State private var tag = ""
    @State private var emailOptedIn = true
    @State private var eventName = ""
    @State private var showingEventDialog = false
    @State private var activityId = "order-123"
    @State private var registrationToken = ""

    var body: some View {
        NavigationView {
            Form {
                Section("State") {
                    Text(model.message).textSelection(.enabled)
                    if let state = model.state {
                        Text("Installation: \(state.installationId ?? "pending")").textSelection(.enabled)
                        Text("User: \(state.externalId ?? "anonymous")")
                        Text(
                            "Sync: \(state.syncing ? "running" : "idle"), pending: \(state.hasPendingChanges.description)"
                        )
                        Text(
                            "Push: \(state.pushRegistration.status), permission: \(state.permission.areNotificationsEnabled.description)"
                        )
                        Text("Email: \(state.user.email?.address ?? "none")")
                        Text("Tags: \(String(describing: state.user.tags))")
                    }
                    if let error = model.errorMessage {
                        Text(error).textSelection(.enabled)
                    } else if let error = model.state?.lastError {
                        Text("\(error.code): \(error.message)").textSelection(.enabled)
                    }
                    Button("Refresh") { model.run { _ = try await $0.refresh() } }
                }
                Section("Identity") {
                    TextField("External user ID", text: $externalId).textInputAutocapitalization(.never)
                    Button("Log in") { model.run { try await $0.login(externalId) } }
                    Button("Log out") { model.run { try await $0.logout() } }
                }
                Section("Email and tags") {
                    TextField("Email", text: $email).textInputAutocapitalization(.never).keyboardType(
                        .emailAddress)
                    Toggle("Email opt-in", isOn: $emailOptedIn)
                    Button("Set email") { model.run { try await $0.setEmail(email, optedIn: emailOptedIn) } }
                    Button("Update email preference") {
                        model.run { try await $0.setEmailOptedIn(emailOptedIn) }
                    }
                    Button("Remove email") { model.run { try await $0.removeEmail() } }
                    TextField("Plan tag", text: $tag)
                    Button("Set plan tag") { model.run { try await $0.setTags(["plan": .string(tag)]) } }
                    Button("Remove plan tag") { model.run { try await $0.removeTags(["plan"]) } }
                }
                Section("Notifications and events") {
                    Button("Request permission") {
                        model.run { _ = try await $0.requestPushPermission(settingsFallback: true) }
                    }
                    Button("Open settings") { model.run { _ = await $0.openPushSettings() } }
                    Button("Opt in to push") { model.run { try await $0.setPushOptedIn(true) } }
                    Button("Opt out of push") { model.run { try await $0.setPushOptedIn(false) } }
                    Button("Track event") {
                        eventName = ""
                        showingEventDialog = true
                    }
                }
                if #available(iOS 16.2, *) {
                    Section("Live Activities") {
                        Text(
                            "Configure LiveActivityProviderConfigurationId in Info.plist. Get a fresh grant from your authenticated backend for each start."
                        )
                        TextField("Activity ID", text: $activityId).textInputAutocapitalization(.never)
                        SecureField("Registration grant", text: $registrationToken)
                        Button("Start default activity") {
                            model.run { client in
                                try await client.liveActivities.startDefault(
                                    activityId, attributes: ["orderId": .string(activityId)],
                                    content: ["status": .string("Preparing")], registrationToken: registrationToken)
                            }
                        }
                        Button("Start typed delivery") {
                            model.run { client in
                                guard client.state.associationId != nil && !client.state.identityChangePending else {
                                    throw NSError(
                                        domain: "Example", code: 1,
                                        userInfo: [NSLocalizedDescriptionKey: "Wait for installation registration"])
                                }
                                _ = try Activity<DeliveryAttributes>.request(
                                    attributes: DeliveryAttributes(
                                        orderId: activityId,
                                        notification_dev: .create(
                                            activityId: activityId, registrationToken: registrationToken)),
                                    content: ActivityContent(
                                        state: .init(status: "Preparing", minutesRemaining: 25), staleDate: nil),
                                    pushType: .token)
                            }
                        }
                        Button("End this activity locally") {
                            model.run { client in
                                for activity in Activity<DefaultLiveActivityAttributes>.activities
                                where activity.attributes.notification_dev?.activityId == activityId {
                                    await activity.end(
                                        ActivityContent(
                                            state: .init(data: ["status": .string("Delivered")]), staleDate: nil),
                                        dismissalPolicy: .immediate)
                                }
                                for activity in Activity<DeliveryAttributes>.activities
                                where activity.attributes.notification_dev?.activityId == activityId {
                                    await activity.end(
                                        ActivityContent(
                                            state: .init(status: "Delivered", minutesRemaining: 0), staleDate: nil),
                                        dismissalPolicy: .immediate)
                                }
                                try await client.liveActivities.exit(activityId)
                            }
                        }
                    }
                }
                Section("Pending opens") {
                    ForEach(model.opens) { open in
                        VStack(alignment: .leading) {
                            Text(open.notification.title)
                            Text(open.notification.deepLink ?? "No deep link")
                            Button("Acknowledge") {
                                model.run { try await $0.acknowledgeOpen(open.interactionId) }
                            }
                        }
                    }
                }
            }
            .navigationTitle("notification.dev")
        }
        .sheet(isPresented: $showingEventDialog) {
            NavigationView {
                Form {
                    TextField("Event name", text: $eventName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .navigationTitle("Track event")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showingEventDialog = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Track") {
                            let name = eventName.trimmingCharacters(in: .whitespacesAndNewlines)

                            model.run { try await $0.track(name) }
                            showingEventDialog = false
                        }
                        .disabled(eventName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .navigationViewStyle(.stack)
        }
    }
}
