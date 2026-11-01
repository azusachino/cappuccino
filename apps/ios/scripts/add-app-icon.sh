#!/bin/sh
# Adds the app icon to an xtool IPA. xtool packages only the binary and Info.plist, so the icon
# comes from Xcode's asset compiler: Assets.car and the two loose PNGs go to the app root, where
# iOS reads them. Run from apps/ios with the IPA path (relative to apps/ios) as the argument.
set -eu
ipa="$1"
ipa_abs="$(cd "$(dirname "$ipa")" && pwd)/$(basename "$ipa")"
work="../../.tmp/ipa-icon"
rm -rf "$work"
mkdir -p "$work/out" "$work/app"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}" \
  xcrun actool Assets.xcassets --compile "$work/out" --platform iphoneos \
  --minimum-deployment-target 17.5 --app-icon AppIcon \
  --output-partial-info-plist "$work/out/partial.plist" \
  --target-device iphone --target-device ipad > /dev/null
unzip -q "$ipa_abs" -d "$work/app"
app="$(ls -d "$work"/app/Payload/*.app)"
cp "$work/out/Assets.car" "$work/out/AppIcon60x60@2x.png" "$work/out/AppIcon76x76@2x~ipad.png" "$app/"
rm -f "$ipa_abs"
(cd "$work/app" && zip -qrX "$ipa_abs" Payload)
echo "icon added to $ipa"
