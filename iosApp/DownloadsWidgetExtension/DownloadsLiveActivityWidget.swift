import ActivityKit
import SwiftUI
import WidgetKit

struct DownloadsLiveActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        let status: String
        let progressPercent: Int
        let transferredText: String
    }

    let downloadId: String
    let title: String
    let subtitle: String
}

@available(iOSApplicationExtension 16.1, *)
struct DownloadsLiveActivityWidget: Widget {
    private let downloadsUrl = URL(string: "lentra://downloads")
    private let appBlue = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadsLiveActivityAttributes.self) { context in
            DownloadActivityLockScreenView(context: context)
                .widgetURL(downloadsUrl)
        } dynamicIsland: { context in
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Text(statusLabel(context.state.status))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.88))
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(progressLabel(context.state.progressPercent))
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.white)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(context.attributes.title)
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.86)
                            .truncationMode(.tail)
                        Text(context.attributes.subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(1)
                            .minimumScaleFactor(0.9)
                            .truncationMode(.tail)
                        ProgressView(value: normalizedProgress(context.state.progressPercent))
                            .progressViewStyle(.linear)
                            .tint(appBlue)
                        HStack {
                            Text(context.state.transferredText)
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.white.opacity(0.86))
                            Spacer(minLength: 6)
                            Label(statusLabel(context.state.status), systemImage: "arrow.down")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.86))
                        }
                    }
                    .padding(.top, 4)
                    .padding(.horizontal, 10)
                }
            } compactLeading: {
                AccentGlyphView()
            } compactTrailing: {
                Text(progressLabel(context.state.progressPercent))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(appBlue)
            } minimal: {
                AccentGlyphView()
            }
            .widgetURL(downloadsUrl)
            .keylineTint(appBlue)
        }
    }

    private func progressLabel(_ progressPercent: Int) -> String {
        if progressPercent < 0 { return "--%" }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }

}

@available(iOSApplicationExtension 16.1, *)
private struct DownloadActivityLockScreenView: View {
    let context: ActivityViewContext<DownloadsLiveActivityAttributes>

    var body: some View {
        ZStack {
            LinearGradient(
                colors: backgroundGradientColors,
                startPoint: .topLeading,
                endPoint: .bottomTrailing,
            )

            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 10) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(context.attributes.title)
                            .font(.headline.weight(.semibold))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(context.attributes.subtitle)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.82))
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 10)
                    Text(progressLabel(context.state.progressPercent))
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.top, 1)
                }

                ProgressView(value: normalizedProgress(context.state.progressPercent))
                    .progressViewStyle(.linear)
                    .tint(.white)

                HStack {
                    Label(statusLabel(context.state.status), systemImage: "arrow.down")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.86))
                    Spacer()
                    Text(context.state.transferredText)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.white.opacity(0.86))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, minHeight: 168, maxHeight: .infinity, alignment: .top)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.white.opacity(0.10), lineWidth: 1),
        )
        .activityBackgroundTint(.clear)
        .activitySystemActionForegroundColor(.white)
    }

    private func progressLabel(_ progressPercent: Int) -> String {
        if progressPercent < 0 { return "--%" }
        return "\(max(0, min(100, progressPercent)))%"
    }

    private func normalizedProgress(_ progressPercent: Int) -> Double {
        guard progressPercent >= 0 else { return 0 }
        return min(max(Double(progressPercent) / 100.0, 0), 1)
    }

    private func statusLabel(_ status: String) -> String {
        switch status.lowercased() {
        case "downloading": return "Downloading"
        case "paused": return "Paused"
        case "failed": return "Failed"
        default: return "Active"
        }
    }

    private var backgroundGradientColors: [Color] {
        let accent = Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0)
        return [
            accent.opacity(0.92),
            accent.opacity(0.42),
            Color.black.opacity(0.97),
        ]
    }
}

private struct AccentGlyphView: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color(red: 30.0 / 255.0, green: 136.0 / 255.0, blue: 229.0 / 255.0).opacity(0.88))
            Image(systemName: "arrow.down")
                .font(.caption2.bold())
                .foregroundStyle(.white)
        }
        .frame(width: 22, height: 22)
    }
}
