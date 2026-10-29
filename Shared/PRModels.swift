import Foundation

/// The buckets a pull request can fall into. Everything except `.mine` asks the user to do something.
enum PRCategory: String, Codable, CaseIterable, Identifiable {
    case review, answer, commented, assigned, mine

    var id: String { rawValue }

    var title: String {
        switch self {
        case .review: "Revisar"
        case .answer: "Responder"
        case .commented: "Comentadas"
        case .assigned: "Asignadas"
        case .mine: "Mis PR"
        }
    }

    var subtitle: String {
        switch self {
        case .review: "Te han pedido revisión"
        case .answer: "Hilos o cambios pendientes en tus PR"
        case .commented: "Comentarios y menciones sin leer"
        case .assigned: "PR asignadas a ti"
        case .mine: "Tus PR abiertas"
        }
    }

    var symbol: String {
        switch self {
        case .review: "eye"
        case .answer: "arrowshape.turn.up.left"
        case .commented: "bubble.left.and.bubble.right"
        case .assigned: "person.crop.circle.badge.checkmark"
        case .mine: "arrow.triangle.pull"
        }
    }

    var needsAction: Bool { self != .mine }
}

struct PullRequest: Codable, Hashable, Identifiable {
    var id: String { url }
    let repo: String
    let number: Int
    let title: String
    let url: String
    let author: String
    let updatedAt: Date
    var isDraft = false
    /// APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED or nil.
    var reviewDecision: String?
    /// SUCCESS, FAILURE, ERROR, PENDING, EXPECTED or nil.
    var checks: String?
    /// Unresolved review threads whose last comment is not from the user.
    var waitingThreads = 0
    /// Free text shown under the title, e.g. why it is in this bucket.
    var detail: String?
    /// GitHub notification thread, for items that come from `/notifications`.
    var threadID: String?
}

struct PRSection: Codable, Hashable {
    let category: PRCategory
    var items: [PullRequest]
}

struct Snapshot: Codable {
    var updatedAt: Date
    var login: String
    var sections: [PRSection]
    var error: String?

    func items(_ category: PRCategory) -> [PullRequest] {
        sections.first { $0.category == category }?.items ?? []
    }

    /// Distinct pull requests that need the user, counted once even if they sit in several buckets.
    var actionCount: Int {
        Set(sections.filter(\.category.needsAction).flatMap(\.items).map(\.url)).count
    }

    /// Action items across buckets, without repeats, in bucket order.
    var actionItems: [(PRCategory, PullRequest)] {
        var seen = Set<String>()
        var result: [(PRCategory, PullRequest)] = []
        for category in PRCategory.allCases where category.needsAction {
            for item in items(category) where seen.insert(item.url).inserted {
                result.append((category, item))
            }
        }
        return result
    }
}

extension Snapshot {
    static let sample = Snapshot(
        updatedAt: .now,
        login: "octocat",
        sections: [
            PRSection(category: .review, items: [
                PullRequest(repo: "acme/android", number: 128, title: "Pantalla de pago con Compose",
                            url: "https://github.com/acme/android/pull/128", author: "ana", updatedAt: .now,
                            checks: "SUCCESS"),
                PullRequest(repo: "acme/api", number: 54, title: "Paginación en /orders",
                            url: "https://github.com/acme/api/pull/54", author: "luis", updatedAt: .now,
                            checks: "PENDING"),
            ]),
            PRSection(category: .answer, items: [
                PullRequest(repo: "acme/android", number: 131, title: "Migrar a Kotlin 2.3",
                            url: "https://github.com/acme/android/pull/131", author: "octocat", updatedAt: .now,
                            reviewDecision: "CHANGES_REQUESTED", checks: "FAILURE", waitingThreads: 2),
            ]),
            PRSection(category: .commented, items: [
                PullRequest(repo: "acme/web", number: 77, title: "Nuevo header",
                            url: "https://github.com/acme/web/pull/77", author: "", updatedAt: .now,
                            detail: "Nuevo comentario"),
            ]),
            PRSection(category: .assigned, items: []),
            PRSection(category: .mine, items: []),
        ]
    )
}

enum DeepLink {
    static let scheme = "bidaidenotificator"

    /// Widgets open URLs through the containing app; this wraps a GitHub URL so the app forwards it to the browser.
    static func open(_ url: String) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "open"
        components.queryItems = [URLQueryItem(name: "url", value: url)]
        return components.url!
    }

    static func target(of url: URL) -> URL? {
        guard url.scheme == scheme,
              let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "url" })?.value
        else { return nil }
        return URL(string: value)
    }
}
