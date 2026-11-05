import AppKit
import WidgetKit

/// Polls GitHub through `gh`, publishes the snapshot to the menu, the widget and the notification center.
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var snapshot: Snapshot?
    @Published private(set) var isRefreshing = false
    @Published private(set) var lastError: String?

    private var login: String?
    private var pollTask: Task<Void, Never>?

    /// GitHub notification id → `updated_at` already shown, so each new activity notifies once.
    private var delivered: [String: String] {
        get { UserDefaults.standard.dictionary(forKey: "delivered") as? [String: String] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: "delivered") }
    }

    /// False until the first poll; that poll records what is already unread without notifying it.
    private var seeded: Bool {
        get { UserDefaults.standard.bool(forKey: "seeded") }
        set { UserDefaults.standard.set(newValue, forKey: "seeded") }
    }

    private init() {
        snapshot = SnapshotStore.load()
    }

    var actionCount: Int { snapshot?.actionCount ?? 0 }

    func start() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: UInt64(Preferences.pollInterval * 1_000_000_000))
            }
        }
    }

    /// Restarts the wait so a new interval applies right away.
    func restartPolling() {
        pollTask?.cancel()
        pollTask = nil
        start()
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        do {
            let login = try await currentLogin()
            async let bucketsRequest = GitHubService.fetchPullRequests(login: login, includeDrafts: Preferences.includeDrafts)
            async let notificationsRequest = GitHubService.fetchNotifications()
            let (buckets, notifications) = try await (bucketsRequest, notificationsRequest)
            // Actions are a bonus: if they fail, keep the previous runs instead of failing the whole refresh.
            let prRepos = (buckets.mine + buckets.review).map(\.repo)
            let runs = (try? await GitHubService.fetchRuns(login: login, prRepos: prRepos)) ?? snapshot?.runs

            let conversations = notifications.filter(\.isConversation)
            // Comments on merged or closed PRs are done with; if the lookup fails, keep them all.
            let open = try? await GitHubService.openPullRequestURLs(conversations.map { ($0.repo, $0.number) })
            let commented = conversations.filter { open?.contains($0.htmlURL) ?? true }.map { notification in
                PullRequest(repo: notification.repo, number: notification.number, title: notification.title,
                            url: notification.htmlURL, author: "", updatedAt: notification.updatedAt,
                            detail: notification.reasonLabel, threadID: notification.id)
            }
            let activity = latestActivity(notifications)
            let next = Snapshot(updatedAt: .now, login: login, sections: [
                PRSection(category: .review, items: annotated(buckets.review, activity)),
                PRSection(category: .answer, items: annotated(buckets.answer, activity)),
                PRSection(category: .commented, items: deduplicated(commented)),
                PRSection(category: .assigned, items: annotated(buckets.assigned, activity)),
                PRSection(category: .mine, items: annotated(buckets.mine, activity)),
            ], runs: runs)
            let previousRuns = snapshot?.runs
            publish(next)
            lastError = nil
            await deliver(notifications, login: login)
            deliverFinished(runs ?? [], previous: previousRuns)
        } catch {
            lastError = error.localizedDescription
            if var stale = snapshot {
                stale.error = lastError
                publish(stale)
            }
        }
    }

    func markRead(threadID: String) async {
        // Drop it right away so the menu and widgets do not keep showing it while GitHub catches up.
        if var current = snapshot {
            for index in current.sections.indices {
                if current.sections[index].category == .commented {
                    current.sections[index].items.removeAll { $0.threadID == threadID }
                } else {
                    // The PR still belongs here; only the unread chip goes away.
                    for item in current.sections[index].items.indices
                    where current.sections[index].items[item].threadID == threadID {
                        current.sections[index].items[item].threadID = nil
                        current.sections[index].items[item].activity = nil
                    }
                }
            }
            publish(current)
        }
        try? await GitHubService.markRead(threadID: threadID)
        await refresh()
    }

    /// Opens a PR or run from the menu: closes the menu, and a comment notification counts as seen.
    func open(_ pullRequest: PullRequest) {
        open(pullRequest.url, threadID: pullRequest.threadID)
    }

    func open(_ string: String, threadID: String? = nil) {
        NSApp.keyWindow?.close()
        if let url = URL(string: string) { NSWorkspace.shared.open(url) }
        if let threadID { Task { await markRead(threadID: threadID) } }
    }

    private var displayName: String??

    private func currentDisplayName() async -> String? {
        if let displayName { return displayName }
        let value = await GitHubService.displayName()
        displayName = .some(value)
        return value
    }

    private func currentLogin() async throws -> String {
        if let login { return login }
        let value = try await GitHubService.login()
        login = value
        return value
    }

    private func publish(_ snapshot: Snapshot) {
        self.snapshot = snapshot
        try? SnapshotStore.save(snapshot)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Newest unread activity per PR URL, with the notification thread that clears it.
    private func latestActivity(_ notifications: [GhNotification]) -> [String: (PRActivity, String)] {
        var result: [String: (PRActivity, String)] = [:]
        for notification in notifications.sorted(by: { $0.updatedAt > $1.updatedAt }) where result[notification.htmlURL] == nil {
            if let activity = notification.activity { result[notification.htmlURL] = (activity, notification.id) }
        }
        return result
    }

    /// Tags PRs that have unread activity, so opening them also marks the notification as read.
    private func annotated(_ items: [PullRequest], _ activity: [String: (PRActivity, String)]) -> [PullRequest] {
        items.map { pr in
            guard let (kind, threadID) = activity[pr.url] else { return pr }
            var copy = pr
            copy.activity = kind
            copy.threadID = threadID
            return copy
        }
    }

    /// Several unread notifications can point at the same PR; keep the newest one.
    private func deduplicated(_ items: [PullRequest]) -> [PullRequest] {
        var seen = Set<String>()
        return items.sorted { $0.updatedAt > $1.updatedAt }.filter { seen.insert($0.url).inserted }
    }

    private func deliver(_ notifications: [GhNotification], login: String) async {
        var delivered = self.delivered
        let isFirstRun = !seeded
        // Never more than a handful per poll, so waking the Mac up does not flood the screen.
        var budget = 8

        let me = Set([login] + [await currentDisplayName()].compactMap { $0 })

        for notification in notifications.sorted(by: { $0.updatedAt < $1.updatedAt }) {
            let stamp = ISO8601DateFormatter().string(from: notification.updatedAt)
            let previous = delivered[notification.id]
            guard previous != stamp else { continue }
            delivered[notification.id] = stamp
            guard !isFirstRun, budget > 0, Preferences.notifies(notification.reason) else { continue }

            // Explain the notification with what happened since the last one about this PR.
            let since = previous.flatMap { ISO8601DateFormatter().date(from: $0) }
                ?? notification.lastReadAt
                ?? notification.updatedAt.addingTimeInterval(-15 * 60)
            var summary: ActivitySummary?
            if let events = try? await GitHubService.timeline(repo: notification.repo, number: notification.number) {
                let found = ActivitySummary(events: events, since: since, until: notification.updatedAt,
                                            login: login, me: me)
                // Nothing someone else did explains it (own activity, CI…): stay quiet rather than ping for no reason.
                if found.isEmpty { continue }
                summary = found
            }

            var title = summary?.parts.first ?? notification.reasonLabel
            var details = Array(summary?.parts.dropFirst() ?? [])
            var url = notification.htmlURL
            var preview: String?
            if let commentURL = notification.latestCommentURL,
               !commentURL.hasSuffix("/pulls/\(notification.number)"),
               let comment = await GitHubService.comment(at: commentURL) {
                if summary == nil {
                    // No timeline: fall back to the comment alone, and skip activity the user caused themselves.
                    if me.contains(comment.author) { continue }
                    title = Self.title(for: notification.reason, author: comment.author) ?? title
                }
                if !me.contains(comment.author) {
                    let text = comment.body.replacingOccurrences(of: "\r\n", with: " ")
                        .replacingOccurrences(of: "\n", with: " ")
                    if !text.isEmpty { preview = "«\(text.prefix(160))»" }
                    url = comment.htmlURL ?? url
                }
            }
            if let preview { details.append(preview) }

            Notifier.shared.post(
                id: "\(notification.id)-\(stamp)", title: title,
                subtitle: "\(notification.repo)#\(notification.number) · \(notification.title)",
                body: details.isEmpty ? notification.title : details.joined(separator: "\n"),
                url: url, threadID: notification.id,
                sound: summary?.botAssigned == true && Preferences.partyMode ? Notifier.assignSound : Notifier.defaultSound
            )
            budget -= 1
        }

        // Forget notifications that are no longer unread.
        let current = Set(notifications.map(\.id))
        self.delivered = delivered.filter { current.contains($0.key) }
        seeded = true
    }

    /// Runs that were queued or running on the previous poll and have finished now.
    private func deliverFinished(_ runs: [WorkflowRun], previous: [WorkflowRun]?) {
        guard let previous else { return }
        let wasActive = Set(previous.filter(\.outcome.isActive).map(\.id))
        for run in runs where wasActive.contains(run.id) {
            let reason: String
            let title: String
            switch run.outcome {
            case .success: (reason, title) = ("run_success", "✅ \(run.workflow) ha pasado")
            case .failure: (reason, title) = ("run_failure", "❌ \(run.workflow) ha fallado")
            default: continue
            }
            guard Preferences.notifies(reason) else { continue }
            Notifier.shared.post(
                id: "run-\(run.id)", title: title, subtitle: "\(run.repo) · \(run.branch)",
                body: run.title, url: run.url, threadID: nil
            )
        }
    }

    private static func title(for reason: String, author: String) -> String? {
        switch reason {
        case "comment": "@\(author) ha comentado"
        case "mention": "@\(author) te ha mencionado"
        case "team_mention": "@\(author) ha mencionado a tu equipo"
        case "author": "@\(author) en tu PR"
        case "review_requested": "@\(author) te pide revisión"
        case "assign": "@\(author) te ha asignado"
        default: nil
        }
    }
}
