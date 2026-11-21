#!/bin/sh
# Unit-tests the core logic (ShapeSync) with the real sources on a plain JDK.
# CI uses ./gradlew test, which needs the Android SDK; this keeps it reproducible locally.
#   JUNIT_CLASSPATH=/path/junit.jar:/path/hamcrest.jar
set -e
cd "$(dirname "$0")"
OUT=build/jvmtest
CP="${JUNIT_CLASSPATH:-/tmp/junit.jar:/tmp/hamcrest.jar}"
JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"

# Log is Xposed-backed; stub it. app/test-placeholders supplies the launcher class names.
cat > /tmp/PLAAS_Log.java <<'EOF'
package com.plallappsshape.launcher;
final class Log {
    static boolean verbose;
    static void d(String m) {}
    static void i(String m) { System.out.println("[I] " + m); }
    static void e(String m, Throwable t) { System.out.println("[E] " + m + " " + t); }
}
EOF

$JAVAC -d "$OUT/classes" \
    app/src/main/java/com/plallappsshape/launcher/ShapeSync.java \
    /tmp/PLAAS_Log.java \
    $(find app/test-placeholders -name '*.java')

$JAVAC -cp "$OUT/classes:$CP" -d "$OUT/test-classes" \
    $(find app/src/test/java -name '*.java')

$JAVA -cp "$OUT/classes:$OUT/test-classes:$CP" \
    org.junit.runner.JUnitCore com.plallappsshape.launcher.ShapeSyncTest
