#!/usr/bin/env python3
"""Guard the packaged diagnostic activities' size-only configuration contract."""
import re
import sys
import unittest

ACTIVITIES = {
    "com.naos.sursound.MainActivity",
    "com.naos.sursound.LanTestActivity",
}
# android.content.pm.ActivityInfo: keyboardHidden=0x20, orientation=0x80,
# screenLayout=0x100, screenSize=0x400, smallestScreenSize=0x800.
REQUIRED_FLAGS = 0xDA0

def verify_manifest_dump(dump):
    activities = {}
    current = None
    indent = None
    for line in dump.splitlines():
        stripped = line.lstrip()
        depth = len(line) - len(stripped)
        if stripped.startswith("E: "):
            if current is not None and depth <= indent:
                current = None
            if stripped.startswith("E: activity "):
                current = {}
                indent = depth
            continue
        if current is None or depth <= indent or not stripped.startswith("A: "):
            continue
        # Activity's own attributes precede nested elements. Ignore attributes
        # deeper than the direct child indentation.
        if depth != indent + 2:
            continue
        match = re.match(r'A: android:name\([^)]*\)="([^"]+)"', stripped)
        if match:
            name = match.group(1)
            if name.startswith("."):
                name = "com.naos.sursound" + name
            activities[name] = current
        elif stripped.startswith("A: android:configChanges("):
            match = re.search(r"\)0x([0-9a-fA-F]+)\s*$", stripped)
            if match:
                current["flags"] = int(match.group(1), 16)
        elif stripped.startswith("A: android:screenOrientation("):
            current["fixed_orientation"] = True
    for name in sorted(ACTIVITIES):
        activity = activities.get(name)
        if activity is None:
            raise ValueError(f"Missing diagnostic activity {name}")
        flags = activity.get("flags", 0)
        if flags != REQUIRED_FLAGS:
            raise ValueError(f"{name}: required size-only configChanges mask 0x{REQUIRED_FLAGS:x}; got 0x{flags:x}")
        if activity.get("fixed_orientation"):
            raise ValueError(f"{name}: orientation must not be locked")

def fixture(flags="0xda0", lock=False):
    parts = []
    for activity in sorted(ACTIVITIES):
        parts.append('        E: activity (line=1)\n'
                     f'          A: android:name(0x01010003)="{activity}"\n')
        if flags is not None:
            parts.append(f'          A: android:configChanges(0x0101001f)=(type 0x11){flags}\n')
        if lock:
            parts.append('          A: android:screenOrientation(0x0101001e)=(type 0x10)0x1\n')
        parts.append('          E: intent-filter (line=3)\n'
                     '            E: action (line=4)\n'
                     '              A: android:name(0x01010003)="android.intent.action.MAIN"\n')
    return "".join(parts)

class RotationGateTest(unittest.TestCase):
    def test_valid_manifest(self):
        verify_manifest_dump(fixture())

    def test_missing_and_partial_flags(self):
        for flags in (None, "0x80", "0x4a0", "0xfff"):
            with self.subTest(flags=flags), self.assertRaises(ValueError):
                verify_manifest_dump(fixture(flags))

    def test_reject_locked_orientation(self):
        with self.assertRaises(ValueError):
            verify_manifest_dump(fixture(lock=True))

    def test_requires_both_activities(self):
        with self.assertRaises(ValueError):
            verify_manifest_dump(fixture().replace("com.naos.sursound.MainActivity", "unrelated.Activity"))

if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        unittest.main(argv=[sys.argv[0]])
    else:
        try:
            verify_manifest_dump(sys.stdin.read())
        except ValueError as exc:
            sys.exit(f"Rotation APK gate failed: {exc}")
        print("Rotation APK gate: both diagnostic Activities handle required size changes; no fixed orientation")
