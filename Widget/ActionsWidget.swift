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
        .description("Tus workflows en curso y cómo acabaron los últimos.")
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
                    ActionsSummary(snapshot: snapshot, bars: 12)
                case .systemMedium:
                    HStack(alignment: .top, spacing: 14) {
                        ActionsSummary(snapshot: snapshot, bars: 10).frame(width: 96)
                        CardColumns(items: snapshot.workflowRuns, columns: 1, perColumn: 3,
                                    emptyText: "Sin ejecuciones recientes") { RunRow(run: $0) }
                    }
                default:
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .bottom) {
                            VStack(alignment: .leading, spacing: 10) {
                                CardBadge(title: "GitHub Actions", symbol: "play.circle")
                                RunState(snapshot: snapshot)
                            }
                            Spacer()
                            HistoryBars(runs: snapshot.workflowRuns, count: family == .systemExtraLarge ? 30 : 16)
                        }
                        Divider().opacity(0.5)
                        CardColumns(items: snapshot.workflowRuns, columns: family == .systemExtraLarge ? 2 : 1, perColumn: 5,
                                    emptyText: "Sin ejecuciones recientes") { RunRow(run: $0) }
                    }
                }
            }
            .widgetURL(snapshot.workflowRuns.first.map { DeepLink.open($0.url) })
        } else {
            LoadingHint(symbol: "play.circle")
        }
    }
}

/// Left column: badge, what is going on right now and the recent history.
private struct ActionsSummary: View {
    let snapshot: Snapshot
    let bars: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            CardBadge(title: "Actions", symbol: "play.circle")
            Spacer(minLength: 6)
            RunState(snapshot: snapshot)
            Spacer(minLength: 6)
            HistoryBars(runs: snapshot.workflowRuns, count: bars)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Running first, then failing, then all green.
private struct RunState: View {
    let snapshot: Snapshot

    var body: some View {
        let active = snapshot.activeRuns.count
        let failing = snapshot.failingRuns.count
        if active > 0 {
            BigCount(caption: "en curso", captionColor: .orange) { Text("\(active)") }
        } else if failing > 0 {
            BigCount(caption: "fallando", captionColor: .red) { Text("\(failing)") }
        } else if snapshot.workflowRuns.isEmpty {
            BigCount(caption: "sin ejecuciones") { Text("–") }
        } else {
            BigCount(caption: "todo en verde", captionColor: .green) {
                Image(systemName: "checkmark").foregroundStyle(.green)
            }
        }
    }
}

/// One bar per run, oldest on the left. Cancelled and skipped runs are shorter.
private struct HistoryBars: View {
    let runs: [WorkflowRun]
    let count: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(runs.prefix(count).reversed()) { run in
                RoundedRectangle(cornerRadius: 2.5)
                    .fill(run.outcome.color)
                    .frame(width: 6, height: run.outcome == .cancelled ? 9 : 22)
            }
        }
        .frame(height: 22, alignment: .bottom)
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
