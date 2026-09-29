#!/usr/bin/env bash
# One-command setup of Sweet Home 3D and this plugin on CWS (Ubuntu 24.04).
# Installs what is missing, builds the plugin only when its source changed, and
# installs it plus the `sh3d` launcher. Running it again changes nothing.
# Why this fork exists: cshuttle/homeassistant ADR 0004 and issue #72.
set -euo pipefail
cd "$(dirname "$0")/.."

# The app itself is the official 7.5 bundle (below), with its own Java runtime: Ubuntu
# ships 7.2, and plug-ins such as ExportToHTML5 need 7.4. The sweethome3d package stays
# for the furniture libraries it installs under /usr/share/sweethome3d. The headless JDK
# only builds the plugin.
pkgs=(sweethome3d openjdk-21-jre openjdk-17-jdk-headless xvfb x11-utils x11vnc matchbox-window-manager)
missing=()
for p in "${pkgs[@]}"; do dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p"); done
if ((${#missing[@]})); then
  sudo DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get install -y -q "${missing[@]}"
fi

# Pinned downloads: fetched only when missing or changed, and a changed upload fails the
# install instead of running new code. fetch FILE URL SHA256
fetch() {
  if ! echo "$3  $1" | sha256sum --check --quiet --status 2>/dev/null; then
    mkdir -p "$(dirname "$1")"
    curl -fsSL -o "$1.part" "$2"
    echo "$3  $1.part" | sha256sum --check --quiet
    mv "$1.part" "$1"
    echo "downloaded $(basename "$1")"
  fi
}

# Sweet Home 3D 7.5, official Linux bundle (SourceForge's published MD5 was checked
# before this SHA-256 was pinned).
app="$HOME/.local/opt/SweetHome3D-7.5"
cache="$HOME/.cache/sh3d-install"
fetch "$cache/SweetHome3D-7.5-linux-x64.tgz" \
  https://sourceforge.net/projects/sweethome3d/files/SweetHome3D/SweetHome3D-7.5/SweetHome3D-7.5-linux-x64.tgz/download \
  53487eed09650d5cd4310733e3ec80434633ed9df372793acd6fab2c319c2322
if [[ ! -x "$app/SweetHome3D" ]]; then
  mkdir -p "$(dirname "$app")"
  tar xzf "$cache/SweetHome3D-7.5-linux-x64.tgz" -C "$(dirname "$app")"
  echo "installed Sweet Home 3D 7.5 in $app"
fi
# The bundle ships Java 8, and the MCP plugin needs a newer Java: run it on the system's
# Java 21, as Ubuntu runs its own 7.2 (sh3d adds the flag its 3D view needs).
if [[ ! -L "$app/runtime" ]]; then
  mv "$app/runtime" "$app/runtime-java8"
  ln -s /usr/lib/jvm/java-21-openjdk-amd64 "$app/runtime"
  echo "pointed Sweet Home 3D 7.5 at Java 21"
fi

# The app reads plugins and furniture libraries from ~/.eteks/sweethome3d
# (not the ~/.sweethome3d the upstream README gives for Linux).
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

# Third-party plug-ins the owner uses over VNC (GPL; cshuttle/homeassistant#76).
# Pinned by checksum: a changed upload fails the install instead of loading new code.
extras=(
  "AdvancedEditing.sh3p|https://sourceforge.net/p/sweethome3d/plug-ins/_discuss/thread/5e6557c2/fef8/attachment/AdvancedEditing.sh3p|d7eb557e735a264c1c9b84c09e02a5587d3b8d5ffdc7c347df43b28a3ffb2915"
  "ExportToHTML5-1.9.1.sh3p|https://www.sweethome3d.com/storage/plugins/ExportToHTML5-1.9.1.sh3p|006843004b628be9bda4251f7c603dc60a6c1d9e86dc1b66ad74a7018f94d836"
  "AutoDimensioning.sh3p|https://sourceforge.net/p/sweethome3d/plug-ins/_discuss/thread/015d788700/98fc/attachment/AutoDimensioning.sh3p|8d3e4045a535d760c2aeb51554b72636e1d5dff4323237f13f95fbc0cea074e8"
)
for extra in "${extras[@]}"; do
  IFS='|' read -r file url sum <<<"$extra"
  fetch "$plugins/$file" "$url" "$sum"
done

# Contributions furniture library (Free Art License 1.3): bifold, pocket, patio,
# front and garage doors, and a picture window for the ground floor.
fetch "$cache/3DModels-Contributions-1.9.3.zip" \
  https://sourceforge.net/projects/sweethome3d/files/SweetHome3D-models/3DModels-1.9.3/3DModels-Contributions-1.9.3.zip/download \
  ded86e784ef4b732a4bcb7a9a213039b5ece57abdd8a89a22a377a7053ebbc9c
lib="$HOME/.eteks/sweethome3d/furniture/Contributions.sh3f"
if [[ ! -f "$lib" || "$cache/3DModels-Contributions-1.9.3.zip" -nt "$lib" ]]; then
  mkdir -p "$(dirname "$lib")"
  unzip -o -q -j "$cache/3DModels-Contributions-1.9.3.zip" Contributions.sh3f -d "$(dirname "$lib")"
  touch "$lib"  # unzip keeps the entry's old date, which would look out of date forever
  echo "installed the Contributions furniture library"
fi

mkdir -p "$HOME/.local/bin"
if ! cmp -s scripts/sh3d "$HOME/.local/bin/sh3d"; then
  install -m 755 scripts/sh3d "$HOME/.local/bin/sh3d"
  echo "installed launcher ~/.local/bin/sh3d"
fi
[[ -f "$HOME/.vnc/passwd" ]] || echo "next: set a VNC password once with  x11vnc -storepasswd ~/.vnc/passwd"
echo "ok: Sweet Home 3D, plugin and launcher are in place"
