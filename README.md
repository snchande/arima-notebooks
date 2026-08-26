# Arima Notebooks

> **A modern, multi-language notebook for the agentic era — a cross-platform execution plane where people and AI agents collaborate on the same code**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Version](https://img.shields.io/badge/version-3.2.0-informational.svg)](CHANGELOG.md)

Arima Notebooks is part of the **Arima** platform. It is a **ground-up modernization of the notebook** — a locally-hosted, browser-based environment that treats many rich programming languages as equals and is built for a world where humans and AI agents work on code together. Eight languages run side by side today — JavaScript, TypeScript, C#, F#, C++, Java, JShell, and Python — with more on the way. Three AI co-pilots (Claude, GitHub Copilot, Antigravity) are wired in locally (Claude & Antigravity as CLI subprocesses, Copilot via the GitHub Copilot SDK — no API keys), and the **whole system is exposed via MCP** so any agent can drive it programmatically. The notebook becomes a shared artifact: you, the UI, an AI co-pilot, and any MCP agent are all first-class users of it.

No cloud account. No data sent anywhere. Runs entirely on your machine.

> ☕ **Why "Barista"?** Arima Notebooks is built on **Java**, and in coffee culture a *barista* serves what you order. **Barista** is Arima's pure-Java core engine — it "serves" every foundational capability: executing cells across all eight languages, running pipelines, managing packages, and powering the MCP server. Java is the coffee; Barista brews it. Throughout these docs, "Barista" refers to that engine.

> 📄 **[Product Brochure (PDF)](docs/brochure/arima-brochure.pdf)** — 12 pages, branded, with architecture diagrams.
> 🛡 **AI-contributor rules** live in **[AGENTS.md](AGENTS.md)** — read this before you (or your CLI) write code.
> 🔍 **Pre-flight security check:** `pwsh ./scripts/security-check.ps1` or `./scripts/security-check.sh`.

![Arima Notebooks UI](docs/screenshots/00-cover-medium.png)

---

## Get started

One file, one command. It explains itself before it changes anything, asks once, then checks your toolchain, installs what's missing with your platform's own package manager, clones the repo, and builds the JAR.

**Windows (PowerShell)**
```powershell
irm https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.ps1 | iex
```

**macOS / Linux**
```bash
curl -fsSL https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.sh | bash
```

Then:

```bash
arima start          # starts the server and opens http://localhost:8585
arima register       # optional: open .anb notebook files by double-clicking
```

**Runs on your machine only.** Arima executes code as your user account, so it listens on
localhost and refuses any request that did not come from this computer - other devices on
your network cannot connect. See [docs/SECURITY.md](docs/SECURITY.md).

**Only a JDK is required.** The build uses the bundled Maven Wrapper, so you do not need
Maven installed - which matters on Windows, where Maven is not distributed through winget
at all. Everything else (Node, .NET, Python, a C++ compiler) is optional and only enables
the cell modes that need it.

**Working on Arima rather than with it?** Add `-Dev` / `--dev`. That clones the full
history so you can branch and open pull requests, and offers every language runtime so you
can run the whole tutorial suite. Without it you get a shallow clone and the essentials.

**Look before you leap.** Both installers take a check-only flag that prints the plan and the dependency table and then stops without touching the machine:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.ps1))) -CheckOnly
```
```bash
curl -fsSL https://raw.githubusercontent.com/snchande/arima-notebooks/master/install.sh | bash -s -- --check-only
```

| Flag | PowerShell | sh | What it does |
|------|-----------|-----|--------------|
| Check only | `-CheckOnly` | `--check-only` | Explain and probe, change nothing |
| Unattended | `-Yes` | `--yes` | Skip every confirmation (CI) |
| Minimum | `-SkipOptional` | `--skip-optional` | Only Java, Maven and Git |
| Leave PATH alone | `-NoPath` | `--no-path` | Do not register the launcher |
| Elsewhere | `-Dir <path>` | `--dir <path>` | Default: `%LOCALAPPDATA%\Arima` / `~/.arima` |
| Start over | `-Reset` | `--reset` | Discard recorded progress |
| No animation | `-NoAnim` | `--no-anim` | Plain output |
| Developer | `-Dev` | `--dev` | Full git history and every language runtime |

If a step fails, the installer stops, says which step and why, and records its progress in `~/.arima-install-state` — re-running picks up from the failed step instead of starting over. It also prints the exact URL and the diagnostics block to [file an issue](https://github.com/snchande/arima-notebooks/issues/new).

Already have the repo cloned? Use `arima install` instead — it is the same setup without the download step, and the one-file installers hand over to it.

---

## Why Arima Notebooks?

The notebook changed how we explore ideas, prototype, and teach: write a little code, run it, see the result instantly. That tight feedback loop is one of the best ideas in software. But the notebook itself has barely evolved — while the world around it changed completely.

**Two shifts, in particular:**

- **Real work is polyglot.** Teams move fluidly across many strong, statically-typed and systems languages — not just one. A notebook that privileges a single language is a notebook that's out of step.
- **Code is now a team sport with AI.** Software is no longer written by people alone; humans and AI agents build it together, on the same artifact.

Arima Notebooks was rethought from first principles for both. It isn't a single-language tool with others bolted on — it's a **shared, cross-platform execution plane for code**, where many languages run side by side and where people and agents collaborate on the very same living document.

- **Every language is first-class** — JavaScript, TypeScript, C#, F#, C++, Java, JShell, and Python today, with more to come. Real compilation, real dependencies, real tooling for each — none of them a plugin, none an afterthought.
- **Learn a second language in terms of your first** — every code cell can be read side by side in another of the eight languages, idiomatic rather than transliterated, commented where that language forces a different approach, and executable on both sides so you can check the two really do agree.
- **Agents are a new kind of cell** — a body written in natural language rather than code, executed by a local agent CLI (Claude, Copilot, Antigravity). Agents compose into pipelines and are callable over MCP like anything else.
- **Humans and agents, together** — a built-in AI co-pilot, plus full MCP access, so any external agent can drive the same notebook you're editing. You learn faster, adapt faster, and reach a working result together.
- **Local-first** — your notebooks and code never leave your machine. No cloud account, no sign-up.
- **Open source, built to be reshaped** — use it, extend it, add a language, bring your own tools, and contribute back to shape where the notebook goes next.

---

## Built for the Agentic Era

Arima Notebooks is designed to be **used**, **customized**, and **contributed back to** — entirely through agentic prompts. The product, the docs, and the contribution workflow all assume an AI partner is in your loop.

### The Agentic Cycle

```
   Use ──► Customize ──► Contribute ──► (back to) Use
    │           │              │
    │           │              └── "Package this as a PR." → AI opens a PR upstream
    │           └──────────────── "Add a violin-plot helper." → AI edits the code in your fork
    └──────────────────────────── "Generate a notebook that fits a polynomial to this data." → AI builds it in Arima
```

Three surfaces, one workflow:

| Surface | What you do | How AI helps |
|---|---|---|
| **Arima Notebooks UI** | Write and run cells | The built-in **Arima Agentic Assistant (AAA)** (Claude / Copilot / Antigravity) generates cells, explains errors, converts between languages, and can update the focused cell directly (with one-click Undo) |
| **Your terminal** | `claude` / `copilot` / `agy` inside the Arima Notebooks repo | Reshape Arima Notebooks itself — add a language, change a theme, fix a bug, write a tutorial |
| **Any MCP-aware agent** | Claude Code, Claude Desktop, custom agents | Drive Arima Notebooks over MCP — create notebooks, add cells, run pipelines, install packages programmatically |

### Sample Agentic Prompts

**While using a notebook:**
- *"Generate a JShell cell that loads `sales.csv` with Tablesaw and plots revenue by quarter."*
- *"Convert this Java stream pipeline to a C# LINQ query."* (then click **Insert into notebook**)
- *"Why is this cell's output empty? Look at the dependency chain."*

**While customizing Arima Notebooks itself (in your terminal, inside the repo):**
- *"Add an Excel export option for notebooks — a button in the toolbar that downloads the current notebook as `.xlsx`."*
- *"The dark theme is too contrasty. Tweak `arima.css` so the cell borders are softer."*
- *"Add a tutorial notebook `java-701.vnb` covering Java 21 virtual threads — five cells, beginner-friendly."*

**Closing the contribution loop:**
- *"That worked. Package the change as a PR back to upstream with a good description."*
- *"Run `pwsh ./scripts/security-check.ps1` before you push."*

The bar to **customize for yourself** and the bar to **contribute back** become the same bar — one sentence to your AI CLI.

> 📖 See [`AGENTS.md`](AGENTS.md) for the architecture guardrails every AI agent must follow when editing this repo. The companion files [`CLAUDE.md`](CLAUDE.md), [`.github/copilot-instructions.md`](.github/copilot-instructions.md), and [`GEMINI.md`](GEMINI.md) all defer to it.

---

## Features at a Glance

| Feature | Description |
|---------|-------------|
| **Eight execution modes** | JShell · Java · JavaScript (Node.js) · TypeScript · C# · F# · C++ · Python |
| **Real-time output** | Console output streams live via WebSocket — no polling |
| **Maven Package Manager** | Install/uninstall Maven packages; auto-injected into JShell classpath |
| **npm Package Manager** | Install npm packages for JavaScript *and* TypeScript cells with one click |
| **NuGet Package Manager** | Install NuGet packages for C# and F# cells with one click |
| **PyPI Package Manager** | Find, install, and use any package from the Python Package Index in Python cells — isolated on `PYTHONPATH` |
| **C++ Built-in Headers** | 26 standard headers pre-included; MSVC, GCC, and Clang auto-detected |
| **TypeScript Type-checking** | Optional `tsc --noEmit` pass before each cell — type errors with proper line numbers |
| **Pipeline Orchestration** | Chain cells with `//@ depends:` annotations — works across all 8 languages |
| **Multi-provider AI** | Claude · GitHub Copilot · Antigravity — local (CLI + Copilot SDK), no API key needed |
| **AI Language Conversion** | Switch a cell's language and AI converts the code automatically |
| **Polyglot** | Read any cell in a language you are learning, beside the one you already know — generated once, saved with the notebook, and **runnable on both sides** with their timings compared |
| **MCP Server** | Expose Arima Notebooks as an MCP tool server for Claude Code, Claude Desktop, and custom agents — ten tools over JSON-RPC 2.0 |
| **MCP from the CLI** | `arima mcp tools` / `arima mcp exec "..."` — call the same MCP tools straight from CMD, PowerShell, or bash, no client needed |
| **Agents & Skills** | Build, list, and run agents from the **Agents** tab or over MCP (`barista_list_agents`, `barista_run_agent`) |
| **One-shot install** | `arima install` checks every dependency, installs what's missing via winget / brew / apt, builds, and reports readiness |
| **Safe self-update** | `arima update` fast-forwards or rebases onto upstream, and refuses to touch uncommitted work |
| **Built-in Data Science** | XChart · Commons Math · Tablesaw · simple-statistics · mathjs — pre-installed |
| **Tutorial Library** | Built-in tutorials across JShell, Java, JavaScript, TypeScript, C#, F#, C++, Python, and Agents & Skills — organized into per-language tabs |
| **Guided Tutorial Player** | Play any tutorial as a **narrated** walkthrough — hands-free **autopilot** or self-paced **interactive** — and interrupt to ask questions by **voice or text** (browser-native audio, answered by your AI provider) |
| **Docs reading mode** | One-click distraction-free reading view in the in-app documentation |
| **Interactive Console** | Full REPL console with tab completion |
| **Guided Tour (First Run Experience)** | Interactive spotlight walkthrough of every section on first launch — replay anytime from **Settings → Guided Tour** |
| **Dark theme** | Easy on the eyes by default |

---

## Requirements

| Requirement | Version | Notes |
|-------------|---------|-------|
| **Java JDK** | 17+ (21 recommended) | Must be a JDK — not just JRE. JShell is a JDK tool. |
| **Maven** | 3.8+ | For building from source |
| **Node.js** | 18+ (22.6+ for TS) | Optional — for JavaScript / TypeScript cells and npm packages |
| **TypeScript (tsc)** | 5.0+ | Optional — `npm install -g typescript` to enable type-check diagnostics |
| **.NET SDK** | 6.0+ | Optional — for C# and F# cells (free from [dot.net](https://dot.net)) |
| **C++ compiler** | Any | Optional — MSVC (Windows), GCC or Clang (Mac/Linux); auto-detected |
| **Python** | 3.9+ | Optional — for Python cells and PyPI packages |
| **Git** | 2.x | Optional — enables `arima update` (sync this checkout with upstream) |
| **AI CLI** | Latest | Optional — Claude CLI, GitHub Copilot CLI (`copilot`, used by the Copilot SDK), or Antigravity CLI (`agy`) for AI features |
| **Internet** | — | For Maven Central, npm registry, NuGet, and PyPI downloads |

> **You don't have to install these by hand.** Run `arima install` and the CLI checks every dependency, installs whatever is missing (winget on Windows; brew / apt / dnf / pacman on Linux & macOS), builds the JAR, and prints a readiness report.

---

## Quick Start

> Starting from nothing? The one-file installer in [Get started](#get-started) does Steps 1 and 2 for you. The steps below are the manual path, and what to do once the repo is already on disk.

### Step 1 — Clone the repository

```bash
git clone https://github.com/snchande/arima-notebooks.git
cd arima-notebooks
```

### Step 2 — Install

`install` is the one-shot setup command: it probes every dependency, offers to install the missing ones, prepares `data/`, `notebooks/` and `logs/`, wires the AI guardrails, builds the JAR, and finishes with a **readiness report**.

**Windows — Command Prompt**
```cmd
arima install
```

**Windows — PowerShell**
```powershell
./arima.ps1 install
```
> If PowerShell blocks the script with an execution-policy error, run once:
> `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`

**Linux / macOS**
```bash
./arima.sh install
```

Useful flags: `--yes` (install without prompting), `--skip-optional` (only Java + Maven), `--no-build`, `--path` (register the folder on your PATH so plain `arima` works anywhere).

### Step 3 — Start Arima Notebooks

```cmd
arima start            ::  start the server and open the browser
arima start --bg       ::  detached, logs to arima.log
arima                  ::  home screen: live status + every command
```
```powershell
./arima.ps1 start      #   start the server and open the browser
./arima.ps1 start -Bg
./arima.ps1            #   home screen: live status + every command
```
```bash
./arima.sh start       #   start the server and open the browser
./arima.sh start --bg
./arima.sh             #   home screen: live status + every command
```

`start` auto-builds the JAR if it's missing, starts the server, and opens your browser. Running the launcher with **no subcommand** shows the home screen described above. Run `arima help` for the full command list.

**Maven dev mode (all platforms):**
```bash
mvn spring-boot:run
```

**JAR directly (all platforms):**
```bash
java --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \
     -jar target/arima-notebooks-1.0.0-SNAPSHOT.jar
```

### Step 4 — Open your browser

Navigate to **[http://localhost:8585](http://localhost:8585)**

The `arima` CLI opens this automatically on every platform.

### Step 5 — Keeping up to date

```bash
arima update      # fetch upstream, fast-forward or rebase your commits, rebuild
arima restart     # pick up the new JAR
```

`update` **never touches uncommitted work**. If your working tree is dirty it stops before doing anything and tells you to commit or stash first. If the rebase itself conflicts, it aborts the rebase automatically, restores your branch exactly as it was, and lists the conflicting files with the commands to resolve them by hand.

### Step 6 — (Optional) Enable AI features

Arima Notebooks supports three AI providers — all local, no API key needed. Claude & Antigravity run as CLI subprocesses; Copilot runs through the **GitHub Copilot SDK**, which drives the local `copilot` CLI:

| Provider | Install | Auth |
|----------|---------|------|
| **Claude** (recommended) | [claude.ai/code](https://claude.ai/code) | `claude auth` |
| **GitHub Copilot** | [GitHub Copilot CLI](https://github.com/github/copilot-cli) (`copilot`, v1.0.55-5+) | authenticate the `copilot` CLI |
| **Antigravity** | [install `agy`](https://antigravity.google/docs/cli-install) | run `agy` to sign in (or set `GEMINI_API_KEY` / `ANTIGRAVITY_API_KEY`) |

Switch providers any time in **Settings → AI Provider**.

---

## Step-by-Step Usage Guide

### Creating Your First Notebook

1. Click the **folder icon** in the top toolbar to open the Notebook Browser
2. Click **+ New Notebook** under "My Notebooks"
3. Give it a name and press Enter
4. Your new notebook opens with one empty code cell

### Running Code

Each code cell has a **mode button** (top-right of the cell) that cycles between all eight languages:

| Mode | Badge | What it does |
|------|-------|-------------|
| **JShell** | `◈ JShell` | Runs as a Java snippet. Variables persist across cells in the same notebook. |
| **Java** | `◈ Java` | Compiles and runs a full Java class. Per-cell isolation — perfect for class definitions. |
| **JavaScript** | `◈ JS` | Runs via Node.js. Built-in helpers: `barista.table()`, `barista.html()`, `barista.display()`. |
| **TypeScript** | `◆ TS` | Runs via Node.js's built-in type-stripping (Node 22.6+). Optional `tsc --noEmit` type-check. Same helpers as JS, with full TypeScript type signatures. |
| **C#** | `◈ C#` | Compiled as a C# 9+ top-level program via `dotnet run`. NuGet packages auto-injected. |
| **F#** | `◈ F#` | Runs as an F# script via `dotnet fsi`. Inline `#r "nuget:"` directives supported. |
| **C++** | `◈ C++` | Compiled and run with MSVC/GCC/Clang. 26 standard headers pre-included. |
| **Python** | `🐍 Python` | Runs via the system `python` 3. Install any library from the **PyPI** tab and `import` it. Helpers: `barista.table()`, `barista.stats()`, `barista.html()`, `barista.image()`. |

When you switch a cell's language, Arima Notebooks offers to **convert the existing code** using AI.

**To run a cell:** Click **Run** or press `Ctrl+Enter`.

**To run all cells:** Click **Run All** in the top toolbar.

### JShell Mode — Shared State

JShell cells in the same notebook share a session. Variables declared in cell 1 are available in cell 2:

```java
// Cell 1 (JShell)
var name = "Arima";
var version = 1.2;

// Cell 2 (JShell) — can use name and version directly
System.out.printf("Hello from %s v%.1f%n", name, version);
```

### Java Mode — Full Classes

Switch a cell to Java mode to write complete class definitions:

```java
// Cell (Java mode) — compiles and runs as a full program
public class Fibonacci {
    public static void main(String[] args) {
        int n = 10;
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) {
            System.out.print(a + " ");
            int temp = a + b;
            a = b;
            b = temp;
        }
    }
}
```

### Installing Maven Packages

1. Click the **Packages** tab
2. Enter a Maven coordinate: `groupId:artifactId:version`
   ```
   com.google.code.gson:gson:2.10.1
   ```
3. Click **Install**
4. The JAR downloads and is immediately available in your JShell session

Search for packages at [search.maven.org](https://search.maven.org/).

### Pipeline Orchestration

Chain cells so they run in dependency order using `//@ annotations`:

```java
//@ anchor: load-data
//@ description: Load CSV from disk
var data = Table.read().csv("data.csv");
```

```java
//@ anchor: clean-data
//@ depends: load-data
//@ description: Remove null rows
var clean = data.dropRowsWithMissingValues();
```

```java
//@ anchor: visualize
//@ depends: clean-data
//@ description: Plot the result
// ... chart code
```

Click **Run with Dependencies** on any cell to automatically execute its full dependency chain first.

### AI Assistant

1. Click the **AI** tab in the right panel (or press `Ctrl+\`)
2. Select your AI provider in the provider bar (Claude · Copilot · Antigravity)
3. Type a question or request:
   - *"Write a C++ cell that sorts a vector using std::sort"*
   - *"Explain what this code does"* (attach a cell with the 🤖 button)
   - *"Generate a notebook showing Java streams with examples"*
4. Code blocks in the response include an **Insert into notebook** button

---

## Tutorial Library

Arima Notebooks ships with **40 built-in tutorials** across all eight languages plus a dedicated **Agents & Skills** track. Open the Notebook Browser and click **Arima Tutorials** — they're organised into per-language tabs.

| ID | Title | Mode | Level |
|----|-------|------|-------|
| `arima-101` | Getting Started with Arima Notebooks | Mixed | Beginner |
| `jshell-101` | JShell Basics | JShell | Beginner |
| `jshell-201` | JShell Intermediate | JShell | Intermediate |
| `jshell-301` | JShell Advanced | JShell | Advanced |
| `jshell-401` | JShell Functional & Concurrency | JShell | Advanced |
| `jshell-501` | JShell Design Patterns | JShell | Advanced |
| `java-101` | Java Basics | Java | Beginner |
| `java-201` | Java Intermediate | Java | Intermediate |
| `java-301` | Java Advanced | Java | Advanced |
| `java-401` | Java Functional & Streams | Java | Advanced |
| `java-501` | Java Design Patterns | Java | Advanced |
| `java-601` | Java Data Science | Java | Advanced |
| `js-101` | JavaScript Basics | JS | Beginner |
| `js-201` | JavaScript Intermediate | JS | Intermediate |
| `js-301` | JavaScript Advanced | JS | Advanced |
| `js-401` | JavaScript Data Science | JS | Advanced |
| `js-501` | JavaScript D3 Visualization | JS | Advanced |
| `ts-101` | TypeScript Introduction | TS | Beginner |
| `ts-201` | TypeScript Intermediate (Generics, Classes, Unions) | TS | Intermediate |
| `ts-301` | TypeScript Advanced (Conditional & Mapped Types) | TS | Advanced |
| `ts-401` | TypeScript Expert (Async, Patterns, Modern Features) | TS | Advanced |
| `ts-501` | TypeScript Typed Data Analysis | TS | Advanced |
| `csharp-101` | C# Introduction | C# | Beginner |
| `csharp-201` | C# Data & Pipelines | C# | Intermediate |
| `fsharp-101` | F# Introduction | F# | Beginner |
| `fsharp-201` | F# Advanced Patterns | F# | Intermediate |
| `cpp-101` | C++ Fundamentals | C++ | Beginner |
| `cpp-201` | C++ Classes & STL | C++ | Intermediate |
| `cpp-301` | C++ Templates & Algorithms | C++ | Advanced |
| `cpp-401` | C++ Concurrency & Modern Features | C++ | Advanced |
| `cpp-501` | C++ Systems & Performance | C++ | Advanced |
| `python-101` | Python Fundamentals | Python | Beginner |
| `python-201` | Python Collections & OOP | Python | Intermediate |
| `python-301` | Python Standard Library & Functional | Python | Intermediate |
| `python-401` | Python Networking | Python | Advanced |
| `python-501` | Python Databases | Python | Advanced |
| `python-601` | Python Data Science, Metrics & Reporting | Python | Advanced |
| `python-701` | Python Pipelines, Modules & Orchestration | Python | Expert |
| `agent-101` | Explain Code | Agents | Beginner |
| `agent-201` | Code Reviewer | Agents | Intermediate |
| `agent-301` | Test Writer | Agents | Intermediate |
| `agent-401` | Reviewer in a Pipeline | Agents | Advanced |
| `agent-501` | Multi-Agent Review | Agents | Advanced |
| `agent-601` | MCP-driven Agent | Agents | Advanced |
| `skill-101` | Commit Message | Skills | Beginner |

Tutorials open in **read-only mode** — your personal notebooks are separate.

Any tutorial can be played as a **narrated walkthrough** — hands-free *autopilot* or self-paced *interactive* — and you can interrupt it to ask questions by voice or text.

---

## Arima Notebooks CLI

Three launchers sit in the project root. They are **feature-identical** — same commands, same flags, same ASCII art, same animations, same exit codes. Pick whichever matches your shell.

| Shell | Launcher | Flag style |
|---|---|---|
| Windows CMD | `arima` (`arima.cmd`) | `--bg`, `--yes`, `--purge` … |
| Windows PowerShell | `./arima.ps1` | `-Bg`, `-Yes`, `-Purge` … |
| Linux / macOS bash | `./arima.sh` | `--bg`, `--yes`, `--purge` … |

Every command opens with Barista brewing your notebook, then the banner:

```
         ( )                 .-"""""-.      A R I M A   N O T E B O O K S
          ) (              .'    \    '.    ------------------------------------------
        .------.          /      )      \   Java  JShell  JS  TS  C#  F#  C++  Python
        |######|]         \      (      /   Brewed by Barista - JShell + Spring Boot
        |######|           '.    /    .'    Server: http://localhost:8585
        '------'             '-.....-'
       ~~~~~~~~~~
   Barista serves your notebook.
```

The mascot beside the heading is a coffee bean — the same bean Barista drops into the cup below.

The brew animation is a ten-frame character animation: a coffee bean drops into the cup, Barista grinds and brews it, and serves it steaming. It plays on `start`, `welcome`, a successful `install`, and on demand via `arima brew`. Disable it with `--no-anim` / `-NoAnim`; it degrades to a single static frame whenever output is piped.

### Start here — just run `arima`

With no subcommand the CLI shows a **home screen** that adapts to what it finds:

| State | What you get |
|---|---|
| **Running** | A live metadata block read straight from the server — version and build, **when it started and how long it has been up**, the JVM PID, port, auth mode, Java/OS/memory, active JShell sessions, notebook counts, MCP endpoint and protocol, and which of the eight languages are ready — followed by the full command list. |
| **Built, stopped** | Build location, the full command list, and how to start. |
| **Nothing built yet** | A first-run walkthrough: what Arima is, a readiness check of every dependency, and an offer to install what is missing and start the server. |

```
  LIVE SERVER
  ------------------------------------------------------------
    [ok] Status       RUNNING at http://localhost:8585
    [ok] Version      1.0.0-SNAPSHOT   (built 2026-08-24T22:09:23Z)
    [ok] Started      2026-08-24 15:20:19 PDT   (up 3m 0s)
    [ok] Process      PID 24132   port 8585   auth local
    [ok] Java         25  --  Java HotSpot(TM) 64-Bit Server VM
    [ok] OS           Windows 11 10.0 (amd64)  --  12 CPUs
    [ok] Memory       37 MB used  /  80 MB heap  /  16256 MB max
    [ok] Sessions     0 active JShell session(s)
    [ok] Notebooks    89 total  --  54 tutorials  (notebooks/)
    [ok] MCP          enabled  --  protocol 2024-11-05  --  http://localhost:8585/api/mcp/messages
    [ok] Languages    7/7 ready  --  Java / JShell, JavaScript, TypeScript, C#, F#, C++, Python
```

The same block appears under `arima status`. It comes from [`GET /api/system/info`](docs/API.md#server-info); `startedAt` and `uptime` are read from the JVM itself, so they stay accurate no matter how the server was launched. If that endpoint cannot be reached — an older build, or `oauth` auth mode where `/api/**` requires a session — the launchers fall back to a port/PID summary and say so.

The first-run prompt only appears in an interactive terminal. Piped or scripted invocations print guidance instead, so `arima` in a script never kicks off an unattended install.

### Lifecycle

| Subcommand | Description |
|---|---|
| `install` | **Checks every dependency, installs whatever is missing, builds the JAR, and prints a readiness report.** Uses winget on Windows; brew / apt / dnf / pacman on Linux & macOS. Always asks before installing system-wide packages (`--yes` to skip). |
| `update` | Fetches upstream and **fast-forwards** (if you have no local commits) or **rebases** your commits on top. Refuses to run with a dirty working tree; auto-aborts and restores your branch on conflict. |
| `uninstall` | Removes build output (`target/`) and logs. **Never touches `notebooks/`, `src/`, or `.git/`.** `--purge` additionally deletes `data/`. Asks for confirmation. |

### Server

| Subcommand | Description |
|---|---|
| `start` | Start the server, auto-build if needed, open the browser |
| `start --bg` | Start detached; logs to `arima.log`; spinner waits for the port, then opens the browser |
| `stop` | Stop the running server |
| `restart` | Stop then start — use this after `update` to pick up the new JAR |
| `status` | Server state + PID, JAR, every runtime, AI CLIs, and git checkout state |
| `open` | Open the browser (server must already be running) |
| `logs` | Tail `arima.log` (background mode only) |

### MCP — drive Arima from the terminal

`POST /api/mcp/messages` is a stateless JSON-RPC 2.0 endpoint, so the CLI can call the **same tools any MCP client would** — no client, no SSE session, no config needed.

| Subcommand | Description |
|---|---|
| `mcp` | Endpoints, live server info, and the command list |
| `mcp info` / `mcp ping` | Server name, version, protocol / health check |
| `mcp tools` | List every MCP tool with its parameters (`*` marks required) |
| `mcp call <tool> k=v ...` | Call any tool by name; also accepts a single JSON object |
| `mcp exec "<code>"` | Run Java/JShell code (`barista_execute_code`) |
| `mcp notebooks` · `mcp read <id>` · `mcp search <q>` | Notebook shortcuts |
| `mcp agents` · `mcp run-agent <id> <task>` | Agent & skill shortcuts |
| `mcp raw '<json-rpc>'` | Send a raw JSON-RPC envelope |
| `mcp config` | Print an MCP client config snippet for Claude Desktop / Claude Code |

```console
$ arima mcp exec "var answer = 6*7; System.out.println(answer);"
Session: mcp-session
Status: SUCCESS
Output:
42

Execution time: 281ms

$ arima mcp call barista_search_cells query=tablesaw
$ arima mcp config
```

### Build & info

| Subcommand | Description |
|---|---|
| `build` / `rebuild` | `mvn clean package -DskipTests` |
| `version` | Arima version plus every detected runtime |
| `welcome` | Pick how you want to work: UI, MCP, or extend |
| `docs` | Open the brochure and list the documentation |
| `agents` *(alias `ai`)* | Detected AI co-pilots, guardrail files, skills, and subagents |
| `brew` *(alias `coffee`)* | Watch Barista serve a coffee bean |
| `help` | Show the help screen |

### Flags

| CMD / bash | PowerShell | Effect |
|---|---|---|
| `--bg` | `-Bg` | Start detached, logging to `arima.log` |
| `--yes` | `-Yes` | Skip confirmation prompts (install / uninstall) |
| `--purge` | `-Purge` | `uninstall`: also delete `data/` |
| `--no-build` | `-NoBuild` | `install` / `update`: skip the Maven build |
| `--path` | `-AddToPath` | `install`: register this folder on your PATH so plain `arima` works anywhere |
| `--skip-optional` | `-SkipOptional` | `install`: only the required tools (Java + Maven) |
| `--no-anim` | `-NoAnim` | Disable the brew animation, banner reveal, and spinners |

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | General failure (missing tool, build failed, server not running) |
| `2` | `update` refused: uncommitted changes in the working tree |
| `3` | `update` rolled back: rebase conflict (branch restored, nothing lost) |
| `42` | Restart requested by the UI — the foreground `start` loop relaunches automatically |

> **PowerShell note**: if `./arima.ps1` is blocked, run once: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
>
> **No colour?** Set `ARIMA_NO_COLOR=1` (CMD) or `NO_COLOR=1` (bash) for terminals without ANSI support.

---

## Project Structure

```
arima/
├── src/main/java/com/barista/
│   ├── controller/          # REST + WebSocket + MCP endpoints
│   │                        #   Notebook, Shell, Package, NpmPackage, NuGet, PyPi,
│   │                        #   LLM, Agent, Settings, System, User, Mcp
│   ├── service/             # Business logic — one per concern
│   │                        #   Execution: JavaCompiler, NodeJs, TypeScript, DotNet, Cpp, Python
│   │                        #   Packages:  Package (Maven), NpmPackage, NuGet, PyPi
│   │                        #   AI:        Claude, GitHubCopilot, CopilotCli, Gemini, Agent
│   │                        #   Core:      Notebook, Orchestration, Settings, User, OAuthConfig
│   ├── shell/               # JShell session management
│   └── model/               # Data models (.vnb format, settings)
├── src/main/resources/
│   ├── static/              # Frontend — index.html + CSS + JS (no build step)
│   └── application.properties
├── notebooks/
│   ├── tutorials/           # 40 built-in tutorials (JShell, Java, JS, TS, C#, F#, C++, Python, Agents, Skills)
│   ├── examples/            # Example & demo notebooks (incl. C++ and cross-notebook demos)
│   └── welcome.vnb          # Getting started notebook
├── scripts/
│   ├── start.sh / start.bat # Minimal launchers (watchdog loop)
│   ├── setup-python.sh/.ps1 # Python environment bootstrap
│   └── security-check.sh/.ps1  # Pre-flight security scan
├── arima.cmd                # Full CLI — Windows CMD      (CRLF required, see .gitattributes)
├── arima.ps1                # Full CLI — Windows PowerShell
├── arima.sh                 # Full CLI — Linux / macOS bash
├── .gitattributes           # Pins line endings per interpreter (*.cmd = CRLF, *.sh = LF)
└── docs/
    ├── API.md               # REST API + MCP reference
    ├── ARCHITECTURE.md      # System architecture
    ├── SETUP.md             # Detailed setup guide
    ├── USAGE.md             # Feature documentation
    └── WELCOME.md           # The common welcome experience
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full system design.

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8585` | HTTP server port |
| `barista.notebooks.dir` | `notebooks` | Where notebooks are stored |
| `barista.data.dir` | `data` | App data directory (packages, settings) |

Edit `src/main/resources/application.properties` or set environment variables before starting.

---

## Troubleshooting

**"JShell not found" or JShell errors on startup**
- You need a full **JDK**, not just a JRE
- Verify: `java -version` should show JDK 17 or higher
- Check that `JAVA_HOME` points to a JDK directory, not a JRE

**WebSocket connection errors**
- Check your browser console for STOMP errors
- Make sure nothing is blocking port 8585 (firewall, other processes)
- Try refreshing the page — the SockJS client reconnects automatically

**Maven package install fails**
- Check internet connectivity
- Verify the coordinate format: `groupId:artifactId:version`
- Search for valid coordinates at [search.maven.org](https://search.maven.org/)

**JavaScript cells not working**
- Node.js 18+ must be installed and on your `PATH`
- Verify: `node --version`

**TypeScript cells fail — "Node.js too old"**
- TypeScript cells require Node.js 22.6+ (Node 24 LTS recommended)
- Upgrade from [nodejs.org](https://nodejs.org), verify with `node --version`
- For full type-check diagnostics, also install: `npm install -g typescript`

**AI not responding**
- **Claude**: Install [Claude Code](https://claude.ai/code) and run `claude auth`
- **GitHub Copilot**: Install the [GitHub Copilot CLI](https://github.com/github/copilot-cli) (`copilot`, v1.0.55-5+) and authenticate it — the Copilot SDK drives it
- **Antigravity**: Install [`agy`](https://antigravity.google/docs/cli-install) and run `agy` to sign in
- Check Settings → Server Status — your selected provider should show ✓ Found

**C++ cells fail — "No compiler found"**
- Windows: Install [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022) with "Desktop development with C++"
- macOS: Run `xcode-select --install`
- Linux: Run `sudo apt install g++`
- Or just run `arima install` and accept the C++ toolchain when prompted

**Python cells fail**
- Python 3.9+ must be installed and on your `PATH` — verify with `python --version`
- Packages installed from the **PyPI** tab land in `data/pypi-packages/` and are added to `PYTHONPATH` automatically
- `arima status` shows which Python interpreter Arima detected

**`arima.cmd` prints `'x' is not recognized` or `was unexpected at this time`**
- This means the batch file lost its **CRLF line endings**. `cmd.exe` cannot parse an LF-only `.cmd` file.
- The repo ships a `.gitattributes` that pins `*.cmd`, `*.bat`, and `*.ps1` to CRLF (and `*.sh` to LF). If your checkout predates it, refresh the working tree:
  ```bash
  git rm --cached -r . && git reset --hard
  ```
- Editors that "helpfully" normalise to LF will break it again — make sure yours respects `.gitattributes`.

**CLI output shows garbage like `←[92m`**
- Your console doesn't support ANSI escape codes (legacy `conhost` without virtual-terminal processing).
- Use Windows Terminal, or disable colour: `set ARIMA_NO_COLOR=1` (CMD) / `$env:NO_COLOR=1` (PowerShell) / `export NO_COLOR=1` (bash).
- `--no-anim` / `-NoAnim` turns off just the brew animation and spinners while keeping colour.

**`arima update` says "UNCOMMITTED CHANGES — UPDATE STOPPED"**
- This is intentional: a rebase could overwrite your edits, so the update refuses to start and changes nothing.
- Commit them (`git add -A && git commit -m "wip"`) or stash them (`git stash push -u`), then re-run `arima update`.

**`arima update` says "MERGE CONFLICT — UPDATE ROLLED BACK"**
- The rebase was aborted automatically and your branch is exactly where it was — nothing was lost.
- Resolve by hand: `git rebase origin/master`, fix the listed files, `git add <file>`, `git rebase --continue`, then `arima build`.

---

## Contributing

Arima Notebooks is open source and contributions are welcome.

Whether you want to fix a bug, add a new tutorial, improve documentation, or build a new feature — read [CONTRIBUTING.md](CONTRIBUTING.md) for the full guide including development setup, coding standards, and the PR review process.

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/SETUP.md](docs/SETUP.md) | Detailed installation and prerequisites |
| [docs/USAGE.md](docs/USAGE.md) | Full feature documentation and tutorials |
| [docs/API.md](docs/API.md) | REST API reference |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture and design |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## License

MIT License — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- Inspired by [Jupyter Notebooks](https://jupyter.org/)
- Java REPL powered by [JShell](https://openjdk.org/jeps/222) (JEP 222)
- AI features via [Claude](https://www.anthropic.com/), [GitHub Copilot](https://github.com/features/copilot) (SDK), and [Antigravity](https://antigravity.google/) (`agy` CLI)
- Built with [Spring Boot](https://spring.io/projects/spring-boot)
- Charts via [XChart](https://knowm.org/open-source/xchart/)
- DataFrames via [Tablesaw](https://github.com/jtablesaw/tablesaw)
