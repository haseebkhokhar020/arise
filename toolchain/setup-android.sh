#!/bin/bash
# Arise: Android build toolchain bootstrap (JDK17 + Android SDK + Gradle 8.7)
set -x
export DEBIAN_FRONTEND=noninteractive
export ANDROID_SDK_ROOT=/opt/android-sdk

sudo mkdir -p /opt/android-sdk /usr/lib/jvm
sudo chown -R user:user /opt/android-sdk 2>/dev/null

# 1) JDK 17
if ! /usr/lib/jvm/java-17-openjdk-amd64/bin/java -version >/dev/null 2>&1; then
  sudo apt-get update -qq >/tmp/apt.log 2>&1
  sudo apt-get install -y -qq openjdk-17-jdk-headless unzip zip curl >>/tmp/apt.log 2>&1
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
echo "JDK:" && java -version

# 2) Android command-line tools + SDK packages
if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -sSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf /tmp/clt && mkdir -p /tmp/clt && unzip -q /tmp/clt.zip -d /tmp/clt
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  mv /tmp/clt/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
fi
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/tmp/sdk-licenses.log 2>&1 || true
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/tmp/sdk-install.log 2>&1
echo "SDK packages:"; ls "$ANDROID_SDK_ROOT/platforms" 2>/dev/null

# 3) Gradle 8.7
if [ ! -x /opt/gradle-8.7/bin/gradle ]; then
  curl -sSL -o /tmp/gradle-8.7-bin.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip
  sudo unzip -q /tmp/gradle-8.7-bin.zip -d /opt
fi
/opt/gradle-8.7/bin/gradle --version 2>&1 | head -6
echo "ANDROID_TOOLCHAIN_READY"
