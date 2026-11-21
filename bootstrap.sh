#!/bin/sh
# Bootstrap the Gradle wrapper, then run Gradle.
# The wrapper jar is not vendored (it is a binary blob); the Xposed API jar IS vendored in
# app/libs/api-82.jar, because that artifact was removed from Maven Central and every mirror that
# still has it is unofficial.
set -e
cd "$(dirname "$0")"
GRADLE_VERSION=8.13

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  mkdir -p gradle/wrapper
  echo "distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
      > gradle/wrapper/gradle-wrapper.properties
  curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
      "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar"
fi

if [ ! -x gradlew ]; then
  cat > gradlew <<'GRADLEW'
#!/bin/sh
exec java -Xmx2g -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
GRADLEW
  chmod +x gradlew
fi

if [ ! -f app/libs/api-82.jar ]; then
  echo "error: app/libs/api-82.jar is missing (the Xposed API is vendored, see README)" >&2
  exit 1
fi

case "$1" in
  --setup-only) exit 0 ;;
esac
./gradlew "$@"
