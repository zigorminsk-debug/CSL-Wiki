#!/usr/bin/env bash
set -euo pipefail
if ! ./gradlew connectedDebugAndroidTest --no-daemon > ui-test.log 2>&1; then
  cat ui-test.log
  python3 -c 's=open("ui-test.log").read()[-16000:]; print("::error title=Native UI test diagnostics::"+s.replace("%","%25").replace("\r","%0D").replace("\n","%0A"))'
  exit 1
fi
cat ui-test.log
mkdir -p artifacts
adb pull /sdcard/Android/data/by.csl.wiki/files/welcome.png artifacts/CSL-Wiki-screen.png
