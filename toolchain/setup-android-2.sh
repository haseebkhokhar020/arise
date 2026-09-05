#!/bin/bash
# Arise: finish JDK17 + Android SDK package install
set -x
export ANDROID_SDK_ROOT=/opt/android-sdk
SDKM="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
  curl -fsSL -o /tmp/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  sudo mkdir -p /usr/lib/jvm
  sudo tar -xzf /tmp/jdk17.tar.gz -C /usr/lib/jvm
  sudo mv /usr/lib/jvm/jdk-17* /usr/lib/jvm/java-17-openjdk-amd64
  sudo chown -R root:root /usr/lib/jvm/java-17-openjdk-amd64
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version

yes | "$SDKM" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/tmp/sdk-licenses.log 2>&1 || true
"$SDKM" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/tmp/sdk-install.log 2>&1
echo "SDK packages:"; ls "$ANDROID_SDK_ROOT/platforms" 2>/dev/null; ls "$ANDROID_SDK_ROOT/build-tools" 2>/dev/null

/opt/gradle-8.7/bin/gradle --version 2>&1 | head -6
echo "ANDROID_TOOLCHAIN_READY"
