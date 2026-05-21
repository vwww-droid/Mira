import SwiftUI
import Darwin

@main
struct MiraApp: App {
    private enum EnvironmentKeys {
        static let logSmokeTest = "MIRA_LOG_SMOKE_TEST"
    }

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var viewModel = MiraControlViewModel()

    init() {
        MiraDiagnostics.install()
        MiraNativeStatus.installDiagnosticsLogProvider()
        mira_ios_install_log_hooks()
        emitStandardStreamSmokeTestIfRequested()
    }

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
                .onChange(of: scenePhase) { nextPhase in
                    viewModel.handleScenePhase(nextPhase)
                }
        }
    }

    private func emitStandardStreamSmokeTestIfRequested() {
        guard ProcessInfo.processInfo.environment[EnvironmentKeys.logSmokeTest] == "1" else { return }
        "mira-ios-log-smoke stdout\n".withCString { pointer in
            _ = Darwin.write(STDOUT_FILENO, pointer, strlen(pointer))
        }
        "mira-ios-log-smoke stderr\n".withCString { pointer in
            _ = Darwin.write(STDERR_FILENO, pointer, strlen(pointer))
        }
    }
}
