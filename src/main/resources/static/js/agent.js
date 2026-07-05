/**
 * Agent & Skill notebooks (v0).
 *
 * An "agent notebook" is a normal notebook with metadata.kind = "agent" | "skill".
 * When one is open, this module renders an authoring banner (name · description · provider ·
 * tools · Export) above the cells and a Run dock below them. Running streams the provider CLI's
 * output over the existing STOMP channel (partial_output, cellId "__agent_run__").
 *
 * Backend: /api/agents/{create,run,export,providers}.
 */
const Agent = (function () {
  const RUN_CELL_ID = '__agent_run__';
  const PROVIDERS = [['claude', 'Claude'], ['copilot', 'Copilot'], ['gemini', 'Antigravity']];

  let current = null;      // the open agent/skill notebook (same ref as NotebookEditor's notebook)
  let avail = {};          // provider -> available
  let unsub = null;        // STOMP unsubscribe for the active run

  function init() {
    document.getElementById('btn-add-agent')?.addEventListener('click', () => create('agent'));
    document.getElementById('btn-add-skill')?.addEventListener('click', () => create('skill'));
    Arima.api('GET', '/agents/providers').then(a => { avail = a || {}; }).catch(() => {});
  }

  async function create(kind) {
    const name = prompt(`Name your ${kind}:`, kind === 'skill' ? 'my-skill' : 'my-agent');
    if (name === null) return;
    try {
      const nb = await Arima.api('POST', '/agents/create', { name: name.trim(), kind });
      if (nb && nb.id) NotebookEditor.loadNotebook(nb.id);
    } catch (e) { Arima.setStatus('Create failed: ' + (e.message || e)); }
  }

  // Called by notebook.js on every render.
  function onNotebookLoaded(nb) {
    current = nb;
    removeUI();
    const kind = nb && nb.metadata && nb.metadata.kind;
    if (kind === 'agent' || kind === 'skill') renderUI(nb, kind);
  }

  function removeUI() {
    if (unsub) { try { unsub(); } catch {} unsub = null; }
    document.getElementById('agent-banner')?.remove();
    document.getElementById('agent-dock')?.remove();
  }

  function esc(s) { return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

  function providerOptions(sel) {
    return PROVIDERS.map(([k, label]) => {
      const ok = avail[k];
      const dis = ok ? '' : ' disabled';
      const s = (k === sel) ? ' selected' : '';
      return `<option value="${k}"${s}${dis}>${label}${ok ? '' : ' (not installed)'}</option>`;
    }).join('');
  }

  function defaultProvider() {
    const meta = current?.metadata?.agent || {};
    if (meta.provider && avail[meta.provider]) return meta.provider;
    const firstOk = PROVIDERS.find(([k]) => avail[k]);
    return firstOk ? firstOk[0] : 'claude';
  }

  function tools() {
    const t = current?.metadata?.agent?.tools;
    return Array.isArray(t) ? t : [];
  }

  function renderUI(nb, kind) {
    const scroll = document.getElementById('cells-scroll');
    const cells = document.getElementById('cells-container');
    if (!scroll || !cells) return;

    const banner = document.createElement('div');
    banner.id = 'agent-banner';
    banner.className = 'agent-banner';
    const toolChips = kind === 'agent'
      ? `<span class="agent-tools">${tools().map(t => `<span class="agent-chip">${esc(t)}<button class="chip-x" data-tool="${esc(t)}" title="Remove">×</button></span>`).join('')}
           <button class="agent-chip add" id="agent-add-tool">+ tool</button></span>`
      : '';
    banner.innerHTML = `
      <div class="agent-banner-row">
        <span class="agent-kind ${kind}">${kind === 'skill' ? 'SKILL' : 'AGENT'}</span>
        <input id="agent-name" class="agent-name" value="${esc(nb.name)}" spellcheck="false" />
        <label class="agent-prov">provider
          <select id="agent-provider">${providerOptions(defaultProvider())}</select>
        </label>
        <button id="agent-export" class="agent-export">⤓ Export</button>
      </div>
      <input id="agent-desc" class="agent-desc" value="${esc(nb.description)}" placeholder="One-line description (frontmatter)" />
      ${toolChips}
      <div class="agent-hint">Write this ${kind}'s instructions in the markdown cells below — they become the system prompt.</div>`;
    scroll.insertBefore(banner, cells);

    const dock = document.createElement('div');
    dock.id = 'agent-dock';
    dock.className = 'agent-dock';
    dock.innerHTML = `
      <div class="agent-dock-head">▶ Run ${kind}</div>
      <textarea id="agent-task" class="agent-task" rows="2" placeholder="Give the ${kind} a task…"></textarea>
      <div class="agent-dock-actions">
        <button id="agent-run" class="agent-run">▶ Run</button>
        <span id="agent-run-status" class="agent-run-status"></span>
      </div>
      <pre id="agent-output" class="agent-output" hidden></pre>`;
    scroll.appendChild(dock);

    wire(nb, kind);
  }

  function wire(nb, kind) {
    const nameEl = document.getElementById('agent-name');
    const descEl = document.getElementById('agent-desc');
    const provEl = document.getElementById('agent-provider');
    const persist = () => {
      nb.name = nameEl.value.trim() || nb.name;
      nb.description = descEl.value.trim();
      nb.metadata = nb.metadata || {};
      nb.metadata.agent = nb.metadata.agent || {};
      nb.metadata.agent.provider = provEl.value;
      NotebookEditor.save();
    };
    nameEl?.addEventListener('change', persist);
    descEl?.addEventListener('change', persist);
    provEl?.addEventListener('change', persist);

    document.getElementById('agent-export')?.addEventListener('click', () => exportSpec(provEl.value));
    document.getElementById('agent-run')?.addEventListener('click', () => run(provEl.value));
    document.getElementById('agent-task')?.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(provEl.value); }
    });

    // Tools (agents only)
    document.getElementById('agent-add-tool')?.addEventListener('click', () => {
      const t = prompt('Tool name (e.g. Read, Grep, Bash):');
      if (!t) return;
      nb.metadata = nb.metadata || {}; nb.metadata.agent = nb.metadata.agent || {};
      nb.metadata.agent.tools = tools().concat(t.trim());
      NotebookEditor.save();
      renderUI(nb, kind); // re-render chips
    });
    document.querySelectorAll('#agent-banner .chip-x').forEach(b =>
      b.addEventListener('click', () => {
        nb.metadata.agent.tools = tools().filter(x => x !== b.dataset.tool);
        NotebookEditor.save();
        renderUI(nb, kind);
      }));
  }

  async function exportSpec(provider) {
    try {
      const r = await Arima.api('POST', '/agents/export', { notebookId: current.id, provider });
      if (r && r.success) Arima.setStatus('Exported → ' + r.path);
      else Arima.setStatus('Export failed: ' + (r && r.error || 'unknown'));
    } catch (e) { Arima.setStatus('Export failed: ' + (e.message || e)); }
  }

  async function run(provider) {
    const taskEl = document.getElementById('agent-task');
    const outEl = document.getElementById('agent-output');
    const statusEl = document.getElementById('agent-run-status');
    const runBtn = document.getElementById('agent-run');
    const task = (taskEl.value || '').trim();
    if (!task) { taskEl.focus(); return; }

    const sessionId = Arima.state.currentSessionId || ('nb-' + current.id);
    outEl.hidden = false; outEl.textContent = '';
    runBtn.disabled = true; runBtn.textContent = '● Running…';
    statusEl.textContent = `${provider} · streaming`;

    if (unsub) { try { unsub(); } catch {} }
    unsub = Arima.subscribeToSession(sessionId, (msg) => {
      if (msg.cellId === RUN_CELL_ID && msg.type === 'partial_output' && msg.text) {
        outEl.textContent += msg.text;
        outEl.scrollTop = outEl.scrollHeight;
      }
    });

    try {
      const r = await Arima.api('POST', '/agents/run', { notebookId: current.id, task, provider, sessionId });
      if (unsub) { try { unsub(); } catch {} unsub = null; }
      if (r && r.success === false) {
        outEl.textContent = '⚠ ' + (r.error || 'run failed');
        statusEl.textContent = 'failed';
      } else {
        outEl.textContent = (r && r.output) || outEl.textContent; // final replaces streamed preview
        statusEl.textContent = 'done';
      }
    } catch (e) {
      if (unsub) { try { unsub(); } catch {} unsub = null; }
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      runBtn.disabled = false; runBtn.textContent = '▶ Run';
    }
  }

  // Run init now if the DOM is already parsed (this script loads after the toolbar markup),
  // otherwise wait for DOMContentLoaded.
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { onNotebookLoaded };
})();
window.Agent = Agent;
