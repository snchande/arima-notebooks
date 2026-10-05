/**
 * Agents section of the Agent Factory.
 *
 * A browse/author/run/deploy arena over agent & skill notebooks. The source of truth stays the
 * notebooks themselves: this tab lists them (user notebooks + built-in samples) via
 * /api/agents/list, opens one for editing (hands off to NotebookEditor + agent.js), creates new
 * ones (/api/agents/create), runs one inline — streaming the provider CLI over the same STOMP
 * channel agent.js uses (partial_output, cellId "__agent_run__") — and deploys one into a chosen
 * target (/api/agents/deploy). Active runs surface in a "running now" strip.
 *
 * Tools, connectors and plugins live in sibling modules (tools-tab.js, connectors-tab.js,
 * plugins-tab.js); refresh() drives all of them so one ↻ reloads the whole factory.
 */
const AgentsTab = (function () {
  const RUN_CELL_ID = '__agent_run__';
  const PROVIDER_LABELS = { claude: 'Claude', copilot: 'Copilot', gemini: 'Antigravity' };

  let avail = {};                 // provider -> available
  let targets = [];               // deploy targets: [{key, label, root}]
  let loaded = false;             // first-open lazy load guard
  const running = new Map();      // agentId -> { name, unsub, startedAt }

  function init() {
    document.getElementById('agents-new-agent')?.addEventListener('click', () => create('agent'));
    document.getElementById('agents-new-skill')?.addEventListener('click', () => create('skill'));
    document.getElementById('agents-refresh')?.addEventListener('click', () => refresh());
    // Lazy-load the list the first time the Agents tab is opened.
    document.querySelector('.tab-btn[data-tab="agents"]')?.addEventListener('click', () => {
      if (!loaded) refresh();
    });
    Arima.api('GET', '/agents/providers').then(a => { avail = a || {}; }).catch(() => {});
  }

  /** Deploy targets, shared with the plugin cards so both offer the same choices. */
  async function deployTargets() {
    if (window.PluginsTab) return (await PluginsTab.loadTargets()) || [];
    try { return await Arima.api('GET', '/plugins/targets') || []; } catch { return []; }
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  async function refresh() {
    loaded = true;
    const mine = document.getElementById('agents-grid-mine');
    const samples = document.getElementById('agents-grid-samples');
    if (!mine || !samples) return;
    mine.innerHTML = '<div class="agents-empty">Loading…</div>';
    samples.innerHTML = '';
    try {
      avail = await Arima.api('GET', '/agents/providers').catch(() => avail) || avail;
      targets = await deployTargets();
      const list = await Arima.api('GET', '/agents/list') || [];
      const mineList = list.filter(a => a.source === 'mine');
      const sampleList = list.filter(a => a.source !== 'mine');
      render(mine, mineList, 'You have no agents or skills yet. Create one with <b>+ New Agent</b> above.');
      render(samples, sampleList, 'No built-in samples found.');
    } catch (e) {
      mine.innerHTML = `<div class="agents-empty">Failed to load: ${esc(e.message || e)}</div>`;
    }
    // One ↻ reloads the whole factory.
    if (window.ToolsTab) ToolsTab.refresh();
    if (window.ConnectorsTab) ConnectorsTab.refresh();
    if (window.PluginsTab) PluginsTab.refresh();
  }

  function render(container, agents, emptyMsg) {
    if (!agents.length) { container.innerHTML = `<div class="agents-empty">${emptyMsg}</div>`; return; }
    container.innerHTML = agents.map(card).join('');
    agents.forEach(a => wireCard(container, a));
  }

  function card(a) {
    const kind = a.kind === 'skill' ? 'SKILL' : 'AGENT';
    const provLabel = PROVIDER_LABELS[a.provider] || a.provider || 'claude';
    const provOk = avail[a.provider] !== false;
    const tools = Array.isArray(a.tools) ? a.tools : [];
    const toolChips = kind === 'AGENT' && tools.length
      ? `<div class="agent-card-tools">${tools.map(t => `<span class="agent-card-tool">${esc(t)}</span>`).join('')}</div>`
      : '';
    const deployed = Array.isArray(a.deployedTo) ? a.deployedTo : [];
    return `
      <div class="agent-card" data-agent-id="${esc(a.id)}">
        <div class="agent-card-top">
          <span class="agent-kind ${a.kind === 'skill' ? 'skill' : 'agent'}">${kind}</span>
          <span class="agent-card-name" title="${esc(a.name)}">${esc(a.name)}</span>
        </div>
        <div class="agent-card-desc">${esc(a.description) || '<span class="muted">No description</span>'}</div>
        ${toolChips}
        <div class="agent-card-meta">
          <span class="agent-card-prov ${provOk ? '' : 'off'}" title="${provOk ? '' : 'CLI not installed'}">${esc(provLabel)}</span>
          <span class="agent-card-count">${a.cellCount || 0} cell${a.cellCount === 1 ? '' : 's'}</span>
          ${deployed.length
            ? `<span class="plugin-card-deployed">deployed: ${deployed.map(esc).join(', ')}</span>`
            : ''}
        </div>
        <div class="agent-card-actions">
          <button class="btn-secondary agent-card-open">Open</button>
          <button class="btn-secondary agent-card-deploy" title="Write this ${a.kind} to the files an agentic CLI reads">⤓</button>
          <button class="btn-primary agent-card-run">▶ Run</button>
        </div>
        <div class="agent-card-run-box" hidden>
          <textarea class="agent-card-task" rows="2" placeholder="Give this ${a.kind} a task…"></textarea>
          <div class="agent-card-run-actions">
            <button class="btn-primary agent-card-go">Run</button>
            <button class="btn-secondary agent-card-cancel">Close</button>
            <span class="agent-card-status"></span>
          </div>
          <pre class="agent-card-output" hidden></pre>
        </div>
        <div class="agent-card-run-box agent-card-deploy-box" hidden>
          <label class="tool-arg-row">
            <span class="tool-arg-name">target</span>
            <select class="agent-card-target">
              ${targets.map(t => `<option value="${esc(t.key)}">${esc(t.label)}</option>`).join('')}
            </select>
          </label>
          <div class="agent-card-run-actions">
            <button class="btn-primary agent-card-deploy-go">Deploy</button>
            <button class="btn-secondary agent-card-deploy-cancel">Close</button>
            <span class="agent-card-status agent-card-deploy-status"></span>
          </div>
          <pre class="agent-card-output agent-card-deploy-output" hidden></pre>
        </div>
      </div>`;
  }

  function wireCard(container, a) {
    const el = container.querySelector(`.agent-card[data-agent-id="${cssEscape(a.id)}"]`);
    if (!el) return;
    el.querySelector('.agent-card-open')?.addEventListener('click', () => open(a));
    const box = el.querySelector('.agent-card-run-box');
    el.querySelector('.agent-card-run')?.addEventListener('click', () => {
      box.hidden = !box.hidden;
      if (!box.hidden) el.querySelector('.agent-card-task')?.focus();
    });
    el.querySelector('.agent-card-cancel')?.addEventListener('click', () => { box.hidden = true; });
    el.querySelector('.agent-card-go')?.addEventListener('click', () => runCard(el, a));
    el.querySelector('.agent-card-task')?.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); runCard(el, a); }
    });

    const deployBox = el.querySelector('.agent-card-deploy-box');
    el.querySelector('.agent-card-deploy')?.addEventListener('click', () => {
      deployBox.hidden = !deployBox.hidden;
    });
    el.querySelector('.agent-card-deploy-cancel')?.addEventListener('click', () => {
      deployBox.hidden = true;
    });
    el.querySelector('.agent-card-deploy-go')?.addEventListener('click', () => deployCard(el, a));
  }

  /** Write this agent/skill into the chosen target via its provider's native layout. */
  async function deployCard(el, a) {
    const target = el.querySelector('.agent-card-target')?.value || 'project';
    const outEl = el.querySelector('.agent-card-deploy-output');
    const statusEl = el.querySelector('.agent-card-deploy-status');
    const goBtn = el.querySelector('.agent-card-deploy-go');
    const provider = a.provider || 'claude';

    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Deploying…';
    statusEl.textContent = 'writing';

    try {
      const r = await Arima.api('POST', '/agents/deploy',
        { notebookId: a.id, provider, target });
      if (!r || r.success === false) {
        outEl.textContent = '⚠ ' + ((r && r.error) || 'deploy failed');
        statusEl.textContent = 'failed';
      } else {
        outEl.textContent = (r.paths || [r.path]).join('\n');
        statusEl.textContent = 'deployed';
        Arima.setStatus(`Deployed ${r.kind} ${r.slug} → ${r.targetLabel}`);
        refresh();
      }
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      goBtn.disabled = false; goBtn.textContent = 'Deploy';
    }
  }

  // Best-effort CSS.escape shim (agent ids are UUIDs/slugs, so this is usually a no-op).
  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
  }

  function open(a) {
    NotebookEditor.loadNotebook(a.id, a.source !== 'mine');
    document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
  }

  async function create(kind) {
    const name = prompt(`Name your ${kind}:`, kind === 'skill' ? 'my-skill' : 'my-agent');
    if (name === null) return;
    try {
      const nb = await Arima.api('POST', '/agents/create', { name: name.trim(), kind });
      if (nb && nb.id) {
        NotebookEditor.loadNotebook(nb.id);
        document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
      }
    } catch (e) { Arima.setStatus('Create failed: ' + (e.message || e)); }
  }

  async function runCard(el, a) {
    const taskEl = el.querySelector('.agent-card-task');
    const outEl = el.querySelector('.agent-card-output');
    const statusEl = el.querySelector('.agent-card-status');
    const goBtn = el.querySelector('.agent-card-go');
    const task = (taskEl.value || '').trim();
    if (!task) { taskEl.focus(); return; }

    const provider = a.provider || 'claude';
    if (avail[provider] === false) {
      statusEl.textContent = `${PROVIDER_LABELS[provider] || provider} CLI not installed`;
      return;
    }

    const sessionId = 'agents-tab-' + a.id;
    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Running…';
    statusEl.textContent = `${PROVIDER_LABELS[provider] || provider} · streaming`;
    el.classList.add('running');

    const unsub = Arima.subscribeToSession(sessionId, (msg) => {
      if (msg.cellId === RUN_CELL_ID && msg.type === 'partial_output' && msg.text) {
        outEl.textContent += msg.text;
        outEl.scrollTop = outEl.scrollHeight;
      }
    });
    markRunning(a, unsub);

    try {
      const r = await Arima.api('POST', '/agents/run',
        { notebookId: a.id, task, provider, sessionId });
      if (r && r.success === false) {
        outEl.textContent = '⚠ ' + (r.error || 'run failed');
        statusEl.textContent = 'failed';
      } else {
        outEl.textContent = (r && r.output) || outEl.textContent;
        statusEl.textContent = 'done';
      }
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      goBtn.disabled = false; goBtn.textContent = 'Run';
      el.classList.remove('running');
      clearRunning(a);
    }
  }

  // ── "Running now" strip ──────────────────────────────────────────────
  function markRunning(a, unsub) {
    const prev = running.get(a.id);
    if (prev && prev.unsub) { try { prev.unsub(); } catch {} }
    running.set(a.id, { name: a.name, unsub, startedAt: Date.now() });
    renderRunning();
  }

  function clearRunning(a) {
    const entry = running.get(a.id);
    if (entry && entry.unsub) { try { entry.unsub(); } catch {} }
    running.delete(a.id);
    renderRunning();
  }

  function renderRunning() {
    const wrap = document.getElementById('agents-running');
    const list = document.getElementById('agents-running-list');
    if (!wrap || !list) return;
    if (!running.size) { wrap.hidden = true; list.innerHTML = ''; return; }
    wrap.hidden = false;
    list.innerHTML = Array.from(running.values())
      .map(r => `<span class="agents-running-chip"><span class="agents-run-dot"></span>${esc(r.name)}</span>`)
      .join('');
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { refresh };
})();
window.AgentsTab = AgentsTab;
