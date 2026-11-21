#!/bin/sh
# Unit-tests the core logic (ShapeSync) with the real sources on a plain JDK, without Gradle:
# verify_android.sh first builds the android/launcher3 stubs the tests compile against.
#   JUNIT_CLASSPATH=/path/junit.jar:/path/hamcrest.jar  (default /tmp/junit.jar:/tmp/hamcrest.jar)
set -e
cd "$(dirname "$0")"
JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
CP="${JUNIT_CLASSPATH:-/tmp/junit.jar:/tmp/hamcrest.jar}"
OUT=build/jvmtest

JAVAC="$JAVAC" sh ./verify_android.sh >/dev/null   # android + xposed api stubs

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"

# Log is Xposed-backed: stub it for the JVM run.
cat > "$OUT/Log.java" <<'EOF'
package com.plallappsshape.launcher;
final class Log {
    static void d(String m) {}
    static void i(String m) { System.out.println("[I] " + m); }
    static void e(String m, Throwable t) { System.out.println("[E] " + m + " " + t); }
}
EOF

$JAVAC -d "$OUT/classes" app/src/main/java/com/plallappsshape/launcher/ShapeSync.java "$OUT/Log.java"

$JAVAC -cp "$OUT/classes:build/android-check/classes:$CP" \
    -d "$OUT/test-classes" $(find app/src/test/java -name '*.java')

$JAVA -cp "$OUT/classes:$OUT/test-classes:build/android-check/classes:$CP" \
    org.junit.runner.JUnitCore com.plallappsshape.launcher.ShapeSyncTest
