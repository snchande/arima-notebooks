# The Agents Arena — Design v1

> Local design record. v0 (merged) let you author/run/export an agent as a notebook.
> v1 makes agents first-class, composable units that interwork with code.

## The reframe: definition vs invocation

- **Definition** — an agent notebook (`metadata.kind="agent"`) or a built-in sample. It *is* the agent:
  system prompt (its markdown cells), provider, tools. Browsed/edited in the Agents arena.
- **Invocation** — a new **agent-mode cell** (`mode:"agent"`) that lives in *any* notebook, references
  an agent, takes an input, and produces a normal cell output.

Because an agent cell produces a cell output, it sits in the same `//@ anchor` dependency graph as code
cells — so code and agents interwork in pipelines for free, and MCP's `barista_run_pipeline` already runs
the mixed workflow.

## Interwork DSL (agent-mode cell)

```
//@ anchor: review
//@ depends: sample-code
//@ agent: agent-201        # which agent definition to invoke (notebook id or built-in sample)
//@ bind: reviewText        # bind the response to a JShell String var for downstream code cells
Review this method for bugs:
{{sample-code}}             # templates in the output of the `sample-code` anchor
```

- `{{anchor}}` → replaced with that anchor cell's current output (fresh within a pipeline run).
- `//@ bind: name` → after the agent runs, `String name = "<response>";` is injected into the JShell
  session, so the next Java cell can read it.

## Phases

1. **Agent cells + interwork** (this PR) — `mode:"agent"` executor wired into `ShellController`
   (single-cell) and `OrchestrationService.executeSingleCell` (pipeline); `AgentService.runAgentCell`
   does directive parse + `{{anchor}}` resolve + provider run + `//@ bind`. Samples **agent-401**
   (reviewer in a pipeline), **agent-501** (multi-agent review).
2. **The Agents tab** — a browse/edit/run arena over agent notebooks + samples, with a "running now"
   view. New `agents-tab.js` following the add-a-UI-tab convention.
3. **MCP** — `barista_list_agents` + `barista_run_agent`. Sample **agent-601** (MCP-driven).

## Tutorial series (Agents 101 → 601)

| Level | Sample | Teaches |
|------|--------|---------|
| 101 | Explain Code | basic prompt agent |
| 201 | Code Reviewer | declaring & using tools |
| 301 | Test Writer | multi-step workflow |
| 401 | Reviewer in a Pipeline | `{{anchor}}` + `//@ bind` interwork |
| 501 | Multi-Agent Review | code + multiple agents in one graph |
| 601 | MCP-driven agent | composing Arima agents from an external client |

Skills mirror this (`skill-101` shipped; 201/301 follow).

## Guardrails

Additive: no new `CellType` (agent is a cell **mode**), no model change. Phase 1 touches the orchestration
DSL and Phase 3 the MCP server (both AGENTS.md §2.2) — confirmed with the owner before building. No new
outbound host or WebSocket topic; reuses the `partial_output` stream and the CLI-subprocess providers.
