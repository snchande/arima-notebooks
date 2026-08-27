# Arima Notebooks — Changelog

All notable changes to Arima Notebooks are documented here.
Dates are in `YYYY-MM-DD` format.

---

## [4.0.1] - 2026-08-26

Arima Notebooks is a notebook that runs on your own computer, where eight languages
work side by side. This release makes it **safe by default**, **easy to install**, and
adds a way to **read any cell in a language you are learning**.

Full detail for each item is in the linked documentation.

### Important security fix - please update

Arima was reachable from your **local network**, and it runs code with your user
account. Anyone on the same Wi-Fi could have run code on your machine without any
password. That is now closed: Arima listens on your own computer only, and refuses
anything that did not come from it.

If you want to reach Arima from another device you can turn that on deliberately in
**Settings -> Network access**. The risk is spelled out there, and anything arriving
from another machine is **held until you approve it** - Arima brings the window to the
front and shows you the code before a single line runs.
-> [docs/SECURITY.md](docs/SECURITY.md)

### Install with one command

Download one file, run it, and it does the rest. It explains what it will change
before it changes anything, checks what you already have, and installs only what is
missing. If it fails it resumes from where it stopped rather than starting over.
Maven is no longer needed - a Java installation is enough.
-> [Get started](README.md#get-started) - or the illustrated
[Getting Started guide (PDF)](docs/guide/Arima-Notebooks-Getting-Started.pdf)

### Read a cell in a language you are learning

Every code cell can now show itself in another of the eight languages, side by side
with the one you already know. The translation is written to be idiomatic rather than
literal, and comments only the places where the new language genuinely does things
differently. Both sides can be run, and Arima tells you whether they agree.
-> [Polyglot](docs/USAGE.md#polyglot--learn-a-language-in-terms-of-the-one-you-know)

### Your work keeps its own name

Notebooks are now `.anb` files named after the notebook - `sales-analysis.anb` rather
than a long string of letters and numbers. Existing files are renamed automatically
when you upgrade. Double-clicking one opens it in Arima once you run `arima register`.

### Cells remember what you did

Python, JavaScript and TypeScript cells now share one session, so something you define
in one cell is still there in the next - as Java always did. Restarting a notebook, or
running a pipeline, rebuilds everything cleanly from the top.
-> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

### Every lesson explains itself

All 40 built-in tutorials now explain their code line by line - what each line does and
why it is written that way. Adds a new lesson on pipelines and orchestration.

### Reach it from another device, if you choose to

**Settings -> Network access** lets other devices on your Wi-Fi or LAN reach your
Arima. It is off by default, and the risk is spelled out where you turn it on: Arima
runs code as you, with no sandbox.

What protects you is that nothing from another machine runs on arrival. It is held,
Arima brings the browser to the front with the code on screen, and it runs only when
you press **Run it**. A request nobody answers is refused. Only the machine running
Arima can approve - a remote agent cannot approve its own code.
-> [docs/SECURITY.md](docs/SECURITY.md)

### It tells you when there is a newer version

Arima checks whether `master` has moved on and shows you what changed. **Update and
restart** pulls, rebuilds and restarts for you, and the page reconnects on its own.
It will not discard uncommitted work to do it.
-> [Staying up to date](docs/USAGE.md#staying-up-to-date)

### Also fixed

Thirteen bugs that predate this release, including Python pipelines running as Java,
tutorials that could not be read over MCP, and chart library names quietly colliding
with ordinary code. Each is listed in the entries below.

---

## [Unreleased]

### Security - Arima is reachable only from your own machine

Arima executes code in eight languages as the operating-system user who started it,
and in the default `local` auth mode nothing authenticates the caller. That is the
right trade for a tool on your own laptop, and it means **anything that can reach the
port can run code as you**.

The server was binding `0.0.0.0`. Verified against a second address on the same
network, every one of these succeeded, unauthenticated:

- `POST /api/shell/execute` - arbitrary code execution as the logged-in user
- `POST /api/mcp/messages` - the same, plus read and write access to every notebook
- `GET /api/notebooks` - reading all notebook content
- `POST /actuator/shutdown` - stopping the server remotely

Anyone on the same Wi-Fi could have done it. Three controls now close it:

- **`server.address=127.0.0.1`** - the port is not open on any other interface, so a
  remote connection is refused at the socket rather than merely rejected.
- **`LocalAccessFilter`** - rejects any request whose peer is not a loopback address,
  catching a proxy, a port forward, or a future config change that widens the bind.
- **A `Host` header check** - a page on an attacker's origin can point a hostname at
  127.0.0.1, making the socket genuinely local (DNS rebinding). The header must name
  loopback. It is matched *without* DNS resolution: resolving an attacker-controlled
  header would have let a domain of theirs pointed at 127.0.0.1 pass, which is the
  very attack being prevented.

CORS was `allowedOriginPatterns("*")` with CSRF disabled, so any website you visited
could POST to your own machine and have code executed - a drive-by needing no network
access at all. Now restricted to Arima's own origins.

`docs/SECURITY.md` records the model and the reasoning; AGENTS.md gains a guardrail,
because every new endpoint inherits this power.

---


### One-file install
- **`install.ps1` and `install.sh`.** A newcomer downloads one file and runs it. It explains what Arima is and exactly what it will change *before* touching the machine, shows a dependency table, then installs missing pieces one at a time through the platform's own package manager (winget / Homebrew / apt / dnf / pacman). Progress is recorded in `~/.arima-install-state`, so a failed run resumes from the step that failed rather than starting over, and the failure message carries the diagnostics block and the issue URL. `-CheckOnly` / `--check-only` prints the whole plan and stops. File associations are deliberately not claimed — `arima register` does that separately, when asked.
- Both installers hand over to the repository's own `arima install` once the clone is on disk, so the build and workspace setup live in one place.
- **Two audiences, two paths.** By default the installer gives a *user* a shallow clone and the essentials. `-Dev` / `--dev` gives a *developer* the full git history, so they can branch and open pull requests, and offers every optional language runtime.
- **Maven is no longer a dependency.** The repository now ships a Maven Wrapper (`mvnw`), and all three launchers prefer it over a system Maven, so building needs only a JDK. This was not cosmetic: Maven is not published to winget at all, so the Windows installer's `Apache.Maven` package id could never have resolved and a fresh Windows machine would have failed at that step.

### Polyglot — read a cell in a language you are learning
- **Language tabs on every code cell.** Selecting another language asks the active AI provider to render that cell once; the result is stored on the cell and saved with the notebook, so it is instant afterwards and travels with the file. Translated tabs are real cells: editable, and runnable against that language's own runtime.
- **Compare** splits the cell into two equal columns and **Run both** executes them together, reporting whether the outputs actually match and how long each language took. Runs and timings are persisted, so reopening a notebook shows both sides again.
- Translations are asked to be idiomatic rather than transliterated, and to comment only where the target language forces a different approach.
- New: `POST /api/llm/translate`, `GET /api/llm/languages`, `CellTranslation`, `Cell.translations`, and four `polyglot*` settings.

### One long-lived interpreter per session, for Python, JavaScript and TypeScript
- **`//@ depends` now means the same thing in all eight languages**: the anchor must have run in this session. Python previously re-executed each ancestor's *source* per dependent cell, and JavaScript and TypeScript ignored the annotation outright.
- **`PythonKernelService`** replaces per-cell replay. Measured: an expensive ancestor is paid once instead of once per dependent (2s load with three dependents went from 8.9s to ~2.3s); a `str(uuid.uuid4())` bound in an anchor is now the *same* value in every dependent instead of a different one each time; and an ancestor's side effects fire once rather than once per dependent.
- **`NodeKernelService`** gives JavaScript and TypeScript cross-cell state for the first time, sharing one kernel with a type-stripping pass in front for TS. Type-*checking* still runs out-of-process.
- Reproducibility moves from isolation to restarting: restarting a session drops the interpreter, and running a pipeline rebuilds every step in dependency order from nothing.

### Notebooks are `.anb`, named after themselves
- **`.vnb` is now `.anb`**, migrated on startup, with `.vnb` still readable so an older install upgrades in place.
- **Filenames come from the notebook's name**, not its id — `python-sales-intelligence.anb` rather than a UUID. The id lives in the file and is indexed, and renaming a notebook renames its file.
- **`arima register`** associates `.anb` with Arima (coffee-bean icon, MIME type `application/vnd.arima.notebook+json`), and **`arima open <file>`** starts the server if it is cold, imports notebooks from outside the workspace, and opens straight to them.

### Every tutorial explains itself
- All **40 built-in tutorials** now carry line-by-line commentary in their code cells, covering the reasoning rather than restating the syntax. Adds **Python 701 - Pipelines, Modules & Orchestration**.

### Fixed
- **Python pipelines ran as Java.** `OrchestrationService` had no `python` branch, so Python steps fell through to the JShell fallback and failed to compile.
- **The XChart marker package claimed common type names.** Every JShell session star-imported `org.knowm.xchart.style.markers.*`, which exports `Circle`, `Rectangle`, `Square`, `Diamond` and more, so ordinary teaching code such as `sealed interface Shape permits Circle, Rectangle` named the chart markers and would not compile. Narrowed to `SeriesMarkers` and `Marker`.
- **MCP could not read tutorials.** The notebook lookups in `McpController` had no tutorial fallback, so `barista_read_notebook java-201` reported "not found" for a tutorial that ships with the product.
- **TypeScript was type-checked a version behind** what it ran on: `--target es2022` rejected `Array.prototype.findLast` (ES2023) in code Node executes correctly. Now `es2023`.
- **New settings never reached the browser.** `SettingsController.copyWithMaskedKey` rebuilds the response field by field and had not been extended, so newly added settings persisted to disk but were invisible to the UI.
- Tutorial fixes: a record's compact constructor needs `public` in JShell (`jshell-401`); `%8,.2f` is an invalid format string and threw at runtime (`jshell-501`); `barista.*` is not available in JShell cells, only `BaristaDisplay` (`arima-101`); a stale `dependsOn` pointed at an anchor that no longer exists (`java-401`).

---


### New notebooks start empty, with a language you choose
- **No more surprise JShell cell.** `NotebookService.createNotebook` no longer seeds a starter cell containing a `System.out.println` snippet. A new notebook is created **empty** and shows the "Add a code or markdown cell to get started" state, so the first cell is whatever you add.
- **Default language is asked for at creation.** The **+ New Notebook** flow replaces the old name-only `prompt()` with a dialog that also asks for the notebook's default language (JShell, Java, JavaScript, TypeScript, C#, F#, C++, or Python). It is stored as `metadata.defaultMode` and adopted by every new code cell instead of hardcoding `jshell`. Individual cells can still switch mode as before.
- `POST /api/notebooks` accepts an optional `mode` field; unrecognised values fall back to `jshell`.

---

### Python — the eighth first-class language
- **Python cells.** A new `python` cell mode runs code in a `python3` subprocess via `PythonExecutionService`, following the same subprocess-per-language pattern as the JS/TS/C#/F#/C++ services and returning the same unified `ExecutionResult`. Interactive `input()` is supported through the shared `InteractiveProcessRunner`, so stdin prompts appear inline in the cell like every other language.
- **PyPI package management.** `PyPiService` installs packages with `pip install --target data/pypi-packages/site`, and that directory is placed on `PYTHONPATH` for every Python cell — so `import <pkg>` resolves without touching system site-packages. The Packages tab streams the live pip log. Uninstall removes exactly the files the install added, tracked with a before/after diff (`pip uninstall` has no `--target` support).
- **Pipelines work unchanged.** Anchors are injected as `#@ anchor` / `#@ depends` comments, so the orchestration DSL is identical to the other seven languages. Python joins Maven, npm, and NuGet as the fourth package ecosystem behind `barista_load_module`.
- Python is optional: without `python3` on `PATH` the other seven languages work normally.

---

### Agents & Skills — a new kind of software entity
- **Agents are notebooks, not plugins.** Any `.vnb` with `metadata.kind = "agent"` (or `"skill"`) becomes a callable unit. `AgentService` projects it into an `AgentSpec` and dispatches to an `AgentProvider` (Claude ships today; adding a provider is one new bean). Unlike every other cell, an agent's body is **natural language** rather than code — it is executed by a local agent CLI, not a language runtime.
- **The agent cell DSL.** `//@ agent:` names the agent to dispatch to, `//@ bind:` binds the answer into a JShell variable so downstream cells consume it as data, and `{{anchor}}` interpolates the live output of any anchored cell into the task text. `//@ depends:` works as it does everywhere else — agents are ordinary nodes in the DAG, which makes multi-agent review chains just another pipeline.
- **Three ways to run one.** The **Agents** tab (browse, task, watch the run stream), inside a pipeline, or over MCP. Runs stream over the existing STOMP `partial_output` channel — no new endpoints or topics.
- **Two new MCP tools** bring the server to ten: `barista_list_agents` and `barista_run_agent`.
- **Six new tutorials** — the `agent-101` → `agent-601` track (Explain Code, Code Reviewer, Test Writer, Reviewer in a Pipeline, Multi-Agent Review, MCP-driven Agent) — bringing the built-in library to **39 tutorials**.

---

### Documentation — brochure rebuilt, repo docs realigned
- **Brochure rewritten and rebuilt** (`docs/brochure/arima-brochure.html` + `.pdf`). Python is now a first-class language card, a new **Agents & Skills** page (09) was added, the architecture diagram gained `PythonExecutionService`, `PyPiService`, `AgentService`, and `McpController`, the MCP section lists all ten tools by name, and the cover enumerates all eight languages plus Agents. The brochure is now 12 pages.
- **Fixed silent page clipping in the brochure.** Every `.page` is a fixed 297mm block with `overflow:hidden`, so content that outgrew a page was being cut from the PDF with no warning — seven pages were affected before this release. A new `docs/brochure/build-brochure-pdf.js` measures every page and **fails the build** if any overflows, and page density was tuned so all 12 fit.
- Corrected stale language counts and lists across `docs/ARCHITECTURE.md` ("all six execution modes" → all eight, plus agents), `CONTRIBUTING.md`, `.claude/skills/architecture-check/SKILL.md`, and the MCP tool lists in `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, and `docs/WELCOME.md`.

---

### First Run Experience — a guided tour of the whole workspace
- **New guided walkthrough on first launch.** New users are now welcomed with an interactive **First Run Experience (FRE)** — a spotlight tour (`js/fre.js`) that drives real navigation through every section of Arima Notebooks (the four workspaces, the notebook canvas, first-class languages, pipelines with named anchors, and cross-notebook reuse) before landing on the main workspace. Each step highlights the actual UI element it describes.
- **Opt-in replay from Settings.** A new **Guided Tour** card in **Settings** lets you **Start tour now** at any time, or toggle **"Show the tour on next launch"** to have it replay automatically. The tour runs once automatically on a fresh install and remembers that it has been seen (`localStorage: arima.fre.done` / `arima.fre.replay`).
- Added `src/main/resources/static/js/fre.js`, FRE styles in `css/arima.css`, the Guided Tour settings card in `index.html`, and wiring in `js/settings.js`. The FRE supersedes the old auto-popup welcome modal on first run.

---

### Repositioning — "the notebook, modernized for the agentic era"
- **Product story realigned across all docs.** Arima Notebooks is now consistently positioned as a ground-up *modernization of the notebook* — a **cross-platform execution plane** where people and AI agents collaborate on the same code — rather than as a Java/JVM-first notebook. Messaging is built around two shifts (real work is polyglot; code is a team sport with AI), **every language first-class** (JavaScript, TypeScript, C#, F#, C++, Java, JShell — with more to come), humans-and-agents on a shared artifact, local-first, and **open source built to be reshaped**.
- **Positioned within the platform.** Arima Notebooks is now introduced as **part of the Arima platform** (Arima being the broader product); product docs describe Arima Notebooks specifically and no longer use bare "Arima" to mean the notebook.
- Removed "Java developers were second-class citizens" / "built from the ground up for Java" framing; languages are now presented as equals.
- Updated: `README.md` (hero + "Why Arima Notebooks?"), `CONTRIBUTING.md` ("Why Contribute?"), `docs/WELCOME.md`, `docs/ARCHITECTURE.md` (overview framing), `docs/view/index.html`, `docs/brochure/arima-brochure.html` (+ rebuilt PDF), and `articles/medium-article.md` / `articles/linkedin-article.md` (+ rebuilt PDFs). Agent guardrail files inherit the description via `docs/WELCOME.md`.

---

### Interactive input for every language + runaway-execution guards + attention bell
- **Interactive stdin in all seven languages.** Reading from standard input now prompts the user inline in the cell — `Scanner`/`System.in` (JShell, compiled Java), `readline`/`process.stdin` (JavaScript, TypeScript), `Console.ReadLine()` (C#, F#), and `std::cin` (C++). A new shared `InteractiveProcessRunner` streams subprocess output and, when a program blocks on stdin, flushes what it printed and shows the same inline terminal prompt JShell already used; the typed line is sent back over the existing STOMP channel. Pipelines and MCP runs stay non-interactive (unchanged).
- **Runaway / never-ending loops can no longer wedge a notebook.** JShell cells now run on a bounded worker with an input-aware compute budget (`maxExecutionTimeMs`, default 30s) enforced via `jshell.stop()`, plus an output-line cap (`maxOutputLines`, default 1000). Previously a `while(true){}` in a JShell cell hung the request thread *and* the session lock forever; now it is stopped with a clear message and the session keeps working. Subprocess languages already had a hard timeout — it is now input-aware (time spent waiting on the user is not counted).
- **Manual Stop button.** Every running cell shows a Stop control that interrupts it immediately — `jshell.stop()` for JShell, process kill for subprocess languages — and unblocks a stuck input wait.
- **Attention bell + desktop notification.** When a cell is waiting for your input and you've switched to another tab or app, the header bell lights up with a count, a desktop notification fires (browser-local Web Notification — no new server/host), and the tab title flashes. Clicking the notification or bell entry jumps straight to the waiting cell (right view + notebook tab), scrolls to it, and focuses its prompt.

---

## [3.2.0] — 2026-07-03

### AI provider migration + Arima Agentic Assistant (AAA)
- **Gemini CLI → Antigravity CLI.** Google retired the standalone Gemini CLI on 2026-06-18; the `gemini_cli` provider now drives the **Antigravity CLI (`agy`)** via `agy -p`, grounded in the repo working directory. The internal provider key `gemini_cli` and the `geminiCliAvailable` status flag are retained for backward compatibility.
- **GitHub Copilot CLI (stdin) → GitHub Copilot SDK.** The `copilot_cli` provider now uses the official **GitHub Copilot SDK for Java** (`com.github:copilot-sdk-java`, MIT), which drives the local `copilot` CLI in server mode over JSON-RPC. It runs **chat-only** — a REJECT-all permission handler denies the SDK's tool/file-edit capabilities, so code reaches cells only through the UI.
- **AI panel rebranded to the "Arima Agentic Assistant (AAA)."** Grounded in the full codebase, AAA can **update the focused cell directly**: when a request implies an edit it auto-applies the change with one-click **Undo**; otherwise (or when no CLI is available) it falls back to plain chat suggestions.
- Added `com.github:copilot-sdk-java` to `pom.xml`; updated the settings panel, server-status labels, and provider switcher (Gemini → Antigravity).

---

## [3.1.1] — 2026-05-25

### Repository move
- The GitHub repository was renamed **`snchande/Venus` → `snchande/arima-notebooks`** (GitHub redirects the old URLs; history, PRs, and branch protection are preserved).
- Updated every repository reference — clone/upstream URLs, `pom.xml` SCM + issue-tracker URLs, the in-app changelog link, the brochure, the articles, and the docs viewer — to `github.com/snchande/arima-notebooks`, and the post-clone directory to `arima-notebooks`.

> **Naming note:** this product/repo is **Arima Notebooks** (`arima-notebooks`), a notebooks subsystem. "Arima" on its own is a broader concept and is intentionally left undefined here.

---

## [3.1.0] — 2026-05-25

**Rebrand — Venus is now Arima Notebooks, brewed by the Barista engine.** The product
name changes everywhere; the pure-Java engine that serves notebooks and provides the core
capabilities is now branded **Barista**, with a coffee identity (the mark is a roasted coffee
bean; tagline *"Interactive notebooks, freshly brewed"*).

### Product → Arima
- "Venus Notebooks" → **Arima Notebooks** across the UI, splash, docs, brochure, and articles
- Coffee-bean brand mark replaces the planet/♀ symbol (favicon, splash, top bar, login, welcome hero)
- CLI launcher renamed `venus` → **`arima`** (`arima.ps1` / `arima.sh` / `arima.cmd`); same subcommands
- Front-end app namespace `Venus.*` → `Arima.*`; stylesheet `venus.css` → `arima.css`

### Engine / system → Barista
- Java package `com.venus` → **`com.barista`**; `VenusApplication` → `BaristaApplication`, and all `Venus*` engine classes → `Barista*`
- Runtime cell API `venus.table()/display()/html()/stats()` → **`barista.*`**; C#/F#/C++ helpers `VenusHtml`/`venusTable` → `Barista*`/`barista*` (all built-in tutorials updated)
- Output-protocol sentinels `VENUS_*` → `BARISTA_*`; environment variables `VENUS_*` → `BARISTA_*`
- MCP tool names `venus_*` → **`barista_*`**; build artifact `venus-notebooks` → `arima-notebooks`
- `.claude` agents renamed: primary `venus` → `arima`; `venus-architect`/`venus-security` → `barista-architect`/`barista-security`; `venus-tutorial-writer` → `arima-tutorial-writer`

> The GitHub repository was `snchande/Venus` at the time of this release; it was renamed to `snchande/arima-notebooks` in [3.1.1].

---

## [3.0.0] — 2026-05-24

**Major release.** Arima becomes an AI-native, locally-hosted notebook platform — built for the
agentic era. Beyond the seven languages, this release adds a built-in MCP server, multi-provider
local-CLI AI, authentication, a cross-platform CLI, and a full agentic contributor stack.
Highlights below; the detailed entries from this release follow.

### Why this is a major version
- **Seven languages** now run side by side — JShell, Java, JavaScript, **TypeScript**, C#, F#, and **C++** (TypeScript and C++ are new in the 2.x line).
- **AI is multi-provider and local-first** — Claude, GitHub Copilot, and Gemini all run as local CLI subprocesses. The stored Anthropic API key and direct HTTP path were removed.
- **Arima is now an MCP server** — any MCP client or agent can drive notebooks, cells, packages, and pipelines programmatically.
- **Authentication** — Spring Security with `local` (default) and `oauth` modes.
- **Cross-platform `arima` CLI** — one launcher per shell with `start/stop/status/build/rebuild/open/logs/version/welcome/docs/agents`.
- **Agentic contributor stack** — `AGENTS.md` guardrails, the `arima` agent + specialist subagents and skills, a PR security gate, a CODEOWNERS founder review, and a 12-page product brochure.
- **In-app Welcome & User Guide** — a first-run overlay with Overview / Admin / Developer / Architecture tracks, reopenable anytime from **Help**, plus a **What's New** panel that appears automatically after you update from the repo.

### Welcome, User Guide & Release Notes (in-app)
- New **Welcome / User Guide** overlay (`static/js/welcome.js`): short Arima highlights and four guided tracks — **Overview**, **Admin**, **Developer**, **Architecture** — each a quick flow through the relevant parts of the UI
- Shows automatically on **first run**; reopen anytime from the **Help** button in the top bar
- **What's New** panel surfaces this changelog's highlights automatically whenever the bundled app version changes (i.e. after you pull a new version from the repo)
- The same welcome is available in the terminal via `arima welcome`, and as the **arima** AI agent in Claude/Copilot/Gemini — one consistent entry point everywhere

### Cross-Platform Arima CLI
- New launchers in the repo root — `arima.cmd` (Windows CMD), `arima.ps1` (PowerShell), `arima.sh` (Linux/macOS) — sharing the same subcommands: `start [--bg]`, `stop`, `status`, `build`, `rebuild`, `open`, `logs`, `version`, `help`
- `start` auto-builds the JAR if missing, launches with the required JShell `--add-opens`/`--add-exports` flags, opens `http://localhost:8585`, and re-launches on exit code 42 (UI-requested restart)
- Background mode (`--bg` / `-Bg`) detaches the server and streams to `arima.log`; `status`/`version` report Java, Node.js, .NET, and Maven
- All three launchers add `welcome` (common entry experience — open the UI, drive Arima over MCP, or personalize via an agentic CLI), `docs` (open the brochure + list documentation), and `agents`/`ai` (detected AI co-pilots, guardrail files, skills, and the **arima** agent wired into the repo)
- Launchers export AI context (`BARISTA_HOME`, `BARISTA_AGENTS_GUIDE`, `BARISTA_SKILLS_DIR`, `BARISTA_AGENTS_DIR`, `BARISTA_AI_COPILOTS`) so the in-UI AI panel and any spawned CLI inherit the architecture guardrails

### AI Contributors — Guardrails, Agent, Skills & Security Gate
- **`AGENTS.md`** at the repo root — single source of truth for the architecture/contribution rules every contributor (human or AI) must follow; mirrored by `CLAUDE.md`, `.github/copilot-instructions.md`, and `GEMINI.md`
- **`arima` agent** — the primary full-functionality AI assistant (welcome, operate, document, and extend Arima), with specialist subagents `arima-architect`, `arima-security`, `arima-tutorial-writer` and auto-invoking skills `architecture-check`, `add-execution-language`, `add-tutorial`, `add-rest-endpoint` (registered in `.claude/`)
- **PR security gate** — `scripts/security-check.ps1` / `.sh` + `.github/workflows/security-check.yml`: scans for secrets, command injection, forbidden frontend deps, Lombok, unknown outbound hosts, and layer-crossings; converts a PR back to draft until findings are resolved
- **`.github/CODEOWNERS`** — every PR requires founding-contributor review before merge to `master`
- **Product brochure** — branded 12-page PDF at `docs/brochure/arima-brochure.pdf`

### AI Providers — Local CLI, Multi-Provider
- AI now runs exclusively through **local CLI subprocesses**; the direct Anthropic HTTP API path and the stored API key were removed (eliminates content-filter policy errors and a second credential to manage)
- Three interchangeable providers: **Claude** (`claude`), **GitHub Copilot** (`github-copilot-cli` / `copilot`), **Gemini** (`gemini`) — switch in **Settings → AI Provider**
- Settings tab: the API Key field is replaced with per-provider CLI status; status flags are `claudeCliAvailable`, `githubCopilotAvailable`, `geminiCliAvailable` (was `claudeApiKeySet`)
- `GET /api/llm/provider` reports the active provider, model, and availability; all `/api/llm/*` routes are provider-agnostic
- New services: `GitHubCopilotService`, `CopilotCliService`, `GeminiService` alongside `ClaudeService`

### MCP Tool Server
- Built-in **Model Context Protocol** server over HTTP+SSE (JSON-RPC 2.0) at `/api/mcp/sse` + `/api/mcp/messages` (`McpController`)
- Eight tools: `barista_execute_code`, `barista_list_notebooks`, `barista_read_notebook`, `barista_run_pipeline`, `barista_search_cells`, `barista_load_module`, `barista_create_notebook`, `barista_append_cell`
- Any MCP client (Claude Desktop, Claude Code, custom agents) can drive Arima programmatically — see `docs/API.md` for client setup

### Authentication — Local & OAuth2
- Spring Security added with two modes via `barista.auth.mode`: `local` (default — OS username, no login) and `oauth` (OAuth2 social login)
- OAuth client config stored in `data/oauth-config.json` (gitignored); `SecurityConfig`, `OAuthClientConfig`, `OAuthConfigService`, `UserService`, `AuthProvider`, `OAuthConfig`, `UserProfile` added
- New endpoints: `/api/user/me`, `/api/user/me/email`, `/api/user/oauth-config`, `/api/user/logout`, and `PUT /api/settings/auth-mode`
- Notebooks are scoped per user (`notebooks/{userId}/`)

### Variable Inspector — All Languages
- Variable inspection extended from JShell to **all subprocess runtimes** (JS, TS, C#, F#, C++) via `VariableInspector`
- Per-tab inspector UX

### Open-Source Hardening
- `.github/CODEOWNERS`, `.github/workflows/security-check.yml`, and `scripts/security-check.{sh,ps1}` enforce the architecture/security guardrails in CI and pre-flight
- `AGENTS.md` is the single source of truth for AI contributors; `CLAUDE.md`, `.github/copilot-instructions.md`, and `GEMINI.md` defer to it
- `.claude/` registers slash commands, skills, and subagents for the agentic contribution loop

### Documentation — Agentic Workflow Framing

- **README.md** — new "Built for the Agentic Era" section explaining the *use → customize → contribute* loop, with three surfaces (Arima UI, AI CLI in repo, MCP-aware agents) and sample prompts for each
- **CONTRIBUTING.md** — new "Contributing in an Agentic Cycle" section at the top; the recommended path is now `ask AI CLI in the repo` → `try locally` → `ask AI to package the PR`. Traditional fork-edit-PR still works.
- **docs/USAGE.md** — new "Agentic Workflows — Use, Customize, Contribute" section in the AI Assistant chapter; documents the three surfaces and the MCP tool surface (`barista_create_notebook`, `barista_append_cell`, `barista_read_notebook`, `barista_run_pipeline`, `barista_load_module`)
- **docs/ARCHITECTURE.md** — new "Design Principles (Built for the Agentic Era)" preamble that names the six constraints (no frontend build, no Lombok, subprocess-per-language, small conventions, single JAR/port, MCP-native) and explains *why* — they're what makes the customize-in-an-hour promise real
- **docs/SETUP.md** — new "Recommended: an AI CLI" section; positions installing Claude / Copilot / Gemini CLI as the standard setup step, not an optional extra
- **Articles** — long-form articles in `articles/medium-article.md` and `articles/linkedin-article.md` (with rendered PDFs and embedded SVG diagrams) telling the same story in long-form
- **`.ipynb` interop** — clarified everywhere that Arima ↔ Jupyter round-tripping is *planned for the next update*, not currently shipped

### Variable Inspector & Tab UX
- **Variable inspector** extended to **all subprocess languages** — JavaScript, TypeScript, C#, F#, and C++ (previously JShell-only), via `util/VariableInspector`; inspect live types, values, and structure without printing
- Tab navigation UX refinements across the workspace

---

## [2.1.0] — 2026-05-10

Arima 2.1 adds **TypeScript** as a full first-class language — bringing the total to **seven execution modes** (JShell · Java · JavaScript · TypeScript · C# · F# · C++). The integration leverages Node.js's built-in type-stripping (Node 22.6+), so no additional runtime is required beyond the existing Node.js dependency.

### TypeScript Language Support

- **TypeScript cells** — new `typescript` mode using Node.js's built-in type-stripping
  - Each cell runs as a per-cell isolated `node --experimental-strip-types script.ts` subprocess (Node 22.6+; Node 24+ runs `.ts` files natively)
  - Built-in **typed** Arima preamble: `barista.table(rows)`, `barista.display(value)`, `barista.html(content)`, `barista.stats(arr)` — full TS signatures injected at the top of every cell
  - Shares `data/npm-modules/` with JavaScript cells — `import * as ss from "simple-statistics"` works without any extra setup
  - Line-number correction on errors (preamble offset removed)
  - CodeMirror syntax highlighting uses `text/typescript`
  - TS blue (`#3178c6`) cell badge and `◆` icon

- **Optional `tsc` type-check** — if the TypeScript compiler is on the PATH, Arima runs `tsc --noEmit` before each cell with relaxed-strict settings:
  - Type errors (e.g. `Type 'string' is not assignable to type 'number'`) reported **before** execution starts
  - Type-check failures are folded into the cell's error stream alongside runtime errors
  - Without `tsc`, cells still run — only the type-check pass is skipped
  - Install with `npm install -g typescript` to enable

- **Console TypeScript runtime** — Interactive Console adds a `◆ TypeScript` button alongside JShell/Java/JS for ad-hoc TS expressions

- **AI integration** — the AI Assistant recognises `typescript` mode and emits `` ```ts `` fenced blocks in its responses; the language-conversion banner offers TS targets when cycling cell modes

### Tutorials & Examples

- **5 new tutorial notebooks** — full TS 101 → 501 series matching the JS coverage:
  - `ts-101` — Types, inference, interfaces, functions, arrays, tuples
  - `ts-201` — Generics, classes, modules, union/intersection types
  - `ts-301` — Conditional types, mapped types, `infer`, template literal types
  - `ts-401` — Async patterns, Result types, branded types, builder pattern, ES2024 features
  - `ts-501` — Typed data analysis with stats, HTML reports, and defensive parsing
- **New example notebook** — `typescript-intro` — five-minute TS tour
- Tutorial total goes from **23 → 28**

### REST API & Status

- `POST /api/shell/execute` accepts `"mode": "typescript"`
- `GET /api/settings/status` now reports `typescriptAvailable`, `tscAvailable`, and `typescriptDetail`
- TypeScript cells participate in pipelines via the existing `//@ depends:` system (per-cell isolation, same model as JavaScript and C++)

### Documentation

- In-app docs (Usage Guide, Setup, Tutorials, API Reference, Architecture, Developer Guide) updated with TypeScript sections
- README, `docs/USAGE.md`, `docs/API.md`, `docs/ARCHITECTURE.md`, `docs/SETUP.md`, and `docs/cheatsheet.html` extended
- Mode cycle in mode-toggle tooltip: **JShell → Java → JS → TS → C# → F# → C++ → JShell**

### Internal

- New `service/TypeScriptExecutionService.java` — mirrors `NodeJsExecutionService` with TS type-stripping + optional `tsc` integration
- Wired into `ShellController`, `OrchestrationService`, and `SettingsController`
- No new dependencies in `pom.xml` — TypeScript support is delivered purely through Node.js subprocesses

---

## [2.0.0] — 2026-04-17

Arima 2.0 adds **C# and F#** as full first-class languages — including pipeline dependency injection, NuGet package management, and cross-notebook cell references across all five execution modes. This is a **major feature release**.

### C# Language Support

- **C# cells** — new `csharp` mode using `dotnet run` (standard .NET SDK, no extra tools)
  - Each cell runs as a **C# 9+ top-level program** compiled and executed per cell
  - Auto-injects standard usings: `System`, `System.Linq`, `System.Collections.Generic`, `System.Text`, `System.IO`
  - Built-in helpers: `BaristaHtml(html)`, `BaristaDisplay(obj)`, `BaristaTable<T>(list)`
  - Line-number correction on compiler errors (preamble offset removed)
  - Type declarations (`class`, `record`, `struct`, `enum`, `namespace`) are automatically re-ordered to satisfy the C# 9+ CS8803 rule
  - Inline `#r "nuget:"` directives are stripped — use the NuGet tab instead

- **C# pipeline dependency injection** — `//@ depends:` works across isolated subprocesses:
  - Ancestor cells' source is injected into the dependent cell's compilation unit
  - Ancestor output is silenced via `Console.SetOut(TextWriter.Null)` — only the current cell's output is visible
  - Full transitive closure resolved in topological order
  - Session anchor cache stores each successfully executed anchor's source for reuse

### F# Language Support

- **F# cells** — new `fsharp` mode using `dotnet fsi --exec` (built into .NET SDK 6+)
  - Each cell runs as an `.fsx` script; no `dotnet-script` required
  - Pre-opened namespaces: `System`, `System.Linq`, `System.Collections.Generic`
  - Built-in helpers: `baristaHtml`, `baristaDisplay`, `baristaTable`
  - Inline `#r "nuget:"` directives are extracted from user code and placed at the top of the script file (before any `open` statements), ensuring correct resolution order

- **F# pipeline dependency injection** — full `//@ depends:` support:
  - Ancestor source injected with `System.Console.SetOut(System.IO.TextWriter.Null)` wrapping for output suppression
  - NuGet directives from all ancestors are deduplicated and placed at the very top of the combined script

### Cross-Notebook References (all 5 languages)

- `//@ depends: notebook:{notebookId}/{anchorName}` syntax now works in **C# and F# cells**
- **JShell / Java**: foreign cell executed in the shared session (as before)
- **C# / F#**: Arima builds an **expanded source** — the full transitive dependency chain of the foreign cell, annotation-stripped and concatenated in topological order — and caches it under the cross-notebook key; when the dependent cell runs, this expanded source is injected with output suppressed
- New example notebooks demonstrating cross-notebook C# references:
  - `csharp-shared-utils` — reusable types (`Transaction`, `Product`) and helpers (`Stats`, `Format`)
  - `csharp-cross-notebook` — finance analysis pipeline importing from `csharp-shared-utils`

### NuGet Package Manager

- New `NuGet (C# / F#)` sub-tab in the Packages panel
- Install packages by `PackageId` + `Version`; popular package quick-fill buttons
- Packages stored in `data/nuget-packages.json`, injected per-cell automatically
- REST API: `GET /api/nuget`, `POST /api/nuget/install`, `DELETE /api/nuget/{packageId}`

### Execution Model

- **Mode cycle** extended: `JShell → Java → JS → C# → F# → JShell`
- **Cell badges** — purple for C# (`#a855f7`), orange for F# (`#f97316`)
- Session restart now clears the C#/F# anchor source cache in addition to JShell state

### Tutorial Library

- 4 new tutorial notebooks: `csharp-101`, `csharp-201`, `fsharp-101`, `fsharp-201`
- 2 new example notebooks (cross-notebook pipeline demo): `csharp-shared-utils`, `csharp-cross-notebook`
- Notebook browser now includes an **Examples & Demos** subcategory

### AI Assistant

- System prompt updated with C# and F# context, helpers, pipeline dep injection rules, and NuGet guidance
- Cell mode labels updated (`C# (dotnet run)`, `F# (dotnet fsi)`)

### Open Source Preparation

- Added `CONTRIBUTING.md` — full contributor guide including dev setup, coding standards, PR process
- Added `MAINTAINER.md` — governance model, maintainer hierarchy, release process
- Rewrote `README.md` — expanded GitHub landing page with full feature and tutorial documentation
- Updated `.gitignore` — added `data/npm-modules/`, `data/users/`, `notebooks/local-*/`

### Documentation

- `docs/ARCHITECTURE.md` — complete rewrite covering all 5 execution modes, pipeline system, cross-notebook refs, session anchor cache, real-time output sentinels, server lifecycle, data science stack, and security model
- `docs/SETUP.md` — added .NET SDK install guide; removed `dotnet-script` references
- `docs/USAGE.md` — full C# and F# sections with helpers, NuGet, pipeline dep examples
- `docs/API.md` — NuGet endpoints documented; cross-notebook ref syntax added
- `README.md` — replaced `dotnet-script` requirement with standard .NET SDK 6+

---

## [1.2.0] — 2026-03-29

### Multi-Runtime Interactive Console

**Console tab completely reworked — now supports three independent runtimes with code completion.**

#### New Features
- **Runtime selector bar** in the Console tab — three toggle buttons:
  - `☕ JShell` — Java snippets with shared session state (server-side execution)
  - `♨ Java` — compile and run a full Java class per command
  - `⬡ JavaScript` — Node.js REPL
- **Active runtime badge** in the console header showing the current runtime icon + name
- **Tab completion** for all three runtimes:
  - **JShell** — server-side completion via `JShell.sourceCodeAnalysis().completionSuggestions()`
  - **Java / JavaScript** — client-side keyword/snippet hints from a curated static list
  - Completion **hint box** drops up above the input; clickable items + keyboard cycling
  - Press Tab repeatedly to cycle through suggestions; any other key hides the box
- Input placeholder text updates dynamically to match the selected runtime
- Console output now supports `BARISTA_HTML:` sentinel — inline SVG/HTML charts render in the console, same as in notebook cells
- Input prefix icon reflects the active runtime (`[☕]`, `[♨]`, `[⬡]`)
- History buffer expanded to 500 entries

#### New REST Endpoint
- `POST /api/shell/complete` — returns JShell completion suggestions
  - Body: `{ sessionId, source, cursor }`
  - Response: `{ completions: [...] }`

#### Backend Changes
- `JShellManager.complete(sessionId, source, cursor)` — new method wrapping `SourceCodeAnalysis`
- `ShellSession.getJShell()` — new accessor needed by JShellManager completion

#### CSS Changes (`arima.css`)
- `.console-runtime-bar` / `.console-runtime-btn` / `.console-runtime-btn.active`
- `.console-runtime-badge`
- `.console-hint-box` / `.hint-item` / `.hint-item.active` / `.hint-more`

---

### Notebook Browser Redesign

**Replaced the single flat dropdown with a structured two-section browser.**

#### New Features
- **Notebook Browser** (click the folder icon) now shows two distinct sections:
  - **My Notebooks** — personal notebooks with a `+ New Notebook` button
  - **Arima Tutorials** — built-in read-only tutorials (separate from user notebooks)
- Tutorial notebooks are grouped by **language** (JShell / Java / JavaScript), then **subcategory**:
  - Basics & Foundations
  - Advanced
  - Data Science & Analytics
- Each tutorial card shows:
  - Level badge (e.g. `101`, `201`)
  - Language icon tag
  - `tutorial` read-only badge
- **Filter search** works across both sections simultaneously
- Tutorial notebooks open in a read-only tab — auto-save is disabled; status bar notes `(tutorial — read-only)`
- `loadNotebook(id, isTutorial)` now routes tutorial IDs to `/api/notebooks/tutorials/{id}`

#### CSS Changes (`arima.css`)
- `.nbb-section` / `.nbb-section-hdr` / `.nbb-section-title` / `.nbb-section-note`
- `.nbb-action-btn`
- `.nbb-lang-group` / `.nbb-lang-hdr`
- `.nbb-subcat` / `.nbb-subcat-label`
- `.nbb-level` / `.nbb-lang-tag` / `.nbb-ro-tag`
- `.nbb-tutorials` — left accent border on tutorial cards
- `.nb-browser-card-list` — grid wrapper for card sets
- `.nb-browser-list` forced to `display: block` to allow section-based layout

---

### Tutorial System

**New `notebooks/tutorials/` directory — globally accessible, not user-scoped.**

#### Tutorials Added
| ID | Title | Language | Level | Subcategory |
|----|-------|----------|-------|-------------|
| `jshell-101` | JShell Basics | JShell | 101 | Basics & Foundations |
| `jshell-201` | JShell Intermediate | JShell | 201 | Basics & Foundations |
| `jshell-301` | JShell Advanced | JShell | 301 | Advanced |
| `jshell-401` | JShell Functional & Concurrency | JShell | 401 | Advanced |
| `jshell-501` | JShell Design Patterns | JShell | 501 | Advanced |
| `java-101` | Java Basics | Java | 101 | Basics & Foundations |
| `java-201` | Java Intermediate | Java | 201 | Basics & Foundations |
| `java-301` | Java Advanced | Java | 301 | Advanced |
| `java-401` | Java Functional & Streams | Java | 401 | Advanced |
| `java-501` | Java Design Patterns | Java | 501 | Advanced |
| `java-601` | Java Data Science | Java | 601 | Data Science & Analytics |
| `js-101` | JavaScript Basics | JavaScript | 101 | Basics & Foundations |
| `js-201` | JavaScript Intermediate | JavaScript | 201 | Basics & Foundations |
| `js-301` | JavaScript Advanced | JavaScript | 301 | Advanced |
| `js-401` | JavaScript Data Science | JavaScript | 401 | Data Science & Analytics |
| `js-501` | JavaScript D3 Visualization | JavaScript | 501 | Data Science & Analytics |

#### New API Endpoints
- `GET /api/notebooks/tutorials` — list all tutorials with metadata
- `GET /api/notebooks/tutorials/{id}` — load a single tutorial

#### Backend Changes
- `NotebookService.listTutorials()` — scans `notebooks/tutorials/`, returns sorted list
- `NotebookService.getTutorial(id)` — reads single tutorial from tutorials directory
- `NotebookController` — added `/tutorials` and `/tutorials/{id}` routes (declared before `/{id}` to prevent Spring MVC path-variable collision)
- `NotebookService.readNotebookMeta()` — now includes `metadata` map field

---

## [1.1.0] — 2026-03-14

### Cell Orchestration & Pipeline System
- New `PIPELINE` cell type — orchestrates other cells in dependency order
- `//@ annotation` DSL in Java comments: `anchor:`, `depends:`, `pipeline:`, `steps:`, `description:`, `on-error:`
- `OrchestrationService` — Kahn's topological sort, DFS cycle detection, transitive closure
- `orchestration.js` — client-side dependency status badges (pending / running / ok / error / stale)
- New API endpoints: `POST /execute-pipeline`, `POST /execute-with-deps`, `POST /run-to-here`, `GET /validate-graph/{id}`
- `Cell.java` extended: `mode`, `anchor`, `dependsOn` (List), `pipelineSteps` (List)

### Multi-Mode Cells
- Every CODE cell now has a `mode` field: `jshell` (default) or `java` or `nodejs`
- `JavaCompilerService` — compiles to a temp dir, runs in subprocess, captures stdout/stderr
- `NodeJsExecutionService` — executes JavaScript via Node.js subprocess
- `ShellController` routes `mode` field to the correct engine
- Cell mode button cycles: JShell → Java → JavaScript → JShell

### In-line Chart Output
- `BARISTA_HTML:` sentinel: output lines starting with this prefix are rendered as inline HTML/SVG
- `barista.html(content)` helper function available in all JavaScript cells
- `BaristaDisplay` (Java) renders XChart charts as base64 PNG inline in cell output

### Data Science Stack (Built-in, no install)
- XChart 3.8.6 — chart rendering
- Commons Math 3.6.1 — statistics, regression, distributions
- Tablesaw 0.43.1 — DataFrames
- OpenCSV 5.9 — CSV parsing
- All imported automatically in every JShell session

### Documentation System
- `docs.js` — 8-section help overlay (Usage, Tutorials, Pipeline, MCP & Agents, API, Architecture, Setup, Developer Guide)
- Accessible via **Help** button in toolbar

### Bug Fixes
- `PackageService.applyPackagesToSession()` now called when a JShell session is first created (packages were missing from new sessions)
- Kernel restart now re-applies packages automatically
- `console-tab.js` CSS class names fixed (`cout-*` — was using `console-entry-*`)
- JShell error messages now include contextual hints
- `showOutput` in `notebook.js` now shows separate compile-error formatting

---

## [1.0.0] — 2026-03-08

### Initial Release
- Spring Boot 3.2.3 server on port 8585
- JShell-powered interactive notebook cells
- Notebook CRUD — `.vnb` JSON format saved to `notebooks/{userId}/`
- Maven package installer — downloads JARs from Maven Central, injects into JShell classpath
- npm package installer — downloads npm packages to `data/npm-modules/`
- Claude AI assistant — chat and notebook generation via Anthropic API
- STOMP WebSocket real-time output
- Dark/light theme
- Session management with per-notebook JShell isolation
- Step Navigator — walk through cells one by one

---

## Versioning

Arima Notebooks follows [Semantic Versioning](https://semver.org/):

- **MAJOR** — breaking changes to notebook file format or API
- **MINOR** — new features, new tabs, new endpoints, new cell types
- **PATCH** — bug fixes, style tweaks, documentation updates
