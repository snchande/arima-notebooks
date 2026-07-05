---
name: add-execution-language
description: Scaffold a new language execution service in Arima Notebooks following the existing seven-service pattern. Use when the user asks to add support for a new language (e.g. "add Python support" or "add Kotlin"). Creates the service, wires it into ShellController, adds a CellType mode, and registers the cell badge in the frontend — all preserving the unified ExecutionResult contract.
---

# add-execution-language — scaffold a new runtime

This skill walks through the steps needed to add a new language to Arima Notebooks. Read [`AGENTS.md`](../../../AGENTS.md) first.

## Prerequisites

- The new language has a CLI/REPL/compiler that can be invoked as a subprocess.
- You can describe how a single cell of the new language should run — input shape, output shape, timeout.
- The user has confirmed the addition (this is an architecture change — `AGENTS.md` §2.2 — and the founding contributor should bless it).

## Files to touch

| File | Purpose | Pattern to copy |
|------|---------|-----------------|
| `src/main/java/com/barista/service/<Lang>ExecutionService.java` | New service | Copy `NodeJsExecutionService.java` for interpreted; `CppExecutionService.java` for compiled |
| `src/main/java/com/barista/controller/ShellController.java` | Routes by cell mode | Add a `case` in `executeCell()` switch |
| `src/main/java/com/barista/model/Cell.java` | Mode enum/string | Add a constant if you use an enum |
| `src/main/resources/static/index.html` | Cell badge + mode-class CSS | Add `.cell.mode-<lang>` and `.cell-badge.<lang>` blocks |
| `src/main/resources/static/js/notebook.js` | Mode-cycle button | Add the new mode to the cycle list |
| `src/main/resources/static/js/ai-assistant.js` | Conversion prompts | Add the new language to AI conversion pairs |
| `docs/ARCHITECTURE.md` | Document the runtime | Add a §Execution Model entry |
| `README.md` | Update language list | Touch the badges + features table |
| `notebooks/tutorials/<lang>-101.vnb` | Smoke tutorial | Use the existing `js-101.vnb` as template |

## The execution-service contract

Every execution service must:

1. Expose a method that takes `(String code, ExecutionContext ctx)` and returns `ExecutionResult`.
2. Stream stdout / stderr to the STOMP `/ws` endpoint **while the cell runs** — do not buffer the whole output.
3. Apply a timeout (default 60s) and capture both compile-time and run-time errors.
4. Normalise file paths and line numbers in error messages so they point at the user's cell, not the temp file.
5. Implement `//@ anchor` / `//@ depends` injection: ancestor declarations at global scope, ancestor statements before the current cell's statements.
6. Honour the local-first guarantee — only the language's own package manager may reach the network.

## How to run

1. Confirm scope with the user — which subset of the language?
2. Read the closest existing service (interpreted → `NodeJsExecutionService`; compiled → `CppExecutionService`).
3. Write the new service following the contract above.
4. Add the badge, mode-class CSS, and mode-cycle entry in the frontend.
5. Add a smoke tutorial under `notebooks/tutorials/`.
6. Update `docs/ARCHITECTURE.md` and `README.md`.
7. Run `mvn test` and the security check.
8. Verify the cell badge appears in the browser, the cell runs, and live output streams.
9. Update `AGENTS.md` execution-services list.
10. Open a PR — this is an architecture change, so flag it explicitly in the PR description.

## What not to do

- Don't introduce a new shape of `ExecutionResult`. If you need new fields, add them as optional to the existing class.
- Don't open a new WebSocket endpoint. Reuse `/ws`.
- Don't add a new build step (no Webpack, no esbuild).
- Don't ship without a tutorial — every language gets at least a `<lang>-101.vnb`.
