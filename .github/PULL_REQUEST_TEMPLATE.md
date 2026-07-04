## Summary

<!-- What does this PR do? 1-3 bullet points. -->

-
-

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] Other: ___

## Related issues

Closes #

## Test plan

<!-- How did you verify this works? -->

- [ ] Ran `mvn test`
- [ ] Manually tested in browser at `http://localhost:8585`
- [ ] Tested the specific flow described in the issue

## Checklist

- [ ] Code follows the project style (no Lombok, plain getters/setters)
- [ ] No `data/settings.json` or API keys committed
- [ ] Docs updated if behavior changed (`docs/`, `docs.js`, `cheatsheet.html`)
- [ ] New endpoints added to `docs/API.md`
- [ ] Ran `./scripts/security-check.ps1` (or `.sh`) locally — 0 blocking findings
- [ ] Architecture rules in [`AGENTS.md`](../AGENTS.md) respected — no layer-crossings, no new outbound hosts, no Lombok

## Reviewers

This PR will be auto-marked **draft** until the `security-check` workflow reports 0 blocking findings. Once green, it requires a code-owner review from @snchande (founding contributor) per `CODEOWNERS`.

## AI-assisted changes

If this PR was authored with the help of an AI CLI (Claude, GitHub Copilot, Antigravity), briefly note:

- Which agent: ___
- Did the agent follow [`AGENTS.md`](../AGENTS.md)? [ ] yes
- Were any guardrails overridden manually? ___
