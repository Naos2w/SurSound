package com.naos.sursound

import org.junit.Assert.*
import org.junit.Test

class PairingTicketTest {
    private val now = 1_790_000_000L
    private val key = "00112233445566778899aabbccddeeff" // synthetic vector, not a real session
    private fun offer(ip: String = "192.168.1.20", end: Long = now + 600, secret: String = key) =
        "SURSOUND1|v1|$ip|39771|$end|$secret"

    @Test fun sharedVectorAndExpiry() {
        val ticket = requireNotNull(PairingTicket.parse(offer(), now))
        assertEquals("192.168.1.20", ticket.peer)
        assertEquals(key, ticket.keyHex)
        assertTrue(ticket.isValidAt(now + 599))
        assertFalse(ticket.isValidAt(now + 600))
        assertFalse(ticket.toString().contains(key))
        assertNull(PairingTicket.parse(offer(end = now), now))
        assertNull(PairingTicket.parse(offer(end = now + 601), now))
    }

    @Test fun rejectsUnsafeAndMalformedTickets() {
        for (value in listOf(offer(ip = "8.8.8.8"), offer(ip = "127.0.0.1"),
            offer(ip = "192.168.001.20"), offer(secret = "123456"), offer(secret = "g".repeat(32)),
            offer().replace("|v1|", "|v2|"), offer().replace("|39771|", "|80|"),
            offer() + "|extra", "https://example.com", offer(end = Long.MAX_VALUE),
            offer().replace("|1790000600|", "|+1790000600|"))) {
            assertNull(PairingTicket.parse(value, now))
        }
        assertEquals(key, PairingTicket.parse(offer(secret = key.uppercase()), now)?.keyHex)
    }
}
