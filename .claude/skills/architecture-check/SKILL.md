---
name: architecture-check
description: Verify a proposed code change respects the Arima Notebooks layered architecture (AGENTS.md §2). Use BEFORE writing code when a change spans multiple files or touches controllers/services/static UI. Reports layer-crossings, Lombok reintroduction, forbidden frontend deps, new outbound hosts, and execution-service contract violations.
---

# architecture-check — Arima Notebooks layered-architecture verifier

Read [`AGENTS.md`](../../../AGENTS.md) and [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) before using this skill.

## When to invoke

- Before writing a multi-file change.
- After completing a change but before opening a PR.
- When a user proposal sounds like "add X to the frontend that talks to Y" — and you need to check whether Y is reachable through the existing controller→service path.

## What to check

Walk the proposed change against this checklist:

1. **Layer integrity**
   - Controllers stay thin: validate, call one service, return a DTO. No `Files.write`, no `Runtime.exec`, no `new HttpClient`, no `Connection`.
   - Services hold logic. Service-to-service calls form a DAG (no cycles).
   - Models are plain POJOs — getters/setters + manual `Builder`. **No Lombok annotations.**
   - Frontend talks to backend only via REST `/api/*` and STOMP `/ws`. No `jdbc:`, no `java.sql`, no references to `com.arima.shell.*`.

2. **Execution-service contract**
   - Each language has exactly one service (`JShellManager`, `JavaCompilerService`, `NodeJsExecutionService`, `TypeScriptExecutionService`, `DotNetExecutionService`, `CppExecutionService`).
   - All return a unified `ExecutionResult`. New runtimes must conform.
   - The `//@ anchor` / `//@ depends` DSL is identical across all seven languages.

3. **Local-first guarantee**
   - No new outbound HTTP hosts beyond Maven Central, npm, NuGet.org, AI CLI subprocesses.
   - No telemetry, crash reporting, or auto-update calls.

4. **Security non-negotiables**
   - No `Runtime.exec(String)` — only `ProcessBuilder(List<String>)`.
   - No secrets in source. `data/settings.json` and `oauth-config.json` stay `.gitignore`-d.
   - No `--no-verify`, `--no-gpg-sign`, or other check-skipping flags.

5. **Frontend rules**
   - Vanilla JS. No React, Vue, Svelte, jQuery, lodash, axios.
   - The only CDN deps are the ones already loaded in `index.html` (Inter, JetBrains Mono, CodeMirror, optional mermaid).

## How to run

1. Read the diff (`git diff` or the changed files directly).
2. For each changed file, classify it by layer (controller, service, model, static UI, config, test, doc).
3. Apply the checklist above to each change.
4. Also run `pwsh ./scripts/security-check.ps1` (or `./scripts/security-check.sh`) — it catches many of the same issues mechanically.
5. Report findings in this exact shape:
   ```
   PASS / WARN / BLOCK — <one-line summary>

   <findings, grouped by severity, with file:line>

   Next step: <what to do>
   ```

## What to do on a violation

- **Suggest a fix** that moves the offending code to the correct layer.
- **Never silently "fix it" elsewhere.** Surface the violation, then propose the fix.
- If the user wants to override (e.g., they've decided the architecture should change), capture the decision: it requires an issue + founding-contributor approval, not a feature-PR drive-by.

## Notes

This skill is read-only. It does not modify files. Use the `Edit` / `Write` tools after the user agrees with the proposed fix.
