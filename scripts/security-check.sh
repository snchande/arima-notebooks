#!/usr/bin/env bash
# Arima Notebooks — PR Security & Architecture Check (POSIX)
#
# Runs locally (pre-push) and in CI. Same rules as scripts/security-check.ps1.
# Exit code:
#   0  = pass (no blocking findings)
#   1  = block (at least one blocking finding — PR cannot be marked ready)
#
# Usage:
#   ./scripts/security-check.sh                              # working tree
#   ./scripts/security-check.sh --base origin/master         # diff vs base
#   ./scripts/security-check.sh --json                       # JSON output

set -eo pipefail

BASE_REF=""
JSON=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) BASE_REF="$2"; shift 2;;
    --json) JSON=1; shift;;
    *) echo "Unknown arg: $1" >&2; exit 2;;
  esac
done

cd "$(dirname "$0")/.."
REPO_ROOT="$PWD"

# ──────────────────────────────────────────────────────────────
# File set
# ──────────────────────────────────────────────────────────────
if [[ -n "$BASE_REF" ]]; then
  mapfile -t FILES < <(git diff --name-only --diff-filter=ACMR "$BASE_REF...HEAD" 2>/dev/null | grep -E '\.(java|js|ts|cs|fs|cpp|h|hpp|html|css|xml|yml|yaml|md|json|properties|ps1|sh)$' || true)
fi
if [[ ${#FILES[@]} -eq 0 ]]; then
  mapfile -t FILES < <(git ls-files | grep -E '\.(java|js|ts|cs|fs|cpp|h|hpp|html|css|xml|yml|yaml|md|json|properties|ps1|sh)$' | grep -v -E '^(target|node_modules|data|\.idea|\.vscode)/' || true)
fi

[[ $JSON -eq 0 ]] && echo "" && echo "Arima security check — scanning ${#FILES[@]} file(s)" && \
  echo "───────────────────────────────────────────────────────"

# ──────────────────────────────────────────────────────────────
# Findings store
# ──────────────────────────────────────────────────────────────
FINDINGS=()    # "level|rule|path|line|detail"

add_finding() { FINDINGS+=("$1|$2|$3|$4|$5"); }

# Path exemptions (docs/sample files only flagged on block-level rules)
is_doc_file() {
  case "$1" in
    docs/*|README.md|CONTRIBUTING.md|CHANGELOG.md|AGENTS.md|GEMINI.md|CLAUDE.md| \
    .github/copilot-instructions.md|scripts/security-check.ps1|scripts/security-check.sh) return 0;;
  esac
  return 1
}

# Outbound URL allow-list
# Loopback is never an outbound host. Kept separate from ALLOW so the intent
# is obvious: these are not third parties we have decided to trust.
LOOPBACK='localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|::1'
ALLOW='repo1\.maven\.org|search\.maven\.org|repo\.maven\.apache\.org|registry\.npmjs\.org|www\.npmjs\.com|api\.nuget\.org|www\.nuget\.org|api\.anthropic\.com|claude\.ai|fonts\.googleapis\.com|fonts\.gstatic\.com|cdnjs\.cloudflare\.com|cdn\.jsdelivr\.net|github\.com|api\.github\.com|spring\.io|dot\.net|dotnet\.microsoft\.com|oss\.sonatype\.org|central\.sonatype\.com|shields\.io|img\.shields\.io|openjdk\.org'

# ──────────────────────────────────────────────────────────────
# Rule helpers
# ──────────────────────────────────────────────────────────────
scan_rule() {
  local level="$1" name="$2" pattern="$3" msg="$4" docs_ok="$5"
  local f line lineno
  for f in "${FILES[@]}"; do
    [[ -f "$f" ]] || continue
    if [[ "$docs_ok" != "yes" ]] && is_doc_file "$f"; then continue; fi
    while IFS=: read -r lineno line; do
      [[ -z "$lineno" ]] && continue
      # net/outbound-url allow-list
      if [[ "$name" == "net/outbound-url" ]]; then
        if echo "$line" | grep -qE "https?://($ALLOW)"; then continue; fi
        if echo "$line" | grep -qE "https?://($LOOPBACK)"; then continue; fi
      fi
      add_finding "$level" "$name" "$f" "$lineno" "$msg"
    done < <(grep -nE "$pattern" "$f" 2>/dev/null || true)
  done
}

# ──────────────────────────────────────────────────────────────
# Block — secrets
# ──────────────────────────────────────────────────────────────
scan_rule block secret/anthropic-key   'sk-ant-[A-Za-z0-9_-]{20,}'              'Anthropic API key literal' yes
scan_rule block secret/openai-key      'sk-(proj-)?[A-Za-z0-9]{20,}'            'OpenAI-style API key literal' yes
scan_rule block secret/aws-access-key  'AKIA[0-9A-Z]{16}'                       'AWS access key ID literal' yes
scan_rule block secret/github-token    'gh[pousr]_[A-Za-z0-9]{30,}'             'GitHub token literal' yes
scan_rule block secret/google-key      'AIza[0-9A-Za-z_-]{35}'                  'Google API key literal' yes
scan_rule block secret/private-key-pem -- '-----BEGIN [A-Z ]*PRIVATE KEY-----'  'PEM private key block' yes
scan_rule block secret/slack-token     'xox[abpsr]-[A-Za-z0-9-]{10,}'           'Slack token literal' yes

# ──────────────────────────────────────────────────────────────
# Block — command injection
# ──────────────────────────────────────────────────────────────
scan_rule block exec/runtime-string 'Runtime\.getRuntime\(\)\.exec\s*\(\s*"' \
  'Runtime.exec(String) — use ProcessBuilder(List<String>)' no
scan_rule warn  exec/process-builder-shell 'ProcessBuilder\s*\(\s*"cmd",\s*"/c"|ProcessBuilder\s*\(\s*"sh",\s*"-c"' \
  'Shell-out via ProcessBuilder — confirm no user-controlled input is concatenated' no

# ──────────────────────────────────────────────────────────────
# Block — frontend rules
# ──────────────────────────────────────────────────────────────
scan_rule block ui/forbidden-framework \
  'from[[:space:]]+"(react|vue|svelte)"|require\("(jquery|lodash|axios)"\)' \
  'Forbidden browser framework/library — Arima frontend is vanilla JS' no
scan_rule block ui/lombok \
  'import[[:space:]]+lombok\.|@Data\b|@Getter\b|@Setter\b|@Builder\b|@AllArgsConstructor\b|@NoArgsConstructor\b' \
  'Lombok is forbidden (removed for JDK 25 compatibility)' no

# ──────────────────────────────────────────────────────────────
# Warn — outbound URLs
# ──────────────────────────────────────────────────────────────
scan_rule warn net/outbound-url 'https?://' \
  'Outbound URL — confirm host is on the allow-list (Maven Central, npm, NuGet, AI CLI)' no

# ──────────────────────────────────────────────────────────────
# Warn — style
# ──────────────────────────────────────────────────────────────
scan_rule warn style/system-out-debug 'System\.out\.println[[:space:]]*\([[:space:]]*"DEBUG' \
  'Debug println left in source' no
scan_rule info style/todo-fixme '\b(TODO|FIXME|XXX|HACK)\b' \
  'TODO/FIXME marker' no

# ──────────────────────────────────────────────────────────────
# Architecture lint
# ──────────────────────────────────────────────────────────────
for f in "${FILES[@]}"; do
  case "$f" in
    src/main/java/com/barista/controller/*Controller.java)
      if grep -qE 'import[[:space:]]+com.barista\.controller\.[A-Za-z]+Controller[[:space:]]*;' "$f" 2>/dev/null; then
        add_finding block arch/controller-cycle "$f" 0 \
          'Controller imports another controller — must call through a service'
      fi
      ;;
    src/main/resources/static/*.js)
      if grep -qE 'jdbc:|java\.sql\.|Connection\.|com.barista\.shell\.' "$f" 2>/dev/null; then
        add_finding block arch/frontend-backend-leak "$f" 0 \
          'Frontend references backend internals — must use REST/STOMP only'
      fi
      ;;
    data/*)
      add_finding block secret/data-dir "$f" 0 \
        'Files under data/ must never be committed (gitignored — may contain API keys)'
      ;;
  esac
done

# ──────────────────────────────────────────────────────────────
# Report
# ──────────────────────────────────────────────────────────────
BLOCKS=0; WARNS=0; INFOS=0
for entry in "${FINDINGS[@]}"; do
  IFS='|' read -r level rule path line detail <<<"$entry"
  case "$level" in block) BLOCKS=$((BLOCKS+1));; warn) WARNS=$((WARNS+1));; info) INFOS=$((INFOS+1));; esac
done

if [[ $JSON -eq 1 ]]; then
  printf '{"summary":{"blocks":%d,"warns":%d,"infos":%d},"findings":[' "$BLOCKS" "$WARNS" "$INFOS"
  first=1
  for entry in "${FINDINGS[@]}"; do
    IFS='|' read -r level rule path line detail <<<"$entry"
    [[ $first -eq 0 ]] && printf ','
    first=0
    detail_esc=$(printf '%s' "$detail" | sed 's/"/\\"/g')
    printf '{"level":"%s","rule":"%s","path":"%s","line":%s,"detail":"%s"}' \
      "$level" "$rule" "$path" "$line" "$detail_esc"
  done
  printf ']}\n'
else
  for want in block warn info; do
    count=0
    for entry in "${FINDINGS[@]}"; do
      IFS='|' read -r level rule path line detail <<<"$entry"
      [[ "$level" == "$want" ]] || continue
      [[ $count -eq 0 ]] && echo "" && echo "${want^^} findings:"
      count=$((count+1))
      loc="$path"; [[ "$line" != "0" ]] && loc="$path:$line"
      printf "  [%s] %s — %s\n" "$rule" "$loc" "$detail"
    done
  done
  echo ""
  echo "───────────────────────────────────────────────────────"
  echo "Summary: $BLOCKS block, $WARNS warn, $INFOS info"
fi

if [[ $BLOCKS -gt 0 ]]; then
  [[ $JSON -eq 0 ]] && echo "" && echo "FAIL — resolve blocking findings before this PR can be marked ready for review."
  exit 1
fi
[[ $JSON -eq 0 ]] && echo "" && echo "PASS — no blocking findings."
exit 0
