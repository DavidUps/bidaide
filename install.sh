#!/bin/sh
# Installs or updates Bidaide Notificator from the latest GitHub Release:
#   curl -fsSL https://raw.githubusercontent.com/DavidUps/bidaide/main/install.sh | sh
set -eu

REPO="DavidUps/bidaide"
APP="Bidaide Notificator.app"
URL="https://github.com/$REPO/releases/latest/download/BidaideNotificator.zip"

fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\033[1m==>\033[0m %s\n' "$1"; }

[ "$(uname -s)" = Darwin ] || fail "Bidaide Notificator solo funciona en macOS."
[ "$(sw_vers -productVersion | cut -d. -f1)" -ge 14 ] || fail "Necesitas macOS 14 o posterior."

# /Applications when the user can write there (admins), otherwise ~/Applications.
DEST="${BIDAIDE_DEST:-/Applications}"
if [ ! -w "$DEST" ]; then
  DEST="$HOME/Applications"
  mkdir -p "$DEST"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

step "Descargando la última versión"
curl -fL --progress-bar -o "$TMP/app.zip" "$URL" || fail "No se pudo descargar $URL"
ditto -x -k "$TMP/app.zip" "$TMP"

step "Instalando en $DEST"
pkill -x "Bidaide Notificator" 2>/dev/null || true
rm -rf "$DEST/$APP"
ditto "$TMP/$APP" "$DEST/$APP"
# The app is ad-hoc signed and not notarized; without this Gatekeeper refuses to open it.
xattr -dr com.apple.quarantine "$DEST/$APP" 2>/dev/null || true

if ! command -v gh >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    step "Instalando GitHub CLI"
    brew install gh
  else
    printf '\nFalta GitHub CLI. Instálala desde https://cli.github.com y ejecuta «gh auth login».\n'
  fi
fi
if command -v gh >/dev/null 2>&1 && ! gh auth status >/dev/null 2>&1; then
  printf '\nInicia sesión en GitHub CLI para que la app pueda leer tus PR:\n  gh auth login\n'
fi

open "$DEST/$APP"
step "Listo: Bidaide Notificator está en la barra de menú."
