import Foundation

/// One entry of `GET /notifications`, reduced to what the app needs.
struct GhNotification: Hashable {
    let id: String
    let reason: String
    let title: String
    let repo: String
    let number: Int
    let htmlURL: String
    let updatedAt: Date
    let latestCommentURL: String?
}

struct GhComment {
    let author: String
    let body: String
    let htmlURL: String?
}

enum GitHubService {
    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()

    static func login() async throws -> String {
        let data = try await GhCli.run(["api", "user", "--jq", ".login"])
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: Pull requests

    private static let searchQuery = """
    query($review: String!, $assigned: String!, $authored: String!) {
      review: search(query: $review, type: ISSUE, first: 50) { nodes { ...pr } }
      assigned: search(query: $assigned, type: ISSUE, first: 50) { nodes { ...pr } }
      authored: search(query: $authored, type: ISSUE, first: 50) { nodes { ...pr } }
    }
    fragment pr on PullRequest {
      number title url updatedAt isDraft reviewDecision
      author { login }
      repository { nameWithOwner }
      commits(last: 1) { nodes { commit { statusCheckRollup { state } } } }
      reviewThreads(first: 100) { nodes { isResolved comments(last: 1) { nodes { author { login } } } } }
    }
    """

    struct PullRequestBuckets {
        var review: [PullRequest] = []
        var assigned: [PullRequest] = []
        var answer: [PullRequest] = []
        var mine: [PullRequest] = []
    }

    static func fetchPullRequests(login: String, includeDrafts: Bool) async throws -> PullRequestBuckets {
        let base = "is:pr is:open archived:false sort:updated-desc"
        let data = try await GhCli.run([
            "api", "graphql",
            "-f", "query=\(searchQuery)",
            "-f", "review=\(base) review-requested:\(login)",
            "-f", "assigned=\(base) assignee:\(login)",
            "-f", "authored=\(base) author:\(login)",
        ])
        let response = try decoder.decode(SearchResponse.self, from: data)
        guard let result = response.data else {
            throw GhError(message: response.errors?.first?.message ?? "Respuesta vacía de GitHub")
        }

        func convert(_ search: SearchResponse.Search) -> [PullRequest] {
            search.nodes.compactMap { $0?.pullRequest(login: login) }
                .filter { includeDrafts || !$0.isDraft }
        }

        var buckets = PullRequestBuckets()
        buckets.review = convert(result.review)
        buckets.assigned = convert(result.assigned)
        // Own drafts still matter: comments on them need an answer too.
        buckets.mine = result.authored.nodes.compactMap { $0?.pullRequest(login: login) }
        buckets.answer = buckets.mine.compactMap { pr in
            var reasons: [String] = []
            if pr.reviewDecision == "CHANGES_REQUESTED" { reasons.append("Cambios pedidos") }
            if pr.waitingThreads > 0 {
                reasons.append(pr.waitingThreads == 1 ? "1 hilo esperando" : "\(pr.waitingThreads) hilos esperando")
            }
            guard !reasons.isEmpty else { return nil }
            var copy = pr
            copy.detail = reasons.joined(separator: " · ")
            return copy
        }
        return buckets
    }

    private struct SearchResponse: Decodable {
        struct Login: Decodable { let login: String }
        struct Repo: Decodable { let nameWithOwner: String }
        struct Rollup: Decodable { let state: String }
        struct Commit: Decodable { let statusCheckRollup: Rollup? }
        struct CommitNode: Decodable { let commit: Commit }
        struct Commits: Decodable { let nodes: [CommitNode] }
        struct ThreadComment: Decodable { let author: Login? }
        struct ThreadComments: Decodable { let nodes: [ThreadComment] }
        struct Thread: Decodable { let isResolved: Bool; let comments: ThreadComments }
        struct Threads: Decodable { let nodes: [Thread] }

        struct Node: Decodable {
            let number: Int?
            let title: String?
            let url: String?
            let updatedAt: Date?
            let isDraft: Bool?
            let reviewDecision: String?
            let author: Login?
            let repository: Repo?
            let commits: Commits?
            let reviewThreads: Threads?

            func pullRequest(login: String) -> PullRequest? {
                guard let number, let title, let url, let updatedAt, let repository else { return nil }
                let waiting = reviewThreads?.nodes.filter { thread in
                    !thread.isResolved && thread.comments.nodes.last?.author?.login != login
                }.count ?? 0
                return PullRequest(
                    repo: repository.nameWithOwner, number: number, title: title, url: url,
                    author: author?.login ?? "ghost", updatedAt: updatedAt, isDraft: isDraft ?? false,
                    reviewDecision: reviewDecision,
                    checks: commits?.nodes.first?.commit.statusCheckRollup?.state,
                    waitingThreads: waiting
                )
            }
        }

        struct Search: Decodable { let nodes: [Node?] }
        struct Result: Decodable { let review: Search; let assigned: Search; let authored: Search }
        struct GraphQLError: Decodable { let message: String }

        let data: Result?
        let errors: [GraphQLError]?
    }

    // MARK: Actions

    /// Runs the user started, in the repos of their open PRs and the ones they pushed to lately.
    /// GitHub has no "my runs" endpoint, so this asks each repo; a repo that fails (no access, Actions off) is skipped.
    static func fetchRuns(login: String, prRepos: [String]) async throws -> [WorkflowRun] {
        let data = try await GhCli.run(["api", "user/repos?sort=pushed&per_page=15"])
        let since = Date.now.addingTimeInterval(-14 * 24 * 3600)
        let pushed = try decoder.decode([RawRepo].self, from: data)
            .filter { !$0.archived && ($0.pushed_at ?? .distantPast) > since }
            .map(\.full_name)
        var repos: [String] = []
        for repo in prRepos + pushed where !repos.contains(repo) { repos.append(repo) }

        return await withTaskGroup(of: [WorkflowRun].self) { group in
            for repo in repos.prefix(10) {
                group.addTask {
                    let path = "repos/\(repo)/actions/runs?actor=\(login)&per_page=10&exclude_pull_requests=true"
                    guard let data = try? await GhCli.run(["api", path]),
                          let page = try? decoder.decode(RawRuns.self, from: data)
                    else { return [] }
                    return page.workflow_runs.map { $0.run(repo: repo) }
                }
            }
            var runs: [WorkflowRun] = []
            for await batch in group { runs += batch }
            return Array(runs.sorted { $0.startedAt > $1.startedAt }.prefix(30))
        }
    }

    private struct RawRepo: Decodable {
        let full_name: String
        let archived: Bool
        let pushed_at: Date?
    }

    private struct RawRuns: Decodable {
        struct Run: Decodable {
            let id: Int
            let name: String?
            let display_title: String?
            let head_branch: String?
            let status: String?
            let conclusion: String?
            let run_started_at: Date?
            let created_at: Date
            let updated_at: Date
            let html_url: String

            func run(repo: String) -> WorkflowRun {
                WorkflowRun(
                    id: id, workflow: name ?? "Workflow", title: display_title ?? "", repo: repo,
                    branch: head_branch ?? "", status: status ?? "queued", conclusion: conclusion,
                    startedAt: run_started_at ?? created_at, updatedAt: updated_at, url: html_url
                )
            }
        }
        let workflow_runs: [Run]
    }

    // MARK: Notifications

    /// Unread notifications about pull requests.
    static func fetchNotifications() async throws -> [GhNotification] {
        let data = try await GhCli.run(["api", "notifications?per_page=50"])
        let raw = try decoder.decode([RawNotification].self, from: data)
        return raw.compactMap { item in
            guard item.unread, item.subject.type == "PullRequest",
                  let apiURL = item.subject.url,
                  let number = Int(apiURL.split(separator: "/").last ?? "")
            else { return nil }
            return GhNotification(
                id: item.id, reason: item.reason, title: item.subject.title,
                repo: item.repository.full_name, number: number,
                htmlURL: "https://github.com/\(item.repository.full_name)/pull/\(number)",
                updatedAt: item.updated_at, latestCommentURL: item.subject.latest_comment_url
            )
        }
    }

    /// The comment or review a notification points at, for the author and a preview of the text.
    static func comment(at apiURL: String) async -> GhComment? {
        guard let data = try? await GhCli.run(["api", apiURL]),
              let raw = try? decoder.decode(RawComment.self, from: data),
              let author = raw.user?.login
        else { return nil }
        return GhComment(author: author, body: raw.body ?? "", htmlURL: raw.html_url)
    }

    static func markRead(threadID: String) async throws {
        _ = try await GhCli.run(["api", "-X", "PATCH", "notifications/threads/\(threadID)"])
    }

    private struct RawNotification: Decodable {
        struct Subject: Decodable {
            let title: String
            let url: String?
            let latest_comment_url: String?
            let type: String
        }
        struct Repository: Decodable { let full_name: String }

        let id: String
        let reason: String
        let unread: Bool
        let updated_at: Date
        let subject: Subject
        let repository: Repository
    }

    private struct RawComment: Decodable {
        struct User: Decodable { let login: String }
        let user: User?
        let body: String?
        let html_url: String?
    }
}

extension GhNotification {
    var reasonLabel: String {
        switch reason {
        case "review_requested": "Revisión pedida"
        case "assign": "Asignada"
        case "comment": "Nuevo comentario"
        case "mention": "Te han mencionado"
        case "team_mention": "Mencionan a tu equipo"
        case "author": "Actividad en tu PR"
        case "state_change": "Cambio de estado"
        case "ci_activity": "Actividad de CI"
        default: reason
        }
    }

    /// Reasons that land in the "Comentadas" bucket.
    var isConversation: Bool {
        ["comment", "mention", "team_mention", "author"].contains(reason)
    }
}
