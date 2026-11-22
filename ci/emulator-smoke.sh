#!/usr/bin/env bash
set -euo pipefail
trap 'RESULT=$?; if [ "$RESULT" -ne 0 ]; then printf "::error title=Emulator smoke stage failed::Stage failed with exit %s. See native test report and device diagnostics.\n" "$RESULT"; adb devices -l || true; fi' EXIT
if ! ./gradlew connectedDebugAndroidTest --no-daemon > ui-test.log 2>&1; then
  cat ui-test.log
  python3 -c 's=open("ui-test.log").read()[-16000:]; print("::error title=Native UI test diagnostics::"+s.replace("%","%25").replace("\r","%0D").replace("\n","%0A"))'
  exit 1
fi
cat ui-test.log
mkdir -p artifacts
adb pull /data/local/tmp/csl-welcome.png artifacts/CSL-Wiki-screen.png
