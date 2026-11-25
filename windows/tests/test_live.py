import socket
import struct
import unittest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from sursound_receiver.live import Control, LiveWire, LiveSession, PROBE, ACK, STOP
from sursound_receiver.protocol import PCM_BYTES
from sursound_receiver.pairing import PairingOffer, service_info

KEY = bytes.fromhex("00112233445566778899aabbccddeeff")  # public synthetic vector only
SESSION = 0x0102030405060708
PEER = ("192.168.1.20", 45000)
PCM = struct.pack("<h", 123) * (PCM_BYTES // 2)

def audio(wire, generation, sequence, pcm=PCM):
    header = struct.pack(">4sBQI", b"SSP0", 1, 123, sequence)
    return header + AESGCM(wire.audio_key(SESSION, generation)).encrypt(header[5:17], pcm, header)

class LiveTests(unittest.TestCase):
    def setUp(self):
        self.wire = LiveWire(KEY)
        self.receiver = LiveSession(PEER[0], KEY)

    def probe(self, generation, counter, now, peer=PEER, session=SESSION):
        return self.receiver.receive(self.wire.seal(Control(PROBE, session, generation, counter)), peer, now)

    def test_independent_vector_and_tamper(self):
        c = Control(PROBE, SESSION, 2, 7)
        data = self.wire.seal(c)
        self.assertEqual(data.hex(), "534c4331010102030405060708000000020000000000000007a1d2c3ffbc562c5c37a4ca983e7c29d4d05a2d4ec04c9acf348d5fc8eda384c9")
        self.assertEqual(self.wire.audio_key(SESSION, 2).hex(), "1d50ec1c429d167fbbd0237c1c94859e")
        self.assertEqual(self.wire.open(data), c)
        self.assertNotEqual(self.wire.audio_key(SESSION, 2), self.wire.audio_key(SESSION, 3))
        self.assertIsNone(LiveWire(bytes(16)).open(data))
        for i in (0, 4, 12, 16, 24, 25, len(data)-1):
            changed = bytearray(data)
            changed[i] ^= 1
            self.assertIsNone(self.wire.open(bytes(changed)))
        self.assertIsNone(self.wire.open(data[:-1]))

    def test_no_audio_before_probe_and_replay_rejection(self):
        r = self.receiver
        r.receive(audio(self.wire, 0, 0), PEER, 0)
        self.assertEqual(r.blocked_audio, 1)
        self.assertFalse(r.ready)
        self.assertIsNone(self.probe(0, 1, .1, ("192.168.1.21", 45000)))
        ack = self.probe(0, 1, .2)
        self.assertEqual(self.wire.open(ack), Control(ACK, SESSION, 0, 1))
        self.assertTrue(r.ready)
        self.assertTrue(r.consume_flush())
        self.assertIsNone(self.probe(0, 1, .3))
        self.assertIsNone(self.probe(0, 2, .3, (PEER[0], 45001)))
        self.assertIsNone(self.probe(0, 2, .3, session=SESSION+1))
        r.receive(audio(self.wire, 0, 0), PEER, .4)
        r.receive(audio(self.wire, 0, 0), PEER, .5)
        self.assertEqual(r.stats.admitted, 1)
        self.assertEqual(r.stats.sequence_rejected, 1)

    def test_recovery_discards_pending_and_old_generation(self):
        r = self.receiver
        self.probe(0, 1, 0)
        self.assertTrue(r.consume_flush())
        for i in range(32):
            r.receive(audio(self.wire, 0, i), PEER, .1)
        self.assertEqual(len(r.playout.pending), 32)
        r.tick(1.6)
        self.assertFalse(r.ready)
        self.assertTrue(r.consume_flush())
        self.assertEqual(r.discarded, 32)
        self.assertFalse(r.playout.pending)
        self.assertIsNone(self.probe(0, 2, 1.7))
        r.receive(audio(self.wire, 0, 32), PEER, 1.8)
        ack = self.probe(1, 3, 2.0)
        self.assertEqual(self.wire.open(ack), Control(ACK, SESSION, 1, 3))
        self.assertTrue(r.ready)
        self.assertEqual(r.recoveries, 1)
        self.assertTrue(r.consume_flush())
        self.assertIsNone(self.probe(0, 4, 2.1))
        r.receive(audio(self.wire, 0, 33), PEER, 2.2)
        self.assertEqual(r.stats.authentication_failed, 1)
        for i in range(32):
            r.receive(audio(self.wire, 1, i), PEER, 2.3)
        self.assertEqual(r.playout.next_chunk(0), PCM*4)
        self.assertEqual(r.stats.admitted, 64)

    def test_audio_gap_forces_new_generation_even_with_control(self):
        r = self.receiver
        self.probe(0, 1, 0)
        r.receive(audio(self.wire, 0, 0), PEER, .1)
        self.probe(0, 2, .5)
        self.probe(0, 3, 1.0)
        r.tick(1.7)
        self.assertFalse(r.ready)
        self.assertIsNone(self.probe(0, 4, 1.8))
        self.assertIsNotNone(self.probe(1, 5, 2.0))

    def test_stop_and_recovery_deadlines_are_terminal(self):
        r = self.receiver
        self.probe(0, 1, 0)
        bad_stop = bytearray(self.wire.seal(Control(STOP, SESSION, 0, 2)))
        bad_stop[-1] ^= 1
        r.receive(bytes(bad_stop), PEER, .1)
        self.assertTrue(r.ready)
        r.receive(self.wire.seal(Control(STOP, SESSION, 0, 1)), PEER, .2)
        self.assertTrue(r.ready)
        r.receive(self.wire.seal(Control(STOP, SESSION, 0, 2)), PEER, .3)
        self.assertTrue(r.closed)
        self.assertTrue(r.consume_flush())
        self.assertIsNone(self.probe(1, 3, .4))
        r2 = LiveSession(PEER[0], KEY)
        r2.receive(self.wire.seal(Control(PROBE, SESSION, 0, 1)), PEER, 0)
        r2.tick(1.5)
        r2.tick(11.5)
        self.assertTrue(r2.closed)
        r3 = LiveSession(PEER[0], KEY)
        r3.receive(self.wire.seal(Control(PROBE, SESSION, 0, 1)), PEER, 0)
        r3.tick(3600)
        self.assertTrue(r3.closed)
        self.assertIn("60-minute", r3.reason)

    def test_live_offer_is_explicit(self):
        offer = PairingOffer.create(PEER[0], KEY, 1790000000, 600, mode="live")
        self.assertEqual(offer.payload(), "SURSOUND1|live|192.168.1.20|39771|1790000600|00112233445566778899aabbccddeeff")
        self.assertEqual(service_info(PEER[0], "live").properties, {b"version": b"1", b"mode": b"live"})
        self.assertNotIn(KEY.hex(), repr(offer))

    def test_real_udp_loopback_probe_audio_stop(self):
        # Synthetic localhost test; no physical speaker, media capture or user network data.
        r = LiveSession("127.0.0.1", KEY)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as receiver, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            receiver.bind(("127.0.0.1", 0))
            sender.bind(("127.0.0.1", 0))
            receiver.settimeout(1)
            sender.settimeout(1)
            address = receiver.getsockname()
            sender.sendto(self.wire.seal(Control(PROBE, SESSION, 0, 1)), address)
            data, peer = receiver.recvfrom(2048)
            reply = r.receive(data, peer, 0)
            receiver.sendto(reply, peer)
            self.assertEqual(self.wire.open(sender.recvfrom(2048)[0]), Control(ACK, SESSION, 0, 1))
            sender.sendto(audio(self.wire, 0, 0), address)
            data, peer = receiver.recvfrom(2048)
            r.receive(data, peer, .1)
            self.assertEqual(r.stats.admitted, 1)
            sender.sendto(self.wire.seal(Control(STOP, SESSION, 0, 2)), address)
            data, peer = receiver.recvfrom(2048)
            r.receive(data, peer, .2)
            self.assertTrue(r.closed)

if __name__ == "__main__":
    unittest.main()
