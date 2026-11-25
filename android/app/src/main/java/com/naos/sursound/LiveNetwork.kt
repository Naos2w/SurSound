package com.naos.sursound

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Capture the originally selected, on-link Wi-Fi path. Never migrate to another network. */
internal class LiveNetwork private constructor(
    private val manager: ConnectivityManager,
    private val network: Network,
    private val address: LinkAddress,
    private val peer: InetAddress
) {
    fun bind(socket: DatagramSocket) = network.bindSocket(socket)

    fun stillValid(): Boolean {
        if (manager.activeNetwork != network) return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        val links = manager.getLinkProperties(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            address in links.linkAddresses &&
            Ipv4Subnet.contains(address.address, address.prefixLength, peer)
    }

    companion object {
        fun select(context: Context, peer: InetAddress): LiveNetwork {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: error("Network service unavailable")
            val network = manager.activeNetwork ?: error("Connect to the receiver's Wi-Fi first")
            val caps = manager.getNetworkCapabilities(network) ?: error("Wi-Fi unavailable")
            check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                "Live mode requires the selected local Wi-Fi network, without VPN routing."
            }
            val address = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && it.address != peer &&
                    Ipv4Subnet.contains(it.address, it.prefixLength, peer)
            } ?: error("Receiver must be on the same IPv4 Wi-Fi subnet.")
            return LiveNetwork(manager, network, address, peer).also {
                check(it.stillValid()) { "Selected Wi-Fi changed before sharing." }
            }
        }
    }
}
