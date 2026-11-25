import Combine
import Darwin
import Foundation

/// Browse only explicitly advertised receivers, and only on request.
final class PeerDiscovery: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {
    struct Peer: Identifiable, Equatable {
        let id: String
        let name: String
        let address: String
    }

    @Published private(set) var peers: [Peer] = []
    @Published private(set) var status = "Discovery idle"
    private var browser: NetServiceBrowser?
    private var services: [String: NetService] = [:]
    private var timeout: DispatchWorkItem?

    func start() {
        stop()
        peers = []
        status = "Browsing local receivers..."
        let b = NetServiceBrowser()
        b.delegate = self
        browser = b
        b.searchForServices(ofType: "_sursound._udp.", inDomain: "local.")
        let end = DispatchWorkItem { [weak self] in self?.stop() }
        timeout = end
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: end)
    }

    func stop() {
        timeout?.cancel()
        timeout = nil
        browser?.delegate = nil
        browser?.stop()
        browser = nil
        for service in services.values {
            service.delegate = nil
            service.stop()
        }
        services.removeAll()
        peers = []
        status = "Discovery idle"
    }

    private func serviceID(_ service: NetService) -> String {
        service.name + "|" + service.type + "|" + service.domain
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard self.browser === browser, services.count < 24 else { return }
        let id = serviceID(service)
        guard services[id] == nil else { return }
        services[id] = service
        service.delegate = self
        service.resolve(withTimeout: 4)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        guard self.browser === browser else { return }
        let id = serviceID(service)
        services[id]?.delegate = nil
        services[id]?.stop()
        services.removeValue(forKey: id)
        peers.removeAll { $0.id == id }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String : NSNumber]) {
        guard self.browser === browser else { return }
        stop()
        status = "Discovery unavailable. Check Local Network permission or enter an IP manually."
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let id = serviceID(sender)
        guard services[id] === sender, sender.port == Int(LanPacket.port) else { return }
        for data in sender.addresses ?? [] {
            guard data.count >= MemoryLayout<sockaddr_in>.size else { continue }
            let host: String? = data.withUnsafeBytes { bytes in
                guard let base = bytes.baseAddress else { return nil }
                var addr = sockaddr_in()
                memcpy(&addr, base, MemoryLayout<sockaddr_in>.size)
                guard Int32(addr.sin_family) == AF_INET else { return nil }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                guard inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(buffer.count)) != nil else { return nil }
                return String(cString: buffer)
            }
            guard let address = host, LanPacket.localPeer(address) != nil else { continue }
            peers.removeAll { $0.id == id }
            peers.append(Peer(id: id, name: sender.name, address: address))
            peers.sort { $0.name < $1.name }
            status = "Choose a receiver; a fresh pairing key is still required."
            return
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        let id = serviceID(sender)
        guard services[id] === sender else { return }
        sender.stop()
        services.removeValue(forKey: id)
    }
}
