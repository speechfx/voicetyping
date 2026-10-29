#!/bin/bash
# Voice Typing for macOS - installs, or updates to, the latest release:
#   curl -fsSL https://raw.githubusercontent.com/speechfx/voicetyping/main/macos/install.sh | bash
# Needs no admin rights. VT_ZIP_URL and VT_JAR_URL override the downloads (any curl URL, file:// included).
set -euo pipefail

REPO="speechfx/voicetyping"
APP="Voice Typing"
AGENT="io.github.speechfx.voicetyping"
say() { printf '\033[1m%s\033[0m\n' "$*"; }

# 1. A Java 21+ runtime where the app's launcher looks for one; installed once, for this user only.
have_java() {
  for h in /Library/Java/JavaVirtualMachines/*/Contents/Home "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -x "$h/bin/java" ] || continue
    v=$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$h/release" 2>/dev/null || true)
    [ "${v:-0}" -ge 21 ] && return 0
  done
  return 1
}
if ! have_java; then
  [ "$(uname -m)" = arm64 ] && arch=aarch64 || arch=x64
  say "Installing a Java 25 runtime for Voice Typing (one time, about 50 MB)..."
  jvms="$HOME/Library/Java/JavaVirtualMachines"
  mkdir -p "$jvms"
  tmp=$(mktemp -d)
  curl -fL --progress-bar -o "$tmp/jre.tar.gz" "https://api.adoptium.net/v3/binary/latest/25/ga/mac/$arch/jre/hotspot/normal/eclipse"
  tar -xzf "$tmp/jre.tar.gz" -C "$jvms"
  rm -rf "$tmp"
fi

# 2. The app itself.
release=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" || true)
asset() { printf '%s' "$release" | grep -o "\"browser_download_url\": *\"[^\"]*$1\"" | head -1 | sed 's/.*"\(http[^"]*\)"$/\1/'; }
url="${VT_ZIP_URL:-$(asset '-macos\.zip')}"
jar_url="${VT_JAR_URL:-$(asset 'speechfx-voicetyping-app\.jar')}"
[ -n "$url" ] && [ -n "$jar_url" ] || { echo "Could not find the latest release of $APP." >&2; exit 1; }

# The app runs this jar rather than carrying one, so an update of the code alone is just a new jar.
jar_dir="$HOME/speechfx/voicetyping/app"
mkdir -p "$jar_dir"
curl -fL --progress-bar -o "$jar_dir/speechfx-voicetyping-app.jar.part" "$jar_url"
mv -f "$jar_dir/speechfx-voicetyping-app.jar.part" "$jar_dir/speechfx-voicetyping-app.jar"

dir=/Applications
[ -w "$dir" ] || { dir="$HOME/Applications"; mkdir -p "$dir"; }
first_install=yes
[ -d "$dir/$APP.app" ] && first_install=no

say "Downloading $APP..."
tmp=$(mktemp -d)
curl -fL --progress-bar -o "$tmp/vt.zip" "$url"
ditto -x -k "$tmp/vt.zip" "$tmp/x"

osascript -e "quit app \"$APP\"" >/dev/null 2>&1 || true
pkill -f "$APP.app/Contents" >/dev/null 2>&1 || true
sleep 1
rm -rf "$dir/$APP.app"
mv "$tmp/x/$APP.app" "$dir/"
xattr -dr com.apple.quarantine "$dir/$APP.app" 2>/dev/null || true
# Built on Windows the app is unsealed ("code has no resources but signature indicates they must be present"),
# and macOS then cannot keep the Accessibility grant tied to it. Seal it here, ad hoc.
codesign --force -s - "$dir/$APP.app" >/dev/null 2>&1 || true
rm -rf "$tmp"

# 3. Start at login on the first install; an update keeps whatever the user chose in the menu.
if [ "$first_install" = yes ]; then
  mkdir -p "$HOME/Library/LaunchAgents"
  cat > "$HOME/Library/LaunchAgents/$AGENT.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$AGENT</string>
  <key>ProgramArguments</key><array><string>/usr/bin/open</string><string>-a</string><string>$APP</string></array>
  <key>RunAtLoad</key><true/>
</dict></plist>
EOF
fi

open -a "$dir/$APP.app"
say "$APP is installed in $dir and running in the menu bar (top right)."
say "Press Ctrl+Option+Y to open the dictation window."
echo "The first time, macOS asks to allow the Microphone, and to allow $APP under"
echo "System Settings > Privacy & Security > Accessibility (needed for the Ctrl+Option+Y key)."
