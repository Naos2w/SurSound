import SwiftUI

@main
struct SurSoundApp: App {
    var body: some Scene {
        WindowGroup { ReceiverView() }
    }
}

struct ReceiverView: View {
    @StateObject private var receiver = Receiver()
    @Environment(\.scenePhase) private var scenePhase
    @State private var peer = ""
    @State private var key = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Local sender") {
                    TextField("Sender private IPv4 address", text: $peer)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.numbersAndPunctuation)
                    SecureField("32-character hexadecimal key", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("Find this iPhone’s address in Settings → Wi-Fi → network details. Enter the Android sender’s address here and use the same fresh pairing key on both devices.")
                        .font(.footnote)
                }
                .disabled(receiver.active)
                Section("Playback diagnostic") {
                    Button("Listen (45 seconds)") {
                        receiver.start(peer: peer, secret: key)
                        key = ""
                    }
                    .disabled(receiver.active)
                    Button("Stop", role: .destructive) { receiver.stop() }
                        .disabled(!receiver.active)
                    Text(receiver.status)
                    Text("Authenticated packets: \(receiver.packetCount)")
                    Text("Missing / rejected / output drops: \(receiver.drops)")
                    Text("48 kHz stereo PCM16, encrypted local packets. This is receiver-only; no recording, cloud service or synchronized playback.")
                        .font(.footnote)
                }
            }
            .navigationTitle("SurSound PoC")
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { receiver.stop("Stopped on backgrounding."); key = "" }
        }
        .onDisappear { receiver.stop(); key = "" }
    }
}
