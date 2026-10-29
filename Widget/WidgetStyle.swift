import SwiftUI
import WidgetKit

/// Shared look of the widgets: a dark card with a summary column on the left and a list on the right.
enum WidgetStyle {
    static let accent = Color(red: 0.45, green: 0.46, blue: 0.98)
    static let comment = Color(red: 0.25, green: 0.55, blue: 1.0)
}

struct CardBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        if colorScheme == .dark {
            LinearGradient(colors: [Color(white: 0.09), Color(white: 0.04)], startPoint: .top, endPoint: .bottom)
        } else {
            LinearGradient(colors: [.white, Color(white: 0.95)], startPoint: .top, endPoint: .bottom)
        }
    }
}

/// "⟟ GITHUB" label at the top of the card.
struct CardBadge: View {
    let title: String
    let symbol: String
    var color = WidgetStyle.accent

    var body: some View {
        Label {
            Text(title.uppercased()).tracking(0.6)
        } icon: {
            Image(systemName: symbol)
        }
        .font(.system(size: 11, weight: .semibold))
        .foregroundStyle(color)
        .labelStyle(BadgeLabelStyle())
        .lineLimit(1)
        .widgetAccentable()
    }
}

private struct BadgeLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 5) {
            configuration.icon
            configuration.title
        }
    }
}

/// The big number with its caption, e.g. "2 / por revisar".
struct BigCount<Value: View>: View {
    let caption: String
    var captionColor: Color = .secondary
    @ViewBuilder let value: Value

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            value
                .font(.system(size: 44, weight: .semibold))
                .monospacedDigit()
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(caption)
                .font(.system(size: 13))
                .foregroundStyle(captionColor)
                .lineLimit(2)
        }
    }
}

/// One line of the list: icon, bold title, secondary metadata and optional trailing content.
struct CardRow<Trailing: View>: View {
    let symbol: String
    let color: Color
    let title: String
    let subtitle: String
    let url: String
    var threadID: String? = nil
    @ViewBuilder var trailing: Trailing

    var body: some View {
        Link(destination: DeepLink.open(url, threadID: threadID)) {
            HStack(alignment: .center, spacing: 10) {
                RowIcon(symbol: symbol, color: color)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                trailing
            }
        }
    }
}

/// Filled circles get a white glyph on a colored disc; everything else is tinted.
private struct RowIcon: View {
    let symbol: String
    let color: Color

    var body: some View {
        Group {
            if symbol.hasSuffix(".circle.fill") {
                Image(systemName: symbol).symbolRenderingMode(.palette).foregroundStyle(.white, color)
            } else {
                Image(systemName: symbol).foregroundStyle(color)
            }
        }
        .font(.system(size: 15, weight: .medium))
        .frame(width: 18)
    }
}

extension CardRow where Trailing == EmptyView {
    init(symbol: String, color: Color, title: String, subtitle: String, url: String, threadID: String? = nil) {
        self.init(symbol: symbol, color: color, title: title, subtitle: subtitle, url: url, threadID: threadID) {
            EmptyView()
        }
    }
}

/// Rows laid out in one or more columns. A full column spreads its rows over the height; a short one stays at the top.
struct CardColumns<Item: Identifiable, Row: View>: View {
    let items: [Item]
    let columns: Int
    let perColumn: Int
    let emptyText: String
    @ViewBuilder let row: (Item) -> Row

    var body: some View {
        let shown = Array(items.prefix(columns * perColumn))
        if shown.isEmpty {
            AllClear(text: emptyText)
        } else {
            HStack(alignment: .top, spacing: 20) {
                ForEach(0..<columns, id: \.self) { column in
                    let slice = Array(shown.dropFirst(column * perColumn).prefix(perColumn))
                    let full = slice.count == perColumn
                    VStack(alignment: .leading, spacing: full ? 0 : 12) {
                        ForEach(slice) { item in
                            row(item).frame(maxHeight: full ? .infinity : nil)
                        }
                        if !full { Spacer(minLength: 0) }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }
}

struct AllClear: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle").font(.system(size: 15, weight: .medium))
            Text(text).font(.system(size: 13, weight: .semibold))
        }
        .foregroundStyle(.green)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct LoadingHint: View {
    let symbol: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.title2).foregroundStyle(WidgetStyle.accent)
            Text("Abre Bidaide Notificator para cargar los datos")
                .font(.caption).multilineTextAlignment(.center).foregroundStyle(.secondary)
        }
    }
}

extension Date {
    /// "hace 12 min".
    var shortAgo: String {
        // The rest of the UI is in Spanish, so do not follow the system language here.
        formatted(.relative(presentation: .named, unitsStyle: .abbreviated).locale(Locale(identifier: "es_ES")))
    }
}
