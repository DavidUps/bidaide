import AppKit
import UserNotifications

/// Posts macOS notifications and handles their clicks: open the PR, or mark the GitHub notification as read.
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    private static let category = "pull-request"
    private static let markReadAction = "mark-read"

    private var center: UNUserNotificationCenter { .current() }

    func setUp() {
        center.delegate = self
        let markRead = UNNotificationAction(identifier: Self.markReadAction, title: "Marcar como leída")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: Self.category, actions: [markRead], intentIdentifiers: [])
        ])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func post(id: String, title: String, subtitle: String, body: String, url: String, threadID: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.subtitle = subtitle
        content.body = body
        content.sound = .default
        content.categoryIdentifier = Self.category
        content.threadIdentifier = subtitle
        content.userInfo = ["url": url, "threadID": threadID ?? ""]
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
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
