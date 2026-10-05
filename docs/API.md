# Arima Notebooks - REST API Reference

Base URL: `http://localhost:8585/api`

All requests and responses use JSON (`Content-Type: application/json`).

---

## Notebooks

### List Notebooks
```
GET /api/notebooks
```
Returns metadata for all notebooks (not full cell content).

**Response 200:**
```json
[
  {
    "id": "welcome",
    "name": "Welcome to Arima Notebooks",
    "description": "...",
    "created": "2025-01-01T00:00:00",
    "modified": "2025-01-01T00:00:00",
    "cellCount": 10
  }
]
```

---

### Get Notebook
```
GET /api/notebooks/{id}
```
Returns a full notebook including all cells.

**Response 200:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "My Notebook",
  "description": "",
  "created": "2025-01-01T10:00:00",
  "modified": "2025-01-01T12:00:00",
  "cells": [
    {
      "id": "cell-1",
      "type": "CODE",
      "source": "System.out.println(\"Hello\");",
      "output": "Hello\n",
      "error": "",
      "returnValue": null,
      "executed": true,
      "executionCount": 1
    }
  ],
  "metadata": {}
}
```

**Response 404:** Notebook not found.

---

### Create Notebook
```
POST /api/notebooks
```
**Body:**
```json
{ "name": "My New Notebook", "mode": "python" }
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | no | Defaults to `Untitled Notebook` |
| `mode` | no | Default language for the notebook's code cells — one of `jshell`, `java`, `nodejs`, `typescript`, `csharp`, `fsharp`, `cpp`, `python`. Stored as `metadata.defaultMode`; unrecognised values fall back to `jshell`. |

**Response 201:** Returns the created notebook. The notebook is created **empty** (`cells: []`) — no starter cell is added.

---

### Save Notebook
```
PUT /api/notebooks/{id}
```
**Body:** Full notebook JSON (same format as GET response).

**Response 200:** Returns the saved notebook with updated `modified` timestamp.

---

### Delete Notebook
```
DELETE /api/notebooks/{id}
```
**Response 200:**
```json
{ "deleted": true }
```

---

## Shell Execution

### Execute Code
```
POST /api/shell/execute
```
**Body:**
```json
{
  "sessionId": "nb-welcome",
  "code": "System.out.println(\"Hello World\");",
  "cellId": "cell-2",
  "mode": "jshell"
}
```

- `sessionId`: Any string identifying the session. Use `nb-{notebookId}` for notebooks, `console` for the console tab.
- `cellId`: Optional. If provided, associates the result with a specific cell (for WebSocket broadcasts).
- `mode`: Execution engine to use. Values:
  - `"jshell"` (default) — shared JShell REPL session
  - `"java"` — compiles and runs a full `public class Main` via `javax.tools`
  - `"nodejs"` — runs code via Node.js subprocess
  - `"typescript"` — runs code via Node.js with `--experimental-strip-types` (Node 22.6+); if `tsc` is available, also runs `tsc --noEmit` for type-check diagnostics
  - `"csharp"` — runs code as a C# 9+ top-level program via `dotnet run`
  - `"fsharp"` — runs code as an F# script via `dotnet fsi --exec`
  - `"cpp"` — compiles and runs C++17 via `g++` or `clang++`

**Response 200:**
```json
{
  "sessionId": "nb-welcome",
  "cellId": "cell-2",
  "output": "Hello World\n",
  "error": "",
  "returnValue": null,
  "status": "VALID",
  "success": true,
  "executionTimeMs": 45,
  "executionCount": 1
}
```

**Status values:**
- `VALID` — Code was accepted and executed
- `REJECTED` — Compile error
- `ERROR` — Runtime exception
- `OVERWRITTEN` — Variable/method was redefined

---

### Execute Pipeline Cell
```
POST /api/shell/execute-pipeline
```
Runs all steps of a PIPELINE cell in topological order.

**Body:**
```json
{
  "notebookId": "welcome",
  "cellId": "cell-pipeline-1",
  "sessionId": "nb-welcome"
}
```

**Response 200:**
```json
{
  "steps": [
    { "anchor": "loadData", "success": true, "output": "...", "executionTimeMs": 120 },
    { "anchor": "process",  "success": true, "output": "...", "executionTimeMs": 45 }
  ],
  "success": true,
  "totalTimeMs": 165
}
```

---

### Execute Cell with Dependencies
```
POST /api/shell/execute-with-deps
```
Runs all transitive dependencies of a cell (in topological order) before running the cell itself.

**Body:**
```json
{
  "notebookId": "welcome",
  "cellId": "cell-3",
  "sessionId": "nb-welcome"
}
```

**Response 200:** Same format as execute-pipeline response.

---

### Run To Here
```
POST /api/shell/run-to-here
```
Runs all cells above and including the specified cell, in document order.

**Body:**
```json
{
  "notebookId": "welcome",
  "cellId": "cell-5",
  "sessionId": "nb-welcome"
}
```

**Response 200:** Array of `ExecutionResult` objects, one per executed cell.

---

### Cross-Notebook References

Cells can declare dependencies on cells in other notebooks using the annotation DSL:

```
//@ depends: notebook:{notebookId}/{anchorName}
```

**Example:**
```csharp
//@ anchor: myAnalysis
//@ depends: notebook:csharp-shared-utils/cs_statistics, cs_loadData
```

**How Arima Notebooks resolves cross-notebook refs at execution time:**

| Language | Resolution |
|----------|-----------|
| JShell / Java | The foreign cell's source is executed in the current JShell session |
| C# / F# | Arima Notebooks builds an **expanded source** (full transitive dep chain, annotation-stripped) and injects it with output suppressed before the current cell's code |

Cross-notebook execution is triggered automatically by `execute-with-deps` and `execute-pipeline` when they encounter `notebook:*` references. It can also be triggered manually by `POST /api/shell/execute` when the cell has `//@ depends:` annotations and a session anchor cache is already populated.

---

### Validate Dependency Graph
```
GET /api/shell/validate-graph/{notebookId}
```
Checks the notebook's cell dependency graph for cycles and undefined anchor references.

**Response 200 (valid):**
```json
{ "valid": true, "errors": [] }
```

**Response 200 (invalid):**
```json
{
  "valid": false,
  "errors": [
    "Cycle detected: loadData → process → loadData",
    "Unknown anchor 'missingCell' referenced by 'compute'"
  ]
}
```

---

### Restart Session
```
POST /api/shell/{sessionId}/restart
```
Clears all variables and state. The session ID remains the same.

**Response 200:**
```json
{ "message": "Session restarted", "sessionId": "nb-welcome" }
```

---

### Close Session
```
DELETE /api/shell/{sessionId}
```
Terminates and removes the JShell instance.

**Response 200:**
```json
{ "closed": true }
```

---

### List Sessions
```
GET /api/shell/sessions
```
**Response 200:**
```json
["nb-welcome", "console", "nb-abc123"]
```

---

### Get Session Info
```
GET /api/shell/{sessionId}/info
```
**Response 200:**
```json
{
  "sessionId": "nb-welcome",
  "executionCount": 12,
  "classpath": ["/path/to/data/packages/gson-2.10.1.jar"]
}
```

---

## Agents, Skills, Tools & Plugins

A **definition** is a normal notebook with `metadata.kind` set to one of four values. There is no
separate storage format — the service for each kind projects the notebook into a spec.

| `metadata.kind` | Instructions / docs | Implementation | Metadata block |
|---|---|---|---|
| `agent` | markdown cells = system prompt | — | `metadata.agent` (`provider`, `tools`) |
| `skill` | markdown cells = instructions | — | `metadata.agent.provider` |
| `tool` | markdown cells = docs | **code cells, any of the 8 languages** | `metadata.tool` (`mode`, `params`) |
| `plugin` | markdown cells = README | — | `metadata.plugin` (`version`, `author`, `members`) |

Each provider knows how to **run** a definition (invoke the CLI, streaming output over the STOMP
topic `/topic/shell/{sessionId}` as `partial_output` with `cellId = "__agent_run__"`) and how to
**deploy** it into that CLI's native files. Three providers are registered: `claude`
(`.claude/`), `copilot` (`.github/`) and `gemini` → Antigravity (`.antigravity/`).

Built-in samples: `agent-101`, `agent-201`, `agent-301`, `agent-401` (reviewer in a pipeline),
`agent-501` (multi-agent review), `agent-601` (MCP-driven), `skill-101`, `tool-101` (word count),
`plugin-101` (review kit).

All of it is reachable over **MCP**: `barista_list_agents` / `barista_run_agent` for agents,
`barista_list_tools` / `barista_invoke_tool` for tools, plus one `arima_<tool_name>` entry per
authored tool carrying its own JSON Schema (see the MCP section).

### Provider availability
```
GET /api/agents/providers
```
**Response 200:** `{ "claude": true, "copilot": true, "gemini": false }`

### Create an agent/skill notebook
```
POST /api/agents/create
{ "name": "code-reviewer", "kind": "agent" }   // kind: "agent" | "skill"
```
Returns the created `Notebook` (pre-seeded with a starter instructions cell).

### Run
```
POST /api/agents/run
{ "notebookId": "agent-201", "task": "Review the diff on my branch", "provider": "claude", "sessionId": "nb-..." }
```
**Response 200:** `{ "output": "...", "provider": "claude", "success": true }` — output also streams live via STOMP.

A tool grant in `metadata.agent.tools` that matches a tool authored in Arima is resolved before the
run: the agent's prompt gains that tool's signature and its `arima_<name>` MCP name. Grants that
match no Arima tool are passed through as the CLI's own built-ins (`Read`, `Bash`, …).

### Deploy

```
POST /api/agents/deploy
{ "notebookId": "agent-201", "provider": "claude", "target": "project" }
```
**Response 200:**
```json
{ "success": true, "kind": "agent", "slug": "code-reviewer", "target": "project",
  "targetLabel": "this project (repo root)", "provider": "claude",
  "paths": [".claude/agents/code-reviewer.md"], "path": ".claude/agents/code-reviewer.md",
  "deployedAt": "2026-10-05T05:08:27Z" }
```

Targets:

| `target` | Writes under | Meaning |
|---|---|---|
| `project` | repo root | this checkout only |
| `user` | `~` | every project on this machine |
| `bundle` | `data/` | staged copy, nothing installed |

Skills deploy to `skills/<name>/SKILL.md` under the provider's folder; agents to
`agents/<name>.md`. Every write is recorded in `data/deployments.json`, which is what makes a
deploy reversible.

```
POST /api/agents/export
{ "notebookId": "agent-201", "provider": "claude" }
```
A thin alias of `deploy` with `target: "project"`, kept for existing callers.
**Response 200:** `{ "path": ".claude/agents/code-reviewer.md", "success": true }`

### Deployments
```
GET /api/agents/deployments
```
**Response 200:** an array of `{ id, kind, slug, target, targetLabel, provider, paths[], deployedAt, present }`,
newest first. `present` is false when the files were removed outside Arima.

```
POST /api/agents/undeploy
{ "id": "agent-201", "target": "project" }
```
**Response 200:** `{ "success": true, "removed": 1, "target": "project" }` — deletes exactly the
recorded paths and prunes the directories they emptied; never touches anything it did not write.

---

## Tools

A tool is a notebook you can **call**. `metadata.tool.params` is the signature; the code cells are
the body. On invocation each argument is validated, coerced to its declared type, and bound as a
variable of the same name in the tool's own language before the body runs through that language's
execution service. Whatever the tool prints is its return value.

Parameter types: `string` · `integer` · `number` · `boolean`. Body modes: the eight Arima executes
(`jshell`, `java`, `nodejs`, `typescript`, `csharp`, `fsharp`, `cpp`, `python`).

### List tools
```
GET /api/tools/list
```
**Response 200:** array of `{ id, name, slug, description, mode, params[], paramCount, hasBody, source }`.

### Body modes
```
GET /api/tools/modes
```
**Response 200:** `["jshell","java","nodejs","typescript","csharp","fsharp","cpp","python"]`

### Create a tool notebook
```
POST /api/tools/create
{ "name": "word-count", "mode": "python" }
```
Returns the created `Notebook`, pre-seeded with a docs cell, one `input` parameter, and a runnable body.

### Parameter schema
```
GET /api/tools/{id}/schema
```
**Response 200:** `{ "name": "arima_word_count", "description": "...", "mode": "python", "inputSchema": { … } }`
— the same JSON Schema the MCP server advertises.

### Invoke
```
POST /api/tools/invoke
{ "toolId": "tool-101", "args": { "text": "hello world", "detailed": "true" }, "sessionId": "nb-..." }
```
**Response 200:** `{ "success": true, "output": "...", "returnValue": null, "error": "", "executionTimeMs": 222, "tool": "word-count", "mode": "python" }`

A missing required argument returns `{ "success": false, "error": "Tool 'X' requires parameter 'y'." }`.

---

## Plugins

A plugin bundles agents, skills and tools into one installable Claude Code plugin directory:

```
<target>/plugins/<slug>/
  .claude-plugin/plugin.json   name, version, description, author
  agents/<slug>.md             each member agent
  skills/<slug>/SKILL.md       each member skill
  commands/<slug>.md           one per member tool
  .mcp.json                    points back at Arima's MCP server
  README.md                    the plugin notebook's markdown cells
```

Member **tools are not copied into the bundle** — they stay in Arima and the generated command
reaches them over the in-process MCP server. One source of truth, and no new outbound host.

### List plugins
```
GET /api/plugins/list
```
**Response 200:** array of `{ id, name, slug, description, version, author, members[], memberCount, source, deployedTo[] }`
where each member is `{ id, name, kind, missing }`.

### Deploy targets
```
GET /api/plugins/targets
```
**Response 200:** `[{ "key": "project", "label": "this project (repo root)", "root": "...", "pluginRoot": "..." }, …]`

### Create a plugin notebook
```
POST /api/plugins/create
{ "name": "review-kit" }
```

### Deploy
```
POST /api/plugins/deploy
{ "pluginId": "plugin-101", "target": "project" }
```
**Response 200:** `{ "success": true, "slug": "review-kit", "target": "project", "targetLabel": "...", "fileCount": 6, "root": "...", "deployedAt": "..." }`

Members that no longer resolve are skipped with a warning rather than failing the bundle. A
redeploy replaces the previous files and removes any the new build no longer produces. Undeploy via
`POST /api/agents/undeploy` with the plugin's id.

---

## Package Manager

### List Installed Packages
```
GET /api/packages
```
**Response 200:**
```json
[
  {
    "groupId": "com.google.code.gson",
    "artifactId": "gson",
    "version": "2.10.1",
    "jarPath": "data/packages/com.google.code.gson_gson_2.10.1.jar",
    "installedAt": "2025-01-01T10:00:00",
    "coordinate": "com.google.code.gson:gson:2.10.1",
    "displayName": "gson 2.10.1"
  }
]
```

---

### Install Package
```
POST /api/packages/install
```
**Body:**
```json
{ "coordinate": "com.google.code.gson:gson:2.10.1" }
```

Downloads the JAR from Maven Central and adds it to all active JShell sessions.

**Response 201:** Returns the `PackageInfo` object.

**Response 400:** Invalid coordinate format.

**Response 500:** Download failed (package not found on Maven Central).

---

### Remove Package
```
DELETE /api/packages/{groupId}/{artifactId}/{version}
```
Example: `DELETE /api/packages/com.google.code.gson/gson/2.10.1`

**Response 200:**
```json
{
  "removed": true,
  "message": "Package removed. Restart JShell sessions to apply changes.",
  "coordinate": "com.google.code.gson:gson:2.10.1"
}
```

---

### Search Maven Central
```
GET /api/packages/search?q={query}
```
Proxies the Maven Central search API.

**Response 200:** Raw Maven Central search JSON (see search.maven.org docs).

---

## NuGet Package Manager

NuGet packages are used by C# and F# cells. Packages are stored in `data/nuget-packages.json` and
injected as `#r "nuget: PackageId, Version"` directives before each cell execution.

### List NuGet Packages
```
GET /api/nuget
```
**Response 200:**
```json
[
  {
    "packageId": "Newtonsoft.Json",
    "version": "13.0.3",
    "installedAt": "2026-04-16T10:00:00"
  }
]
```

---

### Install NuGet Package
```
POST /api/nuget/install
```
**Body:**
```json
{
  "packageId": "Newtonsoft.Json",
  "version": "13.0.3"
}
```
**Response 201:** The created `NuGetPackageInfo` object.
**Response 400:** Missing `packageId` or `version`.

---

### Remove NuGet Package
```
DELETE /api/nuget/{packageId}
```
Example: `DELETE /api/nuget/Newtonsoft.Json`

**Response 200:**
```json
{ "removed": true, "packageId": "Newtonsoft.Json", "message": "..." }
```
**Response 404:** Package not installed.

### PyPI (Python)

Manage packages for Python cells. Installed into `data/pypi-packages/site` (added to `PYTHONPATH`).

```
GET    /api/pypi/packages              # list installed
POST   /api/pypi/packages/install      # { "name": "requests", "version": "latest" }
DELETE /api/pypi/packages/{name}       # remove
GET    /api/pypi/packages/search?q=    # look up a package on PyPI (exact name)
GET    /api/pypi/status                # { "available": true, "version": "Python 3.12.4" }
```

**Install response 200:** `{ "name": "requests", "version": "2.31.0", "installedAt": "...", "paths": [...] }`

---

## AI Assistant

Arima Notebooks supports three AI providers: **Claude CLI**, **GitHub Copilot SDK**, and **Antigravity CLI** (`agy`). All `/api/llm/*` endpoints route to the currently active provider — no change to request format needed when switching.

### Get Active Provider
```
GET /api/llm/provider
```
Returns information about the active AI provider and its status.

**Response 200:**
```json
{
  "provider": "claude_cli",
  "label": "Claude",
  "model": "claude-sonnet-4-6",
  "available": true
}
```

`provider` values: `claude_cli` · `copilot_cli` (Copilot SDK) · `gemini_cli` (now the Antigravity CLI `agy`; key retained for compatibility)

---

### Chat
```
POST /api/llm/chat
```
Routes to the active AI provider. Request format is the same regardless of which provider is selected.

**Body (single message):**
```json
{ "message": "Explain Java streams" }
```

**Body (conversation):**
```json
{
  "history": [
    { "role": "user", "content": "What is JShell?" },
    { "role": "assistant", "content": "JShell is..." }
  ],
  "message": "How do I import classes in JShell?"
}
```

**Response 200:**
```json
{
  "response": "JShell allows you to import classes using...",
  "role": "assistant"
}
```

**Response 400:** Active CLI not found or not authenticated.

---

### Generate Notebook
```
POST /api/llm/generate
```
**Body:**
```json
{ "prompt": "A notebook about Java streams and collectors" }
```

**Response 200:** Returns a notebook JSON string (same format as GET /api/notebooks/{id}).

---

### Explain Code
```
POST /api/llm/explain
```
**Body:**
```json
{ "code": "list.stream().filter(x -> x > 0).collect(Collectors.toList())" }
```

**Response 200:**
```json
{ "explanation": "This code filters a list to only include positive numbers..." }
```

---

### Fix Error
```
POST /api/llm/fix
```
**Body:**
```json
{
  "code": "var x = \"hello\".toLower();",
  "error": "ERROR: cannot find symbol: method toLower()"
}
```

**Response 200:**
```json
{ "fix": "The method should be `toLowerCase()` not `toLower()`..." }
```

---

### Translate a Cell (Polyglot)

Renders one cell's source in another language, for the side-by-side comparison view.
The result is returned to the caller to store on the cell — the server does not persist it.

```
POST /api/llm/translate
```
**Body:**
```json
{
  "source": "squares = [n * n for n in nums]",
  "from": "python",
  "to": "java"
}
```

**Response 200** — a `CellTranslation`:
```json
{
  "source": "List<Integer> squares = nums.stream().map(n -> n * n).toList();",
  "generatedAt": "2026-08-25T18:04:00Z",
  "provider": "claude_cli",
  "sourceHash": "100c34b0d96789ef",
  "edited": false,
  "output": "",
  "error": "",
  "executed": false,
  "success": false,
  "executionTimeMs": null,
  "lastExecutedAt": ""
}
```

`sourceHash` is a prefix of the SHA-256 of the source it was generated from; when it no
longer matches the cell's current source the UI marks the translation stale. The run
fields are filled in by the client after executing the translation, and are saved with
the notebook so both languages' results and timings survive a reload.

**400** if `source`, `from` or `to` is missing, if either language is not comparable, or
if both are the same. **500** if the AI provider fails or returns no code block.

---

### List Comparable Languages
```
GET /api/llm/languages
```

**Response 200:**
```json
{ "languages": [ { "mode": "cpp", "label": "C++17" }, { "mode": "python", "label": "Python 3" } ] }
```

---

## Settings

### Get Settings
```
GET /api/settings
```
Returns current application settings.

**Response 200:**
```json
{
  "aiProvider": "claude_cli",
  "claudeModel": "claude-sonnet-4-6",
  "claudeMaxTokens": 4096,
  "githubCopilotModel": "gpt-4o",
  "geminiModel": "gemini-2.5-flash",
  "serverPort": 8585,
  "theme": "dark",
  "editorFontSize": 14,
  "showLineNumbers": true,
  "focusExecutingCell": true,
  "autoSaveIntervalSecs": 30,
  "maxExecutionTimeMs": 30000,
  "maxOutputLines": 1000
}
```

`aiProvider` values: `claude_cli` · `copilot_cli` · `gemini_cli`

---

### Update Settings
```
PUT /api/settings
```
**Body:** Partial or full settings object.

**Response 200:** Updated settings.

---

### Server Status
```
GET /api/settings/status
```
**Response 200:**
```json
{
  "version": "1.0.0",
  "javaVersion": "21.0.1",
  "javaHome": "/usr/lib/jvm/java-21-openjdk",
  "aiProvider": "claude_cli",
  "claudeCliAvailable": true,
  "claudeModel": "claude-sonnet-4-6",
  "githubCopilotAvailable": true,
  "githubCopilotStatus": "✓ Copilot SDK · GitHub Copilot CLI 1.0.69-0",
  "githubCopilotModel": "gpt-4o",
  "geminiCliAvailable": false,
  "geminiCliStatus": "✗ Antigravity CLI (agy) not found",
  "geminiModel": "gemini-2.5-flash",
  "theme": "dark",
  "dotnetAvailable": true,
  "cppAvailable": true,
  "cppCompilerDetail": "MSVC 19.40 (Visual Studio 2022)",
  "typescriptAvailable": true,
  "typescriptDetail": "Node v24.13.0 (built-in TS strip) + tsc Version 6.0.3 (type-check)",
  "tscAvailable": true
}
```

The `typescriptAvailable` flag reports whether Node ≥ 22.6 is on the PATH (required for the built-in type-stripping runtime). The optional `tscAvailable` flag indicates whether the `tsc` compiler is also present — if so, TS cells receive a pre-execution `tsc --noEmit` type-check pass.

---

## System

### Server Info
```
GET /api/system/info
```
Live metadata about the running server — the authoritative answer to *"is Arima up, since when, and what is it running?"*. The `arima` CLI calls this on a bare invocation and on `arima status`.

Unlike `GET /api/settings/status` (which reports configuration and AI-provider availability), this endpoint reports **runtime** state: JVM start time, uptime, the real JVM PID, live shell sessions, and notebook counts.

**Response 200:**
```json
{
  "name": "Arima Notebooks",
  "tagline": "A local-first, AI-native notebook for eight languages - run code, build pipelines, and drive it all over MCP.",
  "status": "running",
  "version": "4.0.1",
  "buildTimestamp": "2026-08-24T22:09:23Z",
  "startedAt": "2026-08-24 15:10:02 PDT",
  "startedAtEpochMs": 1787609402878,
  "uptimeMs": 18238,
  "uptime": "18s",
  "pid": 30104,
  "port": 8585,
  "url": "http://localhost:8585",
  "authMode": "local",
  "java":     { "version": "25", "vendor": "Oracle Corporation", "vm": "Java HotSpot(TM) 64-Bit Server VM", "home": "C:\Java\jdk-25" },
  "os":       { "name": "Windows 11", "version": "10.0", "arch": "amd64", "cpus": 12 },
  "memory":   { "usedMb": 40, "totalMb": 80, "maxMb": 16256 },
  "sessions": { "active": 0, "ids": [] },
  "notebooks":{ "tutorials": 54, "total": 88, "dir": "notebooks" },
  "mcp":      { "enabled": true, "protocol": "2024-11-05",
                "sse": "http://localhost:8585/api/mcp/sse",
                "messages": "http://localhost:8585/api/mcp/messages" },
  "languages": [
    { "name": "Java / JShell", "available": true, "detail": "25" },
    { "name": "JavaScript",    "available": true, "detail": "Node.js" },
    { "name": "TypeScript",    "available": true, "detail": "Node.js + tsc" },
    { "name": "C#",            "available": true, "detail": ".NET SDK" },
    { "name": "F#",            "available": true, "detail": "dotnet fsi" },
    { "name": "C++",           "available": true, "detail": "MSVC (Visual Studio Build Tools)" },
    { "name": "Python",        "available": true, "detail": "Python 3.14.2" }
  ]
}
```

`pid` is the JVM's own process id, which may differ from the launcher's wrapper PID. `startedAt` and `uptime` come from the JVM `RuntimeMXBean`, so they survive a launcher restart and never drift.

**Auth:** in the default `local` auth mode this endpoint is open. In `oauth` mode it requires authentication like every other `/api/**` route — CLI callers that get a `401` should fall back to `GET /actuator/health`, which stays open.

---

### Shutdown
```
POST /api/system/shutdown
```
Graceful shutdown — drains in-flight requests, then exits 0.

**Response 200:**
```json
{ "status": "shutting_down", "message": "Arima is shutting down" }
```

---

### Restart
```
POST /api/system/restart
```
Graceful self-restart. Writes an OS trampoline script that waits for port 8585 to be released, relaunches the JAR, and exits. If no runnable JAR is found the server exits with code **42** so an external watchdog (the `arima` launchers) relaunches it instead.

**Response 200:**
```json
{ "status": "restarting", "message": "Arima is restarting" }
```

---

## WebSocket (STOMP)

Connect via SockJS at `/ws`, then use STOMP.

### Subscribe to Shell Output
```
SUBSCRIBE /topic/shell/{sessionId}
```
Receives `ExecutionResult` JSON whenever code is executed in that session.

### Send Code for Execution
```
SEND /app/shell/{sessionId}
```
**Body:**
```json
{ "code": "System.out.println(\"hello\");", "cellId": "cell-2" }
```

Result is broadcast to all subscribers of `/topic/shell/{sessionId}`.

---

## MCP Server (Model Context Protocol)

Arima Notebooks implements MCP over HTTP+SSE transport (JSON-RPC 2.0). This lets any MCP-compatible AI client (Claude Desktop, Claude Code CLI, custom agents) use Arima Notebooks as a tool server — executing Java code, reading notebooks, running pipelines, and more.

### Connecting MCP Clients

#### Claude Desktop

Add to `claude_desktop_config.json` (see [SETUP.md — MCP section](SETUP.md#mcp-server-setup)):

```json
{
  "mcpServers": {
    "arima-notebooks": {
      "url": "http://localhost:8585/api/mcp/sse"
    }
  }
}
```

After saving, restart Claude Desktop. Arima Notebooks tools appear automatically in every conversation.

#### Claude Code CLI

Add to your MCP config (run `claude mcp add --help` for current syntax):

```bash
claude mcp add arima-notebooks --transport sse --url http://localhost:8585/api/mcp/sse
```

Or add manually to `~/.claude/settings.json` (macOS/Linux) or `%APPDATA%\claude\settings.json` (Windows):

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

Restart `claude` after editing. Verify with:

```bash
claude mcp list
```

#### Custom Agent / CLI Clients

Any client that supports MCP HTTP+SSE transport can connect directly:

1. **Open SSE stream**: `GET http://localhost:8585/api/mcp/sse` — the server sends an `endpoint` event with the POST URL
2. **Handshake**: send `initialize` then listen for `notifications/initialized`
3. **List tools**: send `tools/list` to discover all Arima Notebooks tools
4. **Call tools**: send `tools/call` with `name` and `arguments`

Example using `curl`:

```bash
# List available tools
curl -s -X POST http://localhost:8585/api/mcp/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Arima Notebooks must be running (`arima start` or `mvn spring-boot:run`) before connecting.

---

### SSE Stream
```
GET /api/mcp/sse?sessionId={optional-id}
```
Opens a Server-Sent Events stream. On connect, sends an `endpoint` event with the URL to POST messages to.

**Response:** `text/event-stream`

---

### Send Message
```
POST /api/mcp/messages?sessionId={optional-id}
```
Handles JSON-RPC 2.0 messages.

**Request body:** JSON-RPC 2.0 object
```json
{"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}
```

**Supported methods:**
- `initialize` — Return server info and protocol version
- `notifications/initialized` — Client acknowledgement (no-op)
- `ping` — Health check
- `tools/list` — List all available tools
- `tools/call` — Invoke a tool by name

---

### Available MCP Tools

| Tool | Required Params | Description |
|---|---|---|
| `barista_execute_code` | `code` | Execute Java in a JShell session |
| `barista_list_notebooks` | *(none)* | List all notebooks |
| `barista_read_notebook` | `notebookId` | Read all cells from a notebook |
| `barista_run_pipeline` | `notebookId`, `cellId` | Run a pipeline cell |
| `barista_search_cells` | `query` | Search cells by content or anchor |
| `barista_load_module` | `notebookRef` | Load `notebookId/anchor` into session |
| `barista_create_notebook` | `name` | Create new notebook with optional cells |
| `barista_append_cell` | `notebookId`, `source` | Append a cell, optionally execute |
| `barista_list_agents` | *(none)* | List agent & skill definitions (yours + samples) |
| `barista_run_agent` | `agentId`, `task` | Run an agent/skill against a task, return its response |
| `barista_list_tools` | *(none)* | List the tools authored as notebooks, with their parameters |
| `barista_invoke_tool` | `toolId` | Call a tool by id with an `args` map |
| `arima_<tool_name>` | *the tool's own* | One entry per authored tool, carrying its real JSON Schema |

The `arima_<tool_name>` entries are generated from the tool catalog on every `tools/list`, so a
client sees `arima_word_count(text, detailed)` rather than a generic dispatcher — authoring a tool
in Arima is all it takes to give every connected MCP client a new callable tool. A tool with no
code cells yet is omitted (there is nothing to call).

---

### Connect Claude Desktop
Add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "arima-notebooks": {
      "url": "http://localhost:8585/api/mcp/sse"
    }
  }
}
```

---

### Example: Create Notebook via MCP
```bash
curl -X POST http://localhost:8585/api/mcp/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1,
    "method": "tools/call",
    "params": {
      "name": "barista_create_notebook",
      "arguments": {
        "name": "My Agent Notebook",
        "description": "Created by an AI agent",
        "cells": [
          {"type": "MARKDOWN", "source": "# Hello from Agent"},
          {"type": "CODE", "source": "System.out.println(\"Agent was here!\");", "anchor": "hello"}
        ]
      }
    }
  }'
```

---

### Example: Append and Execute a Cell
```bash
curl -X POST http://localhost:8585/api/mcp/messages \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 2,
    "method": "tools/call",
    "params": {
      "name": "barista_append_cell",
      "arguments": {
        "notebookId": "your-notebook-id",
        "source": "var result = 42 * 2; System.out.println(result);",
        "anchor": "compute",
        "execute": true,
        "session": "agent-session"
      }
    }
  }'
```
