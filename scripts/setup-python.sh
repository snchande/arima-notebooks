#!/usr/bin/env bash
# ============================================================================
#  Arima Notebooks — Python setup (Linux / macOS)
#
#  Detects a Python 3 interpreter for Arima's Python cells and, if missing,
#  offers to install one via the platform package manager (apt / dnf / brew).
#  Safe to run repeatedly.  Use --yes for non-interactive install.
# ============================================================================
set -u
YES=0
[ "${1:-}" = "--yes" ] && YES=1

cyan(){ printf '\033[96m%s\033[0m\n' "$*"; }
green(){ printf '\033[92m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[93m%s\033[0m\n' "$*"; }
red(){ printf '\033[91m%s\033[0m\n' "$*"; }
have(){ command -v "$1" >/dev/null 2>&1; }

find_python() {
  for c in python3 python; do
    if have "$c"; then
      v="$("$c" --version 2>&1)"
      case "$v" in *"Python 3"*) echo "$v"; return 0;; esac
    fi
  done
  return 1
}

cyan "Arima Notebooks — Python setup"
cyan "------------------------------"

if found="$(find_python)"; then
  green "  Found: $found"
  if python3 -m pip --version >/dev/null 2>&1; then
    green "  pip:   $(python3 -m pip --version | sed 's/ from.*//')"
  else
    yellow "  pip not found — bootstrapping with ensurepip..."
    python3 -m ensurepip --upgrade || yellow "  Could not bootstrap pip; install python3-pip via your package manager."
  fi
  green ""
  green "  Python cells are ready. Restart Arima if running: ./arima.sh stop && ./arima.sh start"
  exit 0
fi

yellow "  No Python 3 interpreter found on PATH."
do_install=$YES
if [ "$YES" -ne 1 ]; then
  printf '  Install Python 3 now? [Y/n]: '
  read -r ans
  case "$ans" in [nN]*) do_install=0;; *) do_install=1;; esac
fi

if [ "$do_install" -eq 1 ]; then
  if have apt-get; then
    cyan "  Installing via apt..."; sudo apt-get update && sudo apt-get install -y python3 python3-pip
  elif have dnf; then
    cyan "  Installing via dnf..."; sudo dnf install -y python3 python3-pip
  elif have brew; then
    cyan "  Installing via Homebrew..."; brew install python
  else
    red "  No supported package manager (apt/dnf/brew) found."
    yellow "  Install manually from https://www.python.org/downloads/ then re-run this script."
    exit 1
  fi
  green ""
  green "  Installed. Verify with:  python3 --version"
  green "  Then restart Arima:      ./arima.sh stop && ./arima.sh start"
else
  cyan "  Skipped. To enable Python cells later:"
  cyan "    1. Install Python 3.8+ (python.org or your package manager)"
  cyan "    2. Restart Arima:  ./arima.sh stop && ./arima.sh start"
  cyan "  Packages install from the app: Packages -> PyPI."
fi
