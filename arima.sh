#!/usr/bin/env bash
# ============================================================================
#  Arima Notebooks - Linux / macOS CLI
#
#  Shares one look, one animation vocabulary and one command set with
#  arima.cmd (Windows CMD) and arima.ps1 (PowerShell).
#
#    Lifecycle : install  update  uninstall
#    Server    : start [--bg]  stop  restart  status  open  logs
#    Build     : build  rebuild
#    MCP       : mcp [ping|info|tools|call|exec|notebooks|read|search|agents|
#                     run-agent|raw|config]
#    Info      : version  welcome  docs  agents  help
#
#  Everything printed is 7-bit ASCII so all three launchers render identically.
# ============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR="target/arima-notebooks-1.0.0-SNAPSHOT.jar"
PORT=8585
URL="http://localhost:${PORT}"
MCP_URL="${URL}/api/mcp/messages"
MIN_JAVA=17
RULE="------------------------------------------------------------"
TAGLINE='A local-first, AI-native notebook for eight languages - run code, build pipelines, and drive it all over MCP.'

# ── AI co-pilot context ─────────────────────────────────────────────────────
# These files turn any AI CLI invoked inside this repo (the in-UI AI panel or a
# terminal session) into an Arima-aware co-pilot that follows the architecture
# guardrails and can use the registered skills + subagents.
AGENTS_GUIDE="$SCRIPT_DIR/AGENTS.md"
SKILLS_DIR="$SCRIPT_DIR/.claude/skills"
AGENTS_DIR="$SCRIPT_DIR/.claude/agents"

# ── Colours (skip if NO_COLOR / ARIMA_NO_COLOR or not a TTY) ────────────────
if [ -t 1 ] && [ -z "${NO_COLOR-}" ] && [ -z "${ARIMA_NO_COLOR-}" ]; then
    C_RESET=$'\033[0m'; C_DIM=$'\033[90m';   C_RED=$'\033[91m'
    C_GREEN=$'\033[92m'; C_YELLOW=$'\033[93m'; C_CYAN=$'\033[96m'
    C_WHITE=$'\033[97m'
else
    C_RESET=''; C_DIM=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_CYAN=''; C_WHITE=''
fi
ANIM=1
[ -t 1 ] || ANIM=0
[ -n "${ARIMA_NO_ANIM-}" ] && ANIM=0

say()    { printf '%b\n' "$*"; }
title()  { say "${C_WHITE}$*${C_RESET}"; }
info()   { say "${C_CYAN}$*${C_RESET}"; }
ok()     { say "${C_GREEN}$*${C_RESET}"; }
warn()   { say "${C_YELLOW}$*${C_RESET}"; }
err()    { say "${C_RED}$*${C_RESET}"; }
dim()    { say "${C_DIM}$*${C_RESET}"; }

frame_pause() { [ "$ANIM" = "1" ] && sleep "${1:-0.055}"; return 0; }

section() { echo; info "  $1"; dim "  $RULE"; }

# step <index> <total> <text>
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

# ── The Barista brew animation ──────────────────────────────────────────────
# A coffee bean drops into the cup, Barista brews it, and serves it steaming.
# Eight frames redrawn in place with ANSI cursor-up (ESC[8A); the same art,
# timing and captions ship in arima.cmd and arima.ps1.
# Rows 0-1 = bean / steam, rows 2-6 = the cup, row 7 = the caption.
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
    esac
    local clr=''
    [ "${1-}" != '' ] && [ "$ANIM" = "1" ] && clr=$'\033[2K'
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
    # No animation available (piped output or --no-anim): show the served cup.
    if [ "$ANIM" != "1" ]; then brew_frame 7; return 0; fi
    local seq=(1 2 3 4 5 6 7 8 7 8) i=0
    for n in "${seq[@]}"; do
        [ "$i" -gt 0 ] && printf '\033[8A'
        brew_frame "$n"
        if [ "$i" -lt 6 ]; then sleep 0.13; else sleep 0.19; fi
        i=$((i + 1))
    done
}

# The mascot: a coffee bean. Six rows, each padded to 18 columns so the text
# column beside it always starts at the same offset.
BANNER_ART=(
'     .-"""""-.    '
"   .'    \    '.  "
'  /      )      \ '
'  \      (      / '
"   '.    /    .'  "
"     '-.....-'    "
)

# banner [heading] [l1] [l2] [l3] [l4]
banner() {
    local head="${1:-A R I M A   N O T E B O O K S}"
    local l1="${2:-------------------------------------------}"
    local l2="${3:-Java  JShell  JS  TS  C#  F#  C++  Python}"
    local l3="${4:-Brewed by Barista - JShell + Spring Boot}"
    local l4="${5:-Server: $URL}"
    local text=("$head" "$l1" "$l2" "$l3" "$l4" '')
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

# ── Probes ──────────────────────────────────────────────────────────────────
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

python_bin() {
    for b in python3 python py; do have "$b" && { echo "$b"; return; }; done
}

cpp_bin() {
    for b in g++ clang++ cl; do have "$b" && { echo "$b"; return; }; done
}

server_up() {
    if have curl; then
        curl -s -o /dev/null -m 2 "$URL" >/dev/null 2>&1
    elif have wget; then
        wget -q -T 2 -O /dev/null "$URL" >/dev/null 2>&1
    else
        (echo >"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
    fi
}

listening_pid() {
    local pid=''
    if have lsof; then
        pid=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -n1)
    elif have ss; then
        pid=$(ss -ltnp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p { print $0 }' \
            | grep -oE 'pid=[0-9]+' | head -n1 | cut -d= -f2)
    elif have netstat; then
        # Windows netstat (Git Bash / MSYS) spells the state LISTENING and puts
        # the PID in the last column; BSD/macOS uses LISTEN with the PID in $9.
        pid=$(netstat -ano 2>/dev/null \
              | awk -v p=":$PORT$" '/LISTENING/ && $2 ~ p { print $NF; exit }')
        [ -z "$pid" ] && pid=$(netstat -anv 2>/dev/null \
              | awk -v p="\.$PORT$" '/LISTEN/ && $4 ~ p { print $9; exit }')
    fi
    printf '%s' "$pid" | grep -E '^[0-9]+$' || true
}

# kill(1) cannot signal a native Windows PID from Git Bash; taskkill can.
kill_pid() {
    kill "$1" 2>/dev/null && return 0
    have taskkill && taskkill //PID "$1" //F >/dev/null 2>&1 && return 0
    return 1
}

open_browser() {
    if   have xdg-open;       then xdg-open "$URL" >/dev/null 2>&1 &
    elif have open;           then open "$URL"     >/dev/null 2>&1 &
    elif have powershell.exe; then powershell.exe -NoProfile -Command "Start-Process '$URL'" >/dev/null 2>&1 &
    else dim "  Open $URL manually."
    fi
}

# Spinner-backed wait; same frame set as arima.cmd / arima.ps1.
wait_for_server() {
    local timeout="${1:-45}" frames='-\|/' i=0 elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        server_up && { [ "$ANIM" = "1" ] && printf '\r\033[2K'; return 0; }
        if [ "$ANIM" = "1" ]; then
            printf '\r\033[2K%s  [%s]  waiting for the server ... %ss%s' \
                "$C_DIM" "${frames:$((i % 4)):1}" "$((timeout - elapsed))" "$C_RESET"
        fi
        sleep 0.5
        i=$((i + 1))
        elapsed=$((elapsed + 1))
    done
    [ "$ANIM" = "1" ] && printf '\r\033[2K'
    return 1
}

confirm() {
    if [ "${F_YES:-0}" = "1" ]; then dim "  (--yes) $1 -> yes"; return 0; fi
    echo
    printf '%s  %s %s[type '"'"'yes'"'"' to confirm]: %s' "$C_YELLOW" "$1" "$C_DIM" "$C_RESET"
    local answer; read -r answer
    [ "$(printf '%s' "$answer" | tr 'A-Z' 'a-z')" = "yes" ]
}

# ── AI co-pilot wiring ──────────────────────────────────────────────────────
detect_copilots() {
    local first
    for first in claude;                        do have "$first" && { echo "Claude=$first";      break; }; done
    for first in copilot github-copilot-cli gh; do have "$first" && { echo "Copilot=$first";     break; }; done
    for first in agy gemini;                    do have "$first" && { echo "Antigravity=$first"; break; }; done
}

set_ai_context() {
    export BARISTA_HOME="$SCRIPT_DIR"
    [ -f "$AGENTS_GUIDE" ] && export BARISTA_AGENTS_GUIDE="$AGENTS_GUIDE"
    [ -d "$SKILLS_DIR" ]   && export BARISTA_SKILLS_DIR="$SKILLS_DIR"
    [ -d "$AGENTS_DIR" ]   && export BARISTA_AGENTS_DIR="$AGENTS_DIR"
    local names
    names=$(detect_copilots | cut -d= -f1 | tr '\n' ',' | sed 's/,$//' | tr 'A-Z' 'a-z')
    [ -n "$names" ] && export BARISTA_AI_COPILOTS="$names"
    return 0
}

show_copilots() {
    local names
    names=$(detect_copilots | cut -d= -f1 | tr '\n' ' ')
    if [ -n "${names// }" ]; then
        row ok   'AI' "${names} (co-pilot ready)"
    else
        row warn 'AI' 'no CLI found  (install Claude, Copilot, or Antigravity)'
    fi
    if [ -f "$AGENTS_GUIDE" ] && [ -d "$SKILLS_DIR" ] && [ -d "$AGENTS_DIR" ]; then
        row ok   'Guardrails' 'AGENTS.md + skills/ + agents/ loaded  (run: ./arima.sh agents)'
    else
        row warn 'Guardrails' 'AGENTS.md / .claude skills+agents missing'
    fi
}

# ── Runtime inventory (shared by install / status / version) ────────────────
show_runtimes() {
    local jm; jm=$(java_major)
    if [ "$jm" -ge "$MIN_JAVA" ] 2>/dev/null; then
        row ok  'Java' "$(java -version 2>&1 | head -n1)"
    elif [ "$jm" -gt 0 ] 2>/dev/null; then
        row err 'Java' "$(java -version 2>&1 | head -n1) -- JDK ${MIN_JAVA}+ required"
    else
        row err 'Java' "NOT FOUND -- install JDK ${MIN_JAVA}+ from https://adoptium.net/"
    fi

    if have mvn; then
        row ok  'Maven' "$(mvn --version 2>&1 | grep 'Apache Maven' | head -n1)"
    else
        row err 'Maven' 'NOT FOUND -- needed to build (https://maven.apache.org/)'
    fi

    if have node; then row ok   'Node.js' "$(node --version)  -- JS / TS cells"
    else               row warn 'Node.js' 'not found -- JS / TS cells disabled (nodejs.org)'; fi

    have tsc && row ok 'tsc' 'found -- TypeScript type-check diagnostics on'

    if have dotnet; then row ok   '.NET' "$(dotnet --version)  -- C# / F# cells"
    else                 row warn '.NET' 'not found -- C# / F# cells disabled (https://dot.net)'; fi

    local py; py=$(python_bin)
    if [ -n "$py" ]; then row ok   'Python' "$($py --version 2>&1)  -- Python cells + PyPI"
    else                  row warn 'Python' 'not found -- Python cells disabled (python.org)'; fi

    local cxx; cxx=$(cpp_bin)
    if [ -n "$cxx" ]; then row ok   'C++' "$cxx  -- C++ cells"
    else                   row warn 'C++' 'no compiler found -- C++ cells disabled (GCC / Clang)'; fi

    if have git; then row ok   'Git' "found -- './arima.sh update' can sync with upstream"
    else              row warn 'Git' "not found -- './arima.sh update' unavailable"; fi
}

# ── Dependency catalogue + auto-install ─────────────────────────────────────
# Rows are "key|Label|required|brew-formula|apt-package|url|note".
dep_rows() {
    cat <<'EOF'
java|Java|1|temurin|openjdk-21-jdk|https://adoptium.net/|JDK 21 - runs the server and JShell cells
mvn|Maven|1|maven|maven|https://maven.apache.org/|builds the Arima JAR
git|Git|0|git|git|https://git-scm.com/|enables: ./arima.sh update
node|Node.js|0|node|nodejs|https://nodejs.org/|JavaScript + TypeScript cells, npm packages
dotnet|.NET|0|dotnet-sdk|dotnet-sdk-8.0|https://dot.net/|C# + F# cells, NuGet packages
python|Python|0|python@3.13|python3|https://www.python.org/downloads/|Python cells, PyPI packages
cpp|C++|0|gcc|g++|https://gcc.gnu.org/|C++ cells
EOF
}

dep_present() {
    case "$1" in
        java)   [ "$(java_major)" -ge "$MIN_JAVA" ] 2>/dev/null ;;
        python) [ -n "$(python_bin)" ] ;;
        cpp)    [ -n "$(cpp_bin)" ] ;;
        *)      have "$1" ;;
    esac
}

# Which package manager can install things here?
pkg_manager() {
    if   have brew;    then echo brew
    elif have apt-get; then echo apt
    elif have dnf;     then echo dnf
    elif have pacman;  then echo pacman
    fi
}

pkg_install() {
    local mgr="$1" label="$2" brewf="$3" aptp="$4"
    case "$mgr" in
        brew)   dim "         brew install $brewf";        brew install "$brewf" ;;
        apt)    dim "         sudo apt-get install -y $aptp"; sudo apt-get install -y "$aptp" ;;
        dnf)    dim "         sudo dnf install -y $aptp";  sudo dnf install -y "$aptp" ;;
        pacman) dim "         sudo pacman -S --noconfirm $aptp"; sudo pacman -S --noconfirm "$aptp" ;;
        *)      return 1 ;;
    esac
}

# ── Subcommands: lifecycle ──────────────────────────────────────────────────
cmd_install() {
    banner 'A R I M A   -   I N S T A L L' \
           '------------------------------------------' \
           'Check every dependency, install what is missing,' \
           'build the JAR, and report readiness.' \
           "Server: $URL"

    local total=6 missing=() key label req brewf aptp url note

    step 1 $total 'Checking dependencies'
    bar 10 'probing toolchains'
    show_runtimes
    while IFS='|' read -r key label req brewf aptp url note; do
        [ -z "$key" ] && continue
        [ "${F_SKIPOPT:-0}" = "1" ] && [ "$req" = "0" ] && continue
        dep_present "$key" || missing+=("$key|$label|$req|$brewf|$aptp|$url|$note")
    done < <(dep_rows)

    step 2 $total 'Installing missing dependencies'
    local mgr; mgr=$(pkg_manager)
    if [ "${#missing[@]}" -eq 0 ]; then
        bar 100 'nothing to install'
        row ok 'Dependencies' 'all present'
    elif [ -z "$mgr" ]; then
        bar 100 'no package manager found'
        row err 'Installer' 'no brew/apt/dnf/pacman -- install these by hand:'
        for m in "${missing[@]}"; do
            IFS='|' read -r key label req brewf aptp url note <<< "$m"
            dim "           $(printf '%-9s' "$label") $url"
        done
    else
        echo
        warn "  These will be installed with $mgr (system-wide):"
        for m in "${missing[@]}"; do
            IFS='|' read -r key label req brewf aptp url note <<< "$m"
            if [ "$mgr" = "brew" ]; then dim "      $(printf '%-9s' "$label") $brewf"
            else                          dim "      $(printf '%-9s' "$label") $aptp"; fi
        done
        if confirm "Install the packages listed above?"; then
            echo
            [ "$mgr" = "apt" ] && sudo apt-get update
            local i=0
            for m in "${missing[@]}"; do
                IFS='|' read -r key label req brewf aptp url note <<< "$m"
                i=$((i + 1))
                bar $(( 100 * i / ${#missing[@]} )) "installing $label"
                pkg_install "$mgr" "$label" "$brewf" "$aptp" || true
                if dep_present "$key"; then row ok "$label" 'installed'
                else row warn "$label" 'not visible yet -- open a NEW shell and re-run'; fi
            done
        else
            echo
            warn '  Skipped -- no packages were installed.'
            dim  '  Re-run with --yes to install without prompting, or --skip-optional for the minimum.'
        fi
    fi

    step 3 $total 'Preparing the workspace'
    bar 100 'creating folders'
    for d in data notebooks logs; do
        if [ -d "$d" ]; then row ok "$d" 'already present'
        else mkdir -p "$d" && row ok "$d" 'created'; fi
    done

    step 4 $total 'Wiring AI co-pilots'
    bar 100 'detecting AI CLIs'
    set_ai_context
    show_copilots

    step 5 $total 'Building the JAR'
    if ! dep_present java || ! dep_present mvn; then
        row err 'Build' 'skipped -- a required tool is still missing'
    elif [ "${F_NOBUILD:-0}" = "1" ]; then
        bar 100 'skipped (--no-build)'
    else
        bar 50 'mvn clean package -DskipTests'
        echo
        if ! mvn clean package -DskipTests; then
            echo; err '  Build failed -- see the Maven output above.'; return 1
        fi
        bar 100 'build complete'
        row ok 'JAR' "$JAR"
    fi

    step 6 $total 'Registering on your PATH'
    if [ "${F_PATH:-0}" = "1" ]; then
        row ok 'PATH' "add this line to your shell rc:"
        dim  "           export PATH=\"\$PATH:$SCRIPT_DIR\""
    else
        row ok 'PATH' 'left untouched  (use --path for the export line)'
    fi

    section 'READINESS'
    show_runtimes
    if [ -f "$JAR" ]; then row ok 'JAR' "$JAR"; else row err 'JAR' 'not built'; fi

    local still=() reqmiss=0
    while IFS='|' read -r key label req brewf aptp url note; do
        [ -z "$key" ] && continue
        dep_present "$key" || { still+=("$label|$url|$note"); [ "$req" = "1" ] && reqmiss=1; }
    done < <(dep_rows)

    echo
    if [ "$reqmiss" = "0" ] && [ -f "$JAR" ]; then
        show_brew
        if [ "${#still[@]}" -eq 0 ]; then
            ok '  EVERYTHING IS READY -- all eight languages are available.'
        else
            ok "  READY -- Arima will start. ${#still[@]} optional runtime(s) absent:"
            for s in "${still[@]}"; do
                IFS='|' read -r label url note <<< "$s"
                dim "      $(printf '%-9s' "$label") $note   $url"
            done
            dim '      Add them later and re-run: ./arima.sh install'
        fi
        echo
        echo '    ./arima.sh start        Start the server and open the UI'
        echo '    ./arima.sh start --bg   Start detached, logs to arima.log'
        echo '    ./arima.sh mcp tools    Drive Arima over MCP from this terminal'
        echo '    ./arima.sh update       Sync with upstream and rebuild'
        echo
        return 0
    fi

    err '  NOT READY.'
    for s in "${still[@]}"; do
        IFS='|' read -r label url note <<< "$s"
        dim "      $(printf '%-9s' "$label") $url"
    done
    [ -f "$JAR" ] || dim '      JAR       not built -- run ./arima.sh build once the tools above exist'
    dim '      Then re-run: ./arima.sh install'
    echo
    return 1
}

cmd_update() {
    banner 'A R I M A   -   U P D A T E' \
           '------------------------------------------' \
           'Sync this checkout with upstream, then rebuild.' \
           'Your local commits are replayed on top (rebase).' \
           'Uncommitted work always blocks the update.'

    if ! have git; then
        err '  ERROR: git not found.'
        dim '  Install git from https://git-scm.com/ then re-run: ./arima.sh update'
        return 1
    fi
    if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        err '  ERROR: this folder is not a git repository.'
        dim '  Re-clone with: git clone https://github.com/snchande/arima-notebooks.git'
        return 1
    fi

    local total=5 was_running=0 rebased=0
    server_up && was_running=1

    # 1 -- the working tree must be clean, or a rebase could eat local edits
    step 1 $total 'Checking your working tree'
    bar 10 'git status'
    local tracked untracked
    tracked=$(git status --porcelain --untracked-files=no 2>/dev/null)
    untracked=$(git ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')
    if [ -n "$tracked" ]; then
        echo
        section 'UNCOMMITTED CHANGES -- UPDATE STOPPED'
        err '  You have local edits that a rebase could overwrite.'
        dim '  Nothing has been changed. Commit (or stash) them first so your work is safe:'
        echo
        printf '%s\n' "$tracked" | head -20 | while IFS= read -r l; do warn "      $l"; done
        echo
        info '  Option A -- keep the changes as a commit (recommended)'
        dim  '      git add -A'
        dim  '      git commit -m "wip: my local changes"'
        dim  '      ./arima.sh update'
        echo
        info '  Option B -- park the changes, update, then bring them back'
        dim  '      git stash push -u -m "before arima update"'
        dim  '      ./arima.sh update'
        dim  '      git stash pop'
        echo
        return 2
    fi
    row ok 'Working tree' 'clean -- safe to rebase'
    [ "$untracked" -gt 0 ] && row warn 'Untracked' "$untracked new file(s) present -- they will be left alone"

    # 2 -- fetch
    step 2 $total 'Fetching from upstream'
    bar 30 'git fetch --prune'
    local has_origin=1
    git remote get-url origin >/dev/null 2>&1 || has_origin=0
    if [ "$has_origin" = "0" ]; then
        row warn 'Remote' "no 'origin' configured -- skipping sync, rebuilding only"
    else
        if ! git fetch --prune origin; then
            row err 'Fetch' 'failed -- check your network / credentials'
            return 1
        fi
        row ok 'Fetch' 'up to date with origin'
    fi

    # 3 -- fast-forward when purely behind, rebase when we have local commits
    local base=''
    if [ "$has_origin" = "1" ]; then
        step 3 $total 'Syncing with the base branch'
        local branch counts ahead behind
        branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
        base=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)
        if [ -z "$base" ]; then
            base='origin/master'
            git rev-parse --verify --quiet "$base" >/dev/null 2>&1 || base='origin/main'
        fi
        if ! git rev-parse --verify --quiet "$base" >/dev/null 2>&1; then
            row err 'Base' "cannot resolve a base branch (tried $base)"
            return 1
        fi
        row ok 'Branch' "$branch  ->  $base"

        counts=$(git rev-list --left-right --count "HEAD...$base" 2>/dev/null)
        ahead=$(echo "$counts"  | awk '{print $1}')
        behind=$(echo "$counts" | awk '{print $2}')
        row ok 'Divergence' "$ahead local commit(s) ahead, $behind behind"

        if [ "$behind" = "0" ]; then
            bar 60 'already in sync'
            row ok 'Sync' 'nothing to pull -- already current'
        elif [ "$ahead" = "0" ]; then
            bar 50 "fast-forwarding $behind commit(s)"
            if ! git merge --ff-only "$base"; then
                row err 'Sync' "fast-forward failed -- run 'git status' to inspect"
                return 3
            fi
            row ok 'Sync' "fast-forwarded $behind commit(s)"
            rebased=1
        else
            bar 50 "rebasing $ahead local commit(s) onto $base"
            if ! git rebase "$base"; then
                local conflicts
                conflicts=$(git diff --name-only --diff-filter=U 2>/dev/null)
                # Restore the pre-rebase state; never leave a half-finished rebase.
                git rebase --abort >/dev/null 2>&1
                echo
                section 'MERGE CONFLICT -- UPDATE ROLLED BACK'
                err '  The rebase hit conflicts, so it was aborted.'
                dim '  Your branch is back exactly where it was -- nothing was lost.'
                if [ -n "$conflicts" ]; then
                    echo
                    info '  Files that conflict with upstream:'
                    printf '%s\n' "$conflicts" | head -20 | while IFS= read -r f; do warn "      $f"; done
                fi
                echo
                info '  Resolve it by hand, once:'
                dim  "      git rebase $base"
                dim  '      # ...fix the conflicted files, then...'
                dim  '      git add <file>'
                dim  '      git rebase --continue'
                dim  '      ./arima.sh build'
                echo
                info '  Or drop your local commits and take upstream as-is (destructive):'
                dim  "      git reset --hard $base"
                echo
                return 3
            fi
            row ok 'Sync' "rebased $ahead commit(s) onto $base"
            rebased=1
        fi
    fi

    # 4 -- rebuild
    if [ "${F_NOBUILD:-0}" = "1" ]; then
        step 4 $total 'Build skipped (--no-build)'
        bar 100 'skipped'
    elif [ "$rebased" = "0" ] && [ -f "$JAR" ]; then
        step 4 $total 'Rebuild not needed'
        bar 100 'JAR already current'
        row ok 'JAR' 'unchanged -- no new commits to compile'
    else
        step 4 $total 'Rebuilding the JAR'
        have mvn || { row err 'Maven' 'not found -- install from https://maven.apache.org/'; return 1; }
        bar 80 'mvn clean package -DskipTests'
        echo
        if ! mvn clean package -DskipTests; then
            echo; err '  Build failed after the update -- see the Maven output above.'; return 1
        fi
        bar 100 'build complete'
        row ok 'JAR' "$JAR"
    fi

    step 5 $total 'Finishing up'
    if [ "$was_running" = "1" ]; then
        row warn 'Server' 'still running the OLD jar -- restart to pick up the update'
        dim  '           ./arima.sh restart'
    else
        row ok 'Server' 'not running -- start it with: ./arima.sh start'
    fi

    section 'UPDATED'
    ok '  Arima Notebooks is up to date.'
    echo
    return 0
}

cmd_uninstall() {
    banner 'A R I M A   -   U N I N S T A L L' \
           '------------------------------------------' \
           'Remove build output and runtime logs.' \
           'Your notebooks and source code are never touched.' \
           ''

    local targets=()
    [ -d target ]          && targets+=("target/|compiled classes + the JAR")
    [ -f arima.log ]       && targets+=("arima.log|background stdout log")
    [ -f arima-err.log ]   && targets+=("arima-err.log|background stderr log")
    if [ "${F_PURGE:-0}" = "1" ] && [ -d data ]; then
        targets+=("data/|SETTINGS, packages, users -- unrecoverable")
    fi

    section 'WILL BE REMOVED'
    if [ "${#targets[@]}" -eq 0 ]; then
        dim '    (nothing -- already clean)'
    else
        for t in "${targets[@]}"; do
            IFS='|' read -r name note <<< "$t"
            if [ "$name" = "data/" ]; then row err "$name" "$note"; else row warn "$name" "$note"; fi
        done
    fi

    section 'WILL BE KEPT'
    row ok 'notebooks/'  'every notebook you wrote'
    row ok 'src/, docs/' 'source code and documentation'
    row ok '.git/'       'git history and remotes'
    [ "${F_PURGE:-0}" = "1" ] || row ok 'data/' 'settings, packages, users  (use --purge to delete)'

    if [ "${#targets[@]}" -eq 0 ]; then
        echo; ok '  Nothing to do.'; echo; return 0
    fi
    if ! confirm 'Remove the items listed above?'; then
        echo; warn '  Cancelled -- nothing was removed.'; echo; return 1
    fi

    echo
    step 1 3 'Stopping the server'
    if server_up; then cmd_stop >/dev/null 2>&1; row ok 'Server' 'stopped'
    else row ok 'Server' 'not running'; fi

    step 2 3 'Removing files'
    local i=0
    for t in "${targets[@]}"; do
        IFS='|' read -r name note <<< "$t"
        i=$((i + 1))
        bar $(( 100 * i / ${#targets[@]} )) "removing $name"
        rm -rf "${name%/}" && row ok "$name" 'removed' || row err "$name" 'could not remove'
    done

    step 3 3 'Cleaning up the PATH entry'
    if [ "${F_PATH:-0}" = "1" ]; then
        row warn 'PATH' "remove this line from your shell rc:"
        dim  "           export PATH=\"\$PATH:$SCRIPT_DIR\""
    else
        row ok 'PATH' 'left untouched  (use --path to be reminded about the entry)'
    fi

    section 'UNINSTALLED'
    ok  '  Build output removed. The checkout itself is intact.'
    dim '  Reinstall any time with:  ./arima.sh install'
    echo
    return 0
}

# ── Subcommands: server ─────────────────────────────────────────────────────
ensure_jar() {
    [ -f "$JAR" ] && return 0
    warn '  JAR not found -- building first...'
    have mvn || { err '  ERROR: Maven not found. Install from https://maven.apache.org/'; return 1; }
    mvn clean package -DskipTests -q || { err '  Build failed.'; return 1; }
    ok '  Build complete.'
}

cmd_start() {
    show_brew
    banner

    if server_up; then
        ok   '  Arima Notebooks is already running.'
        info "  URL: $URL"
        printf '  Open in browser? [Y/n]: '
        local ans; read -r ans
        [[ ! "$ans" =~ ^[nN]$ ]] && open_browser
        return 0
    fi

    section 'ENVIRONMENT'
    if [ "$(java_major)" -lt "$MIN_JAVA" ] 2>/dev/null; then
        err "  ERROR: JDK ${MIN_JAVA}+ not found."
        dim '  Install it from https://adoptium.net/  -- or let Arima do it: ./arima.sh install'
        return 1
    fi
    show_runtimes

    # Wire the AI co-pilot context before launching the JVM so the in-UI AI
    # panel (and any CLI it spawns) inherits the guardrails + skills + agents.
    set_ai_context
    show_copilots

    ensure_jar || return 1

    local jvm_args=(
        --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED
        --add-opens=java.base/java.lang=ALL-UNNAMED
        --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED
        -jar "$JAR"
    )

    if [ "${F_BG:-0}" = "1" ]; then
        section 'STARTING (BACKGROUND)'
        nohup java "${jvm_args[@]}" >arima.log 2>arima-err.log &
        row ok 'Process' "PID $!   logs: arima.log"
        if wait_for_server 45; then
            row ok 'Server' "up at $URL"
            open_browser
            echo
            return 0
        fi
        row err 'Server' 'no response after 45s -- check arima.log / arima-err.log'
        return 1
    fi

    section 'RUNNING (FOREGROUND)'
    dim '  Press Ctrl+C to stop'
    dim "  $RULE"
    echo
    ( sleep 5 && server_up && open_browser ) &

    # Exit code 42 = restart requested by the UI (matches scripts/start.sh)
    while true; do
        java "${jvm_args[@]}"
        local code=$?
        if [ "$code" -eq 42 ]; then
            echo
            dim  "  $RULE"
            info '  Restarting Arima Notebooks...'
            dim  "  $RULE"
            sleep 1
            continue
        fi
        return "$code"
    done
}

cmd_stop() {
    local pid; pid=$(listening_pid)
    if [ -z "$pid" ]; then row warn 'Server' 'not running'; return 1; fi
    row ok 'Server' "stopping PID $pid"
    if kill_pid "$pid"; then
        sleep 1
        server_up && kill_pid "$pid"
        row ok 'Server' 'stopped'
        return 0
    fi
    row err 'Server' "failed to stop PID $pid"
    return 1
}

cmd_restart() {
    banner 'A R I M A   -   R E S T A R T'
    step 1 2 'Stopping'
    if server_up; then cmd_stop; sleep 1; else row ok 'Server' 'was not running'; fi
    step 2 2 'Starting'
    cmd_start
}

cmd_status() {
    banner 'A R I M A   -   S T A T U S'
    section 'SERVER'
    if server_up; then
        show_live_server
    else
        row warn 'State' 'STOPPED -- start it with: ./arima.sh start'
    fi
    if [ -f "$JAR" ]; then row ok 'JAR' "$JAR"
    else row warn 'JAR' 'not built yet -- run: ./arima.sh install'; fi

    section 'RUNTIMES'
    show_runtimes

    section 'AI'
    show_copilots

    if have git && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        section 'CHECKOUT'
        row ok 'Branch' "$(git rev-parse --abbrev-ref HEAD) @ $(git rev-parse --short HEAD)"
        local n; n=$(git status --porcelain --untracked-files=no | wc -l | tr -d ' ')
        if [ "$n" -gt 0 ]; then
            row warn 'Changes' "$n uncommitted file(s) -- commit before: ./arima.sh update"
        else
            row ok 'Changes' "clean -- './arima.sh update' is safe to run"
        fi
    fi
    echo
    return 0
}

cmd_open() {
    if server_up; then ok '  Opening Arima Notebooks...'; open_browser; return 0; fi
    err '  Arima Notebooks is not running. Start it first: ./arima.sh start'
    return 1
}

cmd_logs() {
    if [ ! -f arima.log ]; then
        err '  No log file found (arima.log).'
        dim '  Logs are only written in background mode: ./arima.sh start --bg'
        return 1
    fi
    info '  Tailing arima.log (Ctrl+C to stop)...'
    dim  "  $RULE"
    tail -n 40 -f arima.log
}

# ── Subcommands: build ──────────────────────────────────────────────────────
cmd_build() {
    banner 'A R I M A   -   B U I L D'
    have mvn || { err '  ERROR: Maven not found.'; dim '  Install from https://maven.apache.org/ or run: ./arima.sh install'; return 1; }
    step 1 1 'mvn clean package -DskipTests'
    echo
    if mvn clean package -DskipTests; then
        section 'BUILD SUCCESSFUL'
        row ok 'JAR' "$JAR"
        dim '  Run: ./arima.sh start'
        echo
        return 0
    fi
    section 'BUILD FAILED'
    err '  Check the Maven output above.'
    echo
    return 1
}

# ── MCP over the command line ───────────────────────────────────────────────
# POST /api/mcp/messages is a plain, stateless JSON-RPC 2.0 endpoint, so the CLI
# can call the very same tools an MCP client would -- no SSE session required.

json_escape() {
    # Minimal JSON string escaping for values we build by hand.
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
        | awk 'BEGIN{ORS=""} {print (NR>1 ? "\\n" : "") $0}'
}

mcp_post() {
    have curl || { err '  ERROR: curl is required for ./arima.sh mcp'; return 1; }
    curl -s -X POST "$MCP_URL" -H 'Content-Type: application/json' --data-binary "$1" -m 180
}

# Pretty-print a JSON-RPC response: tool text if present, else the raw JSON.
mcp_render() {
    local body="$1" py
    py=$(python_bin)
    if [ -z "$py" ]; then printf '%s\n' "$body"; return 0; fi
    printf '%s' "$body" | "$py" -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(sys.stdin.read()); raise SystemExit(0)
if d.get("error"):
    e = d["error"]
    print("  MCP error %s: %s" % (e.get("code"), e.get("message")))
    raise SystemExit(1)
r = d.get("result") or {}
if isinstance(r, dict) and "content" in r:
    for item in r["content"]:
        print(item.get("text") if item.get("type") == "text" else json.dumps(item, indent=2))
else:
    print(json.dumps(r, indent=2))
'
}

# Build a JSON object from key=value pairs (or pass a single JSON object).
mcp_args_json() {
    if [ "$#" -eq 0 ]; then echo '{}'; return; fi
    case "$1" in
        \{*) [ "$#" -eq 1 ] && { printf '%s' "$1"; return; } ;;
    esac
    local out='{' first=1 k v
    for pair in "$@"; do
        k="${pair%%=*}"; v="${pair#*=}"
        [ "$k" = "$pair" ] && { warn "  Ignoring '$pair' -- expected key=value"; continue; }
        # key=@path reads the value from a file -- the escape hatch for code that
        # the shell would otherwise mangle.
        case "$v" in
            @*) [ -f "${v#@}" ] && v="$(cat "${v#@}")" ;;
        esac
        [ "$first" = "0" ] && out="$out,"
        first=0
        case "$v" in
            true|false)     out="$out\"$k\":$v" ;;
            ''|*[!0-9-]*)   out="$out\"$k\":\"$(json_escape "$v")\"" ;;
            *)              out="$out\"$k\":$v" ;;
        esac
    done
    printf '%s}' "$out"
}

mcp_call_tool() {
    local tool="$1"; shift
    server_up || { mcp_down; return 1; }
    local body
    body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$tool\",\"arguments\":$(mcp_args_json "$@")}}"
    mcp_render "$(mcp_post "$body")"
}

mcp_down() {
    err '  Arima Notebooks is not running -- MCP needs the server.'
    dim '  Start it first:  ./arima.sh start --bg'
}

mcp_overview() {
    banner 'A R I M A   -   M C P' \
           '------------------------------------------' \
           'Model Context Protocol server (JSON-RPC 2.0)' \
           'Call the same tools any MCP client would.' \
           "Server: $URL"
    section 'ENDPOINTS'
    row ok 'SSE'      "$URL/api/mcp/sse"
    row ok 'Messages' "$MCP_URL"
    if server_up; then
        row ok 'Server' 'reachable'
    else
        row warn 'Server' 'STOPPED -- start it with: ./arima.sh start --bg'
    fi

    section 'COMMANDS'
    echo '    ./arima.sh mcp info                    Server name, version, protocol'
    echo '    ./arima.sh mcp ping                    Health check'
    echo '    ./arima.sh mcp tools                   List every MCP tool + its parameters'
    echo '    ./arima.sh mcp call <tool> k=v ...     Call any tool by name'
    echo '    ./arima.sh mcp exec "<code>"           Run Java/JShell code'
    echo '    ./arima.sh mcp notebooks               List notebooks'
    echo '    ./arima.sh mcp read <notebookId>       Read every cell of a notebook'
    echo '    ./arima.sh mcp search <query>          Search cells by anchor or source'
    echo '    ./arima.sh mcp agents                  List agents & skills'
    echo '    ./arima.sh mcp run-agent <id> <task>   Run an agent against a task'
    echo "    ./arima.sh mcp raw '<json-rpc>'        Send a raw JSON-RPC envelope"
    echo '    ./arima.sh mcp config                  Print an MCP client config snippet'

    section 'EXAMPLES'
    dim '    ./arima.sh mcp exec "System.out.println(2+2);"'
    dim '    ./arima.sh mcp call barista_search_cells query=tablesaw'
    dim '    ./arima.sh mcp call barista_append_cell notebookId=my-nb source="var x=1;" execute=true'
    echo
    return 0
}

cmd_mcp() {
    [ "$#" -eq 0 ] && { mcp_overview; return 0; }
    local sub="$1"; shift
    case "$sub" in
        info)
            server_up || { mcp_down; return 1; }
            mcp_render "$(mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')" ;;
        ping)
            server_up || { mcp_down; return 1; }
            mcp_post '{"jsonrpc":"2.0","id":1,"method":"ping","params":{}}' >/dev/null || return 1
            ok "  MCP is alive at $MCP_URL" ;;
        tools)
            server_up || { mcp_down; return 1; }
            local body py
            body=$(mcp_post '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')
            py=$(python_bin)
            section 'MCP TOOLS'
            if [ -z "$py" ]; then printf '%s\n' "$body"; return 0; fi
            printf '%s' "$body" | "$py" -c '
import json, sys
d = json.load(sys.stdin)
for t in d.get("result", {}).get("tools", []):
    print()
    print("    " + t["name"])
    print("      " + t.get("description", ""))
    sch = t.get("inputSchema") or {}
    req = sch.get("required", [])
    for name, spec in (sch.get("properties") or {}).items():
        mark = "*" if name in req else " "
        print("      %s %-12s %s" % (mark, name, spec.get("description", "")))
'
            echo
            dim '    * = required.  Call one with:  ./arima.sh mcp call <tool> key=value ...'
            echo ;;
        call)
            [ "$#" -eq 0 ] && { err '  Usage: ./arima.sh mcp call <tool> key=value ...'; return 1; }
            local tool="$1"; shift
            mcp_call_tool "$tool" "$@" ;;
        exec)
            [ "$#" -eq 0 ] && { err '  Usage: ./arima.sh mcp exec "<java code>"   or   ./arima.sh mcp exec @file.java'; return 1; }
            # `exec @file` runs the file's contents -- use this when the code
            # contains quotes the shell would eat.
            if [ "$#" -eq 1 ] && [ "${1#@}" != "$1" ]; then
                [ -f "${1#@}" ] || { err "  File not found: ${1#@}"; return 1; }
                mcp_call_tool barista_execute_code "code=$1"
            else
                mcp_call_tool barista_execute_code "code=$*"
            fi ;;
        notebooks) mcp_call_tool barista_list_notebooks ;;
        agents)    mcp_call_tool barista_list_agents ;;
        read)
            [ "$#" -eq 0 ] && { err '  Usage: ./arima.sh mcp read <notebookId>'; return 1; }
            mcp_call_tool barista_read_notebook "notebookId=$1" ;;
        search)
            [ "$#" -eq 0 ] && { err '  Usage: ./arima.sh mcp search <query>'; return 1; }
            mcp_call_tool barista_search_cells "query=$*" ;;
        run-agent)
            [ "$#" -lt 2 ] && { err '  Usage: ./arima.sh mcp run-agent <agentId> <task>'; return 1; }
            local agent="$1"; shift
            mcp_call_tool barista_run_agent "agentId=$agent" "task=$*" ;;
        raw)
            server_up || { mcp_down; return 1; }
            [ "$#" -eq 0 ] && { err '  Usage: ./arima.sh mcp raw '"'"'{"jsonrpc":"2.0","id":1,"method":"tools/list"}'"'"''; return 1; }
            mcp_post "$*" ; echo ;;
        config)
            section 'MCP CLIENT CONFIGURATION'
            dim '    Add this to your MCP client (Claude Desktop, Claude Code, custom agents):'
            echo
            cat <<EOF
    {
      "mcpServers": {
        "arima-notebooks": {
          "url": "$URL/api/mcp/sse"
        }
      }
    }
EOF
            echo
            dim '    Claude Code, one-liner:'
            dim  "        claude mcp add --transport sse arima-notebooks $URL/api/mcp/sse"
            echo
            dim  '    Arima must be running for the client to connect:  ./arima.sh start --bg'
            echo ;;
        *)
            err "  Unknown mcp subcommand: $sub"
            dim '  Run: ./arima.sh mcp'
            return 1 ;;
    esac
}

# ── Subcommands: info ───────────────────────────────────────────────────────

# ── Live server metadata (GET /api/system/info) ─────────────────────────────

# Emits "state|Label|Value" lines. Non-zero when the server cannot be queried,
# so callers can fall back to a port/PID summary.
server_info_rows() {
    have curl || return 1
    local py body
    py=$(python_bin)
    [ -n "$py" ] || return 1
    body=$(curl -s -m 3 "$URL/api/system/info" 2>/dev/null) || return 1
    [ -n "$body" ] || return 1
    printf '%s' "$body" | "$py" -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    raise SystemExit(1)
if d.get("status") != "running":
    raise SystemExit(1)
def row(state, label, value):
    print("%s|%s|%s" % (state, label, value))
row("ok", "Status",  "RUNNING at %s" % d.get("url", ""))
row("ok", "Version", "%s   (built %s)" % (d.get("version", "?"), d.get("buildTimestamp", "?")))
row("ok", "Started", "%s   (up %s)" % (d.get("startedAt", "?"), d.get("uptime", "?")))
row("ok", "Process", "PID %s   port %s   auth %s"
        % (d.get("pid", "?"), d.get("port", "?"), d.get("authMode", "?")))
j = d.get("java") or {}
row("ok", "Java",    "%s  --  %s" % (j.get("version", "?"), j.get("vm", "?")))
o = d.get("os") or {}
row("ok", "OS",      "%s %s (%s)  --  %s CPUs"
        % (o.get("name", "?"), o.get("version", ""), o.get("arch", ""), o.get("cpus", "?")))
m = d.get("memory") or {}
row("ok", "Memory",  "%s MB used  /  %s MB heap  /  %s MB max"
        % (m.get("usedMb", "?"), m.get("totalMb", "?"), m.get("maxMb", "?")))
s = d.get("sessions") or {}
row("ok", "Sessions", "%s active JShell session(s)" % s.get("active", 0))
n = d.get("notebooks") or {}
row("ok", "Notebooks", "%s total  --  %s tutorials  (%s/)"
        % (n.get("total", 0), n.get("tutorials", 0), n.get("dir", "notebooks")))
mc = d.get("mcp") or {}
row("ok" if mc.get("enabled") else "warn", "MCP",
    "%s  --  protocol %s  --  %s"
    % ("enabled" if mc.get("enabled") else "disabled",
       mc.get("protocol", "?"), mc.get("messages", "")))
langs = d.get("languages") or []
up   = [l.get("name", "?") for l in langs if l.get("available")]
down = [l for l in langs if not l.get("available")]
row("ok" if not down else "warn", "Languages",
    "%d/%d ready  --  %s" % (len(up), len(langs), ", ".join(up)))
if down:
    row("warn", "Disabled",
        ", ".join("%s (%s)" % (l.get("name", "?"), l.get("detail", "")) for l in down))
'
}

show_live_server() {
    local rows
    rows=$(server_info_rows)
    if [ -n "$rows" ]; then
        printf '%s\n' "$rows" | while IFS='|' read -r st label value; do
            [ -n "$label" ] && row "$st" "$label" "$value"
        done
        return 0
    fi
    # The port answers but /api/system/info did not: an older build, or oauth
    # mode where /api/** requires a signed-in session.
    row ok 'Status' "RUNNING at $URL"
    local pid; pid=$(listening_pid)
    [ -n "$pid" ] && row ok 'PID' "$pid"
    row warn 'Details' 'live metadata unavailable -- rebuild, or sign in if auth mode is oauth'
}

# ── Home screen (bare invocation) ───────────────────────────────────────────

show_commands() {
    section 'ALL COMMANDS'
    printf '%s    Lifecycle  %s%sinstall   update   uninstall%s\n'                      "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '%s    Server     %s%sstart   stop   restart   status   open   logs%s\n'     "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '%s    MCP        %s%smcp   mcp tools   mcp call   mcp exec   mcp config%s\n' "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '%s    Build      %s%sbuild   rebuild%s\n'                                    "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '%s    Info       %s%sversion   welcome   docs   agents   brew   help%s\n'    "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '%s    Flags      %s%s--bg  --yes  --purge  --no-build  --path  --skip-optional  --no-anim%s\n' "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    echo
    dim  "    ./arima.sh help    full description of every command and flag"
}

# Ask a yes/no question that defaults to yes. Never blocks a non-interactive run.
ask_yes() {
    [ "${F_YES:-0}" = "1" ] && { dim "  (--yes) $1 -> yes"; return 0; }
    [ -t 0 ] || return 1
    echo
    printf '%s  %s %s[Y/n]: %s' "$C_YELLOW" "$1" "$C_DIM" "$C_RESET"
    local answer; read -r answer
    answer=$(printf '%s' "$answer" | tr 'A-Z' 'a-z')
    [ -z "$answer" ] || [ "$answer" = "y" ] || [ "$answer" = "yes" ]
}

# First-run experience: nothing is built yet, so explain what Arima is and
# offer the one command that gets the user to a running server.
show_fre() {
    section 'FIRST RUN'
    dim '    Arima Notebooks is not built on this machine yet.'
    echo
    printf '%s    What it is%s\n' "$C_WHITE" "$C_RESET"
    dim '      A notebook you run on your own machine. Write cells in Java/JShell,'
    dim '      JavaScript, TypeScript, C#, F#, C++ or Python; chain them into'
    dim '      pipelines with //@ anchor and //@ depends; and drive the whole thing'
    dim '      from any MCP client or an AI CLI.'
    dim '      Local-first: no account, no telemetry, nothing leaves your machine.'

    section 'READINESS'
    show_runtimes
    row err 'Build' "$JAR not built yet"

    section 'NEXT'
    dim '    One command checks every dependency, installs what is missing, and builds:'
    echo
    printf '%s        ./arima.sh install%s\n' "$C_WHITE" "$C_RESET"
    dim  '        ./arima.sh start        then start the server and open the UI'
    echo

    if ask_yes 'Install everything that is missing and start Arima now?'; then
        echo
        cmd_install || return 1
        echo
        cmd_start
        return $?
    fi
    echo
    # Only report a decline when there was actually a prompt to decline.
    [ -t 0 ] && dim '    Nothing installed. Run  ./arima.sh install  when you are ready.'
    echo
    return 0
}

# Bare `./arima.sh` with no subcommand.
cmd_home() {
    banner
    printf '%s  %s%s\n' "$C_CYAN" "$TAGLINE" "$C_RESET"

    if server_up; then
        section 'LIVE SERVER'
        show_live_server
        show_commands
        echo
        info "  Open it: $URL"
        echo
        return 0
    fi

    if [ ! -f "$JAR" ]; then
        show_fre
        return $?
    fi

    section 'SERVER'
    row warn 'State' 'STOPPED'
    row ok   'Build' "$JAR"
    show_commands
    section 'NEXT'
    printf '%s    ./arima.sh start%s%s        start the server and open %s%s\n' \
        "$C_WHITE" "$C_RESET" "$C_DIM" "$URL" "$C_RESET"
    dim  '    ./arima.sh start --bg   start it detached, logging to arima.log'
    echo
    return 0
}

cmd_version() {
    banner 'A R I M A   -   V E R S I O N'
    section 'VERSIONS'
    # Read the version off the JAR name -- that is the artifact we launch.
    row ok 'Arima' "$(basename "$JAR" .jar | sed 's/^arima-notebooks-//')"
    show_runtimes
    echo
}

cmd_welcome() {
    set_ai_context
    show_brew
    banner
    title '  Welcome to Arima Notebooks'
    dim   '  A local notebook for Java, JShell, JS, TS, C#, F#, C++ and Python'
    dim   '  -- with AI co-pilots and a built-in MCP server.'

    section 'PICK HOW YOU WANT TO WORK'
    printf '    %s1) Open the UI            %s%sfull notebook experience in your browser%s\n' "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    dim   "         ./arima.sh start        ->  $URL"
    printf '    %s2) Drive Arima over MCP   %s%soperate & automate from any MCP client%s\n' "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    dim   '         ./arima.sh mcp tools    list the tools right here in the terminal'
    dim   "         SSE  $URL/api/mcp/sse"
    dim   "         POST $MCP_URL"
    printf '    %s3) Personalize & extend   %s%sadd features -- needs an agentic CLI%s\n' "$C_WHITE" "$C_RESET" "$C_DIM" "$C_RESET"
    dim   '         run  claude  /  copilot  /  agy   in this folder, then ask the arima agent'

    section 'AI CO-PILOTS'
    show_copilots

    section 'THE ONE DIFFERENCE'
    dim   '    This arima CLI operates & automates Arima (incl. MCP) but cannot change its code.'
    dim   '    An agentic CLI (claude / copilot / agy) can ALSO personalize and extend Arima.'

    section 'NEXT'
    echo  '    ./arima.sh install    Check prerequisites, install what is missing, build'
    echo  '    ./arima.sh start      Start the server and open the UI'
    echo  '    ./arima.sh docs       Open the brochure and list the docs'
    echo  '    ./arima.sh agents     AI co-pilots, skills & the arima agent'
    echo
    dim   '  Full welcome: docs/WELCOME.md'
    echo
}

cmd_docs() {
    banner 'A R I M A   -   D O C S'
    section 'DOCUMENTATION'
    echo  '    Brochure (PDF)   docs/brochure/arima-brochure.pdf'
    echo  '    Welcome          docs/WELCOME.md'
    echo  '    Getting started  README.md'
    echo  '    Architecture     docs/ARCHITECTURE.md'
    echo  '    API + MCP        docs/API.md'
    echo  '    Contributor      CONTRIBUTING.md  +  AGENTS.md'
    echo  '    Cheat sheet      docs/cheatsheet.html'
    echo
    server_up && dim "    In the running app: open the in-UI docs overlay at $URL"
    local pdf="$SCRIPT_DIR/docs/brochure/arima-brochure.pdf"
    if [ -f "$pdf" ]; then
        ok '    Opening the brochure...'
        if   have xdg-open; then xdg-open "$pdf" >/dev/null 2>&1 &
        elif have open;     then open "$pdf"     >/dev/null 2>&1 &
        else dim "    Open manually: $pdf"; fi
    else
        warn '    Brochure PDF not found -- open docs/brochure/arima-brochure.html in a browser.'
    fi
    echo
}

cmd_agents() {
    set_ai_context
    banner 'A R I M A   -   A G E N T S' \
           '------------------------------------------' \
           'AI co-pilots, guardrails, skills and subagents' \
           'wired into this repository.' \
           ''

    section 'DETECTED AI CLIs'
    local found; found=$(detect_copilots)
    if [ -n "$found" ]; then
        while IFS='=' read -r name bin; do
            [ -n "$name" ] && row ok "$name" "binary: $bin"
        done <<< "$found"
    else
        row warn 'none' 'install one of the following:'
        dim  '      Claude     :  https://claude.ai/code                      then  claude auth'
        dim  '      Copilot    :  GitHub Copilot CLI (copilot, v1.0.55-5+; used by the Copilot SDK)'
        dim  '      Antigravity:  https://antigravity.google/docs/cli-install  then  run  agy'
    fi

    section 'GUARDRAILS (read automatically by every AI CLI in this repo)'
    for pair in "AGENTS.md|$AGENTS_GUIDE" ".claude/skills|$SKILLS_DIR" ".claude/agents|$AGENTS_DIR" \
                "CLAUDE.md|$SCRIPT_DIR/CLAUDE.md" ".github/copilot-instructions.md|$SCRIPT_DIR/.github/copilot-instructions.md" \
                "GEMINI.md|$SCRIPT_DIR/GEMINI.md"; do
        label="${pair%%|*}"; path="${pair#*|}"
        if [ -e "$path" ]; then row ok "$label" ''; else row warn "$label" 'missing'; fi
    done

    section 'SKILLS (auto-invoke when your request matches)'
    [ -d "$SKILLS_DIR" ] && for d in "$SKILLS_DIR"/*/; do [ -d "$d" ] && dim "      $(basename "$d")"; done

    section 'SUBAGENTS (spawn explicitly for a focused review)'
    [ -d "$AGENTS_DIR" ] && for f in "$AGENTS_DIR"/*.md; do [ -f "$f" ] && dim "      $(basename "$f" .md)"; done

    section 'HOW TO USE'
    echo "    In the Arima UI : open the AI panel (Ctrl+\\), pick a provider, ask away."
    echo "    In a terminal   : run your CLI from this folder so it loads AGENTS.md:"
    dim  "                        claude        (or)   copilot        (or)   agy"
    echo '    Example prompt  : "Add a Kotlin execution mode following CppExecutionService,'
    dim  '                       then run ./scripts/security-check.sh and open a PR."'
    echo
    dim  '    Env exported for this session:'
    dim  "      BARISTA_HOME=${BARISTA_HOME:-}"
    dim  "      BARISTA_AGENTS_GUIDE=${BARISTA_AGENTS_GUIDE:-}"
    dim  "      BARISTA_SKILLS_DIR=${BARISTA_SKILLS_DIR:-}"
    dim  "      BARISTA_AGENTS_DIR=${BARISTA_AGENTS_DIR:-}"
    echo
}

cmd_help() {
    banner 'A R I M A   N O T E B O O K S   C L I' \
           '------------------------------------------' \
           'Java  JShell  JS  TS  C#  F#  C++  Python' \
           'Usage:  ./arima.sh [command] [flags]' \
           "Server: $URL"

    section 'START HERE'
    echo  '    (no command)     Home screen -- live server metadata when it is running,'
    dim   '                     the full command list, or first-run setup if not built yet'

    section 'LIFECYCLE'
    echo '    install          Check every dependency, install what is missing, build, report readiness'
    echo '    update           Sync with upstream (fast-forward or rebase), then rebuild'
    echo '    uninstall        Remove build output and logs (notebooks are kept)'

    section 'SERVER'
    echo '    start            Start the server, auto-build if needed, open the browser'
    echo '    start --bg       Start detached; logs to arima.log'
    echo '    stop             Stop the running server'
    echo '    restart          Stop then start (use after `update`)'
    echo '    status           Server state, PID, runtimes, AI CLIs, checkout state'
    echo '    open             Open the browser (server must already be running)'
    echo '    logs             Tail arima.log (background mode only)'

    section 'MCP (drive Arima from this terminal)'
    echo '    mcp              Endpoints, live server info, and the command list'
    echo '    mcp tools        List every MCP tool and its parameters'
    echo '    mcp call <tool> k=v ...   Call any tool by name'
    echo '    mcp exec "<code>"         Run Java/JShell code through MCP'
    echo '    mcp notebooks | read <id> | search <q> | agents | run-agent <id> <task>'
    echo '    mcp config       Print an MCP client config snippet'

    section 'BUILD'
    echo '    build            mvn clean package -DskipTests'
    echo '    rebuild          Alias of build (always a clean build)'

    section 'INFO'
    echo '    version          Arima version plus every detected runtime'
    echo '    welcome          Pick how you want to work: UI, MCP, or extend'
    echo '    docs             Open the brochure and list the documentation'
    echo '    agents           AI co-pilots, guardrails, skills & subagents'
    echo '    brew             Watch Barista serve a coffee bean (alias: coffee)'
    echo '    help             Show this help'

    section 'FLAGS'
    echo '    --bg             start detached, logging to arima.log'
    echo '    --yes            skip confirmation prompts (install / uninstall)'
    echo '    --purge          uninstall: also delete data/ (settings, packages, users)'
    echo '    --no-build       install/update: skip the Maven build'
    echo '    --path           install/uninstall: show the PATH export line'
    echo '    --skip-optional  install: only install the required tools (Java + Maven)'
    echo '    --no-anim        disable the animated banner and spinners'

    section 'EXAMPLES'
    dim '    ./arima.sh                          home screen: live status + every command'
    dim '    ./arima.sh install --yes            install every missing dependency, no prompts'
    dim '    ./arima.sh update                   pull upstream, rebase local commits, rebuild'
    dim '    ./arima.sh restart                  stop, then start again'
    dim '    ./arima.sh mcp exec "2+2"           run a snippet through the MCP server'
    dim '    ./arima.sh uninstall --purge --yes  full clean, no prompts'
    echo
    info "  URL: $URL"
    echo
}

# ── Dispatch ────────────────────────────────────────────────────────────────
CMD="${1:-home}"
shift || true

F_BG=0; F_YES=0; F_PURGE=0; F_NOBUILD=0; F_PATH=0; F_SKIPOPT=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --bg|-b)        F_BG=1 ;;
        --yes|-y)       F_YES=1 ;;
        --purge)        F_PURGE=1 ;;
        --no-build)     F_NOBUILD=1 ;;
        --path)         F_PATH=1 ;;
        --skip-optional) F_SKIPOPT=1 ;;
        --no-anim)      ANIM=0 ;;
        *)              ARGS+=("$arg") ;;
    esac
done
export F_BG F_YES F_PURGE F_NOBUILD F_PATH F_SKIPOPT

case "$(printf '%s' "$CMD" | tr 'A-Z' 'a-z')" in
    home)               cmd_home ;;
    install)            cmd_install ;;
    update|upgrade)     cmd_update ;;
    uninstall)          cmd_uninstall ;;
    start)              cmd_start ;;
    stop)               banner 'A R I M A   -   S T O P'; cmd_stop ;;
    restart)            cmd_restart ;;
    status)             cmd_status ;;
    open)               cmd_open ;;
    logs)               cmd_logs ;;
    build|rebuild)      cmd_build ;;
    brew|coffee)        show_brew; banner ;;
    mcp)                cmd_mcp ${ARGS+"${ARGS[@]}"} ;;
    version|--version)  cmd_version ;;
    agents|ai)          cmd_agents ;;
    welcome)            cmd_welcome ;;
    docs)               cmd_docs ;;
    help|-h|--help)     cmd_help ;;
    *)
        err "Unknown command: $CMD"
        dim 'Run: ./arima.sh help'
        exit 1 ;;
esac
exit $?
