import SwiftUI

/// A GitHub Actions workflow run started by the user.
struct WorkflowRun: Codable, Hashable, Identifiable {
    let id: Int
    /// Workflow name, e.g. "CI".
    let workflow: String
    /// Commit message or PR title the run is about.
    let title: String
    let repo: String
    let branch: String
    /// queued, in_progress, completed, waiting, requested, pending…
    let status: String
    /// success, failure, cancelled, skipped, timed_out… once completed.
    let conclusion: String?
    let startedAt: Date
    let updatedAt: Date
    let url: String

    var outcome: RunOutcome {
        switch status {
        case "in_progress": return .running
        case "completed": break
        default: return .queued
        }
        switch conclusion {
        case "success": return .success
        case "failure", "timed_out", "startup_failure": return .failure
        case "action_required": return .queued
        default: return .cancelled
        }
    }

    var repoName: String { String(repo.split(separator: "/").last ?? "") }
}

enum RunOutcome {
    case running, queued, success, failure, cancelled

    var isActive: Bool { self == .running || self == .queued }

    var label: String {
        switch self {
        case .running: "En curso"
        case .queued: "En cola"
        case .success: "Correcto"
        case .failure: "Fallido"
        case .cancelled: "Cancelado"
        }
    }

    var symbol: String {
        switch self {
        case .running: "progress.indicator"
        case .queued: "clock.fill"
        case .success: "checkmark.circle.fill"
        case .failure: "xmark.circle.fill"
        case .cancelled: "minus.circle.fill"
        }
    }

    var color: Color {
        switch self {
        case .running, .queued: .orange
        case .success: .green
        case .failure: .red
        case .cancelled: .gray
        }
    }
}

extension Snapshot {
    var workflowRuns: [WorkflowRun] { runs ?? [] }

    var activeRuns: [WorkflowRun] { workflowRuns.filter(\.outcome.isActive) }
}
