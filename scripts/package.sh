#!/bin/zsh
# Builds a Release of Bidaide Notificator and zips it for a GitHub Release / Homebrew cask.
# Usage: scripts/package.sh 0.1.0   ->   build/BidaideNotificator-0.1.0.zip
set -euo pipefail

VERSION="${1:?Usage: scripts/package.sh <version>}"
cd "$(dirname "$0")/.."
if [[ -d /Applications/Xcode.app && -z "${DEVELOPER_DIR:-}" ]]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate --quiet
xcodebuild -project BidaideNotificator.xcodeproj -scheme BidaideNotificator -configuration Release \
  -destination 'generic/platform=macOS' -derivedDataPath build/DerivedData -quiet \
  MARKETING_VERSION="$VERSION" build

ZIP="build/BidaideNotificator-$VERSION.zip"
rm -f "$ZIP"
# ditto keeps the bundle's signatures and symlinks intact, unlike zip.
ditto -c -k --keepParent "build/DerivedData/Build/Products/Release/Bidaide Notificator.app" "$ZIP"
echo "$ZIP"
shasum -a 256 "$ZIP" | cut -d' ' -f1
