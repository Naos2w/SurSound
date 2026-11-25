import CryptoKit
import SwiftUI

@main
struct SurSoundApp: App {
    var body: some Scene {
        WindowGroup { ReceiverView() }
    }
}

/// Diagnostic interface only; production design is intentionally deferred.
struct ReceiverView: View {
    @StateObject private var receiver = Receiver()
    @StateObject private var tone = ToneSender()
    @StateObject private var discovery = PeerDiscovery()
    @Environment(\.scenePhase) private var scenePhase
    @State private var peer = ""
    @State private var key = ""
    @State private var prepared = false
    @State private var preparationMessage = "Ready"
    @State private var preparationID = UUID()

    var body: some View {
        NavigationStack {
            Form {
                Section("Local peer") {
                    TextField("Other device private IPv4 address", text: $peer)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.numbersAndPunctuation)
                    TextField("32-character hexadecimal pairing key", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Generate fresh key") {
                        key = SymmetricKey(size: .bits128).withUnsafeBytes {
                            $0.map { String(format: "%02x", $0) }.joined()
                        }
                    }
                    Text("For receiving, enter the sender's address. For sending, choose the receiver's address. Privately share a fresh key for each test.")
                        .font(.footnote)
                }
                .disabled(receiver.active || tone.active || prepared)
                Section("Discover receivers") {
                    Button("Find local receivers (15 s)") { discovery.start() }
                    Text(discovery.status).font(.footnote)
                    ForEach(discovery.peers) { device in
                        Button(device.name + " — " + device.address) {
                            peer = device.address
                            discovery.stop()
                        }
                    }
                }
                .disabled(receiver.active || tone.active || prepared)
                Section("Receive audio") {
                    Button("Listen (45 seconds)") {
                        discovery.stop()
                        receiver.start(peer: peer, secret: key)
                        key = ""
                    }
                    .disabled(receiver.active || tone.active || prepared)
                    Button("Stop listening", role: .destructive) { receiver.stop() }
                        .disabled(!receiver.active)
                    Text(receiver.status)
                    Text("Authenticated packets: \(receiver.packetCount)")
                    Text("Missing / rejected / scheduler / output drops: \(receiver.drops)")
                }
                Section("Send synthetic tone") {
                    Button("Send test tone (30 seconds)") {
                        discovery.stop()
                        tone.start(peer: peer, secret: key)
                        key = ""
                    }
                    .disabled(receiver.active || tone.active || prepared)
                    Button("Stop tone", role: .destructive) { tone.stop() }
                        .disabled(!tone.active)
                    Text(tone.status)
                }
                Section("Share permitted app audio (legacy ReplayKit)") {
                    Text("Grant Local Network access using Find local receivers first. Prepare a fresh pairing, then tap the system broadcast control and confirm Start Broadcast. Only permitted app audio is transmitted; microphone and screen frames are discarded. Use the system recording indicator to stop. The PoC stops after 30 seconds.")
                        .font(.footnote)
                    Button("Prepare 30-second share") { prepare() }
                        .disabled(receiver.active || tone.active || prepared)
                    if prepared {
                        BroadcastPicker().frame(width: 56, height: 56)
                        Button("Cancel prepared pairing", role: .destructive) { cancelPreparation() }
                    }
                    Text(preparationMessage).font(.footnote)
                }
            }
            .navigationTitle("SurSound PoC")
        }
        .onAppear { BroadcastRequest.cancel() }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                receiver.stop("Stopped on backgrounding.")
                tone.stop("Stopped on backgrounding.")
                discovery.stop()
                key = ""
                // The explicitly started system broadcast extension has its own timeout.
            }
        }
        .onDisappear {
            receiver.stop()
            tone.stop()
            discovery.stop()
            cancelPreparation()
            key = ""
        }
    }

    private func prepare() {
        discovery.stop()
        do {
            try BroadcastRequest.prepare(peer: peer, secret: key)
            key = ""
            prepared = true
            preparationMessage = "Prepared. Tap the system broadcast control within 90 seconds."
            let id = UUID()
            preparationID = id
            DispatchQueue.main.asyncAfter(deadline: .now() + 90) {
                guard preparationID == id else { return }
                cancelPreparation()
                preparationMessage = "Pairing expired. Prepare a new session."
            }
        } catch {
            BroadcastRequest.cancel()
            preparationMessage = error.localizedDescription
        }
    }

    private func cancelPreparation() {
        BroadcastRequest.cancel()
        preparationID = UUID()
        prepared = false
        preparationMessage = "No pending pairing. Stop an active broadcast from the system recording indicator."
    }
}
