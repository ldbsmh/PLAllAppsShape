#!/usr/bin/env python3
"""Guards the files the Xposed manager needs in order to list this module at all.

Every item here has been, or could be, silently lost while "cleaning up" the project - and the
symptom is always the same: the module simply does not appear in the manager. So this runs in CI
against the built apk, and locally against the source tree.

Xposed manager, from LSPosed's ModuleUtil:
  getModernModuleApk()  -> needs META-INF/xposed/java_init.list
  isLegacyModule()      -> meta-data xposedminversion
  InstalledModule       -> META-INF/xposed/module.prop (minApiVersion / targetApiVersion / staticScope)
  getScopeList()        -> meta-data xposedscope (a string-array resource)
  module row            -> ApplicationInfo.loadIcon() -> android:icon must resolve

Usage: check_module_metadata.py <apk>            # check the built apk
       check_module_metadata.py --source <dir>   # check the source tree
"""
import re
import sys
import zipfile

REQUIRED_ENTRIES = [
    "META-INF/xposed/java_init.list",
    "META-INF/xposed/module.prop",
]

REQUIRED_META = [
    "xposedmodule",
    "xposedminversion",
    "xposeddescription",
    "xposedscope",
]

REQUIRED_PROPS = ["minApiVersion", "targetApiVersion"]


def check_apk(path):
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    problems = []

    for entry in REQUIRED_ENTRIES:
        if entry not in names:
            problems.append(f"missing {entry} (the manager would not list the module)")

    if "META-INF/xposed/java_init.list" in names:
        body = z.read("META-INF/xposed/java_init.list").decode().strip()
        classes = [l.strip() for l in body.splitlines() if l.strip() and not l.startswith("#")]
        if not classes:
            problems.append("java_init.list lists no entry class")

    if "META-INF/xposed/module.prop" in names:
        props = dict(
            line.split("=", 1)
            for line in z.read("META-INF/xposed/module.prop").decode().splitlines()
            if "=" in line and not line.strip().startswith("#")
        )
        for key in REQUIRED_PROPS:
            if key not in props:
                problems.append(f"module.prop has no {key}")
        # staticScope=true promises META-INF/xposed/scope.list; claiming it without the file is wrong
        if props.get("staticScope", "").strip() == "true" and "META-INF/xposed/scope.list" not in names:
            problems.append("module.prop says staticScope=true but META-INF/xposed/scope.list is absent")

    man = z.read("AndroidManifest.xml").decode("utf-16-le", "ignore")
    for key in REQUIRED_META:
        if key not in man:
            problems.append(f"manifest meta-data {key} is missing")

    # The manager draws the row with loadIcon(): no android:icon -> no usable icon
    if "icon" not in man:
        problems.append("manifest application has no android:icon (module rows render blank)")

    # an icon attribute must point at a resource that is actually packaged
    if not any(n.startswith("res/mipmap") or n.startswith("res/drawable") for n in names):
        problems.append("android:icon is set but no mipmap/drawable resource is packaged")

    return problems


def check_source(root):
    problems = []
    files = {
        "META-INF/xposed/java_init.list": f"{root}/app/src/main/resources/META-INF/xposed/java_init.list",
        "META-INF/xposed/module.prop": f"{root}/app/src/main/resources/META-INF/xposed/module.prop",
        "AndroidManifest.xml": f"{root}/app/src/main/AndroidManifest.xml",
    }
    for name, path in files.items():
        try:
            with open(path) as f:
                f.read()
        except OSError:
            problems.append(f"missing {name} ({path})")
    return problems


def main(argv):
    if len(argv) > 2 and argv[1] == "--source":
        problems = check_source(argv[2])
        target = argv[2]
    else:
        problems = check_apk(argv[1])
        target = argv[1]

    if problems:
        print(f"FAIL {target}")
        for p in problems:
            print("  -", p)
        return 1
    print(f"OK   {target}: module metadata complete (java_init.list, module.prop, manifest meta-data, icon)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
