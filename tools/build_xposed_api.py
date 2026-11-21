#!/usr/bin/env python3
"""Builds the module's compileOnly Xposed API jar from source.

The Xposed API (de.robv.android.xposed:api:82) is no longer on Maven Central, so CI compiles the
few API classes the module uses against tiny android stubs and jars them. At runtime the classes
come from the Xposed/LSPosed framework on the device.

Usage: build_xposed_api.py <out-jar> [javac]
"""
import os
import subprocess
import sys
import tempfile

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build/xposed-api.jar")
JAVAC = sys.argv[2] if len(sys.argv) > 2 else "javac"
JAR = os.path.join(os.path.dirname(JAVAC), "jar") if os.path.dirname(JAVAC) else "jar"

SOURCES = {
    "android/content/Context.java": """
package android.content;
public class Context {}
""",
    "android/content/pm/ApplicationInfo.java": """
package android.content.pm;
public class ApplicationInfo {
    public String packageName;
    public String processName;
    public String sourceDir;
}
""",
    "de/robv/android/xposed/IXposedHookLoadPackage.java": """
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
""",
    "de/robv/android/xposed/IXposedHookZygoteInit.java": """
package de.robv.android.xposed;
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;
    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
""",
    "de/robv/android/xposed/XC_MethodHook.java": """
package de.robv.android.xposed;
import java.lang.reflect.Member;
public abstract class XC_MethodHook {
    public static final int PRIORITY_DEFAULT = 50;
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Member method;
        private Object result;
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; }
        public boolean hasThrowable() { return false; }
        public Throwable getThrowable() { return null; }
    }
    public class Unhook {}
}
""",
    "de/robv/android/xposed/XposedBridge.java": """
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook hook) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> c, String name, XC_MethodHook hook) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> c, XC_MethodHook hook) { return null; }
}
""",
    "de/robv/android/xposed/XposedHelpers.java": """
package de.robv.android.xposed;
public final class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader cl) { return null; }
    public static Object callMethod(Object o, String name, Object... args) { return null; }
    public static Object getObjectField(Object o, String name) { return null; }
    public static void setObjectField(Object o, String name, Object v) {}
    public static Object getStaticObjectField(Class<?> c, String name) { return null; }
    public static java.lang.reflect.Method findMethodExact(Class<?> c, String name, Class<?>... p) { return null; }
    public static java.lang.reflect.Method findAndHookMethod(Class<?> c, String name,
            Object... paramsAndCallback) { return null; }
}
""",
    "de/robv/android/xposed/callbacks/XC_LoadPackage.java": """
package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
        public android.content.pm.ApplicationInfo appInfo;
    }
}
""",
}


def main():
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src")
    classes = os.path.join(tmp, "classes")
    os.makedirs(classes)
    files = []
    for path, body in SOURCES.items():
        full = os.path.join(src, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(body)
        files.append(full)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subprocess.check_call([JAVAC, "-nowarn", "-source", "8", "-target", "8", "-d", classes] + files)
    subprocess.check_call([JAR, "cf", OUT, "-C", classes, "."])
    print("xposed api jar ->", OUT)


if __name__ == "__main__":
    main()
