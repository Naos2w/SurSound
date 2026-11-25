"""V1 authenticated wire format shared by Android and iOS."""
from dataclasses import dataclass
import ipaddress
import struct
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PCM_BYTES = 960
SILENCE = bytes(PCM_BYTES)
PORT = 39771
MAX_SEQUENCE = 2**31 - 1
LOCAL_NETWORKS = tuple(ipaddress.IPv4Network(n) for n in
                       ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))

def local_ipv4(value):
    ip = ipaddress.IPv4Address(value)
    if not any(ip in network for network in LOCAL_NETWORKS) or ip.is_reserved:
        raise ValueError("Choose an explicit private or link-local IPv4 address.")
    return str(ip)

@dataclass(frozen=True)
class AudioPacket:
    session: int
    sequence: int
    pcm: bytes

class V1Decoder:
    def __init__(self, key):
        if len(key) != 16:
            raise ValueError("A fresh 128-bit pairing key is required.")
        self.cipher = AESGCM(key)

    def decode(self, packet):
        if len(packet) != 993 or packet[:5] != b"SSP0\x01":
            return None
        session, sequence = struct.unpack(">QI", packet[5:17])
        if sequence >= MAX_SEQUENCE:
            return None
        try:
            pcm = self.cipher.decrypt(packet[5:17], packet[17:], packet[:17])
        except InvalidTag:
            return None
        return AudioPacket(session, sequence, pcm)
