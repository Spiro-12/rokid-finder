#!/usr/bin/env bash
#
# Record a RokidFinder demo that shows BOTH the live camera view (what's in
# front of you) AND the green HUD overlay on the monochrome display.
#
# IMPORTANT: open RokidFinder ON THE GLASSES from the launcher first, so the
# camera/detection service is actually running. (Launching over adb won't start
# the camera foreground-service.) Then run this script.
#
# Usage:  ./record-demo.sh
# Stop:   press Enter in this terminal when you're done.

set -euo pipefail

PKG="com.example.rokidfinder"
TOGGLE="com.example.rokidfinder.TOGGLE_PREVIEW"
OUT="$HOME/Desktop/rokidfinder-demo-$(date +%Y%m%d-%H%M%S).mp4"

command -v scrcpy >/dev/null || { echo "scrcpy not found (brew install scrcpy)"; exit 1; }
command -v adb    >/dev/null || { echo "adb not found (brew install --cask android-platform-tools)"; exit 1; }

adb get-state >/dev/null 2>&1 || { echo "No device. Plug in the glasses."; exit 1; }

echo "Turning record-mode ON (camera shows on the display)…"
adb shell am broadcast -a "$TOGGLE" >/dev/null 2>&1 || true

echo "Recording to: $OUT"
echo ">>> Do your demo. Press Enter here to stop. <<<"
scrcpy --record "$OUT" --window-title "RokidFinder Recording" >/dev/null 2>&1 &
SCRCPY_PID=$!

# Wait for the user to hit Enter, then clean up.
read -r _ || true

echo "Turning record-mode OFF…"
adb shell am broadcast -a "$TOGGLE" >/dev/null 2>&1 || true

kill "$SCRCPY_PID" 2>/dev/null || true
wait "$SCRCPY_PID" 2>/dev/null || true

echo "Saved: $OUT"
