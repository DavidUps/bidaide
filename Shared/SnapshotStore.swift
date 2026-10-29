import Foundation

/// The app writes the snapshot, the widget reads it. Both resolve the real home folder, since inside the
/// widget sandbox `NSHomeDirectory()` points at the container.
enum SnapshotStore {
    static var directory: URL {
        let home: URL
        if let entry = getpwuid(getuid()), let dir = entry.pointee.pw_dir {
            home = URL(fileURLWithPath: String(cString: dir), isDirectory: true)
        } else {
            home = FileManager.default.homeDirectoryForCurrentUser
        }
        return home.appendingPathComponent("Library/Application Support/BidaideNotificator", isDirectory: true)
    }

    static var fileURL: URL { directory.appendingPathComponent("snapshot.json") }

    static func load() -> Snapshot? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(Snapshot.self, from: data)
    }

    static func save(_ snapshot: Snapshot) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(snapshot).write(to: fileURL, options: .atomic)
    }
}
