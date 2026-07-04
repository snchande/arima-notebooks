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
{ "name": "My New Notebook" }
```

**Response 201:** Returns the created notebook.

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

**How Arima resolves cross-notebook refs at execution time:**

| Language | Resolution |
|----------|-----------|
| JShell / Java | The foreign cell's source is executed in the current JShell session |
| C# / F# | Arima builds an **expanded source** (full transitive dep chain, annotation-stripped) and injects it with output suppressed before the current cell's code |

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

---

## AI Assistant

Arima supports three AI providers: **Claude CLI**, **GitHub Copilot SDK**, and **Antigravity CLI** (`agy`). All `/api/llm/*` endpoints route to the currently active provider — no change to request format needed when switching.

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

Arima implements MCP over HTTP+SSE transport (JSON-RPC 2.0). This lets any MCP-compatible AI client (Claude Desktop, Claude Code CLI, custom agents) use Arima as a tool server — executing Java code, reading notebooks, running pipelines, and more.

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

After saving, restart Claude Desktop. Arima tools appear automatically in every conversation.

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
3. **List tools**: send `tools/list` to discover all Arima tools
4. **Call tools**: send `tools/call` with `name` and `arguments`

Example using `curl`:

```bash
# List available tools
curl -s -X POST http://localhost:8585/api/mcp/messages \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Arima must be running (`arima start` or `mvn spring-boot:run`) before connecting.

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
