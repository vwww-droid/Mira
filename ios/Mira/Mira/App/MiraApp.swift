import SwiftUI

@main
struct MiraApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: MiraControlViewModel())
        }
    }
}
