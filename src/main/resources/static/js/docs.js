/**
 * Arima Notebooks — In-App Documentation
 * Usage Guide · Setup & Install · Tutorials · Pipelines/Workflows · MCP & Agents
 * API Reference · Architecture · Developer Guide
 */

const DocsPanel = (() => {
    const DOCS = {

// ═══════════════════════════════════════════════════════
// USER MANUAL
// ═══════════════════════════════════════════════════════
usage: `
# Arima Notebooks — User Guide

Arima is a multi-language interactive notebook environment. Write and run code across **seven languages** — JShell, Java, JavaScript, **TypeScript**, C#, F#, and C++ — in cells that live alongside Markdown documentation. Organise work with named Workflows, get AI assistance from Claude, GitHub Copilot, or Antigravity, and expose everything as an API.

---

## 1. Getting Started

1. **Open the app** at [http://localhost:8585](http://localhost:8585)
2. **Create a notebook** — click **+ New** in the top bar, give it a name, press Enter
3. **Add cells** — use the toolbar:
   - **+ Code** — an executable code cell (default: JShell)
   - **+ Markdown** — a text/documentation cell
   - **+ Pipeline** — a named workflow cell (see Section 5)
4. **Run a code cell** — press the ▶ button, or press **Shift+Enter** inside the editor
5. **Save** — press **Ctrl+S** or click **Save**
6. **Play a tutorial** — click **Tutorials**, then **▶ Guided** on any card for a narrated, hands-free walkthrough you can interrupt and ask questions (voice or text). See the **Tutorials** doc for the full player guide.

---

## 2. Execution Modes

Every code cell has an **execution mode** badge in its header. Click it to cycle through all available languages.

| Mode badge | Language | Runtime | Shared state |
|---|---|---|---|
| **JShell** | Java (REPL) | Built-in JDK | ✓ Shared across cells |
| **Java** | Java (compile+run) | \`javax.tools\` + subprocess | ✗ Per-cell |
| **JS** | JavaScript | Node.js subprocess | ✗ Per-cell |
| **TS** | TypeScript 5+ | Node.js type-stripping (+ optional \`tsc\` check) | ✗ Per-cell |
| **C#** | C# 9+ top-level | \`dotnet run\` | ✗ Per-cell (pipeline injection) |
| **F#** | F# Script | \`dotnet fsi\` | ✗ Per-cell (pipeline injection) |
| **C++** | C++ 17/20 | MSVC / GCC / Clang subprocess | ✗ Per-cell |
| **Python** | Python 3.8+ | \`python\` subprocess | ✗ Per-cell (pipeline injection) |

---

### 2.1 JShell Mode — Java REPL (default)

All cells in a notebook share **one JShell session**. Variables, methods, and imports declared in one cell are available in all later cells — just like Jupyter.

\`\`\`java
// Cell 1 — declare variables
var name = "Arima";
var version = 2;

// Cell 2 — use what Cell 1 declared
System.out.printf("Hello from %s v%d!%n", name, version);
\`\`\`

**Tips:**
- Omit \`public class\` and \`public static void main\` — write statements directly
- Use \`var\` for type inference
- Expressions automatically show their value (green return-value display)
- Pre-loaded: \`java.util.*\`, streams, Tablesaw, XChart, Commons Math, OpenCSV

---

### 2.2 Java Mode — Compile + Run

Each cell is compiled as a standalone \`public class Main\` and executed in a subprocess.

\`\`\`java
public class Greeter {
    private final String name;
    public Greeter(String name) { this.name = name; }
    public String greet() { return "Hello, " + name + "!"; }
    public static void main(String[] args) {
        System.out.println(new Greeter("World").greet());
    }
}
\`\`\`

Bare statements are auto-wrapped — no class boilerplate required.

---

### 2.3 JavaScript Mode — Node.js

Each cell runs as a Node.js script. A built-in \`barista\` object provides output helpers.

\`\`\`javascript
const data = [10, 25, 37, 42, 18];
const mean = data.reduce((a, b) => a + b, 0) / data.length;
barista.table([{ metric: "Mean", value: mean.toFixed(2) }]);
\`\`\`

**Built-in helpers:** \`barista.table(rows)\`, \`barista.display(value)\`, \`barista.html(html)\`, \`barista.stats(arr)\`

Install npm packages via the **Packages → npm** tab. Modules are available via \`require()\`.

---

### 2.4 TypeScript Mode — Node.js with type-stripping

TypeScript cells run as **TypeScript 5+** via Node.js 22.6's built-in type-stripping runtime — no separate compile step, no project setup. If \`tsc\` is on your PATH, Arima also runs a quick \`tsc --noEmit\` pass before execution so you see type errors with proper line numbers.

\`\`\`typescript
type Sale = { region: string; amount: number };

const data: Sale[] = [
    { region: "North", amount: 120 },
    { region: "South", amount: 95  },
    { region: "East",  amount: 142 },
];

const total: number = data.reduce((sum, r) => sum + r.amount, 0);
barista.table([{ metric: "Total",   value: total },
             { metric: "Avg/row", value: (total / data.length).toFixed(2) }]);
\`\`\`

**Built-in helpers (typed):** \`barista.table(rows: Record<string, unknown>[])\`, \`barista.display(value: unknown)\`, \`barista.html(content: string)\`, \`barista.stats(arr: number[])\`

**npm packages** installed via the **Packages → npm** tab work in TS cells too — the same \`data/npm-modules/\` directory is shared:

\`\`\`typescript
import * as ss from "simple-statistics";

const samples: number[] = [10, 25, 37, 42, 18, 65, 22];
console.log("median:", ss.median(samples));
console.log("stddev:", ss.standardDeviation(samples).toFixed(3));
\`\`\`

**Type-check vs runtime:** Type errors (e.g. \`Type 'string' is not assignable to type 'number'\`) are reported from \`tsc --noEmit\` when available. Runtime exceptions are reported as usual.

> **Tip — Node version:** TypeScript cells require Node.js ≥ 22.6 (Node 24 LTS is recommended). The **Settings → Server Status** panel shows the detected version.

---

### 2.5 C# Mode — dotnet run

Each C# cell runs as a **C# 9+ top-level program** compiled and executed via \`dotnet run\`. No project file setup required — Arima generates everything automatically.

\`\`\`csharp
//@ anchor: loadData
//@ description: Load transaction records

var transactions = new List<(string Category, decimal Amount)>
{
    ("Food",      142.50m),
    ("Transport",  89.00m),
    ("Utilities", 115.00m),
};

Console.WriteLine($"Loaded {transactions.Count} records");
ArimaTable(transactions);
\`\`\`

**Built-in helpers:** \`ArimaHtml(html)\`, \`ArimaDisplay(obj)\`, \`ArimaTable<T>(list)\`

**Auto-usings:** \`System\`, \`System.Linq\`, \`System.Collections.Generic\`, \`System.Text\`, \`System.IO\`

Use the **Packages → NuGet** tab to install NuGet packages. They are automatically injected into every C# and F# cell.

---

### 2.6 F# Mode — dotnet fsi

Each F# cell runs as an \`.fsx\` script via F# Interactive. No installation beyond the .NET SDK.

\`\`\`fsharp
//@ anchor: computeStats
//@ description: Compute basic statistics

let values = [| 10.0; 25.0; 37.0; 42.0; 18.0 |]
let mean   = Array.average values
let sorted = Array.sort values
let median = sorted.[sorted.Length / 2]

printfn "Mean:   %.2f" mean
printfn "Median: %.2f" median
baristaTable [| {| Metric = "Mean"; Value = mean |}
              {| Metric = "Median"; Value = median |} |]
\`\`\`

**Built-in helpers:** \`baristaHtml\`, \`baristaDisplay\`, \`baristaTable\`

**Auto-opens:** \`System\`, \`System.Linq\`, \`System.Collections.Generic\`

Inline \`#r "nuget: PackageId, Version"\` directives are supported and automatically placed at the correct position in the script.

---

### 2.7 C++ Mode — Native Compilation

Each C++ cell is compiled and run as a standalone program using the best available compiler: **MSVC** on Windows (Visual Studio Build Tools), **GCC** or **Clang** on macOS/Linux. No project setup required — Arima generates a temp source file, compiles it, and runs it.

\`\`\`cpp
//@ anchor: hello-cpp
//@ description: Basic C++ hello world with STL

#include <iostream>
#include <vector>
#include <numeric>

int main() {
    std::vector<int> nums = {1, 2, 3, 4, 5};
    int sum = std::accumulate(nums.begin(), nums.end(), 0);
    std::cout << "Sum: " << sum << std::endl;
    return 0;
}
\`\`\`

**Standard headers included by default** (no \`#include\` needed — but you can add them for clarity):
\`<iostream>\`, \`<vector>\`, \`<string>\`, \`<map>\`, \`<algorithm>\`, \`<numeric>\`, \`<sstream>\`, \`<fstream>\`, \`<functional>\`, \`<memory>\`, \`<thread>\`, \`<mutex>\`, \`<chrono>\`, \`<regex>\`, \`<filesystem>\`, \`<optional>\`, \`<variant>\`, \`<tuple>\`, \`<array>\`, \`<deque>\`, \`<queue>\`, \`<stack>\`, \`<set>\`, \`<unordered_map>\`, \`<unordered_set>\`

**Important:** C++ cells are per-cell isolated — there is no shared state between cells. Each cell compiles and runs as a complete program.

See **Packages → C++ (Built-in)** for the full header reference grouped by category.

---

### 2.8 Python Mode — python3 subprocess

Each Python cell runs in a fresh **Python 3** process. Write top-level code — no boilerplate. Install any library from the **Packages → PyPI** tab and \`import\` it; installed packages live in an isolated folder on \`PYTHONPATH\`, so your system Python is never touched.

\`\`\`python
import statistics

scores = [88, 92, 79, 95, 84]
print("mean:", statistics.mean(scores))
print("stdev:", round(statistics.stdev(scores), 2))

# Arima helpers are pre-injected into every Python cell:
barista.table([{"name": "Ada", "score": 92}, {"name": "Linus", "score": 88}])
barista.stats(scores)
\`\`\`

**Arima helpers (Python):**
- \`barista.table(rows)\` — print a list of dicts as a text table
- \`barista.stats(values)\` — count / min / max / mean / std
- \`barista.html(markup)\` — render inline HTML (KPI cards, badges, **inline SVG charts**)
- \`barista.image(fig)\` — render a matplotlib figure (or PNG bytes / path) as an image
- \`barista.display(obj)\` — pretty-print as JSON

**Pipelines:** like C#/F#, Python cells compose via \`//@ anchor:\` and \`//@ depends:\` — ancestor cells are executed (output suppressed) so their variables are in scope for the dependent cell.

---

### 2.9 Cell Output

| Output type | Shown as |
|---|---|
| \`System.out.println\` / \`Console.WriteLine\` / \`console.log\` (JS/TS) / \`printfn\` / \`std::cout\` | Plain text below the cell |
| Return value expression (JShell) | Green ⟹ value display |
| \`ArimaHtml()\` / \`barista.html()\` / \`baristaHtml\` | Rendered inline HTML block |
| \`ArimaDisplay(chart)\` (XChart) | Inline PNG chart image |
| Compile error | Red ✖ with line numbers |
| Runtime exception | Red ✖ with stack trace excerpt |

---

## 3. Markdown Cells

Click a Markdown cell to enter edit mode. Press **Escape** or click elsewhere to render.

Supported: headings, bold/italic, lists, code blocks (\`\`\`java\`\`\`), blockquotes, tables, links, inline code.

---

## 4. Keyboard Shortcuts

| Key | Action |
|---|---|
| **Shift+Enter** | Run current cell |
| **Ctrl+Enter** | Run current cell (alternative) |
| **Ctrl+S** | Save notebook |
| **Ctrl+\\** | Toggle AI Assistant sidebar |
| **↑ / ↓** (Console) | Navigate command history |
| **Escape** (Markdown) | Exit edit mode |

---

## 5. Package Managers

### Maven (Java / JShell)

Go to **Packages → Maven** to install Maven Central packages.

1. Enter a Maven coordinate: \`groupId:artifactId:version\`
2. Click **Install** — the JAR is downloaded and added to all JShell sessions
3. Use it in any JShell or Java cell — no import step required for pre-installed libs

### npm (JavaScript / TypeScript)

Go to **Packages → npm** to install npm packages — they are shared between JS and TS cells.

1. Enter a package name (e.g. \`lodash\`, \`mathjs\`, \`simple-statistics\`)
2. Click **Install** — the package is downloaded to \`data/npm-modules/\`
3. Use via \`require('lodash')\` in JS cells, or \`import * as _ from 'lodash'\` in TS cells

### NuGet (C# / F#)

Go to **Packages → NuGet** to install NuGet packages.

1. Enter a Package ID and Version (e.g. \`Newtonsoft.Json\`, \`13.0.3\`)
2. Click **Install** — the package is registered and automatically injected into every C# and F# cell
3. Use it immediately — no \`#r\` directive needed in cell code

### PyPI (Python)

Go to **Packages → PyPI** to install any package from the [Python Package Index](https://pypi.org).

1. Enter a package name (e.g. \`requests\`, \`numpy\`, \`pandas\`) or \`name==version\`
2. Click **Install** — \`pip\` downloads it into an isolated folder (\`data/pypi-packages/\`) added to \`PYTHONPATH\`
3. Use it immediately with \`import <package>\` in any Python cell — your system Python is never modified

Use **Find on PyPI** to look up a package's latest version and summary before installing. Because installs go to a dedicated target folder, **Remove** cleanly deletes exactly what that package added.

### C++ (Built-in Headers)

Go to **Packages → C++ (Built-in)** to see the reference panel.

C++ does **not** use installable packages — instead, Arima pre-includes 26 standard C++ headers covering all major categories: I/O, containers, algorithms, threading, filesystem, and more. The panel shows these grouped by category with usage examples.

For third-party libraries (Boost, Eigen, nlohmann/json, etc.) install them system-wide using your OS package manager so the compiler can find them — Arima does not manage C++ package installation.

> **Removing a package:** The Maven, npm, NuGet, and PyPI tabs each have a **Remove** button next to installed packages. You'll be warned that any notebook cells using that package will fail until it is re-installed.

---

## 6. Cell Workflows (Pipelines)

Arima includes a named-cell dependency system that works across all seven languages. See the **Pipelines** tab for the full reference.

**Quick start:**
1. Click the **+ anchor** badge in a cell header to name a cell (e.g. \`loadData\`)
2. Add \`//@ depends: loadData, setup\` at the top of a dependent cell
3. Click **▶▶** (run-with-deps) to auto-execute prerequisites in order
4. Add a **+ Pipeline** cell to define and run a complete named workflow

---

## 7. AI Assistant

Open with **Ctrl+\\** or the **AI** button.

- **Chat** — ask anything about code in any of the seven languages; attach cell context with the 🤖 button
- **Generate notebook** — click ★, describe your goal, get a full multi-cell notebook
- **Explain / Fix** — right-click on a cell or use the 🤖 button to explain or fix errors
- **Insert code** — code blocks in AI responses include **+ Insert into notebook**
- **Switch provider** — use the provider bar at the top of the AI panel to switch between Claude, GitHub Copilot (SDK), and Antigravity. The active provider is saved in Settings.
- **Language conversion** — when you switch a cell's language, a banner appears offering to convert the existing code to the new language using AI

All three providers run as **local CLI subprocesses** — no API key or cloud account needed (only CLI login).

---

## 8. Interactive Console

The Console tab is a live REPL for JShell, Java, JavaScript, and TypeScript — separate from notebooks.

- **JShell** — stateful; use the Session field to share a notebook's session (\`nb-{notebookId}\`)
- **Java** — compile-and-run per command
- **JavaScript** — Node.js per command
- **TypeScript** — Node.js with type-stripping per command
- **Tab** — auto-complete in JShell mode
- **↑ / ↓** — navigate history (up to 500 entries)

---

## 9. Settings

| Setting | Description |
|---|---|
| AI Provider | Claude CLI · GitHub Copilot SDK · Antigravity CLI (\`agy\`) |
| Model | Model name for the selected provider (e.g. claude-sonnet-4-6) |
| Theme | Dark (default) or Light |
| Font Size | Editor font size in pixels |
| Line Numbers | Show/hide in code cells |
| Focus executing cell | Scroll to the cell being run during pipeline execution |

The **Server Status** panel shows which runtimes are detected: Java, Node.js (JS + TS), TypeScript compiler, .NET SDK, C++ Compiler, Claude CLI, Copilot (SDK), Antigravity CLI.

---

## 10. Saving

- **Auto-save** every 30 seconds when changes are present (yellow dot on Save button)
- **Manual save** with Ctrl+S
- Notebooks are \`.vnb\` JSON files in \`notebooks/\` — cell output is persisted with the notebook
`,

// ═══════════════════════════════════════════════════════
// SETUP & INSTALL
// ═══════════════════════════════════════════════════════
setup: `
# Setup & Install

Arima runs on any platform — Windows, macOS, and Linux. Install only the runtimes you plan to use.

---

## Required: Java

| Requirement | Minimum | Recommended |
|---|---|---|
| Java JDK | 17 | 21 LTS |
| Maven | 3.8 | 3.9+ |

> You need a full **JDK**, not just a JRE — JShell is a JDK tool.
> Verify: \`javac -version\` (should show 17+)

**Windows (JAVA_HOME):**
\`\`\`cmd
set JAVA_HOME=C:\\Program Files\\Java\\jdk-21
set PATH=%JAVA_HOME%\\bin;%PATH%
\`\`\`

**Mac/Linux:**
\`\`\`bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH
\`\`\`

---

## Optional: Node.js (JavaScript & TypeScript cells)

| Requirement | Minimum | Notes |
|---|---|---|
| Node.js (JS only)   | 18 LTS  | Older Node runs JS but not TS |
| Node.js (JS + TS)   | 22.6+   | Built-in TypeScript type-stripping is required |
| Node.js (recommended) | 24 LTS | Runs \`.ts\` files natively |

Download from [nodejs.org](https://nodejs.org). After install, verify: \`node --version\`

Without Node.js, JavaScript and TypeScript cells will fail. All other languages (JShell, Java, C#, F#, C++) work normally.

### Optional: TypeScript compiler (\`tsc\`) for type-checking

TypeScript **runtime** does not require any install beyond Node 22.6+ — Arima uses Node's built-in type-stripping. But to get full **type-check** diagnostics (e.g. *"Type 'string' is not assignable to type 'number'"*) install the official compiler globally:

\`\`\`bash
npm install -g typescript
tsc --version   # verify
\`\`\`

When \`tsc\` is on the PATH, Arima automatically runs \`tsc --noEmit\` before each TS cell and folds any diagnostics into the cell's error stream. Without \`tsc\`, TS cells still run — you just lose the pre-execution type-check pass.

---

## Optional: .NET SDK (C# and F# cells)

| Requirement | Minimum |
|---|---|
| .NET SDK | 6.0 |

Download from [dot.net](https://dot.net). The SDK is free and runs on Windows, Mac, and Linux.

**No extra tools needed** — Arima uses \`dotnet run\` for C# and \`dotnet fsi\` for F#, both bundled in the SDK.

Verify after install:
\`\`\`bash
dotnet --version      # SDK version
dotnet fsi --version  # F# Interactive version
\`\`\`

Without .NET SDK, C# and F# cells will show a ".NET SDK not found" error. JShell, Java, and JavaScript cells work normally.

### NuGet packages

C# and F# packages are installed via the **Packages → NuGet** tab. Arima uses the .NET SDK's built-in NuGet restore — no extra NuGet CLI installation needed.

NuGet package cache location:
- **Windows:** \`%USERPROFILE%\\.nuget\\packages\`
- **Mac/Linux:** \`~/.nuget/packages\`

---

## Optional: C++ (C++ cells)

Arima detects whichever C++ compiler is available on your system. No extra install needed if you already have one.

| Platform | Compiler | Install |
|---|---|---|
| Windows | MSVC | Install [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022) — select "Desktop development with C++" |
| macOS | Clang | \`xcode-select --install\` |
| Linux | GCC | \`sudo apt install g++\` or \`sudo dnf install gcc-c++\` |

Verify: \`cl\` (Windows) or \`g++ --version\` / \`clang++ --version\`

Without a C++ compiler, C++ cells will show a "No C++ compiler found" error. All other languages work normally.

---

## Optional: AI Assistant

Arima supports three AI providers — all run as **local CLI subprocesses**, no API key required.

### Claude CLI (default)

1. Install Claude Code from [claude.ai/code](https://claude.ai/code)
2. Run \`claude auth\` in a terminal to sign in
3. Start Arima — Claude is detected automatically

Verify: \`claude --version\`

### GitHub Copilot (SDK)

Arima uses the **GitHub Copilot SDK for Java**, which drives the local \`copilot\` CLI (chat-only).

1. Install the [GitHub Copilot CLI](https://github.com/github/copilot-cli) (\`copilot\`, v1.0.55-5+)
2. Authenticate the \`copilot\` CLI
3. Select **Copilot** in Arima Settings → AI Provider

Verify: \`copilot --version\`

### Antigravity CLI (\`agy\`)

Successor to the retired Gemini CLI (Google retired Gemini CLI on 2026-06-18).

1. Install: [antigravity.google/docs/cli-install](https://antigravity.google/docs/cli-install)
2. Sign in: run \`agy\` (or set \`GEMINI_API_KEY\` / \`ANTIGRAVITY_API_KEY\`)
3. Select **Antigravity** in Arima Settings → AI Provider

Arima calls \`agy -p "prompt"\`.

The Settings tab → Server Status shows which AI providers are detected.

---

## Building and Running Arima

### Step 1 — Get the code

\`\`\`bash
git clone https://github.com/snchande/arima-notebooks.git
cd arima-notebooks
\`\`\`

### Step 2 — Build

\`\`\`bash
mvn clean package -DskipTests
\`\`\`

Creates \`target/arima-notebooks-1.0.0-SNAPSHOT.jar\`.

### Step 3 — Start Arima

**Option A — Windows launcher (recommended)**
\`\`\`cmd
barista
\`\`\`
Auto-builds if needed, starts the server, and opens your browser.

**Option B — Startup scripts**
\`\`\`bash
./scripts/start.sh      # Unix/Mac
scripts\\start.bat       # Windows
\`\`\`

**Option C — Maven dev mode**
\`\`\`bash
mvn spring-boot:run
\`\`\`

**Option D — JAR directly**
\`\`\`bash
java \\
  --add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED \\
  --add-opens=java.base/java.lang=ALL-UNNAMED \\
  --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED \\
  -jar target/arima-notebooks-1.0.0-SNAPSHOT.jar
\`\`\`

### Step 4 — Open Browser

Navigate to: **http://localhost:8585**

---

## Environment Verification Checklist

| Runtime | Verify command | Expected |
|---|---|---|
| Java JDK | \`javac -version\` | \`javac 17\` or higher |
| Maven | \`mvn -version\` | \`Apache Maven 3.8\` or higher |
| Node.js (JS) | \`node --version\` | \`v18\` or higher |
| Node.js (TS) | \`node --version\` | \`v22.6\` or higher |
| TypeScript (optional) | \`tsc --version\` | any 5.x or 6.x |
| .NET SDK (C#) | \`dotnet --version\` | \`6.0\` or higher |
| F# Interactive | \`dotnet fsi --version\` | \`F# ...\` line |
| C++ (Windows) | \`cl\` | MSVC version line |
| C++ (Mac/Linux) | \`g++ --version\` or \`clang++ --version\` | any version |
| Claude CLI | \`claude --version\` | any version |
| Copilot CLI | \`copilot --version\` | v1.0.55-5+ (used by the Copilot SDK) |
| Antigravity CLI (\`agy\`) | run \`agy\` to sign in | any version |

The **Settings → Server Status** panel shows which runtimes Arima detected on startup.

---

## Configuration

### Change port (default: 8585)

In \`src/main/resources/application.properties\`:
\`\`\`properties
server.port=9000
\`\`\`

Or at startup:
\`\`\`bash
java ... -jar arima-notebooks.jar --server.port=9000
\`\`\`

---

## Arima CLI (Windows)

\`barista.cmd\` is in the project root:

| Command | Description |
|---|---|
| \`barista\` | Start server, open browser |
| \`barista start --bg\` | Start in background (logs → \`arima.log\`) |
| \`barista stop\` | Stop the running server |
| \`barista status\` | Running state, PID, Java version |
| \`barista rebuild\` | Force clean build |
| \`barista logs\` | Tail \`arima.log\` |

---

## IDE Setup

### IntelliJ IDEA

Add these JVM args to your run configuration:
\`\`\`
--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED
\`\`\`

### VS Code

In \`.vscode/launch.json\`:
\`\`\`json
{
  "configurations": [{
    "type": "java",
    "name": "Arima Notebooks",
    "request": "launch",
    "mainClass": "com.barista.ArimaApplication",
    "vmArgs": "--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED"
  }]
}
\`\`\`

---

## Troubleshooting

### JShell not starting / InaccessibleObjectException
Missing JVM flags or JRE instead of JDK. Ensure you use all three \`--add-opens\` flags and that \`javac -version\` works.

### TypeScript cells fail — "Node.js too old for TypeScript cells"
Your Node version is below 22.6. Upgrade to Node 22.6+ (recommended: 24 LTS) from [nodejs.org](https://nodejs.org). Verify with \`node --version\`. JavaScript cells continue to work on older Node versions.

### TypeScript type errors are not reported (only runtime errors)
\`tsc\` is not on your PATH. Install with \`npm install -g typescript\`, then restart Arima. The **Settings → Server Status** panel should show *"+ tsc"*.

### C# cells fail — ".NET SDK not found"
Install the .NET SDK from [dot.net](https://dot.net). Verify: \`dotnet --version\`. Restart Arima.

### F# cells fail — ".NET SDK not found"
Same fix — F# Interactive (\`dotnet fsi\`) is bundled with the .NET SDK.

### NuGet packages not loading
Check **Packages → NuGet** tab that the package is listed. Ensure internet access for first-time package download. The .NET runtime caches packages in \`~/.nuget/packages\` after first use.

### C++ cells fail — "No C++ compiler found"
- **Windows:** Install [Visual Studio Build Tools](https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022) with the "Desktop development with C++" workload. Restart Arima.
- **macOS:** Run \`xcode-select --install\`. Verify: \`clang++ --version\`.
- **Linux:** Run \`sudo apt install g++\` (Debian/Ubuntu) or \`sudo dnf install gcc-c++\` (Fedora).

### AI not responding
- **Claude:** Run \`claude auth\` in a terminal, then restart Arima. Check Settings → Server Status: **Claude CLI** must show ✓ Found.
- **Copilot (SDK):** Authenticate the \`copilot\` CLI (v1.0.55-5+). Verify: \`copilot --version\`.
- **Antigravity (\`agy\`):** Run \`agy\` to sign in. Install: https://antigravity.google/docs/cli-install
- Switch providers in Settings → AI Provider if one CLI is not available.

### Port 8585 in use
Change the port in \`application.properties\` or pass \`--server.port=8586\` at startup.

### WebSocket connection failed
Check the browser console (F12). Ensure no proxy is intercepting WebSocket connections. Try restarting the server.

### Package download fails (Maven)
Verify internet access to \`repo1.maven.org\`. Check the coordinate format: \`groupId:artifactId:version\`.
`,

// ═══════════════════════════════════════════════════════
// TUTORIALS
// ═══════════════════════════════════════════════════════
tutorials: `
# Tutorial Notebooks

Arima ships with **built-in tutorial notebooks** covering all seven languages, from beginner to expert. Open them from the **Tutorials** button in the toolbar.

The browser has two views — **Select Notebook** (your own notebooks) and **Tutorials** — and the Tutorials view is organised into **per-language tabs** (JShell · Java · JavaScript · TypeScript · C# · F# · C++ · Agents & Skills). Use the **⛶ full-screen** toggle (or press **F**) for a roomier layout.

---

## ▶ Guided Tutorial Player

Every tutorial can be **played** instead of just read. Click the **▶ Guided** button on any tutorial card to open the player — a narrated, multimodal walkthrough that reads each step aloud and lets you ask questions as you go.

### Two modes (switch anytime)

| Mode | Behaviour |
|---|---|
| **▶ Autopilot** | Hands-free. Narrates each step and **auto-advances** to the next. Great for a first pass or listening while you follow along. |
| **✋ Interactive** | Self-paced. You drive with **Prev / Next**; each step is narrated when you land on it. |

Flip between the two with the toggle in the player header — the switch takes effect immediately from your current step.

### Ask questions — by voice or text

At any moment you can **interrupt and ask a question**. Type it in the ask box and press **Enter**, or click the **🎤 microphone** and speak. Your question — together with the current step as context — goes to your active AI provider (Claude / Copilot / Antigravity), and the answer appears in the Q&A panel **and is read back aloud**. In autopilot, asking pauses narration; once the answer finishes, the walkthrough resumes.

### Controls & shortcuts

| Control | Shortcut | Action |
|---|---|---|
| ⏸ / ▶ | **Space** | Pause / resume narration |
| Next ⟩ | **→** | Next step |
| ⟨ Prev | **←** | Previous step |
| 🎤 | — | Ask by voice |
| Close | **Esc** | Exit the player |

### Choosing a natural voice

Narration uses your browser's built-in voices. The player **auto-selects the most natural voice your system offers** (Windows 11's neural "Natural" voices or Chrome's Google voice), but you can pick any voice and set the speed (Slow / Natural / Brisk / Fast) from the controls in the player header — your choice is remembered. If your default sounds robotic, switch to a "Natural"/"Neural" or "Google" voice in the dropdown for a far more human read.

> **Audio & voice are browser-native** (the Web Speech API) — nothing is sent to any external audio service. Narration and voice input work best in Chrome. If your browser has no speech support, the player still works visually and Q&A still answers in text.

---

## JShell Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **JShell 101** | Beginner | Variables, types, operators, control flow, methods, collections |
| **JShell 201** | Intermediate | Lambdas, streams, records, Optional, functional patterns |
| **JShell 301** | Advanced | Concurrency, virtual threads, sealed types, pattern matching |
| **JShell 401** | Expert | Design patterns, JShell APIs, tooling |
| **JShell 501** | Expert | Data science with Tablesaw, XChart, Commons Math |

---

## Java Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **Java 101** | Beginner | Classes, OOP, collections, exceptions |
| **Java 201** | Intermediate | Generics, interfaces, streams, records |
| **Java 301** | Advanced | Concurrency, NIO, reflection |
| **Java 401** | Expert | Design patterns, testing patterns |
| **Java 501** | Expert | Functional Java, reactive patterns |
| **Java 601** | Expert | Data science with Tablesaw and XChart |

---

## JavaScript Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **JS 101** | Beginner | Variables, functions, arrays, objects |
| **JS 201** | Intermediate | Promises, async/await, closures |
| **JS 301** | Advanced | Functional patterns, generators |
| **JS 401** | Expert | Data analysis with mathjs |
| **JS 501** | Expert | D3.js visualization |

---

## TypeScript Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **TS 101** | Beginner | Types, type inference, interfaces, functions, arrays, tuples |
| **TS 201** | Intermediate | Generics, classes, modules, union & intersection types |
| **TS 301** | Advanced | Conditional types, mapped types, \`infer\`, template literal types |
| **TS 401** | Expert | Async patterns, decorators, ES2024 features, error handling |
| **TS 501** | Expert | Data analysis & stats with typed records and npm packages |

---

## C# Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **C# 101** | Beginner | Types, records, LINQ, collections, ArimaTable |
| **C# 201** | Intermediate | Pipeline dependencies, session anchors, shared data |

---

## C++ Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **C++ 101** | Beginner | Variables, arithmetic, strings, vectors, control flow |
| **C++ 201** | Intermediate | Classes, templates, STL algorithms, lambdas, modern C++17/20 |
| **C++ 301** | Advanced | Threading, smart pointers, filesystem, regex, chrono |

---

## F# Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **F# 101** | Beginner | Functions, pattern matching, lists, discriminated unions |
| **F# 201** | Intermediate | NuGet packages, pipelines, cross-cell data sharing |

---

## Python Tutorials

| Notebook | Level | Topics |
|---|---|---|
| **Python 101** | Beginner | Variables, types, f-strings, control flow, loops, functions |
| **Python 201** | Beginner | Lists, dicts, sets, comprehensions, classes, dataclasses, error handling |
| **Python 301** | Advanced | Standard library (datetime, collections, itertools, json, re), map/filter, generators, decorators |
| **Python 401** | Advanced | Networking — urllib, the \`requests\` library, a local socket server, concurrent fetches |
| **Python 501** | Advanced | Databases — SQLite (stdlib), parameterized queries, aggregation, transactions, PyPI drivers |
| **Python 601** | Data Science | Metrics & reporting with built-in rendering (tables, KPI cards, SVG charts), NumPy, pandas, matplotlib |

Install libraries for these from the **Packages → PyPI** tab.

---

## Example Notebooks

| Notebook | Language | Demonstrates |
|---|---|---|
| **C# Shared Utilities** | C# | Reusable types and helpers — import from any notebook |
| **C# Cross-Notebook Pipeline** | C# | Finance analysis importing shared types across notebooks |

---

## How to Open a Tutorial

1. Click the **Browse** button (folder icon) in the toolbar
2. Find the tutorial under its language group
3. Click to open — tutorials are read-only by default
4. Press **Run All** to execute everything, or step through with **Shift+Enter**
5. Experiment freely — edits are not saved back to the tutorial

---

## Copy Between Notebooks

| Action | How |
|---|---|
| Copy a cell | Click the **⧉ copy icon** on the cell header |
| Paste as new cell | Click **Paste** in toolbar or **Ctrl+Shift+V** |
| Duplicate a cell | Click the **duplicate icon** on the cell header |
| Copy to another notebook | Copy the cell, switch notebooks, then Paste |
`,

// ═══════════════════════════════════════════════════════
// PIPELINE / WORKFLOW REFERENCE
// ═══════════════════════════════════════════════════════
pipeline: `
# Cell Workflows & Orchestration

Arima introduces **named cell orchestration** — a system for naming cells, declaring dependencies between them, and building deterministic, reusable execution workflows.

Workflows work the same way in **all seven execution modes**: JShell, Java, JavaScript, TypeScript, C#, F#, and C++. They also extend **across notebooks**, enabling modular code libraries shared across your entire workspace.

---

## Why Notebooks Need Workflows

Traditional notebooks number cells by execution order (\`[3]\`). This creates hidden, fragile coupling: cell 5 silently depends on cell 2 having run, but nothing enforces or communicates this. When you share a notebook, re-order cells, or restart a session, things break in unpredictable ways.

**Arima solves this with explicit, named dependencies:**

| Feature | Traditional notebooks | Arima |
|---|---|---|
| Cell identity | Execution number (\`[3]\`) | Semantic name (\`loadData\`) |
| Dependencies | Implicit (position-based) | Explicit (\`//@ depends:\`) |
| Named workflows | None | Pipeline cells |
| Cycle detection | None | Built-in, with full error path |
| Minimum execution | "Run All Above" (everything) | Transitive closure only |
| Cross-notebook sharing | None | \`notebook:{id}/{anchor}\` refs |
| Language support | Single language | All 7 languages |

---

## 1. Naming Cells — Anchors

Any CODE or PIPELINE cell can have an **anchor name** — a stable identifier used to reference it.

**Two ways to set an anchor:**

**Click the badge** in the cell header (the faint \`+ anchor\` label on hover), type a name, press Enter.

**Or add an annotation** in the cell source:
\`\`\`
//@ anchor: loadData
//@ description: Load raw transaction records from source
\`\`\`

**Naming rules:** letters, digits, hyphens, underscores. No spaces. Must be unique in the notebook.

---

## 2. Declaring Dependencies

Add \`//@ depends:\` at the top of a cell to declare what must run before it:

\`\`\`
//@ anchor: summarize
//@ depends: loadData, normalize
//@ description: Aggregate by category
\`\`\`

Click **▶▶** (run-with-deps) and Arima:
1. Finds all declared dependencies (and their dependencies recursively)
2. Runs a cycle check — reports an error with the full cycle path if one exists
3. Topologically sorts the execution order
4. Runs each cell in order, updating status badges as it goes

**Dependency badge colours:**
- ⬜ **pending** — not yet run this session
- 🟡 **running** — currently executing
- 🟢 **ok** — last run succeeded
- 🔴 **error** — last run had errors
- 🟡 **stale** — source edited since last run

---

## 3. Pipeline Cells — Named Workflows

A **PIPELINE cell** defines a named, self-contained workflow that can be run with a single click.

\`\`\`
//@ pipeline: monthly-report
//@ description: End-to-end monthly spend analysis
//@ steps: loadData, normalize, summarize, visualize, report
//@ on-error: stop
\`\`\`

| Directive | Required | Description |
|---|---|---|
| \`//@ pipeline: name\` | Yes | Names this workflow (also its anchor) |
| \`//@ steps: a, b, c\` | Yes | Ordered step list |
| \`//@ description: text\` | No | Human-readable label in the cell header |
| \`//@ on-error: stop\|continue\` | No | Stop on first failure (default: stop) |

When you click **Run Pipeline**, Arima resolves the full dependency graph for all steps — even if a step itself has \`//@ depends:\` — and runs everything in the correct order.

---

## 4. Example — Multi-Cell Workflow (C#)

\`\`\`csharp
//@ anchor: loadData
//@ description: Load monthly transactions
var data = new[] {
    (Category: "Food",      Amount: 142.50m),
    (Category: "Transport", Amount:  89.00m),
    (Category: "Utilities", Amount: 115.00m),
};
Console.WriteLine($"Loaded {data.Length} records");
\`\`\`

\`\`\`csharp
//@ anchor: summarize
//@ depends: loadData
//@ description: Aggregate spend by category
var summary = data.GroupBy(r => r.Category)
                  .Select(g => new { Category = g.Key, Total = g.Sum(r => r.Amount) })
                  .OrderByDescending(x => x.Total)
                  .ToList();
ArimaTable(summary);
\`\`\`

\`\`\`
//@ pipeline: spendReport
//@ description: Monthly spend report
//@ steps: loadData, summarize
\`\`\`

Click **▶ Run Pipeline** on the pipeline cell to execute the entire workflow.

---

## 5. Cross-Notebook References

Cells in one notebook can depend on cells in **any other notebook**:

\`\`\`
//@ depends: notebook:{notebookId}/{anchorName}
\`\`\`

**Example:**
\`\`\`csharp
//@ anchor: analyzeSpend
//@ depends: notebook:csharp-shared-utils/cs_statistics
//@ description: Use shared Stats helper from another notebook

var amounts = transactions.Select(t => t.Amount);
Console.WriteLine($"Mean: {Stats.Mean(amounts):F2}");
\`\`\`

**How it works by language:**

| Language | Cross-notebook mechanism |
|---|---|
| JShell / Java | Foreign cell's source is executed in the current shared session |
| C# / F# | Arima builds the full transitive dependency chain of the foreign cell (annotation-stripped, in topological order), injects it with output suppressed, then runs the current cell |
| C++ | Foreign cell's source is prepended to the current cell before compilation (per-cell isolation; no shared state) |

This means C# and F# cells can share types, records, and classes across notebooks — the injected source is compiled together with the dependent cell.

**Cross-notebook output suppression:** When injecting foreign cell code, Arima wraps it with console output suppression so only the current cell's output is visible. Earlier cells in the dependency chain are re-executed silently to recreate their state.

---

## 6. Run Actions

Each CODE cell has three run actions:

| Button | Action |
|---|---|
| ▶ | Run this cell only |
| ▶▶ | Run this cell with all \`//@ depends:\` resolved first (transitive) |
| ⏭ | Run from the top of the notebook down to this cell (positional) |

**Toolbar:** Click **Validate** to check the entire notebook's dependency graph for unknown anchors and cycles.

---

## 7. Execution Rules

1. **Transitive resolution:** If A depends on B, and B depends on C, running A also runs C then B first
2. **Topological order:** Dependencies always run before the cells that need them (Kahn's algorithm)
3. **No skip:** Arima always re-runs the full dependency closure — state is always fresh, never stale
4. **Cycle detection:** DFS back-edge detection before any execution. Clear error: *"Cycle detected: A → B → A"*
5. **Unknown anchors:** Clear error if a \`//@ depends:\` names an anchor that doesn't exist in the notebook or the referenced cross-notebook

---

## 8. Full Annotation Reference

\`\`\`
//@ anchor: myCell          — give this cell a stable name
//@ depends: a, b, c        — run these anchors (and their deps) before this cell
//@ depends: notebook:id/anchor  — cross-notebook dependency
//@ pipeline: myWorkflow    — (PIPELINE cells) name this workflow
//@ steps: a, b, c          — (PIPELINE cells) ordered step list
//@ on-error: stop|continue — stop on first failure (default: stop)
//@ description: text       — human-readable label shown in UI
//@ namespace: com.mylib   — (JShell cross-notebook) wrap source in a class to prevent name collisions
\`\`\`

The same DSL works identically in JShell, Java, JavaScript, C#, and F# cells. In C#/F# the comment syntax is \`//\`, consistent with how those languages use comments. In JShell/Java you can also use \`//\`.
`,

// ═══════════════════════════════════════════════════════
// MCP & AGENTS
// ═══════════════════════════════════════════════════════
mcp: `
# MCP & Agent Integration

## What is MCP?

The **Model Context Protocol (MCP)** is an open standard that allows AI systems — Claude Desktop, Claude Code, GitHub Copilot, and custom agents — to discover and invoke tools provided by external servers. Think of it as a universal plugin system for AI: instead of an AI hallucinating code, it can actually execute it.

## Why Arima + MCP is Unique

Most MCP servers provide read-only data sources: search results, database queries, file contents. **Arima is fundamentally different** — it provides a live **multi-language computational environment**.

When an AI agent connects to Arima, it gains the ability to:

- **Write and execute code** across seven languages (JShell, Java, JavaScript, TypeScript, C#, F#, C++) and see real output
- **Create, read, and organise notebooks** — persistent, structured documents with cells, anchors, and dependencies
- **Trigger named workflows** (pipelines) that execute entire analysis chains in a single tool call
- **Build cumulative sessions** — load a module once, use it across many code executions
- **Generate and verify results** — run computations, inspect output, iterate until correct

This transforms AI from a code *suggester* into a code *executor* — an agent that can actually compute answers, build data pipelines, and verify its own work.

---

## MCP Endpoint

\`\`\`
GET  http://localhost:8585/api/mcp/sse       — SSE stream (MCP transport)
POST http://localhost:8585/api/mcp/messages  — JSON-RPC 2.0 messages
\`\`\`

Arima implements **JSON-RPC 2.0 over HTTP+SSE** (MCP spec 2024-11-05).

---

## Available Tools (8)

| Tool | Required Params | What it does |
|---|---|---|
| \`barista_execute_code\` | \`code\` | Execute code in a live session (any language via \`mode\`) |
| \`barista_list_notebooks\` | *(none)* | List all notebooks with metadata |
| \`barista_read_notebook\` | \`notebookId\` | Read all cells (source, anchors, output) |
| \`barista_run_pipeline\` | \`notebookId\`, \`cellId\` | Execute a full workflow with dependency resolution |
| \`barista_search_cells\` | \`query\` | Find cells by anchor name or source content |
| \`barista_load_module\` | \`notebookRef\` | Load \`notebookId/anchor\` into a session |
| \`barista_create_notebook\` | \`name\` | Create a notebook (optionally with cells pre-populated) |
| \`barista_append_cell\` | \`notebookId\`, \`source\` | Append a cell and optionally execute it immediately |

---

## 1. Connect Claude Desktop

Edit your Claude Desktop config:
- **Windows:** \`%APPDATA%\\Claude\\claude_desktop_config.json\`
- **macOS:** \`~/Library/Application Support/Claude/claude_desktop_config.json\`

\`\`\`json
{
  "mcpServers": {
    "arima-notebooks": {
      "url": "http://localhost:8585/api/mcp/sse"
    }
  }
}
\`\`\`

Restart Claude Desktop. You can now say:
> *"Create a Arima notebook called 'Sales Analysis' and compute the monthly totals for this CSV data…"*
> *"Run the ETL pipeline in my data-processing notebook and show the results"*
> *"Search my notebooks for cells that compute fibonacci"*

---

## 2. Connect Claude Code (CLI)

Add to \`.claude/settings.json\` (project) or \`~/.claude/settings.json\` (global):

\`\`\`json
{
  "mcpServers": {
    "barista": {
      "url": "http://localhost:8585/api/mcp/sse"
    }
  }
}
\`\`\`

Then in a Claude Code session:
\`\`\`bash
claude "List all Arima notebooks and tell me which have pipeline cells"
claude "Create a C# notebook that computes descriptive statistics for this data and runs it"
claude "Load the math-helpers module from my utilities notebook and use it to compute the moving average"
\`\`\`

---

## 3. Use Cases

### Autonomous Data Analysis
An agent reads a dataset, writes JShell or C# code to analyse it, executes the code, reads the output, refines the approach, and produces a summary — all without human intervention.

### Code Generation + Verification
An agent generates a solution, creates it as a Arima cell, executes it, inspects the output, and confirms correctness before reporting success.

### Notebook-as-Module Library
Build reusable analytical modules in notebooks with named anchors. An agent can discover these via \`barista_search_cells\` and load them via \`barista_load_module\` — composing complex analyses from pre-verified building blocks.

### CI/CD Integration
A CI agent can execute a pipeline notebook after each deployment to verify system behaviour, comparing current output against expected results.

### Multi-Language Agents
An agent working on a polyglot project can execute Java, C#, and JavaScript code in the same session context — switching languages per task without losing notebook state.

---

## 4. Execute Code via MCP

\`\`\`bash
curl -X POST http://localhost:8585/api/mcp/messages \\
  -H "Content-Type: application/json" \\
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": {
      "name": "barista_execute_code",
      "arguments": {
        "code": "var x = List.of(1,2,3,4,5); x.stream().mapToInt(i->i).sum()",
        "session": "agent-session",
        "mode": "jshell"
      }
    }
  }'
\`\`\`

---

## 5. Create a Notebook via MCP

\`\`\`bash
curl -X POST http://localhost:8585/api/mcp/messages \\
  -H "Content-Type: application/json" \\
  -d '{
    "jsonrpc": "2.0", "id": 2, "method": "tools/call",
    "params": {
      "name": "barista_create_notebook",
      "arguments": {
        "name": "Agent Analysis",
        "description": "Generated by Claude",
        "cells": [
          {"type": "MARKDOWN", "source": "# Agent-Generated Analysis"},
          {"type": "CODE", "source": "System.out.println(\\"Hello from AI!\\");", "anchor": "init"}
        ]
      }
    }
  }'
\`\`\`

---

## 6. Expose Cells as Reusable Modules

Any cell with \`//@ anchor: name\` is discoverable and loadable by agents:

1. **Annotate your cell:**
\`\`\`java
//@ anchor: fibonacci
//@ description: Compute fibonacci numbers up to n
int fib(int n) { return n <= 1 ? n : fib(n-1) + fib(n-2); }
\`\`\`

2. **Agent searches for it:**
\`\`\`json
{"method": "tools/call", "params": {"name": "barista_search_cells", "arguments": {"query": "fibonacci"}}}
\`\`\`

3. **Agent loads it into its session:**
\`\`\`json
{"method": "tools/call", "params": {"name": "barista_load_module", "arguments": {"notebookRef": "my-notebook/fibonacci", "session": "agent-1"}}}
\`\`\`

4. **Agent uses it:**
\`\`\`json
{"method": "tools/call", "params": {"name": "barista_execute_code", "arguments": {"code": "System.out.println(fib(10));", "session": "agent-1"}}}
\`\`\`

---

## 7. Quick Test

\`\`\`bash
# List all available tools
curl -s -X POST http://localhost:8585/api/mcp/messages \\
  -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \\
  | python -m json.tool
\`\`\`
`,

// ═══════════════════════════════════════════════════════
// API REFERENCE
// ═══════════════════════════════════════════════════════
api: `
# REST API Reference

## Why Arima Has an API

Arima was designed **API-first** from the beginning. The notebook UI is a client of the API — every action the browser performs is an HTTP call that any other system can make too.

This means Arima is not just a notebook tool for humans. It is a **programmable computational environment** that can be:

- **Embedded** in CI/CD pipelines — execute a data verification notebook after every deployment
- **Driven by AI agents** — generate, run, and inspect notebook cells without the browser
- **Integrated with other tools** — push results to dashboards, trigger workflows from external events
- **Automated** — run recurring analysis notebooks on a schedule
- **Tested** — validate notebook behaviour from automated test suites

Every execution engine (JShell, Java, JavaScript, TypeScript, C#, F#, C++), every pipeline workflow, every package manager, and every AI endpoint is accessible via the same REST API the browser uses.

---

**Base URL:** \`http://localhost:8585/api\`

All requests and responses use JSON. No authentication required (local use only).

---

## Notebooks

| Method | Path | Description |
|---|---|---|
| GET | /notebooks | List all notebooks (metadata only) |
| POST | /notebooks | Create notebook — body: \`{name}\` |
| GET | /notebooks/{id} | Get full notebook including all cells |
| PUT | /notebooks/{id} | Save/update a notebook |
| DELETE | /notebooks/{id} | Delete a notebook |
| GET | /notebooks/tutorials | List all tutorial and example notebooks |
| GET | /notebooks/tutorials/{id} | Get a tutorial or example notebook |

**Create response:**
\`\`\`json
{ "id": "abc123", "name": "My Notebook", "cells": [], "created": "...", "modified": "..." }
\`\`\`

---

## Code Execution

| Method | Path | Description |
|---|---|---|
| POST | /shell/execute | Execute code in any language |
| POST | /shell/{sessionId}/restart | Restart a session (clears all state) |
| DELETE | /shell/{sessionId} | Close a session |
| GET | /shell/sessions | List active session IDs |
| GET | /shell/{sessionId}/info | Session info (classpath, execution count) |
| POST | /shell/complete | JShell tab-completion suggestions |

**Execute request:**
\`\`\`json
{
  "sessionId": "nb-my-notebook-id",
  "code": "System.out.println(42);",
  "cellId": "cell-123",
  "mode": "jshell"
}
\`\`\`

**mode values:** \`jshell\` · \`java\` · \`nodejs\` · \`typescript\` · \`csharp\` · \`fsharp\` · \`cpp\`

**Execute response:**
\`\`\`json
{
  "output": "42\\n",
  "error": "",
  "status": "VALID",
  "success": true,
  "executionTimeMs": 12,
  "executionCount": 1
}
\`\`\`

---

## Workflow Execution (Orchestration)

| Method | Path | Description |
|---|---|---|
| POST | /shell/execute-pipeline | Run a PIPELINE cell (resolves all deps) |
| POST | /shell/execute-with-deps | Run a cell with its \`//@ depends:\` resolved |
| POST | /shell/run-to-here | Run all cells from top to a target cell |
| GET | /shell/validate-graph/{notebookId} | Validate the full dependency graph |

**Execute-pipeline request:**
\`\`\`json
{
  "notebookId": "abc123",
  "pipelineCellId": "cell-456",
  "sessionId": "nb-abc123"
}
\`\`\`

**Validate-graph response:**
\`\`\`json
{
  "valid": false,
  "errors": [
    "Unknown anchor 'xyz' referenced by cell #analysis",
    "Cycle detected: setup → transform → setup"
  ]
}
\`\`\`

---

## Maven Packages (Java / JShell)

| Method | Path | Description |
|---|---|---|
| GET | /packages | List installed Maven packages |
| POST | /packages/install | Install — body: \`{coordinate}\` |
| DELETE | /packages/{g}/{a}/{v} | Remove by coordinate |
| GET | /packages/search?q= | Search Maven Central |

Coordinate format: \`groupId:artifactId:version\` — e.g. \`com.google.code.gson:gson:2.10.1\`

---

## NuGet Packages (C# / F#)

| Method | Path | Description |
|---|---|---|
| GET | /nuget | List installed NuGet packages |
| POST | /nuget/install | Install — body: \`{packageId, version}\` |
| DELETE | /nuget/{packageId} | Remove by package ID |

\`\`\`json
// Install request
{ "packageId": "Newtonsoft.Json", "version": "13.0.3" }
\`\`\`

---

## AI Assistant

Arima routes all AI calls through the active provider (Claude, Copilot, or Antigravity) configured in Settings.

| Method | Path | Description |
|---|---|---|
| GET | /llm/provider | Current provider info: \`{provider, label, model, available}\` |
| POST | /llm/chat | Chat with the active AI provider |
| POST | /llm/generate | Generate a full notebook from a prompt |
| POST | /llm/explain | Explain a code snippet |
| POST | /llm/fix | Suggest a fix for an error |

\`\`\`json
// Chat request
{ "message": "How do I use C++ smart pointers?", "history": [] }

// Provider response
{ "provider": "claude", "label": "Claude", "model": "claude-sonnet-4-6", "available": true }
\`\`\`

---

## Settings

| Method | Path | Description |
|---|---|---|
| GET | /settings | Get current settings |
| PUT | /settings | Update settings |
| GET | /settings/status | Server status (runtimes, versions) |

The status endpoint reports which runtimes are detected: \`javaAvailable\`, \`nodeAvailable\`, \`typescriptAvailable\`, \`tscAvailable\`, \`typescriptDetail\`, \`dotnetAvailable\`, \`cppAvailable\`, \`cppCompilerDetail\`, \`claudeCliAvailable\`, \`copilotCliAvailable\`, \`geminiCliAvailable\`.

---

## WebSocket (Real-time Output)

Connect to \`/ws\` using STOMP over SockJS.

- Subscribe to \`/topic/shell/{sessionId}\` — receive \`ExecutionResult\` JSON after each execution
- Send to \`/app/shell/{sessionId}\` — body: \`{code, cellId, mode}\` — executes and broadcasts to all subscribers

The WebSocket path is used by the browser to stream cell output in real time. External clients can subscribe to the same topics to monitor execution in progress.

---

## Cross-Notebook Reference in API

When calling \`execute-with-deps\` or \`execute-pipeline\` on a cell that has \`//@ depends: notebook:{id}/{anchor}\`, Arima automatically:

1. Loads the referenced notebook from disk
2. Resolves the anchor's full dependency chain
3. For JShell/Java: executes the foreign source in the current session
4. For C#/F#: builds the expanded source and injects it with output suppressed

No special API parameter is needed — the cross-notebook resolution is automatic.
`,

// ═══════════════════════════════════════════════════════
// ARCHITECTURE
// ═══════════════════════════════════════════════════════
arch: `
# Architecture

Arima is a **single-server application** — one Spring Boot process serves the static frontend and provides all APIs. There is no separate frontend build, no microservices, no message queue. Everything communicates over HTTP REST and STOMP WebSocket. Seven execution engines (JShell, Java, JavaScript, TypeScript, C#, F#, C++) plug into a single \`ShellController\` route, all reporting back through one unified \`ExecutionResult\`.

---

## System Overview

\`\`\`mermaid
graph LR
    subgraph Browser["Browser (SPA — no build step)"]
        UI[HTML/CSS/JS]
    end

    subgraph Server["Arima Server — Spring Boot 3.2 / Java 21"]
        Controllers["REST Controllers\n+ WebSocket"]
        Services["Execution Services"]
        Storage["File Storage (.vnb)"]
    end

    subgraph Engines["Execution Engines (7 languages)"]
        JShell["JShell (in-process)"]
        Java["javac + subprocess"]
        Node["node subprocess (JS)"]
        TS["node --experimental-strip-types (TS)\n+ optional tsc --noEmit"]
        DotNet["dotnet run / fsi"]
        CPP["cl.exe / g++ / clang++"]
    end

    subgraph AI["AI Providers (CLI subprocess)"]
        Claude["claude CLI"]
        Copilot["copilot CLI (via Copilot SDK)"]
        Gemini["agy CLI (Antigravity)"]
    end

    UI -- "HTTP REST\n+ STOMP/SockJS" --> Controllers
    Controllers --> Services
    Services --> Storage
    Services --> JShell
    Services --> Java
    Services --> Node
    Services --> TS
    Services --> DotNet
    Services --> CPP
    Services --> Claude
    Services --> Copilot
    Services --> Gemini
\`\`\`

---

## Backend Layer Map

\`\`\`mermaid
graph TD
    HTTP["HTTP Request"] --> Controller["Controller Layer\nNotebookController · ShellController\nPackageController · NuGetController\nLLMController · SettingsController\nNpmPackageController · McpController"]
    WS["WebSocket / STOMP"] --> Controller

    Controller --> Orchestration["OrchestrationService\nDep graph · Toposort · Cycle detection\nCross-notebook resolution"]
    Controller --> Shell["JShellManager\nShellSession (one per notebook)"]
    Controller --> Compiler["JavaCompilerService\njavax.tools + subprocess"]
    Controller --> NodeSvc["NodeJsExecutionService\nnode -e subprocess"]
    Controller --> TsSvc["TypeScriptExecutionService\nnode --experimental-strip-types\n+ optional tsc --noEmit"]
    Controller --> DotNetSvc["DotNetExecutionService\ndotnet run (C#) · dotnet fsi (F#)\nSession anchor cache"]
    Controller --> CppSvc["CppExecutionService\ncl.exe / g++ / clang++\nAuto-detects MSVC · GCC · Clang"]
    Controller --> NbSvc["NotebookService\n.vnb CRUD · tutorial registry"]
    Controller --> PkgSvc["PackageService\nMaven Central download\nJShell classpath injection"]
    Controller --> NuGetSvc["NuGetService\nNuGet package list management"]
    Controller --> AIDelegate["AIDelegate interface\nRoutes to active provider"]
    AIDelegate --> ClaudeSvc["ClaudeService\nclaude CLI subprocess"]
    AIDelegate --> CopilotSvc["CopilotCliService\nGitHub Copilot SDK"]
    AIDelegate --> GeminiSvc["GeminiService\nAntigravity agy CLI"]
    Controller --> SettingsSvc["SettingsService\ndata/settings.json"]

    Orchestration --> Shell
    Orchestration --> Compiler
    Orchestration --> NodeSvc
    Orchestration --> TsSvc
    Orchestration --> DotNetSvc
    Orchestration --> CppSvc
\`\`\`

---

## Cell Execution — Per Language

\`\`\`mermaid
graph TD
    Cell["Cell with mode"] --> R{Route by mode}

    R -- "jshell" --> JS["JShellManager.execute()\nShared in-process JShell session\nVariables persist across cells"]
    R -- "java" --> JC["JavaCompilerService\nCompile via javax.tools\nRun as subprocess\nPer-cell isolation"]
    R -- "nodejs" --> ND["NodeJsExecutionService\nnode -e subprocess\nrequire() from data/npm-modules"]
    R -- "typescript" --> TS2["TypeScriptExecutionService\nOptional tsc --noEmit type-check\nnode --experimental-strip-types\nShared NODE_PATH with JS cells"]
    R -- "csharp" --> CS["DotNetExecutionService.executeCSharp()\nGenerate temp .csproj\ndotnet run\nInject dep context if //@ depends:"]
    R -- "fsharp" --> FS["DotNetExecutionService.executeFSharp()\nWrite .fsx script\ndotnet fsi --exec\nExtract #r nuget: to top of file"]
    R -- "cpp" --> CPP["CppExecutionService.execute()\nDetect: MSVC / GCC / Clang\nWrite temp .cpp file\nCompile + run subprocess\nPer-cell isolation"]

    JS --> Result["ExecutionResult"]
    JC --> Result
    ND --> Result
    TS2 --> Result
    CS --> Result
    FS --> Result
    CPP --> Result

    Result --> WS["Broadcast via\nSTOMP WebSocket\n/topic/shell/{sessionId}"]
\`\`\`

---

## Pipeline / Workflow Execution

\`\`\`mermaid
sequenceDiagram
    participant B as Browser
    participant SC as ShellController
    participant OS as OrchestrationService
    participant NS as NotebookService
    participant EE as ExecutionEngine

    B->>SC: POST /shell/execute-pipeline
    SC->>NS: getNotebook(notebookId)
    NS-->>SC: Notebook with all cells
    SC->>OS: executePipeline(notebook, pipelineCell, sessionId)
    Note over OS: Parse annotations and build anchor map
    Note over OS: DFS cycle detection + Kahn topological sort
    loop For each step in execution order
        OS->>EE: execute(source, mode, sessionId)
        EE-->>OS: ExecutionResult
        OS-->>B: Broadcast result via WebSocket
    end
    OS-->>SC: PipelineResult
    SC-->>B: HTTP 200 PipelineResult
\`\`\`

---

## Cross-Notebook Reference Resolution

\`\`\`mermaid
graph TD
    Dep["Cell with\n//@ depends: notebook:shared-utils/helper"]
    -->
    Load["OrchestrationService\nloadCrossNotebookModule()"]

    Load --> Mode{Foreign cell mode?}

    Mode -- "jshell/java" --> ExecForeign["Execute foreign cell source\nin current JShell session\n(shared variable space)"]

    Mode -- "csharp/fsharp" --> Expand["buildExpandedDotNetSource()\nCollect all transitive deps of\nforeign cell in topological order\nAnnotation-strip, concatenate"]

    Expand --> Cache["DotNetExecutionService\ncacheAnchorSource(sessionId,\n'notebook:shared-utils/helper',\nexpandedSource)"]

    Cache --> Inject["When dependent cell runs:\nresolveTransitiveDeps() finds cached source\nInject with output suppression:\nConsole.SetOut(TextWriter.Null)\n[ancestor code]\nConsole.SetOut(original)"]

    ExecForeign --> Ready["Types/variables available\nin session"]
    Inject --> Ready
\`\`\`

---

## Notebook Data Model

\`\`\`mermaid
erDiagram
    Notebook {
        string id
        string name
        string description
        datetime created
        datetime modified
        map metadata
    }
    Cell {
        string id
        CellType type
        string mode
        string source
        string anchor
        list dependsOn
        list pipelineSteps
        string output
        string error
        boolean executed
        int executionCount
    }
    Notebook ||--o{ Cell : contains
\`\`\`

**Cell types:** \`CODE\` · \`MARKDOWN\` · \`PIPELINE\`

**Cell modes:** \`jshell\` · \`java\` · \`nodejs\` · \`typescript\` · \`csharp\` · \`fsharp\` · \`cpp\`

**Storage format:** Pretty-printed JSON in \`notebooks/{userId}/{id}.vnb\`

---

## Frontend Module Structure

\`\`\`mermaid
graph TD
    HTML["index.html\nSingle-page app shell"] --> AppJS["app.js\nArima global module\nREST helpers · STOMP client\nTab navigation · Status bar"]

    AppJS --> NB["notebook.js\nNotebook editor · Cell rendering\nExecution · Cross-notebook picker\nLanguage conversion banner"]
    AppJS --> Console["console-tab.js\nInteractive REPL\nJShell · Java · JS modes\nTab completion · History"]
    AppJS --> Orch["orchestration.js\nClient-side dep graph\nAnchor parsing · Badge updates\nStaleness detection"]
    AppJS --> Pkg["packages.js\nMaven package manager UI\nC++ built-in headers panel"]
    AppJS --> NPM["npm-packages.js\nnpm package manager UI"]
    AppJS --> NuGet["nuget.js\nNuGet package manager UI"]
    AppJS --> AI["ai-assistant.js\nArima Agentic Assistant (AAA)\nClaude · Copilot · Antigravity\nNotebook generation"]
    AppJS --> Settings["settings.js\nSettings form · Server status\nAI provider switcher"]
    AppJS --> Docs["docs.js\nThis documentation overlay"]
    AppJS --> SLC["server-lifecycle.js\nHealth poll · Reconnect overlay\nShutdown/restart UI"]
\`\`\`

All frontend modules use the **IIFE pattern** — no build step, no bundler. Change a JS file and refresh the browser.

---

## Data Flow — Real-time Output

\`\`\`mermaid
sequenceDiagram
    participant B as Browser
    participant WS as STOMP Broker
    participant JM as JShellManager
    participant SS as ShellSession

    B->>WS: SUBSCRIBE /topic/shell/nb-abc123
    B->>WS: SEND /app/shell/nb-abc123
    Note over B,WS: payload: code, cellId, mode
    WS->>JM: execute(sessionId, code, cellId)
    JM->>SS: execute(code, cellId)
    SS->>SS: jshell.eval(code)
    Note over SS: Capture stdout via proxy OutputStream
    SS-->>JM: ExecutionResult
    JM->>WS: broadcast to /topic/shell/nb-abc123
    WS-->>B: ExecutionResult JSON
    Note over B: Update cell output and dep badges
\`\`\`

---

## Session Anchor Cache (C# / F#)

\`\`\`mermaid
graph LR
    Exec["DotNetExecutionService\nexecuteCSharp / executeFSharp"]
    --> Parse["Parse //@ anchor:\nand //@ depends:"]
    --> Check{All deps\ncached?}
    Check -- No --> Error["Return error:\n'Run anchor X first'"]
    Check -- Yes --> Resolve["resolveTransitiveDeps()\nPost-order DFS through dep chain"]
    --> Inject["Build combined program:\n[suppress] dep1 source\n[suppress] dep2 source\n[active] current cell source"]
    --> Run["dotnet run / dotnet fsi"]
    --> Success{Success?}
    Success -- Yes --> Store["cacheAnchorSource(sessionId, anchor, source)\nStore for future dependents"]
    Success -- No --> Err2["Return error result"]
\`\`\`

---

## Storage Layout

\`\`\`
barista/
├── notebooks/
│   ├── tutorials/          Built-in tutorials (read-only, checked in)
│   ├── examples/           Example notebooks (read-only, checked in)
│   └── {userId}/           User notebooks (gitignored)
│       └── *.vnb
└── data/
    ├── settings.json       App settings (gitignored — may contain keys)
    ├── packages.json       Installed Maven packages
    ├── nuget-packages.json Installed NuGet packages
    ├── packages/           Downloaded JARs
    │   └── *.jar
    └── npm-modules/        npm packages
        └── node_modules/
\`\`\`

---

## Security Model

- **Local only:** Binds to \`127.0.0.1\`. No authentication — do not expose to a network.
- **No API keys in transit:** Claude calls go through the local \`claude\` CLI subprocess.
- **Code execution:** All runtimes run with the same OS-user permissions as the Arima server process.
- **CORS:** Configured for localhost only.
`,

// ═══════════════════════════════════════════════════════
// DEVELOPER GUIDE
// ═══════════════════════════════════════════════════════
dev: `
# Developer Guide

Arima is an open-source project. This guide covers the architecture for developers who want to extend it, contribute to it, or build on top of it.

---

## 1. Development Environment

**Requirements:**
- JDK 17–21 (full JDK, not JRE — needed for JShell and \`javax.tools\`)
- Maven 3.8+
- Node.js 18+ (for JavaScript cell testing)
- .NET SDK 6+ (for C#/F# cell testing)
- IntelliJ IDEA or VS Code

**Start in dev mode:**
\`\`\`bash
mvn spring-boot:run
\`\`\`

The frontend has no build step — change a JS/CSS file and refresh the browser. Backend changes require a server restart (or Spring Boot DevTools hot-reload).

**JVM flags** (applied automatically via \`pom.xml\`):
\`\`\`
--add-opens=jdk.jshell/jdk.jshell=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-exports=jdk.jshell/jdk.jshell=ALL-UNNAMED
\`\`\`

---

## 2. Key Services

| Service | Purpose |
|---|---|
| \`JShellManager\` | Session map; creates, manages, and broadcasts from JShell sessions |
| \`ShellSession\` | Wraps one JShell instance; synchronized execute(); stdout capture via proxy OutputStream |
| \`JavaCompilerService\` | javax.tools compile → temp dir → subprocess run; auto-wraps bare statements |
| \`NodeJsExecutionService\` | node subprocess; require() resolves from data/npm-modules/ |
| \`TypeScriptExecutionService\` | node \`--experimental-strip-types\` subprocess; optional \`tsc --noEmit\` type-check; shares NODE_PATH with JS |
| \`DotNetExecutionService\` | dotnet run (C#) + dotnet fsi (F#); session anchor cache; dep injection |
| \`CppExecutionService\` | Auto-detects MSVC/GCC/Clang; writes temp .cpp; compiles + runs; exposes isAvailable() / getCompilerDetail() |
| \`OrchestrationService\` | Anchor parsing; dep graph; toposort (Kahn's); cycle detection (DFS); cross-notebook resolution |
| \`NotebookService\` | .vnb CRUD; tutorial + example registry (scans notebooks/tutorials/ and notebooks/examples/) |
| \`PackageService\` | Maven Central HTTP download; JShell classpath injection |
| \`NuGetService\` | NuGet package list persistence; per-cell injection for C#/F# |
| \`ClaudeService\` | claude CLI subprocess; streams stdout back to caller |
| \`CopilotCliService\` | GitHub Copilot SDK (drives local copilot CLI); implements AIDelegate |
| \`GeminiService\` | Antigravity \`agy\` CLI subprocess; implements AIDelegate |
| \`SettingsService\` | data/settings.json load/save |

---

## 3. Adding a New REST Endpoint

1. **Choose the right controller** in \`src/main/java/com/barista/controller/\`
2. **Add the method:**
\`\`\`java
@GetMapping("/notebooks/{id}/stats")
public ResponseEntity<Map<String, Object>> getStats(@PathVariable String id) {
    return ResponseEntity.ok(Map.of("cellCount", notebookService.getNotebook(id, userId).getCells().size()));
}
\`\`\`
3. Keep controllers thin — delegate to services
4. Update **API Reference** in this docs overlay and \`docs/API.md\`

---

## 4. Adding a New Execution Language

Adding a language requires changes in five places:

1. **Create a new service** e.g. \`RubyExecutionService\` — implement \`execute(sessionId, cellId, code)\` returning \`ExecutionResult\`

2. **Route in \`ShellController\`:**
\`\`\`java
case "ruby" -> rubyExecutionService.execute(sessionId, req.getCellId(), req.getCode());
\`\`\`

3. **Route in \`OrchestrationService.executeSingleCell()\`:**
\`\`\`java
} else if ("ruby".equals(mode)) {
    return rubyExecutionService.execute(sessionId, cell.getId(), cell.getSource());
}
\`\`\`

4. **Add the mode to \`notebook.js\` mode cycle:**
\`\`\`js
const MODE_CYCLE = ['jshell','java','nodejs','typescript','csharp','fsharp','cpp','ruby'];
const MODE_LABELS = { ..., ruby: 'Ruby' };
\`\`\`

5. **Add CSS badge colour in \`arima.css\`:**
\`\`\`css
.mode-badge.ruby { background: rgba(204,52,45,.15); color: #cc342d; }
\`\`\`

---

## 5. Adding a New Cell Type

1. **Add to \`CellType.java\`:**
\`\`\`java
public enum CellType { CODE, MARKDOWN, PIPELINE, YOUR_TYPE }
\`\`\`

2. **Add fields to \`Cell.java\`** if needed (Jackson serialises all public getters automatically)

3. **Render in \`notebook.js\` \`renderCell()\`:**
\`\`\`js
if (cell.type === 'YOUR_TYPE') buildYourCell(cell, div, bodyWrap);
\`\`\`

4. **Add CSS in \`arima.css\`:**
\`\`\`css
.cell.type-yourtype::before { background: var(--purple); }
\`\`\`

5. **Add toolbar button in \`index.html\`** and wire it in \`notebook.js bindToolbar()\`

---

## 6. The Dependency / Orchestration System

Two implementations run in parallel — server and browser:

**Server: \`OrchestrationService.java\`** (authoritative):
- \`parseAnnotations(source)\` — extracts anchor, dependsOn, pipelineSteps from \`//@ lines\`
- \`buildAnchorMap(notebook)\` — maps anchor names to cells
- \`expandClosure(anchors, map)\` — transitive dependency set
- \`topologicalSort(closure, adj)\` — Kahn's algorithm
- \`detectCycle(adj)\` — DFS 3-color marking, returns cycle path
- \`loadCrossNotebookModule()\` — resolves \`notebook:id/anchor\` refs, routes by cell mode

**Browser: \`orchestration.js\`** (instant UI feedback):
- Same parsing and graph algorithms for live dependency badge updates
- \`depStatus\` map: anchor → {status, count, timestamp}
- \`markOk()\`, \`markError()\`, \`markStale()\`, \`refreshBadges()\`

**Adding a new \`//@ directive\`:**
1. Parse it in both \`OrchestrationService.java parseAnnotations()\` and \`orchestration.js parseAnnotations()\`
2. Add the field to \`Cell.java\` if it needs to be persisted
3. Handle it in the execution logic on the server side

---

## 7. C# / F# Execution Deep Dive

Both languages use \`DotNetExecutionService\`. The key pattern for pipeline dependency injection:

\`\`\`
executeCSharp(sessionId, cellId, source):
  1. Parse //@ anchor and //@ depends from source
  2. Look up each dep in sessionAnchorSources[sessionId]
  3. resolveTransitiveDeps(): post-order DFS through the dep chain
  4. For each ancestor, append:
       Console.SetOut(TextWriter.Null);
       [ancestor source, annotation-stripped]
       Console.SetOut(original);
  5. Append current cell source (output active)
  6. Generate temp .csproj with NuGet refs + usings preamble
  7. Run: dotnet run --project <tempDir> -v q
  8. Adjust error line numbers (subtract preamble line count)
  9. On success: store source in sessionAnchorSources[sessionId][anchor]
\`\`\`

The F# path is similar but uses \`dotnet fsi --exec\` and extracts \`#r "nuget:"\` directives to the top of the script.

---

## 8. Frontend Module Pattern

All JS files use the IIFE pattern — no bundler, no npm for frontend:

\`\`\`js
const MyModule = (() => {
    let privateVar = null;

    function privateHelper() { /* ... */ }

    function publicMethod() { /* ... */ }

    return { publicMethod };
})();
\`\`\`

**Global modules:**
- \`Arima\` — root module; \`Arima.api(method, path, body)\`, \`Arima.state\`, \`Arima.setStatus()\`
- \`NotebookEditor\` — called from HTML onclick attributes
- \`AIAssistant\` — context injection from notebook.js
- \`Orchestration\` — dep graph operations called from notebook.js
- \`DocsPanel\` — docs overlay (this file)

---

## 9. JShell Execution Deep Dive

\`\`\`
ShellSession.execute(code, cellId):
  1. captureBuffer.reset()     — clear stdout capture buffer
  2. jshell.eval(code)         — run in JShell remote subprocess
  3. proxyStream.flush()       — flush captured output
  4. Process SnippetEvents:
     - event.exception()       → runtime error
     - event.status() REJECTED → compile error (from Diag list)
     - event.value()           → return value
  5. Build ExecutionResult
  6. JShellManager broadcasts via WebSocket /topic/shell/{sessionId}
\`\`\`

**Key insight — output capture:** JShell runs code in a remote subprocess. \`System.setOut()\` in the main JVM doesn't work. The correct method (used here) is registering a custom \`OutputStream\` with \`JShell.builder().out()\`, which routes all subprocess stdout through our capture buffer.

---

## 10. Contributing to Arima

Arima is open source. To contribute:

1. **Read \`CONTRIBUTING.md\`** — coding standards, commit format, PR process
2. **Read \`MAINTAINER.md\`** — governance model (Founding Maintainer + Co-Maintainers)
3. **Open an issue or discussion first** for anything beyond a small bug fix
4. **Follow the coding rules:**
   - No Lombok (build compatibility)
   - No frontend build step (no webpack, no TypeScript, no npm for frontend)
   - Controllers are thin; business logic is in services
   - CSS uses \`var(--barista-*)\` custom properties — no hard-coded colours

**Contribution checklist before submitting a PR:**
- [ ] Java: no Lombok; explicit getters/setters; Builder inner class
- [ ] JS: IIFE module pattern; no npm for frontend
- [ ] New endpoints documented in API Reference
- [ ] New cell types/modes documented in Usage Guide
- [ ] New \`//@ directives\` implemented in BOTH \`OrchestrationService.java\` AND \`orchestration.js\`
- [ ] \`CHANGELOG.md\` entry under the current version section
- [ ] Build passes: \`mvn clean package -DskipTests\`
- [ ] Manual smoke test: server starts, UI opens, new feature works

---

## 11. Key Files Reference

| File | Purpose |
|---|---|
| \`ArimaApplication.java\` | Spring Boot entry point |
| \`shell/ShellSession.java\` | JShell wrapper, output capture |
| \`shell/JShellManager.java\` | Session map, WebSocket broadcast |
| \`service/JavaCompilerService.java\` | Full Java compile + run |
| \`service/NodeJsExecutionService.java\` | Node.js subprocess execution (JS) |
| \`service/TypeScriptExecutionService.java\` | TypeScript execution via Node type-stripping + optional tsc |
| \`service/DotNetExecutionService.java\` | C# and F# execution + anchor cache |
| \`service/OrchestrationService.java\` | Dep graph, toposort, pipeline execution, cross-notebook |
| \`service/NotebookService.java\` | .vnb CRUD, tutorial + example registry |
| \`service/PackageService.java\` | Maven Central integration |
| \`service/NuGetService.java\` | NuGet package management |
| \`service/CppExecutionService.java\` | C++ compilation + execution; compiler auto-detection |
| \`service/ClaudeService.java\` | Claude CLI integration |
| \`service/CopilotCliService.java\` | GitHub Copilot SDK integration |
| \`service/GeminiService.java\` | Antigravity CLI (\`agy\`) integration |
| \`static/index.html\` | Single-page app entry point |
| \`static/js/orchestration.js\` | Client-side dep graph + badges |
| \`static/js/notebook.js\` | Main notebook editor |
| \`static/js/docs.js\` | This in-app documentation |
| \`static/css/arima.css\` | All styles (dark/light themes, custom properties) |
| \`application.properties\` | Server config (port 8585, notebook/data directories) |
`
    };  // end DOCS

    let currentDoc = 'usage';

    function init() {
        document.getElementById('btn-docs-close')?.addEventListener('click', hide);

        // Reading mode — hide the sidebar and widen the text column for comfortable reading.
        document.getElementById('btn-docs-reading')?.addEventListener('click', () => {
            const panel = document.querySelector('.docs-panel');
            const on = panel?.classList.toggle('reading');
            try { localStorage.setItem('arima.docs.reading', on ? '1' : '0'); } catch {}
        });
        try {
            if (localStorage.getItem('arima.docs.reading') === '1')
                document.querySelector('.docs-panel')?.classList.add('reading');
        } catch {}

        document.querySelectorAll('.docs-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.docs-tab').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                showDoc(tab.dataset.doc);
            });
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                const overlay = document.getElementById('docs-overlay');
                if (!overlay?.classList.contains('hidden')) hide();
            }
        });
    }

    function show(section) {
        const overlay = document.getElementById('docs-overlay');
        if (!overlay) return;
        overlay.classList.remove('hidden');
        showDoc(section || currentDoc);
        document.querySelectorAll('.docs-tab').forEach(t => {
            t.classList.toggle('active', t.dataset.doc === (section || currentDoc));
        });
    }

    function hide() {
        document.getElementById('docs-overlay')?.classList.add('hidden');
    }

    function showDoc(section) {
        currentDoc = section || 'usage';
        const content = document.getElementById('docs-content');
        if (!content) return;
        const md = DOCS[currentDoc] || DOCS.usage;
        try {
            content.innerHTML = marked.parse(md);
        } catch(e) {
            content.textContent = md;
        }
        content.scrollTop = 0;

        // Render Mermaid diagrams — wrapped in try/catch so a broken diagram never crashes the panel.
        // marked renders ```mermaid blocks as <pre><code class="language-mermaid">...</code></pre>
        try {
            content.querySelectorAll('pre code.language-mermaid').forEach((codeEl, i) => {
                const pre = codeEl.parentElement;
                const diagram = codeEl.textContent;
                const div = document.createElement('div');
                div.className = 'mermaid';
                div.id = 'mermaid-' + Date.now() + '-' + i;
                div.textContent = diagram;
                pre.replaceWith(div);
            });
            if (typeof mermaid !== 'undefined') {
                mermaid.initialize({
                    startOnLoad: false,
                    theme: 'dark',
                    securityLevel: 'loose',
                    themeVariables: {
                        background: '#1e2030',
                        primaryColor: '#313244',
                        primaryTextColor: '#cdd6f4',
                        primaryBorderColor: '#45475a',
                        lineColor: '#6c7086',
                        secondaryColor: '#181825',
                        tertiaryColor: '#1e2030'
                    }
                });
                const nodes = content.querySelectorAll('.mermaid');
                if (nodes.length > 0) {
                    mermaid.run({ nodes });
                }
            }
        } catch(mermaidErr) {
            // Mermaid failure is non-fatal — docs text is already visible
            console.warn('Mermaid render error (non-fatal):', mermaidErr);
        }
    }

    return { init, show, hide };
})();

function showDocsPanel(section) {
    DocsPanel.show(section);
}

document.addEventListener('DOMContentLoaded', () => DocsPanel.init());
