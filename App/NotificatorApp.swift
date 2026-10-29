import AppKit
import SwiftUI

@main
struct NotificatorApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra {
            MenuView()
                .environmentObject(model)
        } label: {
            MenuBarLabel(count: model.actionCount, hasError: model.lastError != nil)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView()
                .environmentObject(model)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        Preferences.registerDefaults()
        Notifier.shared.setUp()
        Task { @MainActor in AppModel.shared.start() }
    }

    /// Links coming from the widget: `bidaidenotificator://open?url=…`.
    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls {
            guard let target = DeepLink.target(of: url) else { continue }
            NSWorkspace.shared.open(target.url)
            if let threadID = target.threadID {
                Task { @MainActor in await AppModel.shared.markRead(threadID: threadID) }
            }
        }
    }
}

struct MenuBarLabel: View {
    let count: Int
    let hasError: Bool

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: hasError ? "exclamationmark.triangle" : "arrow.triangle.pull")
            if count > 0 { Text("\(count)") }
        }
    }
}
