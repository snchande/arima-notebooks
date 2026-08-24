#Requires -Version 5.1
<#
.SYNOPSIS
  Arima Notebooks PowerShell CLI.

.DESCRIPTION
  Cross-platform PowerShell launcher for Arima Notebooks. Mirrors arima.cmd
  (Windows CMD) and arima.sh (Linux/macOS). Subcommands:
    start [-Bg]   stop   status   build   rebuild   open   logs   version   help

.EXAMPLE
  ./arima.ps1                     # same as: ./arima.ps1 start
  ./arima.ps1 start -Bg           # background, writes arima.log
  ./arima.ps1 stop
  ./arima.ps1 status
  ./arima.ps1 help
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Command = 'start',

    [switch] $Bg
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Resolve repo root (directory holding this script)
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $RepoRoot

$JarPath = Join-Path 'target' 'arima-notebooks-1.0.0-SNAPSHOT.jar'
$Port    = 8585
$Url     = "http://localhost:$Port"

# ── AI co-pilot context ───────────────────────────────────────────────────────
# These files turn any AI CLI invoked inside this repo (the in-UI AI panel or a
# terminal session) into a Arima-aware co-pilot that follows the architecture
# guardrails and can use the registered skills + subagents.
$AgentsGuide = Join-Path $RepoRoot 'AGENTS.md'
$SkillsDir   = Join-Path $RepoRoot (Join-Path '.claude' 'skills')
$AgentsDir   = Join-Path $RepoRoot (Join-Path '.claude' 'agents')

# ── Colour helpers ──────────────────────────────────────────────────────────
function W-Title  ($t) { Write-Host $t -ForegroundColor White }
function W-Info   ($t) { Write-Host $t -ForegroundColor Cyan }
function W-Ok     ($t) { Write-Host $t -ForegroundColor Green }
function W-Warn   ($t) { Write-Host $t -ForegroundColor Yellow }
function W-Err    ($t) { Write-Host $t -ForegroundColor Red }
function W-Dim    ($t) { Write-Host $t -ForegroundColor DarkGray }

function Show-Banner {
    Write-Host ''
    Write-Host '        .         ' -ForegroundColor Yellow -NoNewline; W-Title  '  A R I M A   N O T E B O O K S'
    Write-Host '       /|\        ' -ForegroundColor Yellow -NoNewline; W-Dim    '  -----------------------------'
    Write-Host '      ( @ )       ' -ForegroundColor Yellow -NoNewline; W-Info   '  Java | JS | TS | C# | F# | C++ | Python'
    Write-Host '     /|\_/|\      ' -ForegroundColor Yellow -NoNewline; W-Dim    '  Brewed by Barista · JShell + Spring Boot'
    Write-Host '    / |   | \     ' -ForegroundColor Yellow -NoNewline; W-Dim    "  Port: $Port"
    Write-Host '   /__|   |__\    ' -ForegroundColor Yellow
    W-Dim '  -----------------------------'
    Write-Host ''
}

# ── AI co-pilot wiring ────────────────────────────────────────────────────────
# Detect installed AI CLIs. Each may be invoked under a couple of binary names.
function Get-AiCopilots {
    $copilots = [ordered]@{}
    $probes = [ordered]@{
        'Claude'      = @('claude')
        'Copilot'     = @('copilot', 'github-copilot-cli', 'gh')
        'Antigravity' = @('agy', 'gemini')
    }
    foreach ($name in $probes.Keys) {
        foreach ($bin in $probes[$name]) {
            $cmd = Get-Command $bin -ErrorAction SilentlyContinue
            if ($cmd) { $copilots[$name] = $bin; break }
        }
    }
    return $copilots
}

# Export the context so the Arima JVM (and any CLI it spawns for the in-UI AI
# panel) resolves the guardrails + skills + agents regardless of where the
# process was launched from.
function Set-BaristaAiContext {
    $env:BARISTA_HOME = $RepoRoot
    if (Test-Path $AgentsGuide) { $env:BARISTA_AGENTS_GUIDE = $AgentsGuide }
    if (Test-Path $SkillsDir)   { $env:BARISTA_SKILLS_DIR   = $SkillsDir }
    if (Test-Path $AgentsDir)   { $env:BARISTA_AGENTS_DIR   = $AgentsDir }
    $copilots = Get-AiCopilots
    if ($copilots.Count -gt 0) {
        $env:BARISTA_AI_COPILOTS = ($copilots.Keys -join ',').ToLower()
    }
    return $copilots
}

function Show-AiCopilots {
    param([hashtable] $Copilots)
    if ($null -eq $Copilots) { $Copilots = Get-AiCopilots }
    $guardOk = (Test-Path $AgentsGuide) -and (Test-Path $SkillsDir) -and (Test-Path $AgentsDir)
    if ($Copilots.Count -gt 0) {
        $list = ($Copilots.Keys -join ' · ')
        W-Dim  "  AI:      $list  (co-pilot ready)"
    } else {
        W-Warn '  AI:      no CLI found  (install Claude, Copilot, or Antigravity for AI features)'
    }
    if ($guardOk) {
        W-Dim  '           guardrails AGENTS.md + skills/ + agents/ loaded -> run `./arima.ps1 agents`'
    } else {
        W-Warn '           AGENTS.md / .claude skills+agents missing -- AI guardrails not wired'
    }
}

# ── Probes ──────────────────────────────────────────────────────────────────
function Test-Java {
    try {
        $null = Get-Command java -ErrorAction Stop
        return $true
    } catch {
        W-Err  '  ERROR: Java not found.'
        W-Dim  '  Install JDK 17+ (21 recommended) from https://adoptium.net/'
        return $false
    }
}

function Test-Maven {
    try {
        $null = Get-Command mvn -ErrorAction Stop
        return $true
    } catch {
        W-Err  '  ERROR: Maven not found.'
        W-Dim  '  Install from https://maven.apache.org/'
        return $false
    }
}

function Test-ServerUp {
    try {
        $r = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

function Get-ListeningPid {
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop |
                Select-Object -First 1
        return $conn.OwningProcess
    } catch {
        return $null
    }
}

function Ensure-Jar {
    if (-not (Test-Path $JarPath)) {
        W-Warn '  JAR not found -- building first...'
        if (-not (Test-Maven)) { return $false }
        mvn clean package -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            W-Err '  Build failed.'
            return $false
        }
        W-Ok  '  Build complete.'
    }
    return $true
}

function Wait-Server {
    param([int] $TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-ServerUp) { return $true }
        Start-Sleep -Milliseconds 1500
    }
    return $false
}

# ── Subcommands ─────────────────────────────────────────────────────────────
function Cmd-Start {
    Show-Banner

    if (Test-ServerUp) {
        W-Ok   '  Arima Notebooks is already running.'
        W-Info "  URL: $Url"
        $ans = Read-Host '  Open in browser? [Y/n]'
        if ($ans -notmatch '^[nN]') { Start-Process $Url }
        return 0
    }

    if (-not (Test-Java)) { return 1 }
    $jv = (& java -version 2>&1 | Select-Object -First 1)
    W-Dim "  Java:    $jv"

    if (Get-Command node -ErrorAction SilentlyContinue) {
        W-Dim "  Node.js: $(node --version)  (JavaScript/TypeScript cells enabled)"
    } else {
        W-Warn '  Node.js: not found  (JS/TS cells disabled -- install from nodejs.org)'
    }

    if (Get-Command dotnet -ErrorAction SilentlyContinue) {
        W-Dim  "  .NET:    $(dotnet --version)  (C# and F# cells enabled)"
    } else {
        W-Warn '  .NET:    not found  (C#/F# cells disabled -- install from https://dot.net)'
    }

    $pyCmd = if (Get-Command python -ErrorAction SilentlyContinue) { 'python' }
             elseif (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' }
             elseif (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { $null }
    if ($pyCmd) {
        W-Dim  "  Python:  $(& $pyCmd --version 2>&1)  (Python cells + PyPI enabled)"
    } else {
        W-Warn '  Python:  not found  (Python cells disabled -- install from https://www.python.org/downloads/)'
    }

    # Wire the AI co-pilot context before launching the JVM so the in-UI AI
    # panel (and any CLI it spawns) inherits the guardrails + skills + agents.
    $copilots = Set-BaristaAiContext
    Show-AiCopilots -Copilots $copilots

    if (-not (Ensure-Jar)) { return 1 }

    $jvmArgs = @(
        '--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED',
        '-jar', $JarPath
    )

    if ($Bg) {
        W-Info '  Starting Arima Notebooks in background...'
        $logPath = Join-Path $RepoRoot 'arima.log'
        $errPath = Join-Path $RepoRoot 'arima-err.log'
        $proc = Start-Process -FilePath java -ArgumentList $jvmArgs `
                -RedirectStandardOutput $logPath -RedirectStandardError $errPath `
                -WindowStyle Hidden -PassThru
        W-Ok   "  Started. PID: $($proc.Id)   Logs: arima.log"
        W-Dim  '  Waiting for server...'
        if (Wait-Server -TimeoutSec 30) {
            W-Ok  '  Ready! Opening browser...'
            Start-Process $Url
            return 0
        } else {
            W-Err '  Server did not respond after 30s -- check arima.log / arima-err.log'
            return 1
        }
    } else {
        W-Ok  '  Launching Arima Notebooks...'
        W-Dim '  Press Ctrl+C to stop'
        W-Dim '  --------------------------------------'
        Write-Host ''
        # Open browser after a short delay so the server has time to bind
        Start-Job -ScriptBlock {
            param($url)
            Start-Sleep -Seconds 5
            Start-Process $url
        } -ArgumentList $Url | Out-Null

        & java @jvmArgs
        return $LASTEXITCODE
    }
}

function Cmd-Stop {
    Write-Host ''
    W-Warn '  Stopping Arima Notebooks...'
    $procId = Get-ListeningPid
    if (-not $procId) {
        W-Err '  Arima Notebooks is not running.'
        return 1
    }
    W-Dim  "  Killing PID $procId"
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        W-Ok '  Stopped.'
        return 0
    } catch {
        W-Err "  Failed to stop PID $procId : $_"
        return 1
    }
}

function Cmd-Status {
    Write-Host ''
    if (Test-ServerUp) {
        W-Ok  "  [RUNNING] Arima Notebooks is up at $Url"
        $procId = Get-ListeningPid
        if ($procId) { W-Dim "  PID: $procId" }
    } else {
        W-Err '  [STOPPED] Arima Notebooks is not running.'
    }
    Write-Host ''

    if (Test-Path $JarPath) {
        W-Dim "  JAR: $JarPath (exists)"
    } else {
        W-Warn "  JAR: not built yet -- run: ./arima.ps1 build"
    }

    if (Get-Command java -ErrorAction SilentlyContinue) {
        $jv = (& java -version 2>&1 | Select-Object -First 1)
        W-Dim  "  Java:     $jv"
    } else {
        W-Err  '  Java:     NOT FOUND'
    }

    if (Get-Command node -ErrorAction SilentlyContinue) {
        W-Dim "  Node.js:  $(node --version)"
    } else {
        W-Warn '  Node.js:  not found (JavaScript/TypeScript cells disabled)'
    }

    if (Get-Command dotnet -ErrorAction SilentlyContinue) {
        W-Dim "  .NET:     $(dotnet --version)"
    } else {
        W-Warn '  .NET:     not found (C# / F# cells disabled)'
    }

    if (Get-Command python -ErrorAction SilentlyContinue) { W-Dim "  Python:   $(python --version 2>&1)" }
    elseif (Get-Command python3 -ErrorAction SilentlyContinue) { W-Dim "  Python:   $(python3 --version 2>&1)" }
    else { W-Warn '  Python:   not found (Python cells disabled)' }

    $copilots = Get-AiCopilots
    if ($copilots.Count -gt 0) {
        W-Dim  "  AI:       $($copilots.Keys -join ' · ')  (co-pilot ready -- run: ./arima.ps1 agents)"
    } else {
        W-Warn '  AI:       no CLI found (Claude / Copilot / Gemini -- AI features limited)'
    }
    Write-Host ''
    return 0
}

function Cmd-Build {
    Show-Banner
    W-Info '  Building Arima Notebooks...'
    W-Dim  '  --------------------------------------'
    if (-not (Test-Maven)) { return 1 }
    mvn clean package -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Host ''
        W-Ok   '  Build successful!'
        W-Info '  Run: ./arima.ps1 start'
    } else {
        Write-Host ''
        W-Err '  Build failed. Check output above.'
    }
    return $LASTEXITCODE
}

function Cmd-Rebuild {
    Show-Banner
    W-Warn '  Rebuilding Arima Notebooks (force)...'
    W-Dim  '  --------------------------------------'
    if (-not (Test-Maven)) { return 1 }
    mvn clean package -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Host ''
        W-Ok '  Rebuild successful!'
    } else {
        Write-Host ''
        W-Err '  Rebuild failed. Check output above.'
    }
    return $LASTEXITCODE
}

function Cmd-Open {
    if (Test-ServerUp) {
        W-Ok '  Opening Arima Notebooks...'
        Start-Process $Url
        return 0
    } else {
        W-Err '  Arima Notebooks is not running. Start it first: ./arima.ps1 start'
        return 1
    }
}

function Cmd-Logs {
    $logPath = Join-Path $RepoRoot 'arima.log'
    if (-not (Test-Path $logPath)) {
        W-Err '  No log file found (arima.log).'
        W-Dim '  Logs are only written in background mode: ./arima.ps1 start -Bg'
        return 1
    }
    W-Info '  Tailing arima.log (Ctrl+C to stop)...'
    W-Dim  '  --------------------------------------'
    Get-Content -Path $logPath -Wait -Tail 40
    return 0
}

function Cmd-Version {
    Write-Host ''
    W-Title '  Arima Notebooks'
    if (Test-Path 'pom.xml') {
        $line = Select-String -Path 'pom.xml' -Pattern '<version>' |
                Where-Object { $_.Line -notmatch '(parent|spring|junit|jackson|boot)' } |
                Select-Object -First 1
        if ($line) { W-Dim  "  Version:  $($line.Line.Trim())" }
    }
    if (Get-Command java   -ErrorAction SilentlyContinue) { W-Dim "  Java:     $((& java -version 2>&1 | Select-Object -First 1))" }
    if (Get-Command node   -ErrorAction SilentlyContinue) { W-Dim "  Node.js:  $(node --version)" }
    else { W-Warn '  Node.js:  not installed' }
    if (Get-Command dotnet -ErrorAction SilentlyContinue) { W-Dim "  .NET:     $(dotnet --version)" }
    if (Get-Command python -ErrorAction SilentlyContinue) { W-Dim "  Python:   $(python --version 2>&1)" }
    elseif (Get-Command python3 -ErrorAction SilentlyContinue) { W-Dim "  Python:   $(python3 --version 2>&1)" }
    if (Get-Command mvn    -ErrorAction SilentlyContinue) {
        $m = (mvn --version 2>&1 | Select-String 'Apache Maven' | Select-Object -First 1)
        if ($m) { W-Dim "  Maven:    $($m.Line.Trim())" }
    }
    Write-Host ''
    return 0
}

function Cmd-Help {
    Write-Host ''
    Write-Host '        .         ' -ForegroundColor Yellow -NoNewline; W-Title '  Arima Notebooks CLI (PowerShell)'
    Write-Host '       /|\        ' -ForegroundColor Yellow -NoNewline; W-Dim   '  --------------------------------------'
    Write-Host '      ( @ )       ' -ForegroundColor Yellow -NoNewline; W-Info  '  Java | JS | TS | C# | F# | C++ | Python'
    Write-Host '     /|\_/|\      ' -ForegroundColor Yellow
    Write-Host '    / |   | \     ' -ForegroundColor Yellow -NoNewline; W-Info  '  USAGE'
    Write-Host '   /__|   |__\    ' -ForegroundColor Yellow -NoNewline; W-Dim   '    ./arima.ps1 [command] [-Bg]'
    W-Dim   '  ---------------'
    Write-Host ''
    W-Info '  COMMANDS'
    W-Dim  '  --------------------------------------'
    Write-Host '    start            Start server (auto-build, auto-open browser)' -ForegroundColor White
    Write-Host '    start -Bg        Start in background, write logs to arima.log' -ForegroundColor White
    Write-Host '    stop             Stop a running server' -ForegroundColor White
    Write-Host '    status           Show server state, PID, Java, Node.js, .NET, JAR' -ForegroundColor White
    Write-Host '    build            Compile and package (skips if JAR exists)' -ForegroundColor White
    Write-Host '    rebuild          Force clean build' -ForegroundColor White
    Write-Host '    open             Open browser (server must already be running)' -ForegroundColor White
    Write-Host '    logs             Tail arima.log (background mode only)' -ForegroundColor White
    Write-Host '    version          Show Java, Node.js, .NET, Maven, project version' -ForegroundColor White
    Write-Host '    welcome          Common welcome — open the UI, use MCP, or personalize Arima' -ForegroundColor White
    Write-Host '    docs             Open the brochure and list the documentation' -ForegroundColor White
    Write-Host '    agents           Show AI co-pilots, skills & the arima agent wired into this repo' -ForegroundColor White
    Write-Host '    help             Show this help' -ForegroundColor White
    Write-Host ''
    W-Info '  EXAMPLES'
    W-Dim  '  --------------------------------------'
    Write-Host '    ./arima.ps1                  (same as: ./arima.ps1 start)' -ForegroundColor White
    Write-Host '    ./arima.ps1 start -Bg' -ForegroundColor White
    Write-Host '    ./arima.ps1 stop' -ForegroundColor White
    Write-Host '    ./arima.ps1 status' -ForegroundColor White
    Write-Host ''
    W-Info "  URL: $Url"
    Write-Host ''
    return 0
}

function Cmd-Welcome {
    $copilots = Set-BaristaAiContext
    Show-Banner
    W-Title  '  Welcome to Arima Notebooks'
    W-Dim    '  A local notebook for Java | JS | TS | C# | F# | C++ | Python — with AI co-pilots and MCP.'
    Write-Host ''
    W-Info   '  PICK HOW YOU WANT TO WORK'
    W-Dim    '  --------------------------------------'
    Write-Host '    1) Open the UI            ' -ForegroundColor White -NoNewline; W-Dim 'full notebook experience in your browser'
    W-Dim    "         ./arima.ps1 start        ->  $Url"
    Write-Host '    2) Drive Arima over MCP   ' -ForegroundColor White -NoNewline; W-Dim 'operate & automate from any MCP client'
    W-Dim    "         SSE  $Url/api/mcp/sse"
    W-Dim    "         POST $Url/api/mcp/messages"
    Write-Host '    3) Personalize & extend   ' -ForegroundColor White -NoNewline; W-Dim 'add features — needs an agentic CLI'
    W-Dim    '         run  claude  /  copilot  /  gemini   in this folder, then ask the arima agent'
    Write-Host ''
    if ($copilots.Count -gt 0) {
        W-Ok   "  AI co-pilots ready: $($copilots.Keys -join ' · ')   (run: ./arima.ps1 agents)"
    } else {
        W-Warn '  No AI CLI found — install Claude, Copilot, or Gemini to personalize Arima.'
    }
    Write-Host ''
    W-Info   '  THE ONE DIFFERENCE'
    W-Dim    '  --------------------------------------'
    W-Dim    '    This `arima` CLI operates & automates Arima (incl. MCP) but cannot change its code.'
    W-Dim    '    An agentic CLI (claude / copilot / gemini) can ALSO personalize and extend Arima.'
    Write-Host ''
    W-Info   '  NEXT'
    W-Dim    '  --------------------------------------'
    Write-Host '    ./arima.ps1 start      Start the server and open the UI' -ForegroundColor White
    Write-Host '    ./arima.ps1 docs       Open the brochure and list the docs' -ForegroundColor White
    Write-Host '    ./arima.ps1 agents     AI co-pilots, skills & the arima agent' -ForegroundColor White
    Write-Host ''
    W-Dim    '  Full welcome: docs/WELCOME.md'
    Write-Host ''
    return 0
}

function Cmd-Docs {
    $brochure = Join-Path $RepoRoot (Join-Path 'docs' (Join-Path 'brochure' 'arima-brochure.pdf'))
    Write-Host ''
    W-Title  '  Arima Notebooks — Documentation'
    W-Dim    '  --------------------------------------'
    Write-Host '    Brochure (PDF) docs/brochure/arima-brochure.pdf' -ForegroundColor White
    Write-Host '    Welcome        docs/WELCOME.md' -ForegroundColor White
    Write-Host '    Getting started README.md' -ForegroundColor White
    Write-Host '    Architecture   docs/ARCHITECTURE.md' -ForegroundColor White
    Write-Host '    API + MCP      docs/API.md' -ForegroundColor White
    Write-Host '    Contributor    CONTRIBUTING.md  +  AGENTS.md' -ForegroundColor White
    Write-Host '    Cheat sheet    docs/cheatsheet.html' -ForegroundColor White
    Write-Host ''
    if (Test-ServerUp) {
        W-Dim "    In the running app: open the in-UI docs overlay at $Url"
    }
    if (Test-Path $brochure) {
        W-Ok  '    Opening the brochure...'
        Start-Process $brochure
    } else {
        W-Warn '    Brochure PDF not found — open docs/brochure/arima-brochure.html in a browser.'
    }
    Write-Host ''
    return 0
}

function Cmd-Agents {
    $copilots = Set-BaristaAiContext
    Write-Host ''
    Write-Host '        .         ' -ForegroundColor Yellow -NoNewline; W-Title '  Arima AI Co-pilots, Skills & Agents'
    Write-Host '       /|\        ' -ForegroundColor Yellow -NoNewline; W-Dim   '  --------------------------------------'
    Write-Host '      ( @ )       ' -ForegroundColor Yellow
    Write-Host ''

    W-Info '  DETECTED AI CLIs'
    W-Dim  '  --------------------------------------'
    if ($copilots.Count -gt 0) {
        foreach ($name in $copilots.Keys) { W-Ok "    [ok] $name  (binary: $($copilots[$name]))" }
    } else {
        W-Warn '    none found -- install one:'
        W-Dim  '      Claude     :  https://claude.ai/code                      then  claude auth'
        W-Dim  '      Copilot    :  GitHub Copilot CLI (copilot, v1.0.55-5+; used by the Copilot SDK)'
        W-Dim  '      Antigravity:  https://antigravity.google/docs/cli-install  then  run  agy'
    }
    Write-Host ''

    W-Info '  GUARDRAILS (read automatically by every AI CLI in this repo)'
    W-Dim  '  --------------------------------------'
    $g = @(@('AGENTS.md', $AgentsGuide), @('.claude/skills', $SkillsDir), @('.claude/agents', $AgentsDir),
           @('CLAUDE.md', (Join-Path $RepoRoot 'CLAUDE.md')),
           @('.github/copilot-instructions.md', (Join-Path $RepoRoot (Join-Path '.github' 'copilot-instructions.md'))),
           @('GEMINI.md', (Join-Path $RepoRoot 'GEMINI.md')))
    foreach ($pair in $g) {
        if (Test-Path $pair[1]) { W-Ok "    [ok] $($pair[0])" } else { W-Warn "    [--] $($pair[0])  (missing)" }
    }
    Write-Host ''

    W-Info '  SKILLS (auto-invoke when your request matches)'
    W-Dim  '  --------------------------------------'
    if (Test-Path $SkillsDir) {
        Get-ChildItem -Path $SkillsDir -Directory | ForEach-Object { W-Dim "    - $($_.Name)" }
    }
    Write-Host ''
    W-Info '  SUBAGENTS (spawn explicitly for a focused review)'
    W-Dim  '  --------------------------------------'
    if (Test-Path $AgentsDir) {
        Get-ChildItem -Path $AgentsDir -Filter '*.md' | ForEach-Object { W-Dim "    - $($_.BaseName)" }
    }
    Write-Host ''

    W-Info '  HOW TO USE'
    W-Dim  '  --------------------------------------'
    Write-Host '    In the Arima UI : open the AI panel (Ctrl+\), pick a provider, ask away.' -ForegroundColor White
    Write-Host '    In a terminal   : run your CLI from this folder so it loads AGENTS.md:' -ForegroundColor White
    W-Dim      '                        claude        (or)   copilot        (or)   gemini'
    Write-Host '    Example prompt  : "Add a Kotlin execution mode following CppExecutionService,' -ForegroundColor White
    W-Dim      '                       then run ./scripts/security-check.ps1 and open a PR."'
    Write-Host ''
    W-Dim      '    Env exported for this session:'
    W-Dim      "      BARISTA_HOME=$env:BARISTA_HOME"
    W-Dim      "      BARISTA_AGENTS_GUIDE=$env:BARISTA_AGENTS_GUIDE"
    W-Dim      "      BARISTA_SKILLS_DIR=$env:BARISTA_SKILLS_DIR"
    W-Dim      "      BARISTA_AGENTS_DIR=$env:BARISTA_AGENTS_DIR"
    Write-Host ''
    return 0
}

# ── Dispatch ────────────────────────────────────────────────────────────────
switch ($Command.ToLower()) {
    'start'    { exit (Cmd-Start) }
    'stop'     { exit (Cmd-Stop) }
    'status'   { exit (Cmd-Status) }
    'build'    { exit (Cmd-Build) }
    'rebuild'  { exit (Cmd-Rebuild) }
    'open'     { exit (Cmd-Open) }
    'logs'     { exit (Cmd-Logs) }
    'version'  { exit (Cmd-Version) }
    'agents'   { exit (Cmd-Agents) }
    'ai'       { exit (Cmd-Agents) }
    'welcome'  { exit (Cmd-Welcome) }
    'docs'     { exit (Cmd-Docs) }
    'help'     { exit (Cmd-Help) }
    '-h'       { exit (Cmd-Help) }
    '--help'   { exit (Cmd-Help) }
    default {
        W-Err "Unknown command: $Command"
        W-Dim 'Run: ./arima.ps1 help'
        exit 1
    }
}
