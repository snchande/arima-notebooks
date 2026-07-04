# .claude/ — Arima Notebooks Claude Code Configuration

This directory is the **central registration** for Arima's Claude Code integration: slash commands, skills, subagents, and project settings. Files here are checked into the repo so every contributor gets the same set automatically when they open Arima in Claude Code.

## Layout

```
.claude/
├── README.md                          # this file
├── settings.json                      # permissions, env vars, registry
├── settings.local.json                # per-developer overrides (gitignored)
│
├── commands/                          # slash commands
│   ├── start.md                       # /start — start the arima server
│   ├── build.md                       # /build — build the JAR
│   └── create-notebook.md             # /create-notebook — scaffold a .vnb
│
├── skills/                            # workflow skills (auto-invoked when relevant)
│   ├── architecture-check/SKILL.md    # verify layered architecture
│   ├── add-execution-language/SKILL.md# scaffold a new runtime
│   ├── add-tutorial/SKILL.md          # author a .vnb tutorial
│   └── add-rest-endpoint/SKILL.md     # add a REST endpoint
│
└── agents/                            # subagents
    ├── barista.md                       # PRIMARY front-door agent — welcome + full functionality
    ├── barista-architect.md             # architecture reviewer (read-only)
    ├── barista-security.md              # triages security-check findings
    └── arima-tutorial-writer.md       # writes tutorial notebooks
```

## The `arima` agent (start here)

`arima` is the **primary, full-functionality** agent — the single front door for using, operating,
documenting, and extending Arima. It delivers the common welcome (open the UI · drive Arima over MCP ·
personalize & extend), opens the right docs, and — because it can write code — guides feature changes
while obeying [`../AGENTS.md`](../AGENTS.md). It delegates focused work to the three specialists
(`barista-architect`, `barista-security`, `arima-tutorial-writer`).

The same agent persona is described for **all three agentic CLIs** so the experience is consistent:
[`../CLAUDE.md`](../CLAUDE.md), [`../.github/copilot-instructions.md`](../.github/copilot-instructions.md),
and [`../GEMINI.md`](../GEMINI.md). The terminal equivalent is the `arima welcome` command; the canonical
content lives in [`../docs/WELCOME.md`](../docs/WELCOME.md).

## Skills vs. agents — when each fires

- **Skills** are triggered automatically by Claude when a request matches the skill's description. You don't need to name them. They run *in* the main conversation.
- **Agents** are spawned explicitly. Use them when you want a focused, scoped review (e.g. *"hand this proposal to `barista-architect` for review"*). They run in their own context and return a summary.

## Adding a skill

1. Create `.claude/skills/<name>/SKILL.md` with YAML frontmatter (`name`, `description`) and a body describing when to invoke and how to run.
2. Add `"<name>"` to `skills.enabled` in `settings.json`.
3. Document the skill in this README's layout table.

## Adding an agent

1. Create `.claude/agents/<name>.md` with YAML frontmatter (`name`, `description`, `tools`).
2. Add `"<name>"` to `agents.enabled` in `settings.json`.
3. Document the agent in this README's layout table.

## See also

- [`../AGENTS.md`](../AGENTS.md) — architecture guardrails every contributor (human or AI) must follow.
- [`../CLAUDE.md`](../CLAUDE.md) — Claude-specific instructions.
- [`../.github/copilot-instructions.md`](../.github/copilot-instructions.md) — Copilot equivalent.
- [`../GEMINI.md`](../GEMINI.md) — Antigravity (`agy`) equivalent (formerly Gemini CLI).

— Suresh Chande, founding contributor
