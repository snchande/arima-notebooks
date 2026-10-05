# The Agent Factory — Design v1

> Local design record. Completes the agentic authoring surface started by
> [`agents-arena-v1.md`](agents-arena-v1.md): **agents · skills · tools · plugins**, and a real
> **deploy** verb.

## What already shipped (Agents Arena v0/v1)

| Piece | State |
|---|---|
| Agent & skill definitions as notebooks (`metadata.kind`) | done |
| Agent-mode cells (`mode:"agent"`, `//@ agent:`, `//@ bind:`, `{{anchor}}`) | done |
| Agents tab (browse / create / run, "running now" strip) | done |
| MCP `barista_list_agents` / `barista_run_agent` | done |
| Export → `.claude/agents/<slug>.md`, `.claude/skills/<slug>/SKILL.md` | done (Claude only) |
| Tutorials agent-101…601, skill-101 | done |

## The four gaps this design closes

1. **Tools were never real.** `metadata.agent.tools` was a list of free-text names, typed into a
   prompt as *"You may use these tools: …"*. You could not **author** a tool, give it a signature,
   run it, or let an agent actually call it.
2. **Plugins did not exist.** No way to bundle an agent + its skills + its tools into one
   installable unit.
3. **Deploy did not exist.** `POST /api/agents/export` wrote into the Arima repo root only, had no
   target choice, no record of what was deployed, no undeploy — and NPE'd when `BARISTA_HOME` was
   unset (`BaristaHome.directory()` returns null).
4. **Two of the three providers were fiction.** The UI offered `copilot` and `gemini`
   (Antigravity) in its provider dropdowns, but only `ClaudeAgentProvider` existed as a bean, so
   picking either failed with *"Unknown provider"*.

## The model: four kinds, one storage format

Still **no new storage format and no new `CellType`** — a definition is a normal notebook with a
`metadata.kind` flag, projected into a spec record by its service.

| `metadata.kind` | Is | Instructions from | Implementation from |
|---|---|---|---|
| `agent` | a prompt + tool grants | markdown cells | — |
| `skill` | instructions only | markdown cells | — |
| `tool` | a **callable function** | markdown cells (docs) | **code cells, any of the 8 languages** |
| `plugin` | a bundle of the above | markdown cells (README) | `metadata.plugin.members` |

### Tools — `metadata.tool`

```json
"tool": {
  "mode": "python",
  "params": [
    { "name": "ticker", "type": "string",  "description": "Symbol", "required": true },
    { "name": "days",   "type": "integer", "description": "Window", "required": false, "default": "30" }
  ]
}
```

`ToolService.invoke(id, args)` validates the args against `params`, emits a **language-native
preamble** declaring each param as a local variable, concatenates the notebook's code cells, and
runs the whole thing through that language's existing execution service. The tool's stdout is its
return value. One notebook = one tool; `type` is one of `string · integer · number · boolean`.

### Plugins — `metadata.plugin`

```json
"plugin": { "version": "1.0.0", "members": ["agent-201", "skill-101", "my-tool"] }
```

A plugin **builds** into a Claude Code plugin directory:

```
<target>/plugins/<slug>/
  .claude-plugin/plugin.json     name, version, description, author
  agents/<slug>.md               each member agent (frontmatter + body)
  skills/<slug>/SKILL.md         each member skill
  commands/<slug>.md             one per member tool — invokes it over Arima's MCP server
  .mcp.json                      points the plugin at Arima's own MCP endpoint
  README.md                      the plugin notebook's markdown cells
```

Member tools are *not* re-implemented in the bundle — they stay in Arima and are reached over the
MCP server already in-process. That keeps one source of truth and adds no outbound host.

## Deploy

`DeploymentService` owns three targets:

| Target | Root | Meaning |
|---|---|---|
| `project` | repo root `.claude/` | this checkout only |
| `user` | `~/.claude/` | every project on this machine |
| `bundle` | `data/plugins/<slug>/` | staged copy, nothing installed |

Every write is recorded in `data/deployments.json` (`{id, kind, slug, target, paths[], deployedAt}`),
which makes `GET /api/agents/deployments` and `POST /api/agents/undeploy` possible — undeploy
removes exactly the paths Arima recorded, never anything else.

## API

```
GET  /api/tools/list                   list tool definitions (mine + samples)
POST /api/tools/create                 { name } -> new tool notebook
GET  /api/tools/{id}/schema            JSON-Schema projection of its params
POST /api/tools/invoke                 { toolId, args, sessionId? } -> output

GET  /api/plugins/list                 list plugin definitions
POST /api/plugins/create               { name } -> new plugin notebook
POST /api/plugins/deploy               { pluginId, target } -> written paths

POST /api/agents/deploy                { notebookId, target, provider } -> written paths
GET  /api/agents/deployments           what is currently deployed, per target
POST /api/agents/undeploy              { id, target }
```

`POST /api/agents/export` stays as a thin alias of `deploy{target:"project"}` so nothing that
already calls it breaks.

## MCP

Tools authored in Arima become **callable by any MCP client** — that is what "deploy a tool" means:

- `barista_list_tools` — the catalog, with each tool's parameter schema.
- `barista_invoke_tool` — `{ toolId, args }`.
- plus one **dynamic entry per tool**, named `arima_<slug>`, advertised in `tools/list` with the
  tool's own JSON Schema, so a client sees `arima_fetch_quote(ticker, days)` rather than a generic
  dispatcher.

## Providers

`CopilotAgentProvider` (`copilot`) and `AntigravityAgentProvider` (`gemini`) join
`ClaudeAgentProvider`, each wrapping the chat service already in the tree
(`GitHubCopilotService`, `GeminiService`). Neither CLI streams through our seam yet, so `run`
delivers the response as one chunk to the sink. Export writes each system's native layout:
`.github/agents/<slug>.md` for Copilot, `.antigravity/agents/<slug>.md` for Antigravity.

## Guardrails

Additive throughout: no new `CellType`, no model rewrite, no new outbound host, no new WebSocket
topic (tool runs and agent runs share `partial_output`). Touches the MCP server and adds services
that call the eight execution services — both flagged in AGENTS.md §2.2 and confirmed with the
owner before building. `BaristaHome.directory()` gains a `user.dir` fallback so deploys work under
`mvn spring-boot:run`.

## Tutorials

`tool-101` (author & call a tool) and `plugin-101` (bundle and deploy) extend the Agents & Skills
track.
