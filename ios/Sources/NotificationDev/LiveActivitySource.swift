import ActivityKit
import Foundation

@MainActor
struct LiveActivityInstance {
    let id: String
    let metadata: LiveActivityRegistrationMetadata?
    let currentToken: () -> Data?
    let ended: () -> Bool
    let tokens: () -> AsyncStream<Data>
    let ends: () -> AsyncStream<Bool>

    @available(iOS 16.1, *)
    static func activityKit<Attributes: NotificationDevLiveActivityAttributes>(
        _ activity: Activity<Attributes>
    ) -> Self {
        Self(
            id: activity.id, metadata: activity.attributes.notification_dev,
            currentToken: { activity.pushToken },
            ended: { activity.activityState == .ended || activity.activityState == .dismissed },
            tokens: {
                activityStream { continuation in
                    for await token in activity.pushTokenUpdates {
                        guard !Task.isCancelled else { break }
                        continuation.yield(token)
                    }
                }
            },
            ends: {
                activityStream { continuation in
                    for await state in activity.activityStateUpdates {
                        guard !Task.isCancelled else { break }
                        continuation.yield(state == .ended || state == .dismissed)
                    }
                }
            }
        )
    }
}

@MainActor
struct LiveActivitySource {
    let type: String
    let existing: () -> [LiveActivityInstance]
    let incoming: () -> AsyncStream<LiveActivityInstance>
    let currentStartToken: () -> String?
    let startTokens: () -> AsyncStream<Void>
    let enabled: () -> Bool
    let authorizationUpdates: () -> AsyncStream<Void>

    @available(iOS 16.1, *)
    static func activityKit<Attributes: NotificationDevLiveActivityAttributes>(_: Attributes.Type) -> Self {
        Self(
            type: String(describing: Attributes.self),
            existing: { Activity<Attributes>.activities.map { .activityKit($0) } },
            incoming: {
                activityStream { continuation in
                    for await activity in Activity<Attributes>.activityUpdates {
                        guard !Task.isCancelled else { break }
                        continuation.yield(.activityKit(activity))
                    }
                }
            },
            currentStartToken: {
                if #available(iOS 17.2, *) {
                    return Activity<Attributes>.pushToStartToken.map { NotificationDev.hexToken($0) }
                }
                return nil
            },
            startTokens: {
                if #available(iOS 17.2, *) {
                    return activityStream { continuation in
                        for await _ in Activity<Attributes>.pushToStartTokenUpdates {
                            guard !Task.isCancelled else { break }
                            continuation.yield(())
                        }
                    }
                }
                return AsyncStream { $0.finish() }
            },
            enabled: { ActivityAuthorizationInfo().areActivitiesEnabled },
            authorizationUpdates: {
                activityStream { continuation in
                    for await _ in ActivityAuthorizationInfo().activityEnablementUpdates {
                        guard !Task.isCancelled else { break }
                        continuation.yield(())
                    }
                }
            }
        )
    }
}

@MainActor
private func activityStream<Value>(
    _ observe: @escaping @MainActor (AsyncStream<Value>.Continuation) async -> Void
) -> AsyncStream<Value> {
    AsyncStream { continuation in
        let task = Task { @MainActor in
            await observe(continuation)
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}
