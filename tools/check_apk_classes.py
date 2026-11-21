#!/usr/bin/env python3
"""Verifies that the built apk does not *define* Xposed framework classes.

compileOnly keeps them out, but a stray `implementation` would bundle de.robv.android.xposed.* into
the module dex. The module's dex is loaded before the framework's, so a bundled copy silently
shadows it - and if that copy's signatures differ from the real API, every call dies at runtime
with NoSuchMethodError (which is what a hand-written stub with a wrong return type caused once).

Only class *definitions* count. Referencing xposed classes is of course expected in every module,
so a string-pool scan gives false positives.

Usage: check_apk_classes.py <apk>
"""
import re
import struct
import sys
import zipfile

PREFIX = "Lde/robv/android/xposed/"


class Dex:
    def __init__(self, data):
        self.data = data
        (self.string_ids_size, self.string_ids_off,
         _type_size, self.type_ids_off,
         _proto_size, _proto_off,
         _field_size, _field_off,
         _method_size, _method_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", data, 0x38)

    def string(self, idx):
        off = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)[0]
        p = off
        while self.data[p] & 0x80:      # uleb128 utf16 length
            p += 1
        p += 1
        end = self.data.index(b"\x00", p)
        return self.data[p:end].decode("utf-8", "replace")

    def type(self, idx):
        return self.string(struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)[0])

    def defined_classes(self):
        for i in range(self.class_defs_size):
            class_idx = struct.unpack_from("<I", self.data, self.class_defs_off + i * 32)[0]
            yield self.type(class_idx)


def main(path):
    z = zipfile.ZipFile(path)
    bundled = {}
    total = 0
    for name in sorted(z.namelist()):
        if not re.match(r"classes\d*\.dex$", name):
            continue
        for desc in Dex(z.read(name)).defined_classes():
            total += 1
            if desc.startswith(PREFIX):
                bundled.setdefault(name, []).append(desc)

    if bundled:
        print("FAIL the apk defines xposed framework classes (they shadow the framework's own):")
        for dex, classes in bundled.items():
            print(f"  {dex}: {len(classes)}, e.g. {sorted(classes)[:3]}")
        print("       the API jar must stay compileOnly")
        return 1

    print(f"OK   no xposed framework classes defined in the apk ({total} classes checked)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
