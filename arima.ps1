#Requires -Version 5.1
<#
.SYNOPSIS
  Arima Notebooks PowerShell CLI.

.DESCRIPTION
  Cross-platform PowerShell launcher for Arima Notebooks. Shares one look, one
  animation vocabulary and one command set with arima.cmd (Windows CMD) and
  arima.sh (Linux/macOS).

  Lifecycle : install  update  uninstall
  Server    : start [-Bg]  stop  restart  status  open  logs
  Build     : build  rebuild
  MCP       : mcp [ping|info|tools|call|exec|notebooks|read|search|agents|run-agent|raw|config]
  Info      : version  welcome  docs  agents  help

.EXAMPLE
  ./arima.ps1                     # same as: ./arima.ps1 start
  ./arima.ps1 install             # check deps, install what is missing, build
  ./arima.ps1 update              # sync with master, then rebuild
  ./arima.ps1 uninstall           # remove build output (notebooks are kept)
  ./arima.ps1 start -Bg           # background, writes arima.log
  ./arima.ps1 mcp tools           # list the MCP tools over JSON-RPC
#>

[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Command = 'home',

    # Start the server detached, logging to arima.log
    [switch] $Bg,
    # Assume "yes" for confirmation prompts (uninstall)
    [switch] $Yes,
    # uninstall: also delete data/ (settings, packages, users)
    [switch] $Purge,
    # install/update: skip the Maven build
    [switch] $NoBuild,
    # install/uninstall: add/remove this folder from the user PATH
    [switch] $AddToPath,
    # install: only install the required tools (Java + Maven), skip optional runtimes
    [switch] $SkipOptional,
    # Disable the animated banner and spinners
    [switch] $NoAnim,
    # Everything after the subcommand (mcp arguments, tool parameters, ...)
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest = @()
)

Set-StrictMode -Version Latest

# Set by 'open <file>' so a server it starts does not also open the home page.
$script:SuppressOpen = $false
$ErrorActionPreference = 'Stop'

# Resolve repo root (directory holding this script)
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $RepoRoot

# Prefer the bundled Maven Wrapper over a system Maven. It pins the version the
# project builds with and, more importantly, means a fresh machine needs only a
# JDK - Maven is not distributed through winget at all.
function Get-MavenCommand {
    $wrapper = Join-Path $RepoRoot 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapper) { return $wrapper }
    return 'mvn'
}

$JarPath = Join-Path 'target' 'arima-notebooks-1.0.0-SNAPSHOT.jar'
$Port    = 8585
$Url     = "http://localhost:$Port"
$LogOut  = Join-Path $RepoRoot 'arima.log'
$LogErr  = Join-Path $RepoRoot 'arima-err.log'
$MinJava = 17
$McpUrl  = "$Url/api/mcp/messages"
$Tagline = 'A local-first, AI-native notebook for eight languages - run code, build pipelines, and drive it all over MCP.'

# $IsWindows only exists on PowerShell 6+; on 5.1 we are always on Windows.
$IsWindowsHost = if (Test-Path Variable:\IsWindows) { $IsWindows } else { $true }

# ── AI co-pilot context ───────────────────────────────────────────────────────
# These files turn any AI CLI invoked inside this repo (the in-UI AI panel or a
# terminal session) into an Arima-aware co-pilot that follows the architecture
# guardrails and can use the registered skills + subagents.
$AgentsGuide = Join-Path $RepoRoot 'AGENTS.md'
$SkillsDir   = Join-Path $RepoRoot (Join-Path '.claude' 'skills')
$AgentsDir   = Join-Path $RepoRoot (Join-Path '.claude' 'agents')

# ── Shared look & feel ────────────────────────────────────────────────────────
# Every glyph below is plain ASCII on purpose: arima.cmd runs under whatever
# console code page the user has, and the three launchers must render the same.
$Script:Anim = (-not $NoAnim) -and (-not [Console]::IsOutputRedirected)
$Script:Rule = '  ' + ('-' * 60)
$SpinFrames  = @('-', '\', '|', '/')

function W-Title  ($t) { Write-Host $t -ForegroundColor White }
function W-Info   ($t) { Write-Host $t -ForegroundColor Cyan }
function W-Ok     ($t) { Write-Host $t -ForegroundColor Green }
function W-Warn   ($t) { Write-Host $t -ForegroundColor Yellow }
function W-Err    ($t) { Write-Host $t -ForegroundColor Red }
function W-Dim    ($t) { Write-Host $t -ForegroundColor DarkGray }
function W-Plain  ($t) { Write-Host $t }

function Frame-Pause ([int] $Ms) { if ($Script:Anim) { Start-Sleep -Milliseconds $Ms } }

function Clear-Line {
    if ($Script:Anim) { Write-Host ("`r" + (' ' * 72) + "`r") -NoNewline }
}

function Write-Section ($Name) {
    Write-Host ''
    W-Info  "  $Name"
    W-Dim   $Script:Rule
}

# Numbered progress step, e.g. "  [2/5]  Building the JAR"
function Write-Step ($Index, $Total, $Text) {
    Write-Host ''
    Write-Host "  [$Index/$Total] " -ForegroundColor Cyan -NoNewline
    Write-Host " $Text" -ForegroundColor White
}

# Aligned result rows: [ok] / [--] / [!!]
function Write-Row ($State, $Label, $Detail) {
    $pad = "$Label".PadRight(13)
    switch ($State) {
        'ok'   { Write-Host '    [ok] ' -ForegroundColor Green  -NoNewline }
        'warn' { Write-Host '    [--] ' -ForegroundColor Yellow -NoNewline }
        'err'  { Write-Host '    [!!] ' -ForegroundColor Red    -NoNewline }
        default{ Write-Host '    [..] ' -ForegroundColor DarkGray -NoNewline }
    }
    Write-Host $pad -ForegroundColor White -NoNewline
    W-Dim $Detail
}

# Unified progress bar: [########............]  40%  label
function Write-Bar ([int] $Percent, $Label) {
    $width  = 20
    $filled = [Math]::Max(0, [Math]::Min($width, [int][Math]::Round($width * $Percent / 100)))
    $bar    = ('#' * $filled) + ('.' * ($width - $filled))
    $line   = "  [$bar] {0,3}%  $Label" -f $Percent
    if ($Script:Anim) {
        Write-Host ("`r" + $line.PadRight(72)) -ForegroundColor DarkGray -NoNewline
        if ($Percent -ge 100) { Write-Host '' }
    } else {
        W-Dim $line
    }
}

# ── The Barista brew animation ────────────────────────────────────────────────
# A coffee bean drops into the cup, Barista brews it, and serves it steaming.
# Eight frames redrawn in place with ANSI cursor-up; identical art, timing and
# captions in arima.cmd and arima.sh. Rows 0-1 are the bean/steam, rows 2-6 the
# cup, row 7 the caption.
$ESCCH = [char]27
$BrewFrames = @(
    @{ Cup = @('        (@)', '', '        .------.', '        |      |]', '        |      |', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista picks a bean...' },
    @{ Cup = @('', '        (@)',   '        .------.', '        |      |]', '        |      |', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista picks a bean...' },
    @{ Cup = @('', '',              '        .------.', '        |  *   |]', '        |      |', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista grinds it...'    },
    @{ Cup = @('', '',              '        .------.', '        |      |]', '        |::::::|', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista is brewing...'   },
    @{ Cup = @('', '',              '        .------.', '        |::::::|]', '        |######|', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista is brewing...'   },
    @{ Cup = @('          (', '           )', '        .------.', '        |######|]', '        |######|', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista is brewing...' },
    @{ Cup = @('         ( )', '          ) (', '        .------.', '        |######|]', '        |######|', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista serves your notebook.' },
    @{ Cup = @('          ) (', '         ( )', '        .------.', '        |######|]', '        |######|', "        '------'", '       ~~~~~~~~~~'); Caption = 'Barista serves your notebook.' }
)
# Row -> colour. Bean/steam gold, cup white, saucer dim.
$BrewColors = @('Yellow', 'DarkGray', 'White', 'DarkYellow', 'DarkYellow', 'White', 'DarkGray')

function Write-BrewFrame ($Frame, [switch] $Clear) {
    for ($r = 0; $r -lt $Frame.Cup.Count; $r++) {
        $prefix = if ($Clear) { "$ESCCH[2K" } else { '' }
        $colour = if ($r -le 1 -and $Frame.Cup[$r] -match '@') { 'Yellow' } else { $BrewColors[$r] }
        Write-Host ($prefix + $Frame.Cup[$r]) -ForegroundColor $colour
    }
    $prefix = if ($Clear) { "$ESCCH[2K" } else { '' }
    Write-Host ($prefix + '   ' + $Frame.Caption) -ForegroundColor Cyan
}

function Show-Brew {
    # No animation available (redirected output or -NoAnim): show the served cup.
    if (-not $Script:Anim) {
        Write-Host ''
        Write-BrewFrame $BrewFrames[-2]
        return
    }
    Write-Host ''
    $height = $BrewFrames[0].Cup.Count + 1
    $sequence = @(0, 1, 2, 3, 4, 5, 6, 7, 6, 7)
    for ($i = 0; $i -lt $sequence.Count; $i++) {
        if ($i -gt 0) { Write-Host "$ESCCH[${height}A" -NoNewline }
        Write-BrewFrame $BrewFrames[$sequence[$i]] -Clear
        Start-Sleep -Milliseconds $(if ($i -lt 6) { 130 } else { 190 })
    }
}

$BannerArt = @(
    '     .-"""""-.    ',
    "   .'    \    '.  ",
    '  /      )      \ ',
    '  \      (      / ',
    "   '.    /    .'  ",
    "     '-.....-'    "
)

function Show-Banner {
    param([string] $Heading = 'A R I M A   N O T E B O O K S', [string[]] $Lines)
    if (-not $Lines) {
        $Lines = @(
            ('-' * 42),
            'Java  JShell  JS  TS  C#  F#  C++  Python',
            'Brewed by Barista - JShell + Spring Boot',
            "Server: $Url",
            ''
        )
    }
    $colors = @('White', 'DarkGray', 'Cyan', 'DarkGray', 'DarkGray', 'DarkGray')
    Write-Host ''
    for ($i = 0; $i -lt $BannerArt.Count; $i++) {
        Write-Host $BannerArt[$i] -ForegroundColor Yellow -NoNewline
        $text = if ($i -eq 0) { "  $Heading" }
                elseif ($i - 1 -lt $Lines.Count) { "  $($Lines[$i - 1])" }
                else { '' }
        Write-Host $text -ForegroundColor $colors[$i]
        Frame-Pause 55
    }
    W-Dim $Script:Rule
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
    param($Copilots)
    if ($null -eq $Copilots) { $Copilots = Get-AiCopilots }
    $guardOk = (Test-Path $AgentsGuide) -and (Test-Path $SkillsDir) -and (Test-Path $AgentsDir)
    if ($Copilots.Count -gt 0) {
        Write-Row 'ok' 'AI' "$($Copilots.Keys -join '  ')  (co-pilot ready)"
    } else {
        Write-Row 'warn' 'AI' 'no CLI found  (install Claude, Copilot, or Antigravity)'
    }
    if ($guardOk) {
        Write-Row 'ok' 'Guardrails' 'AGENTS.md + skills/ + agents/ loaded  (run: arima agents)'
    } else {
        Write-Row 'warn' 'Guardrails' 'AGENTS.md / .claude skills+agents missing'
    }
}

# ── Probes ────────────────────────────────────────────────────────────────────
function Have ($Bin) { [bool](Get-Command $Bin -ErrorAction SilentlyContinue) }

# Tools such as `java -version` and `mvn --version` write their banner to
# stderr. With $ErrorActionPreference = 'Stop' (set at the top of this script)
# PowerShell turns every redirected stderr line into a terminating
# NativeCommandError -- fatal on Windows PowerShell 5.1, and on pwsh 7.3+ where
# $PSNativeCommandUseErrorActionPreference defaults to $true. Relax both knobs
# for the duration of the call and hand back plain strings.
function Invoke-NativeOut {
    param([Parameter(Mandatory = $true)][scriptblock] $Command)
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $nativeVar = Get-Variable -Name PSNativeCommandUseErrorActionPreference `
                              -Scope Global -ErrorAction SilentlyContinue
    if ($nativeVar) {
        $oldNative = $nativeVar.Value
        $global:PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        return @(& $Command 2>&1 | ForEach-Object { "$_" })
    } finally {
        $ErrorActionPreference = $oldEap
        if ($nativeVar) { $global:PSNativeCommandUseErrorActionPreference = $oldNative }
    }
}

function Get-JavaVersionLine {
    if (-not (Have java)) { return $null }
    return (Invoke-NativeOut { java -version } | Select-Object -First 1 | Out-String).Trim()
}

# Parse "21.0.2" / "1.8.0_392" out of the java -version banner -> major int.
function Get-JavaMajor {
    $line = Get-JavaVersionLine
    if (-not $line) { return 0 }
    if ($line -match '"(\d+)(?:\.(\d+))?') {
        $first = [int]$Matches[1]
        if ($first -eq 1 -and $Matches.Count -gt 2) { return [int]$Matches[2] }
        return $first
    }
    return 0
}

function Get-PythonBin {
    foreach ($b in @('python', 'python3', 'py')) { if (Have $b) { return $b } }
    return $null
}

function Test-Java {
    if (-not (Have java)) {
        W-Err  '  ERROR: Java not found.'
        W-Dim  "  Install JDK $MinJava+ (21 recommended) from https://adoptium.net/"
        return $false
    }
    return $true
}

function Test-Maven {
    # The bundled wrapper downloads Maven itself, so a JDK is enough.
    if (Test-Path -LiteralPath (Join-Path $RepoRoot 'mvnw.cmd')) { return $true }
    if (-not (Have mvn)) {
        W-Err  '  ERROR: Maven not found.'
        W-Dim  '  Install from https://maven.apache.org/'
        return $false
    }
    return $true
}

# Fast local port probe -- far quicker than an HTTP round-trip, which matters
# because the spinner polls this several times a second.
function Test-ServerUp {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
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
        & (Get-MavenCommand) clean package -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            W-Err '  Build failed.'
            return $false
        }
        W-Ok  '  Build complete.'
    }
    return $true
}

# Spinner-backed wait. Shares its frame set with arima.cmd / arima.sh.
function Wait-Server {
    param([int] $TimeoutSec = 45, [string] $Label = 'waiting for the server')
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $i = 0
    while ((Get-Date) -lt $deadline) {
        if (Test-ServerUp) { Clear-Line; return $true }
        if ($Script:Anim) {
            $left = [int]([Math]::Max(0, ($deadline - (Get-Date)).TotalSeconds))
            $frame = $SpinFrames[$i % $SpinFrames.Count]
            Write-Host ("`r  [$frame]  $Label ... ${left}s   ") -ForegroundColor DarkGray -NoNewline
        }
        $i++
        Start-Sleep -Milliseconds 250
    }
    Clear-Line
    return $false
}

function Confirm-Action {
    param([string] $Prompt, [string] $Expect = 'yes')
    if ($Yes) { W-Dim "  (-Yes) $Prompt -> $Expect"; return $true }
    Write-Host ''
    Write-Host "  $Prompt " -ForegroundColor Yellow -NoNewline
    Write-Host "[type '$Expect' to confirm]: " -ForegroundColor DarkGray -NoNewline
    $answer = Read-Host
    return ($answer.Trim().ToLower() -eq $Expect)
}

# ── Runtime inventory (shared by install / status / version) ──────────────────
function Show-Runtimes {
    param([switch] $Verbose)

    $major = Get-JavaMajor
    if ($major -ge $MinJava) {
        Write-Row 'ok' 'Java' (Get-JavaVersionLine)
    } elseif ($major -gt 0) {
        Write-Row 'err' 'Java' "$(Get-JavaVersionLine) -- JDK $MinJava+ required"
    } else {
        Write-Row 'err' 'Java' "NOT FOUND -- install JDK $MinJava+ from https://adoptium.net/"
    }

    if (Have mvn) {
        $m = (Invoke-NativeOut { mvn --version } | Select-String 'Apache Maven' | Select-Object -First 1)
        Write-Row 'ok' 'Maven' $(if ($m) { $m.Line.Trim() } else { 'found' })
    } else {
        Write-Row 'err' 'Maven' 'NOT FOUND -- needed to build (https://maven.apache.org/)'
    }

    if (Have node) {
        Write-Row 'ok' 'Node.js' "$(node --version)  -- JS / TS cells"
    } else {
        Write-Row 'warn' 'Node.js' 'not found -- JS / TS cells disabled (nodejs.org)'
    }

    if (Have tsc) {
        Write-Row 'ok' 'tsc' 'found -- TypeScript type-check diagnostics on'
    } elseif ($Verbose) {
        Write-Row 'warn' 'tsc' 'not found -- optional (npm i -g typescript)'
    }

    if (Have dotnet) {
        Write-Row 'ok' '.NET' "$(dotnet --version)  -- C# / F# cells"
    } else {
        Write-Row 'warn' '.NET' 'not found -- C# / F# cells disabled (https://dot.net)'
    }

    $py = Get-PythonBin
    if ($py) {
        Write-Row 'ok' 'Python' "$(Invoke-NativeOut { & $py --version })  -- Python cells + PyPI"
    } else {
        Write-Row 'warn' 'Python' 'not found -- Python cells disabled (python.org)'
    }

    $cxx = @('cl', 'g++', 'clang++') | Where-Object { Have $_ } | Select-Object -First 1
    if ($cxx) {
        Write-Row 'ok' 'C++' "$cxx  -- C++ cells"
    } else {
        Write-Row 'warn' 'C++' 'no compiler found -- C++ cells disabled (MSVC / GCC / Clang)'
    }

    if (Have git) {
        Write-Row 'ok' 'Git' 'found -- `arima update` can sync with upstream'
    } else {
        Write-Row 'warn' 'Git' 'not found -- `arima update` unavailable'
    }
}

# ── Dependency catalogue (shared by install / status) ─────────────────────────
# One row per tool Arima can use. `Required` tools block the install; everything
# else only disables the cell modes that depend on it. Winget ids are the
# Windows install path; Url is the manual fallback.
function Get-Dependencies {
    @(
        [pscustomobject]@{ Key='java';   Label='Java';    Required=$true;  Winget='EclipseAdoptium.Temurin.21.JDK';
                           Url='https://adoptium.net/';                          Note='JDK 21 - runs the server and JShell cells' }
        [pscustomobject]@{ Key='mvn';    Label='Maven';   Required=$true;  Winget='Apache.Maven';
                           Url='https://maven.apache.org/';                      Note='builds the Arima JAR' }
        [pscustomobject]@{ Key='git';    Label='Git';     Required=$false; Winget='Git.Git';
                           Url='https://git-scm.com/';                           Note='enables: arima update' }
        [pscustomobject]@{ Key='node';   Label='Node.js'; Required=$false; Winget='OpenJS.NodeJS.LTS';
                           Url='https://nodejs.org/';                            Note='JavaScript + TypeScript cells, npm packages' }
        [pscustomobject]@{ Key='dotnet'; Label='.NET';    Required=$false; Winget='Microsoft.DotNet.SDK.9';
                           Url='https://dot.net/';                               Note='C# + F# cells, NuGet packages' }
        [pscustomobject]@{ Key='python'; Label='Python';  Required=$false; Winget='Python.Python.3.13';
                           Url='https://www.python.org/downloads/';              Note='Python cells, PyPI packages' }
        [pscustomobject]@{ Key='cpp';    Label='C++';     Required=$false; Winget='Microsoft.VisualStudio.2022.BuildTools';
                           Url='https://visualstudio.microsoft.com/downloads/';  Note='C++ cells (LARGE download, several GB)' }
    )
}

function Test-Dependency ($Dep) {
    switch ($Dep.Key) {
        'java'   { return (Get-JavaMajor) -ge $MinJava }
        'python' { return [bool](Get-PythonBin) }
        'cpp'    { return [bool](@('cl', 'g++', 'clang++') | Where-Object { Have $_ }) }
        default  { return (Have $Dep.Key) }
    }
}

function Get-DependencyDetail ($Dep) {
    switch ($Dep.Key) {
        'java'   { return (Get-JavaVersionLine) }
        'mvn'    { $m = (Invoke-NativeOut { mvn --version } | Select-String 'Apache Maven' | Select-Object -First 1)
                   return $(if ($m) { $m.Line.Trim() } else { 'found' }) }
        'node'   { return "$(node --version)" }
        'dotnet' { return "$(dotnet --version)" }
        'python' { $b = Get-PythonBin; return "$(Invoke-NativeOut { & $b --version })" }
        'cpp'    { return (@('cl', 'g++', 'clang++') | Where-Object { Have $_ } | Select-Object -First 1) }
        'git'    { return ((Invoke-NativeOut { git --version } | Select-Object -First 1) -replace 'git version ', 'v') }
        default  { return 'found' }
    }
}

# winget installs land in the machine/user PATH but not in the *current* process,
# so re-read both scopes before we probe the tool again.
function Update-PathFromRegistry {
    if (-not $IsWindowsHost) { return }
    $parts = @(
        [Environment]::GetEnvironmentVariable('Path', 'Machine'),
        [Environment]::GetEnvironmentVariable('Path', 'User')
    ) | Where-Object { $_ }
    if ($parts) { $env:Path = ($parts -join ';') }
    foreach ($v in @('JAVA_HOME', 'MAVEN_HOME', 'DOTNET_ROOT')) {
        $val = [Environment]::GetEnvironmentVariable($v, 'Machine')
        if (-not $val) { $val = [Environment]::GetEnvironmentVariable($v, 'User') }
        if ($val) { Set-Item -Path "env:$v" -Value $val }
    }
}

function Install-Dependency ($Dep) {
    if (-not (Have winget)) {
        Write-Row 'err' $Dep.Label "winget unavailable -- install manually: $($Dep.Url)"
        return $false
    }
    W-Dim "         winget install --id $($Dep.Winget)"
    Invoke-NativeOut {
        & winget install --id $Dep.Winget --exact --silent `
            --accept-package-agreements --accept-source-agreements --disable-interactivity
    } | ForEach-Object { W-Dim "         $_" }
    Update-PathFromRegistry
    if (Test-Dependency $Dep) {
        Write-Row 'ok' $Dep.Label "installed -- $(Get-DependencyDetail $Dep)"
        return $true
    }
    Write-Row 'warn' $Dep.Label 'installed, but not visible yet -- open a NEW terminal and re-run'
    return $false
}

# ── Git helpers (used by update) ──────────────────────────────────────────────
function Git-Out {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]] $GitArgs)
    return (Invoke-NativeOut { & git @GitArgs })
}

function Test-GitRepo {
    if (-not (Have git)) {
        W-Err  '  ERROR: git not found.'
        W-Dim  '  Install git from https://git-scm.com/ then re-run: arima update'
        return $false
    }
    $null = Git-Out rev-parse --is-inside-work-tree
    if ($LASTEXITCODE -ne 0) {
        W-Err  '  ERROR: this folder is not a git repository.'
        W-Dim  '  Re-clone with: git clone https://github.com/snchande/arima-notebooks.git'
        return $false
    }
    return $true
}

# ── Subcommands: lifecycle ────────────────────────────────────────────────────
function Cmd-Install {
    Show-Banner -Heading 'A R I M A   -   I N S T A L L' -Lines @(
        ('-' * 42),
        'Check every dependency, install what is missing,',
        'build the JAR, and report readiness.',
        "Server: $Url",
        ''
    )

    $total = 6

    # 1 -- scan
    Write-Step 1 $total 'Checking dependencies'
    Write-Bar 10 'probing toolchains'
    $deps    = Get-Dependencies
    $missing = @()
    foreach ($d in $deps) {
        if (Test-Dependency $d) {
            Write-Row 'ok' $d.Label (Get-DependencyDetail $d)
        } else {
            $tag = if ($d.Required) { 'err' } else { 'warn' }
            $why = if ($d.Required) { 'REQUIRED' } else { 'optional' }
            Write-Row $tag $d.Label "missing ($why) -- $($d.Note)"
            $missing += $d
        }
    }
    if ($SkipOptional) { $missing = @($missing | Where-Object { $_.Required }) }

    # 2 -- install whatever is missing
    Write-Step 2 $total 'Installing missing dependencies'
    if ($missing.Count -eq 0) {
        Write-Bar 100 'nothing to install'
        Write-Row 'ok' 'Dependencies' 'all present'
    } elseif (-not (Have winget)) {
        Write-Bar 100 'winget unavailable'
        Write-Row 'err' 'winget' 'not available -- install these by hand:'
        foreach ($d in $missing) { W-Dim "           $($d.Label.PadRight(9)) $($d.Url)" }
    } else {
        Write-Host ''
        W-Warn '  These will be installed with winget (system-wide):'
        foreach ($d in $missing) { W-Dim "      $($d.Label.PadRight(9)) $($d.Winget)" }
        if (-not (Confirm-Action 'Install the packages listed above?')) {
            Write-Host ''
            W-Warn '  Skipped -- no packages were installed.'
            W-Dim  '  Re-run with -Yes to install without prompting, or -SkipOptional for the minimum.'
            $missing = @($missing)   # keep them in the readiness report
        } else {
            Write-Host ''
            $i = 0
            foreach ($d in $missing) {
                $i++
                Write-Bar ([int](100 * $i / $missing.Count)) "installing $($d.Label)"
                Write-Host ''
                $null = Install-Dependency $d
            }
            Update-PathFromRegistry
            $missing = @($missing | Where-Object { -not (Test-Dependency $_) })
        }
    }

    # 3 -- workspace
    Write-Step 3 $total 'Preparing the workspace'
    Write-Bar 100 'creating folders'
    foreach ($d in @('data', 'notebooks', 'logs')) {
        $p = Join-Path $RepoRoot $d
        if (Test-Path $p) {
            Write-Row 'ok' $d 'already present'
        } else {
            New-Item -ItemType Directory -Path $p -Force | Out-Null
            Write-Row 'ok' $d 'created'
        }
    }

    # 4 -- AI wiring
    Write-Step 4 $total 'Wiring AI co-pilots'
    Write-Bar 100 'detecting AI CLIs'
    $copilots = Set-BaristaAiContext
    Show-AiCopilots -Copilots $copilots

    # 5 -- build (only possible once Java + Maven are actually present)
    $blockers = @($missing | Where-Object { $_.Required })
    $built    = Test-Path $JarPath
    if ($blockers.Count -gt 0) {
        Write-Step 5 $total 'Build skipped -- required tools still missing'
        foreach ($d in $blockers) { Write-Row 'err' $d.Label "install it, then re-run: arima install" }
    } elseif ($NoBuild) {
        Write-Step 5 $total 'Build skipped (-NoBuild)'
        Write-Bar 100 'skipped'
    } else {
        Write-Step 5 $total 'Building the JAR'
        Write-Bar 50 'mvn clean package -DskipTests'
        Write-Host ''
        & (Get-MavenCommand) clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            W-Err '  Build failed -- see the Maven output above.'
            return 1
        }
        Write-Bar 100 'build complete'
        Write-Row 'ok' 'JAR' $JarPath
        $built = $true
    }

    # 6 -- PATH
    Write-Step 6 $total 'Registering on your user PATH'
    if ($AddToPath -and $IsWindowsHost) {
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($null -eq $userPath) { $userPath = '' }
        if (($userPath -split ';') -contains $RepoRoot) {
            Write-Row 'ok' 'PATH' 'already registered'
        } else {
            $newPath = if ($userPath.TrimEnd(';')) { "$($userPath.TrimEnd(';'));$RepoRoot" } else { $RepoRoot }
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
            Write-Row 'ok' 'PATH' "added $RepoRoot (new terminals only)"
        }
    } else {
        Write-Row 'ok' 'PATH' 'left untouched  (use -AddToPath to run `arima` from anywhere)'
    }

    # ── Readiness report ──────────────────────────────────────────────────────
    Write-Section 'READINESS'
    $stillMissing = @(Get-Dependencies | Where-Object { -not (Test-Dependency $_) })
    $reqMissing   = @($stillMissing | Where-Object { $_.Required })

    foreach ($d in Get-Dependencies) {
        if (Test-Dependency $d) {
            Write-Row 'ok' $d.Label (Get-DependencyDetail $d)
        } else {
            $tag = if ($d.Required) { 'err' } else { 'warn' }
            Write-Row $tag $d.Label "still missing -- $($d.Note)"
        }
    }
    Write-Row $(if ($built) { 'ok' } else { 'err' }) 'JAR' $(if ($built) { $JarPath } else { 'not built' })

    Write-Host ''
    if ($reqMissing.Count -eq 0 -and $built) {
        Show-Brew
        if ($stillMissing.Count -eq 0) {
            W-Ok '  EVERYTHING IS READY -- all eight languages are available.'
        } else {
            W-Ok "  READY -- Arima will start. $($stillMissing.Count) optional runtime(s) absent:"
            foreach ($d in $stillMissing) { W-Dim "      $($d.Label.PadRight(9)) $($d.Note)   $($d.Url)" }
            W-Dim  '      Add them later and re-run: arima install'
        }
        Write-Host ''
        W-Plain '    arima start        Start the server and open the UI'
        W-Plain '    arima start -Bg    Start detached, logs to arima.log'
        W-Plain '    arima mcp tools    Drive Arima over MCP from this terminal'
        W-Plain '    arima update       Sync with upstream and rebuild'
        Write-Host ''
        return 0
    }

    W-Err '  NOT READY.'
    foreach ($d in $reqMissing) { W-Dim "      $($d.Label.PadRight(9)) required -- $($d.Url)" }
    if (-not $built) { W-Dim '      JAR       not built -- run `arima build` once the tools above exist' }
    W-Dim  '      Then re-run: arima install'
    Write-Host ''
    return 1
}

function Cmd-Update {
    Show-Banner -Heading 'A R I M A   -   U P D A T E' -Lines @(
        ('-' * 42),
        'Sync this checkout with upstream, then rebuild.',
        'Your local commits are replayed on top (rebase).',
        'Uncommitted work always blocks the update.',
        ''
    )

    if (-not (Test-GitRepo)) { return 1 }
    $total = 5
    $wasRunning = Test-ServerUp

    # 1 -- working tree must be clean, otherwise a rebase can eat local edits
    Write-Step 1 $total 'Checking your working tree'
    Write-Bar 10 'git status'
    $status    = @(Git-Out status --porcelain)
    $tracked   = @($status | Where-Object { $_ -and $_ -notmatch '^\?\?' })
    $untracked = @($status | Where-Object { $_ -match '^\?\?' })

    if ($tracked.Count -gt 0) {
        Write-Host ''
        Write-Section 'UNCOMMITTED CHANGES -- UPDATE STOPPED'
        W-Err  '  You have local edits that a rebase could overwrite.'
        W-Dim  '  Nothing has been changed. Commit (or stash) them first so your work is safe:'
        Write-Host ''
        foreach ($line in ($tracked | Select-Object -First 20)) { W-Warn "      $line" }
        if ($tracked.Count -gt 20) { W-Dim "      ... and $($tracked.Count - 20) more" }
        Write-Host ''
        W-Info '  Option A -- keep the changes as a commit (recommended)'
        W-Dim  '      git add -A'
        W-Dim  '      git commit -m "wip: my local changes"'
        W-Dim  '      arima update'
        Write-Host ''
        W-Info '  Option B -- park the changes, update, then bring them back'
        W-Dim  '      git stash push -u -m "before arima update"'
        W-Dim  '      arima update'
        W-Dim  '      git stash pop'
        Write-Host ''
        return 2
    }
    Write-Row 'ok' 'Working tree' 'clean -- safe to rebase'
    if ($untracked.Count -gt 0) {
        Write-Row 'warn' 'Untracked' "$($untracked.Count) new file(s) present -- they will be left alone"
    }

    # 2 -- fetch
    Write-Step 2 $total 'Fetching from upstream'
    Write-Bar 30 'git fetch --prune'
    $hasOrigin = $true
    $null = Git-Out remote get-url origin
    if ($LASTEXITCODE -ne 0) { $hasOrigin = $false }

    if (-not $hasOrigin) {
        Write-Row 'warn' 'Remote' 'no `origin` configured -- skipping sync, rebuilding only'
    } else {
        $null = Git-Out fetch --prune origin
        if ($LASTEXITCODE -ne 0) {
            Write-Row 'err' 'Fetch' 'failed -- check your network / credentials'
            return 1
        }
        Write-Row 'ok' 'Fetch' 'up to date with origin'
    }

    # 3 -- rebase or fast-forward onto the base branch
    Write-Step 3 $total 'Syncing with the base branch'
    $rebased = $false
    if ($hasOrigin) {
        $branch = (Git-Out rev-parse --abbrev-ref HEAD | Select-Object -First 1)
        $base   = (Git-Out rev-parse --abbrev-ref --symbolic-full-name '@{u}' | Select-Object -First 1)
        if ($LASTEXITCODE -ne 0 -or -not $base) {
            $base = 'origin/master'
            $null = Git-Out rev-parse --verify --quiet $base
            if ($LASTEXITCODE -ne 0) { $base = 'origin/main' }
        }
        $null = Git-Out rev-parse --verify --quiet $base
        if ($LASTEXITCODE -ne 0) {
            Write-Row 'err' 'Base' "cannot resolve a base branch (tried $base)"
            return 1
        }
        Write-Row 'ok' 'Branch' "$branch  ->  $base"

        $counts = (Git-Out rev-list --left-right --count "HEAD...$base" | Select-Object -First 1) -split '\s+'
        $ahead  = [int]$counts[0]
        $behind = [int]$counts[1]
        Write-Row 'ok' 'Divergence' "$ahead local commit(s) ahead, $behind behind"

        if ($behind -eq 0) {
            Write-Bar 60 'already in sync'
            Write-Row 'ok' 'Sync' 'nothing to pull -- already current'
        } elseif ($ahead -eq 0) {
            # Purely behind: a fast-forward cannot conflict.
            Write-Bar 50 "fast-forwarding $behind commit(s)"
            $null = Git-Out merge --ff-only $base
            if ($LASTEXITCODE -ne 0) {
                Write-Row 'err' 'Sync' 'fast-forward failed -- run `git status` to inspect'
                return 3
            }
            Write-Row 'ok' 'Sync' "fast-forwarded $behind commit(s)"
            $rebased = $true
        } else {
            # Local commits + upstream commits: replay ours on top.
            Write-Bar 50 "rebasing $ahead local commit(s) onto $base"
            $null = Git-Out rebase $base
            if ($LASTEXITCODE -ne 0) {
                $conflicts = @(Git-Out diff --name-only --diff-filter=U)
                # Restore the pre-rebase state so the checkout is never left mid-rebase.
                $null = Git-Out rebase --abort
                Write-Host ''
                Write-Section 'MERGE CONFLICT -- UPDATE ROLLED BACK'
                W-Err  '  The rebase hit conflicts, so it was aborted.'
                W-Dim  "  Your branch is back exactly where it was -- nothing was lost."
                if ($conflicts.Count -gt 0) {
                    Write-Host ''
                    W-Info '  Files that conflict with upstream:'
                    foreach ($f in ($conflicts | Select-Object -First 20)) { W-Warn "      $f" }
                    if ($conflicts.Count -gt 20) { W-Dim "      ... and $($conflicts.Count - 20) more" }
                }
                Write-Host ''
                W-Info '  Resolve it by hand, once:'
                W-Dim  "      git rebase $base       # start the rebase"
                W-Dim  '      # ...fix the conflicted files, then...'
                W-Dim  '      git add <file>'
                W-Dim  '      git rebase --continue'
                W-Dim  '      arima build'
                Write-Host ''
                W-Info '  Or drop your local commits and take upstream as-is (destructive):'
                W-Dim  "      git reset --hard $base"
                Write-Host ''
                return 3
            }
            Write-Row 'ok' 'Sync' "rebased $ahead commit(s) onto $base"
            $rebased = $true
        }
    }

    # 4 -- rebuild
    if ($NoBuild) {
        Write-Step 4 $total 'Build skipped (-NoBuild)'
        Write-Bar 100 'skipped'
    } elseif (-not $rebased -and (Test-Path $JarPath)) {
        Write-Step 4 $total 'Rebuild not needed'
        Write-Bar 100 'JAR already current'
        Write-Row 'ok' 'JAR' 'unchanged -- no new commits to compile'
    } else {
        Write-Step 4 $total 'Rebuilding the JAR'
        if (-not (Test-Maven)) { return 1 }
        Write-Bar 80 'mvn clean package -DskipTests'
        Write-Host ''
        & (Get-MavenCommand) clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            W-Err '  Build failed after the update -- see the Maven output above.'
            return 1
        }
        Write-Bar 100 'build complete'
        Write-Row 'ok' 'JAR' $JarPath
    }

    # 5 -- restart hint
    Write-Step 5 $total 'Finishing up'
    if ($wasRunning) {
        Write-Row 'warn' 'Server' 'still running the OLD jar -- restart to pick up the update'
        W-Dim  '           arima restart'
    } else {
        Write-Row 'ok' 'Server' 'not running -- start it with: arima start'
    }

    Write-Section 'UPDATED'
    W-Ok  '  Arima Notebooks is up to date.'
    Write-Host ''
    return 0
}

function Cmd-Uninstall {
    Show-Banner -Heading 'A R I M A   -   U N I N S T A L L' -Lines @(
        ('-' * 42),
        'Remove build output and runtime logs.',
        'Your notebooks and source code are never touched.',
        '',
        ''
    )

    $targets = @()
    $target  = Join-Path $RepoRoot 'target'
    if (Test-Path $target) { $targets += ,@('target/',      'compiled classes + the JAR') }
    if (Test-Path $LogOut) { $targets += ,@('arima.log',     'background stdout log') }
    if (Test-Path $LogErr) { $targets += ,@('arima-err.log', 'background stderr log') }
    if ($Purge) {
        $dataDir = Join-Path $RepoRoot 'data'
        if (Test-Path $dataDir) { $targets += ,@('data/', 'SETTINGS, packages, users -- unrecoverable') }
    }

    Write-Section 'WILL BE REMOVED'
    if ($targets.Count -eq 0) {
        W-Dim '    (nothing -- already clean)'
    } else {
        foreach ($t in $targets) {
            if ($t[0] -eq 'data/') { Write-Row 'err' $t[0] $t[1] } else { Write-Row 'warn' $t[0] $t[1] }
        }
    }

    Write-Section 'WILL BE KEPT'
    Write-Row 'ok' 'notebooks/' 'every notebook you wrote'
    Write-Row 'ok' 'src/, docs/' 'source code and documentation'
    Write-Row 'ok' '.git/'      'git history and remotes'
    if (-not $Purge) {
        Write-Row 'ok' 'data/' 'settings, packages, users  (use -Purge to delete)'
    }

    if ($targets.Count -eq 0 -and -not $AddToPath) {
        Write-Host ''
        W-Ok '  Nothing to do.'
        Write-Host ''
        return 0
    }

    if (-not (Confirm-Action 'Remove the items listed above?')) {
        Write-Host ''
        W-Warn '  Cancelled -- nothing was removed.'
        Write-Host ''
        return 1
    }

    Write-Host ''
    Write-Step 1 3 'Stopping the server'
    if (Test-ServerUp) {
        $null = Cmd-Stop
    } else {
        Write-Row 'ok' 'Server' 'not running'
    }

    Write-Step 2 3 'Removing files'
    $i = 0
    foreach ($t in $targets) {
        $i++
        Write-Bar ([int](100 * $i / [Math]::Max(1, $targets.Count))) "removing $($t[0])"
        $path = Join-Path $RepoRoot ($t[0].TrimEnd('/'))
        try {
            Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction Stop
            Write-Row 'ok' $t[0] 'removed'
        } catch {
            Write-Row 'err' $t[0] "could not remove: $_"
        }
    }

    Write-Step 3 3 'Cleaning up the PATH entry'
    if ($AddToPath) {
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($null -eq $userPath) { $userPath = '' }
        $kept = @($userPath -split ';' | Where-Object { $_ -and $_ -ne $RepoRoot })
        [Environment]::SetEnvironmentVariable('Path', ($kept -join ';'), 'User')
        Write-Row 'ok' 'PATH' 'entry removed (new terminals only)'
    } else {
        Write-Row 'ok' 'PATH' 'left untouched  (use -AddToPath to remove the entry too)'
    }

    Write-Section 'UNINSTALLED'
    W-Ok   '  Build output removed. The checkout itself is intact.'
    W-Dim  '  Reinstall any time with:  arima install'
    Write-Host ''
    return 0
}

# ── Subcommands: server ───────────────────────────────────────────────────────
function Cmd-Start {
    Show-Brew
    Show-Banner

    if (Test-ServerUp) {
        W-Ok   '  Arima Notebooks is already running.'
        W-Info "  URL: $Url"
        $ans = Read-Host '  Open in browser? [Y/n]'
        if ($ans -notmatch '^[nN]') { Start-Process $Url }
        return 0
    }

    Write-Section 'ENVIRONMENT'
    if (-not (Test-Java)) { return 1 }
    Show-Runtimes

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
        Write-Section 'STARTING (BACKGROUND)'
        $proc = Start-Process -FilePath java -ArgumentList $jvmArgs `
                -RedirectStandardOutput $LogOut -RedirectStandardError $LogErr `
                -WindowStyle Hidden -PassThru
        Write-Row 'ok' 'Process' "PID $($proc.Id)   logs: arima.log"
        if (Wait-Server -TimeoutSec 45) {
            Write-Row 'ok' 'Server' "up at $Url"
            # "open <file>" starts the server itself and then navigates straight to the
            # notebook, so it suppresses this to avoid leaving a stray home tab behind.
            if (-not $script:SuppressOpen) { Start-Process $Url }
            Write-Host ''
            return 0
        }
        Write-Row 'err' 'Server' 'no response after 45s -- check arima.log / arima-err.log'
        return 1
    }

    Write-Section 'RUNNING (FOREGROUND)'
    W-Dim '  Press Ctrl+C to stop'
    W-Dim $Script:Rule
    Write-Host ''
    # Open the browser once the port actually accepts connections.
    Start-Job -ScriptBlock {
        param($u, $p)
        for ($n = 0; $n -lt 60; $n++) {
            $c = New-Object System.Net.Sockets.TcpClient
            try { $c.Connect('127.0.0.1', $p); $c.Close(); Start-Process $u; return } catch { }
            finally { $c.Close() }
            Start-Sleep -Milliseconds 500
        }
    } -ArgumentList $Url, $Port | Out-Null

    # Exit code 42 = restart requested by the UI (matches scripts/start.sh)
    while ($true) {
        & java @jvmArgs
        $code = $LASTEXITCODE
        if ($code -ne 42) { return $code }
        Write-Host ''
        W-Dim  $Script:Rule
        W-Info '  Restarting Arima Notebooks...'
        W-Dim  $Script:Rule
        Start-Sleep -Seconds 1
    }
}

function Cmd-Stop {
    $procId = Get-ListeningPid
    if (-not $procId) {
        Write-Row 'warn' 'Server' 'not running'
        return 1
    }
    Write-Row 'ok' 'Server' "stopping PID $procId"
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Row 'ok' 'Server' 'stopped'
        return 0
    } catch {
        Write-Row 'err' 'Server' "failed to stop PID $procId : $_"
        return 1
    }
}

function Cmd-Restart {
    Show-Banner -Heading 'A R I M A   -   R E S T A R T'
    Write-Step 1 2 'Stopping'
    if (Test-ServerUp) { $null = Cmd-Stop; Start-Sleep -Seconds 1 }
    else { Write-Row 'ok' 'Server' 'was not running' }
    Write-Step 2 2 'Starting'
    return (Cmd-Start)
}

function Cmd-Status {
    Show-Banner -Heading 'A R I M A   -   S T A T U S'
    Write-Section 'SERVER'
    if (Test-ServerUp) {
        Show-LiveServer
    } else {
        Write-Row 'warn' 'State' 'STOPPED -- start it with: arima start'
    }
    if (Test-Path $JarPath) {
        $age = (Get-Item $JarPath).LastWriteTime
        Write-Row 'ok' 'JAR' "$JarPath  (built $age)"
    } else {
        Write-Row 'warn' 'JAR' 'not built yet -- run: arima install'
    }

    Write-Section 'RUNTIMES'
    Show-Runtimes -Verbose

    Write-Section 'AI'
    Show-AiCopilots -Copilots (Get-AiCopilots)

    if (Have git) {
        Write-Section 'CHECKOUT'
        $null = Git-Out rev-parse --is-inside-work-tree
        if ($LASTEXITCODE -eq 0) {
            $branch = (Git-Out rev-parse --abbrev-ref HEAD | Select-Object -First 1)
            $sha    = (Git-Out rev-parse --short HEAD | Select-Object -First 1)
            $dirty  = @(Git-Out status --porcelain | Where-Object { $_ -notmatch '^\?\?' })
            Write-Row 'ok' 'Branch' "$branch @ $sha"
            if ($dirty.Count -gt 0) {
                Write-Row 'warn' 'Changes' "$($dirty.Count) uncommitted file(s) -- commit before: arima update"
            } else {
                Write-Row 'ok' 'Changes' 'clean -- `arima update` is safe to run'
            }
        }
    }
    Write-Host ''
    return 0
}

function Cmd-Open {
    param([string] $File)

    if (-not $File) {
        if (Test-ServerUp) {
            W-Ok '  Opening Arima Notebooks...'
            Start-Process $Url
            return 0
        }
        W-Err '  Arima Notebooks is not running. Start it first: arima start'
        return 1
    }

    if (-not (Test-Path -LiteralPath $File)) {
        W-Err "  No such file: $File"
        return 1
    }

    # A double-clicked .anb has to work from a cold machine, so start the server
    # rather than telling the user to do it themselves.
    if (-not (Test-ServerUp)) {
        W-Info '  Arima Notebooks is not running - starting it...'
        $script:Bg = $true
        $script:SuppressOpen = $true
        $rc = Cmd-Start
        if ($rc -ne 0) { return $rc }
    }

    $full = (Resolve-Path -LiteralPath $File).Path
    try {
        $resp = Invoke-RestMethod -Uri "$Url/api/notebooks/open-file" -Method Post `
                    -ContentType 'application/json' `
                    -Body (@{ path = $full } | ConvertTo-Json)
    } catch {
        W-Err "  Could not open that notebook: $($_.Exception.Message)"
        return 1
    }
    if (-not $resp.id) {
        W-Err '  That file is not a readable Arima notebook.'
        return 1
    }
    W-Ok "  Opening $(Split-Path -Leaf $full)"
    Start-Process "$Url/notebooks/$($resp.id)"
    return 0
}

$AnbProgId   = 'ArimaNotebooks.Notebook'
$AnbMimeType = 'application/vnd.arima.notebook+json'

function Get-AnbIconPath {
    $candidates = @(
        (Join-Path $RepoRoot 'src/main/resources/static/arima-notebook.ico'),
        (Join-Path $RepoRoot 'arima-notebook.ico')
    )
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c) { return (Resolve-Path -LiteralPath $c).Path } }
    return $null
}

function Update-ShellIconCache {
    # SHCNE_ASSOCCHANGED - tells Explorer to re-read associations and icons now,
    # instead of at the next sign-in.
    try {
        Add-Type -ErrorAction Stop -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ArimaShell {
    [DllImport("shell32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    public static extern void SHChangeNotify(int eventId, uint flags, IntPtr item1, IntPtr item2);
}
'@
        [ArimaShell]::SHChangeNotify(0x08000000, 0x1000, [IntPtr]::Zero, [IntPtr]::Zero)
    } catch { }
}

function Cmd-Register {
    if (-not $IsWindowsHost) {
        W-Err '  File registration is Windows-only. On Linux use ./arima.sh register.'
        return 1
    }
    $icon = Get-AnbIconPath
    if (-not $icon) {
        W-Err '  Icon not found (src/main/resources/static/arima-notebook.ico).'
        return 1
    }
    $launcher = Join-Path $RepoRoot 'arima.cmd'
    if (-not (Test-Path -LiteralPath $launcher)) {
        W-Err "  arima.cmd not found next to this script."
        return 1
    }

    W-Info '  Registering .anb with Arima Notebooks...'

    # Everything goes under HKCU so no administrator rights are needed.
    $classes = 'HKCU:\Software\Classes'
    New-Item -Path "$classes\.anb" -Force | Out-Null
    Set-ItemProperty -Path "$classes\.anb" -Name '(Default)'     -Value $AnbProgId
    Set-ItemProperty -Path "$classes\.anb" -Name 'Content Type'  -Value $AnbMimeType
    Set-ItemProperty -Path "$classes\.anb" -Name 'PerceivedType' -Value 'document'
    New-Item -Path "$classes\.anb\OpenWithProgids" -Force | Out-Null
    Set-ItemProperty -Path "$classes\.anb\OpenWithProgids" -Name $AnbProgId -Value ([byte[]]@()) -Type None

    New-Item -Path "$classes\$AnbProgId" -Force | Out-Null
    Set-ItemProperty -Path "$classes\$AnbProgId" -Name '(Default)' -Value 'Arima Notebook'
    New-Item -Path "$classes\$AnbProgId\DefaultIcon" -Force | Out-Null
    Set-ItemProperty -Path "$classes\$AnbProgId\DefaultIcon" -Name '(Default)' -Value "$icon,0"
    New-Item -Path "$classes\$AnbProgId\shell\open\command" -Force | Out-Null
    Set-ItemProperty -Path "$classes\$AnbProgId\shell\open\command" -Name '(Default)' `
        -Value ('"{0}" open "%1"' -f $launcher)

    # Register the MIME type both ways, so content-type lookups resolve too.
    $mimeKey = "$classes\MIME\Database\Content Type\$AnbMimeType"
    New-Item -Path $mimeKey -Force | Out-Null
    Set-ItemProperty -Path $mimeKey -Name 'Extension' -Value '.anb'

    Update-ShellIconCache
    W-Ok  '  .anb files now open in Arima Notebooks'
    W-Dim "  Icon: $icon"
    W-Dim "  MIME: $AnbMimeType"
    W-Dim '  Explorer may need a refresh (F5) to redraw existing icons.'
    return 0
}

function Cmd-Unregister {
    if (-not $IsWindowsHost) {
        W-Err '  File registration is Windows-only.'
        return 1
    }
    W-Info '  Removing the .anb association...'
    $classes = 'HKCU:\Software\Classes'
    foreach ($k in @("$classes\.anb", "$classes\$AnbProgId",
                     "$classes\MIME\Database\Content Type\$AnbMimeType")) {
        if (Test-Path -LiteralPath $k) { Remove-Item -LiteralPath $k -Recurse -Force }
    }
    Update-ShellIconCache
    W-Ok '  .anb association removed'
    return 0
}

function Cmd-Logs {
    if (-not (Test-Path $LogOut)) {
        W-Err '  No log file found (arima.log).'
        W-Dim '  Logs are only written in background mode: ./arima.ps1 start -Bg'
        return 1
    }
    W-Info '  Tailing arima.log (Ctrl+C to stop)...'
    W-Dim  $Script:Rule
    Get-Content -Path $LogOut -Wait -Tail 40
    return 0
}

# ── MCP over the command line ─────────────────────────────────────────────────
# POST /api/mcp/messages is a plain, stateless JSON-RPC 2.0 endpoint, so the CLI
# can call the very same tools an MCP client would -- no SSE session required.

$Script:McpId = 0

function Invoke-Mcp {
    param([string] $Method, $Params = @{})
    $Script:McpId++
    $body = @{ jsonrpc = '2.0'; id = $Script:McpId; method = $Method; params = $Params } |
            ConvertTo-Json -Depth 12 -Compress
    try {
        return Invoke-RestMethod -Method Post -Uri $McpUrl -ContentType 'application/json' `
                                 -Body $body -TimeoutSec 180 -ErrorAction Stop
    } catch {
        W-Err "  MCP request failed: $_"
        return $null
    }
}

# Turn CLI arguments into a parameter hashtable.
# Accepts either a single JSON object -- '{"code":"1+1"}' -- or key=value pairs.
function ConvertTo-McpArgs {
    param([string[]] $Pairs)
    $map = @{}
    if (-not $Pairs -or $Pairs.Count -eq 0) { return $map }
    if ($Pairs.Count -eq 1 -and $Pairs[0].TrimStart().StartsWith('{')) {
        $obj = $Pairs[0] | ConvertFrom-Json
        foreach ($p in $obj.PSObject.Properties) { $map[$p.Name] = $p.Value }
        return $map
    }
    foreach ($pair in $Pairs) {
        $eq = $pair.IndexOf('=')
        if ($eq -lt 1) { W-Warn "  Ignoring '$pair' -- expected key=value"; continue }
        $k = $pair.Substring(0, $eq)
        $v = $pair.Substring($eq + 1)
        # key=@path reads the value from a file -- the escape hatch for code that
        # CMD (which cannot nest quotes) or any shell would otherwise mangle.
        if ($v.StartsWith('@') -and (Test-Path -LiteralPath $v.Substring(1))) {
            $map[$k] = (Get-Content -LiteralPath $v.Substring(1) -Raw)
            continue
        }
        if     ($v -eq 'true')          { $map[$k] = $true }
        elseif ($v -eq 'false')         { $map[$k] = $false }
        elseif ($v -match '^-?\d+$')    { $map[$k] = [int]$v }
        else                            { $map[$k] = $v }
    }
    return $map
}

# tools/call results are {content:[{type:text,text:...}]} -- print the text.
function Show-McpResult {
    param($Response)
    if ($null -eq $Response) { return 1 }
    if ($Response.PSObject.Properties.Name -contains 'error' -and $Response.error) {
        W-Err "  MCP error $($Response.error.code): $($Response.error.message)"
        return 1
    }
    $result = $Response.result
    if ($null -eq $result) { W-Dim '  (empty result)'; return 0 }
    if ($result.PSObject.Properties.Name -contains 'content') {
        foreach ($item in $result.content) {
            if ($item.type -eq 'text') { Write-Host $item.text } else { Write-Host ($item | ConvertTo-Json -Depth 8) }
        }
        return 0
    }
    Write-Host ($result | ConvertTo-Json -Depth 12)
    return 0
}

function Invoke-McpTool {
    param([string] $Tool, [string[]] $ToolArgs)
    if (-not (Test-ServerUp)) { Show-McpDown; return 1 }
    $params = @{ name = $Tool; arguments = (ConvertTo-McpArgs $ToolArgs) }
    return (Show-McpResult (Invoke-Mcp 'tools/call' $params))
}

function Show-McpDown {
    W-Err  '  Arima Notebooks is not running -- MCP needs the server.'
    W-Dim  '  Start it first:  arima start -Bg'
}

function Show-McpOverview {
    Show-Banner -Heading 'A R I M A   -   M C P' -Lines @(
        ('-' * 42),
        'Model Context Protocol server (JSON-RPC 2.0)',
        'Call the same tools any MCP client would.',
        "Server: $Url",
        ''
    )
    Write-Section 'ENDPOINTS'
    Write-Row 'ok' 'SSE'      "$Url/api/mcp/sse"
    Write-Row 'ok' 'Messages' $McpUrl
    if (Test-ServerUp) {
        $info = Invoke-Mcp 'initialize' @{}
        if ($info -and $info.result) {
            Write-Row 'ok' 'Server' "$($info.result.serverInfo.name) v$($info.result.serverInfo.version)"
            Write-Row 'ok' 'Protocol' $info.result.protocolVersion
        }
    } else {
        Write-Row 'warn' 'Server' 'STOPPED -- start it with: arima start -Bg'
    }

    Write-Section 'COMMANDS'
    W-Plain '    arima mcp info                    Server name, version, protocol'
    W-Plain '    arima mcp ping                    Health check'
    W-Plain '    arima mcp tools                   List every MCP tool + its parameters'
    W-Plain '    arima mcp call <tool> k=v ...     Call any tool by name'
    W-Plain '    arima mcp exec "<code>"           Run Java/JShell code'
    W-Plain '    arima mcp notebooks               List notebooks'
    W-Plain '    arima mcp read <notebookId>       Read every cell of a notebook'
    W-Plain '    arima mcp search <query>          Search cells by anchor or source'
    W-Plain '    arima mcp agents                  List agents & skills'
    W-Plain '    arima mcp run-agent <id> <task>   Run an agent against a task'
    W-Plain '    arima mcp raw ''<json-rpc>''        Send a raw JSON-RPC envelope'
    W-Plain '    arima mcp config                  Print an MCP client config snippet'

    Write-Section 'EXAMPLES'
    W-Dim '    arima mcp exec "System.out.println(2+2);"'
    W-Dim '    arima mcp call barista_search_cells query=tablesaw'
    W-Dim '    arima mcp call barista_append_cell notebookId=my-nb source="var x=1;" execute=true'
    W-Dim '    arima mcp raw "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"'
    Write-Host ''
    return 0
}

function Cmd-Mcp {
    param([string[]] $McpArgs)
    # arima.cmd forwards the raw command line, so CMD-style global flags can
    # arrive here. They were already handled by the caller -- drop them so they
    # are never mistaken for an mcp subcommand.
    $globalFlags = @('--no-anim', '--yes', '-y', '--bg', '-b', '--purge',
                     '--no-build', '--path', '--skip-optional')
    $McpArgs = @($McpArgs | Where-Object { $_ -and ($globalFlags -notcontains $_.ToLower()) })
    if (-not $McpArgs -or $McpArgs.Count -eq 0) { return (Show-McpOverview) }

    $sub  = $McpArgs[0].ToLower()
    $tail = @(if ($McpArgs.Count -gt 1) { $McpArgs[1..($McpArgs.Count - 1)] })

    switch ($sub) {
        'info' {
            if (-not (Test-ServerUp)) { Show-McpDown; return 1 }
            return (Show-McpResult (Invoke-Mcp 'initialize' @{}))
        }
        'ping' {
            if (-not (Test-ServerUp)) { Show-McpDown; return 1 }
            $r = Invoke-Mcp 'ping' @{}
            if ($null -eq $r) { return 1 }
            W-Ok "  MCP is alive at $McpUrl"
            return 0
        }
        'tools' {
            if (-not (Test-ServerUp)) { Show-McpDown; return 1 }
            $r = Invoke-Mcp 'tools/list' @{}
            if ($null -eq $r -or $null -eq $r.result) { return 1 }
            Write-Section 'MCP TOOLS'
            foreach ($t in $r.result.tools) {
                Write-Host ''
                Write-Host "    $($t.name)" -ForegroundColor White
                W-Dim   "      $($t.description)"
                if ($t.inputSchema -and $t.inputSchema.properties) {
                    $required = @()
                    if ($t.inputSchema.PSObject.Properties.Name -contains 'required') { $required = @($t.inputSchema.required) }
                    foreach ($p in $t.inputSchema.properties.PSObject.Properties) {
                        $mark = if ($required -contains $p.Name) { '*' } else { ' ' }
                        W-Dim "      $mark $($p.Name.PadRight(12)) $($p.Value.description)"
                    }
                }
            }
            Write-Host ''
            W-Dim '    * = required.  Call one with:  arima mcp call <tool> key=value ...'
            Write-Host ''
            return 0
        }
        'call' {
            if ($tail.Count -eq 0) { W-Err '  Usage: arima mcp call <tool> key=value ...'; return 1 }
            return (Invoke-McpTool $tail[0] @(if ($tail.Count -gt 1) { $tail[1..($tail.Count - 1)] }))
        }
        'exec' {
            if ($tail.Count -eq 0) {
                W-Err '  Usage: arima mcp exec "<java code>"   or   arima mcp exec @file.java'
                return 1
            }
            # `exec @file` runs the file's contents -- use this whenever the code
            # contains quotes, since CMD cannot nest them.
            if ($tail.Count -eq 1 -and $tail[0].StartsWith('@')) {
                $path = $tail[0].Substring(1)
                if (-not (Test-Path -LiteralPath $path)) { W-Err "  File not found: $path"; return 1 }
                return (Invoke-McpTool 'barista_execute_code' @("code=@$path"))
            }
            return (Invoke-McpTool 'barista_execute_code' @("code=$($tail -join ' ')"))
        }
        'notebooks' { return (Invoke-McpTool 'barista_list_notebooks' @()) }
        'agents'    { return (Invoke-McpTool 'barista_list_agents' @()) }
        'read' {
            if ($tail.Count -eq 0) { W-Err '  Usage: arima mcp read <notebookId>'; return 1 }
            return (Invoke-McpTool 'barista_read_notebook' @("notebookId=$($tail[0])"))
        }
        'search' {
            if ($tail.Count -eq 0) { W-Err '  Usage: arima mcp search <query>'; return 1 }
            return (Invoke-McpTool 'barista_search_cells' @("query=$($tail -join ' ')"))
        }
        'run-agent' {
            if ($tail.Count -lt 2) { W-Err '  Usage: arima mcp run-agent <agentId> <task>'; return 1 }
            return (Invoke-McpTool 'barista_run_agent' @("agentId=$($tail[0])", "task=$($tail[1..($tail.Count - 1)] -join ' ')"))
        }
        'raw' {
            if (-not (Test-ServerUp)) { Show-McpDown; return 1 }
            if ($tail.Count -eq 0) { W-Err '  Usage: arima mcp raw ''{"jsonrpc":"2.0","id":1,"method":"tools/list"}'''; return 1 }
            try {
                $resp = Invoke-RestMethod -Method Post -Uri $McpUrl -ContentType 'application/json' `
                                          -Body ($tail -join ' ') -TimeoutSec 180 -ErrorAction Stop
                Write-Host ($resp | ConvertTo-Json -Depth 12)
                return 0
            } catch {
                W-Err "  MCP request failed: $_"
                return 1
            }
        }
        'config' {
            Write-Section 'MCP CLIENT CONFIGURATION'
            W-Dim '    Add this to your MCP client (Claude Desktop, Claude Code, custom agents):'
            Write-Host ''
            Write-Host @"
    {
      "mcpServers": {
        "arima-notebooks": {
          "url": "$Url/api/mcp/sse"
        }
      }
    }
"@
            Write-Host ''
            W-Dim  '    Claude Code, one-liner:'
            W-Dim  "        claude mcp add --transport sse arima-notebooks $Url/api/mcp/sse"
            Write-Host ''
            W-Dim  '    Arima must be running for the client to connect:  arima start -Bg'
            Write-Host ''
            return 0
        }
        default {
            W-Err "  Unknown mcp subcommand: $sub"
            W-Dim '  Run: arima mcp'
            return 1
        }
    }
}

# ── Subcommands: build ────────────────────────────────────────────────────────
function Cmd-Build {
    Show-Banner -Heading 'A R I M A   -   B U I L D'
    if (-not (Test-Maven)) { return 1 }
    Write-Step 1 1 'mvn clean package -DskipTests'
    Write-Host ''
    & (Get-MavenCommand) clean package -DskipTests
    if ($LASTEXITCODE -eq 0) {
        Write-Section 'BUILD SUCCESSFUL'
        Write-Row 'ok' 'JAR' $JarPath
        W-Dim  '  Run: arima start'
        Write-Host ''
        return 0
    }
    Write-Section 'BUILD FAILED'
    W-Err '  Check the Maven output above.'
    Write-Host ''
    return $LASTEXITCODE
}

function Cmd-Rebuild { return (Cmd-Build) }

# ── Subcommands: info ─────────────────────────────────────────────────────────
# ── Live server metadata (GET /api/system/info) ──────────────────────────────

function Get-ServerInfo {
    try {
        return Invoke-RestMethod -Uri "$Url/api/system/info" -TimeoutSec 3 -ErrorAction Stop
    } catch {
        return $null
    }
}

# Maps /api/system/info onto "state|Label|Value" rows. Returns $null when the
# server cannot be queried, so callers can fall back. arima.cmd shells out to
# `arima.ps1 _inforows` for these same rows rather than re-implementing the map.
function Get-LiveRows {
    $d = Get-ServerInfo
    if (-not $d -or $d.status -ne 'running') { return $null }

    $rows = [System.Collections.Generic.List[string]]::new()
    # ConvertFrom-Json turns the ISO buildTimestamp into a DateTime whose
    # rendering varies by host/culture; pin it so every launcher agrees.
    $built = if ($d.buildTimestamp -is [datetime]) {
        $d.buildTimestamp.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    } else { "$($d.buildTimestamp)" }
    $rows.Add("ok|Status|RUNNING at $($d.url)")
    $rows.Add("ok|Version|$($d.version)   (built $built)")
    $rows.Add("ok|Started|$($d.startedAt)   (up $($d.uptime))")
    $rows.Add("ok|Process|PID $($d.pid)   port $($d.port)   auth $($d.authMode)")
    $rows.Add("ok|Java|$($d.java.version)  --  $($d.java.vm)")
    $rows.Add("ok|OS|$($d.os.name) $($d.os.version) ($($d.os.arch))  --  $($d.os.cpus) CPUs")
    $rows.Add("ok|Memory|$($d.memory.usedMb) MB used  /  $($d.memory.totalMb) MB heap  /  $($d.memory.maxMb) MB max")
    $rows.Add("ok|Sessions|$($d.sessions.active) active JShell session(s)")
    $rows.Add("ok|Notebooks|$($d.notebooks.total) total  --  $($d.notebooks.tutorials) tutorials  ($($d.notebooks.dir)/)")

    $mcpState = if ($d.mcp.enabled) { 'enabled' } else { 'disabled' }
    $mcpMark  = if ($d.mcp.enabled) { 'ok' } else { 'warn' }
    $rows.Add("$mcpMark|MCP|$mcpState  --  protocol $($d.mcp.protocol)  --  $($d.mcp.messages)")

    $up   = @($d.languages | Where-Object { $_.available })
    $down = @($d.languages | Where-Object { -not $_.available })
    $langMark = if ($down.Count -eq 0) { 'ok' } else { 'warn' }
    $rows.Add("$langMark|Languages|$($up.Count)/$($d.languages.Count) ready  --  $(($up | ForEach-Object { $_.name }) -join ', ')")
    if ($down.Count -gt 0) {
        $rows.Add("warn|Disabled|" + (($down | ForEach-Object { "$($_.name) ($($_.detail))" }) -join ', '))
    }
    return $rows
}

function Show-LiveServer {
    $rows = Get-LiveRows
    if (-not $rows) {
        # The port answers but /api/system/info did not: an older build, or
        # oauth mode where /api/** requires a signed-in session.
        Write-Row 'ok' 'Status' "RUNNING at $Url"
        $procId = Get-ListeningPid
        if ($procId) { Write-Row 'ok' 'PID' "$procId" }
        Write-Row 'warn' 'Details' 'live metadata unavailable -- rebuild, or sign in if auth mode is oauth'
        return
    }
    foreach ($r in $rows) {
        $parts = $r -split '\|', 3
        Write-Row $parts[0] $parts[1] $parts[2]
    }
}
# ── Home screen (bare invocation) ────────────────────────────────────────────

function Show-Commands {
    Write-Section 'ALL COMMANDS'
    $rows = @(
        @('Lifecycle', 'install   update   uninstall'),
        @('Server',    'start   stop   restart   status   open   logs'),
        @('MCP',       'mcp   mcp tools   mcp call   mcp exec   mcp config'),
        @('Build',     'build   rebuild'),
        @('Info',      'version   welcome   docs   agents   brew   help'),
        @('Switches',  '-Bg  -Yes  -Purge  -NoBuild  -AddToPath  -SkipOptional  -NoAnim')
    )
    foreach ($r in $rows) {
        Write-Host ('    ' + $r[0].PadRight(11)) -ForegroundColor White -NoNewline
        W-Dim $r[1]
    }
    Write-Host ''
    W-Dim '    ./arima.ps1 help    full description of every command and switch'
}

# Ask a yes/no question that defaults to yes. Never blocks a non-interactive run.
function Confirm-Default-Yes {
    param([string] $Prompt)
    if ($Yes) { W-Dim "  (-Yes) $Prompt -> yes"; return $true }
    if (-not [Environment]::UserInteractive -or [Console]::IsInputRedirected) { return $false }
    Write-Host ''
    Write-Host "  $Prompt " -ForegroundColor Yellow -NoNewline
    Write-Host '[Y/n]: ' -ForegroundColor DarkGray -NoNewline
    $answer = Read-Host
    $answer = "$answer".Trim().ToLower()
    return ($answer -eq '' -or $answer -eq 'y' -or $answer -eq 'yes')
}

# First-run experience: nothing is built yet, so explain what Arima is and
# offer the one command that gets the user to a running server.
function Show-Fre {
    Write-Section 'FIRST RUN'
    W-Dim '    Arima Notebooks is not built on this machine yet.'
    Write-Host ''
    Write-Host '    What it is' -ForegroundColor White
    W-Dim '      A notebook you run on your own machine. Write cells in Java/JShell,'
    W-Dim '      JavaScript, TypeScript, C#, F#, C++ or Python; chain them into'
    W-Dim '      pipelines with //@ anchor and //@ depends; and drive the whole thing'
    W-Dim '      from any MCP client or an AI CLI.'
    W-Dim '      Local-first: no account, no telemetry, nothing leaves your machine.'

    Write-Section 'READINESS'
    Show-Runtimes -Verbose
    Write-Row 'err' 'Build' "$JarPath not built yet"

    Write-Section 'NEXT'
    W-Dim '    One command checks every dependency, installs what is missing, and builds:'
    Write-Host ''
    Write-Host '        ./arima.ps1 install' -ForegroundColor White
    W-Dim  '        ./arima.ps1 start        then start the server and open the UI'
    Write-Host ''

    if (Confirm-Default-Yes 'Install everything that is missing and start Arima now?') {
        Write-Host ''
        $rc = Cmd-Install
        if ($rc -ne 0) { return $rc }
        Write-Host ''
        return (Cmd-Start)
    }
    Write-Host ''
    if (-not [Console]::IsInputRedirected) {
        W-Dim '    Nothing installed. Run  ./arima.ps1 install  when you are ready.'
    }
    Write-Host ''
    return 0
}

# Bare `./arima.ps1` with no subcommand.
function Cmd-Home {
    Show-Banner
    Write-Host "  $Tagline" -ForegroundColor Cyan

    if (Test-ServerUp) {
        Write-Section 'LIVE SERVER'
        Show-LiveServer
        Show-Commands
        Write-Host ''
        W-Info "  Open it: $Url"
        Write-Host ''
        return 0
    }

    if (-not (Test-Path $JarPath)) {
        return (Show-Fre)
    }

    Write-Section 'SERVER'
    Write-Row 'warn' 'State' 'STOPPED'
    Write-Row 'ok'   'Build' $JarPath
    Show-Commands
    Write-Section 'NEXT'
    Write-Host '    ./arima.ps1 start' -ForegroundColor White -NoNewline
    W-Dim "        start the server and open $Url"
    W-Dim '    ./arima.ps1 start -Bg   start it detached, logging to arima.log'
    Write-Host ''
    return 0
}


function Cmd-Version {
    Show-Banner -Heading 'A R I M A   -   V E R S I O N'
    Write-Section 'VERSIONS'
    # Read the version off the JAR name -- that is the artifact we actually
    # launch, and it stays correct without parsing pom.xml's nested <version>s.
    Write-Row 'ok' 'Arima' ([IO.Path]::GetFileNameWithoutExtension($JarPath) -replace '^arima-notebooks-', '')
    Show-Runtimes -Verbose
    Write-Host ''
    return 0
}

function Cmd-Welcome {
    $copilots = Set-BaristaAiContext
    Show-Brew
    Show-Banner
    W-Title  '  Welcome to Arima Notebooks'
    W-Dim    '  A local notebook for Java, JShell, JS, TS, C#, F#, C++ and Python'
    W-Dim    '  -- with AI co-pilots and a built-in MCP server.'

    Write-Section 'PICK HOW YOU WANT TO WORK'
    Write-Host '    1) Open the UI            ' -ForegroundColor White -NoNewline; W-Dim 'full notebook experience in your browser'
    W-Dim    "         arima start          ->  $Url"
    Write-Host '    2) Drive Arima over MCP   ' -ForegroundColor White -NoNewline; W-Dim 'operate & automate from any MCP client'
    W-Dim    "         SSE  $Url/api/mcp/sse"
    W-Dim    "         POST $Url/api/mcp/messages"
    Write-Host '    3) Personalize & extend   ' -ForegroundColor White -NoNewline; W-Dim 'add features -- needs an agentic CLI'
    W-Dim    '         run  claude  /  copilot  /  agy   in this folder, then ask the arima agent'

    Write-Section 'AI CO-PILOTS'
    Show-AiCopilots -Copilots $copilots

    Write-Section 'THE ONE DIFFERENCE'
    W-Dim    '    This arima CLI operates & automates Arima (incl. MCP) but cannot change its code.'
    W-Dim    '    An agentic CLI (claude / copilot / agy) can ALSO personalize and extend Arima.'

    Write-Section 'NEXT'
    W-Plain  '    arima install    Check prerequisites, install what is missing, build'
    W-Plain  '    arima start      Start the server and open the UI'
    W-Plain  '    arima docs       Open the brochure and list the docs'
    W-Plain  '    arima agents     AI co-pilots, skills & the arima agent'
    Write-Host ''
    W-Dim    '  Full welcome: docs/WELCOME.md'
    Write-Host ''
    return 0
}

function Cmd-Docs {
    $brochure = Join-Path $RepoRoot (Join-Path 'docs' (Join-Path 'brochure' 'arima-brochure.pdf'))
    Show-Banner -Heading 'A R I M A   -   D O C S'
    Write-Section 'DOCUMENTATION'
    W-Plain  '    Brochure (PDF)   docs/brochure/arima-brochure.pdf'
    W-Plain  '    Welcome          docs/WELCOME.md'
    W-Plain  '    Getting started  README.md'
    W-Plain  '    Architecture     docs/ARCHITECTURE.md'
    W-Plain  '    API + MCP        docs/API.md'
    W-Plain  '    Contributor      CONTRIBUTING.md  +  AGENTS.md'
    W-Plain  '    Cheat sheet      docs/cheatsheet.html'
    Write-Host ''
    if (Test-ServerUp) {
        W-Dim "    In the running app: open the in-UI docs overlay at $Url"
    }
    if (Test-Path $brochure) {
        W-Ok  '    Opening the brochure...'
        Start-Process $brochure
    } else {
        W-Warn '    Brochure PDF not found -- open docs/brochure/arima-brochure.html in a browser.'
    }
    Write-Host ''
    return 0
}

function Cmd-Agents {
    $copilots = Set-BaristaAiContext
    Show-Banner -Heading 'A R I M A   -   A G E N T S' -Lines @(
        ('-' * 42),
        'AI co-pilots, guardrails, skills and subagents',
        'wired into this repository.',
        '',
        ''
    )

    Write-Section 'DETECTED AI CLIs'
    if ($copilots.Count -gt 0) {
        foreach ($name in $copilots.Keys) { Write-Row 'ok' $name "binary: $($copilots[$name])" }
    } else {
        Write-Row 'warn' 'none' 'install one of the following:'
        W-Dim  '      Claude     :  https://claude.ai/code                      then  claude auth'
        W-Dim  '      Copilot    :  GitHub Copilot CLI (copilot, v1.0.55-5+; used by the Copilot SDK)'
        W-Dim  '      Antigravity:  https://antigravity.google/docs/cli-install  then  run  agy'
    }

    Write-Section 'GUARDRAILS (read automatically by every AI CLI in this repo)'
    $g = @(@('AGENTS.md', $AgentsGuide), @('.claude/skills', $SkillsDir), @('.claude/agents', $AgentsDir),
           @('CLAUDE.md', (Join-Path $RepoRoot 'CLAUDE.md')),
           @('.github/copilot-instructions.md', (Join-Path $RepoRoot (Join-Path '.github' 'copilot-instructions.md'))),
           @('GEMINI.md', (Join-Path $RepoRoot 'GEMINI.md')))
    foreach ($pair in $g) {
        if (Test-Path $pair[1]) { Write-Row 'ok' $pair[0] '' } else { Write-Row 'warn' $pair[0] 'missing' }
    }

    Write-Section 'SKILLS (auto-invoke when your request matches)'
    if (Test-Path $SkillsDir) {
        Get-ChildItem -Path $SkillsDir -Directory | ForEach-Object { W-Dim "      $($_.Name)" }
    }

    Write-Section 'SUBAGENTS (spawn explicitly for a focused review)'
    if (Test-Path $AgentsDir) {
        Get-ChildItem -Path $AgentsDir -Filter '*.md' | ForEach-Object { W-Dim "      $($_.BaseName)" }
    }

    Write-Section 'HOW TO USE'
    W-Plain '    In the Arima UI : open the AI panel (Ctrl+\), pick a provider, ask away.'
    W-Plain '    In a terminal   : run your CLI from this folder so it loads AGENTS.md:'
    W-Dim   '                        claude        (or)   copilot        (or)   agy'
    W-Plain '    Example prompt  : "Add a Kotlin execution mode following CppExecutionService,'
    W-Dim   '                       then run ./scripts/security-check.ps1 and open a PR."'
    Write-Host ''
    W-Dim   '    Env exported for this session:'
    W-Dim   "      BARISTA_HOME=$env:BARISTA_HOME"
    W-Dim   "      BARISTA_AGENTS_GUIDE=$env:BARISTA_AGENTS_GUIDE"
    W-Dim   "      BARISTA_SKILLS_DIR=$env:BARISTA_SKILLS_DIR"
    W-Dim   "      BARISTA_AGENTS_DIR=$env:BARISTA_AGENTS_DIR"
    Write-Host ''
    return 0
}

function Cmd-Help {
    Show-Banner -Heading 'A R I M A   N O T E B O O K S   C L I' -Lines @(
        ('-' * 42),
        'Java  JShell  JS  TS  C#  F#  C++  Python',
        'Usage:  ./arima.ps1 [command] [switches]',
        "Server: $Url",
        ''
    )

    Write-Section 'START HERE'
    W-Plain '    (no command)     Home screen -- live server metadata when it is running,'
    W-Dim   '                     the full command list, or first-run setup if not built yet'

    Write-Section 'LIFECYCLE'
    W-Plain '    install          Check every dependency, install what is missing, build, report readiness'
    W-Plain '    update           Sync with upstream (fast-forward or rebase), then rebuild'
    W-Plain '    uninstall        Remove build output and logs (notebooks are kept)'

    Write-Section 'SERVER'
    W-Plain '    start            Start the server, auto-build if needed, open the browser'
    W-Plain '    start -Bg        Start detached; logs to arima.log'
    W-Plain '    stop             Stop the running server'
    W-Plain '    restart          Stop then start (use after `update`)'
    W-Plain '    status           Server state, PID, runtimes, AI CLIs, checkout state'
    W-Plain '    open [file]      Open the browser, or open a .anb notebook file'
    W-Plain '    register         Associate .anb files with Arima Notebooks'
    W-Plain '    unregister       Remove the .anb file association'
    W-Plain '    logs             Tail arima.log (background mode only)'

    Write-Section 'MCP (drive Arima from this terminal)'
    W-Plain '    mcp              Endpoints, live server info, and the command list'
    W-Plain '    mcp tools        List every MCP tool and its parameters'
    W-Plain '    mcp call <tool> k=v ...   Call any tool by name'
    W-Plain '    mcp exec "<code>"         Run Java/JShell code through MCP'
    W-Plain '    mcp notebooks | read <id> | search <q> | agents | run-agent <id> <task>'
    W-Plain '    mcp config       Print an MCP client config snippet'

    Write-Section 'BUILD'
    W-Plain '    build            mvn clean package -DskipTests'
    W-Plain '    rebuild          Alias of build (always a clean build)'

    Write-Section 'INFO'
    W-Plain '    version          Arima version plus every detected runtime'
    W-Plain '    welcome          Pick how you want to work: UI, MCP, or extend'
    W-Plain '    docs             Open the brochure and list the documentation'
    W-Plain '    agents           AI co-pilots, guardrails, skills & subagents'
    W-Plain '    brew             Watch Barista serve a coffee bean (alias: coffee)'
    W-Plain '    help             Show this help'

    Write-Section 'SWITCHES'
    W-Plain '    -Bg              start detached, logging to arima.log'
    W-Plain '    -Yes             skip confirmation prompts (uninstall)'
    W-Plain '    -Purge           uninstall: also delete data/ (settings, packages, users)'
    W-Plain '    -NoBuild         install/update: skip the Maven build'
    W-Plain '    -AddToPath       install/uninstall: add or remove this folder on the user PATH'
    W-Plain '    -SkipOptional    install: only install the required tools (Java + Maven)'
    W-Plain '    -NoAnim          disable the animated banner and spinners'

    Write-Section 'EXAMPLES'
    W-Dim '    ./arima.ps1                        home screen: live status + every command'
    W-Dim '    ./arima.ps1 install -Yes           install every missing dependency, no prompts'
    W-Dim '    ./arima.ps1 install -AddToPath     first-time setup, then `arima` from anywhere'
    W-Dim '    ./arima.ps1 update                 pull upstream, rebase local commits, rebuild'
    W-Dim '    ./arima.ps1 restart                stop, then start again'
    W-Dim '    ./arima.ps1 mcp exec "2+2"         run a snippet through the MCP server'
    W-Dim '    ./arima.ps1 uninstall -Purge -Yes  full clean, no prompts'
    Write-Host ''
    W-Info "  URL: $Url"
    Write-Host ''
    return 0
}

# ── Dispatch ──────────────────────────────────────────────────────────────────
switch ($Command.ToLower()) {
    'home'      { exit (Cmd-Home) }
    # Undocumented: arima.cmd renders these same rows in batch.
    '_inforows' { $r = Get-LiveRows; if (-not $r) { exit 1 }; $r | ForEach-Object { Write-Output $_ }; exit 0 }
    'install'   { exit (Cmd-Install) }
    'update'    { exit (Cmd-Update) }
    'upgrade'   { exit (Cmd-Update) }
    'uninstall' { exit (Cmd-Uninstall) }
    'start'     { exit (Cmd-Start) }
    'stop'      { Show-Banner -Heading 'A R I M A   -   S T O P'; exit (Cmd-Stop) }
    'restart'   { exit (Cmd-Restart) }
    'status'    { exit (Cmd-Status) }
    'open'       { exit (Cmd-Open ($Rest | Select-Object -First 1)) }
    'register'   { exit (Cmd-Register) }
    'unregister' { exit (Cmd-Unregister) }
    'logs'      { exit (Cmd-Logs) }
    'build'     { exit (Cmd-Build) }
    'rebuild'   { exit (Cmd-Rebuild) }
    'mcp'       { exit (Cmd-Mcp $Rest) }
    'brew'      { Show-Brew; Show-Banner; exit 0 }
    'coffee'    { Show-Brew; Show-Banner; exit 0 }
    'version'   { exit (Cmd-Version) }
    '--version' { exit (Cmd-Version) }
    'agents'    { exit (Cmd-Agents) }
    'ai'        { exit (Cmd-Agents) }
    'welcome'   { exit (Cmd-Welcome) }
    'docs'      { exit (Cmd-Docs) }
    'help'      { exit (Cmd-Help) }
    '-h'        { exit (Cmd-Help) }
    '--help'    { exit (Cmd-Help) }
    default {
        W-Err "Unknown command: $Command"
        W-Dim 'Run: ./arima.ps1 help'
        exit 1
    }
}
