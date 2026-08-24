# Arima Notebooks - Setup Guide

## Prerequisites

### Required
| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| Java JDK | 17 | 21 (LTS) |
| Maven | 3.8 | 3.9+ |

> **Important**: You need a full **JDK**, not just a JRE. JShell is a JDK tool.
> Verify with: `javac -version` (should show javac 17+ or higher)

### Optional
| Requirement | Minimum | Purpose |
|-------------|---------|---------|
| Node.js | 18 LTS (22.6+ for TS) | JavaScript cells (`◈ JS` mode), TypeScript cells (`◆ TS` mode), and npm packages |
| TypeScript (`tsc`) | 5.0 | Pre-execution type-check for TS cells (optional — TS cells still run without it) |
| .NET SDK | 6.0 | C# and F# cells (no extra tools needed) |
| g++ or clang++ | g++ 9 / clang++ 9 | C++ cells (`⚙ C++` mode), C++17 required |
| Python | 3.8+ | Python cells (`🐍 Python` mode) and PyPI packages |
| Claude CLI | Latest | AI Assistant (Claude provider) — `claude auth` |
| GitHub Copilot CLI | 1.0.55-5+ | AI Assistant (Copilot provider, driven by the Copilot SDK) — `copilot` binary |
| Antigravity CLI (`agy`) | Latest | AI Assistant (Antigravity provider, formerly Gemini) — run `agy` to sign in |
| Internet access | — | Maven Central, npm registry, NuGet, PyPI |

> **Node.js**: Install from [nodejs.org](https://nodejs.org) to use JavaScript and TypeScript cells (plus npm packages).
> JavaScript cells work on Node 18+; **TypeScript cells require Node 22.6+** (recommended: Node 24 LTS) for the built-in type-stripping runtime.
> Without Node.js, Java and JShell cells work normally.

> **TypeScript compiler (tsc)**: To get pre-execution type-check diagnostics in TS cells (e.g. *"Type 'string' is not assignable to type 'number'"*), install the official TypeScript compiler globally:
> ```bash
> npm install -g typescript
> tsc --version   # verify
> ```
> Without `tsc`, TS cells still run — Node strips the type annotations and trusts the code. With `tsc` on the PATH, Arima Notebooks also runs `tsc --noEmit` before each cell and folds the diagnostics into the cell's error stream.

> **.NET SDK**: Install from [dot.net](https://dot.net) to use C# **and** F# cells. The SDK is free for all platforms.
> Both C# and F# cells require only the standard .NET SDK — no extra tools like `dotnet-script` are needed.
> Without the .NET SDK, Java, JShell, and JavaScript cells work normally.

> **Python**: The fastest way is the bundled setup helper, which detects Python and installs it if missing (winget/Chocolatey on Windows; apt/dnf/brew on Linux/macOS):
> ```bash
> # Windows PowerShell
> ./scripts/setup-python.ps1
> # Linux / macOS
> ./scripts/setup-python.sh
> ```
> Or install Python 3.8+ manually from [python.org/downloads](https://www.python.org/downloads/) (on Windows, tick **"Add python.exe to PATH"**).
> Ensure `python`, `python3`, or the Windows `py` launcher is on your `PATH`, then restart Arima. Packages are installed from the **Packages → PyPI** tab via `pip` into an isolated folder (`data/pypi-packages/`) added to `PYTHONPATH` — your system site-packages are left untouched.
> Without Python, the other eight languages work normally.

---

## Recommended: an AI CLI

Arima Notebooks is designed to be **used, customized, and contributed back to entirely through agentic prompts** — see the [README's "Built for the Agentic Era"](../README.md#built-for-the-agentic-era) section. To get the full workflow, install at least one of:

| CLI | Install | Auth |
|-----|---------|------|
| **Claude Code** *(recommended)* | [claude.ai/code](https://claude.ai/code) | `claude auth` |
| **GitHub Copilot CLI** | [GitHub Copilot CLI](https://github.com/github/copilot-cli) (`copilot`, v1.0.55-5+) | authenticate the `copilot` CLI |
| **Antigravity CLI** | [install `agy`](https://antigravity.google/docs/cli-install) | run `agy` to sign in |

Once installed, Arima Notebooks uses the CLI as a **local subprocess** — no API key for Arima Notebooks to manage, no second vendor relationship. The same CLI lets you:

- Drive Arima Notebooks from the in-app AI panel (generate cells, explain output, convert languages)
- Open the same CLI inside the cloned repo to **reshape Arima Notebooks itself** (add a feature, fix a bug, write a tutorial)
- Ask the same CLI to **package your change as a PR back upstream** — closing the contribute loop

If you also want to drive Arima Notebooks from outside the UI, the Arima Notebooks MCP server lets any MCP-aware agent (Claude Code, Claude Desktop, custom agents) create notebooks, add cells, and run pipelines programmatically. See [`docs/USAGE.md` → Agentic Workflows](USAGE.md#agentic-workflows--use-customize-contribute).

---

## Language Support

Arima Notebooks supports multiple cell execution modes. Each language is **optional** — install only what you need.

| Language | Mode | Requirement | Install |
|----------|------|-------------|---------|
| JShell (Java REPL) | `jshell` | Built-in (JDK) | — |
| Java (compile+run) | `java` | Built-in (JDK) | — |
| JavaScript | `nodejs` | Node.js 18+ | [nodejs.org](https://nodejs.org) |
| TypeScript | `typescript` | Node.js 22.6+ (Node 24 recommended); optional `tsc` for type-check | [nodejs.org](https://nodejs.org) + `npm install -g typescript` |
| C# | `csharp` | .NET SDK 6+ | [dot.net](https://dot.net) |
| F# | `fsharp` | .NET SDK 6+ | [dot.net](https://dot.net) |
| **C++** | `cpp` | **g++ 9+, clang++ 9+, or MSVC 2017+** | See C++ section below |
| Python | `python` | Python 3.8+ (`python`/`python3`/`py`) | [python.org](https://www.python.org/downloads/) |

### Setting up C++ support

C++ cells require a C++17-compatible compiler. Arima Notebooks auto-detects compilers in this order:
1. `g++` on PATH
2. `clang++` on PATH
3. Visual Studio / Build Tools (MSVC) — Windows only, searched automatically in standard install locations

If no compiler is found, Arima Notebooks displays step-by-step installation instructions when you run a C++ cell.

> **Quick check**: Run `cout << "hello" << endl;` in a C++ cell. If it works, you're all set.
> If you see "C++ compiler not found", follow one of the install options below for your platform.

#### Linux (Ubuntu / Debian)
```bash
# Install g++ (C++ compiler + standard library)
sudo apt update
sudo apt install build-essential

# Verify
g++ --version       # should show g++ 9.0 or higher
g++ -std=c++17 -x c++ - <<< '#include<iostream>
int main(){std::cout<<"ok\n";}' && ./a.out
```

#### Linux (Fedora / RHEL / CentOS)
```bash
sudo dnf install gcc-c++
# or for older systems:
sudo yum install gcc-c++

g++ --version
```

#### Linux (Arch / Manjaro)
```bash
sudo pacman -S gcc
g++ --version
```

#### macOS
```bash
# Option A: Xcode Command Line Tools (provides clang++)
xcode-select --install

# Verify
clang++ --version   # should show Apple clang 12+ or LLVM 10+
# Note: macOS's 'g++' is actually clang++ — both work fine

# Option B: Homebrew GCC (true GCC)
brew install gcc
g++-13 --version   # Homebrew installs as g++-<version>
# Create a symlink if needed:
# ln -s $(brew --prefix gcc)/bin/g++-13 /usr/local/bin/g++
```

#### Windows — Option A: Visual Studio / Build Tools (Auto-detected, No PATH setup needed)

If you already have **Visual Studio 2017, 2019, or 2022** (any edition) or the standalone **Build Tools**, Arima Notebooks finds the compiler automatically — no PATH changes required.

1. Open **Visual Studio Installer** (search "Visual Studio Installer" in Start)
2. Click **Modify** on your installation
3. Under *Workloads*, check **"Desktop development with C++"**
4. Click **Modify** to install
5. Restart Arima Notebooks — C++ cells will work immediately

If you don't have Visual Studio yet, download the free **Build Tools** (no IDE required):
- Go to [visualstudio.microsoft.com/downloads/](https://visualstudio.microsoft.com/downloads/)
- Scroll to "Tools for Visual Studio" → download **"Build Tools for Visual Studio 2022"**
- During install, select **"Desktop development with C++"**
- Restart Arima Notebooks after installation

> **Verify**: Run `cout << __cplusplus << endl;` in a C++ cell. You should see `201703`.

#### Windows — Option B: MSYS2 + MinGW-w64 (Recommended for open-source toolchain)

MSYS2 provides a native Windows GCC toolchain with `g++` accessible from any terminal.

1. Download and install **MSYS2** from [msys2.org](https://www.msys2.org/)
2. Open the **MSYS2 UCRT64** terminal and install the toolchain:
   ```bash
   pacman -S mingw-w64-ucrt-x86_64-gcc
   ```
3. Add `C:\msys64\ucrt64\bin` to your Windows **PATH**:
   - Open **Start → Edit the system environment variables → Environment Variables**
   - Under *System variables*, select **Path → Edit → New**
   - Add: `C:\msys64\ucrt64\bin`
   - Click OK, then **restart Arima Notebooks and any open terminals**
4. Verify (in a new Command Prompt or PowerShell):
   ```cmd
   g++ --version
   ```

> **Tip:** If MSYS2 is installed at a different path, adjust accordingly. Default is `C:\msys64`.

#### Windows — Option C: WinLibs (Standalone, No Install Required)

WinLibs provides a standalone GCC for Windows that requires no installation.

1. Download **WinLibs** from [winlibs.com](https://winlibs.com/) (choose UCRT64, Win64)
2. Extract to a folder, e.g. `C:\mingw64`
3. Add `C:\mingw64\bin` to your system PATH (same steps as above)
4. Verify: `g++ --version`

#### Windows — Option D: Chocolatey

```powershell
# Run as Administrator
choco install mingw
# Verify
g++ --version
```

#### Windows — Option E: WSL (Windows Subsystem for Linux)

If you use WSL, install `build-essential` inside WSL, but note that Arima Notebooks runs as a Windows process and needs a Windows-native `g++.exe` on the Windows PATH. The WSL `g++` is not directly usable from Arima Notebooks.

#### Verify C++ is ready in Arima Notebooks

After installing, restart Arima Notebooks and run this cell:

```cpp
cout << "C++ " << __cplusplus << " works!" << endl;
```

If you see output like `C++ 201703 works!`, C++ cells are ready.

The Settings tab → **Server Status** will show `⚙ C++: ✓ g++ found` when the compiler is detected.

---

### Setting up C# and F# support

Both C# and F# cells require only the standard .NET SDK — no extra tools or global packages needed.

```bash
# 1. Install .NET SDK from https://dot.net (free, cross-platform)

# 2. Verify C# compilation works
dotnet --version

# 3. Verify F# Interactive is available
dotnet fsi --version
```

C# cells compile and run via `dotnet run` inside a generated temp project.
F# cells run via `dotnet fsi --exec` (F# Interactive, bundled with the SDK).

> **No dotnet-script needed**: Arima Notebooks uses `dotnet run` directly for C# cells.
> If you previously installed `dotnet-script`, you can keep it — Arima Notebooks will not use it.

### NuGet packages (C# and F#)

Install NuGet packages via the **Packages → NuGet** tab in Arima Notebooks. Installed packages are automatically injected as `#r "nuget: PackageId, Version"` directives in every C# and F# cell.

---

## Installation

### Step 1: Get the code

```bash
# Clone from GitHub (once published)
git clone https://github.com/snchande/arima-notebooks.git
cd arima-notebooks

# OR download the zip and extract
```

### Step 2: Verify Java

```bash
# Verify JDK (not JRE)
java -version
javac -version

# Both should show 17+ or higher
```

**Windows: Setting JAVA_HOME**
```cmd
# If java isn't found, set JAVA_HOME
set JAVA_HOME=C:\Program Files\Java\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%
```

**Unix/Mac: Setting JAVA_HOME**
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
```

### Step 3: Build

```bash
mvn clean package -DskipTests
```

This creates `target/arima-notebooks-1.0.0-SNAPSHOT.jar`.

### Step 4: Run

**Option A: Arima Notebooks CLI — Windows (recommended)**
```cmd
arima
```
`arima.cmd` in the project root auto-builds if the JAR is missing, starts the server, and opens your browser. See the [Arima Notebooks CLI reference](#arima-notebooks-cli-windows) below for all commands.

**Option B: Startup scripts**
```bash
# Unix/Mac
./scripts/start.sh

# Windows (basic launcher)
scripts\start.bat
```

**Option C: Maven dev mode**
```bash
mvn spring-boot:run
```

**Option D: JAR directly**
```bash
java \
  --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \
  -jar target/arima-notebooks-1.0.0-SNAPSHOT.jar
```

### Step 5: Open Browser

Navigate to: **http://localhost:8585**
(The `arima` CLI opens this automatically.)

---

## Configuration

### Port

Default port is `8585`. To change it:

**Option A: application.properties**
```properties
server.port=9000
```

**Option B: Command line**
```bash
java -jar arima-notebooks.jar --server.port=9000
```

**Option C: Environment variable**
```bash
SERVER_PORT=9000 java -jar arima-notebooks.jar
```

---

## AI Assistant Setup

Arima Notebooks supports three AI providers. Install any one (or all) and select the active provider from the **AI sidebar** switcher or **Settings → AI Provider**.

### Claude CLI (default)

No Anthropic API key or account credits required — Arima Notebooks calls the local Claude CLI.

1. Install Claude Code from [claude.ai/code](https://claude.ai/code)
2. Authenticate: run `claude auth` in a terminal
3. Start Arima Notebooks — the AI Assistant is ready

The Settings tab → Server Status shows **Claude CLI: ✓ Found** when detected.

**Claude CLI on PATH (Windows)** — if Arima Notebooks shows "CLI not found" after authentication:

```cmd
where claude
```

Common install locations checked automatically:
- `%APPDATA%\npm\claude.cmd` (npm global install)
- `%LOCALAPPDATA%\Programs\claude\claude.exe`
- Any directory on your system PATH

---

### GitHub Copilot (SDK)

Arima Notebooks uses the **GitHub Copilot SDK for Java** (`com.github:copilot-sdk-java`), which drives the local `copilot` CLI in server mode over JSON-RPC. It runs chat-only — the SDK's file-editing tools are denied.

1. Install the [GitHub Copilot CLI](https://github.com/github/copilot-cli) (`copilot`, v1.0.55-5 or later)
2. Authenticate the `copilot` CLI
3. Verify: `copilot --version` prints a version number
4. In Arima Notebooks, open **Settings → AI Provider** and select **Copilot**, or use the toggle in the AI sidebar

The Settings tab → Server Status shows **Copilot SDK: ✓ copilot CLI found** when the `copilot` binary is detected.

---

### Antigravity CLI (`agy`)

Google retired the standalone Gemini CLI on 2026-06-18 and replaced it with the **Antigravity CLI (`agy`)**. Arima Notebooks calls `agy` via `agy -p "prompt"` (grounded in the repo working directory).

1. Install the Antigravity CLI from [antigravity.google/docs/cli-install](https://antigravity.google/docs/cli-install)
2. Sign in: run `agy` once in a terminal (or set `GEMINI_API_KEY` / `ANTIGRAVITY_API_KEY`)
3. In Arima Notebooks, open **Settings → AI Provider** and select **Antigravity**, or use the toggle in the AI sidebar

The Settings tab → Server Status shows **Antigravity CLI: ✓ Found** when detected. (The provider key remains `gemini_cli` for backward compatibility.)

---

### Switching AI Providers

You can switch providers at any time — no restart required:

- **AI sidebar**: use the **Claude / Copilot / Antigravity** toggle bar at the top of the AI panel
- **Settings tab**: select the provider card under **AI Provider** and click **Save Settings**

The active provider is shown in the sidebar header and in the status bar badge.

---

## Directory Structure After First Run

```
arima/
├── data/
│   ├── settings.json          # Created automatically
│   ├── packages.json          # Created automatically (initially empty)
│   └── packages/              # JAR files go here when packages are installed
├── notebooks/
│   └── welcome.vnb            # Pre-existing welcome notebook
└── target/
    └── arima-notebooks-1.0.0-SNAPSHOT.jar
```

---

## Arima Notebooks CLI (Windows)

`arima.cmd` is a full-featured command-line launcher located in the project root.

### Commands

| Command | Description |
|---------|-------------|
| `arima` | Start server (auto-build if needed), open browser |
| `arima start` | Same as above |
| `arima start --bg` | Start in background; logs go to `arima.log` |
| `arima stop` | Kill the running server (finds PID via netstat) |
| `arima status` | Running state, PID, JAR exists, Java version |
| `arima build` | `mvn clean package -DskipTests` (skips if JAR exists) |
| `arima rebuild` | Force clean build |
| `arima open` | Open browser (server must already be running) |
| `arima logs` | Tail `arima.log` (background mode only) |
| `arima version` | Java + Maven version info |
| `arima help` | Illustrated help with ASCII banner |

### Background mode

Run the server detached so the terminal is free:

```cmd
arima start --bg
```

Logs are written to `arima.log` in the project root. Stream them with:

```cmd
arima logs
```

Stop the background server at any time with:

```cmd
arima stop
```

### Claude AI with the CLI

Ensure `claude auth` has been run once in any terminal before starting Arima Notebooks.
The `claude` executable is automatically detected from your PATH and common install locations.

---

## MCP Server Setup

Arima Notebooks runs a built-in MCP (Model Context Protocol) server at `http://localhost:8585/api/mcp/sse`. Any MCP-compatible AI client can connect to it while Arima Notebooks is running.

### Claude Desktop

1. Open (or create) `claude_desktop_config.json`:
   - **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
2. Add the Arima Notebooks server entry:
   ```json
   {
     "mcpServers": {
       "arima-notebooks": {
         "url": "http://localhost:8585/api/mcp/sse"
       }
     }
   }
   ```
3. Restart Claude Desktop. Arima Notebooks tools appear automatically.

### Claude Code CLI

```bash
claude mcp add arima-notebooks --transport sse --url http://localhost:8585/api/mcp/sse
```

Or add manually to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "arima-notebooks": {
      "transport": "sse",
      "url": "http://localhost:8585/api/mcp/sse"
    }
  }
}
```

Verify: `claude mcp list` — `arima-notebooks` should appear.

### Available MCP Tools

Once connected, the AI client can:

| Tool | What it does |
|------|-------------|
| `barista_execute_code` | Run Java code in a JShell session |
| `barista_list_notebooks` | List all notebooks |
| `barista_read_notebook` | Read all cells from a notebook |
| `barista_run_pipeline` | Execute a pipeline cell |
| `barista_search_cells` | Search cells by content or anchor |
| `barista_load_module` | Load a notebook module into a session |
| `barista_create_notebook` | Create a notebook with cells |
| `barista_append_cell` | Append and optionally execute a cell |

See [docs/API.md — MCP Server section](API.md#mcp-server-model-context-protocol) for full JSON-RPC details.

---

## IDE Setup

### IntelliJ IDEA

1. Open the project: **File → Open** → select the `arima` directory
2. IntelliJ will detect the `pom.xml` and configure Maven automatically
3. To run from IDE: Edit the run configuration and add JVM args:
   ```
   --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED
   --add-opens=java.base/java.lang=ALL-UNNAMED
   --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED
   ```

### VS Code

1. Install the **Extension Pack for Java** extension
2. Open the `arima` folder
3. Add JVM args to `.vscode/launch.json`:
```json
{
  "configurations": [{
    "type": "java",
    "name": "Arima Notebooks",
    "request": "launch",
    "mainClass": "com.barista.BaristaApplication",
    "vmArgs": "--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED"
  }]
}
```

---

## Troubleshooting

### "JShell not found" / `InaccessibleObjectException`

**Cause**: Missing JVM flags or using JRE instead of JDK.

**Fix**:
1. Ensure you're running with a JDK (check `javac -version`)
2. Always include all three JVM flags:
   ```
   --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED
   --add-opens=java.base/java.lang=ALL-UNNAMED
   --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED
   ```

### `arima` command not recognized

**Cause**: The project root is not in your PATH.

**Fix**: Run `arima.cmd` from the project root directory, or add the project root to your PATH:
```cmd
set PATH=%PATH%;C:\path\to\arima
```

### `arima stop` says "not running" but port 8585 is busy

**Fix**: The process may not be on the expected port. Find and kill manually:
```cmd
netstat -ano | findstr :8585
taskkill /PID <pid> /F
```

### Port 8585 is already in use

**Fix**: Change the port in `application.properties` or via command line:
```bash
java ... -jar arima-notebooks.jar --server.port=8586
```

### WebSocket connection failed

**Symptoms**: Red dot in top-right corner, real-time output not working.

**Fix**:
1. Check the browser console (F12) for errors
2. Ensure no proxy is intercepting WebSocket connections
3. Try a different browser
4. Restart the server

### Package download fails

**Symptoms**: "Failed to download" error when installing packages.

**Fix**:
1. Check internet connectivity
2. Verify package exists on https://search.maven.org/
3. Make sure the version number is correct
4. Check if `data/packages/` directory is writable

### AI not responding

First, check which provider is active in the AI sidebar or Settings → AI Provider, then follow the guide for that provider:

**Claude CLI**
1. Install: [claude.ai/code](https://claude.ai/code)
2. Run `claude auth` in a terminal
3. Verify: `where claude` (Windows) or `which claude` (Mac/Linux)
4. Check Settings → Server Status → **Claude CLI: ✓ Found**

**GitHub Copilot (SDK)**
1. Verify the `copilot` binary (v1.0.55-5+) is on your PATH: `copilot --version`
2. Re-authenticate the `copilot` CLI if needed
3. Check Settings → Server Status → **Copilot SDK: ✓ copilot CLI found**

**Antigravity CLI (`agy`)**
1. Install from [antigravity.google/docs/cli-install](https://antigravity.google/docs/cli-install)
2. Sign in: run `agy` (or set `GEMINI_API_KEY` / `ANTIGRAVITY_API_KEY`)
3. Check Settings → Server Status → **Antigravity CLI: ✓ Found**

### C++ cells fail with "C++ compiler not found"

**Cause**: `g++` and `clang++` are not on the system PATH, or no C++17-capable compiler is installed.

**Fix**:
1. Install a compiler — see the **Setting up C++ support** section above
2. Restart Arima Notebooks (PATH is read at server startup)
3. Verify in Arima Notebooks: run `cout << __cplusplus << endl;` in a C++ cell — should print `201703`

**On Windows — compiler detection order:**
Arima Notebooks checks for compilers in this order:
1. `g++` on PATH (MinGW-w64 / MSYS2 / WinLibs)
2. `clang++` on PATH (LLVM)
3. Visual Studio / Build Tools MSVC (searched automatically, no PATH needed)

**If g++ is not found** (MinGW installed but PATH not updated):
```cmd
where g++           # should print a path; if missing, PATH is not set
g++ --version       # should print version info
```
Re-add `C:\msys64\ucrt64\bin` (or your MinGW bin path) to the system PATH and restart Arima Notebooks.

**If Visual Studio is installed but not detected**: Ensure the "Desktop development with C++" workload is installed (open Visual Studio Installer → Modify → check the workload).

### C++ cell compiles but crashes at runtime

**Symptoms**: Cell shows "Process exited with code 1" or similar.

**Fix**:
- Check for null pointer dereference, array out-of-bounds, or stack overflow
- Use `vector.at(i)` instead of `vector[i]` for bounds-checking
- Add `try/catch` to diagnose unhandled exceptions:
  ```cpp
  try {
      // your code
  } catch (const exception& e) {
      cout << "Exception: " << e.what() << "\n";
  }
  ```

### C++ compile error line numbers seem off

**Cause**: Arima Notebooks adjusts line numbers to hide the injected preamble (25 header lines). In rare cases, the offset may differ.

**Fix**: The error shows `main.cpp:<line>` — the actual error is at that line in your cell code. Temporarily add `#line 1` at the top of the cell to force line 1 as the reference.

### C# cells fail with ".NET SDK not found"

**Fix**:
1. Install the .NET SDK from [dot.net](https://dot.net)
2. Restart Arima Notebooks
3. Verify: `dotnet --version`
4. Check Settings → Server Status → **.NET** shows ✓ Found

> Arima Notebooks uses `dotnet run` directly — no `dotnet-script` tool is needed.

### F# cells fail with ".NET SDK not found"

**Fix**:
1. Install the .NET SDK from [dot.net](https://dot.net)
2. Restart Arima Notebooks
3. Verify: `dotnet fsi --version`
4. Check Settings → Server Status → **dotnet** shows ✓ Found

### NuGet packages not loading in cells

**Symptoms**: C# or F# cell fails with "The type or namespace could not be found".

**Fix**:
1. Go to **Packages → NuGet** tab and verify the package is listed
2. Check the package ID and version are correct (case-sensitive)
3. The .NET runtime downloads packages on first use — ensure internet access
4. NuGet package cache is at `%USERPROFILE%\.nuget\packages` (Windows) or `~/.nuget/packages` (Unix)
