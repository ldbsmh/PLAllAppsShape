#!/usr/bin/env python3
"""Generates a *stub* android.jar (plus launcher3 + libxposed classes) so the module sources can be
type-checked on a machine without the Android SDK. Hand-written: only the members we touch.

Usage: verify_android_stubs.py <out-dir>
"""
import os
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "build/stubs"

STUBS = {
    # ---------------------------------------------------------------- android framework
    "android/content/Context": """
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final int RECEIVER_EXPORTED = 2;
    public java.io.File getFilesDir() { return null; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return null; }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, int flags) { return null; }
    public void sendBroadcast(Intent i) {}
    public android.content.res.Resources getResources() { return null; }
    public String getPackageName() { return null; }
    public boolean bindService(Intent service, ServiceConnection conn, int flags) { return false; }
}
""",
    "android/content/SharedPreferences": """
public interface SharedPreferences {
    boolean getBoolean(String key, boolean def);
    String getString(String key, String def);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putString(String key, String value);
        boolean commit();
        void apply();
    }
    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences prefs, String key);
    }
}
""",
    "android/content/BroadcastReceiver": """
public abstract class BroadcastReceiver {
    public abstract void onReceive(Context context, Intent intent);
    public interface OnSharedPreferenceChangeListener {}
}
""",
    "android/content/IntentFilter": """
public class IntentFilter {
    public IntentFilter(String action) {}
}
""",
    "android/content/Intent": """
public class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public Intent() {}
    public Intent(String action) {}
    public Intent setPackage(String p) { return this; }
}
""",
    "android/content/ServiceConnection": "public interface ServiceConnection {}",
    "android/content/ComponentName": """
public class ComponentName {
    public ComponentName(String pkg, String cls) {}
}
""",
    "android/content/pm/ApplicationInfo": """
public class ApplicationInfo {
    public String packageName;
    public String sourceDir;
}
""",
    "android/content/pm/PackageManager": """
public class PackageManager {
    public static final int GET_SERVICE_INFO = 0;
    public ServiceInfo getServiceInfo(ComponentName name, int flags) { return null; }
    public class ServiceInfo {
        public String processName;
    }
}
""",
    "android/app/Application": """
public class Application extends Context {
    public void onCreate() {}
}
""",
    "android/app/Activity": """
public class Activity extends Context {
    public static final int MODE_PRIVATE = 0;
    protected void onCreate(android.os.Bundle b) {}
    public void setContentView(android.view.View v) {}
    public String getString(int resId) { return null; }
    public android.content.Intent getIntent() { return null; }
}
""",
    "android/os/Bundle": "public class Bundle {}",
    "android/os/Looper": """
public class Looper {
    public static Looper getMainLooper() { return null; }
}
""",
    "android/os/Handler": """
public class Handler {
    public Handler(Looper l) {}
    public boolean post(Runnable r) { return true; }
}
""",
    "android/util/Log": """
public final class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
    public static String getStackTraceString(Throwable t) { return ""; }
}
""",
    "android/util/TypedValue": """
public class TypedValue {
    public static final int COMPLEX_UNIT_SP = 2;
}
""",
    "android/graphics/Path": """
public class Path {
    public Path() {}
    public void transform(android.graphics.Matrix m, Path dst) {}
}
""",
    "android/graphics/Matrix": "public class Matrix {}",
    "android/graphics/Canvas": "public class Canvas {}",
    "android/graphics/Paint": "public class Paint {}",
    "android/graphics/Rect": "public class Rect {}",
    "android/graphics/Color": """
public class Color {
    public static final int WHITE = -1;
    public static int parseColor(String s) { return 0; }
}
""",
    "android/graphics/drawable/Drawable": "public class Drawable {}",
    "android/graphics/drawable/AdaptiveIconDrawable": """
public class AdaptiveIconDrawable extends Drawable {
    public AdaptiveIconDrawable(Drawable bg, Drawable fg) {}
    public Path getIconMask() { return null; }
}
""",
    "android/graphics/drawable/ColorDrawable": """
public class ColorDrawable extends Drawable {
    public ColorDrawable(int color) {}
}
""",
    "android/graphics/drawable/GradientDrawable": """
public class GradientDrawable extends Drawable {
    public void setColor(int c) {}
    public void setCornerRadius(float r) {}
}
""",
    "android/content/res/Resources": """
public class Resources {
    public static Resources getSystem() { return null; }
    public int getIdentifier(String name, String defType, String defPackage) { return 0; }
    public String getString(int id) { return null; }
    public android.util.DisplayMetrics getDisplayMetrics() { return null; }
    public Object getConfiguration() { return null; }
}
""",
    "android/util/DisplayMetrics": """
public class DisplayMetrics {
    public float density;
}
""",
    "android/view/View": """
public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public View(Context c) {}
    public View(Context c, android.util.AttributeSet a) {}
    public void setId(int id) {}
    public void setVisibility(int v) {}
    public void setPadding(int l, int t, int r, int b) {}
    public void setBackground(Drawable d) {}
    public void setLayoutParams(ViewGroup.LayoutParams p) {}
    public void setBackgroundColor(int color) {}
    public android.content.res.Resources getResources() { return null; }
    public void setOnClickListener(OnClickListener l) {}
    public interface OnClickListener { void onClick(View v); }
}
""",
    "android/util/AttributeSet": "public interface AttributeSet {}",
    "android/view/ViewGroup": """
public abstract class ViewGroup extends View {
    public ViewGroup(Context c) { super(c); }
    public ViewGroup(Context c, AttributeSet a) { super(c, a); }
    public void addView(View v) {}
    public void addView(View v, LayoutParams p) {}
    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width;
        public int height;
        public LayoutParams(int w, int h) {
            width = w;
            height = h;
        }
        public int bottomMargin;
        public LayoutParams(int w, int h, float weight) {
            width = w;
            height = h;
        }
    }
}
""",
    "android/view/ViewGroup$LayoutParams": "",
    "android/view/Gravity": """
public class Gravity {
    public static final int CENTER_VERTICAL = 16;
}
""",
    "android/widget/TextView": """
public class TextView extends View {
    public TextView(Context c) { super(c); }
    public TextView(Context c, AttributeSet a) { super(c, a); }
    public void setText(CharSequence s) {}
    public void setText(int resId) {}
    public void setTextSize(int unit, float size) {}
    public void setTextColor(int color) {}
}
""",
    "android/widget/Switch": """
public class Switch extends CompoundButton {
    public Switch(Context c) { super(c); }
}
""",
    "android/widget/CompoundButton": """
public class CompoundButton extends TextView {
    public CompoundButton(Context c) { super(c); }
    public void setChecked(boolean b) {}
    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {}
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton buttonView, boolean isChecked);
    }
}
""",
    "android/widget/LinearLayout": """
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;
    public LinearLayout(Context c) { super(c); }
    public LinearLayout(Context c, AttributeSet a) { super(c, a); }
    public void setOrientation(int o) {}
    public void setGravity(int g) {}
}
""",
    "android/widget/ScrollView": """
public class ScrollView extends ViewGroup {
    public ScrollView(Context c) { super(c); }
    public ScrollView(Context c, AttributeSet a) { super(c, a); }
}
""",
    "android/R": """
public final class R {
    public static final class style {
        public static final class DeviceDefault {
            public static final class Light {}
        }
    }
}
""",
}

# ---------------------------------------------------------------- launcher3 (Pixel Launcher 17)
LAUNCHER = {
    "com/android/launcher3/graphics/ThemeManager": """
public class ThemeManager {
    public static final android.content.SharedPreferences.OnSharedPreferenceChangeListener PREF_ICON_SHAPE_KEYS = null;
    // field types only matter to the compiler
    public static final Object PREF_ICON_SHAPE = null;
    public com.android.launcher3.util.MutableListenableRef iconShapeData;
    public IconState parseIconState(IconState old) { return null; }
    public static class IconState {
        public String iconMask;
        public String themeCode;
        public ShapeDelegate iconShape;
        public ShapeDelegate folderShape;
        public boolean isCircle;
    }
}
""",
    "com/android/launcher3/util/MutableListenableRef": """
public class MutableListenableRef {
    public Object getValue() { return null; }
    public void dispatchValue(Object v) {}
}
""",
    "com/android/launcher3/graphics/ShapeDelegate": """
public interface ShapeDelegate {
    void addToPath(float offX, float offY, float size, android.graphics.Path out);
}
""",
    "com/android/launcher3/graphics/ShapeDelegate$GenericPathShape": """
package com.android.launcher3.graphics;
public class ShapeDelegate$GenericPathShape implements ShapeDelegate {
    public final String pathString;
    public ShapeDelegate$GenericPathShape(String path) { pathString = path; }
    public void addToPath(float x, float y, float size, android.graphics.Path out) {}
}
""",
    "com/android/launcher3/graphics/ShapeDelegate$Circle": """
package com.android.launcher3.graphics;
public class ShapeDelegate$Circle implements ShapeDelegate {
    public void addToPath(float x, float y, float size, android.graphics.Path out) {}
}
""",
    "com/android/launcher3/icons/GraphicsUtils": """
package com.android.launcher3.icons;
public final class GraphicsUtils {
    public static IconShape generateIconShape(int size, android.graphics.Path path) { return null; }
}
""",
    "com/android/launcher3/icons/IconShape": """
package com.android.launcher3.icons;
public final class IconShape {
    public int pathSize;
    public android.graphics.Path path;
}
""",
    "com/android/launcher3/icons/BitmapInfo": """
package com.android.launcher3.icons;
public final class BitmapInfo {
    public final IconShape defaultIconShape = null;
    public final android.view.View owner = null;
}
""",
    "com/android/launcher3/LauncherPrefs": """
package com.android.launcher3;
public class LauncherPrefs {
    public static final Object INSTANCE = null;
    public Object get(Object item) { return null; }
}
""",
    "com/android/launcher3/Item": """
package com.android.launcher3;
public interface Item {}
""",
}

XP = {
    "de/robv/android/xposed/IXposedHookLoadPackage": """
package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
""",
    "de/robv/android/xposed/IXposedHookZygoteInit": """
package de.robv.android.xposed;
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;
    class StartupParam {
        public String modulePath;
    }
}
""",
    "de/robv/android/xposed/XposedBridge": """
package de.robv.android.xposed;
import java.lang.reflect.Member;
public final class XposedBridge {
    public static void log(String text) {}
    public static Object hookMethod(Member m, XC_MethodHook hook) { return null; }
    public static void hookAllMethods(Class<?> c, String name, XC_MethodHook hook) {}
}
""",
    "de/robv/android/xposed/XC_MethodHook": """
package de.robv.android.xposed;
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object r) {}
    }
}
""",
    "de/robv/android/xposed/XposedHelpers": """
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
    "de/robv/android/xposed/callbacks/XC_LoadPackage": """
package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
    }
}
""",
}


def write(base, fq_path, src):
    path = os.path.join(base, fq_path + ".java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(src)


def main():
    for group in (STUBS, LAUNCHER, XP):
        for name, src in group.items():
            if not src.strip():
                continue
            if not src.lstrip().startswith("package"):
                pkg = name.rsplit("/", 1)[0].replace("/", ".")
                imports = [l for l in src.splitlines() if l.strip().startswith("import ")]
                body = "\n".join(l for l in src.splitlines() if not l.strip().startswith("import "))
                extra = "import android.content.Context;\nimport android.content.Intent;\n" \
                        "import android.content.SharedPreferences;\nimport android.content.BroadcastReceiver;\n" \
                        "import android.content.IntentFilter;\nimport android.graphics.drawable.Drawable;\n" \
                        "import android.view.View;\nimport android.view.ViewGroup;\n" \
                        "import android.view.ViewGroup.LayoutParams;\n" \
                        "import android.util.AttributeSet;\n" \
                        "import android.widget.CompoundButton;\nimport android.graphics.Path;\nimport android.content.ComponentName;\n"
                src = "package %s;\n%s%s\n%s" % (pkg, extra, "\n".join(imports), body)
            write(OUT, name, src)
    print("stub sources written to", OUT)


if __name__ == "__main__":
    main()
