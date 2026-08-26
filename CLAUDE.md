# Arima Notebooks - Claude Instructions

> **Architecture guardrails for all AI agents live in [`AGENTS.md`](AGENTS.md).** Read it before making non-trivial changes. The companion files for Copilot (`.github/copilot-instructions.md`) and Gemini (`GEMINI.md`) all point back to `AGENTS.md` as the single source of truth.
>
> **Skills and subagents** are registered in [`.claude/`](.claude/README.md). Skills (`architecture-check`, `add-execution-language`, `add-tutorial`, `add-rest-endpoint`) auto-invoke when relevant. Subagents: the **primary `arima` agent** plus the specialists `arima-architect`, `arima-security`, `arima-tutorial-writer`.

## The `arima` agent + welcome behavior

When a user starts working in this repo — greets you, asks "what can I do / how do I start", or seems new — act as the **`arima` agent** ([`.claude/agents/barista.md`](.claude/agents/barista.md)) and deliver the common welcome from [`docs/WELCOME.md`](docs/WELCOME.md). Present the three paths and let them pick:

1. **Open the UI** — offer to run `arima start` (or `arima open` if already running) → http://localhost:8585.
2. **Drive Arima Notebooks over MCP** — Arima Notebooks exposes an MCP server at `/api/mcp/sse` + `/api/mcp/messages` with tools `barista_execute_code`, `barista_list_notebooks`, `barista_read_notebook`, `barista_run_pipeline`, `barista_search_cells`, `barista_load_module`, `barista_create_notebook`, `barista_append_cell`, `barista_list_agents`, `barista_run_agent`. Offer to help connect an MCP client.
3. **Personalize & extend** — *your* differentiator: you can change Arima Notebooks itself (add a language, theme tweak, tutorial, bug fix) following the guardrails below, then package a PR.

Always offer to open docs (`arima docs` or read the relevant file). **Key difference to state:** the plain `arima` CLI operates/automates Arima Notebooks (incl. MCP) but cannot change its code; an agentic CLI like you can also personalize and extend it. Same welcome is delivered by the `arima welcome` command for terminal users.

## Project Overview
Arima Notebooks is a Java-based interactive notebook environment (similar to Jupyter) powered by
JShell (Java's interactive REPL). It runs as a local Spring Boot web server with a single-page
web UI for writing and executing code in **eight languages** — Java/JShell, JavaScript (Node.js),
TypeScript (Node.js type-stripping + optional `tsc`), C# / F# (.NET SDK), C++ (MSVC/GCC/Clang), and
Python (python3 subprocess) — managing Maven, npm, NuGet, and PyPI packages, and using
Claude/Copilot/Antigravity AI assistance.

## Technology Stack
- **Backend**: Java 21 + Spring Boot 3.2.x
- **REPL Engine**: JDK JShell API (`jdk.jshell` module)
- **Subprocess runtimes**: Node.js (JS/TS), .NET SDK (C#/F#), MSVC/GCC/Clang (C++), Python 3 (Python)
- **Real-time**: STOMP over WebSocket (SockJS)
- **Frontend**: Vanilla HTML/CSS/JavaScript (no build step required)
- **AI**: Three providers — Claude, GitHub Copilot, Antigravity — Claude & Antigravity invoked as **local CLI subprocesses** via `ProcessBuilder`; GitHub Copilot via the **GitHub Copilot SDK** (drives the local `copilot` CLI). No HTTP API key managed by Arima Notebooks
- **Package Managers**: Maven Central (JShell classpath), npm registry (`data/npm-modules/` for JS/TS), NuGet.org (`#r "nuget:"` for C#/F#), PyPI (`data/pypi-packages/` on `PYTHONPATH` for Python)
- **Auth**: Spring Security with two modes — `local` (default, OS username, no login) and `oauth` (OAuth2 social login via `data/oauth-config.json`)
- **MCP**: Built-in Model Context Protocol server (HTTP+SSE, JSON-RPC 2.0) at `/api/mcp` exposing Arima Notebooks as a tool server
- **Data Science**: XChart, Apache Commons Math, Tablesaw, OpenCSV — bundled, auto-imported in JShell
- **Storage**: JSON files on disk (`notebooks/` and `data/` directories)

## Key Commands

### Build
```bash
mvn clean package -DskipTests
```

### Run (Development)
```bash
mvn spring-boot:run
```

### Run (Production JAR)
```bash
java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     -jar target/arima-notebooks-1.0.0-SNAPSHOT.jar
```

### Test
```bash
mvn test
```

### Quick Start — Arima Notebooks CLI (recommended)
The cross-platform `arima` CLI handles build, start, stop, status, and browser-open in one command.
All three launchers accept the same subcommands: `install · update · uninstall · start [--bg] · stop · restart · status · open · logs · build · rebuild · mcp · version · welcome · docs · agents · brew · help`.
Run with **no subcommand** for the home screen: live server metadata (version, when it started, uptime, PID, sessions, notebooks, MCP, languages) when it is running, the full command list, or a first-run setup walkthrough when nothing is built yet. The live block comes from `GET /api/system/info`.
```bash
# Windows CMD            Windows PowerShell        Linux / macOS
arima                    ./arima.ps1               ./arima.sh
arima start --bg         ./arima.ps1 start -Bg     ./arima.sh start --bg
arima status             ./arima.ps1 status        ./arima.sh status
```
`start` auto-builds the JAR if missing, launches with the required JShell `--add-opens` flags, and opens
`http://localhost:8585`. The foreground loop also honors exit code 42 (UI-requested restart).

### Quick Start (minimal scripts)
```bash
# Unix/Mac
./scripts/start.sh

# Windows
scripts\start.bat
```

## Project Structure
```
arima/
├── CLAUDE.md                          # This file
├── AGENTS.md                          # Architecture guardrails (source of truth for all AI agents)
├── GEMINI.md / .github/copilot-instructions.md  # Companion agent files → defer to AGENTS.md
├── README.md                          # User-facing documentation
├── pom.xml                            # Maven build file
├── arima.cmd / arima.ps1 / arima.sh   # Cross-platform Arima CLI launchers
├── .claude/
│   ├── settings.json                  # Claude Code settings (permissions, registry)
│   ├── README.md                      # Registers commands/skills/agents
│   ├── commands/                      # Slash commands: start, build, create-notebook
│   ├── skills/                        # architecture-check, add-execution-language, add-tutorial, add-rest-endpoint
│   └── agents/                        # arima-architect, arima-security, arima-tutorial-writer
├── docs/
│   ├── ARCHITECTURE.md  API.md  SETUP.md  USAGE.md
│   ├── cheatsheet.html                # Developer cheatsheet (gitignored)
│   ├── brochure/                      # Product brochure (HTML + PDF)
│   ├── view/                          # Standalone docs viewer
│   └── screenshots/
├── scripts/
│   ├── start.sh / start.bat           # Minimal launchers (watchdog loop)
│   └── security-check.sh / security-check.ps1  # Pre-flight security scan
├── src/main/java/com/barista/
│   ├── BaristaApplication.java          # Spring Boot entry point
│   ├── config/                        # WebSocketConfig, CorsConfig, SecurityConfig, OAuthClientConfig
│   ├── model/                         # Notebook, Cell, CellType, PackageInfo, NpmPackageInfo,
│   │                                  #   NuGetPackageInfo, BaristaSettings, ExecutionResult,
│   │                                  #   AuthProvider, OAuthConfig, UserProfile
│   ├── shell/                         # JShellManager (sessions) + ShellSession (per-session JShell)
│   ├── util/                          # BaristaDisplay (chart→PNG), BaristaInput, VariableInspector
│   ├── service/                       # Business logic — one per concern:
│   │   ├── NotebookService            #   CRUD for .vnb files
│   │   ├── PackageService             #   Maven Central downloads → JShell classpath
│   │   ├── NpmPackageService          #   npm install → data/npm-modules/
│   │   ├── NuGetService               #   NuGet package management for C#/F#
│   │   ├── JavaCompilerService        #   Full javac compile-and-run (Java mode)
│   │   ├── NodeJsExecutionService     #   JavaScript subprocess
│   │   ├── TypeScriptExecutionService #   TS via Node type-stripping + optional tsc
│   │   ├── DotNetExecutionService     #   C# (dotnet run) + F# (dotnet fsi)
│   │   ├── CppExecutionService        #   C++ via MSVC/GCC/Clang
│   │   ├── PythonKernelService        #   Long-lived python session per shell session
│   │   ├── PythonExecutionService     #   One-shot python subprocess + interpreter detection
│   │   ├── PyPiService                #   PyPI package management for Python (pip --target)
│   │   ├── OrchestrationService       #   Pipeline DAG: topo-sort, cycle detection, deps
│   │   ├── ClaudeService / GitHubCopilotService / CopilotCliService / GeminiService  # AI providers (Copilot→SDK; Gemini slot→Antigravity agy)
│   │   ├── OAuthConfigService / UserService  # Auth
│   │   └── SettingsService            #   Settings persistence
│   └── controller/                    # REST + WebSocket — thin, one service call each:
│       ├── NotebookController  ShellController  PackageController
│       ├── NpmPackageController  NuGetController  LLMController
│       ├── SettingsController  SystemController  UserController
│       └── McpController               #   MCP server (/api/mcp/sse, /api/mcp/messages)
├── src/main/resources/
│   ├── application.properties         # Port 8585, auth mode, MCP, actuator
│   └── static/                        # Served at http://localhost:8585/ (no build step)
│       ├── index.html                 # Single-page app entry point
│       ├── css/arima.css
│       └── js/                        # app, notebook, console-tab, packages, npm-packages,
│                                      #   nuget, orchestration, settings, ai-assistant,
│                                      #   docs, server-lifecycle
├── notebooks/                         # *.vnb JSON files
│   ├── tutorials/                     # 25 built-in tutorials (gitignored personal dirs excluded)
│   ├── examples/                      # Example & demo notebooks
│   └── {userId}/                      # Per-user notebooks (e.g. local-{osuser})
└── data/                              # Runtime data — gitignored (settings, packages, users)
```

## Important Files
- `src/main/java/com/barista/shell/JShellManager.java` - Core JShell execution engine
- `src/main/java/com/barista/service/NodeJsExecutionService.java` - JavaScript subprocess executor
- `src/main/java/com/barista/service/TypeScriptExecutionService.java` - TypeScript executor (Node type-stripping + optional `tsc`)
- `src/main/java/com/barista/service/DotNetExecutionService.java` - C# + F# executor
- `src/main/java/com/barista/service/CppExecutionService.java` - C++ executor (auto-detects MSVC/GCC/Clang)
- `src/main/java/com/barista/service/PythonKernelService.java` - Python executor (one long-lived interpreter per session, PyPI on PYTHONPATH)
- `src/main/java/com/barista/service/PyPiService.java` - PyPI package management (pip `--target`)
- `src/main/java/com/barista/service/ClaudeService.java` - Claude integration via the local `claude` CLI subprocess
- `src/main/java/com/barista/service/OrchestrationService.java` - Pipeline/dependency-graph engine
- `src/main/java/com/barista/controller/McpController.java` - MCP server (HTTP+SSE, JSON-RPC 2.0)
- `src/main/java/com/barista/config/SecurityConfig.java` - Auth modes (local / OAuth2)
- `src/main/resources/static/index.html` - The entire frontend UI
- `src/main/resources/application.properties` - Server config (port 8585, auth mode, MCP)
- `data/settings.json` - Runtime settings (gitignored); `data/oauth-config.json` - OAuth client secrets (gitignored)

## Development Guidelines
1. **JShell Module Access**: Always run with `--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED`
2. **Notebook Format**: Notebooks are stored as `.vnb` JSON files in the `notebooks/` directory
3. **Package Storage**: Maven JARs in `data/packages/`; npm modules in `data/npm-modules/`; NuGet recorded in `data/nuget-packages.json`
4. **Secrets**: Never commit `data/settings.json` or `data/oauth-config.json` — both are gitignored. Do not add new files under `data/` to git.
5. **Frontend**: No build step - pure HTML/CSS/JS served by Spring Boot static resource handler
6. **WebSocket**: All real-time output uses STOMP over SockJS at `/ws`
7. **No `Runtime.exec(String)`**: build subprocesses with `ProcessBuilder(List<String>)` only — command injection is blocked by `scripts/security-check`

## AI Provider Configuration
Arima Notebooks does **not** store an API key for AI. All three providers run locally, using whatever auth the
underlying CLI already has. Claude and Antigravity are invoked as **CLI subprocesses**; GitHub Copilot
runs through the **GitHub Copilot SDK** (`com.github:copilot-sdk-java`), which drives the local
`copilot` CLI in server mode (chat-only — the SDK's file-editing tools are denied via a REJECT-all
permission handler):
1. Install a CLI — `claude` ([claude.ai/code](https://claude.ai/code)), the GitHub Copilot CLI (`copilot`), or Antigravity (`agy`, https://antigravity.google/docs/cli-install)
2. Authenticate it once in a terminal (e.g. `claude auth`, or run `agy` to sign in)
3. Pick the active provider in **Settings → AI Provider** (or via `PUT /api/settings`)

`GET /api/settings/status` reports each provider's availability (`claudeCliAvailable`,
`githubCopilotAvailable`, `geminiCliAvailable`). NOTE: the `gemini_cli` provider key and
`geminiCliAvailable` flag are retained for backward compatibility but now route to the Antigravity
CLI (`agy`) — Google retired the standalone Gemini CLI on 2026-06-18.

## Common Tasks

### Add a new REST endpoint
1. Add method to appropriate controller in `src/main/java/com/barista/controller/`
2. Add corresponding service method if needed
3. Update `docs/API.md`

### Add a new UI tab
1. Add tab button in `index.html`
2. Add tab content `<div>` in `index.html`
3. Create corresponding JS file in `static/js/`
4. Import the JS file in `index.html`

### Modify notebook file format
1. Update `src/main/java/com/barista/model/Notebook.java`
2. Update `src/main/java/com/barista/model/Cell.java`
3. Update `NotebookService.java` serialization
4. Update frontend `notebook.js`

## Troubleshooting
- **JShell not found**: Ensure a full JDK 17+ (21 recommended) is installed (not just JRE). Check `java.home` system property.
- **WebSocket connection fails**: Check browser console for STOMP errors. Ensure `/ws` endpoint is accessible.
- **Package download fails**: Check internet connectivity and Maven Central / npm registry / NuGet.org availability.
- **AI not responding**: The selected provider's CLI must be installed and authenticated. Run `claude auth` (or the Copilot/Antigravity equivalent: authenticate the `copilot` CLI, or run `agy` to sign in) and check **Settings → Server Status** — the active provider should show ✓ Found.
- **TypeScript cells fail**: Node.js 22.6+ required for built-in type-stripping; install `tsc` (`npm i -g typescript`) for type-check diagnostics.
- **C++ / C# / F# cells fail**: Ensure a C++ compiler (MSVC/GCC/Clang) or the .NET SDK is on `PATH`; `arima status` reports what's detected.
