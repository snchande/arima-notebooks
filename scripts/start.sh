#!/bin/bash
# Arima Notebooks — Unix/Mac startup script
# Exit code 42 from the JVM means "restart requested" — loop handles that automatically.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_DIR/target/arima-notebooks-4.0.1.jar"

cd "$PROJECT_DIR"

echo "======================================="
echo "  Arima Notebooks v1.0"
echo "  Java | JavaScript | C# | F# | JShell"
echo "======================================="
echo ""

# ── Dependency checks ──────────────────────────────────────────────────────────
if ! command -v java &>/dev/null; then
    echo "ERROR: Java not found. Please install JDK 17+."
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | grep -oP '(?<=version ")[0-9]+' | head -1)
if [ -n "$JAVA_VER" ] && [ "$JAVA_VER" -lt "17" ] 2>/dev/null; then
    echo "ERROR: Java $JAVA_VER found, but JDK 17+ is required."
    exit 1
fi
echo "Java:    $(java -version 2>&1 | head -1)"

if command -v node &>/dev/null; then
    echo "Node.js: $(node --version)  (JavaScript cells enabled)"
else
    echo "Node.js: not found  (JS cells disabled — install from nodejs.org)"
fi

if command -v dotnet &>/dev/null; then
    echo ".NET:    $(dotnet --version)  (C# and F# cells enabled)"
else
    echo ".NET:    not found  (C#/F# cells disabled — install from https://dot.net)"
fi

# ── AI co-pilot context ─────────────────────────────────────────────────────
# Export the guardrail + skill + agent paths so the Arima JVM (and any CLI it
# spawns for the in-UI AI panel) resolves them regardless of launch dir.
export BARISTA_HOME="$PROJECT_DIR"
[ -f "$PROJECT_DIR/AGENTS.md" ]        && export BARISTA_AGENTS_GUIDE="$PROJECT_DIR/AGENTS.md"
[ -d "$PROJECT_DIR/.claude/skills" ]   && export BARISTA_SKILLS_DIR="$PROJECT_DIR/.claude/skills"
[ -d "$PROJECT_DIR/.claude/agents" ]   && export BARISTA_AGENTS_DIR="$PROJECT_DIR/.claude/agents"
AI_FOUND=""
for c in claude copilot github-copilot-cli agy gemini; do
    command -v "$c" &>/dev/null && AI_FOUND="$AI_FOUND $c"
done
if [ -n "$AI_FOUND" ]; then
    echo "AI:     $AI_FOUND  (co-pilot ready — AGENTS.md + .claude skills/agents loaded; run ./arima.sh agents)"
else
    echo "AI:     no CLI found  (install Claude / Copilot / Antigravity for AI features)"
fi
echo ""

# ── Build if JAR is missing ────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    if ! command -v mvn &>/dev/null; then
        echo "ERROR: Maven not found. Please install Maven 3.8+."
        exit 1
    fi
    echo "Building Arima Notebooks..."
    mvn clean package -DskipTests -q
fi

echo "Starting Arima Notebooks..."
echo "Open http://localhost:8585 in your browser"
echo ""
echo "Press Ctrl+C to stop   |   Use Restart in the UI to apply code changes"
echo "---------------------------------------"

# ── Watchdog loop ──────────────────────────────────────────────────────────────
# Exit code 42 = restart requested (from UI Restart button).
# Any other exit code breaks the loop and terminates normally.
RESTART_CODE=42

while true; do
    java \
        --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \
        --add-opens=java.base/java.lang=ALL-UNNAMED \
        --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \
        -jar "$JAR" "$@"

    EXIT_CODE=$?

    if [ "$EXIT_CODE" -eq "$RESTART_CODE" ]; then
        echo ""
        echo "---------------------------------------"
        echo "  Restarting Arima Notebooks..."
        echo "---------------------------------------"
        sleep 1
        # Re-run any pending Maven build if sources changed
        if [ "$BARISTA_AUTO_BUILD" = "1" ] && command -v mvn &>/dev/null; then
            echo "  Auto-building before restart..."
            mvn package -DskipTests -q
        fi
        continue
    fi

    # Normal exit (0) or error — stop looping
    if [ "$EXIT_CODE" -ne 0 ]; then
        echo ""
        echo "Arima Notebooks exited with code $EXIT_CODE."
    fi
    break
done
