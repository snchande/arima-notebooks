# Contributing to Arima Notebooks

Thank you for your interest in contributing to Arima Notebooks. This document covers everything you need to know — from setting up your development environment to getting your pull request merged.

> **Working with AI coding agents?** Read [`AGENTS.md`](AGENTS.md) first. It is the single source of truth for the architecture rules every contributor — human or AI — must follow. Provider-specific files ([`CLAUDE.md`](CLAUDE.md), [`.github/copilot-instructions.md`](.github/copilot-instructions.md), [`GEMINI.md`](GEMINI.md)) all point at it.
>
> **Before you push:** run `pwsh ./scripts/security-check.ps1` (or `./scripts/security-check.sh`). The same script runs in CI and gates whether your PR can leave draft state. See [`AGENTS.md` §2.3](AGENTS.md) for the rules it enforces.
>
> **Want the big picture?** See the [Product Brochure](docs/brochure/arima-brochure.pdf) — feature set, architecture diagrams, install, and contribution workflow on 12 pages.
>
> **Claude Code users:** skills and subagents specific to Arima Notebooks live under [`.claude/`](.claude/README.md) — `architecture-check`, `add-execution-language`, `add-tutorial`, and `add-rest-endpoint` skills, plus `arima-architect`, `arima-security`, and `arima-tutorial-writer` subagents.

---

## Why Contribute?

Arima Notebooks fills a real gap: **the notebook never modernized for a polyglot, agentic world**. The classic notebook privileges a single language and assumes a human is the only author. Arima Notebooks rethinks it as a shared, cross-platform execution plane where many rich languages are equals and where humans and AI agents collaborate on the same document.

By contributing, you help:

- **Developers across many languages** — JavaScript, TypeScript, C#, F#, C++, Java, and more — learn, explore, and prototype faster in one place
- **People and agents** work together on the same notebook through the built-in co-pilot and MCP
- **Students** discover new languages through interactive, visual notebooks
- **Data scientists** gain the same fluid, exploratory workflow regardless of the language they reach for
- **The broader ecosystem** get a notebook that treats every language as first-class

This is a young, growing project. Your contributions have outsized impact — there is no huge backlog of existing contributors to compete with. Features you build will be used immediately by real developers.

---

## Contributing in an Agentic Cycle

Arima Notebooks is designed to be **reshaped by AI in your own loop, then contributed back through the same loop.** You do not need to spend a Saturday morning learning the codebase by hand. The recommended workflow is:

1. **Use Arima Notebooks.** Notice something missing or wrong.
2. **Open your AI CLI inside the repo** — `claude`, `copilot`, `agy` (Antigravity), whichever you have authenticated.
3. **Describe what you want** in plain English. The AI reads the architecture rules in [`AGENTS.md`](AGENTS.md), edits the right files, runs the build, iterates.
4. **Try it locally.** Refresh Arima Notebooks. Confirm it works for you.
5. **Ask the same AI to contribute it back:**
   > *"Run `pwsh ./scripts/security-check.ps1`, then package this as a PR back to upstream with a good description."*

That is the contribution path. The same loop that customizes Arima Notebooks for you produces the PR for everyone else — the bar to do both becomes the same bar.

### Examples of agentic prompts for contributions

| Goal | Sample prompt to your AI CLI |
|------|---|
| Add a language | *"Add a Kotlin execution mode following the pattern of `CppExecutionService.java`. Wire it into `ShellController` and add the JS frontend mode."* |
| Fix a bug | *"This stack trace shows a NullPointerException in `OrchestrationService.runPipeline`. Find the root cause and fix it. Add a test."* |
| New tutorial | *"Create a `notebooks/tutorials/java-701.vnb` covering Java 21 virtual threads. Five cells, beginner-to-intermediate."* |
| UI tweak | *"In the dark theme, the cell border for TypeScript cells is too saturated. Soften the blue and update `arima.css`."* |
| New API endpoint | *"Add a `GET /api/notebooks/{id}/stats` endpoint that returns cell count, total runs, and last-modified. Update `docs/API.md`."* |
| Close the loop | *"Run security-check, push the branch, open a PR with title `feat(kotlin): add Kotlin execution mode` and a clear description."* |

> If you prefer the traditional fork-edit-PR-by-hand workflow, the rest of this document still applies. The agentic cycle is the **fastest** path, not the only one.

---

## What You Can Contribute

You do not need to write code to contribute. All of the following are welcome:

| Type | Examples |
|------|---------|
| **Bug fixes** | Fix a JShell error message, fix a UI layout issue, fix a broken API endpoint |
| **New features** | New cell type, new execution engine, new export format |
| **Tutorials** | New `.vnb` tutorial notebooks for the built-in tutorial library |
| **Documentation** | Improve setup guides, add usage examples, fix typos |
| **UI/UX** | Improve the dark theme, add keyboard shortcuts, improve accessibility |
| **Testing** | Add unit tests, integration tests, edge case coverage |
| **Performance** | Faster JShell startup, better WebSocket handling, reduced memory usage |
| **Translations** | Translate the UI or documentation |

If you have an idea that doesn't fit a category above — open a Discussion and ask.

---

## Development Setup

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Git | Any | Version control |
| Java JDK | 17–21 | Build and run (JDK required, not JRE) |
| Maven | 3.8+ | Build tool |
| Node.js | 18+ | JavaScript cell execution (optional for Java-only work) |
| .NET SDK | 6.0+ | C# and F# cell execution (optional for Java/JS-only work) |

> **Use JDK 17 or 21** for development. JDK versions above 21 may compile fine but the project targets `--release 21` for compatibility with Spring Boot 3.2.x.

> **For C# / F# work**: install the [.NET SDK](https://dotnet.microsoft.com/download) (6.0 or later). No additional tools are needed — Arima Notebooks uses `dotnet run` for C# and `dotnet fsi` for F#, both included in the standard SDK.

### Fork and Clone

```bash
# 1. Fork the repo on GitHub (click Fork button)

# 2. Clone your fork
git clone https://github.com/YOUR_USERNAME/Arima.git
cd arima-notebooks

# 3. Add the upstream remote so you can pull future changes
git remote add upstream https://github.com/snchande/arima-notebooks.git
```

### Build and Run

```bash
# Build (skip tests for faster first build)
mvn clean package -DskipTests

# Run in dev mode
mvn spring-boot:run

# Open in browser
# http://localhost:8585
```

### Verify Your Setup

1. Open [http://localhost:8585](http://localhost:8585)
2. Open the **welcome.vnb** notebook
3. Run the first cell — you should see output
4. Open the **Packages** tab — it should load without errors

If any of these fail, check [docs/SETUP.md](docs/SETUP.md) for troubleshooting steps.

---

## Project Structure

```
src/main/java/com/barista/
├── controller/          # REST and WebSocket endpoints — add new endpoints here
├── service/             # Business logic — all execution engines and services
│   ├── JShellManager.java          # Core JShell session management
│   ├── JavaCompilerService.java    # Full javac compile + run
│   ├── NodeJsExecutionService.java # Node.js subprocess execution
│   ├── DotNetExecutionService.java # C# (dotnet run) + F# (dotnet fsi) + pipeline dep injection
│   ├── ClaudeService.java          # Anthropic API integration
│   ├── PackageService.java         # Maven Central downloads
│   ├── NuGetService.java           # NuGet package management for C#/F#
│   └── OrchestrationService.java   # Pipeline dependency graph (all 5 languages)
├── model/               # Data models — Notebook, Cell, Settings, etc.
└── shell/               # JShell session lifecycle

src/main/resources/
├── static/
│   ├── index.html       # Entire frontend — single HTML file
│   ├── css/arima.css    # All styles
│   └── js/              # Frontend modules
│       ├── app.js              # App initialization
│       ├── notebook.js         # Notebook editor
│       ├── console-tab.js      # Interactive console
│       ├── orchestration.js    # Pipeline UI
│       ├── packages.js         # Package manager UI
│       ├── ai-assistant.js     # Claude AI panel
│       └── docs.js             # In-app documentation overlay
└── application.properties

notebooks/tutorials/     # Built-in tutorial .vnb files
```

---

## Coding Standards

### Java

- **No Lombok** — all models use plain Java getters/setters with a manual static `Builder` inner class. This is a hard rule due to build compatibility.
- Follow existing patterns in the codebase — look at how similar things are already done before adding something new
- Spring Boot conventions apply: `@RestController`, `@Service`, `@Autowired` via constructor injection
- Keep controllers thin — business logic belongs in services
- JVM flags required for JShell: always include `--add-opens` and `--add-exports` flags when testing JShell-related code

### C# / F# Execution

- C# cells use `dotnet run` against a generated temp directory with a `.csproj`. Never use `dotnet-script` — it is not required.
- F# cells use `dotnet fsi --nologo --exec <file.fsx>`. No special project file needed.
- `DotNetExecutionService` manages the **session anchor cache** — a per-session map of anchor name → source code — used to inject dependency context into later cells. Changes to this class require updating both C# and F# paths.
- `//@ depends:` pipeline injection works by concatenating ancestor sources (with output suppressed) before the current cell's code. Preserve this model when adding new features.
- C# type declarations (`class`, `record`, `struct`, `enum`, `namespace`) must be placed after all top-level executable statements (CS8803). The `splitTypesAndStatements` method handles this — update it if you add new type-like constructs.

### Frontend

- **No build step** — the frontend is pure HTML/CSS/JavaScript served statically by Spring Boot
- No npm for frontend, no webpack, no TypeScript, no React — vanilla JS only
- All frontend code lives in `src/main/resources/static/`
- Follow the existing module pattern in the `.js` files
- CSS lives in `arima.css` — follow the existing custom property naming pattern (`--arima-*`)

### Commit Messages

Use the [Conventional Commits](https://www.conventionalcommits.org/) format:

```
type(scope): short description

Optional longer description.
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`

Examples:
```
feat(cell): add R language cell execution mode
fix(jshell): handle null output from incomplete snippets
docs(tutorials): add java-701 concurrency tutorial
chore(deps): upgrade Spring Boot to 3.2.4
```

---

## Making Changes

### For a Bug Fix

1. Check [existing issues](../../issues) — it may already be reported
2. Open a new issue describing the bug if one doesn't exist
3. Create a branch: `git checkout -b fix/describe-the-bug`
4. Fix it, test it manually
5. Open a PR referencing the issue

### For a New Feature

1. **Open a Discussion or Issue first** — describe what you want to build and why
2. Wait for feedback before writing code — this prevents wasted effort if the feature doesn't fit the project direction
3. Once discussed and agreed, create a branch: `git checkout -b feature/describe-the-feature`
4. Build it, test it
5. Open a PR with a clear description of what changed and why

### For a New Tutorial

Tutorials are `.vnb` JSON files stored in `notebooks/tutorials/`. The easiest way:

1. Open Arima Notebooks locally and create the tutorial notebook interactively
2. Save it as a `.vnb` file
3. Copy it to `notebooks/tutorials/`
4. Add its metadata to the tutorial registry in `NotebookService.java`
5. Open a PR

Tutorial naming convention: `{language}-{level}.vnb` (e.g., `java-701.vnb`, `js-601.vnb`, `csharp-301.vnb`, `fsharp-201.vnb`)

Example notebooks (cross-notebook demos, showcases) go in `notebooks/examples/` and are automatically picked up by the tutorial browser under the **Examples & Demos** subcategory. Set `metadata.subcategory` to `"Examples & Demos"` and `metadata.language` to the appropriate language.

---

## Who Reviews and Merges

Arima Notebooks has a two-tier maintainer structure:

- **Founding Maintainer** — created the project and holds final authority over what gets merged into `main`, the roadmap, and releases. All significant PRs require their approval.
- **Co-Maintainers** — trusted contributors invited by the Founding Maintainer to help with reviews, triage, and merging of non-controversial changes.

See [MAINTAINER.md](MAINTAINER.md) for the full governance model, including how co-maintainership works and how new partners can join the maintainer team.

---

## Pull Request Process

### Before Submitting

- [ ] Your branch is up to date with `main` (`git pull upstream main`)
- [ ] The project builds cleanly: `mvn clean package -DskipTests`
- [ ] You've tested your changes manually against a running server
- [ ] If you added a new endpoint, it's documented in `docs/API.md`
- [ ] If you changed user-facing behavior, `docs/USAGE.md` is updated
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`

### Submitting the PR

1. Push your branch to your fork: `git push origin your-branch-name`
2. Open a Pull Request against the `main` branch of the upstream repo
3. Use this template for your PR description:

```markdown
## What does this PR do?
Brief description of the change.

## Why?
The motivation — what problem does it solve or what value does it add?

## How was it tested?
Steps you took to verify the change works.

## Checklist
- [ ] Builds cleanly (mvn clean package -DskipTests)
- [ ] Tested manually
- [ ] Docs updated (if applicable)
- [ ] CHANGELOG.md updated
```

### Review Process

- A maintainer will review your PR within a few days
- Reviews may request changes — this is normal and not a rejection
- Address feedback by pushing additional commits to the same branch
- Once approved, the maintainer will merge using squash merge

### What Makes a PR Likely to Be Accepted

- **Focused** — one thing per PR. A PR that fixes a bug AND refactors unrelated code is harder to review.
- **Tested** — you've run it and it works
- **Discussed first** — for features, you've had a pre-PR conversation (avoids building something that won't be merged)
- **Documented** — if it changes behavior visible to users, docs are updated

---

## Reporting Issues

When filing a bug, include:

- **Arima Notebooks version** (shown in the Settings tab or `arima version`)
- **OS and Java version** (`java -version`)
- **Steps to reproduce** — be specific
- **Expected behavior** vs **actual behavior**
- **Browser console errors** if it's a frontend issue (F12 → Console)
- **Server logs** if it's a backend issue (run `arima logs` or check terminal output)

---

## Community Guidelines

Be respectful. This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). In short:

- Be kind and patient
- Assume good faith
- Criticism is about code, not people
- Harassment of any kind is not tolerated

---

## Questions?

Open a [GitHub Discussion](../../discussions) — for ideas, questions, "how do I..." — anything that isn't a bug report or feature request.

Issues are for actionable bugs and confirmed feature requests. Discussions are for everything else.
