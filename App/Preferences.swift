import Foundation

/// UserDefaults keys shared by the settings window and the poller.
enum SettingsKey {
    static let pollInterval = "pollInterval"
    static let includeDrafts = "includeDrafts"
    static let showMineInMenu = "showMineInMenu"

    static func notify(_ reason: String) -> String { "notify.\(reason)" }
}

struct NotificationReason: Identifiable {
    let id: String
    let label: String
    let enabledByDefault: Bool

    static let all: [NotificationReason] = [
        .init(id: "review_requested", label: "Me piden revisar una PR", enabledByDefault: true),
        .init(id: "assign", label: "Me asignan una PR", enabledByDefault: true),
        .init(id: "comment", label: "Comentan en una PR que sigo", enabledByDefault: true),
        .init(id: "mention", label: "Me mencionan", enabledByDefault: true),
        .init(id: "team_mention", label: "Mencionan a mi equipo", enabledByDefault: true),
        .init(id: "author", label: "Actividad en mis PR (reviews, comentarios)", enabledByDefault: true),
        .init(id: "state_change", label: "Una PR se fusiona o se cierra", enabledByDefault: false),
        .init(id: "ci_activity", label: "Actividad de CI", enabledByDefault: false),
    ]
}

enum Preferences {
    static func registerDefaults() {
        var defaults: [String: Any] = [
            SettingsKey.pollInterval: 120,
            SettingsKey.includeDrafts: false,
            SettingsKey.showMineInMenu: true,
        ]
        for reason in NotificationReason.all {
            defaults[SettingsKey.notify(reason.id)] = reason.enabledByDefault
        }
        UserDefaults.standard.register(defaults: defaults)
    }

    static var pollInterval: TimeInterval {
        max(60, UserDefaults.standard.double(forKey: SettingsKey.pollInterval))
    }

    static var includeDrafts: Bool { UserDefaults.standard.bool(forKey: SettingsKey.includeDrafts) }

    static func notifies(_ reason: String) -> Bool {
        UserDefaults.standard.object(forKey: SettingsKey.notify(reason)) as? Bool ?? false
    }
}
