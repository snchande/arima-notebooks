<#
.SYNOPSIS
  Detect and (optionally) install Python 3 for Arima Notebooks Python cells.

.DESCRIPTION
  Arima runs Python cells with the system Python 3 interpreter. This helper checks
  whether a suitable interpreter is on PATH and, if not, offers to install one via
  winget (or Chocolatey), then verifies pip. Safe to run repeatedly.

.EXAMPLE
  ./scripts/setup-python.ps1
  ./scripts/setup-python.ps1 -Yes     # non-interactive: install without prompting
#>
[CmdletBinding()]
param([switch] $Yes)

$ErrorActionPreference = 'Stop'
function Info($t){ Write-Host $t -ForegroundColor Cyan }
function Ok($t){ Write-Host $t -ForegroundColor Green }
function Warn($t){ Write-Host $t -ForegroundColor Yellow }
function Err($t){ Write-Host $t -ForegroundColor Red }

function Find-Python {
    foreach ($c in @(@('python'), @('python3'), @('py','-3'))) {
        try {
            $exe = $c[0]; $rest = @($c[1..($c.Count-1)]) + '--version'
            $v = & $exe @rest 2>&1
            if ($LASTEXITCODE -eq 0 -and "$v" -match 'Python 3') { return "$v".Trim() }
        } catch {}
    }
    return $null
}

Info "Arima Notebooks — Python setup"
Info "------------------------------"

$found = Find-Python
if ($found) {
    Ok "  Found: $found"
    try { $pip = & python -m pip --version 2>&1 } catch { $pip = '' }
    if ($LASTEXITCODE -eq 0) { Ok "  pip:   $($pip -replace ' from.*','')" }
    else {
        Warn '  pip not found for this interpreter. Bootstrapping with ensurepip...'
        & python -m ensurepip --upgrade
    }
    Ok "`n  Python cells are ready. Restart Arima if it was running: ./arima.ps1 stop; ./arima.ps1 start"
    exit 0
}

Warn "  No Python 3 interpreter found on PATH."
$doInstall = $Yes
if (-not $Yes) {
    $ans = Read-Host '  Install Python 3 now? [Y/n]'
    $doInstall = ($ans -notmatch '^[nN]')
}

if ($doInstall) {
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Info '  Installing via winget (Python.Python.3.12)...'
        winget install -e --id Python.Python.3.12 --accept-source-agreements --accept-package-agreements
    } elseif (Get-Command choco -ErrorAction SilentlyContinue) {
        Info '  Installing via Chocolatey...'
        choco install -y python
    } else {
        Err '  Neither winget nor Chocolatey is available for an automated install.'
        Warn '  Install manually from https://www.python.org/downloads/ (check "Add python.exe to PATH"), then re-run this script.'
        exit 1
    }
    Warn "`n  Installed. Open a NEW terminal (so PATH refreshes), then verify:"
    Warn '    python --version'
    Warn '  and restart Arima:  ./arima.ps1 stop; ./arima.ps1 start'
} else {
    Info '  Skipped. To enable Python cells later:'
    Info '    1. Install Python 3.8+ from https://www.python.org/downloads/  (tick "Add to PATH")'
    Info '    2. Restart Arima:  ./arima.ps1 stop; ./arima.ps1 start'
    Info '  Packages install from the app: Packages -> PyPI.'
}
