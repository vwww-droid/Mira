import Foundation
import Darwin
import OSLog

nonisolated(unsafe) private var miraDiagnosticsLogFD: Int32 = -1

private func miraDiagnosticsWriteCrashLine(_ line: String) {
    guard miraDiagnosticsLogFD >= 0 else { return }
    line.withCString { pointer in
        _ = Darwin.write(miraDiagnosticsLogFD, pointer, strlen(pointer))
    }
}

private func miraDiagnosticsSignalHandler(_ signalNumber: Int32) {
    miraDiagnosticsWriteCrashLine("crash signal \(signalNumber)\n")
    Darwin.fsync(miraDiagnosticsLogFD)
    Darwin.signal(signalNumber, SIG_DFL)
    Darwin.raise(signalNumber)
}

private func miraDiagnosticsExceptionHandler(_ exception: NSException) {
    MiraDiagnostics.recordUncaughtException(exception)
}

/// iOS LOGS 按 Android 三方 App 可见范围收口: App 主动诊断, stdout/stderr 的 write hook, crash 记录, 以及当前进程内 App/三方依赖的 unified log.
/// unified log 读取仅使用 OSLogStore.currentProcessIdentifier scope, 并默认排除 com.apple.* 框架噪声, 不接收 Relay 的 server/control/device events.
/// 当前保持轻量文件 sink, 后续如需要可替换为 CocoaLumberjack 文件日志库, 但本阶段不引入完整日志框架.
enum MiraDiagnostics {
    private static let queue = DispatchQueue(label: "MiraDiagnostics")
    private static let maxMessageCharacters = 2_000
    private static let maxDetailsCharacters = 2_000
    private static let maxUnifiedLogEntries = 300
    nonisolated(unsafe) private static var installed = false
    nonisolated(unsafe) private static var currentLogURL: URL?
    nonisolated(unsafe) private static var previousLogURL: URL?
    nonisolated(unsafe) private static var installedAt: Date?

    static func install() {
        queue.sync {
            guard !installed else { return }
            installed = true

            let fileManager = FileManager.default
            guard let supportDirectory = try? fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ) else { return }

            let logDirectory = supportDirectory.appendingPathComponent("Mira/Logs", isDirectory: true)
            try? fileManager.createDirectory(at: logDirectory, withIntermediateDirectories: true)

            let current = logDirectory.appendingPathComponent("current.log")
            let previous = logDirectory.appendingPathComponent("previous.log")
            try? fileManager.removeItem(at: previous)
            if fileManager.fileExists(atPath: current.path) {
                try? fileManager.moveItem(at: current, to: previous)
            }

            currentLogURL = current
            previousLogURL = previous
            installedAt = Date()
            miraDiagnosticsLogFD = Darwin.open(current.path, O_WRONLY | O_CREAT | O_APPEND, S_IRUSR | S_IWUSR)
            mira_ios_log_hook_set_capture_fd(miraDiagnosticsLogFD)

            NSSetUncaughtExceptionHandler(miraDiagnosticsExceptionHandler)
            for signalNumber in [SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP] {
                Darwin.signal(signalNumber, miraDiagnosticsSignalHandler)
            }

            writeLineLocked(formatLine(level: "INFO", scope: "diagnostics", message: "installed", details: [
                "currentLog": current.path,
                "previousLog": previous.path,
            ]))
        }
    }

    static func log(level: String = "INFO", scope: String, message: String, details: [String: Any] = [:]) {
        let line = formatLine(level: level, scope: scope, message: message, details: details)
        queue.async {
            writeLineLocked(line)
        }
    }

    static func logSnapshot(maxBytes: Int = 64_000) -> String {
        let currentBudget = max(1, maxBytes / 2)
        let unifiedBudget = max(1, maxBytes - currentBudget)
        let current = queue.sync {
            readTailLocked(url: currentLogURL, maxBytes: currentBudget)
        }
        let unified = unifiedLogSnapshot(maxBytes: unifiedBudget)
        return [current, unified].filter { !$0.isEmpty }.joined()
    }

    static func emitUnifiedLogSmokeTest() {
        if #available(iOS 15.0, *) {
            let logger = Logger(subsystem: "com.vwww.mira.ios", category: "diagnostics")
            logger.notice("mira-ios-log-smoke oslog notice")
            logger.error("mira-ios-log-smoke oslog error")
        }
    }

    static func recordUncaughtException(_ exception: NSException) {
        let stack = exception.callStackSymbols.prefix(32).joined(separator: "\n")
        let details: [String: Any] = [
            "name": exception.name.rawValue,
            "reason": exception.reason ?? "",
            "stack": stack,
        ]
        let line = formatLine(level: "FATAL", scope: "crash.exception", message: "uncaught exception", details: details)
        queue.sync {
            writeLineLocked(line)
            if miraDiagnosticsLogFD >= 0 {
                Darwin.fsync(miraDiagnosticsLogFD)
            }
        }
    }

    static func captureWrite(fd: Int32, bytes: UnsafeRawPointer?, count: Int) {
        guard let bytes, count > 0 else { return }
        let maxCount = min(count, 4096)
        let data = Data(bytes: bytes, count: maxCount)
        guard let raw = String(data: data, encoding: .utf8) else { return }
        let tag = fd == STDERR_FILENO ? "stderr" : "stdout"
        for line in raw.split(whereSeparator: \.isNewline) {
            let text = String(line).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            log(level: fd == STDERR_FILENO ? "WARN" : "INFO", scope: tag, message: text)
        }
    }

    private static func readTailLocked(url: URL?, maxBytes: Int) -> String {
        guard let url, let data = try? Data(contentsOf: url), !data.isEmpty else {
            return ""
        }
        let tailData = data.count > maxBytes ? data.suffix(maxBytes) : data[...]
        return String(data: Data(tailData), encoding: .utf8) ?? ""
    }

    private static func unifiedLogSnapshot(maxBytes: Int) -> String {
        guard #available(iOS 15.0, *) else { return "" }
        let startDate = queue.sync {
            installedAt?.addingTimeInterval(-2) ?? Date().addingTimeInterval(-120)
        }
        do {
            let store = try OSLogStore(scope: .currentProcessIdentifier)
            let position = store.position(date: startDate)
            let entries = try store.getEntries(at: position)
            var lines: [String] = []
            lines.reserveCapacity(64)
            for entry in entries {
                guard let log = entry as? OSLogEntryLog else { continue }
                guard shouldIncludeUnifiedLog(log) else { continue }
                lines.append(formatUnifiedLogLine(log))
                if lines.count > maxUnifiedLogEntries {
                    lines.removeFirst(lines.count - maxUnifiedLogEntries)
                }
            }
            return tailText(lines.joined(), maxBytes: maxBytes)
        } catch {
            return formatLine(level: "WARN", scope: "oslog", message: "unified log unavailable", details: [
                "scope": "currentProcessIdentifier",
                "error": String(describing: error),
            ])
        }
    }

    @available(iOS 15.0, *)
    private static func shouldIncludeUnifiedLog(_ entry: OSLogEntryLog) -> Bool {
        let subsystem = singleLine(entry.subsystem)
        guard !subsystem.isEmpty else { return false }
        if let bundleIdentifier = Bundle.main.bundleIdentifier,
           (subsystem == bundleIdentifier || subsystem.hasPrefix("\(bundleIdentifier).")) {
            return true
        }
        return !subsystem.hasPrefix("com.apple.")
    }

    @available(iOS 15.0, *)
    private static func formatUnifiedLogLine(_ entry: OSLogEntryLog) -> String {
        let subsystem = singleLine(entry.subsystem)
        let category = singleLine(entry.category)
        let message = singleLine(entry.composedMessage)
        let prefix = [subsystem, category].filter { !$0.isEmpty }.joined(separator: "/")
        let decorated = prefix.isEmpty ? message : "[\(prefix)] \(message)"
        return "\(timestamp(entry.date)) \(unifiedLogLevel(entry.level))/oslog(\(getpid())): \(truncated(decorated, maxCharacters: maxMessageCharacters))\n"
    }

    @available(iOS 15.0, *)
    private static func unifiedLogLevel(_ level: OSLogEntryLog.Level) -> String {
        switch level {
        case .debug: return "D"
        case .info, .notice, .undefined: return "I"
        case .error: return "E"
        case .fault: return "F"
        @unknown default: return "I"
        }
    }

    private static func formatLine(level: String, scope: String, message: String, details: [String: Any]) -> String {
        var suffix = ""
        if !details.isEmpty,
           JSONSerialization.isValidJSONObject(details),
           let data = try? JSONSerialization.data(withJSONObject: details, options: [.sortedKeys]),
           let text = String(data: data, encoding: .utf8) {
            suffix = " \(truncated(text, maxCharacters: maxDetailsCharacters))"
        }
        let tag = scope.isEmpty ? "Mira" : scope
        return "\(timestamp()) \(logcatLevel(level))/\(tag)(\(getpid())): \(truncated(singleLine(message), maxCharacters: maxMessageCharacters))\(suffix)\n"
    }

    private static func timestamp(_ date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "MM-dd HH:mm:ss.SSS"
        return formatter.string(from: date)
    }

    private static func logcatLevel(_ level: String) -> String {
        switch level.uppercased() {
        case "ERROR": return "E"
        case "WARN", "WARNING": return "W"
        case "DEBUG": return "D"
        case "FATAL": return "F"
        default: return "I"
        }
    }

    private static func writeLineLocked(_ line: String) {
        guard miraDiagnosticsLogFD >= 0 else { return }
        line.withCString { pointer in
            _ = Darwin.write(miraDiagnosticsLogFD, pointer, strlen(pointer))
        }
    }

    private static func truncated(_ text: String, maxCharacters: Int) -> String {
        guard text.count > maxCharacters else { return text }
        return String(text.prefix(maxCharacters)) + "...<truncated>"
    }

    private static func singleLine(_ text: String) -> String {
        text.replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func tailText(_ text: String, maxBytes: Int) -> String {
        guard maxBytes > 0, let data = text.data(using: .utf8), data.count > maxBytes else {
            return text
        }
        let tailData = data.suffix(maxBytes)
        return String(data: Data(tailData), encoding: .utf8) ?? String(text.suffix(maxBytes))
    }
}

@_cdecl("mira_diagnostics_capture_write")
func miraDiagnosticsCaptureWrite(_ fd: Int32, _ bytes: UnsafeRawPointer?, _ count: Int) {
    MiraDiagnostics.captureWrite(fd: fd, bytes: bytes, count: count)
}
