#Requires -Version 5.1
<#
.SYNOPSIS
  Arima Notebooks one-file installer for Windows.

.DESCRIPTION
  Downloads, builds and registers Arima Notebooks on a machine that has never
  seen it. Nothing is touched until it has told you what it is about to do and
  you have said yes.

  It shares its look, its animation vocabulary and its wording with the three
  launchers it installs (arima.cmd, arima.ps1, arima.sh) -- the frames below are
  copied from arima.ps1 on purpose, because this file has to stand on its own
  before the repository exists.

  Run it straight from the web:
    irm https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.ps1 | iex

  With flags (Invoke-Expression cannot forward arguments, so build a scriptblock):
    & ([scriptblock]::Create((irm https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.ps1))) -Yes

.EXAMPLE
  ./install.ps1 -CheckOnly     # explain, show the dependency table, change nothing
  ./install.ps1 -Yes           # unattended (CI)
  ./install.ps1 -Dir D:\Arima  # install somewhere else
#>

[CmdletBinding()]
param(
    # Skip every confirmation prompt. Intended for CI.
    [switch] $Yes,

    # Explain, probe the toolchain, print the table -- then stop. Changes nothing.
    [switch] $CheckOnly,
    # Full clone plus the optional language runtimes, for working ON Arima
    [switch] $Dev,

    # Only require/offer Java, Maven and Git; leave the optional runtimes alone.
    [switch] $SkipOptional,

    # Do not add the install directory to the user PATH.
    [switch] $NoPath,

    # Forget any recorded progress and run every step again.
    [switch] $Reset,

    # Disable the animated banner, brew frames and spinners.
    [switch] $NoAnim,

    # Where Arima Notebooks lands. Default: %LOCALAPPDATA%\Arima
    [string] $Dir,

    [string] $Repo   = 'https://github.com/snchande/arima-notebooks.git',
    [string] $Branch = 'master'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProductName = 'Arima Notebooks'
$IssuesUrl   = 'https://github.com/snchande/arima-notebooks/issues/new'
$RawBase     = 'https://raw.githubusercontent.com/snchande/arima-notebooks/master'
$Port        = 8585
$Url         = "http://localhost:$Port"
$MinJava     = 17    # the JAR's real floor (see arima.ps1)
$WantJava    = 21    # what we install when Java is missing

$HomeDir   = if ($env:USERPROFILE) { $env:USERPROFILE } else { $HOME }
$StateFile = Join-Path $HomeDir '.arima-install-state'

if (-not $Dir) {
    $Dir = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Arima' }
           else                   { Join-Path $HomeDir 'Arima' }
}

# -- Shared look & feel (mirrors arima.ps1) -----------------------------------
# Every glyph is plain ASCII: the installer runs under whatever console code
# page the machine happens to have, long before it can fix that.
$Script:Anim = (-not $NoAnim) -and (-not [Console]::IsOutputRedirected)
$Script:Rule = '  ' + ('-' * 60)
$SpinFrames  = @('-', '\', '|', '/')

function W-Info  ($t) { Write-Host $t -ForegroundColor Cyan }
function W-Ok    ($t) { Write-Host $t -ForegroundColor Green }
function W-Warn  ($t) { Write-Host $t -ForegroundColor Yellow }
function W-Err   ($t) { Write-Host $t -ForegroundColor Red }
function W-Dim   ($t) { Write-Host $t -ForegroundColor DarkGray }
function W-Plain ($t) { Write-Host $t }

function Frame-Pause ([int] $Ms) { if ($Script:Anim) { Start-Sleep -Milliseconds $Ms } }

function Clear-Line {
    if ($Script:Anim) { Write-Host ("`r" + (' ' * 72) + "`r") -NoNewline }
}

function Write-Section ($Name) {
    Write-Host ''
    W-Info  "  $Name"
    W-Dim   $Script:Rule
}

function Write-Step ($Index, $Total, $Text) {
    Write-Host ''
    Write-Host "  [$Index/$Total] " -ForegroundColor Cyan -NoNewline
    Write-Host " $Text" -ForegroundColor White
}

function Write-Row ($State, $Label, $Detail) {
    $pad = "$Label".PadRight(13)
    switch ($State) {
        'ok'    { Write-Host '    [ok] ' -ForegroundColor Green    -NoNewline }
        'warn'  { Write-Host '    [--] ' -ForegroundColor Yellow   -NoNewline }
        'err'   { Write-Host '    [!!] ' -ForegroundColor Red      -NoNewline }
        default { Write-Host '    [..] ' -ForegroundColor DarkGray -NoNewline }
    }
    Write-Host $pad -ForegroundColor White -NoNewline
    W-Dim $Detail
}

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

# -- The Barista brew animation (identical frames to arima.ps1 / arima.sh) ----
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
    if (-not $Script:Anim) {
        Write-Host ''
        Write-BrewFrame $BrewFrames[-2]
        return
    }
    Write-Host ''
    $height   = $BrewFrames[0].Cup.Count + 1
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

# -- Probes -------------------------------------------------------------------
function Have ($Bin) { [bool](Get-Command $Bin -ErrorAction SilentlyContinue) }

# java/mvn/dotnet print their banners on stderr. With $ErrorActionPreference =
# 'Stop' PowerShell turns every redirected stderr line into a terminating
# NativeCommandError, so relax both knobs for the duration of the call.
function Invoke-NativeOut {
    param([Parameter(Mandatory = $true)][scriptblock] $Command)
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $nativeVar = Get-Variable -Name PSNativeCommandUseErrorActionPreference `
                              -Scope Global -ErrorAction SilentlyContinue
    $oldNative = $null
    if ($nativeVar) {
        $oldNative = $nativeVar.Value
        $global:PSNativeCommandUseErrorActionPreference = $false
    }
    try {
        return @(& $Command 2>&1 | ForEach-Object { "$_" })
    } catch {
        return @()
    } finally {
        $ErrorActionPreference = $oldEap
        if ($nativeVar) { $global:PSNativeCommandUseErrorActionPreference = $oldNative }
    }
}

function Get-JavaVersionLine {
    if (-not (Have java)) { return '' }
    return (Invoke-NativeOut { java -version } | Select-Object -First 1 | Out-String).Trim()
}

# "21.0.2" / "1.8.0_392" out of the java -version banner -> major int.
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
    return ''
}

function Get-CppBin {
    foreach ($b in @('cl', 'g++', 'clang++')) { if (Have $b) { return $b } }
    return ''
}

# -- Dependency catalogue -----------------------------------------------------
# Required tools block the install. Optional ones only disable the cell modes
# that depend on them -- Arima starts and runs perfectly well without any.
function Get-Dependencies {
    @(
        [pscustomobject]@{ Key='java';   Label='Java';    Required=$true;  Winget="EclipseAdoptium.Temurin.$WantJava.JDK";
                           Url='https://adoptium.net/';                         Note="JDK $WantJava - runs the server and JShell cells" }
        # Maven is NOT required and NOT installable here: it is not published to
        # winget at all. The repository ships a Maven Wrapper (mvnw), so the build
        # needs only a JDK. A system Maven is used if present, purely as a shortcut.
        [pscustomobject]@{ Key='mvn';    Label='Maven';   Required=$false; Winget='';
                           Url='https://maven.apache.org/';                     Note='optional - the bundled mvnw wrapper is used when absent' }
        [pscustomobject]@{ Key='git';    Label='Git';     Required=$true;  Winget='Git.Git';
                           Url='https://git-scm.com/';                          Note='clones the repository and powers: arima update' }
        [pscustomobject]@{ Key='node';   Label='Node.js'; Required=$false; Winget='OpenJS.NodeJS.LTS';
                           Url='https://nodejs.org/';                           Note='JavaScript + TypeScript cells, npm packages' }
        [pscustomobject]@{ Key='dotnet'; Label='.NET';    Required=$false; Winget='Microsoft.DotNet.SDK.9';
                           Url='https://dot.net/';                              Note='C# + F# cells, NuGet packages' }
        [pscustomobject]@{ Key='python'; Label='Python';  Required=$false; Winget='Python.Python.3.13';
                           Url='https://www.python.org/downloads/';             Note='Python cells, PyPI packages' }
        [pscustomobject]@{ Key='cpp';    Label='C++';     Required=$false; Winget='Microsoft.VisualStudio.2022.BuildTools';
                           Url='https://visualstudio.microsoft.com/downloads/'; Note='C++ cells (LARGE download, several GB)' }
    )
}

# JShell is a JDK tool, so a bare JRE does not count however new it is.
function Test-Dependency ($Dep) {
    switch ($Dep.Key) {
        'java'   { return ((Get-JavaMajor) -ge $MinJava) -and (Have javac) }
        'python' { return [bool](Get-PythonBin) }
        'cpp'    { return [bool](Get-CppBin) }
        default  { return (Have $Dep.Key) }
    }
}

function Get-DependencyDetail ($Dep) {
    switch ($Dep.Key) {
        'java'   {
            $line  = Get-JavaVersionLine
            $major = Get-JavaMajor
            if (-not $line)        { return 'NOT FOUND' }
            if (-not (Have javac)) { return "$line -- JRE only, a JDK is required" }
            if ($major -lt $MinJava) { return "$line -- JDK $MinJava+ required" }
            if ($major -lt $WantJava) { return "$line -- works; JDK $WantJava recommended" }
            return $line
        }
        'mvn'    { $m = (Invoke-NativeOut { mvn --version } | Select-String 'Apache Maven' | Select-Object -First 1)
                   return $(if ($m) { $m.Line.Trim() } else { 'found' }) }
        'git'    { return ((Invoke-NativeOut { git --version } | Select-Object -First 1) -replace 'git version ', 'v') }
        'node'   { return (Invoke-NativeOut { node --version } | Select-Object -First 1) }
        'dotnet' { return (Invoke-NativeOut { dotnet --version } | Select-Object -First 1) }
        'python' { $b = Get-PythonBin; if (-not $b) { return 'NOT FOUND' }
                   return (Invoke-NativeOut { & $b --version } | Select-Object -First 1) }
        'cpp'    { $c = Get-CppBin; return $(if ($c) { $c } else { 'NOT FOUND' }) }
        default  { return 'found' }
    }
}

# winget writes into the machine/user PATH but not into the *current* process,
# so re-read both scopes before probing the freshly installed tool again.
function Update-PathFromRegistry {
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

function Confirm-Action {
    param([string] $Prompt, [string] $Expect = 'yes')
    if ($Yes) { W-Dim "  (-Yes) $Prompt -> $Expect"; return $true }
    Write-Host ''
    Write-Host "  $Prompt " -ForegroundColor Yellow -NoNewline
    Write-Host "[type '$Expect' to confirm]: " -ForegroundColor DarkGray -NoNewline
    $answer = Read-Host
    return ($answer.Trim().ToLower() -eq $Expect)
}

# -- Resumable state ----------------------------------------------------------
# A handful of key=value lines. Same format as install.sh writes, so a machine
# that dual-boots or runs both shells reads one file either way.
$Script:State = @{}

function Read-State {
    $map = @{}
    if (-not (Test-Path -LiteralPath $StateFile)) { return $map }
    foreach ($line in (Get-Content -LiteralPath $StateFile -ErrorAction SilentlyContinue)) {
        if ($line -match '^\s*#') { continue }
        # 'schema' is re-emitted by Save-State, so keeping it here would double it.
        if ($line -match '^([A-Za-z0-9_]+)=(.*)$' -and $Matches[1] -ne 'schema') {
            $map[$Matches[1]] = $Matches[2]
        }
    }
    return $map
}

function Save-State {
    if ($CheckOnly) { return }
    $lines = @(
        "# $ProductName installer state -- delete this file to start over",
        'schema=1'
    )
    foreach ($k in ($Script:State.Keys | Sort-Object)) {
        $lines += "$k=$($Script:State[$k])"
    }
    Set-Content -LiteralPath $StateFile -Value $lines -Encoding ASCII
}

function Get-Completed {
    if (-not $Script:State.ContainsKey('completed')) { return @() }
    return @($Script:State['completed'] -split ',' | Where-Object { $_ })
}

function Test-StepDone ($Key) { return ((Get-Completed) -contains $Key) }

function Set-StepDone ($Key) {
    $done = @(Get-Completed)
    if ($done -notcontains $Key) { $done += $Key }
    $Script:State['completed'] = ($done -join ',')
    $Script:State.Remove('failed_step')
    $Script:State.Remove('failed_reason')
    Save-State
}

function Get-ResumeCommand {
    $flags = @()
    $defaultDir = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Arima' } else { Join-Path $HomeDir 'Arima' }
    if ($Dir -ne $defaultDir) { $flags += "-Dir '$Dir'" }
    if ($SkipOptional) { $flags += '-SkipOptional' }
    if ($NoPath)       { $flags += '-NoPath' }
    if ($Yes)          { $flags += '-Yes' }
    $tail = if ($flags.Count) { ' ' + ($flags -join ' ') } else { '' }

    # $PSCommandPath is empty when the script arrived through `irm | iex`.
    if ($PSCommandPath) { return "powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`"$tail" }
    if ($tail) { return "& ([scriptblock]::Create((irm $RawBase/install.ps1)))$tail" }
    return "irm $RawBase/install.ps1 | iex"
}

# -- Diagnostics for the issue tracker ----------------------------------------
function Show-Diagnostics ($StepKey, $Reason) {
    Write-Section 'WHAT TO DO NEXT'
    W-Err  "  Step '$StepKey' failed."
    W-Dim  "  Reason: $Reason"
    Write-Host ''
    W-Plain '  Nothing further was changed. Fix the cause and resume with:'
    W-Info  "      $(Get-ResumeCommand)"
    Write-Host ''
    W-Plain "  Still stuck? File an issue -- it takes a minute and we read all of them:"
    W-Info  "      $IssuesUrl"
    Write-Host ''
    W-Plain '  Copy this block into the issue:'
    W-Dim   $Script:Rule

    $os   = try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { 'Windows' }
    $arch = if ([Environment]::Is64BitOperatingSystem) { 'x64' } else { 'x86' }
    $lines = [ordered]@{
        'failed step' = $StepKey
        'reason'      = $Reason
        'os'          = "$os ($([Environment]::OSVersion.Version)) $arch"
        'powershell'  = "$($PSVersionTable.PSVersion) ($($PSVersionTable.PSEdition))"
        'install dir' = $Dir
        'state file'  = $StateFile
    }
    foreach ($k in $lines.Keys) { W-Dim ("    {0,-12} {1}" -f $k, $lines[$k]) }
    foreach ($d in (Get-Dependencies)) {
        $mark = if (Test-Dependency $d) { ' ' } else { '!' }
        W-Dim ("    {0,-12} {1} {2}" -f $d.Label.ToLower(), $mark, (Get-DependencyDetail $d))
    }
    W-Dim  $Script:Rule
    Write-Host ''
}

function Stop-Install ($StepKey, $Reason) {
    $Script:State['failed_step']   = $StepKey
    $Script:State['failed_reason'] = $Reason
    $Script:State['failed_at']     = (Get-Date).ToString('s')
    Save-State
    Show-Diagnostics $StepKey $Reason
    return 1
}

# -- Phase 0: explain, then ask -----------------------------------------------
function Show-Explanation {
    Write-Section 'WHAT ARIMA NOTEBOOKS IS'
    W-Plain '    A local-first, AI-native notebook for eight languages. JavaScript,'
    W-Plain '    TypeScript, C#, F#, C++, Java, JShell and Python run side by side as'
    W-Plain '    equals in one browser-based workspace -- real compilers, real package'
    W-Plain '    managers (Maven, npm, NuGet, PyPI), real dependency pipelines.'
    Write-Host ''
    W-Plain '    Three AI co-pilots (Claude, GitHub Copilot, Antigravity) plug in through'
    W-Plain '    CLIs you already have, and the whole system is exposed over MCP so any'
    W-Plain '    agent can drive the same notebook you are editing.'
    Write-Host ''
    W-Plain '    Everything runs on your machine. No cloud account, no sign-up, no'
    W-Plain '    telemetry, and nothing leaves this computer.'

    if ($Dev) {
        W-Plain ''
        W-Plain '    Running in DEVELOPER mode: full history and every language runtime, for'
        W-Plain '    working ON Arima. Drop -Dev if you only want to use it.'
    }

    Write-Section 'WHAT THIS SCRIPT WILL DO'
    W-Plain '    1. Check your toolchain and show you a table before touching anything.'
    W-Plain '    2. Offer to install the missing pieces, one at a time, with winget.'
    W-Plain '       Nothing is installed that you have not said yes to.'
    W-Plain "    3. Clone $Repo"
    if ($Dev) {
        W-Plain "       into $Dir with full history, so you can branch and open pull requests."
    } else {
        W-Plain "       into $Dir (a shallow clone; fast-forwards it if already there)."
    }
    W-Plain '    4. Build the JAR by handing over to the repository''s own `arima.ps1 install`,'
    W-Plain '       which prepares data/, notebooks/ and logs/. The build uses the bundled'
    W-Plain '       Maven Wrapper, so a JDK is all you need - Maven itself is not required.'

    Write-Section 'WHAT IT WILL CHANGE'
    Write-Row 'warn' 'Disk'  "$Dir  (roughly 400 MB once built)"
    Write-Row 'warn' 'Disk'  "$StateFile  (progress, so a failed run can resume)"
    if ($NoPath) {
        Write-Row 'ok'   'PATH'  'left untouched (-NoPath)'
    } else {
        Write-Row 'warn' 'PATH'  "adds $Dir to your USER PATH -- new terminals only"
    }
    Write-Row 'warn' 'System' 'only the winget packages you approve in step 2'
    Write-Row 'ok'   'Files'  '.anb associations are NOT changed -- run `arima register` later if you want them'
    Write-Row 'ok'   'Admin'  'not required; nothing is installed machine-wide by this script itself'
    Write-Host ''
    W-Dim   '    Undo later with `arima uninstall`, or by deleting the folder above.'
}

# -- Step 1: dependency check -------------------------------------------------
# Always runs. Probing is free and mutates nothing, so resuming never skips it:
# the later steps need to know what is actually on the machine right now.
function Invoke-DepCheck {
    Write-Bar 20 'probing toolchains'
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
    Write-Bar 100 'toolchain probed'
    if ($SkipOptional -and -not $Dev) { $missing = @($missing | Where-Object { $_.Required }) }
    Write-Host ''
    $req = @($missing | Where-Object { $_.Required })
    if ($req.Count -eq 0) {
        W-Ok  '    Everything required is present.'
    } else {
        W-Warn "    $($req.Count) required tool(s) missing -- Arima cannot be built until they are installed."
    }
    return ,@($missing)
}

# -- Step 2: install the missing pieces, one at a time ------------------------
function Install-One ($Dep, $Index, $Count) {
    Write-Host ''
    Write-Bar ([int](100 * ($Index - 1) / $Count)) "installing $($Dep.Label) ($Index of $Count)"
    Write-Host ''
    W-Dim "         winget install --id $($Dep.Winget)"
    Invoke-NativeOut {
        & winget install --id $Dep.Winget --exact --silent `
            --accept-package-agreements --accept-source-agreements --disable-interactivity
    } | ForEach-Object { W-Dim "         $_" }
    Update-PathFromRegistry
    if (Test-Dependency $Dep) {
        Write-Bar ([int](100 * $Index / $Count)) "$($Dep.Label) installed"
        Write-Row 'ok' $Dep.Label "installed -- $(Get-DependencyDetail $Dep)"
        return $true
    }
    Write-Bar ([int](100 * $Index / $Count)) "$($Dep.Label) not confirmed"
    Write-Row 'err' $Dep.Label "not visible after install -- try a NEW terminal, or install by hand: $($Dep.Url)"
    return $false
}

function Invoke-DepInstall ($Missing) {
    if ($Missing.Count -eq 0) {
        Write-Bar 100 'nothing to install'
        Write-Row 'ok' 'Dependencies' 'all present'
        return $true
    }
    if (-not (Have winget)) {
        Write-Bar 100 'winget unavailable'
        Write-Row 'err' 'winget' 'not available on this machine -- install these by hand:'
        foreach ($d in $Missing) { W-Dim "           $($d.Label.PadRight(9)) $($d.Url)" }
        return (@($Missing | Where-Object { $_.Required }).Count -eq 0)
    }

    Write-Host ''
    W-Warn '  These will be installed with winget, one at a time:'
    foreach ($d in $Missing) {
        $why = if ($d.Required) { 'required' } else { 'optional' }
        W-Dim "      $($d.Label.PadRight(9)) $($d.Winget.PadRight(46)) ($why)"
    }
    if (-not (Confirm-Action 'Install the packages listed above?')) {
        Write-Host ''
        W-Warn '  Skipped -- no packages were installed.'
        W-Dim  '  Re-run with -Yes to install without prompting, or -SkipOptional for the minimum.'
        return (@($Missing | Where-Object { $_.Required }).Count -eq 0)
    }

    $i = 0
    $failed = @()
    foreach ($d in $Missing) {
        $i++
        if (-not (Install-One $d $i $Missing.Count)) { $failed += $d }
    }
    Update-PathFromRegistry

    $failedRequired = @($failed | Where-Object { $_.Required })
    if ($failedRequired.Count -gt 0) {
        Write-Host ''
        W-Err '  Required tools could not be installed:'
        foreach ($d in $failedRequired) { W-Dim "      $($d.Label.PadRight(9)) $($d.Url)" }
        return $false
    }
    if ($failed.Count -gt 0) {
        Write-Host ''
        W-Warn '  Optional runtimes did not install -- Arima will still run without them:'
        foreach ($d in $failed) { W-Dim "      $($d.Label.PadRight(9)) $($d.Note)" }
    }
    return $true
}

# -- Step 3: fetch the repository ---------------------------------------------
# Runs git detached so the shared spinner can report progress; git's own output
# is kept in a log we print only when it fails.
function Invoke-Spun {
    param([string] $Exe, [string[]] $Arguments, [string] $Label, [string] $LogBase)
    $out = "$LogBase.out"
    $err = "$LogBase.err"
    $p = Start-Process -FilePath $Exe -ArgumentList $Arguments -NoNewWindow -PassThru `
                       -RedirectStandardOutput $out -RedirectStandardError $err
    $i = 0
    while (-not $p.HasExited) {
        if ($Script:Anim) {
            $frame = $SpinFrames[$i % $SpinFrames.Count]
            Write-Host ("`r  [$frame]  $Label ...   ") -ForegroundColor DarkGray -NoNewline
        }
        $i++
        Start-Sleep -Milliseconds 250
    }
    Clear-Line
    return $p.ExitCode
}

function Get-SpunLog ($LogBase) {
    $text = @()
    foreach ($f in @("$LogBase.out", "$LogBase.err")) {
        if (Test-Path -LiteralPath $f) {
            $text += @(Get-Content -LiteralPath $f -ErrorAction SilentlyContinue)
        }
    }
    return @($text | Where-Object { $_ } | Select-Object -Last 12)
}

function Invoke-Fetch {
    $logBase = Join-Path ([IO.Path]::GetTempPath()) "arima-install-$PID"
    $gitDir  = Join-Path $Dir '.git'

    if (Test-Path -LiteralPath $gitDir) {
        Write-Bar 30 'existing checkout found'
        Write-Row 'ok' 'Checkout' "$Dir (already cloned)"
        $code = Invoke-Spun 'git' @('-C', $Dir, 'pull', '--ff-only') 'updating the checkout' $logBase
        if ($code -ne 0) {
            # A dirty or diverged tree is the user's work; refuse to fix it for them.
            Write-Row 'warn' 'Update' 'could not fast-forward -- keeping the checkout exactly as it is'
            foreach ($l in (Get-SpunLog $logBase)) { W-Dim "           $l" }
        } else {
            Write-Row 'ok' 'Update' "fast-forwarded to origin/$Branch"
        }
        Write-Bar 100 'checkout ready'
        return $true
    }

    if ((Test-Path -LiteralPath $Dir) -and @(Get-ChildItem -LiteralPath $Dir -Force -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Row 'err' 'Directory' "$Dir exists and is not an Arima checkout"
        W-Dim  '           Move it aside, or pass -Dir <another path>.'
        return $false
    }

    Write-Bar 20 "cloning $Repo"
    $parent = Split-Path -Parent $Dir
    if ($parent -and -not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    # A user only ever runs Arima, so a shallow clone is smaller and faster. A
    # developer needs real history to branch and open pull requests.
    $cloneArgs = @('clone')
    if (-not $Dev) { $cloneArgs += @('--depth', '1') }
    $cloneArgs += @('--branch', $Branch, '--single-branch', $Repo, $Dir)
    $code = Invoke-Spun 'git' $cloneArgs "cloning into $Dir" $logBase
    if ($code -ne 0) {
        Write-Row 'err' 'Clone' "git exited with code $code"
        foreach ($l in (Get-SpunLog $logBase)) { W-Dim "           $l" }
        return $false
    }
    Write-Bar 100 'clone complete'
    Write-Row 'ok' 'Checkout' $Dir
    return $true
}

# -- Step 4: hand over to the repository's own installer ----------------------
# arima.ps1 already knows how to prepare the workspace, wire the AI guardrails,
# run Maven and register the folder on PATH. Reuse it rather than re-implement.
function Invoke-Setup {
    $launcher = Join-Path $Dir 'arima.ps1'
    if (-not (Test-Path -LiteralPath $launcher)) {
        Write-Row 'err' 'Launcher' "arima.ps1 not found in $Dir"
        return $false
    }

    $psExe = $null
    try { $psExe = (Get-Process -Id $PID).Path } catch { $psExe = $null }
    if (-not $psExe) { $psExe = 'powershell.exe' }

    # -SkipOptional: the optional runtimes were already offered in step 2, and
    # arima.ps1 must not install anything behind the user's back here.
    $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $launcher,
              'install', '-Yes', '-SkipOptional')
    if (-not $NoPath) { $argv += '-AddToPath' }
    if ($NoAnim)      { $argv += '-NoAnim' }

    Write-Bar 20 'handing over to arima.ps1 install'
    W-Dim  "         $psExe -File `"$launcher`" install -Yes -SkipOptional$(if (-not $NoPath) { ' -AddToPath' })"
    Write-Host ''

    $prev = Get-Location
    try {
        & $psExe @argv
        $code = $LASTEXITCODE
    } finally {
        Set-Location $prev
    }

    if ($code -ne 0) {
        Write-Row 'err' 'Build' "arima.ps1 install exited with code $code"
        return $false
    }
    Write-Bar 100 'built and registered'
    return $true
}

# -- Success ------------------------------------------------------------------
function Show-Success {
    Show-Brew
    Write-Section 'INSTALLED'
    W-Ok   "  $ProductName is installed and ready."
    Write-Host ''
    Write-Row 'ok' 'Location' $Dir
    Write-Row 'ok' 'JAR'      (Join-Path $Dir 'target\arima-notebooks-1.0.0-SNAPSHOT.jar')
    if ($NoPath) {
        Write-Row 'warn' 'PATH' "not modified -- run the launcher as $Dir\arima.cmd"
    } else {
        Write-Row 'ok' 'PATH' ("$Dir added -- open a NEW terminal for plain 'arima' to resolve")
    }

    Write-Section 'NEXT'
    W-Plain '    arima start        Start the server and open the notebook UI'
    W-Info  "                       -> $Url"
    W-Plain '    arima start --bg   Start it detached, logging to arima.log'
    W-Plain '    arima status       Server state, detected runtimes, AI co-pilots'
    W-Plain '    arima welcome      The three ways to work with Arima Notebooks'
    W-Plain '    arima register     Associate .anb notebook files with Arima, so'
    W-Plain '                       double-clicking one opens it in the UI'
    Write-Host ''
    W-Dim   "    In a new terminal:   cd `"$Dir`"  ;  .\arima.cmd start"
    Write-Host ''
}

# -- Main ---------------------------------------------------------------------
function Invoke-Main {
    Show-Banner -Heading 'A R I M A   -   I N S T A L L E R' -Lines @(
        ('-' * 42),
        'One file. Checks, installs, builds, done.',
        'Nothing is changed before you say yes.',
        "Server: $Url",
        ''
    )

    if ($Reset -and (Test-Path -LiteralPath $StateFile)) {
        Remove-Item -LiteralPath $StateFile -Force
        W-Dim '  (-Reset) previous progress discarded'
    }
    $Script:State = Read-State

    Show-Explanation

    if ($CheckOnly) {
        Write-Section 'DEPENDENCY CHECK  (-CheckOnly: nothing will be changed)'
        $null = Invoke-DepCheck
        Write-Section 'CHECK ONLY -- STOPPED'
        W-Ok  '  Nothing was installed, downloaded, or added to PATH.'
        W-Dim '  Drop -CheckOnly to run the install for real.'
        Write-Host ''
        return 0
    }

    $resuming = @(Get-Completed).Count -gt 0
    if ($resuming) {
        Write-Section 'RESUMING'
        Write-Row 'ok' 'State' $StateFile
        Write-Row 'ok' 'Done'  ((Get-Completed) -join ', ')
        if ($Script:State.ContainsKey('failed_step')) {
            Write-Row 'warn' 'Last failure' "$($Script:State['failed_step']) -- $($Script:State['failed_reason'])"
        }
        W-Dim '           Completed steps are skipped. Use -Reset to run them all again.'
    }

    if (-not (Confirm-Action 'Proceed with the steps listed above?')) {
        Write-Host ''
        W-Warn '  Cancelled -- nothing was changed.'
        Write-Host ''
        return 1
    }

    $Script:State['dir']        = $Dir
    $Script:State['started_at'] = (Get-Date).ToString('s')
    Save-State

    $total = 4

    Write-Step 1 $total 'Checking dependencies'
    $missing = Invoke-DepCheck
    Set-StepDone 'deps-check'

    if (Test-StepDone 'deps-install') {
        Write-Step 2 $total 'Installing missing dependencies  (already done -- skipped)'
        Write-Bar 100 'skipped'
    } else {
        Write-Step 2 $total 'Installing missing dependencies'
        if (-not (Invoke-DepInstall $missing)) {
            return (Stop-Install 'deps-install' 'a required tool is still missing after the install attempt')
        }
        Set-StepDone 'deps-install'
    }

    # Re-probe: step 2 may have just put Java, Maven or Git on the PATH.
    $blockers = @(Get-Dependencies | Where-Object { $_.Required -and -not (Test-Dependency $_) })
    if ($blockers.Count -gt 0) {
        return (Stop-Install 'deps-install' ("required tools not available: " + (($blockers | ForEach-Object { $_.Label }) -join ', ')))
    }

    if (Test-StepDone 'fetch') {
        Write-Step 3 $total 'Fetching Arima Notebooks  (already done -- skipped)'
        Write-Bar 100 'skipped'
    } else {
        Write-Step 3 $total 'Fetching Arima Notebooks'
        if (-not (Invoke-Fetch)) {
            return (Stop-Install 'fetch' "could not clone or update $Repo into $Dir")
        }
        Set-StepDone 'fetch'
    }

    if (Test-StepDone 'setup') {
        Write-Step 4 $total 'Building the JAR  (already done -- skipped)'
        Write-Bar 100 'skipped'
    } else {
        Write-Step 4 $total 'Building the JAR'
        if (-not (Invoke-Setup)) {
            return (Stop-Install 'setup' 'the Maven build (arima.ps1 install) did not complete')
        }
        Set-StepDone 'setup'
    }

    $Script:State['finished_at'] = (Get-Date).ToString('s')
    Save-State
    Show-Success
    return 0
}

exit (Invoke-Main)
