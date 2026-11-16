import SwiftUI
import WidgetKit

// ActivityKit only exists on iOS 16.1+. iOS 15 still receives a
// working WidgetKit extension that opens the app's Downloads page.
@main
struct DownloadsWidgetBundle: WidgetBundle {
    @WidgetBundleBuilder
    var body: some Widget {
        if #available(iOSApplicationExtension 16.1, *) {
            DownloadsLiveActivityWidget()
        } else {
            DownloadsShortcutWidget()
        }
    }
}

private struct DownloadsShortcutEntry: TimelineEntry {
    let date: Date
}

private struct DownloadsShortcutProvider: TimelineProvider {
    func placeholder(in context: Context) -> DownloadsShortcutEntry {
        DownloadsShortcutEntry(date: Date())
    }

    func getSnapshot(in context: Context, completion: @escaping (DownloadsShortcutEntry) -> Void) {
        completion(DownloadsShortcutEntry(date: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DownloadsShortcutEntry>) -> Void) {
        completion(Timeline(entries: [DownloadsShortcutEntry(date: Date())], policy: .never))
    }
}

private struct DownloadsShortcutWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: "com.nuvio.downloads.shortcut",
            provider: DownloadsShortcutProvider()
        ) { _ in
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.title)
                    .foregroundColor(.blue)
                Text("Nuvio Downloads")
                    .font(.headline)
                Text("Open your downloads")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .widgetURL(URL(string: "nuvio://downloads"))
        }
        .configurationDisplayName("Nuvio Downloads")
        .description("Quickly open the Downloads page in Nuvio.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
