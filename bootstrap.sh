#!/bin/sh
# Bootstrap: Gradle wrapper + compileOnly Xposed API jar, then run Gradle.
# The wrapper jar and the API jar are not vendored (binary/derived); only this script is.
set -e
cd "$(dirname "$0")"
GRADLE_VERSION=8.13

mkdir -p gradle/wrapper build

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
  echo "distributionUrl=https\\://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
      > gradle/wrapper/gradle-wrapper.properties
  curl -fsSL -o gradle/wrapper/gradle-wrapper.jar \
      "https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar"
fi

if [ ! -x gradlew ]; then
  cat > gradlew <<'EOF'
#!/bin/sh
exec java -Xmx2g -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
EOF
  chmod +x gradlew
fi

if [ ! -f build/xposed-api.jar ]; then
  python3 tools/build_xposed_api.py build/xposed-api.jar
fi

case "$1" in
  --setup-only) exit 0 ;;
esac
./gradlew "$@"
