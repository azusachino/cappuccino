#!/usr/bin/env bash
# Builds an unsigned iPhone .ipa for SideStore or AltStore, which re-sign it.
# The Makefile passes its XCODEBUILD command as the arguments, so this script
# uses the same project and derived-data path as the other Apple targets.
set -euo pipefail

cd "$(dirname "$0")/.."

out_dir=.build/ipa
app_name=Cappuccino.app
# Must match -derivedDataPath in XCODEBUILD (Makefile).
app="$PWD/.build/xcode/Build/Products/Release-iphoneos/$app_name"

rm -rf "$out_dir"
"$@" -scheme Cappuccino -sdk iphoneos -destination 'generic/platform=iOS' -configuration Release build

if [[ ! -d "$app" ]]; then
  echo "error: $app not found after the build" >&2
  exit 1
fi

mkdir -p "$out_dir/Payload"
ditto "$app" "$out_dir/Payload/$app_name"
ditto -c -k --norsrc --noextattr --noacl --keepParent "$out_dir/Payload" "$out_dir/Cappuccino.ipa"
rm -rf "$out_dir/Payload"

ipa="$out_dir/Cappuccino.ipa"
echo "Wrote $ipa ($(du -h "$ipa" | cut -f1 | tr -d ' '))"
