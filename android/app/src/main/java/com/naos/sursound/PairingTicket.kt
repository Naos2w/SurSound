package com.naos.sursound

/** Private, short-lived, out-of-band v1 receiver offer. Never log or persist this object. */
internal class PairingTicket private constructor(
    val peer: String,
    val expiresAtSeconds: Long,
    val keyHex: String,
    val live: Boolean
) {
    fun isValidAt(nowSeconds: Long): Boolean =
        nowSeconds >= 0 && expiresAtSeconds > nowSeconds && expiresAtSeconds - nowSeconds <= MAX_LIFETIME

    override fun toString(): String = "PairingTicket(redacted)"

    companion object {
        const val MAX_LIFETIME = 600L
        private val expiry = Regex("[1-9][0-9]{0,18}")
        private val hex = Regex("[0-9a-fA-F]{32}")

        fun parse(text: String, nowSeconds: Long = System.currentTimeMillis() / 1000): PairingTicket? {
            if (text.length !in 60..128) return null
            val parts = text.split('|')
            if (parts.size != 6 || parts[0] != "SURSOUND1" || parts[1] !in setOf("v1", "live") ||
                parts[3] != "39771" || !expiry.matches(parts[4]) || !hex.matches(parts[5])) return null
            val address = runCatching { LanPacketCodec.localPeer(parts[2]).hostAddress }.getOrNull() ?: return null
            if (address != parts[2]) return null
            val end = parts[4].toLongOrNull() ?: return null
            val ticket = PairingTicket(address, end, parts[5].lowercase(java.util.Locale.ROOT), parts[1] == "live")
            return ticket.takeIf { it.isValidAt(nowSeconds) }
        }
    }
}
