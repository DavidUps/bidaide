#!/bin/zsh
# Builds Bidaide Notificator in Release and installs it in ~/Applications (or the folder passed as $1).
set -euo pipefail

cd "$(dirname "$0")/.."
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
DEST="${1:-$HOME/Applications}"
APP="build/DerivedData/Build/Products/Release/Bidaide Notificator.app"

command -v xcodegen >/dev/null || brew install xcodegen
xcodegen generate --quiet
xcodebuild -project BidaideNotificator.xcodeproj -scheme BidaideNotificator -configuration Release \
  -derivedDataPath build/DerivedData -quiet build

pkill -x "Bidaide Notificator" 2>/dev/null || true
mkdir -p "$DEST"
rm -rf "$DEST/Bidaide Notificator.app"
cp -R "$APP" "$DEST/"
open "$DEST/Bidaide Notificator.app"
echo "Instalada en $DEST/Bidaide Notificator.app"
