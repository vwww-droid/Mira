import SwiftUI

struct ContentView: View {
    @ObservedObject var viewModel: MiraControlViewModel
    @FocusState private var relayFieldFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                titleRow
                Spacer(minLength: 120)
                controls
                Spacer(minLength: 120)
            }
            .frame(maxWidth: .infinity, minHeight: UIScreen.main.bounds.height - 48)
            .padding(.horizontal, 36)
            .padding(.top, 64)
            .padding(.bottom, 36)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color(.systemBackground))
    }

    private var titleRow: some View {
        HStack(alignment: .center) {
            Text("Mira")
                .font(.system(size: 44, weight: .bold, design: .serif))
                .tracking(1.8)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text("by vw2x")
                .font(.custom("AvenirNextCondensed-Regular", size: 13))
                .tracking(1.6)
                .frame(alignment: .trailing)
        }
    }

    private var controls: some View {
        VStack(spacing: 16) {
            TextField("Relay URL", text: $viewModel.relayURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .keyboardType(.URL)
                .focused($relayFieldFocused)
                .font(.system(size: 17, weight: .light, design: .default))
                .padding(.vertical, 18)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(Color(.separator))
                        .frame(height: 1)
                }
                .submitLabel(.done)
                .onSubmit {
                    relayFieldFocused = false
                }

            Button {
                relayFieldFocused = false
                viewModel.connectRelay()
            } label: {
                Text("Connect Relay")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button {
                relayFieldFocused = false
                viewModel.disconnectRelay()
            } label: {
                Text("Disconnect")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)

            Text("Status: \(viewModel.statusText)")
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 2)
        }
        .frame(maxWidth: 520)
    }
}

#Preview {
    ContentView(viewModel: MiraControlViewModel())
}
