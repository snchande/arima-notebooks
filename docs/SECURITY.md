# Security Model

Arima Notebooks executes code. `POST /api/shell/execute`, the pipeline endpoints
and the MCP tools all run whatever they are handed - in Java, JShell, JavaScript,
TypeScript, C#, F#, C++ and Python - as **the operating-system user who started the
server**, with that user's full access to files, network and processes.

That is the point of the product. It is also the whole of its threat model: anything
that can reach the port can run code as you.

---

## The rule

**Arima listens on loopback only, and refuses requests that did not come from this
machine.**

There is no authentication in the default `local` auth mode, by design - it is a tool
you run on your own laptop, and a login prompt for yourself is friction with no
benefit. The security boundary is therefore *reachability*, not credentials, and it
has to hold absolutely.

## How that is enforced

Three independent controls, so that no single mistake reopens the door.

| Control | Where | What it stops |
|---|---|---|
| **Loopback bind** | `server.address=127.0.0.1` in `application.properties` | The port is not open on any other interface. A machine on your network cannot connect at all - the TCP connection is refused, not merely rejected. |
| **Peer check** | `LocalAccessFilter` | Any request whose remote address is not a loopback address gets `403`. Catches a proxy, a port forward, or a future config change that widens the bind. |
| **Host check** | `LocalAccessFilter` | The `Host` header must name loopback. A page on an attacker's origin can point a hostname at `127.0.0.1` and the socket genuinely *is* local - DNS rebinding. Requiring the header closes it. |

CORS is restricted to Arima's own origins (`http://localhost:*`, `http://127.0.0.1:*`,
`http://[::1]:*`). It is **not** a wildcard: with CSRF disabled, `*` allowed any page
you happened to be visiting to POST code to your own machine and have it executed.

## What this was fixed from

Before this was in place, the server bound `0.0.0.0` and the following all succeeded
from another machine on the same network, unauthenticated:

- `POST /api/shell/execute` - arbitrary code execution as the logged-in user
- `POST /api/mcp/messages` - the same, plus reading and writing every notebook
- `GET /api/notebooks` - reading all notebook content
- `POST /actuator/shutdown` - stopping the server remotely

Anyone on the same Wi-Fi - a cafe, an office, a hotel - could have run code on the
machine. This is recorded because the class of bug matters more than the instance:
**any new endpoint inherits the same power**, so the boundary must never be assumed.

---

## Opening it to your local network (optional, off by default)

**Settings -> Network access** binds Arima to every interface instead of loopback, so
another device on your Wi-Fi or LAN can reach it. It takes effect on the next start,
because a listening socket cannot be re-bound underneath a running server.

Understand what you are turning on. Arima runs code as **you**, with no sandbox, and
does not authenticate callers. On a shared network - an office, a cafe, a hotel - that
includes people you cannot see.

### What still protects you

Everything that executes is **held until you approve it**:

| | |
|---|---|
| **Held, not queued** | The remote caller's HTTP request blocks. It cannot report success for code that never ran. |
| **Brought to you** | Arima raises the browser window with the code on screen, rather than leaving a line in a log. |
| **Refused by default** | A request nobody answers expires and is refused. Silence never means yes. |
| **Only you can decide** | `/api/approvals/*` refuses any non-loopback caller, so a remote agent cannot approve its own code. |

Reads (opening a notebook, the UI itself) are served normally once network access is
on - that is what enabling it means. Execution is what is gated:
`/api/shell/execute`, `/api/shell/run-to-here`, `/api/mcp/**`, `/api/agents/run`, and
`/actuator/shutdown`.

### If you did not turn this on

Switch it off in Settings and restart. The banner printed at startup, and the warning
in Settings, both say plainly when it is active.

---

## For contributors

### Do not widen the bind

`server.address` is a security control, not a convenience setting. Changing it, or
adding a second connector, exposes code execution to the network. If a feature seems
to need it, it needs the consent flow described below - not a wider bind.

### Every new endpoint executes with the user's full privileges

There is no sandbox. A new endpoint that runs, compiles, reads or writes anything is
as powerful as the shell. Assume it will be reachable by anything that can reach the
port, and keep the port unreachable.

### Do not disable the filter for tests

`LocalAccessFilter` runs at `HIGHEST_PRECEDENCE`. A test that needs to bypass it
should drive the service directly rather than turning the filter off, so the shipped
configuration is the one that was tested.

### Check the outbound allow-list

`scripts/security-check` blocks `Runtime.exec(String)` and flags new outbound hosts.
The allow-list is Maven Central, the npm registry, NuGet.org, PyPI, and the AI CLI
subprocesses. Adding a host is a deliberate decision, not a drive-by.

---

## What is deliberately *not* protected

- **Code in a notebook you open.** Opening a notebook and running its cells executes
  its code. A `.anb` file from someone else is as dangerous as a script from someone
  else - read it before running it. Arima does not sandbox cells and does not claim to.
- **The AI providers.** Claude, Copilot and Antigravity run as local CLI subprocesses
  under your own credentials. Arima stores no API keys and adds no isolation.
- **Other users of the same OS account.** Anything that can run as your user can reach
  a loopback port. Arima's boundary is the machine, not the account.

## Reporting a vulnerability

Open an issue at <https://github.com/snchande/arima-notebooks/issues>. If it is
sensitive, say so briefly and ask for a private channel before posting details.
