# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x (current) | ✅ Yes |

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in Arima Notebooks, please report it privately:

1. **Email**: [suresh.chande@gmail.com](mailto:suresh.chande@gmail.com)  
   Use the subject line: `[Arima Security] <brief description>`

2. **GitHub Private Advisory** (preferred for confirmed vulnerabilities):  
   Go to **Security → Advisories → New draft security advisory** in this repository.

### What to include

- A description of the vulnerability and its potential impact
- Steps to reproduce (proof-of-concept code if applicable)
- The version(s) affected
- Any suggested fix, if you have one

### Response timeline

| Step | Target |
|------|--------|
| Acknowledge receipt | Within 48 hours |
| Initial assessment | Within 5 business days |
| Fix or mitigation | Depends on severity |
| Public disclosure | After fix is available |

We follow [coordinated vulnerability disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure).

---

## Security Notes for Users

### API Keys

- The **Anthropic API key** and **GitHub Token** you configure are stored in `data/settings.json` on your local machine.
- This file is listed in `.gitignore` and should **never be committed** to version control.
- Arima Notebooks serves on `localhost` only by default. Do not expose port 8585 to the public internet without adding authentication.

### Local-only by default

Arima Notebooks is designed to run **locally** on your development machine. It is not intended as a multi-user server without additional hardening. If you need shared/cloud deployment, enable OAuth authentication via Settings and ensure the server is behind a reverse proxy with TLS.

### Dependency vulnerabilities

Run `mvn dependency-check:check` to scan for known CVEs in Arima Notebooks' Java dependencies. Report any high-severity findings via the process above.
