import Foundation

struct GhError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Runs the GitHub CLI. Authentication is whatever `gh auth login` left in the keychain; the app keeps no tokens.
enum GhCli {
    static let executable: URL? = locate()

    private static func locate() -> URL? {
        let candidates = ["/opt/homebrew/bin/gh", "/usr/local/bin/gh", "/usr/bin/gh"]
        if let path = candidates.first(where: FileManager.default.isExecutableFile(atPath:)) {
            return URL(fileURLWithPath: path)
        }
        // GUI apps do not inherit the shell PATH, so ask a login shell.
        guard let data = try? execute(URL(fileURLWithPath: "/bin/zsh"), ["-lc", "command -v gh"]).out,
              let path = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              FileManager.default.isExecutableFile(atPath: path)
        else { return nil }
        return URL(fileURLWithPath: path)
    }

    static func run(_ arguments: [String]) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                continuation.resume(with: Result { try runBlocking(arguments) })
            }
        }
    }

    static func runBlocking(_ arguments: [String]) throws -> Data {
        guard let executable else {
            throw GhError(message: "No encuentro GitHub CLI. Instálala con «brew install gh».")
        }
        let result = try execute(executable, arguments)
        guard result.status == 0 else {
            let stderr = String(data: result.err, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if stderr.contains("gh auth login") || stderr.contains("not logged") {
                throw GhError(message: "Sin sesión en GitHub CLI. Ejecuta «gh auth login».")
            }
            throw GhError(message: stderr.isEmpty ? "gh terminó con código \(result.status)" : stderr)
        }
        return result.out
    }

    private static func execute(_ executable: URL, _ arguments: [String]) throws -> (status: Int32, out: Data, err: Data) {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        var environment = ProcessInfo.processInfo.environment
        environment["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:" + (environment["PATH"] ?? "")
        environment["GH_PROMPT_DISABLED"] = "1"
        environment["GH_NO_UPDATE_NOTIFIER"] = "1"
        environment["NO_COLOR"] = "1"
        process.environment = environment

        let out = Pipe(), err = Pipe()
        process.standardOutput = out
        process.standardError = err
        process.standardInput = FileHandle.nullDevice
        try process.run()

        // Drain stderr in parallel so a full pipe never blocks the child.
        var errData = Data()
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global().async {
            errData = err.fileHandleForReading.readDataToEndOfFile()
            group.leave()
        }
        let outData = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        group.wait()
        return (process.terminationStatus, outData, errData)
    }
}
