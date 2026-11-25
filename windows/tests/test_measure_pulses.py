from array import array
import math
import unittest
from sursound_receiver.measure_pulses import analyze

RATE=48000
def signal(delay_ms, gain=2000, count=20):
    data=array("h",[0])*(RATE*22)
    tone=array("h",(int(gain*math.sin(2*math.pi*440*i/RATE)) for i in range(2400)))
    start=RATE//2+round(delay_ms*RATE/1000)
    for k in range(count):
        a=start+k*RATE
        data[a:a+len(tone)]=tone
    return data

class PulseTests(unittest.TestCase):
    def test_known_two_channel_offset_and_uncertainty(self):
        a,b=signal(0),signal(2,gain=1200)
        result=analyze(a,b,RATE,0,0.5)
        self.assertEqual(result["matched_pulses"],20)
        self.assertAlmostEqual(result["median_signed_ms"],2,delta=1)
        self.assertEqual(result["conditional_result"],"within_target_with_supplied_bound")
        result=analyze(a,b,RATE,0,4)
        self.assertEqual(result["conditional_result"],"inconclusive")

    def test_outside_target_and_calibrated_channel_skew(self):
        a,b=signal(0),signal(12)
        result=analyze(a,b,RATE,0,0.5)
        self.assertEqual(result["conditional_result"],"outside_target_with_supplied_bound")
        result=analyze(a,b,RATE,12,0.5)
        self.assertEqual(result["conditional_result"],"within_target_with_supplied_bound")

    def test_reject_incomplete_or_clipped_recording(self):
        a=signal(0)
        for b in (signal(0,count=19),array("h",[0])*len(a),signal(0,gain=32767)):
            with self.assertRaises(ValueError):
                analyze(a,b,RATE,0,1)

if __name__=="__main__":
    unittest.main()
