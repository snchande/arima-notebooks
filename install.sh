#!/usr/bin/env bash
# ============================================================================
#  Arima Notebooks - one-file installer for macOS and Linux
#
#  Downloads, builds and registers Arima Notebooks on a machine that has never
#  seen it. Nothing is touched until it has told you what it is about to do and
#  you have said yes.
#
#  It shares its look, its animation vocabulary and its wording with the three
#  launchers it installs (arima.cmd, arima.ps1, arima.sh). The frames below are
#  copied from arima.sh on purpose: this file has to stand on its own before
#  the repository exists.
#
#    curl -fsSL https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.sh | bash
#
#  With flags (the shell needs -s -- to forward them through the pipe):
#    curl -fsSL .../install.sh | bash -s -- --yes
#    curl -fsSL .../install.sh | bash -s -- --check-only
#
#  Everything printed is 7-bit ASCII so it renders the same everywhere.
# ============================================================================

if [ -z "${BASH_VERSION:-}" ]; then
    echo "The Arima installer needs bash. Try:  curl -fsSL <url> | bash" >&2
    exit 1
fi

set -u

PRODUCT='Arima Notebooks'
REPO_URL='https://github.com/snchande/arima-notebooks.git'
RAW_BASE='https://raw.githubusercontent.com/snchande/arima-notebooks/master'
ISSUES_URL='https://github.com/snchande/arima-notebooks/issues/new'
BRANCH='master'
# Two audiences. A user just runs Arima and gets a shallow clone; a developer
# works ON it and needs full history to branch and open pull requests.
F_DEV=0
CLONE_DEPTH='--depth 1'
PORT=8585
URL="http://localhost:${PORT}"
MIN_JAVA=17      # the JAR's real floor (see arima.sh)

# Recommended floors for the optional runtimes. These are SOFT: a tool below its
# floor is kept and used, never replaced - we only report what will not work.
# Presence alone used to be the whole check, so an old Node reported as present and
# TypeScript cells then failed at runtime with nothing having warned at install time.
MIN_NODE=22      # 22.6+ for built-in type-stripping
MIN_NODE_TS=6
MIN_DOTNET=8     # C# / F# cells target the .NET 8 SDK
MIN_PY_MAJOR=3
MIN_PY_MINOR=8
WANT_JAVA=21     # what we install when Java is missing
RULE="------------------------------------------------------------"

STATE_FILE="${HOME}/.arima-install-state"
DIR="${ARIMA_HOME:-$HOME/.arima}"

F_YES=0; F_CHECK=0; F_SKIPOPT=0; F_NOPATH=0; F_RESET=0
ANIM=1

usage() {
    cat <<EOF
Arima Notebooks installer

  --yes            skip every confirmation prompt (for CI)
  --check-only     explain, probe the toolchain, print the table -- change nothing
  --skip-optional  only require/offer Java, Maven and Git
  --no-path        do not append an export line to your shell rc
  --reset          forget recorded progress and run every step again
  --no-anim        disable the animated banner, brew frames and spinners
  --dir <path>     where Arima lands (default: \$HOME/.arima)
  --repo <url>     repository to clone (default: $REPO_URL)
  --branch <name>  branch to clone (default: master)
      --dev        full history plus the optional language runtimes
  -h, --help       this text
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --yes|-y)         F_YES=1 ;;
        --check-only)     F_CHECK=1 ;;
        --skip-optional)  F_SKIPOPT=1 ;;
        --dev)            F_DEV=1 ;;
        --no-path)        F_NOPATH=1 ;;
        --reset)          F_RESET=1 ;;
        --no-anim)        ANIM=0 ;;
        --dir)            shift; DIR="${1:-$DIR}" ;;
        --repo)           shift; REPO_URL="${1:-$REPO_URL}" ;;
        --branch)         shift; BRANCH="${1:-$BRANCH}" ;;
        -h|--help)        usage; exit 0 ;;
        *)                echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
    shift
done

# -- Shared look & feel (mirrors arima.sh) -----------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR-}" ] && [ -z "${ARIMA_NO_COLOR-}" ]; then
    C_RESET=$'\033[0m'; C_DIM=$'\033[90m';    C_RED=$'\033[91m'
    C_GREEN=$'\033[92m'; C_YELLOW=$'\033[93m'; C_CYAN=$'\033[96m'
    C_WHITE=$'\033[97m'
else
    C_RESET=''; C_DIM=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_CYAN=''; C_WHITE=''
fi
[ -t 1 ] || ANIM=0
[ -n "${ARIMA_NO_ANIM-}" ] && ANIM=0

say()   { printf '%b\n' "$*"; }
info()  { say "${C_CYAN}$*${C_RESET}"; }
ok()    { say "${C_GREEN}$*${C_RESET}"; }
warn()  { say "${C_YELLOW}$*${C_RESET}"; }
err()   { say "${C_RED}$*${C_RESET}"; }
dim()   { say "${C_DIM}$*${C_RESET}"; }
plain() { printf '%s\n' "$*"; }

frame_pause() { [ "$ANIM" = "1" ] && sleep "${1:-0.055}"; return 0; }

section() { echo; info "  $1"; dim "  $RULE"; }

step() { echo; printf '%s  [%s/%s] %s%s %s%s\n' "$C_CYAN" "$1" "$2" "$C_RESET" "$C_WHITE" "$3" "$C_RESET"; }

# row <ok|warn|err|dots> <label> <detail>
row() {
    local mark colour
    case "$1" in
        ok)   mark='[ok]'; colour="$C_GREEN"  ;;
        warn) mark='[--]'; colour="$C_YELLOW" ;;
        err)  mark='[!!]'; colour="$C_RED"    ;;
        *)    mark='[..]'; colour="$C_DIM"    ;;
    esac
    printf '%s    %s %s%s%-13s%s%s%s%s\n' \
        "$colour" "$mark" "$C_RESET" "$C_WHITE" "$2" "$C_RESET" "$C_DIM" "$3" "$C_RESET"
}

# bar <percent> <label>  ->  [########............]  40%  label
bar() {
    local pct="$1" label="${2:-}" filled i str=''
    filled=$(( (pct * 20) / 100 ))
    for (( i = 0; i < 20; i++ )); do
        if [ "$i" -lt "$filled" ]; then str="${str}#"; else str="${str}."; fi
    done
    printf '%s  [%s] %3s%%  %s%s\n' "$C_DIM" "$str" "$pct" "$label" "$C_RESET"
    frame_pause 0.04
}

# -- The Barista brew animation (identical frames to arima.sh / arima.ps1) ---
brew_frame() {
    local n="$1" r0='' r1='' r3 r4 cap
    case "$n" in
        1) r0="${C_YELLOW}        (@)${C_RESET}"; r3='|      |]'; r4='|      |'; cap='Barista picks a bean...' ;;
        2) r1="${C_YELLOW}        (@)${C_RESET}"; r3='|      |]'; r4='|      |'; cap='Barista picks a bean...' ;;
        3) r3='|  *   |]'; r4='|      |'; cap='Barista grinds it...' ;;
        4) r3='|      |]'; r4='|::::::|'; cap='Barista is brewing...' ;;
        5) r3='|::::::|]'; r4='|######|'; cap='Barista is brewing...' ;;
        6) r0="${C_DIM}          (${C_RESET}"; r1="${C_DIM}           )${C_RESET}"
           r3='|######|]'; r4='|######|'; cap='Barista is brewing...' ;;
        7) r0="${C_DIM}         ( )${C_RESET}"; r1="${C_DIM}          ) (${C_RESET}"
           r3='|######|]'; r4='|######|'; cap='Barista serves your notebook.' ;;
        8) r0="${C_DIM}          ) (${C_RESET}"; r1="${C_DIM}         ( )${C_RESET}"
           r3='|######|]'; r4='|######|'; cap='Barista serves your notebook.' ;;
        *) r3='|      |]'; r4='|      |'; cap='Barista is brewing...' ;;
    esac
    local clr=''
    [ "$ANIM" = "1" ] && clr=$'\033[2K'
    printf '%s%s\n'       "$clr" "$r0"
    printf '%s%s\n'       "$clr" "$r1"
    printf '%s%s        .------.%s\n'  "$clr" "$C_WHITE"  "$C_RESET"
    printf '%s%s        %s%s\n'        "$clr" "$C_YELLOW" "$r3" "$C_RESET"
    printf '%s%s        %s%s\n'        "$clr" "$C_YELLOW" "$r4" "$C_RESET"
    printf "%s%s        '------'%s\n"  "$clr" "$C_WHITE"  "$C_RESET"
    printf '%s%s       ~~~~~~~~~~%s\n' "$clr" "$C_DIM"    "$C_RESET"
    printf '%s%s   %s%s\n'             "$clr" "$C_CYAN"   "$cap" "$C_RESET"
}

show_brew() {
    echo
    if [ "$ANIM" != "1" ]; then brew_frame 7; return 0; fi
    local i=0 n
    for n in 1 2 3 4 5 6 7 8 7 8; do
        [ "$i" -gt 0 ] && printf '\033[8A'
        brew_frame "$n"
        if [ "$i" -lt 6 ]; then sleep 0.13; else sleep 0.19; fi
        i=$((i + 1))
    done
}

BANNER_ART=(
'     .-"""""-.    '
"   .'    \    '.  "
'  /      )      \ '
'  \      (      / '
"   '.    /    .'  "
"     '-.....-'    "
)

banner() {
    local head="${1:-A R I M A   N O T E B O O K S}"
    local text=("$head" \
        '------------------------------------------' \
        'One file. Checks, installs, builds, done.' \
        'Nothing is changed before you say yes.' \
        "Server: $URL" '')
    local tint=("$C_WHITE" "$C_DIM" "$C_CYAN" "$C_DIM" "$C_DIM" '')
    echo
    local i
    for i in 0 1 2 3 4 5; do
        printf '%s%s%s' "$C_YELLOW" "${BANNER_ART[$i]}" "$C_RESET"
        [ -n "${text[$i]}" ] && printf '%s  %s%s' "${tint[$i]}" "${text[$i]}" "$C_RESET"
        printf '\n'
        [ "$i" -lt 5 ] && frame_pause
    done
    dim "  $RULE"
    echo
}

# Spinner-backed wait on a background job; same frame set as arima.sh.
spin_wait() {
    local pid="$1" label="$2" frames='-\|/' i=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$ANIM" = "1" ]; then
            printf '\r\033[2K%s  [%s]  %s ...%s' \
                "$C_DIM" "${frames:$((i % 4)):1}" "$label" "$C_RESET"
        fi
        sleep 0.25
        i=$((i + 1))
    done
    [ "$ANIM" = "1" ] && printf '\r\033[2K'
    wait "$pid"
}

# -- Confirmation ------------------------------------------------------------
# Piped from curl, stdin is the script itself -- ask the terminal directly.
confirm() {
    if [ "$F_YES" = "1" ]; then dim "  (--yes) $1 -> yes"; return 0; fi
    echo
    printf '%s  %s %s[type '"'"'yes'"'"' to confirm]: %s' "$C_YELLOW" "$1" "$C_DIM" "$C_RESET"
    local answer=''
    if [ -t 0 ]; then
        read -r answer
    elif [ -r /dev/tty ]; then
        read -r answer < /dev/tty
    else
        echo
        err '  No terminal is available to ask for confirmation.'
        dim '  Re-run with --yes if you have already read the plan above.'
        return 1
    fi
    [ "$(printf '%s' "$answer" | tr 'A-Z' 'a-z')" = "yes" ]
}

# -- Probes ------------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

java_major() {
    have java || { echo 0; return; }
    local v
    v=$(java -version 2>&1 | head -n1 | sed -n 's/.*"\([0-9][0-9.]*\).*/\1/p')
    case "$v" in
        1.*) echo "$v" | cut -d. -f2 ;;
        '')  echo 0 ;;
        *)   echo "$v" | cut -d. -f1 ;;
    esac
}

python_bin() { for b in python3 python py; do have "$b" && { echo "$b"; return; }; done; }
cpp_bin()    { for b in g++ clang++ cl; do have "$b" && { echo "$b"; return; }; done; }

os_label() {
    if [ -r /etc/os-release ]; then
        # shellcheck disable=SC1091
        printf '%s (%s)' "$(. /etc/os-release && printf '%s' "${PRETTY_NAME:-$NAME}")" "$(uname -rm)"
    elif [ "$(uname -s)" = "Darwin" ] && have sw_vers; then
        printf 'macOS %s (%s)' "$(sw_vers -productVersion)" "$(uname -m)"
    else
        uname -srm
    fi
}

# -- Dependency catalogue ----------------------------------------------------
# key|Label|required|brew|apt|dnf|pacman|url|note
# Required tools block the install. Optional ones only disable the cell modes
# that depend on them -- Arima starts and runs perfectly well without any.
dep_rows() {
    cat <<EOF
java|Java|1|temurin|openjdk-${WANT_JAVA}-jdk|java-${WANT_JAVA}-openjdk-devel|jdk-openjdk|https://adoptium.net/|JDK ${WANT_JAVA} - runs the server and JShell cells
mvn|Maven|0|maven|maven|maven|maven|https://maven.apache.org/|optional - the bundled mvnw wrapper is used when absent
git|Git|1|git|git|git|git|https://git-scm.com/|clones the repository and powers: arima update
node|Node.js|0|node|nodejs|nodejs|nodejs|https://nodejs.org/|JavaScript + TypeScript cells, npm packages
dotnet|.NET|0|dotnet-sdk|dotnet-sdk-8.0|dotnet-sdk|dotnet-sdk|https://dot.net/|C# + F# cells, NuGet packages
python|Python|0|python@3.13|python3|python3|python|https://www.python.org/downloads/|Python cells, PyPI packages
cpp|C++|0|gcc|g++|gcc-c++|gcc|https://gcc.gnu.org/|C++ cells
EOF
}

# JShell is a JDK tool, so a bare JRE does not count however new it is.
# Version probes. Anything unparseable returns 0 and callers treat that as
# acceptable, so a runtime whose version we cannot read is never called too old.
ver_field() { echo "$1" | tr -d 'v' | cut -d. -f"$2" | sed 's/[^0-9].*//'; }

node_ts_ready() {
    have node || return 1
    local v maj min; v=$(node --version 2>/dev/null)
    maj=$(ver_field "$v" 1); min=$(ver_field "$v" 2)
    [ -z "$maj" ] && return 0
    [ "$maj" -gt "$MIN_NODE" ] 2>/dev/null && return 0
    [ "$maj" -eq "$MIN_NODE" ] 2>/dev/null && [ "${min:-0}" -ge "$MIN_NODE_TS" ] 2>/dev/null
}

dotnet_ok() {
    have dotnet || return 1
    local maj; maj=$(ver_field "$(dotnet --version 2>/dev/null)" 1)
    [ -z "$maj" ] && return 0
    [ "$maj" -ge "$MIN_DOTNET" ] 2>/dev/null
}

py_ok() {
    local v maj min
    v=$("$1" --version 2>&1 | tr -d '' | awk '{print $2}')
    [ -z "$v" ] && return 0
    maj=$(ver_field "$v" 1); min=$(ver_field "$v" 2)
    [ -z "$maj" ] && return 0
    [ "$maj" -gt "$MIN_PY_MAJOR" ] 2>/dev/null && return 0
    [ "$maj" -eq "$MIN_PY_MAJOR" ] 2>/dev/null && [ "${min:-0}" -ge "$MIN_PY_MINOR" ] 2>/dev/null
}

dep_present() {
    case "$1" in
        java)   [ "$(java_major)" -ge "$MIN_JAVA" ] 2>/dev/null && have javac ;;
        python) [ -n "$(python_bin)" ] ;;
        cpp)    [ -n "$(cpp_bin)" ] ;;
        *)      have "$1" ;;
    esac
}

dep_detail() {
    local jm
    case "$1" in
        java)
            have java || { echo 'NOT FOUND'; return; }
            jm=$(java_major)
            if ! have javac; then
                printf '%s -- JRE only, a JDK is required' "$(java -version 2>&1 | head -n1)"
            elif [ "$jm" -lt "$MIN_JAVA" ] 2>/dev/null; then
                printf '%s -- JDK %s+ required' "$(java -version 2>&1 | head -n1)" "$MIN_JAVA"
            elif [ "$jm" -lt "$WANT_JAVA" ] 2>/dev/null; then
                printf '%s -- works; JDK %s recommended' "$(java -version 2>&1 | head -n1)" "$WANT_JAVA"
            else
                java -version 2>&1 | head -n1
            fi ;;
        mvn)    mvn --version 2>&1 | grep 'Apache Maven' | head -n1 ;;
        git)    git --version 2>&1 | head -n1 | sed 's/git version /v/' ;;
        node)
            if node_ts_ready; then node --version 2>&1 | head -n1
            else printf '%s -- keeping it; JS fine, TS needs %s.%s+'                      "$(node --version 2>&1 | head -n1)" "$MIN_NODE" "$MIN_NODE_TS"; fi ;;
        dotnet)
            if dotnet_ok; then dotnet --version 2>&1 | head -n1
            else printf '%s -- keeping it; C#/F# expect %s.0+'                      "$(dotnet --version 2>&1 | head -n1)" "$MIN_DOTNET"; fi ;;
        python)
            local p; p=$(python_bin)
            if [ -z "$p" ]; then echo 'NOT FOUND'
            elif py_ok "$p"; then $p --version 2>&1 | head -n1
            else printf '%s -- keeping it; %s.%s+ recommended for PyPI'                      "$($p --version 2>&1 | head -n1)" "$MIN_PY_MAJOR" "$MIN_PY_MINOR"; fi ;;
        cpp)    local c; c=$(cpp_bin);    [ -n "$c" ] && echo "$c" || echo 'NOT FOUND' ;;
        *)      echo found ;;
    esac
}

pkg_manager() {
    if   have brew;    then echo brew
    elif have apt-get; then echo apt
    elif have dnf;     then echo dnf
    elif have pacman;  then echo pacman
    fi
}

pkg_name() {
    local mgr="$1" brewf="$2" aptp="$3" dnfp="$4" pacp="$5"
    case "$mgr" in
        brew)   echo "$brewf" ;;
        apt)    echo "$aptp"  ;;
        dnf)    echo "$dnfp"  ;;
        pacman) echo "$pacp"  ;;
    esac
}

pkg_command() {
    local mgr="$1" pkg="$2"
    case "$mgr" in
        # Temurin ships as a cask; everything else we ask for is a formula.
        brew)   if [ "$pkg" = "temurin" ]; then echo "brew install --cask temurin"
                else echo "brew install $pkg"; fi ;;
        apt)    echo "sudo apt-get install -y $pkg" ;;
        dnf)    echo "sudo dnf install -y $pkg" ;;
        pacman) echo "sudo pacman -S --noconfirm $pkg" ;;
    esac
}

pkg_install() {
    local mgr="$1" pkg="$2"
    case "$mgr" in
        brew)   if [ "$pkg" = "temurin" ]; then brew install --cask temurin
                else brew install "$pkg"; fi ;;
        apt)    sudo apt-get install -y "$pkg" ;;
        dnf)    sudo dnf install -y "$pkg" ;;
        pacman) sudo pacman -S --noconfirm "$pkg" ;;
        *)      return 1 ;;
    esac
}

# -- Resumable state ---------------------------------------------------------
# A handful of key=value lines, the same schema install.ps1 writes.
STATE_COMPLETED=''
STATE_FAILED_STEP=''
STATE_FAILED_REASON=''

state_load() {
    STATE_COMPLETED=''; STATE_FAILED_STEP=''; STATE_FAILED_REASON=''
    [ -r "$STATE_FILE" ] || return 0
    local k v line
    while IFS= read -r line; do
        case "$line" in \#*|'') continue ;; esac
        k="${line%%=*}"; v="${line#*=}"
        case "$k" in
            completed)     STATE_COMPLETED="$v" ;;
            failed_step)   STATE_FAILED_STEP="$v" ;;
            failed_reason) STATE_FAILED_REASON="$v" ;;
        esac
    done < "$STATE_FILE"
}

state_save() {
    [ "$F_CHECK" = "1" ] && return 0
    {
        printf '# %s installer state -- delete this file to start over\n' "$PRODUCT"
        printf 'schema=1\n'
        printf 'completed=%s\n' "$STATE_COMPLETED"
        printf 'dir=%s\n' "$DIR"
        [ -n "$STATE_FAILED_STEP" ]   && printf 'failed_step=%s\n' "$STATE_FAILED_STEP"
        [ -n "$STATE_FAILED_REASON" ] && printf 'failed_reason=%s\n' "$STATE_FAILED_REASON"
        printf 'updated_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%S)"
    } > "$STATE_FILE"
}

step_done() { case ",${STATE_COMPLETED}," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

mark_done() {
    step_done "$1" || STATE_COMPLETED="${STATE_COMPLETED:+$STATE_COMPLETED,}$1"
    STATE_FAILED_STEP=''; STATE_FAILED_REASON=''
    state_save
}

resume_command() {
    local flags=''
    [ "$DIR" != "${ARIMA_HOME:-$HOME/.arima}" ] && flags="$flags --dir $DIR"
    [ "$F_SKIPOPT" = "1" ] && flags="$flags --skip-optional"
    [ "$F_NOPATH"  = "1" ] && flags="$flags --no-path"
    [ "$F_YES"     = "1" ] && flags="$flags --yes"
    if [ -f "${BASH_SOURCE[0]:-}" ]; then
        printf 'bash %s%s' "${BASH_SOURCE[0]}" "$flags"
    elif [ -n "$flags" ]; then
        printf 'curl -fsSL %s/install.sh | bash -s --%s' "$RAW_BASE" "$flags"
    else
        printf 'curl -fsSL %s/install.sh | bash' "$RAW_BASE"
    fi
}

# -- Diagnostics for the issue tracker ---------------------------------------
show_diagnostics() {
    local key="$1" reason="$2" k label req brewf aptp dnfp pacp url note mark mgr
    mgr=$(pkg_manager); [ -n "$mgr" ] || mgr='none detected'
    section 'WHAT TO DO NEXT'
    err  "  Step '$key' failed."
    dim  "  Reason: $reason"
    echo
    plain '  Nothing further was changed. Fix the cause and resume with:'
    info  "      $(resume_command)"
    echo
    plain '  Still stuck? File an issue -- it takes a minute and we read all of them:'
    info  "      $ISSUES_URL"
    echo
    plain '  Copy this block into the issue:'
    dim   "  $RULE"
    dim   "$(printf '    %-12s %s' 'failed step' "$key")"
    dim   "$(printf '    %-12s %s' 'reason'      "$reason")"
    dim   "$(printf '    %-12s %s' 'os'          "$(os_label)")"
    dim   "$(printf '    %-12s %s' 'shell'       "bash $BASH_VERSION")"
    dim   "$(printf '    %-12s %s' 'pkg manager' "$mgr")"
    dim   "$(printf '    %-12s %s' 'install dir' "$DIR")"
    dim   "$(printf '    %-12s %s' 'state file'  "$STATE_FILE")"
    while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
        [ -z "$k" ] && continue
        if dep_present "$k"; then mark=' '; else mark='!'; fi
        dim "$(printf '    %-12s %s %s' "$(printf '%s' "$label" | tr 'A-Z' 'a-z')" "$mark" "$(dep_detail "$k")")"
    done < <(dep_rows)
    dim   "  $RULE"
    echo
}

stop_install() {
    STATE_FAILED_STEP="$1"
    STATE_FAILED_REASON="$2"
    state_save
    show_diagnostics "$1" "$2"
    exit 1
}

# -- Phase 0: explain, then ask ----------------------------------------------
show_explanation() {
    local mgr; mgr=$(pkg_manager)

    section 'WHAT ARIMA NOTEBOOKS IS'
    plain '    A local-first, AI-native notebook for eight languages. JavaScript,'
    plain '    TypeScript, C#, F#, C++, Java, JShell and Python run side by side as'
    plain '    equals in one browser-based workspace -- real compilers, real package'
    plain '    managers (Maven, npm, NuGet, PyPI), real dependency pipelines.'
    echo
    plain '    Three AI co-pilots (Claude, GitHub Copilot, Antigravity) plug in through'
    plain '    CLIs you already have, and the whole system is exposed over MCP so any'
    plain '    agent can drive the same notebook you are editing.'
    echo
    plain '    Everything runs on your machine. No cloud account, no sign-up, no'
    plain '    telemetry, and nothing leaves this computer.'

    if [ "$F_DEV" = "1" ]; then
        plain ''
        plain '    Running in DEVELOPER mode: full history and every language runtime, for'
        plain '    working ON Arima. Drop --dev if you only want to use it.'
    fi

    section 'WHAT THIS SCRIPT WILL DO'
    plain '    1. Check your toolchain and show you a table before touching anything.'
    if [ -n "$mgr" ]; then
        plain "    2. Offer to install the missing pieces, one at a time, with ${mgr}."
        plain '       Nothing is installed that you have not said yes to.'
    else
        plain '    2. Report anything missing -- no brew/apt/dnf/pacman was found, so'
        plain '       nothing can be installed automatically on this machine.'
    fi
    plain "    3. Clone $REPO_URL"
    if [ "$F_DEV" = "1" ]; then
        plain "       into $DIR with full history, so you can branch and open pull requests."
    else
        plain "       into $DIR (a shallow clone; fast-forwards it if already there)."
    fi
    plain "    4. Build the JAR by handing over to the repository's own 'arima.sh install',"
    plain "       which prepares data/, notebooks/ and logs/. The build uses the bundled"
    plain "       Maven Wrapper, so a JDK is all you need - Maven itself is not required."

    section 'WHAT IT WILL CHANGE'
    row warn 'Disk'   "$DIR  (roughly 400 MB once built)"
    row warn 'Disk'   "$STATE_FILE  (progress, so a failed run can resume)"
    if [ "$F_NOPATH" = "1" ]; then
        row ok   'PATH' 'left untouched (--no-path)'
    else
        row warn 'PATH' "appends one export line to your shell rc -- new shells only"
    fi
    case "$mgr" in
        apt|dnf|pacman) row warn 'System' "packages you approve are installed with sudo $mgr" ;;
        brew)           row warn 'System' 'packages you approve are installed with brew (no sudo)' ;;
        *)              row ok   'System' 'no package manager found -- nothing will be installed' ;;
    esac
    row ok   'Files'  '.anb associations are NOT changed -- run "arima register" later if you want them'
    echo
    dim  '    Undo later with "arima uninstall", or by deleting the folder above.'
}

# -- Step 1: dependency check ------------------------------------------------
# Always runs. Probing is free and mutates nothing, so resuming never skips it:
# the later steps need to know what is actually on the machine right now.
MISSING=''
MISSING_N=0
MISSING_REQ=0

run_dep_check() {
    local k label req brewf aptp dnfp pacp url note tag why
    MISSING=''; MISSING_N=0; MISSING_REQ=0
    bar 20 'probing toolchains'
    while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
        [ -z "$k" ] && continue
        if dep_present "$k"; then
            row ok "$label" "$(dep_detail "$k")"
            continue
        fi
        if [ "$req" = "1" ]; then tag=err; why=REQUIRED; else tag=warn; why=optional; fi
        row "$tag" "$label" "missing ($why) -- $note"
        if [ "$F_SKIPOPT" = "1" ] && [ "$req" = "0" ]; then continue; fi
        MISSING="${MISSING}${k}|${label}|${req}|${brewf}|${aptp}|${dnfp}|${pacp}|${url}|${note}"$'\n'
        MISSING_N=$((MISSING_N + 1))
        [ "$req" = "1" ] && MISSING_REQ=$((MISSING_REQ + 1))
    done < <(dep_rows)
    bar 100 'toolchain probed'
    echo
    if [ "$MISSING_REQ" -eq 0 ]; then
        ok  '    Everything required is present.'
    else
        warn "    $MISSING_REQ required tool(s) missing -- Arima cannot be built until they are installed."
    fi
}

# -- Step 2: install the missing pieces, one at a time -----------------------
run_dep_install() {
    local mgr k label req brewf aptp dnfp pacp url note pkg why i=0 failed_req=0 failed_opt=''
    mgr=$(pkg_manager)

    if [ "$MISSING_N" -eq 0 ]; then
        bar 100 'nothing to install'
        row ok 'Dependencies' 'all present'
        return 0
    fi

    if [ -z "$mgr" ]; then
        bar 100 'no package manager found'
        row err 'Installer' 'no brew/apt/dnf/pacman -- install these by hand:'
        while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
            [ -z "$k" ] && continue
            dim "           $(printf '%-9s' "$label") $url"
        done <<< "$MISSING"
        if [ "$(uname -s)" = "Darwin" ]; then
            echo
            dim '           Homebrew is the usual way in on macOS:'
            dim '           /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'
        fi
        [ "$MISSING_REQ" -eq 0 ]
        return $?
    fi

    echo
    warn "  These will be installed with $mgr, one at a time:"
    while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
        [ -z "$k" ] && continue
        pkg=$(pkg_name "$mgr" "$brewf" "$aptp" "$dnfp" "$pacp")
        if [ "$req" = "1" ]; then why='required'; else why='optional'; fi
        dim "      $(printf '%-9s' "$label") $(printf '%-30s' "$(pkg_command "$mgr" "$pkg")") ($why)"
    done <<< "$MISSING"

    if ! confirm "Install the packages listed above?"; then
        echo
        warn '  Skipped -- no packages were installed.'
        dim  '  Re-run with --yes to install without prompting, or --skip-optional for the minimum.'
        [ "$MISSING_REQ" -eq 0 ]
        return $?
    fi

    [ "$mgr" = "apt" ] && sudo apt-get update

    while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
        [ -z "$k" ] && continue
        i=$((i + 1))
        pkg=$(pkg_name "$mgr" "$brewf" "$aptp" "$dnfp" "$pacp")
        echo
        bar $(( 100 * (i - 1) / MISSING_N )) "installing $label ($i of $MISSING_N)"
        dim "         $(pkg_command "$mgr" "$pkg")"
        pkg_install "$mgr" "$pkg" || true
        if dep_present "$k"; then
            bar $(( 100 * i / MISSING_N )) "$label installed"
            row ok "$label" "installed -- $(dep_detail "$k")"
        else
            bar $(( 100 * i / MISSING_N )) "$label not confirmed"
            row err "$label" "not visible after install -- try a NEW shell, or install by hand: $url"
            if [ "$req" = "1" ]; then failed_req=$((failed_req + 1))
            else failed_opt="${failed_opt}${label} "; fi
        fi
    done <<< "$MISSING"

    if [ "$failed_req" -gt 0 ]; then
        echo
        err "  $failed_req required tool(s) could not be installed."
        return 1
    fi
    if [ -n "$failed_opt" ]; then
        echo
        warn "  Optional runtimes did not install -- Arima will still run without them: $failed_opt"
    fi
    return 0
}

# -- Step 3: fetch the repository --------------------------------------------
run_fetch() {
    local logbase="${TMPDIR:-/tmp}/arima-install-$$"
    if [ -d "$DIR/.git" ]; then
        bar 30 'existing checkout found'
        row ok 'Checkout' "$DIR (already cloned)"
        ( git -C "$DIR" pull --ff-only ) > "$logbase.out" 2>&1 &
        if spin_wait $! 'updating the checkout'; then
            row ok 'Update' "fast-forwarded to origin/$BRANCH"
        else
            # A dirty or diverged tree is the user's work; refuse to fix it for them.
            row warn 'Update' 'could not fast-forward -- keeping the checkout exactly as it is'
            tail -n 8 "$logbase.out" 2>/dev/null | while IFS= read -r l; do dim "           $l"; done
        fi
        bar 100 'checkout ready'
        return 0
    fi

    if [ -e "$DIR" ] && [ -n "$(ls -A "$DIR" 2>/dev/null)" ]; then
        row err 'Directory' "$DIR exists and is not an Arima checkout"
        dim  '           Move it aside, or pass --dir <another path>.'
        return 1
    fi

    bar 20 "cloning $REPO_URL"
    mkdir -p "$(dirname "$DIR")" || return 1
    [ "$F_DEV" = "1" ] && CLONE_DEPTH=''
    ( git clone $CLONE_DEPTH --branch "$BRANCH" --single-branch "$REPO_URL" "$DIR" ) > "$logbase.out" 2>&1 &
    if ! spin_wait $! "cloning into $DIR"; then
        row err 'Clone' 'git clone failed'
        tail -n 12 "$logbase.out" 2>/dev/null | while IFS= read -r l; do dim "           $l"; done
        return 1
    fi
    bar 100 'clone complete'
    row ok 'Checkout' "$DIR"
    return 0
}

# -- Step 4: hand over to the repository's own installer ---------------------
# arima.sh already knows how to prepare the workspace, wire the AI guardrails
# and run Maven. Reuse it rather than re-implement it here.
run_setup() {
    local launcher="$DIR/arima.sh"
    if [ ! -f "$launcher" ]; then
        row err 'Launcher' "arima.sh not found in $DIR"
        return 1
    fi
    chmod +x "$launcher" 2>/dev/null || true

    bar 20 'handing over to arima.sh install'
    dim  "         $launcher install --yes --skip-optional"
    echo
    # --skip-optional: the optional runtimes were already offered in step 2, and
    # arima.sh must not install anything behind the user's back here.
    local anim_flag=''
    [ "$ANIM" = "1" ] || anim_flag='--no-anim'
    if ! ( cd "$DIR" && bash "$launcher" install --yes --skip-optional $anim_flag ); then
        row err 'Build' 'arima.sh install did not complete'
        return 1
    fi
    bar 100 'built'
    return 0
}

# -- Step 5: PATH ------------------------------------------------------------
# arima.sh --path only prints the export line; appending it is this script's
# job, because only an installer has the standing to edit a shell rc.
add_to_path() {
    if [ "$F_NOPATH" = "1" ]; then
        row ok 'PATH' 'left untouched (--no-path)'
        return 0
    fi
    local rc marker='# added by the Arima Notebooks installer'
    case "${SHELL##*/}" in
        zsh)  rc="$HOME/.zshrc" ;;
        bash) if [ "$(uname -s)" = "Darwin" ]; then rc="$HOME/.bash_profile"; else rc="$HOME/.bashrc"; fi ;;
        *)    rc="$HOME/.profile" ;;
    esac
    if [ -f "$rc" ] && grep -Fq "$marker" "$rc" 2>/dev/null; then
        row ok 'PATH' "$rc already has the entry"
        return 0
    fi
    if printf '\n%s\nexport PATH="$PATH:%s"\n' "$marker" "$DIR" >> "$rc" 2>/dev/null; then
        row ok 'PATH' "export line appended to $rc  (new shells only)"
    else
        row warn 'PATH' "could not write $rc -- add this line yourself:"
        dim  "           export PATH=\"\$PATH:$DIR\""
    fi
    return 0
}

# -- Success -----------------------------------------------------------------
show_success() {
    show_brew
    section 'INSTALLED'
    ok   "  $PRODUCT is installed and ready."
    echo
    row ok 'Location' "$DIR"
    row ok 'JAR'      "$DIR/target/arima-notebooks-4.0.1.jar"

    section 'NEXT'
    plain '    arima start        Start the server and open the notebook UI'
    info  "                       -> $URL"
    plain '    arima start --bg   Start it detached, logging to arima.log'
    plain '    arima status       Server state, detected runtimes, AI co-pilots'
    plain '    arima welcome      The three ways to work with Arima Notebooks'
    plain '    arima register     Associate .anb notebook files with Arima, so'
    plain '                       double-clicking one opens it in the UI'
    echo
    dim   "    In a new shell:   cd $DIR  &&  ./arima.sh start"
    echo
}

# -- Main --------------------------------------------------------------------
main() {
    banner 'A R I M A   -   I N S T A L L E R'

    if [ "$F_RESET" = "1" ] && [ -f "$STATE_FILE" ]; then
        rm -f "$STATE_FILE"
        dim '  (--reset) previous progress discarded'
    fi
    state_load

    show_explanation

    if [ "$F_CHECK" = "1" ]; then
        section 'DEPENDENCY CHECK  (--check-only: nothing will be changed)'
        run_dep_check
        section 'CHECK ONLY -- STOPPED'
        ok  '  Nothing was installed, downloaded, or added to your PATH.'
        dim '  Drop --check-only to run the install for real.'
        echo
        return 0
    fi

    if [ -n "$STATE_COMPLETED" ]; then
        section 'RESUMING'
        row ok 'State' "$STATE_FILE"
        row ok 'Done'  "$STATE_COMPLETED"
        [ -n "$STATE_FAILED_STEP" ] && \
            row warn 'Last failure' "$STATE_FAILED_STEP -- $STATE_FAILED_REASON"
        dim '           Completed steps are skipped. Use --reset to run them all again.'
    fi

    if ! confirm 'Proceed with the steps listed above?'; then
        echo
        warn '  Cancelled -- nothing was changed.'
        echo
        return 1
    fi

    state_save
    local total=5

    step 1 $total 'Checking dependencies'
    run_dep_check
    mark_done 'deps-check'

    if step_done 'deps-install'; then
        step 2 $total 'Installing missing dependencies  (already done -- skipped)'
        bar 100 'skipped'
    else
        step 2 $total 'Installing missing dependencies'
        run_dep_install || stop_install 'deps-install' \
            'a required tool is still missing after the install attempt'
        mark_done 'deps-install'
    fi

    # Re-probe: step 2 may have just put Java, Maven or Git on the PATH.
    local k label req brewf aptp dnfp pacp url note blockers=''
    while IFS='|' read -r k label req brewf aptp dnfp pacp url note; do
        [ -z "$k" ] && continue
        [ "$req" = "1" ] || continue
        dep_present "$k" || blockers="${blockers}${label} "
    done < <(dep_rows)
    [ -n "$blockers" ] && stop_install 'deps-install' "required tools not available: $blockers"

    if step_done 'fetch'; then
        step 3 $total 'Fetching Arima Notebooks  (already done -- skipped)'
        bar 100 'skipped'
    else
        step 3 $total 'Fetching Arima Notebooks'
        run_fetch || stop_install 'fetch' "could not clone or update $REPO_URL into $DIR"
        mark_done 'fetch'
    fi

    if step_done 'setup'; then
        step 4 $total 'Building the JAR  (already done -- skipped)'
        bar 100 'skipped'
    else
        step 4 $total 'Building the JAR'
        run_setup || stop_install 'setup' 'the Maven build (arima.sh install) did not complete'
        mark_done 'setup'
    fi

    if step_done 'path'; then
        step 5 $total 'Registering on your PATH  (already done -- skipped)'
        bar 100 'skipped'
    else
        step 5 $total 'Registering on your PATH'
        add_to_path
        mark_done 'path'
    fi

    show_success
    return 0
}

main
exit $?
