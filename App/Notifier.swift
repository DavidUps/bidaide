import AppKit
import UserNotifications

/// Posts macOS notifications and handles their clicks: open the PR, or mark the GitHub notification as read.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    private static let category = "pull-request"
    private static let markReadAction = "mark-read"

    static let defaultSound = "notification.caf"
    /// Party mode sound for a new assignment.
    static let assignSound = "party-assign.caf"

    private var center: UNUserNotificationCenter { .current() }

    func setUp() {
        center.delegate = self
        let markRead = UNNotificationAction(identifier: Self.markReadAction, title: "Marcar como leída")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: [markRead], intentIdentifiers: [])
        ])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func post(id: String, title: String, subtitle: String, body: String, url: String, threadID: String?,
              sound: String = Notifier.defaultSound) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.subtitle = subtitle
        content.body = body
        // Sounds bundled in App/Sounds are played by the app in `willPresent`; giving macOS one too makes it play its
        // default sound on top. Only an unknown name is left to macOS.
        if Bundle.main.url(forResource: sound, withExtension: nil) == nil {
            content.sound = UNNotificationSound(named: UNNotificationSoundName(sound))
        }
        // Only GitHub notifications can be marked as read.
        if threadID != nil { content.categoryIdentifier = Self.category }
        content.threadIdentifier = subtitle
        content.userInfo = ["url": url, "threadID": threadID ?? "", "sound": sound]
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    /// macOS does not always find sounds in the app bundle and plays its default one instead, so the app plays the
    /// sound itself. It still respects the user turning sounds off for the app in System Settings.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let name = notification.request.content.userInfo["sound"] as? String ?? ""
        guard let url = Bundle.main.url(forResource: name, withExtension: nil),
              let sound = NSSound(contentsOf: url, byReference: true)
        else {
            completionHandler([.banner, .sound, .list])
            return
        }
        center.getNotificationSettings { settings in
            if settings.soundSetting == .enabled {
                DispatchQueue.main.async { sound.play() }
            }
            completionHandler([.banner, .list])
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let threadID = info["threadID"] as? String ?? ""
        switch response.actionIdentifier {
        case Self.markReadAction:
            if !threadID.isEmpty {
                Task { @MainActor in await AppModel.shared.markRead(threadID: threadID) }
            }
        case UNNotificationDefaultActionIdentifier:
            if let string = info["url"] as? String, let url = URL(string: string) {
                NSWorkspace.shared.open(url)
            }
        default:
            break
        }
        completionHandler()
    }
}
