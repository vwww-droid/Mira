import Foundation

struct MiraNativeStatus {
    let backendName: String
    let ptyLifecycle: String

    static let pending = MiraNativeStatus(
        backendName: "ios-ui-placeholder",
        ptyLifecycle: "pending fork exec integration"
    )
}
