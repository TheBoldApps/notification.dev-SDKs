import ActivityKit
import NotificationDevActivityKit
import SwiftUI
import WidgetKit

@main
struct NotificationDevLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DefaultLiveActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                Text(title(context.attributes)).font(.headline)
                Text(context.isStale ? "Open the app to refresh" : status(context.state)).font(.title)
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text(status(context.state)) }
            } compactLeading: {
                Text(title(context.attributes))
            } compactTrailing: {
                Text(status(context.state))
            } minimal: {
                Text("•")
            }
        }
    }

    private func title(_ attributes: DefaultLiveActivityAttributes) -> String {
        if case .string(let value) = attributes.data["name"] { return value }
        return "Activity"
    }

    private func status(_ content: DefaultLiveActivityAttributes.ContentState) -> String {
        if case .string(let value) = content.data["status"] { return value }
        return "Waiting for an update"
    }
}
