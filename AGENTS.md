# AGENTS.md — Architecture Guardrails for AI Contributors

> **Audience:** Claude, GitHub Copilot, Antigravity, Cursor, Continue.dev, and any other AI coding agent invoked inside this repository.
>
> **Companion files:** [`CLAUDE.md`](CLAUDE.md) (Claude-specific), [`.github/copilot-instructions.md`](.github/copilot-instructions.md) (Copilot), [`GEMINI.md`](GEMINI.md) (Antigravity `agy` / legacy Gemini). All three load this file by reference — change one, change the others.

Arima Notebooks is meant to be **personalized and extended by its users with AI assistance**. That is the workflow the product is built around — the *use → customize → contribute* loop. The expectation:

- A user installs Arima Notebooks, finds something missing.
- They open *you* — their AI CLI — inside the cloned repo.
- They describe the change in plain English.
- You read this file, edit the right places, run the build, iterate until it works.
- They use the change locally. If it would help others, they ask you to package it as a PR back upstream.

Your job is to make that loop fast and safe. To keep the system coherent as many hands shape it, every AI agent that writes code in this repo must obey the rules below.

If a user instruction contradicts these rules, **stop and ask**. The user can override any rule, but the override must be explicit per session — never silent.

When the user asks you to "package this as a PR," run `pwsh ./scripts/security-check.ps1` (or `./scripts/security-check.sh`) first, fix anything it flags, then create a focused branch and PR with a clear description per the [Conventional Commits](https://www.conventionalcommits.org/) format documented in `CONTRIBUTING.md`.

---

## 1. The mental model

Arima Notebooks is **one Spring Boot 3.2 application on Java 21**, serving a static HTML/CSS/JS UI directly from the JAR. There is **no frontend build step**. The backend is layered:

```
┌─────────────────────────────────────────────────────────────────┐
│ Browser (vanilla HTML/CSS/JS) ── REST /api/* ── STOMP /ws       │
├─────────────────────────────────────────────────────────────────┤
│  controller/   REST endpoints — thin, no business logic         │
│  service/      Business logic — one service per concern         │
│  shell/        JShellManager + ShellSession                     │
│  model/        Plain Java POJOs — no Lombok, manual Builder     │
│  config/       WebSocket, CORS                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before making non-trivial changes.** It is authoritative on package layout, execution model, and the orchestration DSL.

---

## 2. Rules that must not be broken

### 2.1 Stay inside the layer

- **Controllers** receive the request, validate inputs, call exactly one service, return a DTO. No business logic, no `Files.write`, no `Runtime.exec`, no `new HttpClient`.
- **Services** own business logic. They may call other services, but call graphs must remain a DAG — no cycles.
- **Models** are pure data — getters, setters, manual `Builder`, `equals`/`hashCode`. **No Lombok.** Lombok was removed because of JDK 25 conflicts; do not reintroduce it.
- **Frontend JS** talks to the backend only via the REST and STOMP endpoints. It must not embed credentials, must not hit external hosts, and must not assume any backend internals beyond the documented API.

### 2.2 Do not change the architecture without an issue

These are load-bearing decisions. Do not change them in a feature PR:

- The seven execution-service split (`JShellManager`, `JavaCompilerService`, `NodeJsExecutionService`, `TypeScriptExecutionService`, `DotNetExecutionService`, `CppExecutionService`, plus `OrchestrationService`).
- The unified `ExecutionResult` shape — all runtimes return the same DTO.
- The `//@ anchor` / `//@ depends` orchestration DSL — the syntax is identical across all seven languages, by design.
- The local-first guarantee — no telemetry, no analytics, no auto-update calls.
- The port `8585` default — there is exactly one well-known place to look.
- The static-resources-from-JAR pattern — no Webpack, no Vite, no npm in the frontend build.

If a change requires modifying any of these, open an issue first and tag `@snchande`.

### 2.3 Security non-negotiables

- **Never commit secrets.** `data/settings.json` is `.gitignore`-d for a reason. So is `oauth-config.json`. Do not add new files under `data/` to git.
- **Never construct shell commands from user-controlled strings.** Use `ProcessBuilder` with an explicit argv list. The `scripts/security-check` pipeline blocks PRs that introduce `Runtime.exec(String)`.
- **Never add a new outbound HTTP host** beyond the allow-list (Maven Central, npm registry, NuGet.org, the AI CLI subprocess). The security check enforces this.
- **Never silently weaken `--add-opens` / `--add-exports`** JVM flags. JShell needs the existing set; do not remove any.

### 2.4 Frontend rules

- Vanilla JS, no framework. Do not add React, Vue, Svelte, jQuery, lodash, axios, etc.
- The only allowed third-party browser dependencies are the ones already loaded from CDN in `index.html` (CodeMirror, Inter, JetBrains Mono).
- New tabs follow the pattern in `CLAUDE.md` §"Add a new UI tab" — button in `index.html`, content div in `index.html`, JS module in `static/js/`.
- All real-time output flows over the existing STOMP topic. Do not open new WebSocket endpoints.

### 2.5 Style

- **Code comments:** default to none. Only comment the *why*, never the *what*. Identifiers and function names carry the *what*.
- **No emojis** in source files, commit messages, or docs unless the user asks for them.
- **Tests are integration-leaning.** Mocks are tolerated for IO, not for the database layer or for execution services.

---

## 3. The contribution loop

When the user asks you to add a capability, follow this loop:

1. **Read** `CLAUDE.md`, this file, and `docs/ARCHITECTURE.md`.
2. **Plan** in one paragraph: which layer changes, which files, which tests.
3. **Confirm** with the user if the change touches §2.2 or §2.3.
4. **Write** the change. Smallest possible diff. No drive-by refactors.
5. **Verify** locally:
   ```bash
   arima rebuild
   arima start
   mvn test
   pwsh ./scripts/security-check.ps1   # or .sh on Linux/macOS
   ```
6. **Update docs** that became wrong because of your change — README, `docs/`, this file, `CLAUDE.md` if the architecture shifted.
7. **Open a PR.** Use the PR template. Attach a screenshot for UI changes.

If the security check, `mvn test`, or `architecture-lint` fails in CI, **fix the failure** — do not disable the check, do not skip it with `--no-verify`, do not delete the test.

---

## 4. What you may do freely

- Add a new tutorial under `notebooks/` (especially `notebooks/tutorials/`).
- Add a new execution helper inside an existing execution service.
- Add a new REST endpoint, as long as it stays inside the controller→service pattern.
- Add a new field to `BaristaSettings`, with a sensible default.
- Improve docs, fix typos, clarify error messages.
- Add new unit or integration tests.
- Refactor inside a single file when the user asks.

---

## 5. What you must never do without explicit consent

- Modify `pom.xml` to add a non-Apache-2.0/MIT dependency.
- Touch `.github/workflows/security-check.yml` or `scripts/security-check.*`.
- Change the default port, the JAR name, the package layout, or the static-resource path.
- Add a build step to the frontend.
- Reintroduce Lombok.
- Add network telemetry, crash reporting, or "anonymous usage statistics."
- Force-push, amend published commits, or delete branches.
- Bypass the founding-contributor review on `master` (it is enforced by `CODEOWNERS`).

---

## 6. When the user says "make it personal"

Arima Notebooks is designed to be **shaped by the people who use it**. If the user is only personalizing their local copy:

- It is fine to keep the change in a fork.
- It is fine to commit experimental code to a feature branch and never PR it.
- If the change is useful to others, the user can ask you to *"package this as a PR against upstream"* — at that point all the rules above apply.

This is the loop the product is built around. Treat it as the happy path.

---

## 7. About this file

This file lives at the repo root so it loads automatically into Claude, Copilot, and Antigravity's context. If you change it, update the per-provider companion files too:

- `CLAUDE.md` — Claude Code's primary instruction file
- `.github/copilot-instructions.md` — GitHub Copilot's instructions
- `GEMINI.md` — Antigravity CLI (`agy`) instructions (formerly the Gemini CLI; Google retired Gemini CLI on 2026-06-18)

The three companion files are short — they exist to point at this file as the source of truth.

— Suresh Chande, founding contributor
