#!/usr/bin/env bash
# sh3d-win-restart.sh — restart Sweet Home 3D on Windows from WSL without leaving a "[Recovered]" home.
#
# For a Windows host running Sweet Home 3D natively, driven from WSL (written for chrispc, whose
# launcher is ~/.local/bin/sh3d-win). Steps:
#   1. find the Sweet Home 3D home windows (title "<file> - Sweet Home 3D"); refuse when any title
#      starts with "* " (unsaved changes; a "[Recovered]" home always has the star);
#   2. close every home window gracefully (WM_CLOSE, the message CloseMainWindow and the window's
#      X button send) and wait for the javaw process to exit; never kill it, since a killed app
#      leaves an auto-save behind that reopens as "[Recovered]" next time;
#   3. remove stale auto-recovery files (*.recovered) from Sweet Home 3D's recovery folder;
#   4. optionally install a new plugin over plugins/sh3d-mcp.sh3p, backing the old one up OUTSIDE
#      the plugins folder (Sweet Home 3D tries to load every file in it, whatever its extension);
#   5. relaunch with sh3d-win and wait for the MCP port 127.0.0.1:9877.
#
# Where Sweet Home 3D 7.5 keeps things on Windows (read from its classes, not guessed):
#   OperatingSystem.getDefaultApplicationFolder() = <user.home>\Application Data\eTeks\Sweet Home 3D
#   ("Application Data" is the legacy junction to %APPDATA%), AutoRecoveryManager writes
#   <that>\recovery\<file name>.recovered (or <uuid>-<name>.recovered), and PluginManager loads
#   <that>\plugins\*. The folder moves only if Java is started with -Dcom.eteks.sweethome3d.
#   applicationFolders / preferencesFolder, which sh3d-win does not pass; override with SH3D_APP_DIR.
#
# Usage: sh3d-win-restart.sh [--install PLUGIN.sh3p] [--file HOME.sh3d] [--timeout SECONDS] [--dry-run]
#   --install  copy this .sh3p over plugins/sh3d-mcp.sh3p while the app is closed
#   --file     home to reopen (default: sh3d-win's default, the live house)
#   --timeout  seconds to wait for the app to exit (default 60)
#   --dry-run  print what would be done; only read-only queries run
# Environment: SH3D_WIN (launcher, default ~/.local/bin/sh3d-win), SH3D_APP_DIR (WSL path of the
# Sweet Home 3D application folder), SH3D_MCP_PORT (default 9877).
# Exit codes: 0 done, 1 error, 2 refused (unsaved changes), 3 the app did not exit in time.
set -euo pipefail

port="${SH3D_MCP_PORT:-9877}"
launcher="${SH3D_WIN:-$HOME/.local/bin/sh3d-win}"
plugin_name="sh3d-mcp.sh3p"
install=""
file=""
timeout_s=60
dry_run=0

usage() { sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "sh3d-win-restart: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --install) [ $# -ge 2 ] || die "--install needs a .sh3p file"; install="$2"; shift 2 ;;
        --file) [ $# -ge 2 ] || die "--file needs a .sh3d file"; file="$2"; shift 2 ;;
        --timeout) [ $# -ge 2 ] || die "--timeout needs seconds"; timeout_s="$2"; shift 2 ;;
        --dry-run) dry_run=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "unknown argument: $1" ;;
    esac
done
case "$timeout_s" in ''|*[!0-9]*) die "--timeout must be a whole number of seconds" ;; esac

if [ -n "$install" ]; then
    [ -f "$install" ] || die "plugin file not found: $install"
    case "$install" in *.sh3p) ;; *) die "--install expects a .sh3p file: $install" ;; esac
    if command -v unzip >/dev/null 2>&1 && ! unzip -l "$install" 2>/dev/null | grep -q 'ApplicationPlugin.properties'; then
        die "$install is not a Sweet Home 3D plugin (no ApplicationPlugin.properties inside)"
    fi
fi
if [ -n "$file" ] && [ ! -f "$file" ]; then die "home file not found: $file"; fi

# act DESCRIPTION COMMAND...: run COMMAND, or only print it with --dry-run.
act() {
    local what="$1"; shift
    if [ "$dry_run" = 1 ]; then echo "[dry-run] would $what"; else echo "$what"; "$@"; fi
}

listening() { timeout 3 bash -c "(exec 3<>/dev/tcp/127.0.0.1/$port)" 2>/dev/null; }

have_windows() { command -v powershell.exe >/dev/null 2>&1; }

# pwsh_run SCRIPT: run a PowerShell script (passed encoded, so bash quoting never touches it).
pwsh_run() {
    local enc
    # No progress records: on redirected output PowerShell prints them to stderr as CLIXML.
    # shellcheck disable=SC2016
    enc=$(printf '%s\n%s' '$ProgressPreference = "SilentlyContinue"' "$1" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)
    (cd /mnt/c && powershell.exe -NoProfile -NonInteractive -EncodedCommand "$enc" </dev/null | tr -d '\r')
}

# shellcheck disable=SC2016  # PowerShell source: its $ are PowerShell's, not bash's
# Top-level visible windows of a java/javaw process whose title ends with " - Sweet Home 3D".
ps_windows='
$src = @"
using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class Sh3dWindows {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  public static List<string> Find() {
    var found = new List<string>();
    EnumWindows(delegate (IntPtr h, IntPtr l) {
      if (!IsWindowVisible(h)) return true;
      var sb = new StringBuilder(1024); GetWindowText(h, sb, sb.Capacity);
      var title = sb.ToString();
      if (title.EndsWith(" - Sweet Home 3D")) { uint pid; GetWindowThreadProcessId(h, out pid); found.Add(pid + "\t" + h.ToInt64() + "\t" + title); }
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
"@
Add-Type -TypeDefinition $src
foreach ($w in [Sh3dWindows]::Find()) {
  $procId = [int]($w.Split("`t")[0])
  $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
  if ($p -and ($p.ProcessName -eq "javaw" -or $p.ProcessName -eq "java")) { $w }
}
'

app_dir() {
    if [ -n "${SH3D_APP_DIR:-}" ]; then echo "$SH3D_APP_DIR"; return; fi
    local appdata
    appdata=$(pwsh_run '[Environment]::GetFolderPath("ApplicationData")')
    [ -n "$appdata" ] || die "cannot read %APPDATA% from Windows"
    echo "$(wslpath -u "$appdata")/eTeks/Sweet Home 3D"
}

# --- 1. Find the windows and refuse on unsaved changes ---
windows=""
if have_windows; then
    windows=$(pwsh_run "$ps_windows")
elif [ "$dry_run" = 1 ]; then
    echo "[dry-run] powershell.exe is not available here: would list Sweet Home 3D windows"
else
    die "powershell.exe is not available: this script runs under WSL on the Windows host"
fi

if [ -n "$windows" ]; then
    echo "Sweet Home 3D windows:"
    printf '%s\n' "$windows" | cut -f3 | sed 's/^/  /'
    unsaved=$(printf '%s\n' "$windows" | cut -f3 | grep '^\* ' || true)
    if [ -n "$unsaved" ]; then
        echo "Refusing to close Sweet Home 3D: unsaved changes in" >&2
        printf '%s\n' "$unsaved" | sed 's/^/  /' >&2
        echo "Save first (MCP save_home, or Ctrl+S in the window; a '[Recovered]' home counts as unsaved:" >&2
        echo "save_home writes it back to the file it came from, or close that window without saving)," >&2
        echo "then run this again. Nothing was closed, removed or installed." >&2
        exit 2
    fi
elif listening; then
    die "port $port is open but no Sweet Home 3D window was found; close the app by hand"
else
    echo "Sweet Home 3D is not running."
fi

# --- 2. Close gracefully and wait for exit ---
if [ -n "$windows" ]; then
    hwnds=$(printf '%s\n' "$windows" | cut -f2 | paste -sd, -)
    pids=$(printf '%s\n' "$windows" | cut -f1 | sort -u | paste -sd, -)
    close_and_wait() {
        local out
        out=$(pwsh_run "
\$sig = '[DllImport(\"user32.dll\")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);'
\$u = Add-Type -MemberDefinition \$sig -Name Sh3dClose -Namespace Win32 -PassThru
foreach (\$h in @($hwnds)) { [void]\$u::PostMessage([IntPtr][long]\$h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) }
try { Wait-Process -Id $pids -Timeout $timeout_s -ErrorAction Stop; 'exited' } catch { if (Get-Process -Id $pids -ErrorAction SilentlyContinue) { 'running' } else { 'exited' } }
")
        if [ "$out" != "exited" ]; then
            echo "Sweet Home 3D (pid $pids) is still running after ${timeout_s}s; a dialog may be waiting in its" >&2
            echo "window. It was not killed (that would leave a recovery file). Nothing was removed or installed." >&2
            exit 3
        fi
        for _ in $(seq 30); do listening || break; sleep 1; done
    }
    act "close Sweet Home 3D gracefully (WM_CLOSE to window(s) $hwnds) and wait up to ${timeout_s}s for pid $pids to exit" close_and_wait
fi

# --- 3. Stale recovery files ---
if have_windows || [ -n "${SH3D_APP_DIR:-}" ]; then
    dir=$(app_dir)
    [ -d "$dir" ] || die "Sweet Home 3D application folder not found: $dir"
else
    dir='%APPDATA%/eTeks/Sweet Home 3D'
fi
recovery="$dir/recovery"
if [ -d "$recovery" ]; then
    shopt -s nullglob
    stale=("$recovery"/*.recovered)
    shopt -u nullglob
    if [ ${#stale[@]} -gt 0 ]; then
        for f in "${stale[@]}"; do
            act "remove stale recovery file $f ($(du -h "$f" | cut -f1))" rm -f -- "$f"
        done
    else
        echo "No recovery files in $recovery."
    fi
else
    echo "No recovery folder at $recovery."
fi

# --- 4. Install the plugin ---
if [ -n "$install" ]; then
    plugins="$dir/plugins"
    backups="$dir/plugins-backup"
    stamp=$(date +%Y%m%d-%H%M%S)
    # shellcheck disable=SC2016  # the bash -c scripts take their values as $1..$3
    if [ -f "$plugins/$plugin_name" ]; then
        act "back up $plugins/$plugin_name to $backups/${plugin_name%.sh3p}-$stamp.sh3p" \
            bash -c 'mkdir -p "$1" && cp -p "$2" "$1/$3"' _ "$backups" "$plugins/$plugin_name" "${plugin_name%.sh3p}-$stamp.sh3p"
    fi
    # shellcheck disable=SC2016
    act "install $install as $plugins/$plugin_name" \
        bash -c 'mkdir -p "$1" && cp "$2" "$1/$3.tmp" && mv -f "$1/$3.tmp" "$1/$3"' _ "$plugins" "$install" "$plugin_name"
fi

# --- 5. Relaunch and wait for the MCP port ---
[ -x "$launcher" ] || [ "$dry_run" = 1 ] || die "launcher not found or not executable: $launcher"
relaunch() {
    if [ -n "$file" ]; then "$launcher" "$file"; else "$launcher"; fi
    for _ in $(seq 30); do listening && return 0; sleep 1; done
    die "Sweet Home 3D started but nothing listens on 127.0.0.1:$port"
}
act "relaunch Sweet Home 3D with $launcher ${file:-(its default home)} and wait for 127.0.0.1:$port" relaunch
[ "$dry_run" = 1 ] || echo "Sweet Home 3D is back; MCP on 127.0.0.1:$port. Reconnect the client (/mcp) if it was attached."
