import AppIntents
import SwiftUI
import WidgetKit

@main
struct BidaideWidgetBundle: WidgetBundle {
    var body: some Widget {
        PRWidget()
        ActionsWidget()
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
                .containerBackground(for: .widget) { CardBackground() }
        }
        .configurationDisplayName("Pull requests")
        .description("Lo que tienes que revisar, responder y lo que te han comentado.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

// MARK: Content

/// What the widget shows for the chosen section, independent of the size.
private struct PRContent {
    struct Item: Identifiable {
        let category: PRCategory
        let pullRequest: PullRequest
        var id: String { pullRequest.url }
    }

    let snapshot: Snapshot
    let section: WidgetSection

    var title: String { section.category?.title ?? "GitHub" }

    var items: [Item] {
        if let category = section.category {
            return snapshot.items(category).map { Item(category: category, pullRequest: $0) }
        }
        return snapshot.actionItems.map { Item(category: $0.0, pullRequest: $0.1) }
    }

    var count: Int { section.category.map { snapshot.items($0).count } ?? snapshot.actionCount }

    var caption: String {
        switch section.category {
        case nil: "pendientes"
        case .review: "por revisar"
        case .answer: "por responder"
        case .commented: "sin leer"
        case .assigned: "asignadas"
        case .mine: "abiertas"
        }
    }

    var emptyText: String { section == .mine ? "Sin PR abiertas" : "Nada pendiente" }

    /// Check states of the user's own PRs, for the dots in the corner.
    var ownChecks: [String?] { snapshot.items(.mine).prefix(6).map(\.checks) }

    var url: String {
        switch section {
        case .pending, .review: "https://github.com/pulls/review-requested"
        case .assigned: "https://github.com/pulls/assigned"
        default: "https://github.com/pulls"
        }
    }
}

// MARK: Views

struct PRWidgetView: View {
    let entry: PREntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot {
            let content = PRContent(snapshot: snapshot, section: entry.section)
            Group {
                switch family {
                case .systemSmall:
                    PRSummary(content: content)
                case .systemMedium:
                    HStack(alignment: .top, spacing: 16) {
                        PRSummary(content: content).frame(width: 112)
                        CardColumns(items: content.items, columns: 1, perColumn: 3, emptyText: content.emptyText) { PRCardRow(item: $0) }
                    }
                default:
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .bottom) {
                            VStack(alignment: .leading, spacing: 10) {
                                PRBadge(content: content)
                                BigCount(caption: content.caption) { Text("\(content.count)") }
                            }
                            Spacer()
                            OwnChecks(states: content.ownChecks)
                        }
                        Divider().opacity(0.5)
                        CardColumns(items: content.items, columns: family == .systemExtraLarge ? 2 : 1, perColumn: 5,
                                    emptyText: content.emptyText) { PRCardRow(item: $0) }
                    }
                }
            }
            .widgetURL(DeepLink.open(content.url))
        } else {
            LoadingHint(symbol: "arrow.triangle.pull")
        }
    }
}

private struct PRBadge: View {
    let content: PRContent

    var body: some View {
        HStack {
            CardBadge(title: content.title, symbol: "arrow.triangle.pull")
            Spacer(minLength: 0)
            if content.snapshot.error != nil {
                Image(systemName: "exclamationmark.triangle.fill").font(.caption2).foregroundStyle(.orange)
            }
        }
    }
}

/// Left column: badge, big number and the checks of the user's PRs.
private struct PRSummary: View {
    let content: PRContent

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PRBadge(content: content)
            Spacer(minLength: 6)
            BigCount(caption: content.caption) { Text("\(content.count)") }
            Spacer(minLength: 6)
            OwnChecks(states: content.ownChecks)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct OwnChecks: View {
    let states: [String?]

    var body: some View {
        if !states.isEmpty {
            VStack(alignment: .leading, spacing: 5) {
                Text("Tus checks").font(.system(size: 11)).foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    ForEach(Array(states.enumerated()), id: \.offset) { _, state in
                        let style = ChecksStyle(state: state)
                        Image(systemName: style.symbol)
                            .font(.system(size: 14))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(state == nil ? Color.secondary : .white, style.color)
                    }
                }
            }
        }
    }
}

private struct PRCardRow: View {
    let item: PRContent.Item

    var body: some View {
        let pr = item.pullRequest
        let repo = "\(pr.repo.split(separator: "/").last ?? "")#\(pr.number)"
        CardRow(symbol: symbol, color: color, title: pr.title,
                subtitle: "\(pr.detail ?? pr.updatedAt.shortAgo) · \(repo)", url: pr.url)
    }

    private var symbol: String {
        switch item.category {
        case .review, .assigned: "arrow.triangle.pull"
        case .answer: "arrowshape.turn.up.left.circle.fill"
        case .commented: "bubble.left.fill"
        case .mine: ChecksStyle(state: item.pullRequest.checks).symbol
        }
    }

    private var color: Color {
        switch item.category {
        case .review, .assigned: WidgetStyle.accent
        case .answer: .orange
        case .commented: WidgetStyle.comment
        case .mine: ChecksStyle(state: item.pullRequest.checks).color
        }
    }
}

private struct ChecksStyle {
    let state: String?

    var symbol: String {
        switch state {
        case "SUCCESS": "checkmark.circle.fill"
        case "FAILURE", "ERROR": "xmark.circle.fill"
        case "PENDING", "EXPECTED": "ellipsis.circle.fill"
        default: "circle.dashed"
        }
    }

    var color: Color {
        switch state {
        case "SUCCESS": .green
        case "FAILURE", "ERROR": .red
        case "PENDING", "EXPECTED": .orange
        default: .secondary
        }
    }
}
