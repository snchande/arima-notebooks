"""Generate the Agents tutorial track: five sections, each running 101 to 501.

Run from the repo root: `python scripts/gen-agent-tutorials.py`

The Agents track is one tutorial tab with five sections, one per thing you can build:

    Tools       tool-101 .. tool-501
    Skills      skill-101 .. skill-501
    Agents      agent-101 .. agent-601      (authored earlier; this script only re-tags them)
    Connectors  connector-101 .. connector-501
    Plugins     plugin-101 .. plugin-501

Every section's 501 is its deployment lesson: how that artifact reaches the agentic CLIs on this
machine (Claude Code, GitHub Copilot, Antigravity) rather than staying inside Arima.

Kept as a script rather than hand-edited JSON so the whole track stays consistent in shape, and can
be regenerated if the .anb format moves.
"""
import json
import pathlib

TUTORIALS = pathlib.Path("notebooks/tutorials")

AGENT_ICON = "\U0001F916"
SKILL_ICON = "⭐"
TOOL_ICON = "\U0001F527"
PLUG_ICON = "\U0001F50C"
PKG_ICON = "\U0001F4E6"


def md(cid, source):
    return {"id": cid, "type": "MARKDOWN", "mode": "jshell", "source": source,
            "output": "", "executed": False}


def code(cid, source, mode, anchor=None):
    c = {"id": cid, "type": "CODE", "mode": mode, "source": source,
         "output": "", "executed": False}
    if anchor:
        c["anchor"] = anchor
    return c


def notebook(nid, name, description, section, level, cells, extra_meta=None, kind=None):
    meta = {
        "category": "tutorial",
        "subcategory": section,
        "language": "agent",
        "level": level,
    }
    if kind:
        meta["kind"] = kind
    if extra_meta:
        meta.update(extra_meta)
    return {"id": nid, "name": name, "description": description, "metadata": meta, "cells": cells}


def tool_meta(mode, params):
    return {"tool": {"mode": mode, "params": params}}


def param(name, ptype, desc, required=True, default=None):
    p = {"name": name, "type": ptype, "description": desc, "required": required}
    if default is not None:
        p["default"] = default
    return p


# ---------------------------------------------------------------------------
# Tools: 101 -> 501
# ---------------------------------------------------------------------------

TOOL_101 = notebook(
    "tool-101", "Tool 101 · Word Count",
    "Beginner tool — counts words, lines and characters in a block of text. Shows how a "
    "notebook becomes a callable tool with a real signature.",
    "Tools", 101, kind="tool",
    extra_meta=tool_meta("python", [
        param("text", "string", "The text to measure"),
        param("detailed", "boolean", "Also list the five most frequent words", False, "false"),
    ]),
    cells=[
        md("t101-what",
           "# Word Count\n\n"
           "A **tool** is a notebook you can *call*. Three things make it one:\n\n"
           "1. `metadata.kind = \"tool\"` — set for you when you create it.\n"
           "2. A **signature** — the parameter table in the banner above.\n"
           "3. A **body** — the code cells below, in any of the eight languages.\n\n"
           "Before the body runs, every parameter is bound as a variable of the same name. This "
           "tool declares `text` (required) and `detailed` (optional), so the Python body below "
           "can just use them. Whatever the tool prints is what the caller gets back."),
        code("t101-body",
             'words = text.split()\n'
             'lines = text.count("\\n") + 1 if text else 0\n'
             '\n'
             'print(f"words:      {len(words)}")\n'
             'print(f"lines:      {lines}")\n'
             'print(f"characters: {len(text)}")\n'
             '\n'
             'if detailed and words:\n'
             '    from collections import Counter\n'
             '    top = Counter(w.lower().strip(".,!?;:\'\\"") for w in words).most_common(5)\n'
             '    print("\\ntop words:")\n'
             '    for word, n in top:\n'
             '        print(f"  {word:<15} {n}")',
             "python", anchor="count"),
        md("t101-call",
           "## Calling it\n\nThree ways, all the same tool:\n\n"
           "- **Here** — the *Call tool* dock below fills itself in from the signature.\n"
           "- **From the Agents tab** — the tool's card has its own call form.\n"
           "- **From any MCP client** — it is advertised as `arima_tool_101_word_count` with "
           "this exact schema, so Claude Code can call it directly. `barista_list_tools` shows the "
           "whole catalog.\n\n"
           "An **agent** reaches it the same way: grant the tool by name in the agent's banner, and "
           "the agent's prompt gains the signature and the MCP name. That is `tool-401`."),
        md("t101-try",
           "## Try it\n\n"
           "1. Put a paragraph in the `text` box below and press **Call**.\n"
           "2. Flip `detailed` to `true` and call it again.\n"
           "3. Now clear `text` entirely and call it — you get "
           "*\"requires parameter 'text'\"* rather than a crash, because the parameter is marked "
           "required. Validation happens before a line of your body runs.\n\n"
           "Next: `tool-201` on the four parameter types and what defaults really do."),
    ])

TOOL_201 = notebook(
    "tool-201", "Tool 201 · Typed Parameters",
    "Intermediate tool — the four parameter types, required versus optional, defaults, and "
    "what Arima does with a bad argument before your code ever runs.",
    "Tools", 201, kind="tool",
    extra_meta=tool_meta("nodejs", [
        param("amount", "number", "The amount to convert"),
        param("rate", "number", "Units of target per unit of source", False, "0.92"),
        param("places", "integer", "Decimal places in the result", False, "2"),
        param("verbose", "boolean", "Show the working", False, "false"),
    ]),
    cells=[
        md("t201-types",
           "# Typed Parameters\n\n"
           "A tool's signature is not documentation — it is enforced. Four types:\n\n"
           "| Type | Accepts | Bound as |\n|---|---|---|\n"
           "| `string` | anything | the text |\n"
           "| `integer` | whole numbers | a 64-bit integer |\n"
           "| `number` | decimals | a double |\n"
           "| `boolean` | `true` / `false` | a real boolean |\n\n"
           "Arima coerces each argument to its declared type **before** the body runs. Hand "
           "`places` the value `\"abc\"` and the call fails with *\"Parameter 'places' expects an "
           "integer\"* — your code never sees it."),
        md("t201-required",
           "## Required, optional, default\n\n"
           "Each parameter is one of three things:\n\n"
           "- **Required** (`req` ticked, no default) — omit it and the call is refused.\n"
           "- **Optional with a default** — omit it and the default is used. `rate` below "
           "defaults to `0.92`.\n"
           "- **Optional with no default** — omit it and you get the type's zero: `\"\"`, "
           "`0`, `0.0`, `false`.\n\n"
           "The third case is the one that bites. An optional `number` you forgot to give a "
           "default silently arrives as `0`, and a division quietly produces `Infinity`. If a "
           "parameter has a sensible value, **write the default down**."),
        code("t201-body",
             'const converted = amount * rate;\n'
             'const result = converted.toFixed(places);\n'
             '\n'
             'if (verbose) {\n'
             '  console.log(`${amount} x ${rate} = ${converted}`);\n'
             '  console.log(`rounded to ${places} place(s):`);\n'
             '}\n'
             'console.log(result);',
             "nodejs", anchor="convert"),
        md("t201-schema",
           "## The signature is the API\n\n"
           "That table becomes a JSON Schema — `GET /api/tools/tool-201/schema` returns it, "
           "and the MCP server hands the same schema to every connected client:\n\n"
           "```json\n"
           "{\n"
           "  \"type\": \"object\",\n"
           "  \"properties\": {\n"
           "    \"amount\": { \"type\": \"number\", \"description\": \"The amount to convert\" },\n"
           "    \"rate\":   { \"type\": \"number\", \"description\": \"Units of target per unit of source\" }\n"
           "  },\n"
           "  \"required\": [\"amount\"]\n"
           "}\n```\n\n"
           "Which means **the `description` column is not a comment**. It is what an agent reads to "
           "decide what to pass. `\"Units of target per unit of source\"` tells a model something; "
           "`\"the rate\"` tells it nothing."),
        md("t201-try",
           "## Try it\n\n"
           "1. Call with `amount = 100` and nothing else — the defaults fill in.\n"
           "2. Set `places` to `0` and call again.\n"
           "3. Tick `verbose` and watch the working appear.\n"
           "4. Now untick **req** on `amount`, remove its value, and call — you get `0.00` "
           "instead of an error. That is the silent-zero trap. Put `req` back.\n\n"
           "Next: `tool-301` writes the same tool in four other languages."),
    ])

TOOL_301 = notebook(
    "tool-301", "Tool 301 · Any of the Eight Languages",
    "Intermediate tool — a tool body can be written in any language Arima executes. How the "
    "binding differs, what each language is good for, and how to choose.",
    "Tools", 301, kind="tool",
    extra_meta=tool_meta("jshell", [
        param("n", "integer", "How many Fibonacci numbers to produce", True, "10"),
    ]),
    cells=[
        md("t301-idea",
           "# Any of the Eight Languages\n\n"
           "The **language** picker in the banner is not cosmetic. It decides which execution "
           "service runs the body — the very same ones your ordinary notebook cells use — "
           "and how the parameters are declared before it.\n\n"
           "The caller cannot tell. A Python tool and a C++ tool present the identical JSON Schema "
           "over MCP. Pick the language that suits the *job*, not the caller."),
        md("t301-binding",
           "## How each language receives a parameter\n\n"
           "Given `n` declared as an `integer` with the value `10`, Arima prepends:\n\n"
           "| Language | Preamble line |\n|---|---|\n"
           "| JShell / Java | `long n = 10L;` |\n"
           "| JavaScript / TypeScript | `const n = 10;` |\n"
           "| Python | `n = 10` |\n"
           "| C# | `long n = 10;` |\n"
           "| F# | `let n = 10L` |\n"
           "| C++ | `long long n = 10;` |\n\n"
           "A `string` is emitted as a quoted literal with newlines, tabs and quotes escaped, so "
           "multi-line text arrives intact. You never parse arguments yourself."),
        code("t301-body",
             'long a = 0, b = 1;\n'
             'StringBuilder out = new StringBuilder();\n'
             'for (long i = 0; i < n; i++) {\n'
             '    out.append(a).append(i < n - 1 ? ", " : "");\n'
             '    long next = a + b;\n'
             '    a = b;\n'
             '    b = next;\n'
             '}\n'
             'System.out.println(out);',
             "jshell", anchor="fib"),
        md("t301-choosing",
           "## Choosing\n\n"
           "| Reach for | When |\n|---|---|\n"
           "| **Python** | text, data, anything with a PyPI package behind it |\n"
           "| **JavaScript** | JSON shaping, npm modules, quick string work |\n"
           "| **TypeScript** | the same, when you want the types checked |\n"
           "| **JShell** | you want Maven libraries and no ceremony |\n"
           "| **Java** | a real `main`, threads, heavier work |\n"
           "| **C# / F#** | .NET libraries, or you simply think better in them |\n"
           "| **C++** | numeric work where the speed actually matters |\n\n"
           "A tool's packages come from the same managers as everywhere else: Maven for "
           "JShell/Java, npm for JS/TS, NuGet for C#/F#, PyPI for Python. Install in the Packages "
           "tab and the tool body can import it."),
        md("t301-try",
           "## Try it\n\n"
           "1. Call it with `n = 15`.\n"
           "2. Switch **language** to Python and replace the body with:\n\n"
           "   ```python\n"
           "   a, b = 0, 1\n"
           "   out = []\n"
           "   for _ in range(n):\n"
           "       out.append(a)\n"
           "       a, b = b, a + b\n"
           "   print(\", \".join(map(str, out)))\n"
           "   ```\n\n"
           "   Call it again — same signature, same output, different runtime.\n"
           "3. Check `GET /api/tools/tool-301/schema` before and after. Identical.\n\n"
           "Next: `tool-401` hands a tool to an agent."),
    ])

TOOL_401 = notebook(
    "tool-401", "Tool 401 · Giving a Tool to an Agent",
    "Advanced tool — how a tool grant reaches an agent's prompt, what the agent actually "
    "sees, and how to write a tool a model will use correctly.",
    "Tools", 401, kind="tool",
    extra_meta=tool_meta("python", [
        param("path", "string", "Repository-relative path to inspect"),
        param("max_lines", "integer", "Stop after this many lines", False, "40"),
    ]),
    cells=[
        md("t401-grant",
           "# Giving a Tool to an Agent\n\n"
           "Open any agent and look at the **tool chips** in its banner. Each chip is a grant. "
           "Arima resolves every grant against the tools you have authored:\n\n"
           "- **Matches an Arima tool** — the agent's prompt gains that tool's full signature "
           "and its MCP name.\n"
           "- **Matches nothing** — it is passed through to the CLI as one of its own "
           "built-ins (`Read`, `Grep`, `Bash`, and friends).\n\n"
           "So one chip list covers both worlds, and you never have to say which is which."),
        md("t401-sees",
           "## What the agent actually sees\n\n"
           "Grant this tool to an agent and its system prompt grows a section:\n\n"
           "```markdown\n"
           "## Tools available in Arima\n\n"
           "These are notebook-authored tools on this machine. Call one over the `arima` MCP\n"
           "server, or ask the user to run it, and use its printed output.\n\n"
           "### `arima_tool_401_giving_a_tool_to_an_agent`\n"
           "Reads the head of a file so an agent can see what it is working with.\n\n"
           "Parameters:\n"
           "- `path` (string, required) — Repository-relative path to inspect\n"
           "- `max_lines` (integer, optional) — Stop after this many lines\n"
           "```\n\n"
           "That text is generated from the parameter table. **Your descriptions are the agent's "
           "only documentation.** A vague one produces a wrong call."),
        code("t401-body",
             'import os\n'
             '\n'
             '# Confine reads to the working tree - an agent should not be able to walk out of it\n'
             '# just by passing a crafted path.\n'
             'root = os.path.abspath(os.getcwd())\n'
             'target = os.path.abspath(os.path.join(root, path))\n'
             '\n'
             'if not target.startswith(root):\n'
             '    print(f"refused: {path} is outside the working tree")\n'
             'elif not os.path.isfile(target):\n'
             '    print(f"not found: {path}")\n'
             'else:\n'
             '    with open(target, encoding="utf-8", errors="replace") as fh:\n'
             '        for i, line in enumerate(fh):\n'
             '            if i >= max_lines:\n'
             '                print(f"... truncated at {max_lines} lines")\n'
             '                break\n'
             '            print(f"{i + 1:>4}  {line.rstrip()}")',
             "python", anchor="head"),
        md("t401-writing",
           "## Writing a tool a model will use well\n\n"
           "- **Name it for the job**, not the implementation. `fetch-quote` beats `http-get-json`.\n"
           "- **One description sentence that says when to reach for it.** The model is choosing "
           "between tools, not reading a manual.\n"
           "- **Few parameters.** Every optional one is a chance to guess wrong. Give defaults.\n"
           "- **Print a result, not a log.** Whatever lands on stdout is what the agent reads back "
           "— progress chatter becomes part of the answer.\n"
           "- **Fail in words.** `refused: path is outside the working tree` is something a model "
           "can recover from; a stack trace is not.\n"
           "- **Guard the edges.** A tool runs with your permissions. The body above refuses paths "
           "outside the working tree, and yours should refuse whatever it must."),
        md("t401-try",
           "## Try it\n\n"
           "1. Call it with `path = README.md` and `max_lines = 12`.\n"
           "2. Call it with `path = ../../etc/passwd` and watch it refuse.\n"
           "3. Open **agent-201 · Code Reviewer**, press **+ tool**, and type this notebook's "
           "name. Run the agent and ask it to review a file — the signature is now in its "
           "prompt.\n"
           "4. Try the same grant with the name `Bash`. No Arima tool matches, so it passes "
           "straight through to the CLI.\n\n"
           "Next: `tool-501` sends it out to the harness."),
    ])

TOOL_501 = notebook(
    "tool-501", "Tool 501 · Deploying a Tool to the Harness",
    "Advanced tool — how a tool leaves Arima and becomes callable from Claude Code, GitHub "
    "Copilot or Antigravity, and why the implementation stays here.",
    "Tools", 501, kind="tool",
    extra_meta=tool_meta("python", [
        param("name", "string", "Who to greet", False, "world"),
    ]),
    cells=[
        md("t501-route",
           "# Deploying a Tool to the Harness\n\n"
           "An agent or a skill deploys as a **file**. A tool does not — and that is "
           "deliberate.\n\n"
           "A tool's body is Python, or C#, or JShell. Copying it into a CLI's config directory "
           "would mean copying a runtime too. Instead the tool stays in Arima and the harness is "
           "told **how to reach it**, over the MCP server Arima is already running:\n\n"
           "```\n"
           "Claude Code  --MCP-->  Arima  --executes-->  your tool body\n"
           "```\n\n"
           "One source of truth. Edit the notebook and the deployed behaviour changes with it — "
           "no redeploy."),
        md("t501-two-routes",
           "## The two routes out\n\n"
           "**1. Already done: the MCP catalog.**\n"
           "Every tool with a body is advertised on `tools/list` as `arima_<name>`, carrying its "
           "real JSON Schema. Any client pointed at `http://127.0.0.1:8585/api/mcp/sse` sees it. "
           "Nothing to deploy — authoring *is* publishing.\n\n"
           "**2. A plugin, for a nicer surface.**\n"
           "Add the tool as a member of a plugin and deploy that. The bundle gains "
           "`commands/<tool>.md` — a slash command describing the call — plus an "
           "`.mcp.json` pointing at Arima. The CLI user types `/word-count` instead of knowing the "
           "MCP tool name.\n\n"
           "Route 1 is for agents. Route 2 is for people."),
        code("t501-body",
             'print(f"hello, {name} - this ran inside Arima")',
             "python", anchor="greet"),
        md("t501-walkthrough",
           "## Walk it through\n\n"
           "1. **Confirm the catalog.** In a terminal:\n\n"
           "   ```bash\n"
           "   curl -s -X POST \"http://localhost:8585/api/mcp/messages?sessionId=demo\" \\\n"
           "     -H \"Content-Type: application/json\" \\\n"
           "     -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'\n"
           "   ```\n\n"
           "   Find `arima_tool_501_deploying_a_tool_to_the_harness` in the result.\n\n"
           "2. **Connect the harness.** Point your CLI at Arima's MCP endpoint:\n\n"
           "   ```json\n"
           "   { \"mcpServers\": { \"arima\": { \"type\": \"sse\",\n"
           "       \"url\": \"http://127.0.0.1:8585/api/mcp/sse\" } } }\n"
           "   ```\n\n"
           "   For Claude Code that is `.mcp.json` in the project, or `~/.claude.json` for every "
           "project.\n\n"
           "3. **Bundle it.** Agents tab → **+ New Plugin** → add this tool as a member "
           "→ **Deploy** to `bundle` first and read what was written, then to `project` or "
           "`user` when it looks right."),
        md("t501-scope",
           "## Choosing a scope\n\n"
           "| Target | Lands in | Reach |\n|---|---|---|\n"
           "| `project` | the repo's `.claude/` | this checkout only |\n"
           "| `user` | `~/.claude/` | every project on this machine |\n"
           "| `bundle` | `data/plugins/` | nothing installed — just look at it |\n\n"
           "Always deploy to `bundle` first when you are unsure. It writes the identical files "
           "somewhere harmless, so you can read them before anything is installed.\n\n"
           "**Everything is reversible.** The **Deployed** section of the Agents tab lists every "
           "write Arima has made, and **Undeploy** removes exactly those paths — never a file "
           "Arima did not create.\n\n"
           "## A caveat worth knowing\n\n"
           "A deployed tool only answers while **Arima is running**. The harness holds a pointer, "
           "not a copy. If a CLI reports the `arima` server as unavailable, start Arima "
           "(`arima start`) and retry — that is usually the whole problem."),
    ])

# ---------------------------------------------------------------------------
# Skills: 101 -> 501   (101 already exists; re-emitted here to keep the track in one place)
# ---------------------------------------------------------------------------

SKILL_201 = notebook(
    "skill-201", "Skill 201 · Anatomy of a Skill",
    "Intermediate skill — what a skill is made of, how it differs from an agent, and the "
    "frontmatter that decides when it fires.",
    "Skills", 201, kind="skill",
    extra_meta={"agent": {"provider": "claude", "tools": []}},
    cells=[
        md("s201-what",
           "# Anatomy of a Skill\n\n"
           "A skill is **instructions, and nothing else**. No tools, no provider loop, no state. "
           "It is a piece of know-how you want available the moment it is relevant.\n\n"
           "When deployed, a skill is one file:\n\n"
           "```markdown\n"
           "---\n"
           "name: anatomy-of-a-skill\n"
           "description: What this skill is for, in one line.\n"
           "---\n\n"
           "The instructions. Everything below the frontmatter is the skill.\n"
           "```\n\n"
           "Arima builds that file for you: `name` is the slug of the notebook name, `description` "
           "is the notebook description, and the body is **every markdown cell, concatenated in "
           "order**. Which means the cells you are reading are literally the skill."),
        md("s201-vs",
           "## Skill or agent?\n\n"
           "| | Skill | Agent |\n|---|---|---|\n"
           "| Is | instructions | instructions + tool grants |\n"
           "| Fires | when its description matches what is happening | when invoked by name or task |\n"
           "| Runs | inside the caller's turn | as its own run, with its own output |\n"
           "| Costs | nothing until used | a provider call |\n"
           "| Good for | conventions, formats, checklists, house style | work with steps and tools |\n\n"
           "The rule of thumb: if you are teaching the model **how you like things done**, write a "
           "skill. If you are asking it to **go and do something**, write an agent.\n\n"
           "When in doubt, start with a skill. It is cheaper, simpler, and promoting it to an agent "
           "later is a two-minute job."),
        md("s201-structure",
           "## How to structure the body\n\n"
           "The cells of a good skill tend to follow a shape:\n\n"
           "1. **What this is for** — one short paragraph. The model reads this first.\n"
           "2. **The rules** — a tight list. Specific, checkable, no hedging.\n"
           "3. **Examples** — one right, one wrong. Worth more than three more rules.\n"
           "4. **Edge cases** — only the ones that actually come up.\n\n"
           "Keep it short. A skill competing for attention with everything else in the context "
           "window wins on precision, not length. If yours is running past a page, it probably "
           "wants to be two skills."),
        md("s201-example",
           "## Example: right and wrong\n\n"
           "A skill about pull-request descriptions.\n\n"
           "**Wrong — vague, unfalsifiable:**\n\n"
           "> Write good PR descriptions that explain the change clearly and help reviewers "
           "> understand what you did and why.\n\n"
           "**Right — specific, checkable:**\n\n"
           "> Open with one sentence naming the user-visible change. Then a **Why** paragraph "
           "> describing the problem, not the solution. Then a bulleted **What changed** list, one "
           "> bullet per file or concern. Never list files without saying what moved in them. "
           "> Close with how you verified it. No emoji. Under 300 words.\n\n"
           "The second one is enforceable — you can look at the output and say whether it was "
           "followed."),
        md("s201-try",
           "## Try it\n\n"
           "1. Press **+ New Skill** in the Agents tab and name it `pr-description`.\n"
           "2. Write the four sections above into its markdown cells.\n"
           "3. Run it from the dock with a task like *\"I moved the retry logic out of "
           "HttpClient into a RetryPolicy class and added tests\"*.\n"
           "4. Read what comes back and go tighten whichever rule it ignored. That loop is most of "
           "skill authoring.\n\n"
           "Next: `skill-301` on the one line that decides whether a skill is ever used."),
    ])

SKILL_301 = notebook(
    "skill-301", "Skill 301 · Descriptions That Trigger",
    "Intermediate skill — the description line is a matching rule, not a summary. How to "
    "write one that fires when it should and stays quiet when it should not.",
    "Skills", 301, kind="skill",
    extra_meta={"agent": {"provider": "claude", "tools": []}},
    cells=[
        md("s301-why",
           "# Descriptions That Trigger\n\n"
           "A skill that never fires is worse than no skill — you believe it is helping.\n\n"
           "The `description` in the frontmatter is not a summary for humans. It is the **matching "
           "rule** the model uses to decide whether to pull this skill in. Everything about whether "
           "your skill gets used lives in that one line."),
        md("s301-shape",
           "## The shape that works\n\n"
           "> **What it does** + **when to use it** + **the words a user would actually say**.\n\n"
           "Three parts, one line. Compare:\n\n"
           "| | Description |\n|---|---|\n"
           "| **Never fires** | `Commit message helper.` |\n"
           "| **Fires wildly** | `Helps with git and development tasks.` |\n"
           "| **Fires right** | `Turns a description of a change into a Conventional Commits message. Use when the user says \"write a commit message\", \"commit this\", or asks how to phrase a commit.` |\n\n"
           "The third one works because it names the **trigger phrases**. The model is pattern "
           "matching against what the user said; give it the patterns."),
        md("s301-rules",
           "## Rules\n\n"
           "- **Say when, not just what.** `Use when...` earns its place in the line.\n"
           "- **Quote the user's words.** Write the phrases people actually type.\n"
           "- **Name the artifact.** \"a Conventional Commits message\" beats \"commit help\".\n"
           "- **Do not oversell.** \"Helps with development\" matches everything, which means the "
           "model stops trusting it.\n"
           "- **One skill, one job.** A description with \"and also\" in it is two skills.\n"
           "- **Mention the format if the format is the point.** If the skill exists to enforce a "
           "shape, the shape belongs in the trigger line."),
        md("s301-testing",
           "## Testing a trigger\n\n"
           "You cannot eyeball this. Test it:\n\n"
           "1. Deploy the skill to `project` (Agents tab → the skill's card → the "
           "download button → target `project`).\n"
           "2. Open the CLI in that repo.\n"
           "3. Say the thing a user would say — not the thing you wrote in the description, "
           "the thing a **user** would say.\n"
           "4. Did it fire? If not, the gap between your phrasing and theirs is the bug.\n\n"
           "Write down three real phrasings before you write the description. Then make sure the "
           "description covers all three.\n\n"
           "**A negative test matters too.** Say something adjacent but different and check the "
           "skill stays out of it. A skill that fires on everything is noise."),
        md("s301-try",
           "## Try it\n\n"
           "Take `skill-101 · Commit Message` and rewrite its description three ways:\n\n"
           "- One deliberately too narrow (only the exact phrase \"conventional commit\").\n"
           "- One deliberately too broad (\"helps with git\").\n"
           "- One following the rule above.\n\n"
           "Deploy each, and in the CLI try: *\"commit this\"*, *\"what should the commit message "
           "be\"*, and *\"squash these commits\"* (which should **not** fire it).\n\n"
           "Next: `skill-401` on when a skill is the wrong answer."),
    ])

SKILL_401 = notebook(
    "skill-401", "Skill 401 · Skills, Agents and Tools Together",
    "Advanced skill — choosing between the three, and composing them so each does the part "
    "it is actually good at.",
    "Skills", 401, kind="skill",
    extra_meta={"agent": {"provider": "claude", "tools": []}},
    cells=[
        md("s401-three",
           "# Skills, Agents and Tools Together\n\n"
           "Three kinds, three jobs:\n\n"
           "| | Is | Decides nothing / something | Runs code |\n|---|---|---|---|\n"
           "| " + SKILL_ICON + " **Skill** | know-how | nothing — it informs | no |\n"
           "| " + AGENT_ICON + " **Agent** | a worker | what to do next | no, it asks for tools |\n"
           "| " + TOOL_ICON + " **Tool** | a function | nothing — it computes | yes |\n\n"
           "Most people reach for an agent when they want a skill, and for a skill when they want a "
           "tool. The test:\n\n"
           "- Is the answer **deterministic**? That is a tool. Counting lines, parsing a file, "
           "calling an API — do not ask a model to do arithmetic.\n"
           "- Does it need **judgement, in one shot**? That is a skill. Formatting, naming, "
           "choosing a style.\n"
           "- Does it need **judgement, in several steps, with tools**? That is an agent."),
        md("s401-compose",
           "## Composing them\n\n"
           "The pattern that works, in order of how often you will want it:\n\n"
           "**Skill + tool.** The skill says *how to present something*; a tool produces the "
           "numbers. The skill mentions the tool by name so the model knows it exists.\n\n"
           "**Agent + tools.** The agent plans; the tools do the deterministic parts. Grant the "
           "agent only the tools it needs — a long grant list makes for worse choices.\n\n"
           "**Agent + skills.** The agent works; the skills shape the output when they are "
           "relevant. You do not wire this up — deployed skills are available to the agent in "
           "the harness automatically.\n\n"
           "**Everything, in a plugin.** When the set is coherent, bundle it. That is `plugin-201`."),
        md("s401-worked",
           "## A worked example\n\n"
           "*\"Review my changes and write the commit message.\"*\n\n"
           "Built badly, that is one agent with a long prompt doing all of it — and it will "
           "miscount the files, then write a message in whatever style it felt like.\n\n"
           "Built well, it is four pieces:\n\n"
           "| Piece | Kind | Why |\n|---|---|---|\n"
           "| `diff-stat` | " + TOOL_ICON + " tool | counting is deterministic; a model should not guess |\n"
           "| `code-reviewer` | " + AGENT_ICON + " agent | the review needs judgement and steps |\n"
           "| `commit-message` | " + SKILL_ICON + " skill | the format is a convention, not a decision |\n"
           "| `review-kit` | " + PKG_ICON + " plugin | ships the three together |\n\n"
           "Each piece is independently testable, and you can fix the commit format without "
           "touching the reviewer."),
        md("s401-smells",
           "## Smells\n\n"
           "- **An agent whose prompt contains a format specification.** The format wants to be a "
           "skill.\n"
           "- **A skill that explains how to compute something.** That computation wants to be a "
           "tool.\n"
           "- **A tool that asks for a judgement call in a parameter.** Move the judgement up to "
           "the agent and keep the tool dumb.\n"
           "- **An agent with nine tool grants.** Split it, or drop the grants it has never used.\n"
           "- **Two skills that fire on the same phrase.** One of them will lose. Merge them."),
        md("s401-try",
           "## Try it\n\n"
           "Take something you do by hand each week and decompose it on paper first: which part is "
           "deterministic, which is a convention, which needs steps. Then build exactly those "
           "pieces — and resist putting any of them in the agent prompt.\n\n"
           "Next: `skill-501` deploys skills to the harness."),
    ])

SKILL_501 = notebook(
    "skill-501", "Skill 501 · Deploying Skills to the Harness",
    "Advanced skill — where a skill lands for each agentic CLI, choosing project versus user "
    "scope, and how to verify it actually loaded.",
    "Skills", 501, kind="skill",
    extra_meta={"agent": {"provider": "claude", "tools": []}},
    cells=[
        md("s501-where",
           "# Deploying Skills to the Harness\n\n"
           "A skill is a file, so deploying it is a copy — to the place the chosen CLI looks. "
           "Pick a **provider** in the banner and a **target**, and Arima writes:\n\n"
           "| Provider | Target `project` | Target `user` |\n|---|---|---|\n"
           "| Claude | `.claude/skills/<name>/SKILL.md` | `~/.claude/skills/<name>/SKILL.md` |\n"
           "| Copilot | `.github/skills/<name>/SKILL.md` | `~/.github/skills/<name>/SKILL.md` |\n"
           "| Antigravity | `.antigravity/skills/<name>/SKILL.md` | `~/.antigravity/skills/<name>/SKILL.md` |\n\n"
           "`<name>` is the notebook name kebab-cased. The file is frontmatter plus every markdown "
           "cell of the notebook, in order."),
        md("s501-scope",
           "## Project or user?\n\n"
           "**`project`** — the skill belongs to this codebase. House style, this repo's "
           "review checklist, its release ritual. It is also the only scope you can commit to git, "
           "which means your team gets it on the next pull. Prefer this one.\n\n"
           "**`user`** — the skill is about *you*. How you like commit messages, your "
           "preferred explanation style. Follows you to every project, is invisible to everyone "
           "else.\n\n"
           "**`bundle`** — writes the identical file under `data/staged/` and installs "
           "nothing. Use it when you want to read the generated file before committing to either "
           "of the above.\n\n"
           "A skill deployed at both scopes is not an error, but the project copy is the one you "
           "will forget about. Pick one."),
        md("s501-verify",
           "## Verifying it loaded\n\n"
           "Deploying does not mean loaded. Check:\n\n"
           "1. **The file is where you think.** Expand the entry in the Agents tab's **Deployed** "
           "section — it lists the exact paths written.\n"
           "2. **The frontmatter parsed.** Open the file. A `description` containing a stray colon "
           "or a newline can break the YAML; Arima collapses whitespace to one line to avoid "
           "exactly that.\n"
           "3. **It fires.** Restart the CLI (skills are read at startup), then say the thing from "
           "your trigger line. If nothing happens, the description is the suspect — go back "
           "to `skill-301`.\n\n"
           "A skill that exists but never fires looks identical to one that works until the day "
           "you need it."),
        md("s501-lifecycle",
           "## Living with a deployed skill\n\n"
           "**Editing.** Edit the notebook, then deploy again. The file is overwritten in place.\n\n"
           "**Renaming.** The filename comes from the notebook name, so renaming writes a *new* "
           "file. Arima notices: the deploy record for that notebook is updated and the old file is "
           "removed, so you do not end up with two copies of the same skill fighting each other.\n\n"
           "**Removing.** **Undeploy** in the Deployed section deletes exactly the recorded paths "
           "and prunes the directories they emptied. It will not touch a skill Arima did not "
           "write, including one you hand-authored in `.claude/skills/`.\n\n"
           "**Sharing.** For a team, commit the `project` deploy. For anything bigger, bundle the "
           "skill into a plugin — `plugin-501`."),
        md("s501-try",
           "## Try it\n\n"
           "1. Deploy this notebook to `bundle` and open "
           "`data/staged/.claude/skills/skill-501-deploying-skills-to-the-harness/SKILL.md`. That "
           "is the whole artifact — frontmatter, then these cells.\n"
           "2. Deploy `skill-101` to `project`, restart your CLI, and ask it to write a commit "
           "message.\n"
           "3. Check the **Deployed** section, then **Undeploy** and confirm the file is gone and "
           "the empty directory with it.\n\n"
           "That is the whole skill lifecycle. Next section: Connectors."),
    ])

# ---------------------------------------------------------------------------
# Connectors: 101 -> 501
# ---------------------------------------------------------------------------

CONNECTOR_101 = notebook(
    "connector-101", "Connector 101 · Your First Connector",
    "Beginner connector — attach Arima to an external MCP server, probe it, and see the "
    "tools it offers.",
    "Connectors", 101, kind="connector",
    extra_meta={"connector": {
        "transport": "stdio",
        "command": ["npx", "-y", "@modelcontextprotocol/server-everything"],
        "url": "",
        "env": {},
        "enabled": True,
    }},
    cells=[
        md("c101-what",
           "# Your First Connector\n\n"
           "Arima runs an MCP **server** — that is how Claude Code reaches your notebooks and "
           "tools. A **connector** is the mirror image: an MCP server somewhere else that *Arima* "
           "attaches to, so its tools become usable from here.\n\n"
           "```\n"
           "  Claude Code  ---->  Arima  ---->  GitHub MCP server\n"
           "               MCP            MCP\n"
           "     (server)         (client)\n"
           "```\n\n"
           "Filesystem access, a GitHub API, a database, a company-internal service — anything "
           "that speaks MCP can be a connector. You do not write the server; you point at one."),
        md("c101-anatomy",
           "## What a connector is made of\n\n"
           "Like everything else in the factory, it is a notebook. The banner holds the whole "
           "configuration:\n\n"
           "| Field | Means |\n|---|---|\n"
           "| **transport** | `stdio` (a local subprocess) or `sse` (an HTTP endpoint) |\n"
           "| **command** | for stdio: the argv to launch, e.g. `npx -y @modelcontextprotocol/server-everything` |\n"
           "| **url** | for sse: the endpoint, e.g. `http://127.0.0.1:3000/sse` |\n"
           "| **env** | extra environment for a stdio subprocess — where an API token goes |\n"
           "| **enabled** | whether agents may reach it at all |\n\n"
           "The markdown cells (these) are **notes for you**. They are never sent to the server."),
        md("c101-local",
           "## Local by default, and why that matters\n\n"
           "This connector uses **stdio**: Arima launches the command as a subprocess and talks "
           "JSON-RPC over its pipes. Nothing touches the network. That is the default on purpose "
           "— Arima binds to loopback and does not reach outside your machine unless you ask "
           "it to.\n\n"
           "An **sse** connector pointed at `127.0.0.1` is equally local.\n\n"
           "An sse connector pointed at a **real host** does leave your machine, and Arima will not "
           "do that quietly: every probe and every call stops and asks you to approve it first. "
           "That is `connector-301`."),
        md("c101-probe",
           "## Probe it\n\n"
           "Press **Probe** in the dock below. Arima will:\n\n"
           "1. Launch `npx -y @modelcontextprotocol/server-everything` (the MCP project's own "
           "reference server — the first run downloads it).\n"
           "2. Send `initialize`, then `tools/list`.\n"
           "3. Print what the server says it can do, then shut the subprocess down.\n\n"
           "You should see a list of tool names with their parameters — `echo`, `add`, "
           "`longRunningOperation` and friends. That list *is* the connector: whatever appears "
           "there is what an agent can call.\n\n"
           "**If it fails:** the error carries the subprocess's stderr. No `npx` on PATH is the "
           "usual cause — install Node.js and try again."),
        md("c101-next",
           "## Try it\n\n"
           "1. Probe, and read the tool list.\n"
           "2. Change the command to "
           "`npx -y @modelcontextprotocol/server-filesystem .` and probe again — a different "
           "server, different tools, same three steps.\n"
           "3. Untick **enabled** and note that agents can no longer reach it, while you can still "
           "probe it from here.\n\n"
           "Next: `connector-201` on stdio in depth — argv, environment and secrets."),
    ])

CONNECTOR_201 = notebook(
    "connector-201", "Connector 201 · stdio in Depth",
    "Intermediate connector — argv, environment variables, where secrets go, and how to read "
    "the error when a server will not start.",
    "Connectors", 201, kind="connector",
    extra_meta={"connector": {
        "transport": "stdio",
        "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "."],
        "url": "",
        "env": {},
        "enabled": True,
    }},
    cells=[
        md("c201-argv",
           "# stdio in Depth\n\n"
           "A stdio connector is a subprocess. The **command** field is an argv list — a "
           "program and its arguments — and it is handed to the operating system as a list, "
           "never to a shell.\n\n"
           "That is a security property, not a detail. There is no shell, so there is no shell "
           "injection: a `;` or a backtick in an argument is just a character. It also means "
           "**shell syntax does not work**:\n\n"
           "| You might write | What happens |\n|---|---|\n"
           "| `npx server --path ~/docs` | `~` is passed literally, not expanded |\n"
           "| `server && other` | `&&` becomes an argument to `server` |\n"
           "| `server \"two words\"` | splits into `two` and `words` |\n\n"
           "Write absolute paths. If you genuinely need a shell, make the shell the program: "
           "`bash -lc \"...\"`."),
        md("c201-env",
           "## Environment and secrets\n\n"
           "Most interesting servers want credentials. The **env** field takes "
           "`KEY=value` pairs, space-separated, and they are added to the subprocess's "
           "environment:\n\n"
           "```\n"
           "GITHUB_PERSONAL_ACCESS_TOKEN=ghp_xxx  LOG_LEVEL=debug\n"
           "```\n\n"
           "**Where that value lives matters.** It is stored in the connector notebook, which is a "
           "file under `notebooks/<you>/`. So:\n\n"
           "- Do **not** commit a connector notebook with a real token in it.\n"
           "- Prefer a server that reads its own credential file or keychain, if it offers one.\n"
           "- If you must put a token in `env`, treat that notebook the way you treat "
           "`data/settings.json` — personal, gitignored, never shared.\n\n"
           "A deployed connector copies `env` into the harness's `.mcp.json` too, which is another "
           "file that then holds the secret. Know where your tokens are."),
        md("c201-debug",
           "## When it will not start\n\n"
           "Arima gives the subprocess 30 seconds, then reports what it captured. Reading the "
           "error:\n\n"
           "| Error | Usually means |\n|---|---|\n"
           "| `Cannot run program \"npx\"` | the program is not on PATH — install it, or use an absolute path |\n"
           "| `the server sent no reply within 30s` | it started but never spoke MCP — wrong package, or it is waiting on a prompt |\n"
           "| stderr about a missing argument | the server needs a path or flag you did not pass |\n"
           "| an auth error from the server | `env` is missing or wrong |\n\n"
           "A server that writes logs to **stdout** instead of stderr will confuse any MCP client. "
           "Arima skips lines that are not JSON, which rescues most of them, but a server that "
           "interleaves log text *inside* a JSON message cannot be saved — look for a quiet "
           "mode or a `--log-file` flag."),
        md("c201-lifecycle",
           "## One conversation per call\n\n"
           "Arima does not hold a connector open. Each probe and each tool call launches the "
           "server, exchanges messages, and shuts it down.\n\n"
           "That is a deliberate trade:\n\n"
           "- **Good:** nothing to leak, nothing to reconnect after a restart, no stale "
           "subprocess when you edit the command.\n"
           "- **Cost:** a startup per call. For `npx`-launched servers that is a second or two "
           "after the first download.\n\n"
           "If a server is slow to start and you call it constantly, run it yourself as a "
           "long-lived process with an SSE endpoint and connect to that instead — which is "
           "`connector-301`."),
        md("c201-try",
           "## Try it\n\n"
           "1. Probe this one. `server-filesystem .` exposes the directory Arima was started in.\n"
           "2. Change `.` to a path that does not exist and probe again — read the stderr in "
           "the error.\n"
           "3. Set **env** to `LOG_LEVEL=debug` and probe once more.\n"
           "4. Try `command` = `node --version`. It starts and exits without speaking MCP, so you "
           "get the *no reply* error — the signature of \"that program is not an MCP "
           "server\".\n\n"
           "Next: `connector-301` on SSE and the approval gate."),
    ])

CONNECTOR_301 = notebook(
    "connector-301", "Connector 301 · SSE and the Approval Gate",
    "Intermediate connector — HTTP endpoints, the difference loopback makes, and the gate "
    "that stands between Arima and any host outside your machine.",
    "Connectors", 301, kind="connector",
    extra_meta={"connector": {
        "transport": "sse",
        "command": [],
        "url": "http://127.0.0.1:8585/api/mcp/sse",
        "env": {},
        "enabled": True,
    }},
    cells=[
        md("c301-sse",
           "# SSE and the Approval Gate\n\n"
           "The second transport is **sse**: the server is already running somewhere and listening "
           "over HTTP. Arima opens the stream, reads the endpoint the server names, posts "
           "JSON-RPC there, and reads the replies back off the stream.\n\n"
           "Use it when the server is long-lived, shared between several clients, or simply not "
           "something you want to launch per call.\n\n"
           "This connector is pointed at **Arima's own MCP endpoint** — a loop, deliberately. "
           "Probe it and you will see Arima's own tool catalog come back through the client half "
           "of the same program. It is the clearest demonstration there is that the two halves are "
           "the same protocol."),
        md("c301-loopback",
           "## Loopback is not the network\n\n"
           "Arima's security model is simple: **it binds to `127.0.0.1` and it does not reach "
           "outside your machine on its own.** Code runs here with your permissions, so "
           "reachability is the boundary.\n\n"
           "A connector respects that:\n\n"
           "| Endpoint | Leaves the machine? | Approval |\n|---|---|---|\n"
           "| `stdio` command | no — a subprocess | none needed |\n"
           "| `http://127.0.0.1:...` | no | none needed |\n"
           "| `http://localhost:...` | no | none needed |\n"
           "| `https://mcp.example.com/sse` | **yes** | **every time** |\n\n"
           "The card in the Agents tab labels each connector `local` or `remote`, so you can see at "
           "a glance which is which."),
        md("c301-gate",
           "## What the gate does\n\n"
           "Point a connector at a real host and the next probe does not just happen. Arima:\n\n"
           "1. Stops before opening the connection.\n"
           "2. Raises an approval request naming the connector and the exact endpoint.\n"
           "3. Waits for you to approve or deny it in the browser.\n"
           "4. Refuses if you deny — **or if you do not answer**.\n\n"
           "This is the same gate that guards non-local access to Arima itself, reused rather than "
           "reinvented. It fires per connection, not once per connector: approving a probe does "
           "not pre-approve tomorrow's tool call.\n\n"
           "The point is that the outbound host is always **your** choice, made at the moment it "
           "happens. Arima ships with no remote hosts of its own."),
        md("c301-remote",
           "## Working with a remote server\n\n"
           "If you do connect to one:\n\n"
           "- **Probe once and read the tool list carefully.** Those tools will run with whatever "
           "credentials that server holds.\n"
           "- **Know what the server can see.** An MCP server with your repository mounted can read "
           "all of it.\n"
           "- **Prefer a local proxy when one exists.** Many hosted services publish a stdio server "
           "that talks to their API; that keeps the gate out of your way and the credential on "
           "your machine.\n"
           "- **Leave it disabled when you are not using it.** The **enabled** switch stops agents "
           "reaching it without deleting the configuration.\n\n"
           "If the browser is closed when a gate fires, nothing happens until you open it — "
           "the request simply expires and the call is refused."),
        md("c301-try",
           "## Try it\n\n"
           "1. Probe this connector as it stands. Loopback — no gate, and Arima's own tools "
           "come back.\n"
           "2. Change the **url** host to `arima.invalid` and probe. The banner now warns you it "
           "leaves the machine, and the gate fires before any connection is attempted.\n"
           "3. Deny it, and read the error: *\"Reaching ... was not approved.\"*\n"
           "4. Put `127.0.0.1` back.\n\n"
           "Next: `connector-401` uses a connector's tools from an agent."),
    ])

CONNECTOR_401 = notebook(
    "connector-401", "Connector 401 · Connector Tools in an Agent",
    "Advanced connector — calling a connector's tools, mixing them with tools you authored, "
    "and where each one actually runs.",
    "Connectors", 401, kind="connector",
    extra_meta={"connector": {
        "transport": "stdio",
        "command": ["npx", "-y", "@modelcontextprotocol/server-everything"],
        "url": "",
        "env": {},
        "enabled": True,
    }},
    cells=[
        md("c401-call",
           "# Connector Tools in an Agent\n\n"
           "Probing tells you what a server offers. Calling one of its tools is the next step:\n\n"
           "```bash\n"
           "curl -s -X POST http://localhost:8585/api/connectors/call \\\n"
           "  -H \"Content-Type: application/json\" \\\n"
           "  -d '{\"connectorId\":\"connector-401\",\"tool\":\"echo\",\n"
           "       \"args\":{\"message\":\"hello from Arima\"}}'\n"
           "```\n\n"
           "Arima opens the conversation, sends `tools/call`, flattens the content blocks the "
           "server returns into text, and hands it back. If the connector is remote, the approval "
           "gate fires first — for the call, not just the probe."),
        md("c401-three",
           "## Three places a tool can live\n\n"
           "By now an agent can reach tools from three different places, and it is worth being "
           "clear about where each one runs:\n\n"
           "| Kind | Written by | Runs | Reached via |\n|---|---|---|---|\n"
           "| " + TOOL_ICON + " Arima tool | you, as a notebook | inside Arima | `arima_<name>` over MCP |\n"
           "| " + PLUG_ICON + " Connector tool | someone else | in their server | the connector |\n"
           "| CLI built-in | the CLI vendor | in the CLI | a plain grant, e.g. `Read` |\n\n"
           "All three appear as tool grants on an agent. The difference that matters is **trust**: "
           "you can read the body of an Arima tool. You cannot read the inside of a connector."),
        md("c401-pattern",
           "## The useful pattern: wrap, do not expose\n\n"
           "Handing an agent a connector's raw tool surface is rarely the best move — a server "
           "with thirty tools eats the context window and the model picks badly.\n\n"
           "Instead, **wrap the one call you need in an Arima tool**:\n\n"
           "```python\n"
           "# body of an Arima tool, mode: python\n"
           "import json, urllib.request\n"
           "\n"
           "req = urllib.request.Request(\n"
           "    \"http://127.0.0.1:8585/api/connectors/call\",\n"
           "    data=json.dumps({\"connectorId\": \"connector-401\",\n"
           "                     \"tool\": \"echo\",\n"
           "                     \"args\": {\"message\": message}}).encode(),\n"
           "    headers={\"Content-Type\": \"application/json\"})\n"
           "print(json.load(urllib.request.urlopen(req)).get(\"output\", \"\"))\n"
           "```\n\n"
           "Now the agent sees **one** well-named tool with a signature you wrote, and the thirty "
           "it does not need stay out of its way. The connector is an implementation detail.\n\n"
           "That request goes to `127.0.0.1` — Arima calling itself — so it adds no "
           "outbound host of its own."),
        md("c401-fail",
           "## When it goes wrong\n\n"
           "- **`Connector 'x' is disabled`** — the enabled switch is off.\n"
           "- **`Reaching ... was not approved`** — the gate fired and was denied or timed "
           "out. Nothing was sent.\n"
           "- **The tool reported an error** — this came from the *server*, not from Arima. "
           "Its text is the server's own message; take it up with the server.\n"
           "- **Unknown tool** — probe again. Servers change their tool list between versions, "
           "and an `npx`-launched one may have updated under you.\n\n"
           "Because each call is its own conversation, a failure never leaves a broken session "
           "behind. Fix the cause and call again."),
        md("c401-try",
           "## Try it\n\n"
           "1. Probe this connector and pick a tool from the list.\n"
           "2. Call it with the `curl` above, adjusting `tool` and `args` to match its schema.\n"
           "3. Create an Arima tool that wraps it, with a signature of your own choosing.\n"
           "4. Grant that tool to an agent and watch it use the connector without knowing there "
           "is one.\n\n"
           "Next: `connector-501` gives the harness the same connector."),
    ])

CONNECTOR_501 = notebook(
    "connector-501", "Connector 501 · Deploying a Connector to the Harness",
    "Advanced connector — writing the server into a CLI's .mcp.json, choosing a scope, and "
    "removing it again without disturbing anyone else's entries.",
    "Connectors", 501, kind="connector",
    extra_meta={"connector": {
        "transport": "stdio",
        "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "."],
        "url": "",
        "env": {},
        "enabled": True,
    }},
    cells=[
        md("c501-what",
           "# Deploying a Connector to the Harness\n\n"
           "Deploying a connector does not move anything. The server stays where it is; the "
           "harness is told how to reach it, by adding an entry to `.mcp.json` — the file "
           "every agentic CLI reads to learn which MCP servers it has.\n\n"
           "A stdio connector becomes:\n\n"
           "```json\n"
           "{ \"mcpServers\": {\n"
           "    \"connector-501-deploying-a-connector-to-the-harness\": {\n"
           "      \"command\": \"npx\",\n"
           "      \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\", \".\"]\n"
           "    } } }\n"
           "```\n\n"
           "An sse connector becomes `{ \"type\": \"sse\", \"url\": \"...\" }`. Either way the CLI "
           "now talks to that server **directly** — Arima is not in the path at all."),
        md("c501-merge",
           "## It merges, it does not overwrite\n\n"
           "`.mcp.json` is shared. Your CLI may already have servers in it, put there by you or by "
           "another tool. So a connector deploy:\n\n"
           "1. Reads the existing file if there is one.\n"
           "2. Adds or replaces **only this connector's key**.\n"
           "3. Writes the file back with everything else intact.\n\n"
           "Undeploy is the same in reverse: it removes only this connector's key, and deletes the "
           "file only if nothing is left in it. An entry you added by hand survives both.\n\n"
           "If the existing file is not valid JSON, Arima says so in the log and writes a fresh one "
           "rather than guessing — worth knowing before you deploy over a file you have been "
           "editing."),
        md("c501-scope",
           "## Scopes\n\n"
           "| Target | File | Who gets it |\n|---|---|---|\n"
           "| `project` | `<repo>/.claude/.mcp.json` | this checkout — commit it and the team does too |\n"
           "| `user` | `~/.claude/.mcp.json` | every project you open on this machine |\n"
           "| `bundle` | `data/.mcp.json` | nobody — staged so you can read it |\n\n"
           "**Think before committing a project deploy.** The entry travels to everyone who pulls "
           "the repo, and it will try to launch on their machine. Fine for "
           "`npx -y @modelcontextprotocol/server-filesystem .`, which is self-installing and "
           "harmless. Not fine for a command with your absolute home path in it, or an `env` "
           "carrying your token — `connector-201` said where secrets end up, and this is the "
           "place that bites."),
        md("c501-both",
           "## Arima and the harness, side by side\n\n"
           "After deploying, the same server is reachable two ways:\n\n"
           "```\n"
           "  You, in Arima        ->  Arima  ->  the server\n"
           "  You, in Claude Code  ------------>  the server\n"
           "```\n\n"
           "That is usually what you want: notebooks and agents here, the CLI there, one "
           "configuration. But know the consequences.\n\n"
           "- **The approval gate is Arima's, not the CLI's.** A remote connector deployed to the "
           "harness is reached by the CLI under *its* rules, not Arima's. The gate protects calls "
           "that go through Arima.\n"
           "- **Two clients, two subprocesses.** For stdio servers each client launches its own. "
           "Servers holding an exclusive lock on something may object.\n"
           "- **Edits do not propagate.** Change the command here and the deployed `.mcp.json` "
           "still has the old one until you deploy again. Unlike a tool, there is no live pointer "
           "— this is a copy."),
        md("c501-try",
           "## Try it\n\n"
           "1. Deploy this connector to `bundle` and open `data/.mcp.json`.\n"
           "2. Add a second server to that file by hand, then deploy again — your entry "
           "survives.\n"
           "3. **Undeploy** from the Deployed section and confirm only this connector's key went.\n"
           "4. Deploy to `project`, restart your CLI, and ask it what MCP servers it has.\n\n"
           "That completes the Connectors section. Next: Plugins, which ship all of this as one "
           "unit."),
    ])

# ---------------------------------------------------------------------------
# Plugins: 101 -> 501
# ---------------------------------------------------------------------------

PLUGIN_101 = notebook(
    "plugin-101", "Plugin 101 · Review Kit",
    "Beginner plugin — bundles a code-reviewer agent, a commit-message skill and the "
    "word-count tool into one installable Claude Code plugin.",
    "Plugins", 101, kind="plugin",
    extra_meta={"plugin": {"version": "1.0.0", "author": "Arima Notebooks",
                           "members": ["agent-201", "skill-101", "tool-101"]}},
    cells=[
        md("p101-what",
           "# Review Kit\n\n"
           "A **plugin** is how a definition leaves Arima and becomes part of a CLI. It bundles "
           "members — agents, skills and tools — into one directory your agentic CLI "
           "reads.\n\n"
           "This one ships three, named in the banner above:\n\n"
           "| Member | Kind | Gives you |\n|---|---|---|\n"
           "| `agent-201` | " + AGENT_ICON + " agent | a code reviewer |\n"
           "| `skill-101` | " + SKILL_ICON + " skill | Conventional Commits messages |\n"
           "| `tool-101` | " + TOOL_ICON + " tool | word/line/char counts |"),
        md("p101-build",
           "## What Deploy writes\n\n"
           "Pick a target in the banner and press **Deploy**:\n\n"
           "```\n"
           "plugins/plugin-101-review-kit/\n"
           "  .claude-plugin/plugin.json   name, version, description, author\n"
           "  agents/<name>.md             each member agent, frontmatter + instructions\n"
           "  skills/<name>/SKILL.md       each member skill\n"
           "  commands/<name>.md           one per member tool\n"
           "  .mcp.json                    points back at Arima's MCP server\n"
           "  README.md                    these markdown cells\n```\n\n"
           "Member **tools are not copied**. They stay here, and the generated command calls them "
           "over Arima's MCP server — one source of truth, and editing the tool notebook "
           "changes the deployed behaviour with no redeploy."),
        md("p101-targets",
           "## The three targets\n\n"
           "| Target | Lands in | Use when |\n|---|---|---|\n"
           "| **project** | the repo root's `.claude/` | the bundle is for this checkout |\n"
           "| **user** | `~/.claude/` | you want it in every project on this machine |\n"
           "| **bundle** | `data/plugins/` | you just want to read what would be written |\n\n"
           "Every deploy is recorded, so the **Deployed** section in the Agents tab can undo it "
           "— it removes exactly the files Arima wrote, and nothing else."),
        md("p101-next",
           "## Try it\n\n"
           "1. Deploy to `bundle` and read every file it wrote. Six of them, and none is "
           "mysterious.\n"
           "2. Create your own plugin (**+ New Plugin**), add one of your agents as a member, and "
           "deploy to `bundle` too.\n"
           "3. Deploy an agent on its own (the download button on its card) to see the difference "
           "between one file and a bundle.\n\n"
           "Next: `plugin-201` on what belongs in a bundle together."),
    ])

PLUGIN_201 = notebook(
    "plugin-201", "Plugin 201 · Choosing Members",
    "Intermediate plugin — what belongs in one bundle, what does not, and why the answer is "
    "about the person installing it.",
    "Plugins", 201, kind="plugin",
    extra_meta={"plugin": {"version": "0.1.0", "author": "Arima Notebooks", "members": []}},
    cells=[
        md("p201-question",
           "# Choosing Members\n\n"
           "A plugin can hold any mix of agents, skills and tools. The question is not *what can go "
           "in* — it is **what the person installing it is trying to get**.\n\n"
           "A plugin is a unit of installation. Someone adds it because they want a capability, "
           "not because they want seven files. So the test for every member is:\n\n"
           "> If I removed this, would the plugin still deliver what its description promises?\n\n"
           "If yes, it belongs in a different plugin."),
        md("p201-shapes",
           "## Shapes that work\n\n"
           "**The workflow kit.** One agent, the tools it needs, and the skills that shape its "
           "output. `review-kit` is this: reviewer + counter + commit format. Coherent because you "
           "want all of it or none of it.\n\n"
           "**The house style.** Several skills, no agents. Commit messages, PR descriptions, "
           "changelog entries, code comments. Installed once per machine, fires when relevant. The "
           "most underrated shape.\n\n"
           "**The integration.** A connector plus the tools that wrap it plus one agent that knows "
           "how to drive them. Ships access to a system.\n\n"
           "**The single tool, nicely packaged.** One tool and nothing else, so the CLI gets a "
           "slash command instead of a raw MCP name. Perfectly legitimate."),
        md("p201-against",
           "## Shapes that do not work\n\n"
           "- **Everything I have made.** A plugin is not a backup. Nobody installs \"my stuff\".\n"
           "- **Two unrelated workflows.** If half the members only matter to backend work and half "
           "to the docs, that is two plugins, and people want one of them.\n"
           "- **An agent whose tools live in a different plugin.** Members are resolved at deploy "
           "time from *your* notebooks; a user installing only one half gets an agent granting a "
           "tool that is not there. Keep an agent and its tools together.\n"
           "- **A plugin that is one skill.** Deploy the skill directly — the bundle is "
           "overhead with nothing to show for it."),
        md("p201-members",
           "## Working with the member list\n\n"
           "Press **+ member** in the banner and Arima lists every agent, skill and tool you have, "
           "with its id. Members are stored as ids, which has two consequences worth knowing:\n\n"
           "- **Renaming a member is safe.** The id does not change, so the bundle keeps building. "
           "Only the generated filename changes.\n"
           "- **Deleting a member is not an error.** The card shows it in red as missing, and a "
           "deploy skips it with a warning rather than failing. Convenient, but it means a bundle "
           "can quietly lose a piece — check the card before you ship.\n\n"
           "Plugins do not nest. Listing a plugin as a member of a plugin is ignored."),
        md("p201-try",
           "## Try it\n\n"
           "This plugin is deliberately empty. Fill it:\n\n"
           "1. Add `skill-101` and deploy to `bundle`. One skill, one file.\n"
           "2. Add `tool-101` and deploy again — note the `commands/` entry and the new "
           "`.mcp.json`.\n"
           "3. Add `agent-201`, deploy, and read `agents/`.\n"
           "4. Now ask the real question: would you install this? Write the description it would "
           "need to earn that, and see whether the members match the promise.\n\n"
           "Next: `plugin-301` opens the bundle up file by file."),
    ])

PLUGIN_301 = notebook(
    "plugin-301", "Plugin 301 · Inside the Bundle",
    "Intermediate plugin — every file a deploy writes, what reads it, and why the tools are "
    "pointers rather than copies.",
    "Plugins", 301, kind="plugin",
    extra_meta={"plugin": {"version": "1.0.0", "author": "Arima Notebooks",
                           "members": ["agent-101", "skill-101", "tool-101"]}},
    cells=[
        md("p301-layout",
           "# Inside the Bundle\n\n"
           "Deploy this plugin to `bundle` and open `data/plugins/plugin-301-inside-the-bundle/`. "
           "Six files, each with one job:\n\n"
           "```\n"
           ".claude-plugin/plugin.json   the manifest - this is what makes it a plugin\n"
           "agents/<name>.md             one per member agent\n"
           "skills/<name>/SKILL.md       one per member skill\n"
           "commands/<name>.md           one per member tool\n"
           ".mcp.json                    how the commands reach Arima\n"
           "README.md                    these cells\n"
           "```\n\n"
           "Nothing is generated that you could not have written by hand. That is the point — "
           "you can read a bundle and know exactly what installing it does."),
        md("p301-manifest",
           "## plugin.json\n\n"
           "```json\n"
           "{\n"
           "  \"name\": \"plugin-301-inside-the-bundle\",\n"
           "  \"version\": \"1.0.0\",\n"
           "  \"description\": \"Intermediate plugin - every file a deploy writes...\",\n"
           "  \"author\": { \"name\": \"Arima Notebooks\" }\n"
           "}\n```\n\n"
           "| Field | Comes from |\n|---|---|\n"
           "| `name` | the notebook name, kebab-cased — also the directory name |\n"
           "| `version` | the **version** box in the banner |\n"
           "| `description` | the notebook description, whitespace collapsed to one line |\n"
           "| `author` | the **author** field in `metadata.plugin` |\n\n"
           "The CLI reads this to decide the plugin exists at all. A bundle without it is just a "
           "directory."),
        md("p301-members",
           "## The member files\n\n"
           "**Agents and skills** are copies. Frontmatter generated from the notebook's name, "
           "description and tool grants, then every markdown cell as the body. Self-contained — "
           "they work whether or not Arima is running.\n\n"
           "**Tools are not copies.** A tool's body is Python or C# or JShell; copying it would "
           "mean copying a runtime. Instead you get a command:\n\n"
           "```markdown\n"
           "---\n"
           "description: Counts words, lines and characters in a block of text.\n"
           "---\n\n"
           "Invoke the Arima tool `tool-101-word-count` over the `arima` MCP server\n"
           "(tool name `arima_tool_101_word_count`).\n\n"
           "Parameters:\n\n"
           "- `text` (string, required) - The text to measure\n"
           "- `detailed` (boolean, optional) - Also list the five most frequent words\n"
           "```\n\n"
           "The CLI user types a slash command; the call lands in Arima; your notebook's body runs."),
        md("p301-mcp",
           "## .mcp.json, and the trade it makes\n\n"
           "```json\n"
           "{ \"mcpServers\": { \"arima\": {\n"
           "    \"type\": \"sse\",\n"
           "    \"url\": \"http://127.0.0.1:8585/api/mcp/sse\" } } }\n"
           "```\n\n"
           "Written only when the plugin has at least one tool member — a bundle of pure "
           "skills does not need it.\n\n"
           "The address is **loopback**, which decides the trade:\n\n"
           "| | |\n|---|---|\n"
           "| **Gained** | edit the tool notebook and every installed copy changes, with no redeploy |\n"
           "| **Gained** | the implementation never leaves your machine |\n"
           "| **Cost** | tool commands only work while Arima is running |\n"
           "| **Cost** | the bundle is not portable to another machine's CLI unless Arima runs there too |\n\n"
           "Skills and agents in the same bundle keep working regardless. Only the tool commands "
           "depend on the pointer."),
        md("p301-try",
           "## Try it\n\n"
           "1. Deploy to `bundle` and read all six files.\n"
           "2. Edit `tool-101`'s body, then look at the bundle again — unchanged, because the "
           "command is a pointer.\n"
           "3. Edit `skill-101`'s text and look again — also unchanged, because that one *is* "
           "a copy and needs a redeploy.\n"
           "4. Remove the tool member and redeploy. `commands/` and `.mcp.json` both go.\n\n"
           "Next: `plugin-401` on versions and iterating."),
    ])

PLUGIN_401 = notebook(
    "plugin-401", "Plugin 401 · Versioning and Iterating",
    "Advanced plugin — what a redeploy actually does, how removed members are cleaned up, "
    "and how to iterate without leaving debris behind.",
    "Plugins", 401, kind="plugin",
    extra_meta={"plugin": {"version": "0.2.0", "author": "Arima Notebooks",
                           "members": ["skill-101", "tool-101"]}},
    cells=[
        md("p401-redeploy",
           "# Versioning and Iterating\n\n"
           "A redeploy is not a merge and not a wipe. Arima remembers every path the last deploy "
           "wrote, so on the next one it:\n\n"
           "1. Builds the bundle fresh from the current notebooks.\n"
           "2. Writes every file the new build produces, overwriting as needed.\n"
           "3. **Deletes the files the last deploy wrote that this one did not** — the member "
           "you removed, the skill you renamed.\n"
           "4. Replaces the record with the new file list.\n\n"
           "Which means you never accumulate orphans, and you never have to clean up by hand after "
           "a rename. It also means a file you added to the bundle directory yourself will be "
           "removed, because Arima did not write it."),
        md("p401-version",
           "## The version field\n\n"
           "`version` goes straight into `plugin.json`. Arima does not interpret it — it does "
           "not compare versions, refuse a downgrade, or keep old copies. It is for the humans and "
           "the CLI.\n\n"
           "A convention that works:\n\n"
           "| Bump | When |\n|---|---|\n"
           "| patch `1.0.1` | wording, a tightened skill, a fixed tool body |\n"
           "| minor `1.1.0` | a new member, a new tool parameter with a default |\n"
           "| major `2.0.0` | a member removed, a tool's required parameters changed |\n\n"
           "The major case is the one that matters: someone's workflow calls your tool with "
           "specific arguments. Changing a required parameter breaks them silently, and the version "
           "is the only warning they get."),
        md("p401-loop",
           "## The iteration loop\n\n"
           "```\n"
           "edit a member notebook\n"
           "   -> deploy to bundle\n"
           "   -> read the generated files\n"
           "   -> deploy to project\n"
           "   -> restart the CLI and actually use it\n"
           "   -> back to the top\n"
           "```\n\n"
           "Two shortcuts that are not shortcuts:\n\n"
           "- **Skipping `bundle`.** It costs one click and shows you precisely what you are about "
           "to install. Skip it only once the bundle's shape has stopped changing.\n"
           "- **Skipping the restart.** Agents and skills are read when the CLI starts. You will "
           "test the old copy and conclude your edit did nothing.\n\n"
           "Tool bodies are the exception — those are live, because the command is a pointer "
           "into Arima. Edit and call again."),
        md("p401-hygiene",
           "## Hygiene\n\n"
           "- **One plugin, one target.** Deploying to both `project` and `user` leaves two "
           "installs, and the CLI will load whichever it finds — usually not the one you just "
           "edited.\n"
           "- **Check the Deployed section after a rename.** The old files should be gone. If one "
           "is listed as *files missing*, someone deleted it outside Arima and the record is "
           "stale; undeploy to clear it.\n"
           "- **Undeploy before you delete a plugin notebook.** Once the notebook is gone there is "
           "nothing to deploy from, but the record and the installed files remain.\n"
           "- **Do not hand-edit a deployed bundle.** The next redeploy overwrites it. Edit the "
           "notebook."),
        md("p401-try",
           "## Try it\n\n"
           "1. Deploy to `bundle` and list the files.\n"
           "2. Remove `tool-101` from the members and deploy again. `commands/` and `.mcp.json` are "
           "gone — Arima cleaned up after itself.\n"
           "3. Rename this notebook, deploy, and check that the old directory is not left behind.\n"
           "4. Bump `version` to `1.0.0`, deploy, and read `plugin.json`.\n\n"
           "Next: `plugin-501` ships it."),
    ])

PLUGIN_501 = notebook(
    "plugin-501", "Plugin 501 · Shipping to the Harness",
    "Advanced plugin — installing a bundle into Claude Code, Copilot or Antigravity, "
    "verifying every piece loaded, and handing it to someone else.",
    "Plugins", 501, kind="plugin",
    extra_meta={"plugin": {"version": "1.0.0", "author": "Arima Notebooks",
                           "members": ["agent-201", "skill-101", "tool-101"]}},
    cells=[
        md("p501-install",
           "# Shipping to the Harness\n\n"
           "Deploying to `project` or `user` puts the bundle under that scope's `.claude/plugins/`, "
           "which is where Claude Code looks. Restart the CLI and it is installed.\n\n"
           "| Target | Directory | Who gets it |\n|---|---|---|\n"
           "| `project` | `<repo>/.claude/plugins/<slug>/` | this checkout; commit it and the team does too |\n"
           "| `user` | `~/.claude/plugins/<slug>/` | every project on this machine |\n"
           "| `bundle` | `data/plugins/<slug>/` | nobody — staged for reading |\n\n"
           "For Copilot and Antigravity the same members deploy individually into `.github/` and "
           "`.antigravity/` — pick the provider on each member's own card. The plugin **bundle "
           "format is Claude Code's**; the others take the files, not the bundle."),
        md("p501-verify",
           "## Verify each piece, separately\n\n"
           "A bundle can be half-working and look fine. Check the three kinds independently:\n\n"
           "| Piece | How to check | If it fails |\n|---|---|---|\n"
           "| " + PKG_ICON + " the plugin | the CLI lists it among its plugins | `plugin.json` is missing or malformed |\n"
           "| " + AGENT_ICON + " an agent | invoke it by name | check the frontmatter in `agents/<name>.md` |\n"
           "| " + SKILL_ICON + " a skill | say its trigger phrase | the description — see `skill-301` |\n"
           "| " + TOOL_ICON + " a tool | run its slash command | **is Arima running?** |\n\n"
           "That last row is the one that catches people. Tool commands are pointers at a running "
           "Arima. If the agent and skill work but the tool command does not, start Arima before "
           "you debug anything else."),
        md("p501-sharing",
           "## Handing it to someone else\n\n"
           "**A teammate, same repo.** Deploy to `project` and commit `.claude/plugins/<slug>/`. "
           "They pull, restart, and have it. Agents and skills work immediately. Tool commands need "
           "Arima running on **their** machine — say so in the plugin's README, which is what "
           "its markdown cells become.\n\n"
           "**Someone without Arima.** Agents and skills are plain markdown and work anywhere. "
           "Tools do not travel. If the bundle must stand alone, rewrite the tools as skills that "
           "describe the work, or accept that the recipient needs Arima.\n\n"
           "**Back upstream.** If a plugin would help other Arima users, the member *notebooks* are "
           "the thing to contribute, not the bundle — ask your agentic CLI to package them as "
           "a PR, following `AGENTS.md`."),
        md("p501-checklist",
           "## Before you ship\n\n"
           "- [ ] The description says what someone gets, not what it contains.\n"
           "- [ ] No member shows as missing on the plugin's card.\n"
           "- [ ] Deployed to `bundle` and every generated file read at least once.\n"
           "- [ ] No secret in any member — especially a connector's `env`.\n"
           "- [ ] `version` bumped, major if a tool's required parameters changed.\n"
           "- [ ] Installed to `project`, CLI restarted, and all three kinds verified.\n"
           "- [ ] The README cells say whether Arima must be running.\n"
           "- [ ] You know how to undo it — the Deployed section, and **Undeploy**."),
        md("p501-end",
           "## The whole arc\n\n"
           "That is the factory, end to end:\n\n"
           "| Section | Builds | Deploys as |\n|---|---|---|\n"
           "| " + TOOL_ICON + " Tools | a callable function | a pointer over MCP |\n"
           "| " + SKILL_ICON + " Skills | know-how | a markdown file |\n"
           "| " + AGENT_ICON + " Agents | a worker with tools | a markdown file |\n"
           "| " + PLUG_ICON + " Connectors | access to an outside server | an `.mcp.json` entry |\n"
           "| " + PKG_ICON + " Plugins | all of the above, together | an installable directory |\n\n"
           "Five kinds, one storage format — every one of them is a notebook you can open, "
           "read and run. Nothing in the bundle is generated from something you cannot see.\n\n"
           "Go build the thing you actually needed when you started reading."),
    ])

# ---------------------------------------------------------------------------

NEW = [
    TOOL_101, TOOL_201, TOOL_301, TOOL_401, TOOL_501,
    SKILL_201, SKILL_301, SKILL_401, SKILL_501,
    CONNECTOR_101, CONNECTOR_201, CONNECTOR_301, CONNECTOR_401, CONNECTOR_501,
    PLUGIN_101, PLUGIN_201, PLUGIN_301, PLUGIN_401, PLUGIN_501,
]

# Tutorials authored before the track was sectioned: re-tag them so they land in the right section.
RETAG = {
    "agent-101": "Agents", "agent-201": "Agents", "agent-301": "Agents",
    "agent-401": "Agents", "agent-501": "Agents", "agent-601": "Agents",
    "skill-101": "Skills",
}


def main():
    for nb in NEW:
        path = TUTORIALS / f"{nb['id']}.anb"
        path.write_text(json.dumps(nb, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote    {path}")

    for nid, section in RETAG.items():
        path = TUTORIALS / f"{nid}.anb"
        if not path.exists():
            print(f"missing  {path} - skipped")
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("metadata", {}).get("subcategory") == section:
            continue
        data.setdefault("metadata", {})["subcategory"] = section
        path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"re-tagged {path} -> {section}")


if __name__ == "__main__":
    main()
