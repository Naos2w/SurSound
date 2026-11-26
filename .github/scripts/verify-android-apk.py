#!/usr/bin/env python3
"""Check actual class definitions across packaged DEX files, not string references."""
import re
import struct
import sys
import zipfile

REQUIRED = {
    "Landroidx/core/content/ContextCompat;",
    "Landroidx/core/app/ActivityCompat;",
    "Landroidx/activity/result/contract/ActivityResultContract;",
    "Landroidx/fragment/app/Fragment;",
    "Landroidx/fragment/app/FragmentActivity;",
    "Lcom/journeyapps/barcodescanner/CaptureActivity;",
    "Lcom/journeyapps/barcodescanner/CaptureManager;",
    "Lcom/google/zxing/qrcode/QRCodeReader;",
}

def defined_classes(dex):
    if len(dex) < 112 or not dex.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")
    def u32(offset):
        return struct.unpack_from("<I", dex, offset)[0]
    if u32(32) != len(dex) or u32(40) != 0x12345678:
        raise ValueError("Unsupported or invalid DEX layout")
    string_count, strings = u32(56), u32(60)
    type_count, types = u32(64), u32(68)
    class_count, classes = u32(96), u32(100)
    for count, offset, size in ((string_count, strings, 4), (type_count, types, 4),
                                (class_count, classes, 32)):
        if offset + count * size > len(dex):
            raise ValueError("DEX table outside file")
    result = set()
    for i in range(class_count):
        type_idx = u32(classes + 32 * i)
        if type_idx >= type_count:
            raise ValueError("Invalid class type index")
        string_idx = u32(types + 4 * type_idx)
        if string_idx >= string_count:
            raise ValueError("Invalid descriptor string index")
        offset = u32(strings + 4 * string_idx)
        # Skip the DEX string's unsigned LEB128 length. Required names are ASCII.
        for _ in range(5):
            byte = dex[offset]
            offset += 1
            if not byte & 0x80:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = dex.index(0, offset)
        result.add(dex[offset:end].decode("utf-8", errors="replace"))
    return result

def verify(apk):
    definitions = set()
    with zipfile.ZipFile(apk) as archive:
        dex_files = [n for n in archive.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", n)]
        if not dex_files:
            raise ValueError("APK contains no classes.dex")
        for name in dex_files:
            definitions.update(defined_classes(archive.read(name)))
    missing = REQUIRED - definitions
    if missing:
        raise ValueError("Missing QR scanner class definitions: " + ", ".join(sorted(missing)))
    print(f"QR scanner runtime APK gate: {len(REQUIRED)}/{len(REQUIRED)} class definitions present across {len(dex_files)} DEX file(s)")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: verify-android-apk.py <apk>")
    try:
        verify(sys.argv[1])
    except (OSError, zipfile.BadZipFile, ValueError, IndexError, struct.error) as e:
        sys.exit(f"APK verification failed: {e}")
