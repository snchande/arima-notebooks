# Arima Notebooks — Architecture

## Design Principles (Built for the Agentic Era)

Arima Notebooks is designed to be **reshaped by AI** — by the user themselves, in their own loop, with their own AI CLI. Every architectural decision below is in service of that goal. Read these before reading the rest:

- **No build step on the frontend.** Plain HTML/CSS/vanilla JS served from the JAR. Any AI agent that can read HTML can extend the UI. No Webpack, no Vite, no React, no TypeScript transpile pipeline.
- **Plain Java backend, no Lombok, no annotation magic.** Standard Spring Boot. Any agent that can read Java can extend it. Models are POJOs with manual builders.
- **Subprocess-per-language.** Adding a new language = one new `*ExecutionService.java` file modeled on the existing ones (eight today), a `case` in `ShellController`, a frontend mode entry, and — if it has a package ecosystem — a `<lang>` package service/tab. The pattern is deliberately copy-pasteable; the `add-execution-language` skill scaffolds it.
- **Small conventions, not big frameworks.** Notebook format is plain JSON (`.vnb`). Cell metadata uses `//@ anchor` / `//@ depends` annotations. The MCP tool surface mirrors the REST API 1:1.
- **One Spring Boot JAR, one port (8585).** No microservices, no databases, no message queues. There is exactly one place for an agent to look.
- **MCP-native.** The whole system is exposed as MCP tools so external agents (Claude Code, Claude Desktop, custom) can drive Arima Notebooks identically to how the UI does.

> If a proposed change would force a new build step, a new framework, a new layer, or a new outbound dependency — the answer is almost always **no**. The simplicity is the feature: it's what makes the *use → customize → contribute* cycle take an hour instead of a weekend.

See [`AGENTS.md`](../AGENTS.md) for the hard rules every AI contributor must follow.

---

## Overview

Arima Notebooks is a modern notebook rethought as a **cross-platform execution plane** — one place where many languages run side by side and where humans and AI agents collaborate on the same notebook. Under the hood it is a **single-server Java application** that serves a browser-based notebook UI and exposes REST and WebSocket APIs for interactive code execution across **eight runtimes**: JShell, Java, JavaScript, TypeScript, C#, F#, C++, and Python. There is no frontend build step — the browser loads static HTML/CSS/JS directly from Spring Boot's static resource handler.

```
Browser                          Arima Server (Spring Boot 3.2, Java 21)
────────                         ──────────────────────────────────────
                                 ┌──────────────────────────┐
  HTTP GET /            ──────▶  │ Static Resource Handler  │
                        ◀──────  │ index.html · css · js    │
                                 └──────────────────────────┘

  REST /api/*           ──────▶  ┌──────────────────────────┐
                        ◀──────  │ Spring MVC Controllers   │
                                 └────────────┬─────────────┘
                                              │
  WebSocket /ws         ══════▶  ┌──────────────────────────┐
  (STOMP/SockJS)        ◀══════  │ STOMP/SockJS Endpoint    │
                                 └──────────────────────────┘

                      ┌──────────────────────────────────────────────────────┐
                      │              Execution Services                      │
                      │                                                      │
                      │  JShellManager  ·  JavaCompilerService               │
                      │  NodeJsExecutionService  ·  TypeScriptExecutionService│
                      │  DotNetExecutionService  ·  CppExecutionService      │
                      └──────────────────────────────────────────────────────┘

                      ┌──────────────────────────────────────────────────────┐
                      │                  AI Services                         │
                      │                                                      │
                      │  ClaudeService · CopilotCliService · GeminiService   │
                      │  (routed by LLMController based on active provider)  │
                      └──────────────────────────────────────────────────────┘

  AI chat/generate      ──────▶  claude / agy CLI (local subprocess) · copilot (via Copilot SDK)
                        ◀──────  stdout response / SDK assistant-message event
```

---

## Component Map

### Backend (`src/main/java/com/barista/`)

```
com.barista/
├── BaristaApplication          Spring Boot entry point
│
├── config/
│   ├── WebSocketConfig       STOMP over SockJS endpoint (/ws)
│   └── CorsConfig            Cross-origin policy (localhost only)
│
├── model/                    Pure data classes — plain Java, no Lombok, manual Builder
│   ├── Notebook              Top-level notebook container (.vnb JSON)
│   ├── Cell                  Individual cell (type, mode, source, anchor, dependsOn, output)
│   ├── CellType              CODE | MARKDOWN | PIPELINE
│   ├── PackageInfo           Installed Maven package
│   ├── NuGetPackageInfo      Installed NuGet package for C# / F#
│   ├── BaristaSettings         All application settings (model, theme, font size, …)
│   └── ExecutionResult       Unified execution output (all runtimes, all modes)
│
├── shell/                    JShell integration
│   ├── JShellManager         Spring @Service; manages one ShellSession per session ID
│   └── ShellSession          Wraps a single JShell instance; synchronized execute()
│
├── service/                  Business logic
│   ├── NotebookService       CRUD for .vnb files; tutorial registry
│   ├── PackageService        Maven Central download + classpath injection into JShell
│   ├── JavaCompilerService   javax.tools compile + subprocess run for Java-mode cells
│   ├── NodeJsExecutionService Node.js subprocess for JS cells; npm module resolution
│   ├── TypeScriptExecutionService TS cells via `node --experimental-strip-types`; optional `tsc --noEmit` type-check
│   ├── DotNetExecutionService C# (dotnet run) + F# (dotnet fsi) — see §DotNet below
│   ├── CppExecutionService   C++ compile (g++/clang++) + run; anchor/depends injection
│   ├── PythonExecutionService Python (python3) subprocess; PyPI on PYTHONPATH; anchor/depends injection
│   ├── PyPiService           PyPI package install/remove (pip --target) + PyPI JSON lookup
│   ├── NuGetService          NuGet package list management (data/nuget-packages.json)
│   ├── OrchestrationService  Dependency graph, topological sort, cross-notebook refs
│   ├── ClaudeService         Claude CLI integration (local subprocess, no API key stored)
│   ├── CopilotCliService     GitHub Copilot SDK integration (copilot-sdk-java → local `copilot` CLI, JSON-RPC, chat-only)
│   ├── GeminiService         Antigravity CLI integration (`agy -p` subprocess; provider key `gemini_cli`)
│   └── SettingsService       Settings load/save (data/settings.json)
│
├── controller/               REST API layer
│   ├── NotebookController    /api/notebooks/*
│   ├── ShellController       /api/shell/* — routes by cell mode to execution service
│   ├── PackageController     /api/packages/* (Maven)
│   ├── NuGetController       /api/nuget/* (NuGet)
│   ├── LLMController         /api/llm/* — routes to active AI provider service
│   ├── SystemController      /api/system/restart  /api/system/shutdown
│   └── SettingsController    /api/settings/*
│
└── util/
    └── BaristaDisplay          Data science helpers: chart rendering, DataFrame display
```

### Frontend (`src/main/resources/static/`)

```
index.html                    Single-page app shell (all tabs, modals, overlays)
css/
└── arima.css                 All styles — dark theme, cell colour coding, step navigator
js/
├── app.js                    Global init, tab navigation, WebSocket/STOMP, REST helpers
├── notebook.js               Notebook editor: cells, execution, step navigator, save
├── console-tab.js            Interactive console (JShell / Java / JS runtimes)
├── orchestration.js          Pipeline dep-badge rendering, graph validation UI
├── packages.js               Maven + npm package manager UI
├── nuget.js                  NuGet package manager UI (C# / F#)
├── settings.js               Settings form (AI provider, theme, font size, auth)
├── ai-assistant.js           AI chat + notebook generation; dynamic provider switcher
├── docs.js                   In-app documentation overlay (tabbed)
└── server-lifecycle.js       Shutdown/restart overlay, health-poll reconnect
```

---

## Execution Model

### JShell (default mode)

- State-sharing REPL: all cells in a notebook share the **same JShell session** (`nb-{notebookId}`)
- Variables, imports, and method definitions persist across cell boundaries within a session
- Session is reset only when the user clicks **Restart** or the server restarts
- On every new session: server classpath is injected automatically (Maven packages, data science libs)
- Pre-loaded imports: `java.util.*`, `java.util.stream.*`, `java.io.*`, `java.math.*`, `java.time.*`, all XChart / Commons Math / Tablesaw / OpenCSV classes, and `BaristaDisplay`

### Java (compile-and-run)

- Each cell is compiled as a standalone `public class Main { }` via `javax.tools`
- Compilation errors are reported with adjusted line numbers (preamble offset removed)
- Subprocess is used for running the compiled class; stdout/stderr are captured

### JavaScript (Node.js)

- Each cell runs in a Node.js subprocess via `node -e <code>`
- `require()` resolves packages from `data/npm-modules/node_modules/`
- Built-in `arima` object provides `barista.table()`, `barista.display()`, `barista.html()`, `barista.stats()`

### C# (`dotnet run`)

- Each cell runs as a **C# 9+ top-level program** inside a generated temp directory with a `.csproj`
- `dotnet run --project <tempDir> -v q` compiles and runs the cell
- No `dotnet-script` required — standard .NET SDK only
- NuGet packages installed via the NuGet tab are added as `<PackageReference>` in the `.csproj`
- Inline `#r "nuget: PackageId, Version"` directives in cells are stripped (C#) — use NuGet tab instead
- Type declarations (`class`, `record`, `struct`, `enum`, `namespace`) are automatically moved to the end of `Program.cs` to satisfy the C# 9+ top-level statement ordering rule (CS8803)

### F# (`dotnet fsi`)

- Each cell runs as an `.fsx` script via `dotnet fsi --nologo --exec <file.fsx>`
- NuGet packages installed via the NuGet tab are prepended as `#r "nuget: ..."` directives
- Inline `#r "nuget: ..."` directives in cells are **extracted** from the user's code and placed at the **top** of the script file (before any `open` statements), ensuring `dotnet fsi` resolves them before compilation
- Pre-opened namespaces: `System`, `System.Linq`, `System.Collections.Generic`
- Built-in helpers: `baristaHtml`, `baristaDisplay`, `baristaTable`

### C++ (`g++` / `clang++`)

- Each cell is compiled by `g++` or `clang++` (auto-detected on PATH) with `-std=c++17 -Wall`
- Cells run in **notebook mode** by default — Arima Notebooks wraps the code in `main()` automatically; no boilerplate required
- If the cell contains `int main(`, it is compiled as a **complete program** with the preamble prepended
- **Preamble** injected into every cell: 25 standard headers (`<iostream>` through `<chrono>`) + `using namespace std;` + Arima Notebooks helper functions
- **Declaration splitting**: lines/blocks that look like class, struct, function, or template definitions are extracted to global scope; statements go inside `main()`
- **Pipeline dependency injection** (same `//@ anchor:` / `//@ depends:` DSL as C#/F#):
  - Ancestor **declarations** injected at global scope before current cell's declarations
  - Ancestor **statements** injected inside `main()` with stdout suppressed via `__BaristaNullBuf`
- **Arima Notebooks helpers** available in every cell:
  ```cpp
  baristaHtml("...");              // outputs BARISTA_HTML: sentinel → rendered HTML
  baristaDisplay(value);           // cout << value
  baristaTable(myVector);          // formatted item count
  baristaTable(myMap);             // key/value ASCII table
  ```
- **Error normalisation**: temp file paths are replaced with `main.cpp`; line numbers are adjusted to subtract the preamble line count
- **Timeout**: 60 seconds (compile + run combined)
- **Prerequisite**: `g++` (MinGW-w64 on Windows, `build-essential` on Linux, Xcode CLI on macOS) or `clang++` on PATH

### Python (`python3`)

- Each cell runs in a fresh `python -u -X utf8` subprocess (interpreter auto-detected: `python`, `python3`, or the Windows `py -3` launcher)
- **Preamble** injected into every cell: a `barista` helper object — `table()`, `stats()`, `html()`, `image()` (matplotlib figure/PNG → data-URI via the `BARISTA_HTML` sentinel), `display()`
- **PyPI packages**: `PyPiService` installs to `data/pypi-packages/site` via `pip install --target`; that directory is put on `PYTHONPATH` for every Python cell, so `import <pkg>` resolves without touching system site-packages. Uninstall deletes exactly the files the install added (tracked via a before/after diff, since `pip uninstall` doesn't support `--target`).
- **Pipeline dependency injection** (same `//@ anchor:` / `//@ depends:` DSL as C#/F#): ancestor cell sources (transitive closure) are written to a side file and `exec()`'d into the cell's `globals()` with stdout redirected to devnull, so their variables/functions are in scope before the current cell runs
- **Error normalisation**: temp paths replaced with `script.py`; `File "script.py", line N` numbers shifted to subtract the injected preamble
- **Timeout / runaway guard**: shared `InteractiveProcessRunner` (output-line + time limits), same as the other subprocess languages
- **Prerequisite**: Python 3.8+ on PATH

---

## Pipeline / Orchestration System

`OrchestrationService` implements a full dependency graph engine. The same annotation DSL works in **all six execution modes**: JShell, Java, JavaScript, C#, F#, and C++.

### Annotation DSL

```
//@ anchor: loadData          — give a cell a reusable name
//@ depends: loadData, utils  — declare prerequisite anchors (local or cross-notebook)
//@ pipeline: myPipeline      — name a PIPELINE cell
//@ steps: step1, step2       — ordered step list for a PIPELINE cell
//@ description: text         — human-readable label
//@ namespace: com.utils      — wrap JShell cell in a class (cross-notebook only)
```

### Dependency Resolution

1. Build an `anchor → Cell` map from the notebook
2. Compute the **transitive closure** of the target cell's dependencies (DFS)
3. **Topological sort** (Kahn's algorithm) for execution order
4. **Cycle detection** (DFS back-edge detection) — reports the cycle path in the error
5. Execute cells in sorted order

### Cross-Notebook References

Cross-notebook references have the form `notebook:{notebookId}/{anchorName}`:

```
//@ depends: notebook:java-utils/math-helpers
```

**JShell / Java cross-notebook modules** are loaded by executing the foreign cell's source in the current JShell session. An optional `//@ namespace: com.mylib` annotation wraps the foreign source in a class to prevent name collisions.

**C# / F# cross-notebook modules** cannot share session state (each cell is a subprocess). Instead, `OrchestrationService` pre-builds the foreign cell's **expanded source** — the topologically ordered chain of all its local dependencies' source code, concatenated and annotation-stripped. This expanded source is stored in `DotNetExecutionService`'s per-session anchor cache under the key `"notebook:notebookId/anchorName"`. When the dependent cell compiles, `resolveTransitiveDeps` finds this key and injects the expanded source (with output suppressed) before the current cell's code.

### C#/F# Pipeline Dependency Injection

When a C# or F# cell has `//@ depends: X`:

1. `OrchestrationService` (or `DotNetExecutionService.resolveTransitiveDeps`) resolves the full transitive closure of anchor names in topological order
2. For each ancestor cell's source, the **executable statements** are wrapped in output suppression:
   ```csharp
   var __baristaCtxOut = Console.Out;
   Console.SetOut(TextWriter.Null);
   // [ancestor code — variables and types are defined but output is silenced]
   Console.SetOut(__baristaCtxOut);
   ```
3. For C#: type declarations (`class`, `record`, etc.) from all ancestors are collected and placed **after** all executable statements (satisfying CS8803)
4. The current cell's code follows with output fully active — only this cell's output is visible

---

## Session Anchor Cache

`DotNetExecutionService` maintains a per-session source cache for C#/F# pipeline support:

```
sessionAnchorSources: Map<sessionId, Map<anchorKey, sourceCode>>
```

- **Written** when a cell with `//@ anchor: name` executes successfully (stores the cell's source with annotations intact for transitive `parseDepends` resolution)
- **Written** by `OrchestrationService.cacheAnchorSource()` when loading cross-notebook C#/F# modules (stores the expanded source under `"notebook:notebookId/anchorName"`)
- **Read** by `resolveTransitiveDeps` when building the combined program for a dependent cell
- **Cleared** on session restart (`clearSessionAnchors(sessionId)`)

---

## Real-time Output (WebSocket)

Arima Notebooks uses **STOMP over SockJS** for streaming cell output.

| Direction | Destination | Payload | Purpose |
|-----------|-------------|---------|---------|
| Client → Server | `/app/shell/{sessionId}` | `{code, cellId, mode}` | Execute cell |
| Server → Client | `/topic/shell/{sessionId}` | `ExecutionResult` | Cell output |
| Server → Client | `/topic/shell/{sessionId}` | `{type:"input_needed", cellId, text}` | Stdin prompt |

`ExecutionResult` fields: `sessionId`, `cellId`, `output`, `error`, `status`, `success`, `executionTimeMs`, `executionCount`, `notebookId`.

Special output sentinels parsed by `notebook.js`:

| Sentinel | Rendered as |
|----------|-------------|
| `BARISTA_HTML:<html>` | Inline HTML block in cell output |
| `BARISTA_IMG:data:image/png;base64,...` | Inline PNG chart image |

---

## Server Lifecycle

`SystemController` provides graceful shutdown and self-restart via `/api/system/*`.

- **Shutdown** (`POST /api/system/shutdown`): replies immediately, then calls `SpringApplication.exit()` on a background thread after 300 ms
- **Restart** (`POST /api/system/restart`): writes an OS-specific **trampoline script** to a temp file, launches it in the background, then exits. The trampoline polls until port 8585 is free, then re-launches the JAR with the same JVM flags
- **Auto-detect**: `server-lifecycle.js` monitors the WebSocket; if it drops for > 5 s, the overlay appears and `/actuator/health` is polled every 2 s. Server back → page reload. No response in 35 s → "Server stopped" state
- **External watchdog**: `scripts/start.sh` and `scripts/start.bat` loop on exit code 42 (the "restart requested" sentinel)

---

## Data Science Stack (JShell / Java)

Pre-loaded in every JShell session — no installation required:

| Library | Version | Purpose |
|---------|---------|---------|
| **XChart** | 3.8.6 | Charts (XY, bar, pie, area, histogram) |
| **Commons Math** | 3.6.1 | Statistics, regression, distributions |
| **Tablesaw** | 0.43.1 | DataFrame (load CSV, filter, groupBy, aggregate) |
| **OpenCSV** | 5.9 | Raw CSV parsing |

`BaristaDisplay` is the bridge between these libraries and the browser:
- `BaristaDisplay.show(chart)` → PNG → `BARISTA_IMG:…` sentinel → `<img>` in cell output
- `BaristaDisplay.show(table)` → HTML → `BARISTA_HTML:…` sentinel → rendered HTML in cell output

---

## Storage Layout

```
arima/
├── notebooks/
│   ├── examples/          Pre-built example notebooks (checked in)
│   ├── tutorials/         Built-in tutorials: JShell, Java, JS, C#, F# series (checked in)
│   └── <userId>/          User notebooks — gitignored by default
│       └── *.vnb
└── data/
    ├── settings.json      App settings — gitignored (may contain API key)
    ├── packages.json      Installed Maven packages
    ├── nuget-packages.json Installed NuGet packages (C# / F#)
    └── packages/          Downloaded JAR files
        └── *.jar
```

### Notebook File Format (`.vnb`)

```json
{
  "id": "unique-id",
  "name": "Notebook Name",
  "cells": [
    {
      "id": "cell-1",
      "type": "CODE",
      "mode": "jshell",
      "source": "// code here",
      "anchor": "myStep",
      "dependsOn": ["prevStep", "notebook:other-nb/sharedUtil"],
      "output": "…",
      "error": "",
      "executed": true,
      "executionCount": 3
    }
  ]
}
```

Cell `mode` values: `jshell` · `java` · `nodejs` · `typescript` · `csharp` · `fsharp` · `cpp`

---

## Security Model

- **Local only**: no authentication by default. Bind to `127.0.0.1` only (default Spring Boot behaviour).
- **No API keys in transit**: all three AI providers (Claude, Copilot, Antigravity) run locally — Claude & Antigravity as CLI subprocesses, Copilot via the GitHub Copilot SDK driving the local `copilot` CLI — no credentials leave the machine through Arima Notebooks.
- **Code execution**: JShell, Java, Node.js, `dotnet run`, `dotnet fsi`, and `g++`/MSVC all run with the same OS-user permissions as the Arima Notebooks server process. Do **not** expose Arima Notebooks to untrusted network access.
- **CORS**: configured for `localhost` only. Restrict further in production.

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No Lombok | Avoided due to JDK 25+ build compatibility issues; plain Java getters/setters + manual Builder |
| No frontend build step | Zero tooling friction — change a JS file, refresh the browser |
| `dotnet run` instead of `dotnet-script` | Standard SDK only, no global tool install required |
| Source injection for C#/F#/C++ pipeline deps | Processes are isolated; injecting source is the only way to share types/variables across cells |
| C++ declaration splitting | Brace-depth tracker separates global-scope items (classes/functions) from statements; enables the no-`main()` notebook UX |
| Multi-provider AI pattern | Each provider (Claude/Copilot/Antigravity) is a standalone `*Service`; `LLMController` dispatches via a thin `AIDelegate` interface — adding a new provider requires only a new service + one switch case. Claude & Antigravity are CLI subprocesses; Copilot uses the GitHub Copilot SDK |
| Trampoline restart | Works whether started via `mvn spring-boot:run`, `start.sh`, or JAR directly |
| Health-poll reconnect | More reliable than STOMP reconnect; works regardless of how the server stopped |
| Cell expand/collapse | CSS `max-height` transition (96px collapsed → 2000px expanded); CodeMirror `refresh()` after transition keeps line rendering correct |
| Language conversion on mode switch | Mode toggles immediately; an optional banner offers AI-powered conversion via `/api/llm/chat` — non-blocking, auto-dismisses after 15 s |
