---
name: add-rest-endpoint
description: Scaffold a new REST endpoint following the Arima Notebooks controller→service pattern. Use when the user asks to add an API for X. Creates the controller method, a service method, a DTO if needed, an integration test, and updates docs/API.md — preserving the layered architecture.
---

# add-rest-endpoint — scaffold a REST endpoint

## When to invoke

User says "add an endpoint for X" / "expose a way to call Y from the UI" / "I need an API that does Z."

## The pattern

1. **Decide the resource and verb.** Endpoints follow REST: `GET /api/<resource>`, `POST /api/<resource>`, etc.
2. **Pick the right controller.** Existing controllers: `NotebookController`, `ShellController`, `PackageController`, `NuGetController`, `LLMController`, `SystemController`, `SettingsController`. Add a method to the closest match; create a new controller only if the resource doesn't fit any existing one.
3. **Write the service method first.** Business logic lives in a `@Service`. The controller is thin — validate inputs, call the service, return the DTO.
4. **Use plain Java DTOs.** No Lombok. Add getters/setters/manual `Builder` to `com.barista.model.*` if the DTO is reusable; otherwise keep it as a private static inner class on the controller.
5. **Document.** Add the endpoint to `docs/API.md` immediately. Future agents will rely on this.
6. **Test.** Add at least one Spring `@WebMvcTest` for the controller and a unit test for the service.

## Anti-patterns to refuse

- Controller-to-controller calls. The security check blocks this.
- Direct `Runtime.exec`, `Files.write`, `new HttpClient` in a controller. Move them into a service.
- New outbound HTTP hosts. The local-first guarantee is non-negotiable.
- A new WebSocket endpoint. Reuse `/ws`.
- A new persistence mechanism. Notebooks → `.vnb` files; settings → `data/settings.json`; packages → `data/packages/` etc. Pick one.

## How to run

1. Restate the proposal back to the user in one sentence — confirm the resource, verb, request/response shape, and which service owns the logic.
2. Read the closest existing controller + service pair (`NotebookController` + `NotebookService` is the cleanest example).
3. Write the service method, then the controller method, then the DTO.
4. Run `mvn test` and `pwsh ./scripts/security-check.ps1`.
5. Verify with `curl` or the UI tab.
6. Update `docs/API.md` and the cheat sheet if applicable.
