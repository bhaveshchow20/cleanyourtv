#!/usr/bin/env bash
# Debug builds only: seed the TMDB Top Shelf cache on the emulator so the dwell backdrop shows without a key or
# without api.themoviedb.org. Downloads the fixture's backdrops (w1280 and the 4K original) and logos (w500) on this computer, copies them and
# fixture.json into the launcher's cache/tmdb/, and restarts Home.
# Usage: themes/tvos27/tmdb-demo/push.sh [serial]      (default emulator-5554)
#        themes/tvos27/tmdb-demo/push.sh [serial] --clear   removes the fixture and the cached backdrops
set -euo pipefail
SERIAL=${1:-emulator-5554}
PKG=com.gathin.tvhome
HERE=$(cd "$(dirname "$0")" && pwd)
A() { adb -s "$SERIAL" "$@"; }

if [ "${2:-}" = "--clear" ]; then
  A shell "run-as $PKG rm -rf cache/tmdb"
  A shell am force-stop $PKG
  A shell input keyevent KEYCODE_HOME
  echo "Cleared the TMDB cache"; exit 0
fi

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
A shell "run-as $PKG mkdir -p cache/tmdb/img"
# Tmdb keys its cache by size ("w1280-<file>", "original-<file>") and fetches the one the panel needs.
for p in $(grep -o '"/[A-Za-z0-9]*\.jpg"' "$HERE/fixture.json" | tr -d '"/' | sort -u); do
  for size in w1280 original; do
    curl -fsS -m 40 -o "$TMP/$size-$p" "https://image.tmdb.org/t/p/$size/$p"
    A push "$TMP/$size-$p" "/data/local/tmp/$size-$p" >/dev/null
    A shell "run-as $PKG cp /data/local/tmp/$size-$p cache/tmdb/img/$size-$p; rm -f /data/local/tmp/$size-$p"
  done
done
# Title logos ("logo": "/abc.png"), kept as "w500-<file>" beside the backdrops.
for p in $(grep -o '"/[A-Za-z0-9]*\.png"' "$HERE/fixture.json" | tr -d '"/' | sort -u); do
  curl -fsS -m 40 -o "$TMP/w500-$p" "https://image.tmdb.org/t/p/w500/$p"
  A push "$TMP/w500-$p" "/data/local/tmp/w500-$p" >/dev/null
  A shell "run-as $PKG cp /data/local/tmp/w500-$p cache/tmdb/img/w500-$p; rm -f /data/local/tmp/w500-$p"
done
A push "$HERE/fixture.json" /data/local/tmp/tmdb-fixture.json >/dev/null
A shell "run-as $PKG cp /data/local/tmp/tmdb-fixture.json cache/tmdb/fixture.json; rm -f /data/local/tmp/tmdb-fixture.json"
A shell am force-stop $PKG
A shell input keyevent KEYCODE_HOME
echo "Fixture pushed: rest focus on a streaming app in the top row for 3 s; adb logcat -s Tmdb shows each app's match"
