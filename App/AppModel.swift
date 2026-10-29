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

            let commented = notifications.filter(\.isConversation).map { notification in
                PullRequest(repo: notification.repo, number: notification.number, title: notification.title,
                            url: notification.htmlURL, author: "", updatedAt: notification.updatedAt,
                            detail: notification.reasonLabel, threadID: notification.id)
            }
            let next = Snapshot(updatedAt: .now, login: login, sections: [
                PRSection(category: .review, items: buckets.review),
                PRSection(category: .answer, items: buckets.answer),
                PRSection(category: .commented, items: deduplicated(commented)),
                PRSection(category: .assigned, items: buckets.assigned),
                PRSection(category: .mine, items: buckets.mine),
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
        try? await GitHubService.markRead(threadID: threadID)
        await refresh()
    }

    func open(_ pullRequest: PullRequest) {
        open(pullRequest.url)
    }

    func open(_ string: String) {
        if let url = URL(string: string) { NSWorkspace.shared.open(url) }
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

        for notification in notifications.sorted(by: { $0.updatedAt < $1.updatedAt }) {
            let stamp = ISO8601DateFormatter().string(from: notification.updatedAt)
            guard delivered[notification.id] != stamp else { continue }
            delivered[notification.id] = stamp
            guard !isFirstRun, budget > 0, Preferences.notifies(notification.reason) else { continue }

            var title = notification.reasonLabel
            var body = notification.title
            var url = notification.htmlURL
            var subtitle = "\(notification.repo)#\(notification.number)"
            if let commentURL = notification.latestCommentURL,
               !commentURL.hasSuffix("/pulls/\(notification.number)"),
               let comment = await GitHubService.comment(at: commentURL) {
                // Skip activity the user caused themselves.
                if comment.author == login { continue }
                title = Self.title(for: notification.reason, author: comment.author) ?? title
                let preview = comment.body.replacingOccurrences(of: "\r\n", with: " ")
                    .replacingOccurrences(of: "\n", with: " ")
                if !preview.isEmpty {
                    subtitle += " · \(notification.title)"
                    body = String(preview.prefix(180))
                }
                url = comment.htmlURL ?? url
            }

            Notifier.shared.post(
                id: "\(notification.id)-\(stamp)", title: title, subtitle: subtitle,
                body: body, url: url, threadID: notification.id
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
