#!/usr/bin/env bash
# The exit status of the actual fixture tests must gate APK publication.
set -uo pipefail
mkdir -p app/build/reports
classes=com.vidora.app.GuestReleaseGateTest,com.vidora.app.UiVisualContractTest
if [ "${1:-}" = android11 ]; then classes=com.vidora.app.GuestReleaseGateTest; fi
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$classes" \
  --no-daemon 2>&1 | tee app/build/reports/gate-gradle.log
result=${PIPESTATUS[0]}
if [ "${1:-}" != android11 ]; then
  mkdir -p app/build/reports/ui-snapshots
  adb pull /sdcard/Download/vidora-ui-snapshots/. app/build/reports/ui-snapshots/ || result=1
fi
adb logcat -d -t 400 -s AndroidRuntime:E TestRunner:E System.err:W > app/build/reports/gate-logcat.txt || true
if [ "$result" != 0 ]; then
  python3 - <<'PY'
import pathlib
for name in ('gate-gradle.log','gate-logcat.txt'):
    text=pathlib.Path('app/build/reports',name).read_text(errors='replace')[-16000:]
    print('::error title='+name+'::'+text.replace('%','%25').replace('\n','%0A').replace('\r',''))
PY
fi
if [ "$result" = 0 ] && [ "${1:-}" != android11 ]; then
  # The baseline release and its corresponding GPL source are retained for continuity tests.
  gh release download v1.0.108 --pattern Vidora-1.0.108.apk --dir "$RUNNER_TEMP/upgrade-baseline" --clobber || exit 1
  python3 tools/ci/upgrade-gate.py "$RUNNER_TEMP/upgrade-baseline/Vidora-1.0.108.apk" app/build/outputs/apk/release/app-release.apk || exit 1
fi
if [ "${1:-}" = android11 ]; then
  cp app/build/reports/gate-gradle.log app/build/reports/android11-gate-gradle.log
  cp app/build/reports/gate-logcat.txt app/build/reports/android11-gate-logcat.txt
fi
exit "$result"
