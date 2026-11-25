import socket
import unittest
from unittest.mock import patch
from sursound_receiver.pairing import PairingOffer, qr_matrix, service_info, Advertisement, SERVICE_TYPE

KEY = bytes.fromhex("00112233445566778899aabbccddeeff")  # synthetic vector, never a real pairing key

class PairingTests(unittest.TestCase):
    def test_android_shared_ticket_vector(self):
        offer = PairingOffer.create("192.168.1.20", KEY, 1790000000, 600)
        self.assertEqual(offer.payload(), "SURSOUND1|v1|192.168.1.20|39771|1790000600|00112233445566778899aabbccddeeff")
        self.assertNotIn(KEY.hex(), repr(offer))
        self.assertNotIn(str(KEY), repr(offer))

    def test_reject_invalid_receiver_key_and_expiry(self):
        for bad in ("8.8.8.8", "127.0.0.1", "0.0.0.0", "example.com", "192.168.001.20"):
            with self.assertRaises(ValueError):
                PairingOffer.create(bad, KEY, 1790000000, 600)
        for lifetime in (0, -1, 601):
            with self.assertRaises(ValueError):
                PairingOffer.create("192.168.1.20", KEY, 1790000000, lifetime)
        with self.assertRaises(ValueError):
            PairingOffer.create("192.168.1.20", b"123456", 1790000000, 600)

    def test_qr_has_square_matrix_with_quiet_zone(self):
        matrix = qr_matrix(PairingOffer.create("192.168.1.20", KEY, 1790000000, 600))
        self.assertGreater(len(matrix), 25)
        self.assertTrue(all(len(row) == len(matrix) for row in matrix))
        self.assertTrue(all(not cell for row in matrix[:4] + matrix[-4:] for cell in row))
        self.assertTrue(any(cell for row in matrix for cell in row))

    def test_discovery_record_contains_no_secret(self):
        info = service_info("192.168.1.20")
        self.assertEqual(info.type, SERVICE_TYPE)
        self.assertTrue(info.name.startswith("SurSound-Windows-"))
        self.assertEqual(info.port, 39771)
        self.assertEqual(info.addresses, [socket.inet_aton("192.168.1.20")])
        self.assertEqual(info.properties, {b"version": b"1"})
        self.assertNotIn(KEY.hex(), info.name)

    def test_advertisement_scoped_and_withdrawn_once(self):
        with patch("zeroconf.Zeroconf") as constructor:
            advert = Advertisement("192.168.1.20")
            self.assertEqual(constructor.call_args.kwargs["interfaces"], ["192.168.1.20"])
            constructor.return_value.register_service.assert_called_once()
            advert.withdraw_async()
            advert.close()
            advert.close()
            constructor.return_value.unregister_service.assert_called_once()
            constructor.return_value.close.assert_called_once()

if __name__ == "__main__":
    unittest.main()
