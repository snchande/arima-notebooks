/**
 * Arima Notebooks — AI Assistant
 *
 * Context-aware chat powered by the local Claude CLI (Pro plan).
 * Automatically includes the focused cell + notebook state in every message.
 * Responses containing code blocks show "Apply to cell" / "Insert as new cell" actions.
 *
 * Notebook Actions (auto-execute modes):
 *   autoInsert — first code block is automatically inserted as a new cell
 *   autoApply  — first code block is automatically applied to the focused cell
 */
const AIAssistant = (() => {
    let chatHistory  = [];
    let cellContext  = null;   // { id, mode, source, output, error, ... }
    let nbContext    = null;   // { notebookName, cellCount, anchors, ... }
    let directEdit   = false;  // when true, first code block auto-applied to focused cell
    let pendingAutoMode = null; // 'insert' | 'apply' | null — set by notebook action buttons
    let agenticAvailable = false; // true when the active provider's CLI is present (enables auto-apply)
    let lastUserMessage  = '';    // last prompt sent — used to detect edit intent for proactive apply

    // Heuristic: does the user's message ask AAA to change code (vs. just explain/answer)?
    const EDIT_INTENT = /\b(fix|update|change|refactor|improve|rewrite|add|convert|optimi\w*|replace|clean|correct|implement|complete|make it|simplif\w*|shorten|renam\w*)\b/i;

    // ── Init ──────────────────────────────────────────────────────────────
    function init() {
        document.getElementById('btn-ai-send')?.addEventListener('click', sendMessage);
        document.getElementById('btn-ai-clear')?.addEventListener('click', clearChat);
        document.getElementById('btn-ai-gen-nb')?.addEventListener('click', () =>
            document.getElementById('gen-modal')?.classList.remove('hidden'));
        document.getElementById('btn-gen-cancel')?.addEventListener('click', () =>
            document.getElementById('gen-modal')?.classList.add('hidden'));
        document.getElementById('btn-gen-confirm')?.addEventListener('click', generateNotebook);
        // Close button now routes through Arima.closeAi so the FAB + backdrop
        // stay in sync. app.js also wires this handler; both call setOpen(false).
        document.getElementById('btn-ai-close')?.addEventListener('click', () =>
            Arima.closeAi?.());

        document.getElementById('ai-input')?.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
        });

        // Quick-action chips
        document.querySelectorAll('.chip[data-prompt]').forEach(btn => {
            btn.addEventListener('click', () => {
                const input = document.getElementById('ai-input');
                if (input) { input.value = btn.dataset.prompt; sendMessage(); }
            });
        });

        // ── Notebook action buttons ───────────────────────────────────────
        document.getElementById('btn-ai-create-cell')?.addEventListener('click', () => {
            const input = document.getElementById('ai-input');
            if (!input?.value.trim()) {
                input.placeholder = 'Describe the cell to create…';
                input.focus();
                return;
            }
            pendingAutoMode = 'insert';
            sendMessage();
        });
        document.getElementById('btn-ai-update-cell')?.addEventListener('click', () => {
            const input = document.getElementById('ai-input');
            if (!input?.value.trim()) {
                if (!cellContext) {
                    Arima.setStatus('Click a cell first, then describe the update.');
                    return;
                }
                input.value = 'Improve and update this cell, fix any issues and make it cleaner.';
            }
            pendingAutoMode = 'apply';
            sendMessage();
        });
        document.getElementById('btn-ai-new-notebook')?.addEventListener('click', () => {
            document.getElementById('gen-modal')?.classList.remove('hidden');
        });

        // ── Direct-edit toggle ────────────────────────────────────────────
        document.getElementById('btn-ai-direct-edit')?.addEventListener('click', () => {
            directEdit = !directEdit;
            document.getElementById('btn-ai-direct-edit')?.classList.toggle('active', directEdit);
            document.getElementById('ai-direct-edit-banner')?.classList.toggle('visible', directEdit);
        });

        // ── Expand / collapse button ──────────────────────────────────────
        const EXPANDED_W = '680px';
        const COLLAPSED_W = '340px';
        document.getElementById('btn-ai-expand')?.addEventListener('click', () => {
            const sidebar = document.getElementById('ai-sidebar');
            if (!sidebar) return;
            const current = parseInt(sidebar.style.width || getComputedStyle(sidebar).width);
            if (current >= 650) {
                // collapse
                sidebar.style.width = localStorage.getItem('ai-sidebar-width') || COLLAPSED_W;
                document.getElementById('btn-ai-expand')?.setAttribute('title', 'Expand AI panel');
            } else {
                // expand
                sidebar.style.width = EXPANDED_W;
                document.getElementById('btn-ai-expand')?.setAttribute('title', 'Collapse AI panel');
            }
        });

        // ── Drag-to-resize handle ─────────────────────────────────────────
        const handle = document.getElementById('ai-resize-handle');
        if (handle) {
            handle.addEventListener('mousedown', (e) => {
                e.preventDefault();
                const sidebar = document.getElementById('ai-sidebar');
                if (!sidebar) return;
                const startX     = e.clientX;
                const startWidth = sidebar.getBoundingClientRect().width;

                function onMove(ev) {
                    // handle is on the LEFT edge — dragging left widens, right narrows
                    const dx  = ev.clientX - startX;
                    const newW = Math.max(280, Math.min(window.innerWidth * 0.75, startWidth - dx));
                    sidebar.style.width = newW + 'px';
                }
                function onUp() {
                    const w = parseInt(sidebar.style.width);
                    if (w < 600) localStorage.setItem('ai-sidebar-width', sidebar.style.width);
                    document.removeEventListener('mousemove', onMove);
                    document.removeEventListener('mouseup',   onUp);
                }
                document.addEventListener('mousemove', onMove);
                document.addEventListener('mouseup',   onUp);
            });
        }

        // Restore saved width on load
        const savedW = localStorage.getItem('ai-sidebar-width');
        if (savedW) {
            const sidebar = document.getElementById('ai-sidebar');
            if (sidebar) sidebar.style.width = savedW;
        }

        // Wire provider switcher buttons
        document.querySelectorAll('.ai-prov-btn').forEach(btn => {
            btn.addEventListener('click', () => _switchProvider(btn.dataset.provider));
        });

        // Update header + intro with active provider
        _updateProviderUI();
    }

    // Provider metadata
    const PROVIDERS = {
        claude_cli:  { label: 'Claude',      icon: '🤖', color: '#cba6f7' },
        copilot_cli: { label: 'Copilot',     icon: '🐙', color: '#f0883e' },
        gemini_cli:  { label: 'Antigravity', icon: '🚀', color: '#4285f4' },
    };

    async function _switchProvider(provider) {
        if (!PROVIDERS[provider]) return;

        // Mark button as switching
        document.querySelectorAll('.ai-prov-btn').forEach(b => b.classList.toggle('switching', b.dataset.provider === provider));

        try {
            // Save to settings
            const current = await Arima.api('GET', '/settings');
            await Arima.api('PUT', '/settings', { ...current, aiProvider: provider });
            if (Arima.state?.settings) Arima.state.settings.aiProvider = provider;

            _updateProviderUI();

            // Post a system message in chat announcing the switch
            const p = PROVIDERS[provider];
            _appendSwitchNotice(p.label, p.icon);
        } catch (e) {
            Arima.setStatus('Failed to switch provider: ' + e.message);
        } finally {
            document.querySelectorAll('.ai-prov-btn').forEach(b => b.classList.remove('switching'));
        }
    }

    function _appendSwitchNotice(label, icon) {
        const msgs = document.getElementById('ai-messages');
        if (!msgs) return;
        const div = document.createElement('div');
        div.className = 'ai-msg system-notice';
        div.innerHTML = `<div class="ai-msg-body" style="text-align:center;color:var(--text-3);font-size:12px;padding:4px 0">${icon} Switched to <strong>${label}</strong></div>`;
        msgs.appendChild(div);
        msgs.scrollTop = msgs.scrollHeight;
    }

    function _updateProviderUI() {
        Arima.api('GET', '/llm/provider').then(data => {
            const p = PROVIDERS[data.provider] || { label: data.provider, icon: '🤖', color: '#888' };
            agenticAvailable = !!data.available;

            // Header: fixed "Arima Agentic Assistant" branding; active provider shown in the badge
            const titleEl = document.getElementById('ai-provider-title');
            const iconEl  = document.getElementById('ai-prov-icon');
            const badge   = document.getElementById('ai-model-badge');
            if (titleEl) titleEl.textContent = 'Arima Agentic Assistant';
            if (iconEl)  iconEl.textContent  = p.icon;
            if (badge)   badge.textContent   = p.label + (data.model ? ' · ' + data.model : '');

            // Switcher active state + availability
            document.querySelectorAll('.ai-prov-btn').forEach(btn => {
                btn.classList.toggle('active', btn.dataset.provider === data.provider);
            });

            // Fetch availability for all providers and mark unavailable
            Arima.api('GET', '/settings/status').then(status => {
                const avail = {
                    claude_cli:  status.claudeCliAvailable,
                    copilot_cli: status.githubCopilotAvailable,
                    gemini_cli:  status.geminiCliAvailable,
                };
                document.querySelectorAll('.ai-prov-btn').forEach(btn => {
                    btn.classList.toggle('unavailable', !avail[btn.dataset.provider]);
                    const isActive = btn.dataset.provider === data.provider;
                    const available = !!avail[btn.dataset.provider];
                    btn.title = (PROVIDERS[btn.dataset.provider]?.label || btn.dataset.provider)
                        + (isActive ? ' — active' : '')
                        + (!available ? ' — not installed' : '');
                });
            }).catch(() => {});

            // Intro provider line
            const line = document.getElementById('ai-intro-provider-line');
            if (line) {
                if (data.available) {
                    const model = data.model ? ` (${data.model})` : '';
                    line.innerHTML = `${p.icon} Active: <strong style="color:${p.color}">${p.label}</strong>${model} — try a quick action above or ask anything!`;
                } else {
                    line.innerHTML = `${p.icon} <strong style="color:${p.color}">${p.label}</strong> not detected — install it or switch provider above.`;
                }
            }
        }).catch(() => {});
    }

    // Expose for settings.js to call after save
    function _updateIntroProvider() { _updateProviderUI(); }

    // ── Cell context (called from NotebookEditor when a cell gets focus) ──
    function updateCellContext(cellId) {
        const ctx = NotebookEditor?.getContext?.();
        if (!ctx) return;
        nbContext   = ctx;
        cellContext = ctx.cell;
        renderContextBadge();
    }

    function renderContextBadge() {
        const bar = document.getElementById('ai-ctx-bar');
        if (!bar) return;
        if (cellContext) {
            const modeIcon = { jshell: '☕', java: '♨', nodejs: '⬡', typescript: '◆', cpp: '⚙', python: '🐍' }[cellContext.mode] || '◈';
            bar.innerHTML = `
              <span class="ai-ctx-icon">${modeIcon}</span>
              <span class="ai-ctx-label">${nbContext?.notebookName || 'Notebook'}</span>
              <span class="ai-ctx-sep">›</span>
              <span class="ai-ctx-cell">${cellContext.anchor ? '#' + cellContext.anchor : 'focused cell'}</span>
              ${cellContext.error ? '<span class="ai-ctx-err" title="Cell has an error">⚠</span>' : ''}
              <button class="ai-ctx-clear" id="btn-ai-ctx-clear" title="Detach cell context">×</button>`;
            bar.style.display = 'flex';
            document.getElementById('btn-ai-ctx-clear')?.addEventListener('click', () => {
                cellContext = null; bar.style.display = 'none';
            });
        } else {
            bar.style.display = 'none';
        }
    }

    // ── Called from cell toolbar "Ask AI" button ──────────────────────────
    function sendWithContext(code, prompt) {
        const ctx = NotebookEditor?.getContext?.();
        if (ctx?.cell) { cellContext = ctx.cell; nbContext = ctx; }
        else cellContext = { source: code, mode: 'jshell' };
        renderContextBadge();
        Arima.openAi?.();
        const input = document.getElementById('ai-input');
        if (input) { input.value = prompt || ''; input.focus(); }
    }

    // ── Build system prompt with full notebook context ────────────────────
    function buildSystemPrompt() {
        const LANG = {
            jshell:     'JShell (Java snippet — no class wrapper)',
            java:       'Java (full class compile — must have public class Main { public static void main })',
            nodejs:     'JavaScript (Node.js)',
            typescript: 'TypeScript (Node.js with built-in type-stripping; optional tsc type-check)',
            csharp:     'C# (top-level program via dotnet run)',
            fsharp:     'F# (script via dotnet fsi)',
            cpp:        'C++ (MSVC / GCC / Clang subprocess)',
            python:     'Python 3 (python subprocess; PyPI packages via the PyPI tab)',
        };

        let sys = `You are the **Arima Agentic Assistant (AAA)** embedded in **Arima Notebooks** — an interactive multi-language notebook environment. You are grounded in the full Arima codebase (you may reference project files and AGENTS.md when relevant).

## Supported execution modes
| Mode | Language | Notes |
|------|----------|-------|
| jshell | Java 21 | Snippets at top-level, shared session state, pre-imported java.util.*/java.io.* |
| java | Java 21 | Full \`public class Main\` compilation, independent per cell |
| nodejs | JavaScript (Node.js 18+) | CommonJS + ESM, full Node APIs |
| typescript | TypeScript (Node.js 22.6+) | Built-in type-stripping; \`tsc --noEmit\` for type-check; uses same npm modules as JS |
| csharp | C# 9+ | Top-level program, no class wrapper needed |
| fsharp | F# | \`dotnet fsi\` script, functional style |
| python | Python 3.8+ | \`python\` subprocess; install libraries from the PyPI tab and \`import\` them |

## Your role
- Help the user write, debug, understand, and improve code in their Arima notebook cells
- **Proactively offer concrete changes.** When the request implies an edit (fix, improve, refactor, change, add, convert…), return the **complete, runnable** updated cell as a single code block in the focused cell's language — AAA applies it directly to that cell and the user can Undo with one click.
- When only explaining or answering, do NOT return a full replacement cell (it should not overwrite their code); use short illustrative snippets instead.
- Answer questions about the notebook's structure, dependencies, and execution flow
- If the user asks "why is this failing" check the last error in context before answering
- Be concise; working code over lengthy explanation

## Annotation DSL (for orchestration)
\`\`\`
//@ anchor: name        — names this cell (slug form)
//@ depends: a, b       — run cells a and b before this one
//@ description: text   — label shown in cell header
//@ pipeline: name      — marks a PIPELINE cell (defines execution plan)
//@ steps: a, b, c      — PIPELINE: ordered steps to execute
\`\`\`
Cross-notebook: \`//@ depends: notebook:notebookId/anchorName\`

## Arima helpers
- JShell/Java: \`ArimaDisplay.show(chart)\` — inline XChart
- C#: \`ArimaHtml("html")\`, \`ArimaDisplay(obj)\`, \`ArimaTable(IEnumerable)\`
- F#: \`baristaHtml "html"\`, \`baristaDisplay obj\`, \`baristaTable list\`

## C# / F# dependency injection
When a cell has \`//@ depends: anchor\`, Arima compiles and injects the ancestor source (output suppressed) so types/variables are in scope. No shared process — each cell execution is self-contained.`;

        // ── Notebook context ──
        if (nbContext) {
            sys += `\n\n---\n## Current notebook: "${nbContext.notebookName}"`;
            if (nbContext.notebookDesc) sys += ` — ${nbContext.notebookDesc}`;
            sys += `\n${nbContext.cellCount} cells total.`;

            // List all cells with their type, mode, and anchor for structure overview
            if (nbContext.allCells?.length) {
                sys += `\n\n### Notebook structure\n`;
                nbContext.allCells.forEach(c => {
                    const status = c.hasError ? ' ⚠ error' : c.executed ? ' ✓ run' : '';
                    const deps   = c.dependsOn?.length ? ` depends: [${c.dependsOn.join(', ')}]` : '';
                    const snip   = c.snippet ? ` — \`${c.snippet.slice(0, 60)}\`` : '';
                    sys += `- [${c.index + 1}] ${c.type}${c.mode && c.type === 'CODE' ? ' (' + c.mode + ')' : ''}`;
                    if (c.anchor) sys += ` #${c.anchor}`;
                    if (c.description) sys += ` "${c.description}"`;
                    sys += `${deps}${status}${snip}\n`;
                });
            }
        }

        // ── Focused cell ──
        if (cellContext) {
            const lang = LANG[cellContext.mode] || cellContext.mode;
            sys += `\n\n---\n## Focused cell${cellContext.anchor ? ' #' + cellContext.anchor : ''}`;
            if (cellContext.description) sys += ` — "${cellContext.description}"`;
            sys += `\n- Mode: **${lang}**`;
            if (cellContext.dependsOn?.length) sys += `\n- Depends on: ${cellContext.dependsOn.map(d => `\`${d}\``).join(', ')}`;
            if (cellContext.executionCount) sys += `\n- Run count: ${cellContext.executionCount}`;
            if (cellContext.lastExecutedAt) {
                const ago = _timeAgo(cellContext.lastExecutedAt);
                sys += `\n- Last run: ${ago}`;
                if (cellContext.lastExecutionTimeMs) sys += ` (${cellContext.lastExecutionTimeMs}ms)`;
            }
            const fenceLang = ({
                nodejs:'js', typescript:'ts', csharp:'csharp', fsharp:'fsharp', cpp:'cpp', python:'python'
            })[cellContext.mode] || 'java';
            sys += `\n\n**Source:**\n\`\`\`${fenceLang}\n${cellContext.source || '(empty)'}\n\`\`\``;
            if (cellContext.output?.trim())
                sys += `\n\n**Last output:**\n\`\`\`\n${cellContext.output.slice(0, 1200)}\n\`\`\``;
            if (cellContext.error?.trim())
                sys += `\n\n**Last error (IMPORTANT — address this if the user asks why it failed):**\n\`\`\`\n${cellContext.error.slice(0, 1200)}\n\`\`\``;
        }

        sys += `\n\n---
## Responding with code
- Wrap ALL code in fenced code blocks with the correct language tag (\`\`\`java, \`\`\`jshell, \`\`\`csharp, \`\`\`fsharp, \`\`\`js, \`\`\`ts, \`\`\`cpp, \`\`\`python)
- Match the language/mode of the focused cell unless the user explicitly asks to change it
- For an edit request, the FIRST code block is auto-applied to the focused cell (with Undo); make it the complete replacement. Additional blocks show manual **Apply to cell**, **Apply & Run**, and **New cell** buttons.
- Keep each block complete and immediately runnable as a Arima cell`;

        return sys;
    }

    /** Human-readable "X min ago" from ISO timestamp */
    function _timeAgo(iso) {
        try {
            const diff = Math.round((Date.now() - new Date(iso)) / 1000);
            if (diff < 60)   return `${diff}s ago`;
            if (diff < 3600) return `${Math.round(diff/60)}m ago`;
            if (diff < 86400) return `${Math.round(diff/3600)}h ago`;
            return new Date(iso).toLocaleDateString();
        } catch { return iso; }
    }

    // ── Send message ──────────────────────────────────────────────────────
    async function sendMessage() {
        const input   = document.getElementById('ai-input');
        const message = input?.value.trim();
        if (!message) return;
        input.value = '';
        lastUserMessage = message;

        // Consume the pending auto-mode (set by notebook action buttons)
        const autoMode = pendingAutoMode;
        pendingAutoMode = null;

        // Refresh context from notebook at send time
        const freshCtx = NotebookEditor?.getContext?.();
        if (freshCtx?.cell) { cellContext = freshCtx.cell; nbContext = freshCtx; }
        else if (freshCtx)  { nbContext = freshCtx; }
        renderContextBadge();

        appendMessage('user', message);
        chatHistory.push({ role: 'user', content: message });

        const loadingId = 'loading-' + Date.now();
        appendLoadingMessage(loadingId);

        try {
            const response = await Arima.api('POST', '/llm/chat', {
                history:      chatHistory,
                message:      message,
                systemPrompt: buildSystemPrompt()
            });

            removeLoadingMessage(loadingId);
            const text = response.response;
            chatHistory.push({ role: 'assistant', content: text });

            const msgDiv = appendMessage('assistant', text);
            addCodeActions(msgDiv, text, autoMode);
            Arima.setStatus('AI response received');
        } catch (e) {
            removeLoadingMessage(loadingId);
            const errText = e.message || String(e);
            // Provider-aware error hint
            const _ap = Arima.state?.settings?.aiProvider || 'claude_cli';
            const providerHint = _ap === 'copilot_cli'
                ? `AAA is using the **GitHub Copilot SDK**, which drives the local \`copilot\` CLI.\n` +
                  `Make sure the \`copilot\` command (v1.0.55-5+) is on your PATH and authenticated.`
                : _ap === 'gemini_cli'
                ? `AAA is using the **Antigravity CLI** (agy).\n` +
                  `Make sure it is installed and signed in:\n` +
                  `\`\`\`\n# https://antigravity.google/docs/cli-install\nagy\n\`\`\``
                : `AAA uses your local **Claude CLI** (Pro plan). Make sure it is installed and signed in:\n` +
                  `\`\`\`\nclaude auth\n\`\`\``;
            appendMessage('assistant', `**Error:** ${errText}\n\n${providerHint}`);
            Arima.setStatus('AI error');
        }
    }

    // ── Render message ────────────────────────────────────────────────────
    function appendMessage(role, content) {
        const container = document.getElementById('ai-messages');
        if (!container) return null;
        const div = document.createElement('div');
        div.className = `ai-msg ${role}`;

        const avatar = document.createElement('div');
        avatar.className = 'ai-msg-avatar';
        avatar.textContent = role === 'user' ? 'You' : 'AI';

        const body = document.createElement('div');
        body.className = 'ai-msg-body';
        try { body.innerHTML = marked.parse(content); }
        catch { body.textContent = content; }

        div.appendChild(avatar);
        div.appendChild(body);
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
        return div;
    }

    /** Render the "applied" badge with a one-click Undo that restores the prior cell source. */
    function _appliedBadge(preEl, cellId, prevSource, label) {
        const badge = document.createElement('div');
        badge.className = 'ai-code-actions';
        const span = document.createElement('span');
        span.className = 'ai-action-applied-badge';
        span.innerHTML = '✓ <span class="ai-auto-badge">AAA</span> ' + label;
        badge.appendChild(span);
        if (cellId != null && prevSource != null) {
            const undo = document.createElement('button');
            undo.className = 'ai-action-btn ai-action-undo';
            undo.innerHTML = '↶ Undo';
            undo.title = 'Restore the cell to its previous content';
            undo.onclick = () => {
                NotebookEditor?.applyCodeToCell(cellId, prevSource);
                undo.textContent = '✓ Reverted';
                undo.disabled = true;
                Arima.setStatus('Reverted AAA edit');
            };
            badge.appendChild(undo);
        }
        preEl.insertAdjacentElement('afterend', badge);
    }

    /** Add code actions after every code block.
     *  autoMode: 'insert' auto-creates a new cell, 'apply' auto-updates the focused cell.
     *  directEdit: explicit toggle to always auto-apply.
     *  Proactive agentic mode: when the provider is available and the request implies an
     *  edit, AAA auto-applies the first block to the focused cell (with Undo). Otherwise the
     *  manual Apply / Apply & Run / New cell buttons are shown ("simpler UI support"). */
    function addCodeActions(msgDiv, text, autoMode) {
        if (!msgDiv) return;
        const editIntent = EDIT_INTENT.test(lastUserMessage || '');
        const proactiveApply = agenticAvailable && editIntent;
        let firstBlock = true;
        msgDiv.querySelectorAll('pre code').forEach(codeEl => {
            const code = codeEl.textContent.trim();
            const preEl = codeEl.closest('pre');

            // Notebook action button — auto-insert as new cell
            if (autoMode === 'insert' && firstBlock) {
                firstBlock = false;
                NotebookEditor?.insertCodeFromAI(code);
                document.querySelector('[data-tab="notebook"]')?.click();
                _appliedBadge(preEl, null, null, 'Inserted as new cell');
                return;
            }

            // Auto-apply the first block to the focused cell when requested explicitly
            // (button / directEdit toggle) or proactively (agentic + edit intent).
            if (firstBlock && cellContext && (autoMode === 'apply' || directEdit || proactiveApply)) {
                firstBlock = false;
                const prev = NotebookEditor?.applyCodeToCell(cellContext?.id, code);
                document.querySelector('[data-tab="notebook"]')?.click();
                _appliedBadge(preEl, cellContext?.id, prev ?? null, 'Updated cell');
                return;
            }
            firstBlock = false;

            const wrap = document.createElement('div');
            wrap.className = 'ai-code-actions';

            if (cellContext) {
                const applyBtn = document.createElement('button');
                applyBtn.className = 'ai-action-btn ai-action-apply';
                applyBtn.innerHTML = '✏ Apply to cell';
                applyBtn.title = 'Replace the focused cell\'s code with this';
                applyBtn.onclick = () => {
                    NotebookEditor?.applyCodeToCell(cellContext?.id, code);
                    applyBtn.textContent = '✓ Applied';
                    applyBtn.disabled = true;
                    document.querySelector('[data-tab="notebook"]')?.click();
                };
                wrap.appendChild(applyBtn);

                const applyRunBtn = document.createElement('button');
                applyRunBtn.className = 'ai-action-btn ai-action-apply-run';
                applyRunBtn.innerHTML = '▶ Apply & Run';
                applyRunBtn.title = 'Apply this code to the focused cell and execute it immediately';
                applyRunBtn.onclick = async () => {
                    NotebookEditor?.applyCodeToCell(cellContext?.id, code);
                    applyRunBtn.textContent = '⏳ Running…';
                    applyRunBtn.disabled = true;
                    applyBtn.disabled    = true;
                    document.querySelector('[data-tab="notebook"]')?.click();
                    try {
                        await NotebookEditor?.executeCell(cellContext?.id);
                        applyRunBtn.textContent = '✓ Done';
                    } catch {
                        applyRunBtn.textContent = '✗ Error';
                    }
                };
                wrap.appendChild(applyRunBtn);
            }

            const insertBtn = document.createElement('button');
            insertBtn.className = 'ai-action-btn';
            insertBtn.innerHTML = '＋ New cell';
            insertBtn.title = 'Add this as a new cell in the notebook';
            insertBtn.onclick = () => {
                NotebookEditor?.insertCodeFromAI(code);
                insertBtn.textContent = '✓ Inserted';
                insertBtn.disabled = true;
                document.querySelector('[data-tab="notebook"]')?.click();
            };
            wrap.appendChild(insertBtn);

            codeEl.closest('pre').insertAdjacentElement('afterend', wrap);
        });
    }

    function appendLoadingMessage(id) {
        const container = document.getElementById('ai-messages');
        if (!container) return;
        const div = document.createElement('div');
        div.id = id; div.className = 'ai-msg assistant';
        div.innerHTML = `<div class="ai-msg-avatar">AI</div>
            <div class="ai-msg-body"><span class="thinking-dots"><span>.</span><span>.</span><span>.</span></span></div>`;
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
    }

    function removeLoadingMessage(id) { document.getElementById(id)?.remove(); }

    function clearChat() {
        chatHistory = [];
        const c = document.getElementById('ai-messages');
        if (c) c.innerHTML = `<div class="ai-msg assistant">
            <div class="ai-msg-avatar">AI</div>
            <div class="ai-msg-body"><p>Chat cleared. Click a cell or use a quick action to start.</p></div>
        </div>`;
    }

    // ── Generate notebook ─────────────────────────────────────────────────
    async function generateNotebook() {
        const prompt = document.getElementById('gen-prompt')?.value.trim();
        if (!prompt) { alert('Describe the notebook you want to generate.'); return; }
        const btn = document.getElementById('btn-gen-confirm');
        if (btn) { btn.disabled = true; btn.textContent = 'Generating…'; }
        Arima.setStatus('Generating notebook with AI…');
        try {
            const response = await fetch('/api/llm/generate', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ prompt })
            });
            if (!response.ok) {
                const err = await response.json().catch(() => ({}));
                throw new Error(err.error || `HTTP ${response.status}`);
            }
            const raw = await response.text();
            let nb;
            try { nb = JSON.parse(raw.replace(/^```json\s*/m,'').replace(/\s*```\s*$/m,'').trim()); }
            catch { throw new Error('AI returned invalid JSON. Try a clearer description.'); }
            const saved = await Arima.api('POST', '/notebooks', { name: nb.name || 'AI Generated Notebook' });
            nb.id = saved.id;
            await Arima.api('PUT', `/notebooks/${saved.id}`, nb);
            document.getElementById('gen-modal')?.classList.add('hidden');
            document.querySelector('[data-tab="notebook"]')?.click();
            await NotebookEditor.loadNotebook(saved.id);
            Arima.setStatus('Notebook generated: ' + (nb.name || 'AI Notebook'));
            if (document.getElementById('gen-prompt')) document.getElementById('gen-prompt').value = '';
        } catch (e) {
            alert('Failed to generate notebook: ' + e.message);
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = 'Generate'; }
        }
    }

    return { init, sendWithContext, updateCellContext, _updateIntroProvider };
})();

document.addEventListener('DOMContentLoaded', () => AIAssistant.init());
