"""Short-lived local QR offer and opt-in mDNS discovery. No credentials in DNS-SD."""
import secrets
import socket
import threading
from dataclasses import dataclass, field

from .protocol import PORT, local_ipv4

MAX_LIFETIME = 600
SERVICE_TYPE = "_sursound._udp.local."

@dataclass(frozen=True)
class PairingOffer:
    receiver: str
    expires: int
    key: bytes = field(repr=False)

    def __post_init__(self):
        if self.receiver != local_ipv4(self.receiver) or len(self.key) != 16:
            raise ValueError("Invalid receiver or session key.")
        if type(self.expires) is not int or not 0 < self.expires <= 2**63-1:
            raise ValueError("Invalid offer expiry.")

    @classmethod
    def create(cls, receiver, key, now, lifetime):
        if type(now) is not int or now < 0 or type(lifetime) is not int or not 1 <= lifetime <= MAX_LIFETIME:
            raise ValueError("Invalid pairing lifetime.")
        return cls(receiver, now + lifetime, key)

    def payload(self):
        # Private out-of-band credential. Never broadcast, print to logs or persist.
        return f"SURSOUND1|v1|{self.receiver}|{PORT}|{self.expires}|{self.key.hex()}"

def qr_matrix(offer):
    import qrcode
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4)
    qr.add_data(offer.payload())
    qr.make(fit=True)
    return qr.get_matrix()

def service_info(receiver):
    from zeroconf import ServiceInfo
    local = local_ipv4(receiver)
    suffix = secrets.token_hex(4)
    return ServiceInfo(SERVICE_TYPE, f"SurSound-Windows-{suffix}.{SERVICE_TYPE}",
                       addresses=[socket.inet_aton(local)], port=PORT,
                       properties={b"version": b"1"},
                       server=f"sursound-{suffix}.local.", host_ttl=15, other_ttl=15)

class Advertisement:
    """Advertise only the selected IPv4 interface, never a key or hostname."""
    def __init__(self, receiver):
        from zeroconf import IPVersion, Zeroconf
        self.info = service_info(receiver)
        self.zc = Zeroconf(interfaces=[receiver], ip_version=IPVersion.V4Only)
        self.worker = None
        try:
            self.zc.register_service(self.info, allow_name_change=True)
        except Exception:
            self.zc.close()
            raise

    def _withdraw(self):
        try:
            self.zc.unregister_service(self.info)
        finally:
            self.zc.close()

    def withdraw_async(self):
        # Goodbye packets and DNS-SD shutdown must not block the PCM receive loop.
        if self.worker is None:
            self.worker = threading.Thread(target=self._withdraw_quietly,
                                           name="SurSound discovery teardown")
            self.worker.start()

    def _withdraw_quietly(self):
        try:
            self._withdraw()
        except Exception:
            print("Discovery teardown reported an error; records expire shortly.", flush=True)

    def close(self):
        self.withdraw_async()
        self.worker.join()

class PairingWindow:
    """Only the local interactive desktop sees the QR; no temporary image or clipboard."""
    def __init__(self, offer):
        import tkinter as tk
        self.tk = tk
        self.root = tk.Tk()
        self.closed = False
        try:
            self.root.title("SurSound — private receiver pairing")
            self.root.resizable(False, False)
            self.root.protocol("WM_DELETE_WINDOW", self.close)
            matrix = qr_matrix(offer)
            scale = max(1, 420 // len(matrix))
            size = scale * len(matrix)
            tk.Label(self.root, text="Scan receiver QR in SurSound", font=("Segoe UI", 14)).pack(padx=16, pady=(16, 4))
            tk.Label(self.root, text=f"Receiver: {offer.receiver}  |  v1 single device").pack(pady=(0, 6))
            canvas = tk.Canvas(self.root, width=size, height=size, bg="white", highlightthickness=0)
            canvas.pack(padx=16, pady=6)
            for row, modules in enumerate(matrix):
                for column, dark in enumerate(modules):
                    if dark:
                        canvas.create_rectangle(column*scale, row*scale, (column+1)*scale,
                                                (row+1)*scale, fill="black", outline="")
            tk.Label(self.root, text="Private, single-session offer. Do not share a screenshot.").pack(padx=16, pady=4)
            tk.Label(self.root, text="Manual fallback — pairing key:").pack()
            manual = tk.Entry(self.root, width=36, justify="center", font=("Consolas", 12))
            manual.insert(0, offer.key.hex())
            manual.configure(state="readonly")
            manual.pack(padx=16, pady=(2, 8))
            tk.Button(self.root, text="Cancel waiting", command=self.close).pack(pady=(0, 12))
            self.root.update()
        except Exception:
            self.close()
            raise

    def poll(self):
        if not self.closed:
            try:
                self.root.update()
            except self.tk.TclError:
                self.close()

    def close(self):
        if self.closed:
            return
        self.closed = True
        try:
            self.root.destroy()
        except self.tk.TclError:
            pass
