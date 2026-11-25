import ReplayKit
import SwiftUI

struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = "com.naos.sursound.receiver.broadcast"
        picker.showsMicrophoneButton = false
        return picker
    }
    func updateUIView(_ view: RPSystemBroadcastPickerView, context: Context) { }
}
