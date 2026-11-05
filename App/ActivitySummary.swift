import Foundation

/// One entry of a PR timeline, reduced by `gh --jq` to what a notification needs.
struct PREvent: Decodable {
    /// committed, commented, line-commented, reviewed, assigned, review_requested, merged…
    let e: String
    /// GitHub login, or the git author name for commits.
    let who: String
    let bot: Bool
    let at: Date
    /// Review state: approved, changes_requested, commented.
    var state: String?
    /// Who was assigned or asked for review.
    var target: String?
    /// Comments in a `line-commented` thread; 1 otherwise.
    var n: Int = 1
}

/// What other people did on a PR in a time window, in words, so every notification says why it arrived.
struct ActivitySummary {
    /// Most important first, e.g. ["@ana te ha asignado", "2 commits nuevos de Ana"].
    private(set) var parts: [String] = []
    /// A bot assigned the PR to the user: party mode territory.
    private(set) var botAssigned = false

    var isEmpty: Bool { parts.isEmpty }

    /// `me` holds the user's login and git name, so their own activity never explains a notification.
    init(events: [PREvent], since: Date, until: Date, login: String, me: Set<String>) {
        // A little slack: the notification's timestamp can trail the event that caused it by a few seconds.
        let window = events.filter { $0.at > since && $0.at <= until.addingTimeInterval(120) && !me.contains($0.who) }

        var commits: [String] = []
        var commitCount = 0
        var comments: [String] = []
        var commentCount = 0
        var people: [String] = []
        var assigned: [String] = []
        var reviews: [String] = []
        var state: [String] = []

        func add(_ name: String, to list: inout [String]) {
            if !list.contains(name) { list.append(name) }
        }

        for event in window {
            let who = "@\(event.who)"
            switch event.e {
            case "committed":
                commitCount += 1
                add(event.who, to: &commits)
            case "head_ref_force_pushed":
                add("\(who) ha hecho force-push", to: &state)
            case "commented", "line-commented":
                commentCount += event.n
                add(who, to: &comments)
            case "reviewed":
                switch event.state {
                case "approved": add("\(who) ha aprobado", to: &reviews)
                case "changes_requested": add("\(who) pide cambios", to: &reviews)
                case "commented": add("\(who) ha hecho una review", to: &reviews)
                default: break
                }
            case "assigned" where event.target == login:
                botAssigned = botAssigned || event.bot || event.who.hasSuffix("[bot]")
                add(event.bot ? "\(who) te ha asignado automáticamente" : "\(who) te ha asignado", to: &assigned)
            case "review_requested" where event.target == login:
                add("\(who) te pide revisión", to: &assigned)
            case "merged": add("\(who) la ha fusionado", to: &state)
            case "closed": add("\(who) la ha cerrado", to: &state)
            case "reopened": add("\(who) la ha reabierto", to: &state)
            case "ready_for_review": add("\(who) la ha marcado lista para revisar", to: &state)
            default: continue
            }
            add(who, to: &people)
        }

        parts = assigned + reviews
        if commentCount > 0 {
            parts.append(comments.count == 1
                ? (commentCount == 1 ? "\(comments[0]) ha comentado" : "\(comments[0]) ha dejado \(commentCount) comentarios")
                : "\(commentCount) comentarios de \(comments.joined(separator: ", "))")
        }
        if commitCount > 0 {
            let noun = commitCount == 1 ? "1 commit nuevo" : "\(commitCount) commits nuevos"
            parts.append("\(noun) de \(commits.joined(separator: ", "))")
        }
        parts += state
    }
}
