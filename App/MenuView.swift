import SwiftUI

struct MenuView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(SettingsKey.showMineInMenu) private var showMine = true
    @State private var listHeight: CGFloat = 300

    private var categories: [PRCategory] {
        PRCategory.allCases.filter { $0.needsAction || showMine }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            if let error = model.lastError {
                ErrorBanner(message: error)
            }
            content
            Divider()
            footer
        }
        .frame(width: 400)
        .task { await model.refresh() }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Pull requests").font(.headline)
                if let login = model.snapshot?.login {
                    Text("@\(login)").font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            if model.isRefreshing {
                ProgressView().controlSize(.small)
            } else {
                Button {
                    Task { await model.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .help("Actualizar")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private var content: some View {
        if let snapshot = model.snapshot {
            let visible = categories.filter { !snapshot.items($0).isEmpty }
            let runs = snapshot.activeRuns
            if visible.isEmpty && runs.isEmpty {
                EmptyState()
            } else {
                // A ScrollView has no height of its own inside a MenuBarExtra window, so it would collapse to
                // zero: measure the list and give the scroll view that height, up to a limit.
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(visible) { category in
                            SectionHeader(title: category.title, symbol: category.symbol,
                                          count: snapshot.items(category).count)
                            ForEach(snapshot.items(category)) { pr in
                                PRRow(pullRequest: pr) { model.open(pr) }
                                    .contextMenu { RowMenu(pullRequest: pr) }
                            }
                        }
                        if !runs.isEmpty {
                            SectionHeader(title: "Actions", symbol: "play.circle", count: snapshot.activeRuns.count)
                            ForEach(runs) { run in
                                RunRow(run: run) { model.open(run.url) }
                            }
                        }
                    }
                    .padding(.bottom, 6)
                    .background(GeometryReader { proxy in
                        Color.clear.preference(key: ListHeightKey.self, value: proxy.size.height)
                    })
                }
                .frame(height: min(max(listHeight, 60), 520))
                .onPreferenceChange(ListHeightKey.self) { listHeight = $0 }
            }
        } else {
            VStack(spacing: 8) {
                ProgressView()
                Text("Consultando GitHub…").foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, minHeight: 140)
        }
    }

    private var footer: some View {
        HStack {
            if let date = model.snapshot?.updatedAt {
                Text("Actualizado \(date, style: .relative)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            SettingsLink {
                Image(systemName: "gearshape")
            }
            .buttonStyle(.borderless)
            .help("Ajustes")
            .simultaneousGesture(TapGesture().onEnded { NSApp.activate(ignoringOtherApps: true) })
            Button {
                NSApp.terminate(nil)
            } label: {
                Image(systemName: "power")
            }
            .buttonStyle(.borderless)
            .help("Salir")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private struct SectionHeader: View {
    let title: String
    let symbol: String
    let count: Int

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: symbol).foregroundStyle(.tint)
            Text(title).font(.subheadline.weight(.semibold))
            Text("\(count)")
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 6).padding(.vertical, 1)
                .background(.quaternary, in: Capsule())
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.top, 10).padding(.bottom, 4)
        .background(.regularMaterial)
    }
}

struct PRRow: View {
    let pullRequest: PullRequest
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                ChecksIcon(state: pullRequest.checks)
                    .frame(width: 14)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(pullRequest.title)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(metadata)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    if !badges.isEmpty {
                        HStack(spacing: 4) {
                            ForEach(badges, id: \.text) { badge in
                                Text(badge.text)
                                    .font(.caption2.weight(.medium))
                                    .padding(.horizontal, 5).padding(.vertical, 1)
                                    .foregroundStyle(badge.color)
                                    .background(badge.color.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(hovering ? Color.primary.opacity(0.07) : .clear)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help(pullRequest.url)
    }

    private var metadata: String {
        var parts = ["\(pullRequest.repo)#\(pullRequest.number)"]
        if !pullRequest.author.isEmpty { parts.append("@\(pullRequest.author)") }
        parts.append(pullRequest.updatedAt.formatted(.relative(presentation: .named)))
        return parts.joined(separator: " · ")
    }

    private var badges: [(text: String, color: Color)] {
        var result: [(String, Color)] = []
        if pullRequest.isDraft { result.append(("Borrador", .gray)) }
        switch pullRequest.reviewDecision {
        case "APPROVED": result.append(("Aprobada", .green))
        case "CHANGES_REQUESTED": result.append(("Cambios pedidos", .red))
        default: break
        }
        if pullRequest.waitingThreads > 0 {
            result.append(("\(pullRequest.waitingThreads) hilos", .orange))
        }
        if let detail = pullRequest.detail, pullRequest.reviewDecision == nil, pullRequest.waitingThreads == 0 {
            result.append((detail, .blue))
        }
        return result
    }
}

private struct RunRow: View {
    let run: WorkflowRun
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        let outcome = run.outcome
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: outcome.symbol)
                    .foregroundStyle(outcome.color)
                    .frame(width: 14)
                    .padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(run.workflow).lineLimit(1)
                    Text("\(run.repo) · \(run.branch)")
                        .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                VStack(alignment: .trailing, spacing: 3) {
                    Text(outcome.label).font(.caption.weight(.semibold)).foregroundStyle(outcome.color)
                    if outcome == .running {
                        Text(run.startedAt, style: .timer).font(.caption).monospacedDigit().foregroundStyle(.secondary)
                    } else {
                        Text(run.updatedAt.formatted(.relative(presentation: .named)))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
            .background(hovering ? Color.primary.opacity(0.07) : .clear)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help(run.title)
    }
}

struct ChecksIcon: View {
    let state: String?

    var body: some View {
        switch state {
        case "SUCCESS":
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case "FAILURE", "ERROR":
            Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
        case "PENDING", "EXPECTED":
            Image(systemName: "clock.fill").foregroundStyle(.orange)
        default:
            Image(systemName: "circle.dashed").foregroundStyle(.secondary)
        }
    }
}

private struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(message).font(.caption).textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Color.orange.opacity(0.12))
    }
}

private struct EmptyState: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "checkmark.seal").font(.largeTitle).foregroundStyle(.green)
            Text("Nada pendiente").font(.headline)
            Text("Ninguna PR espera por ti.").font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 140)
    }
}

private struct RowMenu: View {
    @EnvironmentObject private var model: AppModel
    let pullRequest: PullRequest

    var body: some View {
        Button("Abrir en el navegador") { model.open(pullRequest) }
        Button("Copiar enlace") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(pullRequest.url, forType: .string)
        }
        if let threadID = pullRequest.threadID {
            Divider()
            Button("Marcar como leída") { Task { await model.markRead(threadID: threadID) } }
        }
    }
}

private struct ListHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
