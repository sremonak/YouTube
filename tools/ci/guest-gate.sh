#!/usr/bin/env bash
# The exit status of the actual fixture tests must gate APK publication.
set -uo pipefail
mkdir -p app/build/reports
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.vidora.app.GuestReleaseGateTest \
  --no-daemon 2>&1 | tee app/build/reports/gate-gradle.log
result=${PIPESTATUS[0]}
adb logcat -d -t 400 -s AndroidRuntime:E TestRunner:E System.err:W > app/build/reports/gate-logcat.txt || true
if [ "$result" != 0 ]; then
  python3 - <<'PY'
import pathlib
for name in ('gate-gradle.log','gate-logcat.txt'):
    text=pathlib.Path('app/build/reports',name).read_text(errors='replace')[-16000:]
    print('::error title='+name+'::'+text.replace('%','%25').replace('\n','%0A').replace('\r',''))
PY
fi
exit "$result"
