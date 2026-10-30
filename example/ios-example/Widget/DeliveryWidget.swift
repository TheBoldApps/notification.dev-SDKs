import ActivityKit
import NotificationDevActivityKit
import SwiftUI
import WidgetKit

@main
struct ExampleWidgets: WidgetBundle {
    var body: some Widget {
        DeliveryWidget()
        DefaultActivityWidget()
    }
}

struct DeliveryWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DeliveryAttributes.self) { context in
            VStack(alignment: .leading) {
                Text(context.attributes.orderId).font(.headline)
                Text(context.isStale ? "Open the app to refresh" : context.state.status)
                Text("\(context.state.minutesRemaining) minutes remaining")
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) { Text(context.state.status) }
            } compactLeading: {
                Text("Delivery")
            } compactTrailing: {
                Text("\(context.state.minutesRemaining)m")
            } minimal: {
                Text("\(context.state.minutesRemaining)m")
            }
        }
    }
}

struct DefaultActivityWidget: Widget {
    private func title(_ attributes: DefaultLiveActivityAttributes) -> String {
        if case .string(let value) = attributes.data["name"] { return value }
        return "Status"
    }

    private func value(_ state: DefaultLiveActivityAttributes.ContentState) -> String {
        if case .number(let value) = state.data["number"] {
            return value.formatted(.number.precision(.fractionLength(0)))
        }
        if case .string(let value) = state.data["status"] { return value }
        return "Waiting for an update"
    }

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DefaultLiveActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                Text(title(context.attributes)).font(.headline)
                Text(value(context.state)).font(.system(size: 44, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
            .padding()
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text(title(context.attributes)) }
                DynamicIslandExpandedRegion(.trailing) { Text(value(context.state)).monospacedDigit() }
            } compactLeading: {
                Text(title(context.attributes))
            } compactTrailing: {
                Text(value(context.state)).monospacedDigit()
            } minimal: {
                Text(value(context.state)).monospacedDigit().minimumScaleFactor(0.5)
            }
        }
    }
}
