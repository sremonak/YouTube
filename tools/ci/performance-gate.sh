#!/usr/bin/env bash
set -uo pipefail
./gradlew :benchmark:connectedBenchmarkAndroidTest --no-daemon
result=$?
# AGP may only copy timestamped additional outputs. Preserve the canonical
# generated profile and JSON directly before emulator-runner shuts the device down.
mkdir -p benchmark/build/outputs/device-measurements
for package in com.vidora.benchmark com.vidora.benchmark.test com.vidora.app; do
  mkdir -p "benchmark/build/outputs/device-measurements/$package"
  adb pull "/sdcard/Android/media/$package/." "benchmark/build/outputs/device-measurements/$package/" || true
done
exit "$result"
