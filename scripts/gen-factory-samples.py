"""Generate the tool-101 and plugin-101 sample definitions.

Run once from the repo root: `python scripts/gen-factory-samples.py`. Kept as a script rather than
hand-edited JSON so the two samples stay consistent with the .anb shape (and can be regenerated if
the format moves).
"""
import json
import pathlib

TUTORIALS = pathlib.Path("notebooks/tutorials")


def cell(cid, ctype, source, mode="jshell", anchor=None):
    c = {
        "id": cid,
        "type": ctype,
        "mode": mode,
        "source": source,
        "output": "",
        "executed": False,
    }
    if anchor:
        c["anchor"] = anchor
    return c


tool_101 = {
    "id": "tool-101",
    "name": "Tool 101 \u00b7 Word Count",
    "description": "Beginner tool \u2014 counts words, lines and characters in a block of text. "
                   "Shows how a notebook becomes a callable tool with a real signature.",
    "metadata": {
        "kind": "tool",
        "category": "tutorial",
        "subcategory": "Agents & Skills",
        "language": "agent",
        "level": 101,
        "tool": {
            "mode": "python",
            "params": [
                {
                    "name": "text",
                    "type": "string",
                    "description": "The text to measure",
                    "required": True,
                },
                {
                    "name": "detailed",
                    "type": "boolean",
                    "description": "Also list the five most frequent words",
                    "required": False,
                    "default": "false",
                },
            ],
        },
    },
    "cells": [
        cell("t101-what", "MARKDOWN",
             "# Word Count\n\nA **tool** is a notebook you can *call*. Three things make it one:\n\n"
             "1. `metadata.kind = \"tool\"` \u2014 set for you when you create it.\n"
             "2. A **signature** \u2014 the parameter table in the banner above.\n"
             "3. A **body** \u2014 the code cells below, in any of the eight languages.\n\n"
             "Before the body runs, every parameter is bound as a variable of the same name. "
             "This tool declares `text` (required) and `detailed` (optional), so the Python body "
             "below can just use them. Whatever the tool prints is what the caller gets back."),
        cell("t101-body", "CODE",
             "words = text.split()\n"
             "lines = text.count(\"\\n\") + 1 if text else 0\n"
             "\n"
             "print(f\"words:      {len(words)}\")\n"
             "print(f\"lines:      {lines}\")\n"
             "print(f\"characters: {len(text)}\")\n"
             "\n"
             "if detailed and words:\n"
             "    from collections import Counter\n"
             "    top = Counter(w.lower().strip(\".,!?;:'\\\"\") for w in words).most_common(5)\n"
             "    print(\"\\ntop words:\")\n"
             "    for word, n in top:\n"
             "        print(f\"  {word:<15} {n}\")",
             mode="python", anchor="count"),
        cell("t101-call", "MARKDOWN",
             "## Calling it\n\nThree ways, all the same tool:\n\n"
             "- **Here** \u2014 the *\u25b6 Call tool* dock below fills in from the signature.\n"
             "- **From the Agents tab** \u2014 the tool's card has its own call form.\n"
             "- **From any MCP client** \u2014 it is advertised as `arima_tool_101_word_count` with "
             "this exact schema, so Claude Code can call it directly. `barista_list_tools` shows "
             "the catalog.\n\n"
             "An **agent** reaches it the same way: grant the tool by name in the agent's banner, "
             "and the agent's prompt gains the signature and the MCP name."),
        cell("t101-next", "MARKDOWN",
             "## Next\n\n- Change `detailed` to a `string` and switch on it \u2014 the binding "
             "follows the declared type.\n"
             "- Rewrite the body in another language (pick one in the banner): the parameters are "
             "declared in whatever language you choose.\n"
             "- Bundle it: add this tool as a member of a **plugin** (see `plugin-101`) and deploy, "
             "and your CLI gets a slash command that calls it."),
    ],
}

plugin_101 = {
    "id": "plugin-101",
    "name": "Plugin 101 \u00b7 Review Kit",
    "description": "Beginner plugin \u2014 bundles a code-reviewer agent, a commit-message skill and "
                   "the word-count tool into one installable Claude Code plugin.",
    "metadata": {
        "kind": "plugin",
        "category": "tutorial",
        "subcategory": "Agents & Skills",
        "language": "agent",
        "level": 101,
        "plugin": {
            "version": "1.0.0",
            "author": "Arima Notebooks",
            "members": ["agent-201", "skill-101", "tool-101"],
        },
    },
    "cells": [
        cell("p101-what", "MARKDOWN",
             "# Review Kit\n\nA **plugin** is how a definition leaves Arima and becomes part of a "
             "CLI. It bundles members \u2014 agents, skills and tools \u2014 into one directory your "
             "agentic CLI reads.\n\nThis one ships three, named in the banner above:\n\n"
             "| Member | Kind | Gives you |\n|---|---|---|\n"
             "| `agent-201` | \U0001F916 agent | a code reviewer |\n"
             "| `skill-101` | \u2b50 skill | Conventional Commits messages |\n"
             "| `tool-101` | \U0001F527 tool | word/line/char counts |"),
        cell("p101-build", "MARKDOWN",
             "## What Deploy writes\n\nPick a target in the banner and press **\u2904 Deploy**:\n\n"
             "```\nplugins/plugin-101-review-kit/\n"
             "  .claude-plugin/plugin.json   name, version, description, author\n"
             "  agents/<name>.md             each member agent, frontmatter + instructions\n"
             "  skills/<name>/SKILL.md       each member skill\n"
             "  commands/<name>.md           one per member tool\n"
             "  .mcp.json                    points back at Arima's MCP server\n"
             "  README.md                    these markdown cells\n```\n\n"
             "Member **tools are not copied**. They stay here, and the generated command calls them "
             "over Arima's MCP server \u2014 one source of truth, and editing the tool notebook "
             "changes the deployed behaviour with no redeploy."),
        cell("p101-targets", "MARKDOWN",
             "## The three targets\n\n"
             "| Target | Lands in | Use when |\n|---|---|---|\n"
             "| **project** | the repo root's `.claude/` | the bundle is for this checkout |\n"
             "| **user** | `~/.claude/` | you want it in every project on this machine |\n"
             "| **bundle** | `data/plugins/` | you just want to read what would be written |\n\n"
             "Every deploy is recorded, so the **Deployed** section in the Agents tab can undo it \u2014 "
             "it removes exactly the files Arima wrote, and nothing else."),
        cell("p101-next", "MARKDOWN",
             "## Next\n\n- Create your own plugin (**+ New Plugin**), add one of your agents as a "
             "member, and deploy to `bundle` first to inspect the output.\n"
             "- Bump `version` in the banner and redeploy \u2014 the old files are replaced, and a "
             "member you removed is cleaned up rather than orphaned.\n"
             "- Deploy an agent on its own (the \u2904 button on its card) when you want just the one "
             "file instead of a bundle."),
    ],
}

for sample in (tool_101, plugin_101):
    path = TUTORIALS / f"{sample['id']}.anb"
    path.write_text(json.dumps(sample, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {path}")
