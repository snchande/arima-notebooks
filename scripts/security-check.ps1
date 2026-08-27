# Arima Notebooks — PR Security & Architecture Check (PowerShell)
#
# Runs locally (pre-push) and in CI. Same script, same rules.
# Exit code:
#   0  = pass (no blocking findings; warnings may be present)
#   1  = block (at least one blocking finding — PR cannot be marked ready for review)
#
# Usage:
#   pwsh ./scripts/security-check.ps1                    # check working tree
#   pwsh ./scripts/security-check.ps1 -BaseRef origin/master  # check diff vs base
#
# This is a lightweight first-pass scanner. It is intentionally pattern-based
# and biased toward false positives — every flagged finding is reviewed by a
# human before merge.

[CmdletBinding()]
param(
  [string]$BaseRef = '',
  [switch]$Json
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$findings = New-Object System.Collections.Generic.List[object]
function Add-Finding {
  param(
    [ValidateSet('block','warn','info')] [string]$Level,
    [string]$Rule,
    [string]$Path,
    [int]$Line = 0,
    [string]$Detail
  )
  $findings.Add([pscustomobject]@{
    level=$Level; rule=$Rule; path=$Path; line=$Line; detail=$Detail
  })
}

# ─────────────────────────────────────────────────────────────
# Determine which files to scan
# ─────────────────────────────────────────────────────────────
$files = @()
if ($BaseRef) {
  try {
    $files = git diff --name-only --diff-filter=ACMR "$BaseRef...HEAD" 2>$null |
             Where-Object { $_ -and (Test-Path $_) }
  } catch { $files = @() }
}
if (-not $files -or $files.Count -eq 0) {
  # Fallback: scan all tracked source files
  $files = git ls-files |
           Where-Object {
             $_ -match '\.(java|js|ts|cs|fs|cpp|h|hpp|html|css|xml|yml|yaml|md|json|properties|ps1|sh)$' -and
             $_ -notmatch '^(target|node_modules|data|\.idea|\.vscode)/'
           }
}

if (-not $Json) {
  Write-Host ""
  Write-Host "Arima security check — scanning $($files.Count) file(s)" -ForegroundColor Cyan
  Write-Host "───────────────────────────────────────────────────────"
}

# ─────────────────────────────────────────────────────────────
# Patterns (rule definitions)
# ─────────────────────────────────────────────────────────────
$rules = @(
  # Secret material — BLOCK
  @{ name='secret/anthropic-key';    pattern='sk-ant-[A-Za-z0-9_-]{20,}';           level='block';
     msg='Anthropic API key literal' },
  @{ name='secret/openai-key';       pattern='sk-(proj-)?[A-Za-z0-9]{20,}';          level='block';
     msg='OpenAI-style API key literal' },
  @{ name='secret/aws-access-key';   pattern='AKIA[0-9A-Z]{16}';                     level='block';
     msg='AWS access key ID literal' },
  @{ name='secret/github-token';     pattern='gh[pousr]_[A-Za-z0-9]{30,}';           level='block';
     msg='GitHub personal/app token literal' },
  @{ name='secret/google-key';       pattern='AIza[0-9A-Za-z_-]{35}';                level='block';
     msg='Google API key literal' },
  @{ name='secret/private-key-pem';  pattern='-----BEGIN [A-Z ]*PRIVATE KEY-----';   level='block';
     msg='PEM private key block' },
  @{ name='secret/slack-token';      pattern='xox[abpsr]-[A-Za-z0-9-]{10,}';         level='block';
     msg='Slack token literal' },

  # Command injection — BLOCK
  @{ name='exec/runtime-string';     pattern='Runtime\.getRuntime\(\)\.exec\s*\(\s*"';  level='block';
     msg='Runtime.exec(String) — use ProcessBuilder(List<String>) instead' },
  @{ name='exec/process-builder-shell'; pattern='ProcessBuilder\s*\(\s*"cmd",\s*"/c"|ProcessBuilder\s*\(\s*"sh",\s*"-c"'; level='warn';
     msg='Shell-out via ProcessBuilder — confirm no user-controlled input is concatenated' },

  # Frontend rules — BLOCK
  @{ name='ui/forbidden-framework';  pattern='^\s*(import\s.+\s+from\s+["''](react|vue|svelte|@angular)[/"'']|const\s+\w+\s*=\s*require\(["''](jquery|lodash|axios)["'']\))'; level='block';
     msg='Forbidden browser framework/library — Arima frontend is vanilla JS' },
  @{ name='ui/lombok';               pattern='import\s+lombok\.|@Data\b|@Getter\b|@Setter\b|@Builder\b|@AllArgsConstructor\b|@NoArgsConstructor\b'; level='block';
     msg='Lombok is forbidden (removed for JDK 25 compatibility) — use plain getters/setters + manual Builder' },

  # Local-first guarantee — WARN (allow-list lives below)
  @{ name='net/outbound-url';        pattern='https?://';
     level='warn';
     msg='Outbound URL — confirm host is on the allow-list (Maven Central, npm, NuGet, AI CLI)' },

  # Style — WARN
  @{ name='style/system-out-debug';  pattern='System\.out\.println\s*\(\s*"DEBUG'; level='warn';
     msg='Debug println left in source' },
  @{ name='style/todo-fixme';        pattern='\b(TODO|FIXME|XXX|HACK)\b'; level='info';
     msg='TODO/FIXME marker' }
)

# Loopback is never an outbound host - it is this machine. Kept separate from the
# allow-list so the intent is clear: these are not third parties we have chosen to
# trust. Matches the LOOPBACK list in security-check.sh.
$loopbackHosts = @('localhost', '127\.0\.0\.1', '0\.0\.0\.0', '::1', '\[::1\]')

# Allow-list for outbound hosts — exempt from net/outbound-url warning
$hostAllowList = @(
  'repo1\.maven\.org', 'search\.maven\.org', 'repo\.maven\.apache\.org',
  'registry\.npmjs\.org', 'www\.npmjs\.com',
  'api\.nuget\.org', 'www\.nuget\.org',
  'api\.anthropic\.com', 'claude\.ai',
  'fonts\.googleapis\.com', 'fonts\.gstatic\.com',
  'cdnjs\.cloudflare\.com', 'cdn\.jsdelivr\.net',
  'github\.com', 'api\.github\.com',
  'spring\.io', 'dot\.net', 'dotnet\.microsoft\.com',
  'oss\.sonatype\.org', 'central\.sonatype\.com',
  'shields\.io', 'img\.shields\.io',
  'openjdk\.org'
)

# Paths exempt from scanning (docs, samples, and README examples)
$pathExemptions = @(
  '^docs/',
  '^README\.md$',
  '^CONTRIBUTING\.md$',
  '^CHANGELOG\.md$',
  '^AGENTS\.md$',
  '^GEMINI\.md$',
  '^CLAUDE\.md$',
  '^\.github/copilot-instructions\.md$',
  '^\.github/PULL_REQUEST_TEMPLATE\.md$',
  '^\.github/ISSUE_TEMPLATE/',
  '^\.claude/',
  '^scripts/security-check\.(ps1|sh)$',
  # The single SPA entry point legitimately loads CDN fonts + CodeMirror.
  # Vetted by the founding contributor; new outbound hosts are still flagged
  # by Test-OutboundHostAllowed below.
  '^src/main/resources/static/index\.html$',
  '^src/main/resources/static/img/'
)

function Test-PathExempt($p) {
  foreach ($pat in $pathExemptions) { if ($p -match $pat) { return $true } }
  return $false
}

# ─────────────────────────────────────────────────────────────
# Run scans
# ─────────────────────────────────────────────────────────────
foreach ($file in $files) {
  if (-not (Test-Path $file)) { continue }
  if ((Get-Item $file).PSIsContainer) { continue }
  $isDocFile = Test-PathExempt $file
  try {
    $lines = Get-Content -LiteralPath $file -ErrorAction Stop
  } catch { continue }

  for ($i=0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    foreach ($rule in $rules) {
      # Exempt (doc/meta) files skip advisory rules AND the code-style block rules
      # (lombok / forbidden-framework) — instruction docs and this scanner itself
      # legitimately quote those patterns. Secret/exec/arch rules still scan everywhere.
      $codeStyleRule = @('ui/lombok','ui/forbidden-framework') -contains $rule.name
      if ($isDocFile -and ($rule.level -ne 'block' -or $codeStyleRule)) { continue }
      if ($line -match $rule.pattern) {
        # net/outbound-url allow-list — extract every host on the line, all must be allowed
        if ($rule.name -eq 'net/outbound-url') {
          # Bracketed IPv6 literals are matched too, so [::1] is recognised rather
          # than silently skipped by a host pattern that cannot express it.
          $allHosts = [regex]::Matches($line, 'https?://(\[[0-9A-Fa-f:]+\]|[A-Za-z0-9.-]+)') |
                      ForEach-Object { $_.Groups[1].Value }
          $unknown = @()
          foreach ($h in $allHosts) {
            $ok = $false
            foreach ($lb in $loopbackHosts) {
              if ($h -match "^$lb`$") { $ok = $true; break }
            }
            if (-not $ok) {
              foreach ($allowed in $hostAllowList) {
                if ($h -match "^$allowed`$") { $ok = $true; break }
              }
            }
            if (-not $ok) { $unknown += $h }
          }
          if ($unknown.Count -eq 0) { continue }
        }
        Add-Finding -Level $rule.level -Rule $rule.name -Path $file -Line ($i+1) -Detail $rule.msg
      }
    }
  }
}

# ─────────────────────────────────────────────────────────────
# Architecture-lint: controller-to-controller calls
# ─────────────────────────────────────────────────────────────
$controllerFiles = $files | Where-Object { $_ -match 'src/main/java/com/barista/controller/.*Controller\.java$' }
foreach ($file in $controllerFiles) {
  $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
  if ($null -eq $content) { continue }
  if ($content -match 'import\s+com.barista\.controller\.\w+Controller\s*;') {
    Add-Finding -Level 'block' -Rule 'arch/controller-cycle' -Path $file -Line 0 `
      -Detail 'Controller imports another controller — must call through a service instead'
  }
  if ($content -match '(?ms)class\s+\w+Controller\b.*?\bRuntime\.getRuntime\(\)') {
    Add-Finding -Level 'block' -Rule 'arch/controller-runtime' -Path $file -Line 0 `
      -Detail 'Controller calls Runtime directly — move into a service'
  }
}

# ─────────────────────────────────────────────────────────────
# Architecture-lint: frontend → backend internals
# ─────────────────────────────────────────────────────────────
$jsFiles = $files | Where-Object { $_ -match 'src/main/resources/static/.*\.js$' }
foreach ($file in $jsFiles) {
  $content = Get-Content -LiteralPath $file -Raw -ErrorAction SilentlyContinue
  if ($null -eq $content) { continue }
  if ($content -match 'jdbc:|java\.sql\.|Connection\.|com.barista\.shell\.') {
    Add-Finding -Level 'block' -Rule 'arch/frontend-backend-leak' -Path $file -Line 0 `
      -Detail 'Frontend references backend internals — must use REST/STOMP only'
  }
}

# ─────────────────────────────────────────────────────────────
# Architecture-lint: data/ files staged for commit
# ─────────────────────────────────────────────────────────────
$dataFiles = $files | Where-Object { $_ -match '^data/' }
foreach ($file in $dataFiles) {
  Add-Finding -Level 'block' -Rule 'secret/data-dir' -Path $file -Line 0 `
    -Detail 'Files under data/ must never be committed (gitignored — may contain API keys)'
}

# ─────────────────────────────────────────────────────────────
# Report
# ─────────────────────────────────────────────────────────────
$blocks = @($findings | Where-Object { $_.level -eq 'block' })
$warns  = @($findings | Where-Object { $_.level -eq 'warn' })
$infos  = @($findings | Where-Object { $_.level -eq 'info' })

if ($Json) {
  $report = [pscustomobject]@{
    summary  = [pscustomobject]@{ blocks=$blocks.Count; warns=$warns.Count; infos=$infos.Count }
    findings = $findings
  }
  $report | ConvertTo-Json -Depth 6
} else {
  foreach ($g in @(
      @{ level='block'; items=$blocks; label='BLOCK' ; color='Red'    },
      @{ level='warn' ; items=$warns ; label='WARN'  ; color='Yellow' },
      @{ level='info' ; items=$infos ; label='INFO'  ; color='Gray'   }
    )) {
    if ($g.items.Count -gt 0) {
      Write-Host ""
      Write-Host "$($g.label) — $($g.items.Count) finding(s)" -ForegroundColor $g.color
      foreach ($f in $g.items) {
        $loc = if ($f.line -gt 0) { "$($f.path):$($f.line)" } else { $f.path }
        Write-Host ("  [{0}] {1} — {2}" -f $f.rule, $loc, $f.detail)
      }
    }
  }
  Write-Host ""
  Write-Host "───────────────────────────────────────────────────────"
  Write-Host ("Summary: {0} block, {1} warn, {2} info" -f $blocks.Count, $warns.Count, $infos.Count)
}

if ($blocks.Count -gt 0) {
  Write-Host ""
  Write-Host "FAIL — resolve blocking findings before this PR can be marked ready for review." -ForegroundColor Red
  exit 1
}
Write-Host ""
Write-Host "PASS — no blocking findings." -ForegroundColor Green
exit 0
