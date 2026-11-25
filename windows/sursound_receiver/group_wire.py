"""Interoperate with existing Android/iOS SSP2 audio and SSC2 clock datagrams."""
from dataclasses import dataclass
import hmac
import struct
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

AUDIO = struct.Struct(">4sBQIq")
CLOCK = struct.Struct(">4sBQQqqq")
PCM_BYTES = 960
MAX_PACKETS = 6000
FRAME_NS = 5_000_000
REQUEST, REPLY = 1, 2

@dataclass(frozen=True)
class GroupAudio:
    session: int
    sequence: int
    origin: int
    pcm: bytes

@dataclass(frozen=True)
class ClockPacket:
    kind: int
    session: int
    nonce: int
    t1: int
    t2: int
    t3: int

class GroupWire:
    def __init__(self, key):
        if len(key) != 16:
            raise ValueError("Fresh 128-bit group key required")
        self.audio = AESGCM(hmac.digest(key, b"SurSound/sync/audio/v2", "sha256")[:16])
        self.control = hmac.digest(key, b"SurSound/sync/control/v2", "sha256")

    def open_audio(self, packet):
        if len(packet) != AUDIO.size + PCM_BYTES + 16:
            return None
        header = packet[:AUDIO.size]
        magic, version, session, sequence, origin = AUDIO.unpack(header)
        if magic != b"SSP2" or version != 2 or sequence >= MAX_PACKETS:
            return None
        try:
            pcm = self.audio.decrypt(header[5:17], packet[AUDIO.size:], header)
        except InvalidTag:
            return None
        return GroupAudio(session, sequence, origin, pcm)

    def seal_clock(self, c):
        if c.kind not in (REQUEST, REPLY) or (c.kind == REQUEST and (c.t2 or c.t3)):
            raise ValueError("Invalid clock packet")
        header = CLOCK.pack(b"SSC2", c.kind, c.session, c.nonce, c.t1, c.t2, c.t3)
        return header + hmac.digest(self.control, header, "sha256")

    def open_clock(self, packet):
        if len(packet) != CLOCK.size + 32:
            return None
        header = packet[:CLOCK.size]
        if not hmac.compare_digest(hmac.digest(self.control, header, "sha256"), packet[CLOCK.size:]):
            return None
        magic, kind, session, nonce, t1, t2, t3 = CLOCK.unpack(header)
        if magic != b"SSC2" or kind not in (REQUEST, REPLY) or (kind == REQUEST and (t2 or t3)):
            return None
        return ClockPacket(kind, session, nonce, t1, t2, t3)
