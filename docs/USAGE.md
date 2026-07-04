# Arima Notebooks - Usage Guide

## Starting Arima (Windows CLI)

`arima.cmd` in the project root is the quickest way to manage Arima Notebooks on Windows.

```cmd
arima               # start server + open browser (auto-builds on first run)
arima start --bg    # start in background
arima stop          # stop the server
arima status        # check if running
arima help          # full command reference
```

See [docs/SETUP.md](SETUP.md#arima-cli-windows) for the complete CLI reference.

---

## The Interface

Arima Notebooks has five main tabs:

| Tab | Purpose |
|-----|---------|
| **Notebook** | Write and execute Java, JavaScript, TypeScript, C#, F#, or C++ code in cells |
| **Console** | Multi-runtime REPL — JShell, Java, JavaScript, or TypeScript with Tab completion |
| **Packages** | Install Maven packages (Java), npm packages (JavaScript / TypeScript), and NuGet packages (C#/F#) |
| **Settings** | Configure AI provider (Claude/Copilot/Antigravity), theme, and preferences |
| **AI** | Chat with the active AI provider; generate notebooks; switch providers inline |

---

## Notebook Tab

### Opening a Notebook

Click the **folder icon** (or the notebook name in the toolbar) to open the **Notebook Browser**.

The browser has two sections:

**My Notebooks** — your personal notebooks.
- Click **+ New Notebook** to create a blank notebook
- Click any card to open it in a new tab

**Arima Tutorials** — built-in read-only notebooks organized by language and level.
- Tutorials are grouped by: `JShell` / `Java` / `JavaScript` / `TypeScript` / `C#` / `F#` / `C++`
- Each group is sub-divided: **Basics & Foundations** → **Advanced** → **Data Science & Analytics**
- Level badges (`101` → `601`) indicate progression within each language track
- Tutorial notebooks open in a **read-only** tab (auto-save is disabled)

Use the **search box** at the top of the browser to filter across both sections simultaneously.

**Tutorial track overview:**

| Level | Focus |
|-------|-------|
| 101 | Variables, types, control flow, functions |
| 201 | Collections, OOP, error handling, pipelines |
| 301 | Generics, lambdas, streams, concurrency |
| 401 | Functional programming, async, advanced patterns |
| 501 | Design patterns, architecture, idiomatic code |
| 601 | Data science, statistics, visualization |

Tutorial tracks available: **JShell**, **Java**, **JavaScript**, **TypeScript**, **C#**, **F#**, **C++**

### Working with Cells

Arima has four cell types, each visually distinct:

| Type | Border | Badge | Purpose |
|------|--------|-------|---------|
| **JShell** (default) | Indigo | `☕ JShell` | Java snippets with shared session state |
| **Java** | Teal | `♨ Java` | Full class compile-and-run, isolated per cell |
| **JavaScript** | Green | `⬡ JS` | Node.js execution, isolated per cell |
| **TypeScript** | TS Blue | `◆ TS` | TypeScript via Node.js type-stripping + optional `tsc --noEmit` type-check, isolated per cell |
| **C#** | Purple | `◈ C#` | C# script via dotnet run, isolated per cell |
| **F#** | Orange | `◈ F#` | F# Interactive (dotnet fsi), isolated per cell |
| **C++** | Cyan | `⚙ C++` | g++/clang++ compile+run, C++17, isolated per cell |
| **Markdown** | Amber | `✎ Markdown` | Documentation text rendered as HTML |
| **Pipeline** | Gold | `⬡ Pipeline` | Orchestrate other cells with dependency steps |

**Code cells** contain executable code. Click the mode button to cycle through languages:
`JShell → Java → JS → TS → C# → F# → C++ → JShell`

> C# and F# cells require the .NET SDK (install from [dot.net](https://dot.net)).
> C++ cells require `g++`, `clang++`, or MSVC — see [docs/SETUP.md](SETUP.md#setting-up-c-support) for install instructions.
> Without the prerequisite, the mode is still selectable but will show a friendly install message on run.

**Clicking a cell expands it** — when a cell is not focused, it shows a collapsed preview (~4 lines) to keep the notebook compact. Click anywhere on the cell to expand it to full height and place the cursor. The cell collapses again when you click elsewhere. All cells render with **VS Code Dark+ syntax highlighting** (keywords, types, functions, strings each in distinct colours).

```java
// JShell mode (default) — snippets, shared state
var greeting = "Hello, Arima!";
System.out.println(greeting);
```

**Markdown cells** contain documentation text using Markdown syntax:
```markdown
# My Notebook
This is a **markdown** cell. It renders formatted text.
```

### Switching Cell Language

Every code cell has a **mode button** on its header. Click it to **cycle through all languages**:

**JShell → Java → JS → C# → F# → C++ → JShell → …**

| Mode | Icon | Behavior |
|------|------|----------|
| **JShell** | `☕ JShell` | Java snippets; variables shared across all cells in session |
| **Java** | `♨ Java` | Full `public class Main { ... }` compile + subprocess; cell is independent |
| **JavaScript** | `⬡ JS` | Node.js subprocess; cell is independent; `require()` loads npm packages |
| **TypeScript** | `◆ TS` | Node.js `--experimental-strip-types` subprocess + optional `tsc --noEmit` type-check; shares NODE_PATH with JS; cell is independent |
| **C#** | `◈ C#` | C# top-level program via `dotnet run`; isolated per cell (with dep injection for `//@ depends:`) |
| **F#** | `◈ F#` | F# script via `dotnet fsi`; isolated per cell (with dep injection for `//@ depends:`) |
| **C++** | `⚙ C++` | g++/clang++/MSVC compile+run, C++17; auto-wraps in `main()`; isolated per cell |

#### AI-powered language conversion

When you switch a cell's language mode, Arima offers to **convert the existing code** to the new language. A banner appears below the cell header:

```
Convert code from Java → C++?    [Convert]  [Keep as-is]
```

- Click **Convert** — Arima sends the code to the active AI provider and rewrites it in the target language. The result replaces the cell contents.
- Click **Keep as-is** (or wait 15 seconds) — the banner dismisses and the original code stays unchanged.

The mode switch is immediate; conversion is always optional.

#### JavaScript mode built-in helpers

Every JS cell has a `arima` object available automatically:

```javascript
// Pretty-print an array of objects as a table
barista.table([ { name: 'Alice', score: 95 }, { name: 'Bob', score: 87 } ]);

// JSON display with indent
barista.display({ key: 'value', nested: { x: 1 } });

// Quick statistics for a number array
barista.stats([12, 45, 23, 67, 34, 56]);
// → count: 6  min: 12  max: 67  mean: 39.5000  std: 18.5472
```

### Running Cells

- **Single cell**: Click the **Run** button or press `Shift+Enter` while in the cell
- **All cells**: Click **Run All** in the toolbar (runs sequentially top-to-bottom)
- **Run with dependencies**: Click **Run ↓** to run the current cell plus all cells it depends on first
- After execution, the view scrolls to the cell output automatically

### Cell Keyboard Shortcuts

| Key | Action |
|-----|--------|
| `Shift+Enter` | Run cell |
| `Ctrl+Enter` | Run cell (alternative) |
| `Ctrl+S` | Save notebook |

### Adding Cells

Click **+ Code** or **+ Markdown** in the toolbar to add cells at the bottom.

### Rearranging Cells

Use the **↑** and **↓** arrow buttons on each cell to move it up or down.

### Deleting Cells

Click the **✕** button on a cell to delete it.

### Restarting the Kernel

Click **↺ Restart** to clear all JShell variables. Cell source code is preserved, output is cleared, and the session starts fresh. Installed packages are re-applied automatically.

---

## Step Navigator

The Step Navigator lets you walk through a notebook cell by cell — perfect for presentations, tutorials, or debugging.

### Starting Step Mode

Click the **Step** button (▶) in the toolbar. A navigation bar appears at the bottom of the screen showing:
- Current cell number and total cell count
- Cell type badge
- Action button that changes based on cell type

### Navigation

| Button | Action |
|--------|--------|
| **◀ Prev** | Go back to the previous cell |
| **▶ Next** / **▷ Run** / **⬡ Run Pipeline** | Execute or advance |
| **✕** | Exit step mode |

**Behavior by cell type:**
- **Markdown**: Action button reads "▶ Next" — advances without executing
- **JShell / Java**: Action button reads "▷ Run & Next" — executes the cell, then advances
- **Pipeline**: Action button reads "⬡ Run Pipeline" — runs the full pipeline, then advances

The active cell is highlighted with a blue focus ring. After execution, the view scrolls to show the output.

---

## Pipeline Cells

Pipeline cells orchestrate other cells in dependency order using a simple annotation DSL.
Annotations work the same way in **all languages** — JShell, Java, JavaScript, C#, and F# cells
can all declare anchors and dependencies and be orchestrated together.

### Annotation DSL

```java
//@ anchor: loadData
//@ description: Loads the raw CSV file
//@ depends: validateConfig

Table data = BaristaDisplay.loadCsv("data/sales.csv");
System.out.println("Rows: " + data.rowCount());
```

| Annotation | Purpose |
|-----------|---------|
| `anchor:` | Unique name for this cell (used by `depends:`) |
| `depends:` | Comma-separated list of anchors this cell needs first |
| `description:` | Human-readable label shown in pipeline view |
| `on-error:` | `stop` (default) or `continue` |
| `pipeline:` | Name for a pipeline cell |
| `steps:` | Ordered list of anchors to run in sequence |

### Pipeline Toolbar

- **Validate** — Check the dependency graph for cycles or undefined anchors
- **⬡ New Pipeline** — Add a pipeline cell that orchestrates selected cells

### Running Pipelines

- Click **Run Pipeline** on a pipeline cell to execute all steps in dependency order
- Dependency badges (✓ / ✗ / ⏳) are shown beside each step after execution
- Failed cells stop the pipeline unless `on-error: continue` is set

### Cross-Notebook Dependencies

Cells can depend on named cells (anchors) in other notebooks. Reference format:

```java
//@ depends: notebook:other-notebook-id/anchorName
```

#### Using the cross-notebook picker

Every code and pipeline cell has a **chain-link button** (⛓) in its header. Clicking it opens a picker dialog:

1. **Select Notebook** — choose any other open notebook from the dropdown
2. **Select Cell Anchor** — choose from that notebook's named cells
3. A preview line shows the exact `//@ depends:` reference that will be inserted
4. Click **Insert Reference** — the annotation is added to the cell source automatically

The picker only shows cells that have an anchor name (`//@ anchor: ...`). Cells without anchors are not listed.

**Tips:**
- You can pick multiple references — open the picker again for each additional dependency
- To remove a cross-notebook dependency, edit the `//@ depends:` line directly in the cell source
- Run cross-notebook dependencies with **→ Run with deps** (the arrow button) — Arima will execute the foreign cell first and inject its output/state

---

## Data Science

Arima includes a full data science stack pre-loaded in every JShell session. No install required.

### Built-in Libraries

| Library | Version | Import Prefix |
|---------|---------|---------------|
| **XChart** | 3.8.6 | `org.knowm.xchart.*` |
| **Commons Math** | 3.6.1 | `org.apache.commons.math3.*` |
| **Tablesaw** | 0.43.1 | `tech.tablesaw.api.*` |
| **OpenCSV** | 5.9 | `com.opencsv.*` |

All imports and the `BaristaDisplay` helper class are automatically available.

### BaristaDisplay — Chart Rendering

Charts are rendered inline in cell output as PNG images.

```java
// XY / Line chart
var chart = BaristaDisplay.xyChart("Title", "X", "Y");
chart.addSeries("sin(x)", xData, yData);
BaristaDisplay.show(chart);

// Bar chart
var bar = BaristaDisplay.barChart("Monthly Sales", "Month", "Revenue");
bar.addSeries("2024", months, values);
BaristaDisplay.show(bar);

// Pie chart
var pie = BaristaDisplay.pieChart("Market Share");
pie.addSeries("Share", labels, percentages);
BaristaDisplay.show(pie);
```

### BaristaDisplay — DataFrame / Table Output

Tables are rendered as styled HTML inline in cell output.

```java
// Tablesaw DataFrame
Table df = BaristaDisplay.loadCsv("data/sales.csv");
BaristaDisplay.show(df);            // full table (max 50 rows)
BaristaDisplay.show(df, 10);        // first 10 rows
BaristaDisplay.info(df);            // column names, types, missing counts
BaristaDisplay.describe(df);        // numeric column statistics

// Custom HTML table from arrays
BaristaDisplay.table(
    new String[][]{{"Alice","30"},{"Bob","25"}},
    "Name", "Age"
);
```

### BaristaDisplay — Statistics Summary

```java
double[] data = {1.2, 3.4, 2.1, 5.0, 4.3};
BaristaDisplay.stats("My Dataset", data);
// Displays: count, min, max, mean, median, std deviation
```

### Commons Math

```java
// Descriptive statistics
var stats = new DescriptiveStatistics(data);
System.out.println("Mean: " + stats.getMean());
System.out.println("Std Dev: " + stats.getStandardDeviation());
System.out.println("Median: " + stats.getPercentile(50));

// Linear regression
var reg = new SimpleRegression();
for (int i = 0; i < x.length; i++) reg.addData(x[i], y[i]);
System.out.printf("R² = %.4f%n", reg.getRSquare());

// Probability distributions
var normal = new NormalDistribution(0, 1);
System.out.println("P(X < 1.96) = " + normal.cumulativeProbability(1.96));
```

### Tablesaw DataFrame

```java
// Load CSV
Table df = BaristaDisplay.loadCsv("data/sales.csv");

// Filter
Table filtered = df.where(df.numberColumn("amount").isGreaterThan(100));

// Group and aggregate
Table summary = df.summarize("amount", AggregateFunctions.mean, AggregateFunctions.sum)
                  .by("region");

// Derive column
df.addColumns(df.numberColumn("price").multiply(df.numberColumn("qty")).setName("revenue"));
```

### See Also

Open notebook **`java-601`** for comprehensive data science examples covering all libraries.

---

## JShell Tips

### Variables Persist Between Cells

```java
// Cell 1
var x = 42;
var name = "Arima";
```

```java
// Cell 2 - can use x and name from Cell 1
System.out.println(name + " says: " + x);
```

### No Class Wrapper Needed (JShell mode)

```java
// This works directly in JShell/Arima:
var list = List.of(1, 2, 3, 4, 5);
list.stream().filter(n -> n > 2).forEach(System.out::println);
```

### Methods and Classes

```java
// Define a method
int factorial(int n) {
    return n <= 1 ? 1 : n * factorial(n - 1);
}
factorial(10)
```

```java
// Define a record
record Person(String name, int age) {}

var people = List.of(
    new Person("Alice", 30),
    new Person("Bob", 25)
);
```

### Java Mode: Full Class

Switch a cell to **Java mode** when you need a full class with a `main` method:

```java
public class Demo {
    public static void main(String[] args) {
        System.out.println("Running as a compiled Java class");
    }
}
```

---

## C# Cells

C# cells run as C# 9+ top-level programs via `dotnet run`. The .NET SDK must be installed.

### Built-in helpers

```csharp
BaristaHtml("<b>bold</b>");       // rendered as HTML in output
BaristaDisplay(myObject);          // Console.WriteLine
BaristaTable(myList);              // ASCII table for any IEnumerable<T>
```

### Records, classes, and LINQ

```csharp
record Product(string Name, decimal Price, int Stock);

var products = new List<Product> {
    new("Widget A", 9.99m, 120),
    new("Widget B", 14.99m, 45),
};

var expensive = products.Where(p => p.Price > 10).ToList();
BaristaTable(expensive);
```

### NuGet packages

Install packages via the **Packages → NuGet** tab, or add an inline reference in the cell:

```csharp
// Inline reference (placed at top of cell):
#r "nuget: Newtonsoft.Json, 13.0.3"
using Newtonsoft.Json;

var json = JsonConvert.SerializeObject(new { name = "Arima", version = 1 });
Console.WriteLine(json);
```

### C# Pipeline dependencies

C# cells each run in their own subprocess. Use `//@ anchor:` and `//@ depends:` to share data:

```csharp
//@ anchor: loadData
record Sale(string Region, decimal Amount);
var sales = new List<Sale> { new("North", 1200m), new("South", 800m) };
Console.WriteLine($"Loaded {sales.Count} rows");
```

```csharp
//@ anchor: analyzeData
//@ depends: loadData
// Arima injects `loadData` source before this cell — `sales` is in scope
var total = sales.Sum(s => s.Amount);
Console.WriteLine($"Total: ${total:N0}");
```

> **How it works**: When `//@ depends: loadData` is declared, Arima prepends the source code from
> `loadData` (with its console output silenced) before compiling and running this cell.
> All variables and types from ancestor cells are in scope.
>
> **Important**: Run ancestor cells first (or use **→ Run with deps**) — Arima caches each
> anchor's source on first successful run and reuses it for dependent cells.

---

## F# Cells

F# cells run as `.fsx` scripts via `dotnet fsi`. The .NET SDK must be installed.

### Built-in helpers

```fsharp
baristaHtml "<b>bold</b>"        // rendered as HTML in output
baristaDisplay myObject           // printfn "%A"
baristaTable myList               // printfn "%A"
```

### Option and Result types

```fsharp
let safeDivide a b =
    if b = 0 then None else Some (a / b)

[10; 0; 5] |> List.map (safeDivide 100) |> List.iter (printfn "%A")
```

### NuGet packages

Install packages via the **Packages → NuGet** tab, or use an inline `#r` directive in the cell:

```fsharp
// Inline reference (Arima places it at the top of the script automatically):
#r "nuget: Humanizer.Core, 2.14.1"
open Humanizer

1024.Bytes().Humanize() |> printfn "%s"
System.DateTime.Now.AddHours(-3.0).Humanize() |> printfn "%s"
```

### F# Pipeline dependencies

Use the same `//@ anchor:` / `//@ depends:` annotations as JShell and C# cells:

```fsharp
//@ anchor: loadTransactions
type Transaction = { Category: string; Amount: decimal }
let transactions = [
    { Category = "Food"; Amount = 85.50m }
    { Category = "Transport"; Amount = 42.00m }
]
printfn "Loaded %d transactions" (List.length transactions)
```

```fsharp
//@ anchor: analyzeTransactions
//@ depends: loadTransactions
// `transactions` and `Transaction` type from loadTransactions are in scope
let byCategory =
    transactions |> List.groupBy (fun t -> t.Category)
byCategory |> List.iter (fun (cat, ts) ->
    printfn "%s: $%.2f" cat (ts |> List.sumBy (fun t -> t.Amount)))
```

---

## C++ Cells

C++ cells compile and run with C++17. No `main()` function is needed — Arima wraps your code automatically.

**Supported compilers** (Arima detects automatically, in order):
- `g++` / `clang++` on PATH — MinGW-w64, MSYS2, WinLibs, Homebrew, or system package
- **Visual Studio / Build Tools (MSVC)** — detected automatically on Windows, no PATH setup needed

> **First time?** Just run a C++ cell. If a compiler is found, it executes immediately. If not, Arima shows exact install instructions for your platform.

### What's available in every C++ cell

The following headers and namespace are injected automatically:
```
<iostream>  <string>    <vector>    <map>       <unordered_map>
<set>       <list>      <deque>     <queue>     <stack>
<algorithm> <numeric>   <functional><sstream>   <iomanip>
<cmath>     <memory>    <optional>  <variant>   <tuple>
<array>     <stdexcept> <fstream>   <random>    <chrono>
using namespace std;
```

### Built-in helpers

```cpp
baristaHtml("<b>bold</b>");         // rendered as HTML in output
baristaDisplay(value);              // cout << value << "\n"
baristaTable(myVector);             // prints item count
baristaTable(myMap);                // key/value ASCII table
```

### Writing a cell

```cpp
// No main() needed — just write statements and declarations:
int factorial(int n) {
    return (n <= 1) ? 1 : n * factorial(n - 1);
}

cout << "5! = " << factorial(5) << "\n";

vector<int> nums = {3, 1, 4, 1, 5, 9};
sort(nums.begin(), nums.end());
for (int x : nums) cout << x << " ";
cout << "\n";
```

### Complete programs

If your cell contains `int main(`, Arima compiles it as a complete program (with the standard headers still prepended):

```cpp
#include <fstream>    // additional headers work fine

int main() {
    cout << "Full program mode\n";
    return 0;
}
```

### C++ Pipeline dependencies

Use the same `//@ anchor:` / `//@ depends:` annotations as Java, C#, and F#:

```cpp
//@ anchor: loadData
struct Record { string name; double value; };
vector<Record> data = {{"A", 1.5}, {"B", 2.3}, {"C", 0.9}};
cout << "Loaded " << data.size() << " records\n";
```

```cpp
//@ anchor: analyzeData
//@ depends: loadData
// Record type and data vector injected from loadData
double sum = 0;
for (const auto& r : data) sum += r.value;
cout << "Total: " << sum << "\n";
cout << "Mean:  " << sum / data.size() << "\n";
```

> **How it works**: Ancestor declarations (classes, structs, functions) are injected at global scope. Ancestor statements are injected at the start of `main()` with stdout suppressed — only the current cell's output is visible.

### Compiler flags

| Compiler | Flags used |
|----------|-----------|
| g++ / clang++ | `-std=c++17 -Wall -Wno-unused-variable` |
| MSVC (cl.exe) | `/EHsc /std:c++17 /Zc:__cplusplus /nologo /W3` |

`__cplusplus` evaluates to `201703` (C++17) with all supported compilers.

### Installing C++ (if needed)

If you see "C++ compiler not found", Arima shows platform-specific instructions. Quick reference:

| Platform | Easiest option |
|----------|---------------|
| Windows | Visual Studio Installer → Modify → "Desktop development with C++" |
| Windows (no VS) | MSYS2 → `pacman -S mingw-w64-ucrt-x86_64-gcc` |
| Ubuntu/Debian | `sudo apt install build-essential` |
| macOS | `xcode-select --install` |
| Fedora/RHEL | `sudo dnf install gcc-c++` |

See **[docs/SETUP.md — Setting up C++ support](SETUP.md#setting-up-c-support)** for full step-by-step instructions with screenshots and PATH setup details.

### Tutorial notebooks

| Notebook | Level | Topics |
|----------|-------|--------|
| `cpp-101` | Beginner | Variables, types, operators, strings, control flow, functions, arrays |
| `cpp-201` | Intermediate | Classes, OOP, operator overloading, inheritance, STL containers, exceptions |
| `cpp-301` | Intermediate+ | Templates, lambdas, STL algorithms, RAII, smart pointers, optional, std::function |
| `cpp-401` | Advanced | Move semantics, perfect forwarding, concurrency, design patterns, constexpr |
| `cpp-501` | Expert | CRTP, variadic templates, fold expressions, std::ranges, type traits, metaprogramming |

---

## Error Log Panel

When network errors or pipeline failures occur, they accumulate in the **Error Log** — a collapsible panel in the status bar.

- A red **⚠ Errors** button appears in the status bar when there are errors
- Click it to toggle the error log panel
- Each entry shows timestamp, source, message, and detail
- Click **Clear** to dismiss all entries

---

## Console Tab

The Console tab is a multi-runtime interactive REPL. Select a language runtime using the buttons at the top, then type code and press **Enter** to execute.

### Selecting a Runtime

| Button | Runtime | Behavior |
|--------|---------|----------|
| `☕ JShell` | JShell (default) | Java snippets; shared session state; all variables persist |
| `♨ Java` | Full Java | Compiles and runs a complete Java class per command |
| `⬡ JavaScript` | Node.js | Executes JavaScript; `require()` loads installed npm packages |
| `◆ TypeScript` | Node.js (`--experimental-strip-types`) | Executes TypeScript expressions; `import` and types supported; shares NODE_PATH with JS |

The active runtime badge in the header shows which runtime is currently selected.

### Running Code

- Type code in the input area
- Press **Enter** to execute
- Press **Shift+Enter** to add a newline (multi-line input)
- Click **Run** button

### Tab Completion

- Press **Tab** to auto-complete the current token
- A hint box drops up above the input showing matching suggestions
- Press **Tab** again to cycle to the next suggestion
- Click any hint item with the mouse to apply it
- Any other key press hides the hint box

**JShell** — completion is server-side using JShell's `SourceCodeAnalysis` API (knows your declared variables and imports).

**Java / JavaScript / TypeScript** — completion is client-side keyword hints (common snippets like `System.out.println(`, `console.log(`, `require(`, `import * as`, `interface`, etc.).

### History Navigation

- Press **↑** / **↓** arrows to navigate command history (up to 500 entries)

### Inline Charts

JavaScript cells can render charts directly in the console output using `barista.html()`:

```javascript
const d3 = require('d3');
const svg = `<svg width="200" height="60">
  <rect x="10" y="10" width="80" height="40" fill="steelblue"/>
  <text x="50" y="36" fill="white" text-anchor="middle">Bar</text>
</svg>`;
barista.html(svg);
```

### Console vs Notebook

The Console uses a separate JShell session (`console` session). Variables defined in the Console are not accessible in Notebook cells and vice versa.

### Restart

Click **Restart** to clear all variables for the current runtime session and start fresh.

---

## Packages Tab

The Packages tab has four sections — **Maven** for Java, **npm** for JavaScript / TypeScript, **NuGet** for C# / F#, and **C++** for the standard library reference.

### Maven Packages (Java / JShell)

1. Click the **Maven (Java)** sub-tab
2. Enter the Maven coordinate: `groupId:artifactId:version`

**Examples:**
```
com.google.code.gson:gson:2.10.1
org.apache.commons:commons-lang3:3.14.0
com.fasterxml.jackson.core:jackson-databind:2.16.1
```

3. Click **Install**

The JAR is downloaded from Maven Central and added to all active JShell sessions immediately.

Use the **Search Maven Central** section to find package coordinates.

### npm Packages (JavaScript / TypeScript)

1. Click the **npm (JavaScript / TypeScript)** sub-tab
2. Enter a package name (or `name@version`)
3. Click **Install** — or click a **popular package pill** for one-click install

npm packages are stored under `data/npm-modules/` and shared between JS and TS cells via `NODE_PATH`. Use them with `require('package')` in JavaScript or `import * as x from 'package'` in TypeScript.

**Popular data science packages:**

| Package | Description | JS usage | TS usage |
|---------|-------------|----------|----------|
| `simple-statistics` | Descriptive stats, regression, hypothesis tests | `require('simple-statistics')` | `import * as ss from 'simple-statistics'` |
| `mathjs` | Full math library — algebra, matrices, units | `require('mathjs')` | `import * as math from 'mathjs'` |
| `danfojs-node` | Pandas-like DataFrames for Node.js | `require('danfojs-node')` | `import * as dfd from 'danfojs-node'` |
| `d3-array` | Array statistics and histogram utilities | `require('d3-array')` | `import * as d3a from 'd3-array'` |
| `lodash` | Utility functions (arrays, objects, strings) | `require('lodash')` | `import * as _ from 'lodash'` |
| `axios` | HTTP client for fetching data | `require('axios')` | `import axios from 'axios'` |
| `dayjs` | Date/time manipulation | `require('dayjs')` | `import dayjs from 'dayjs'` |

**Example — using simple-statistics in a TS cell:**
```typescript
import * as ss from 'simple-statistics';
const data: number[] = [12, 45, 23, 67, 34, 56, 78, 29];
console.log('Mean:', ss.mean(data).toFixed(2));
console.log('Std:', ss.standardDeviation(data).toFixed(2));
```

> **Requires Node.js**: JavaScript cells need Node 18+, TypeScript cells need Node 22.6+.
> Install from [nodejs.org](https://nodejs.org) — the npm sub-tab shows a status indicator.
> For TypeScript type-check diagnostics, also install `typescript` globally: `npm install -g typescript`.

### NuGet Packages (C# / F#)

1. Click the **NuGet (C# / F#)** sub-tab
2. Enter a **Package ID** (e.g. `Newtonsoft.Json`) and **Version** (e.g. `13.0.3`)
3. Click **Install**

The package is saved to `data/nuget-packages.json`. On next C# or F# cell execution, Arima prepends:
```
#r "nuget: Newtonsoft.Json, 13.0.3"
```
The .NET runtime downloads the package automatically on first use (requires internet access).

**Popular NuGet packages:**

| Package | Version | Description |
|---------|---------|-------------|
| `Newtonsoft.Json` | `13.0.3` | JSON serialization/deserialization |
| `CsvHelper` | `33.0.1` | CSV reading and writing |
| `Dapper` | `2.1.35` | Lightweight SQL ORM |
| `MathNet.Numerics` | `5.0.0` | Scientific computing for .NET |
| `Humanizer.Core` | `2.14.1` | Human-friendly strings, numbers, dates |
| `Spectre.Console` | `0.49.1` | Rich terminal output |

**Inline NuGet reference** (alternative): Add directly in a cell:
```csharp
#r "nuget: Newtonsoft.Json, 13.0.3"
using Newtonsoft.Json;
// ...
```

> **Requires .NET SDK**: C# and F# cells need the .NET SDK installed (no extra tools).
> Install from [dot.net](https://dot.net) — the NuGet sub-tab shows a status indicator with setup
> instructions if the SDK is missing.

### Removing Packages

Click **Remove** next to any installed package (Maven, npm, or NuGet).

---

## AI Assistant Tab

### Switching Providers

The AI sidebar header shows the active provider name and icon. Directly below it is a **three-button toggle bar**:

```
[ 🤖 Claude ]  [ 🐙 Copilot ]  [ 🚀 Antigravity ]
```

Click any button to switch providers instantly — no restart required. The switch is saved to settings and a confirmation notice appears in the chat. Only providers with their CLI detected are fully active; unavailable CLIs show a dimmed button.

You can also switch from **Settings → AI Provider**.

### Chatting

1. Go to the **AI** tab
2. Type your question in the input field
3. Press **Enter** or click **Send**

**Example prompts:**
- "How do I read a file in Java?"
- "Explain this code: `Stream.iterate(1, n -> n * 2).limit(10)`"
- "What Maven packages should I use for HTTP requests?"
- "Write a Java method that sorts a list of objects by field name"
- "Convert this Java class to C++"

### Generating Notebooks

Click **Generate Notebook** to have the active AI provider create a complete notebook:

1. Click **Generate Notebook**
2. Describe what you want
3. Click **Generate**
4. The notebook is created and added to your notebook list

---

## Agentic Workflows — Use, Customize, Contribute

Arima assumes an AI partner is in your loop and gives you three surfaces to work from. Pick whichever fits the moment.

### Surface 1 — The AI panel inside Arima (use)

For *this notebook, right now*. Generate cells, explain output, convert between languages, ask why something failed. Attach a cell with the 🤖 button so the AI sees the actual code. Code blocks in responses have an **Insert into notebook** button.

**Try:**
- *"Generate a JShell cell that reads `data/sales.csv` with Tablesaw and shows the first 10 rows."*
- *"Convert the selected Java cell to TypeScript."* (then **Insert into notebook**)
- *"Why is the pipeline saying `clean-data` is stale? Look at the dependency chain."*

### Surface 2 — Your AI CLI in the Arima repo (customize)

For *Arima itself*. Open `claude`, `copilot`, or `agy` (Antigravity) inside the cloned repo. The agent reads [`AGENTS.md`](../AGENTS.md) for the architecture rules and edits the right files. Most one-feature changes take under an hour.

**Try:**
- *"Add a 'Export as Markdown' button to the notebook toolbar. New endpoint, new UI button, update `docs/API.md`."*
- *"The JShell error messages should highlight unresolved symbols in red. Update `JShellManager.formatError` and the matching CSS."*
- *"Add a `notebooks/tutorials/csharp-401.vnb` covering async/await in C#. Six cells, intermediate."*

When you're happy, close the contribution loop with one more prompt:

> *"Run `pwsh ./scripts/security-check.ps1`, then push the branch and open a PR back to upstream with a clear description."*

### Surface 3 — Any MCP-aware agent (drive Arima programmatically)

Arima publishes itself as an [MCP](https://modelcontextprotocol.io) server. Add it to Claude Code, Claude Desktop, or any MCP-aware agent and you can drive notebooks from outside the UI.

| MCP tool | What it does |
|---|---|
| `barista_create_notebook` | Create a notebook by name |
| `barista_append_cell` | Append a cell (and optionally run it) |
| `barista_read_notebook` | Read all cells (source, anchors, output) |
| `barista_run_pipeline` | Execute a cell with its full dependency chain |
| `barista_load_module` | Load `notebookId/anchor` into a session for cross-notebook reuse |

**Try (from inside Claude Desktop, after MCP setup):**
- *"Create a Arima notebook that explores yesterday's API latency data — one cell to load the parquet, one to plot the p95 over time, one to flag outliers. Run it."*
- *"Read the `pricing-experiment` notebook in Arima and summarize what each cell does."*

The notebook is the shared artifact. You, the Arima UI, the AI panel, the CLI, and any MCP agent are all first-class users of it.

---

## Settings Tab

### AI Provider

Select the active AI provider with the **Claude CLI / Copilot / Antigravity** radio buttons. Each provider card expands to show its specific settings and a live status indicator.

**Claude CLI**

| Setting | Description |
|---------|-------------|
| Claude Model | Which Claude model to use (`claude-sonnet-4-6` recommended) |
| Max Tokens | Maximum response length (default 4096) |

Recommended models: `claude-sonnet-4-6` (balanced), `claude-opus-4-6` (most capable), `claude-haiku-4-5` (fastest)

**GitHub Copilot (SDK)**

Uses the GitHub Copilot SDK, which drives the local `copilot` CLI (v1.0.55-5+). Shows a live status dot indicating whether the `copilot` binary is found and authenticated.

**Antigravity CLI (`agy`)**

The successor to the retired Gemini CLI. Shows a live status dot indicating whether the `agy` binary is found and signed in. (The model is managed by `agy`; the provider key remains `gemini_cli` for compatibility.)

### Editor Settings

| Setting | Description |
|---------|-------------|
| Theme | Dark (default) or Light — click a colour swatch for live preview |
| Font Size | Code editor font size in pixels |
| Line Numbers | Show/hide line numbers |
| Focus Executing Cell | Auto-scroll to the cell currently running |

---

## Keyboard Shortcuts Reference

| Shortcut | Action |
|----------|--------|
| `Ctrl+S` | Save current notebook |
| `Shift+Enter` | Run current cell (when in code editor) |
| `Ctrl+Enter` | Run current cell (alternative) |
| `↑` / `↓` | Navigate console history (in Console tab) |

---

## Notebook File Format

Notebooks are stored as `.vnb` JSON files in the `notebooks/` directory:

```json
{
  "id": "uuid-here",
  "name": "My Notebook",
  "cells": [
    {
      "id": "cell-1",
      "type": "MARKDOWN",
      "source": "# Title\nMarkdown content"
    },
    {
      "id": "cell-2",
      "type": "CODE",
      "mode": "jshell",
      "source": "System.out.println(\"Hello\");",
      "anchor": "hello",
      "dependsOn": []
    },
    {
      "id": "cell-3",
      "type": "CODE",
      "mode": "java",
      "source": "public class Demo {\n  public static void main(String[] a) {\n    System.out.println(\"Java mode\");\n  }\n}"
    },
    {
      "id": "cell-4",
      "type": "PIPELINE",
      "source": "//@ pipeline: myPipeline\n//@ steps: hello, compute"
    }
  ]
}
```

**Cell fields:**

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique cell identifier |
| `type` | `CODE` / `MARKDOWN` / `PIPELINE` | Cell type |
| `mode` | `jshell` / `java` / `nodejs` / `csharp` / `fsharp` / `cpp` | Execution mode (CODE cells only) |
| `source` | string | Cell content |
| `anchor` | string | Optional unique name for dependency graph |
| `dependsOn` | string[] | List of anchor names this cell depends on |
| `pipelineSteps` | string[] | Ordered anchor list (PIPELINE cells) |
