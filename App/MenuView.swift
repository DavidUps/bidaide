import SwiftUI

struct MenuView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(SettingsKey.showMineInMenu) private var showMine = true

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
            if visible.isEmpty {
                EmptyState()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: .sectionHeaders) {
                        ForEach(visible) { category in
                            Section {
                                ForEach(snapshot.items(category)) { pr in
                                    PRRow(pullRequest: pr) { model.open(pr) }
                                        .contextMenu { RowMenu(pullRequest: pr) }
                                }
                            } header: {
                                SectionHeader(category: category, count: snapshot.items(category).count)
                            }
                        }
                    }
                    .padding(.bottom, 6)
                }
                .frame(maxHeight: 520)
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
    let category: PRCategory
    let count: Int

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: category.symbol).foregroundStyle(.tint)
            Text(category.title).font(.subheadline.weight(.semibold))
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
