import SwiftUI
import WidgetKit

struct ActionsEntry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
}

struct ActionsProvider: TimelineProvider {
    func placeholder(in context: Context) -> ActionsEntry {
        ActionsEntry(date: .now, snapshot: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (ActionsEntry) -> Void) {
        let snapshot = context.isPreview ? (SnapshotStore.load() ?? .sample) : SnapshotStore.load()
        completion(ActionsEntry(date: .now, snapshot: snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ActionsEntry>) -> Void) {
        // The app reloads timelines after every poll; this is only a fallback.
        let entry = ActionsEntry(date: .now, snapshot: SnapshotStore.load())
        completion(Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(15 * 60))))
    }
}

struct ActionsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "com.bidaide.notificator.actions", provider: ActionsProvider()) { entry in
            ActionsWidgetView(entry: entry)
                .containerBackground(for: .widget) { CardBackground() }
        }
        .configurationDisplayName("GitHub Actions")
        .description("Tus workflows en marcha.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct ActionsWidgetView: View {
    let entry: ActionsEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot {
            Group {
                switch family {
                case .systemSmall:
                    ActionsSummary(snapshot: snapshot)
                case .systemMedium:
                    HStack(alignment: .top, spacing: 14) {
                        ActionsSummary(snapshot: snapshot).frame(width: 96)
                        CardColumns(items: snapshot.activeRuns, columns: 1, perColumn: 3,
                                    emptyText: "Nada en marcha") { RunRow(run: $0) }
                    }
                default:
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 10) {
                            CardBadge(title: "GitHub Actions", symbol: "play.circle")
                            RunState(snapshot: snapshot)
                        }
                        Divider().opacity(0.5)
                        CardColumns(items: snapshot.activeRuns, columns: family == .systemExtraLarge ? 2 : 1, perColumn: 5,
                                    emptyText: "Nada en marcha") { RunRow(run: $0) }
                    }
                }
            }
            .widgetURL(snapshot.activeRuns.first.map { DeepLink.open($0.url) })
        } else {
            LoadingHint(symbol: "play.circle")
        }
    }
}

/// Left column: badge and what is going on right now.
private struct ActionsSummary: View {
    let snapshot: Snapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CardBadge(title: "Actions", symbol: "play.circle")
            Spacer(minLength: 6)
            RunState(snapshot: snapshot)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// How many runs are queued or running right now.
private struct RunState: View {
    let snapshot: Snapshot

    var body: some View {
        let active = snapshot.activeRuns.count
        BigCount(caption: "en marcha", captionColor: active > 0 ? .orange : .secondary) { Text("\(active)") }
    }
}

private struct RunRow: View {
    let run: WorkflowRun

    var body: some View {
        let outcome = run.outcome
        CardRow(symbol: outcome.symbol, color: outcome.color, title: run.workflow,
                subtitle: "\(run.repoName) · \(run.branch)", url: run.url) {
            VStack(alignment: .trailing, spacing: 1) {
                if outcome == .running {
                    // Live elapsed time; WidgetKit keeps it ticking without new timeline entries.
                    Text(run.startedAt, style: .timer)
                        .font(.system(size: 12, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 56, alignment: .trailing)
                } else {
                    Text(run.updatedAt.shortAgo)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Text(outcome.label)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(outcome.color)
            }
            .lineLimit(1)
        }
    }
}
