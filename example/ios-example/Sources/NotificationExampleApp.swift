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
        startup = Task {
            do {
                let client = try await NotificationDev.initialize(
                    SdkConfig(
                        // Replace this placeholder with your notification.dev project UUID.
                        projectId: "YOUR_PUBLIC_PROJECT_UUID",
                        allowLocalhostHTTP: _isDebugAssertConfiguration(),
                        loggingEnabled: _isDebugAssertConfiguration()
                    ))
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
