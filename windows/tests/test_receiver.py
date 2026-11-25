import hashlib
import struct
import unittest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sursound_receiver.protocol import V1Decoder, PCM_BYTES, SILENCE, local_ipv4
from sursound_receiver.playout import Playout
from sursound_receiver.session import Session, signal_stats

# Public synthetic test vector, not a real session key or captured media.
KEY = bytes.fromhex("00112233445566778899aabbccddeeff")
SID = 0x0102030405060708
PCM = bytes(i % 251 for i in range(PCM_BYTES))
PEER = ("192.168.1.2", 30000)

def seal(seq, sid=SID, key=KEY, pcm=PCM):
    head = struct.pack(">4sBQI", b"SSP0", 1, sid, seq)
    return head+AESGCM(key).encrypt(head[5:17], pcm, head)

class ProtocolTests(unittest.TestCase):
    def test_golden_vector(self):
        packet = seal(42)
        self.assertEqual(hashlib.sha256(packet).hexdigest(),
                         "0d9cb39a5141757e2f4903d4ba8762840cbb82416acb5ccdea12496bc2259535")
        decoded = V1Decoder(KEY).decode(packet)
        self.assertEqual((decoded.session, decoded.sequence, decoded.pcm), (SID,42,PCM))

    def test_tamper_wrong_key_and_length(self):
        packet = seal(0)
        decoder = V1Decoder(KEY)
        for i in (5,16,17,len(packet)-1):
            bad = bytearray(packet); bad[i] ^= 1
            self.assertIsNone(decoder.decode(bytes(bad)))
        self.assertIsNone(decoder.decode(packet[:-1]))
        self.assertIsNone(V1Decoder(bytes(16)).decode(packet))

    def test_private_peer_validation(self):
        self.assertEqual(local_ipv4("192.168.1.3"), "192.168.1.3")
        for bad in ("8.8.8.8","127.0.0.1","example.com","0.0.0.0","100.64.0.1","::1"):
            with self.assertRaises(ValueError): local_ipv4(bad)

class SessionTests(unittest.TestCase):
    def test_authentication_before_endpoint_lock(self):
        s = Session(PEER[0], KEY)
        self.assertFalse(s.receive(seal(0,key=bytes(16)),PEER,0))
        self.assertIsNone(s.endpoint)
        self.assertTrue(s.receive(seal(0),PEER,1))
        self.assertFalse(s.receive(seal(1),("192.168.1.3",30000),2))
        self.assertFalse(s.receive(seal(1),(PEER[0],30001),2))
        self.assertFalse(s.receive(seal(1,sid=7),PEER,2))
        self.assertFalse(s.receive(seal(0),PEER,2))
        self.assertEqual(s.stats.admitted,1)
        self.assertEqual(s.stats.authentication_failed,1)
        self.assertEqual(s.stats.wrong_source,2)
        self.assertEqual(s.stats.sequence_rejected,2)
        self.assertEqual(s.last_valid,1)
        self.assertFalse(s.receive(b"SSP2\x02"+bytes(996),PEER,3))
        self.assertEqual(s.stats.group_v2,1)

    def test_signal_stats(self):
        self.assertEqual(signal_stats(struct.pack("<hhhh",0,-32768,1,123)), (3,32768))

class PlayoutTests(unittest.TestCase):
    def test_reorder_window_prebuffer_and_no_timer_skip(self):
        p = Playout()
        self.assertTrue(p.admit(0,PCM))
        for i in reversed(range(1,32)): self.assertTrue(p.admit(i,PCM))
        self.assertFalse(p.admit(32+128,PCM))
        self.assertFalse(p.admit(31,PCM))
        self.assertIsNone(p.next_chunk(4))
        for _ in range(8): self.assertEqual(p.next_chunk(0), PCM*4)
        self.assertTrue(p.drained())
        self.assertFalse(p.admit(1,PCM))
        self.assertIsNone(p.next_chunk(0))
        self.assertEqual(p.rebuffer,1)
        self.assertTrue(p.admit(32,PCM))
        self.assertIsNone(p.next_chunk(0))
        self.assertEqual(p.next_chunk(0,ended=True),PCM+SILENCE*3)
        self.assertEqual(p.expected,33)
        self.assertEqual(p.missing,0)

    def test_missing_only_when_output_needs_data(self):
        p = Playout()
        p.admit(0,PCM)
        for i in range(2,32): p.admit(i,PCM)
        self.assertIsNone(p.next_chunk(2))
        self.assertEqual(p.next_chunk(1),PCM+SILENCE+PCM*2)
        self.assertEqual(p.missing,1)
        self.assertFalse(p.admit(1,PCM))

    def test_short_session_and_bounded_queue(self):
        p=Playout()
        for i in range(128): self.assertTrue(p.admit(i,PCM))
        self.assertFalse(p.admit(128,PCM))
        self.assertEqual(len(p.pending),128)
        self.assertEqual(p.next_chunk(0),PCM*4)
        self.assertTrue(p.admit(128,PCM))
        self.assertLessEqual(len(p.pending),128)

if __name__ == "__main__":
    unittest.main()
