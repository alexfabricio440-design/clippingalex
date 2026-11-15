#!/usr/bin/env bash
# Corre las pruebas en el emulador y trae las imagenes de ejemplo.
cd "$(dirname "$0")"
gradle connectedDebugAndroidTest --no-daemon --stacktrace
code=$?
mkdir -p ../previews
adb pull /sdcard/Download/ccprev/. ../previews/ || true
adb logcat -d -s CCTEST:I AndroidRuntime:E > ../previews/logcat.txt || true
cp -r app/build/reports/androidTests ../previews/report 2>/dev/null || true
exit $code
