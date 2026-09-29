#!/usr/bin/env bash
# One-command setup of Sweet Home 3D and this plugin on CWS (Ubuntu 24.04).
# Installs what is missing, builds the plugin only when its source changed, and
# installs it plus the `sh3d` launcher. Running it again changes nothing.
# Why this fork exists: cshuttle/homeassistant ADR 0004 and issue #72.
set -euo pipefail
cd "$(dirname "$0")/.."

# default-jre runs the app (it needs AWT); the headless JDK only builds the plugin.
pkgs=(sweethome3d default-jre openjdk-17-jdk-headless xvfb x11-utils x11vnc matchbox-window-manager)
missing=()
for p in "${pkgs[@]}"; do dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p"); done
if ((${#missing[@]})); then
  sudo DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y -q "${missing[@]}"
fi

# Ubuntu's sweethome3d launcher reads plugins from its first application folder,
# ~/.eteks/sweethome3d (not the ~/.sweethome3d the upstream README gives for Linux).
plugins="$HOME/.eteks/sweethome3d/plugins"
stamp="$plugins/sh3d-mcp.source"
source_rev="$(git rev-parse HEAD:src HEAD:pom.xml | tr "\n" " ")$({ git diff HEAD -- src pom.xml; git ls-files -o --exclude-standard -s src; git ls-files -o --exclude-standard src | xargs -r sha1sum; } | sha1sum | cut -c1-12)"
if [[ ! -f "$plugins/sh3d-mcp.sh3p" || "$(cat "$stamp" 2>/dev/null)" != "$source_rev" ]]; then
  bash scripts/setup-dev.sh
  sh ./mvnw -q -DskipTests package  # the tests need a display; run them under xvfb-run
  mkdir -p "$plugins"
  install -m 644 target/*.sh3p "$plugins/sh3d-mcp.sh3p"
  echo "$source_rev" > "$stamp"
  echo "installed plugin built from $source_rev"
fi

mkdir -p "$HOME/.local/bin"
if ! cmp -s scripts/sh3d "$HOME/.local/bin/sh3d"; then
  install -m 755 scripts/sh3d "$HOME/.local/bin/sh3d"
  echo "installed launcher ~/.local/bin/sh3d"
fi
[[ -f "$HOME/.vnc/passwd" ]] || echo "next: set a VNC password once with  x11vnc -storepasswd ~/.vnc/passwd"
echo "ok: Sweet Home 3D, plugin and launcher are in place"
