---
name: add-tutorial
description: Create a new Arima Notebooks tutorial notebook (.vnb) that conforms to the built-in tutorial library format. Use when the user wants to ship a new lesson — JShell-301, TS-501, etc. Generates a properly-shaped .vnb JSON, registers it with NotebookService, and updates README.md tutorial table.
---

# add-tutorial — author a built-in tutorial notebook

## When to invoke

The user says "add a tutorial on X" / "write a beginner notebook for Y" / "extend the tutorial library."

## File shape

Tutorials live under `notebooks/tutorials/<id>.vnb`. The `id` follows the convention `<lang>-<level>`:

- `<lang>` ∈ `jshell`, `java`, `js`, `ts`, `csharp`, `fsharp`, `cpp`
- `<level>` ∈ `101` (beginner), `201` (intermediate), `301` (advanced), `401` (functional/concurrency), `501` (data science / patterns), `601` (expert)

The `.vnb` is a JSON file matching `com.barista.model.Notebook`. Look at `notebooks/tutorials/js-101.vnb` as the canonical template.

## Authoring rules

1. **Read-only** — tutorials open in read-only mode in the UI. Users fork them into their personal notebook collection if they want to edit.
2. **Progressive** — each cell builds on the previous one. No "skip this if confused."
3. **Self-contained** — no external package installs required beyond what Arima Notebooks ships pre-loaded.
4. **Markdown headers** between code cells explain the *why*. Code cells carry the *what* via clear identifiers.
5. **No emojis** in code or markdown unless the user explicitly asks.
6. **Mode tagged** — every code cell carries the correct `mode` (`jshell` / `java` / `nodejs` / `typescript` / `csharp` / `fsharp` / `cpp`).
7. **Pipeline DSL** is welcome — `//@ anchor` / `//@ depends` annotations teach the dependency system early.

## How to run

1. Confirm the topic, language, and level with the user.
2. Read an existing tutorial of the same language and adjacent level — that establishes the voice.
3. Write the `.vnb` JSON under `notebooks/tutorials/`. Use unique cell IDs (UUIDs or `<id>-cell-N`).
4. Register the tutorial in `NotebookService` (look for the tutorial registry).
5. Add a row to the tutorial table in `README.md`.
6. Increment the tutorial count in `README.md` ("Arima Notebooks ships with N built-in tutorials") and in the brochure (`docs/brochure/arima-brochure.html`).
7. Run `arima rebuild && arima start` and verify the tutorial appears in **Notebook Browser → Arima Tutorials** and renders end-to-end.

## Quality bar

- Every code cell must run successfully on a fresh JShell / Node / dotnet / compiler install.
- Every markdown cell must read like a teacher, not a reference manual.
- Cover one concept per cell. Three lines of code is fine.
