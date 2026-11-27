#!/usr/bin/env bash
# The exit status of the actual fixture tests must gate APK publication.
set -uo pipefail
mkdir -p app/build/reports
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vidora.app.GuestReleaseGateTest,com.vidora.app.UiVisualContractTest \
  --no-daemon 2>&1 | tee app/build/reports/gate-gradle.log
result=${PIPESTATUS[0]}
mkdir -p app/build/reports/ui-snapshots
adb pull /sdcard/Android/data/com.vidora.app.debug/files/ui-snapshots/. app/build/reports/ui-snapshots/ || true
adb logcat -d -t 400 -s AndroidRuntime:E TestRunner:E System.err:W > app/build/reports/gate-logcat.txt || true
if [ "$result" != 0 ]; then
  python3 - <<'PY'
import pathlib
for name in ('gate-gradle.log','gate-logcat.txt'):
    text=pathlib.Path('app/build/reports',name).read_text(errors='replace')[-16000:]
    print('::error title='+name+'::'+text.replace('%','%25').replace('\n','%0A').replace('\r',''))
PY
fi
if [ "$result" = 0 ]; then
  # The baseline release and its corresponding GPL source are retained for continuity tests.
  gh release download v1.0.108 --pattern Vidora-1.0.108.apk --dir "$RUNNER_TEMP/upgrade-baseline" --clobber || exit 1
  python3 tools/ci/upgrade-gate.py "$RUNNER_TEMP/upgrade-baseline/Vidora-1.0.108.apk" app/build/outputs/apk/release/app-release.apk || exit 1
fi
exit "$result"
