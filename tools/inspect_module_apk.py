#!/usr/bin/env python3
"""Diagnoses why an Xposed/LSPosed module apk is not recognised.

Checks the things LSPosed actually looks at, straight from the apk bytes:
  * META-INF/xposed/java_init.list  (new API)  /  assets/xposed_init (legacy API)
  * META-INF/xposed/module.prop
  * AndroidManifest meta-data: xposedmodule / xposedminversion / xposeddescription / xposedscope
  * every class listed in java_init.list exists in classes*.dex and implements the entry interface
  * apk signature block (v2/v3) - a module without it is refused by LSPosed
  * raw ZIP structure: whether AndroidManifest.xml is STORED/compressed, entry order

Usage: inspect_module_apk.py <module.apk>
"""
import re
import struct
import sys
import zipfile

OK = "  OK   "
BAD = "  FAIL "


class DexClasses:
    """Minimal DEX reader: class names + implemented interfaces per class."""

    def __init__(self, data):
        self.data = data
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", data, 0x38)
        self.classes = {}      # descriptor -> list of interface descriptors

    def string(self, idx):
        off = struct.unpack_from("<I", self.data, self.string_ids_off + idx * 4)[0]
        # uleb128 utf16 length, then MUTF-8 bytes
        p = off
        while self.data[p] & 0x80:
            p += 1
        p += 1
        end = self.data.index(b"\x00", p)
        return self.data[p:end].decode("utf-8", "replace")

    def type(self, idx):
        sidx = struct.unpack_from("<I", self.data, self.type_ids_off + idx * 4)[0]
        return self.string(sidx)

    def parse(self):
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            class_idx, _acc, _sup, ifaces_off = struct.unpack_from("<4I", self.data, base)
            name = self.type(class_idx)
            interfaces = []
            if ifaces_off:
                o = ifaces_off
                size = struct.unpack_from("<I", self.data, o)[0]
                o += 4
                for k in range(size):
                    interfaces.append(self.type(struct.unpack_from("<H", self.data, o + k * 2)[0]))
            self.classes[name] = interfaces
        return self

    def known(self, descriptor):
        return descriptor in self.classes


def check_apk(path):
    print("apk:", path)
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    fails = 0

    def report(ok, msg):
        nonlocal fails
        print((OK if ok else BAD) + msg)
        if not ok:
            fails += 1

    # ---- entry list -------------------------------------------------------
    new_list = "META-INF/xposed/java_init.list"
    old_list = "assets/xposed_init"
    if new_list in names:
        entries = [l.strip() for l in z.read(new_list).decode().splitlines() if l.strip() and not l.startswith("#")]
        report(True, f"{new_list}: {entries}")
        api = "new"
    elif old_list in names:
        entries = [l.strip() for l in z.read(old_list).decode().splitlines() if l.strip() and not l.startswith("#")]
        report(True, f"{old_list}: {entries}")
        api = "legacy"
    else:
        report(False, "no java_init.list / xposed_init entry list")
        entries, api = [], None

    # ---- module.prop ------------------------------------------------------
    prop = "META-INF/xposed/module.prop"
    if prop in names:
        body = z.read(prop).decode().strip()
        report(True, f"module.prop: {body.replace(chr(10), ' | ')}")
    else:
        print("  note  no module.prop (only needed for the description tab list)")

    # ---- dex classes ------------------------------------------------------
    dexes = [DexClasses(z.read(n)).parse() for n in sorted(names) if re.match(r"classes\d*\.dex$", n)]
    all_classes = {}
    for d in dexes:
        all_classes.update(d.classes)
    for entry in entries:
        desc = "L" + entry.replace(".", "/") + ";"
        report(all_classes.get(desc) is not None, f"{entry} exists in dex")
        if all_classes.get(desc) is not None:
            ifaces = all_classes[desc]
            wanted = ["IXposedHookLoadPackage", "IXposedHookInitPackageResources", "IXposedHookZygoteInit"]
            hit = [w for w in wanted if any(w in i for i in ifaces)]
            report(bool(hit), f"{entry} implements: {hit or ifaces}")

    # ---- manifest ---------------------------------------------------------
    man = z.read("AndroidManifest.xml")
    txt = man.decode("utf-16-le", "ignore")
    for key in ("xposedmodule", "xposedminversion", "xposeddescription", "xposedscope"):
        report(key in txt, f"manifest meta-data {key}")
    pkg = re.search(r"com\.[a-z0-9_.]+", txt)
    print("  info  manifest strings ok, package-ish:", pkg.group(0) if pkg else "?")

    # ---- apk signature (v2/v3) -------------------------------------------
    sig = signature_schemes(path)
    report(sig is not None, "apk is signed (v2/v3 block) - unsigned modules are refused"
                            if sig is None else f"apk signed with {sig}")

    # ---- zip layout -------------------------------------------------------
    stored = [i.filename for i in z.infolist() if i.compress_type == 0]
    print("  info  stored (method 0):", stored)
    print("  info  entry order:", [i.filename for i in z.infolist()][:6], "...")

    print("\nresult:", "all checks passed" if fails == 0 else f"{fails} problem(s)")
    return fails


def signature_schemes(path):
    """
    Detects APK signature schemes.

    Preferred: pip install androguard -> APK().is_signed_v2 / v3. Without it we only look for the
    'APK Sig Block 42' footer, which proves a signing block exists but not which scheme.
    """
    try:
        from androguard.core.apk import APK  # noqa: F401
        a = APK(path)
        schemes = [n for n, ok in (("v1", a.is_signed_v1()), ("v2", a.is_signed_v2()),
                                   ("v3", a.is_signed_v3())) if ok]
        return "/".join(schemes) or None
    except Exception:
        pass
    with open(path, "rb") as f:
        data = f.read()
    if b"APK Sig Block 42" in data:
        return "signing block present (install androguard for exact schemes)"
    return None


if __name__ == "__main__":
    sys.exit(1 if check_apk(sys.argv[1]) else 0)
