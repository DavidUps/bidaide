import AppIntents
import SwiftUI
import WidgetKit

@main
struct BidaideWidgetBundle: WidgetBundle {
    var body: some Widget {
        PRWidget()
    }
}

// MARK: Configuration

enum WidgetSection: String, AppEnum {
    case pending, review, answer, commented, assigned, mine

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Sección"
    static var caseDisplayRepresentations: [WidgetSection: DisplayRepresentation] = [
        .pending: "Todo lo pendiente",
        .review: "Revisar",
        .answer: "Responder",
        .commented: "Comentadas",
        .assigned: "Asignadas",
        .mine: "Mis PR",
    ]

    var category: PRCategory? { PRCategory(rawValue: rawValue) }
}

struct PRWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Pull requests"
    static var description = IntentDescription("Elige qué pull requests mostrar.")

    @Parameter(title: "Sección", default: .pending)
    var section: WidgetSection
}

// MARK: Timeline

struct PREntry: TimelineEntry {
    let date: Date
    let snapshot: Snapshot?
    let section: WidgetSection
}

struct PRProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PREntry {
        PREntry(date: .now, snapshot: .sample, section: .pending)
    }

    func snapshot(for configuration: PRWidgetIntent, in context: Context) async -> PREntry {
        let snapshot = context.isPreview ? (SnapshotStore.load() ?? .sample) : SnapshotStore.load()
        return PREntry(date: .now, snapshot: snapshot, section: configuration.section)
    }

    func timeline(for configuration: PRWidgetIntent, in context: Context) async -> Timeline<PREntry> {
        // The app reloads timelines after every poll; this is only a fallback.
        let entry = PREntry(date: .now, snapshot: SnapshotStore.load(), section: configuration.section)
        return Timeline(entries: [entry], policy: .after(.now.addingTimeInterval(15 * 60)))
    }
}

struct PRWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "com.bidaide.notificator.prs", intent: PRWidgetIntent.self, provider: PRProvider()) { entry in
            PRWidgetView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Pull requests")
        .description("Lo que tienes que revisar, responder y lo que te han comentado.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

// MARK: Views

struct PRWidgetView: View {
    let entry: PREntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot {
            switch (entry.section.category, family) {
            case (nil, .systemSmall): CountsGrid(snapshot: snapshot)
            case (nil, .systemMedium): PendingMedium(snapshot: snapshot)
            case (nil, _): PendingList(snapshot: snapshot, limit: family == .systemExtraLarge ? 14 : 8)
            case (let category?, .systemSmall): CategorySmall(snapshot: snapshot, category: category)
            case (let category?, _):
                CategoryList(snapshot: snapshot, category: category, limit: family == .systemMedium ? 3 : 8)
            }
        } else {
            VStack(spacing: 6) {
                Image(systemName: "arrow.triangle.pull").font(.title2)
                Text("Abre Bidaide Notificator para cargar tus PR")
                    .font(.caption).multilineTextAlignment(.center).foregroundStyle(.secondary)
            }
        }
    }
}

private struct CountsGrid: View {
    let snapshot: Snapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            WidgetHeader(title: "Pendiente", count: snapshot.actionCount, error: snapshot.error)
            Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 8) {
                GridRow {
                    CountCell(category: .review, count: snapshot.items(.review).count)
                    CountCell(category: .answer, count: snapshot.items(.answer).count)
                }
                GridRow {
                    CountCell(category: .commented, count: snapshot.items(.commented).count)
                    CountCell(category: .assigned, count: snapshot.items(.assigned).count)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetURL(DeepLink.open("https://github.com/pulls/review-requested"))
    }
}

private struct CountCell: View {
    let category: PRCategory
    let count: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 3) {
                Image(systemName: category.symbol).font(.caption2)
                Text("\(count)").font(.title3.weight(.semibold)).monospacedDigit()
            }
            .foregroundStyle(count > 0 ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            Text(category.title).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

private struct PendingMedium: View {
    let snapshot: Snapshot

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            CountsGrid(snapshot: snapshot)
                .frame(width: 120)
            VStack(alignment: .leading, spacing: 6) {
                let items = snapshot.actionItems.prefix(3)
                if items.isEmpty {
                    AllClear()
                } else {
                    ForEach(Array(items), id: \.1.url) { category, pr in
                        WidgetRow(pullRequest: pr, category: category, compact: true)
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }
}

private struct PendingList: View {
    let snapshot: Snapshot
    let limit: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            WidgetHeader(title: "Pull requests pendientes", count: snapshot.actionCount, error: snapshot.error)
            let items = snapshot.actionItems.prefix(limit)
            if items.isEmpty {
                AllClear()
            } else {
                ForEach(Array(items), id: \.1.url) { category, pr in
                    WidgetRow(pullRequest: pr, category: category, compact: false)
                }
            }
            Spacer(minLength: 0)
            UpdatedFooter(date: snapshot.updatedAt)
        }
    }
}

private struct CategorySmall: View {
    let snapshot: Snapshot
    let category: PRCategory

    var body: some View {
        let items = snapshot.items(category)
        VStack(alignment: .leading, spacing: 4) {
            Label(category.title, systemImage: category.symbol)
                .font(.caption.weight(.semibold)).foregroundStyle(.tint)
            Text("\(items.count)").font(.system(size: 40, weight: .bold, design: .rounded)).monospacedDigit()
            Spacer(minLength: 0)
            if let first = items.first {
                Text(first.title).font(.caption2).lineLimit(2)
                Text("\(first.repo)#\(first.number)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            } else {
                Text(category.subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetURL(items.first.map { DeepLink.open($0.url) })
    }
}

private struct CategoryList: View {
    let snapshot: Snapshot
    let category: PRCategory
    let limit: Int

    var body: some View {
        let items = snapshot.items(category)
        VStack(alignment: .leading, spacing: 6) {
            WidgetHeader(title: category.title, count: items.count, error: snapshot.error, symbol: category.symbol)
            if items.isEmpty {
                AllClear()
            } else {
                ForEach(items.prefix(limit)) { pr in
                    WidgetRow(pullRequest: pr, category: nil, compact: limit <= 3)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

private struct WidgetHeader: View {
    let title: String
    let count: Int
    let error: String?
    var symbol = "arrow.triangle.pull"

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: symbol).foregroundStyle(.tint)
            Text(title).font(.caption.weight(.semibold)).lineLimit(1)
            Spacer(minLength: 0)
            if error != nil {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange).font(.caption2)
            }
            Text("\(count)").font(.caption.weight(.bold)).monospacedDigit()
        }
    }
}

private struct WidgetRow: View {
    let pullRequest: PullRequest
    let category: PRCategory?
    let compact: Bool

    var body: some View {
        Link(destination: DeepLink.open(pullRequest.url)) {
            HStack(alignment: .top, spacing: 6) {
                Image(systemName: category?.symbol ?? checksSymbol)
                    .font(.caption2)
                    .foregroundStyle(category == nil ? checksColor : .accentColor)
                    .frame(width: 12)
                    .padding(.top, 1)
                VStack(alignment: .leading, spacing: 1) {
                    Text(pullRequest.title).font(.caption).lineLimit(1)
                    Text(metadata).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }

    private var metadata: String {
        var parts = ["\(pullRequest.repo.split(separator: "/").last ?? "")#\(pullRequest.number)"]
        if let detail = pullRequest.detail { parts.append(detail) }
        else if !pullRequest.author.isEmpty, !compact { parts.append("@\(pullRequest.author)") }
        return parts.joined(separator: " · ")
    }

    private var checksSymbol: String {
        switch pullRequest.checks {
        case "SUCCESS": "checkmark.circle.fill"
        case "FAILURE", "ERROR": "xmark.circle.fill"
        case "PENDING", "EXPECTED": "clock.fill"
        default: "circle.dashed"
        }
    }

    private var checksColor: Color {
        switch pullRequest.checks {
        case "SUCCESS": .green
        case "FAILURE", "ERROR": .red
        case "PENDING", "EXPECTED": .orange
        default: .secondary
        }
    }
}

private struct AllClear: View {
    var body: some View {
        Label("Nada pendiente", systemImage: "checkmark.seal")
            .font(.caption).foregroundStyle(.green)
    }
}

private struct UpdatedFooter: View {
    let date: Date

    var body: some View {
        Text("Actualizado \(date, style: .relative)")
            .font(.caption2).foregroundStyle(.tertiary)
    }
}
