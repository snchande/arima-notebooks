# Welcome to Arima Notebooks

> This is the **canonical welcome**. The `arima welcome` CLI command and the **arima** AI agent both deliver this same experience — so whether you arrive through the terminal or through an AI co-pilot, you get one consistent starting point.

Arima is a local, browser-based notebook for **seven languages** (JShell · Java · JavaScript · TypeScript · C# · F# · C++) with three AI co-pilots wired in and the **whole system exposed over MCP**. Everything runs on your machine.

---

## Pick how you want to work

There are three ways to work with Arima. You can mix them freely.

### 1 · Open the UI (the full notebook experience)

The richest way to use Arima — cells, live output, package managers, the AI panel, tutorials.

```bash
arima start          # builds if needed, starts the server, opens the browser
# already running?   ->  arima open
```

Then visit **http://localhost:8585**. In the UI you can press **Ctrl+\\** for the AI panel and open the in-app **docs overlay** for guidance without leaving the page.

### 2 · Drive Arima from the CLI via MCP (operate & automate)

Arima runs an **MCP (Model Context Protocol) server** while it's up, so any MCP-aware client — Claude Code, Claude Desktop, or a custom agent — can drive it programmatically:

- **SSE stream:** `GET  http://localhost:8585/api/mcp/sse`
- **Messages:**   `POST http://localhost:8585/api/mcp/messages`

Available MCP tools: `barista_execute_code`, `barista_list_notebooks`, `barista_read_notebook`,
`barista_run_pipeline`, `barista_search_cells`, `barista_load_module`, `barista_create_notebook`,
`barista_append_cell`.

The plain **`arima` command line** manages the lifecycle (`start` · `stop` · `status` · `open` · `logs`) and shows you how to connect. **It does not modify Arima's own code** — it operates Arima, it doesn't personalize it.

### 3 · Personalize & extend Arima (only via an agentic CLI)

This is the part a plain CLI can't do. Run your favorite **agentic CLI** inside the repo —
`claude`, `copilot`, or `agy` (Antigravity) — and the **arima** agent helps you change and grow Arima itself:
add a language, tweak the theme, write a tutorial, fix a bug. It follows the architecture
guardrails in [`AGENTS.md`](../AGENTS.md), verifies locally, runs the security check, and can
package the change as a PR.

```bash
claude        # or: copilot   /   agy   — run from the repo root
```

> **The one difference that matters:** the plain `arima` CLI lets you **operate and automate** Arima (including over MCP). An **agentic CLI** lets you do all of that **and personalize/extend** Arima — because it can write code, and Arima is built to be reshaped by the people who use it.

---

## Documentation

| What | Where | Open it |
|------|-------|---------|
| Product brochure (12-page PDF) | `docs/brochure/arima-brochure.pdf` | `arima docs` |
| Getting started + features | `README.md` | `arima docs` |
| Architecture (diagrams, layers) | `docs/ARCHITECTURE.md` | `arima docs` |
| API + MCP reference | `docs/API.md` | `arima docs` |
| AI-contributor guardrails | `AGENTS.md` | `arima docs` |
| In-app cheat sheet | `docs/cheatsheet.html` | open in browser |
| Live, in-UI docs overlay | the running app | press the docs button in the UI |

`arima docs` opens the brochure and prints links to the rest. In an agentic CLI, just ask:
*"open the Arima architecture docs"* or *"show me how MCP works in Arima"* and the arima agent
will surface the right file.

---

## Quick reference

```
arima welcome     show this welcome and your options
arima start       start the server + open the UI
arima open        open the UI (server already running)
arima status      server state + detected runtimes + AI co-pilots
arima agents      AI co-pilots, skills & the arima agent wired into this repo
arima docs        open the brochure and list the docs
arima stop        stop the server
```

Need to extend Arima? Start an agentic CLI in this folder and say *"help me get started with Arima."*
