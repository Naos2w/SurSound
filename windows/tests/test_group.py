import hashlib
import socket
import struct
import unittest
from dataclasses import replace
from sursound_receiver.group_wire import GroupWire, GroupAudio, ClockPacket, AUDIO, REQUEST, REPLY, MAX_PACKETS
from sursound_receiver.group_timing import GroupTimeline, FRAME_NS, WINDOW, ClockEstimator
from sursound_receiver.group_receiver import GroupReceiver
from sursound_receiver.group_rate import FrameMap, RateController, resample

KEY = bytes.fromhex("00112233445566778899aabbccddeeff")  # public synthetic vector
SID = 0x0102030405060708
PCM = bytes(i % 251 for i in range(960))
ORIGIN = 1_340_000_000
NS = 1_000_000_000
PEER = ("192.168.1.2", 31000)

def encode(wire, seq, origin=ORIGIN, session=SID, pcm=PCM):
    header = AUDIO.pack(b"SSP2", 2, session, seq, origin)
    return header+wire.audio.encrypt(header[5:17], pcm, header)

def settle(t, arrival, offset, origin=ORIGIN, first=0):
    for seq in range(first, min(first+32,MAX_PACKETS)):
        t.offer(GroupAudio(SID, seq, origin, PCM), arrival)
    for i in range(3):
        t1 = arrival+i*30_000_000
        request = t.wire.open_clock(t.probe(t1))
        reply = replace(request, kind=REPLY, t2=t1+offset+2_000_000, t3=t1+offset+3_000_000)
        assert not t.reply(replace(reply, nonce=reply.nonce^1),t1+5_000_000)
        assert t.reply(reply, t1+5_000_000)
        assert not t.reply(reply, t1+6_000_000)

class GroupTests(unittest.TestCase):
    def setUp(self):
        self.wire = GroupWire(KEY)

    def test_shared_android_ios_vectors(self):
        packet = encode(self.wire,42,250_000_000)
        self.assertEqual(hashlib.sha256(packet).hexdigest(),
            "c9281166d6b1a89940f2c910905e5cf6bec094a5d1903b9825900938a2eb9f83")
        self.assertEqual(self.wire.open_audio(packet), GroupAudio(SID,42,250_000_000,PCM))
        request = ClockPacket(REQUEST,SID,0x1112131415161718,NS,0,0)
        clock = self.wire.seal_clock(request)
        self.assertEqual(clock.hex(),"535343320101020304050607081112131415161718000000003b9aca0000000000000000000000000000e2895c6ec4161c010a76eb566e0ea48ee9c83cc456d67d9297c93ee7a0daadec")
        self.assertEqual(self.wire.open_clock(clock),request)
        for data,decode in ((packet,self.wire.open_audio),(clock,self.wire.open_clock)):
            for i in (0,5,16,len(data)-1):
                changed=bytearray(data); changed[i]^=1
                self.assertIsNone(decode(bytes(changed)))
            self.assertIsNone(decode(data[:-1]))
        self.assertIsNone(GroupWire(bytes(16)).open_audio(packet))
        self.assertIsNone(GroupWire(bytes(16)).open_clock(clock))

    def test_two_clocks_same_epoch_and_no_early_start(self):
        results=[]
        for arrival, offset in ((NS,100_000_000),(500_000_000,600_000_000)):
            t=GroupTimeline(self.wire)
            settle(t,arrival,offset)
            self.assertTrue(t.ready)
            self.assertEqual(t.rtt,4_000_000)
            due=t.start_deadline
            self.assertIsNone(t.next_chunk(due-100_000_000,0))
            chunk=t.next_chunk(due-80_000_000,0)
            self.assertEqual(chunk.sequence,0)
            self.assertEqual(chunk.pcm,PCM*4)
            self.assertFalse(t.start_due(due-1,1))
            self.assertTrue(t.start_due(due,1))
            results.append(due+offset)
        self.assertEqual(results,[ORIGIN,ORIGIN])

    def test_no_clock_fails_closed_bounded_and_no_fallback(self):
        t=GroupTimeline(self.wire)
        for seq in range(WINDOW):
            self.assertTrue(t.offer(GroupAudio(SID,seq,240_000_000,PCM),0))
        self.assertFalse(t.offer(GroupAudio(SID,WINDOW,240_000_000,PCM),0))
        for i in range(12):
            self.assertIsNotNone(t.probe(i*100_000_000))
            self.assertIsNone(t.next_chunk(i*100_000_000,0))
        t.settle(1_300_000_000)
        self.assertEqual(t.failure,"No authenticated clock")
        self.assertFalse(t.pending)
        self.assertFalse(t.started)

    def test_reorder_missing_stale_and_refinement(self):
        t=GroupTimeline(self.wire)
        settle(t,NS,100_000_000)
        due=t.start_deadline
        t.pending.pop(1)
        self.assertIsNone(t.next_chunk(due-30_000_000,0))
        chunk=t.next_chunk(due-10_000_000,0)
        self.assertEqual(chunk.pcm,PCM+bytes(960)+PCM*2)
        self.assertEqual(t.missing,1)
        self.assertFalse(t.offer(GroupAudio(SID,1,ORIGIN,PCM),due))
        self.assertFalse(t.offer(GroupAudio(SID,32,ORIGIN+1,PCM),due))
        now=NS+1_060_000_000
        t.offer(GroupAudio(SID,32,ORIGIN,PCM),now-10)
        request=self.wire.open_clock(t.probe(now))
        reply=replace(request,kind=REPLY,t2=now+103_000_000,t3=now+104_000_000)
        self.assertTrue(t.reply(reply,now+5_000_000))
        self.assertEqual(t.refinements,1)
        self.assertEqual(t.offset,101_000_000)
        t.settle(now+4*NS)
        self.assertIsNotNone(t.failure)

    def test_stall_and_final_limit(self):
        t=GroupTimeline(self.wire)
        settle(t,NS,100_000_000)
        due=t.start_deadline
        self.assertFalse(t.start_due(due+21_000_000,1))
        self.assertIsNotNone(t.failure)
        t=GroupTimeline(self.wire)
        first=MAX_PACKETS-4
        origin=ORIGIN-first*FRAME_NS
        settle(t,NS,100_000_000,origin,first)
        chunk=t.next_chunk(t.start_deadline-80_000_000,0)
        self.assertEqual(chunk.sequence,first)
        self.assertEqual(chunk.packets,4)
        self.assertEqual(t.expected,MAX_PACKETS)
        self.assertIsNone(t.next_chunk(t.start_deadline+30_000_000,0))

    def test_endpoint_lock_and_udp_clock_exchange(self):
        r=GroupReceiver("127.0.0.1",KEY)
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as recv, socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as send:
            recv.bind(("127.0.0.1",0)); send.bind(("127.0.0.1",0))
            recv.settimeout(1);send.settimeout(1)
            addr=recv.getsockname()
            send.sendto(encode(self.wire,0),addr)
            data,peer=recv.recvfrom(2048)
            self.assertTrue(r.receive(data,peer,NS))
            self.assertFalse(r.receive(encode(self.wire,1),(peer[0],peer[1]+1),NS))
            self.assertFalse(r.receive(b"SSP0\x01"+bytes(988),peer,NS))
            for i in range(3):
                now=NS+i*30_000_000
                recv.sendto(*r.probe(now))
                request=self.wire.open_clock(send.recvfrom(2048)[0])
                reply=replace(request,kind=REPLY,t2=now+102_000_000,t3=now+103_000_000)
                send.sendto(self.wire.seal_clock(reply),addr)
                data,peer=recv.recvfrom(2048)
                self.assertTrue(r.receive(data,peer,now+5_000_000))
            self.assertTrue(r.timeline.ready)
            self.assertEqual(r.stats.clock_accepted,3)

class RateTests(unittest.TestCase):
    def test_frame_map_and_resample(self):
        m=FrameMap()
        m.append(2400,960,959)
        m.append(3360,960,961)
        self.assertAlmostEqual(m.source_at(959),3360)
        self.assertAlmostEqual(m.source_at(1920),4320)
        self.assertIsNone(m.source_at(1921))
        pcm=b"".join(struct.pack("<hh",i,-i) for i in range(960))
        for count in (959,960,961):
            out=resample(pcm,count)
            self.assertEqual(len(out),count*4)
            self.assertEqual(out[:4],pcm[:4])
            self.assertEqual(out[-4:],pcm[-4:])

    def test_bounded_rate_direction_and_stale_position(self):
        for drift in (-8_000_000,8_000_000):
            r=RateController()
            self.assertTrue(r.observe(48000,48000,NS+10_000_000,100,0,0))
            self.assertFalse(r.observe(49000,49000,NS+20_000_000,100,0,0))
            self.assertTrue(r.observe(96000,96000,2*NS+10_000_000+drift,100,0,0))
            self.assertLessEqual(abs(r.ppm),1000)
            self.assertFalse(r.observe(144000,144000,3*NS,10_000_000,0,0))
            counts=[r.frames_for(960,2*NS+20_000_000) for _ in range(20)]
            self.assertTrue(all(959<=x<=961 for x in counts))
            self.assertTrue((sum(counts)-20*960)*drift<0)
            self.assertEqual(r.frames_for(960,5*NS),960)

if __name__ == "__main__":
    unittest.main()
