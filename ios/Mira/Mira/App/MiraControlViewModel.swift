import Foundation

final class MiraControlViewModel: ObservableObject {
    private enum DefaultsKey {
        static let relayURL = "relay_url"
    }

    @Published var relayURL: String {
        didSet {
            UserDefaults.standard.set(relayURL, forKey: DefaultsKey.relayURL)
        }
    }

    @Published private(set) var statusText: String = "disconnected"

    init() {
        relayURL = UserDefaults.standard.string(forKey: DefaultsKey.relayURL) ?? ""
    }

    func connectRelay() {
        let normalized = relayURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            statusText = "relay url required"
            return
        }
        relayURL = normalized
        statusText = "relay UI ready"
    }

    func disconnectRelay() {
        statusText = "disconnected"
    }
}
