import AVFoundation
import CoreMedia
import Foundation
import ReplayKit

/// Legacy ReplayKit compatibility path. Process only .audioApp, never video or microphone.
final class SampleHandler: RPBroadcastSampleHandler {
    private let lock = NSRecursiveLock()
    private var sender: PacketSender?
    private var converter: AVAudioConverter?
    private let outputFormat = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
    private var packetizer = StereoPacketizer()
    private var timer: DispatchWorkItem?
    private var finished = false

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        lock.lock()
        defer { lock.unlock() }
        do {
            let request = try BroadcastRequest.take()
            guard let peer = LanPacket.localPeer(request.peer), let key = LanPacket.key(request.secret) else {
                throw NSError(domain: "SurSound.Broadcast", code: 4)
            }
            sender = PacketSender(peer: peer, key: key)
            let timeout = DispatchWorkItem { [weak self] in self?.end("30-second sharing test ended.") }
            timer = timeout
            DispatchQueue.global().asyncAfter(deadline: .now() + 30, execute: timeout)
        } catch {
            end("Prepare a fresh local pairing in SurSound and grant local-network access first. " + error.localizedDescription)
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
        guard sampleBufferType == .audioApp else { return }
        lock.lock()
        defer { lock.unlock() }
        guard !finished, let sender = sender else { return }
        if let failure = sender.snapshot().failure {
            end("Local transmission stopped: " + failure); return
        }
        guard CMSampleBufferDataIsReady(sampleBuffer),
              let description = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(description),
              asbd.pointee.mFormatID == kAudioFormatLinearPCM,
              let inputFormat = AVAudioFormat(streamDescription: asbd) else { return }
        let frames = CMSampleBufferGetNumSamples(sampleBuffer)
        guard frames > 0, frames <= 16_384, inputFormat.sampleRate >= 8_000,
              inputFormat.sampleRate <= 192_000, (1...2).contains(inputFormat.channelCount),
              let input = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: AVAudioFrameCount(frames)) else { return }
        input.frameLength = AVAudioFrameCount(frames)
        let status = CMSampleBufferCopyPCMDataIntoAudioBufferList(sampleBuffer, at: 0, frameCount: Int32(frames), into: input.mutableAudioBufferList)
        guard status == noErr else { return }
        if converter == nil || converter?.inputFormat != inputFormat {
            converter = AVAudioConverter(from: inputFormat, to: outputFormat)
        }
        let capacity = AVAudioFrameCount(ceil(Double(frames) * 48_000 / inputFormat.sampleRate) + 64)
        guard let converter = converter, let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
        var supplied = false
        var error: NSError?
        let result = converter.convert(to: output, error: &error) { _, state in
            if supplied { state.pointee = .noDataNow; return nil }
            supplied = true
            state.pointee = .haveData
            return input
        }
        guard result != .error, error == nil, let channels = output.floatChannelData else { return }
        for frame in 0..<Int(output.frameLength) {
            if let packet = packetizer.append(left: channels[0][frame], right: channels[1][frame]) {
                sender.offer(packet)
            }
        }
    }

    override func broadcastPaused() {
        lock.lock(); defer { lock.unlock() }
        // Do not automatically resume audio sharing after a pause.
        end("Sharing paused. Prepare a fresh session to resume.")
    }
    override func broadcastResumed() { }
    override func broadcastFinished() {
        lock.lock(); defer { lock.unlock() }
        cleanup()
    }

    private func cleanup() {
        finished = true
        timer?.cancel()
        timer = nil
        sender?.stop()
        sender = nil
        converter = nil
        packetizer.clear()
    }

    private func end(_ message: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !finished else { return }
        cleanup()
        finishBroadcastWithError(NSError(domain: "SurSound.Broadcast", code: 5, userInfo: [NSLocalizedDescriptionKey: message]))
    }
}
