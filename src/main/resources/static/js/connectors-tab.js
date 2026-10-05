/**
 * Connectors section of the Agent Factory.
 *
 * A connector is a notebook with metadata.kind = "connector" naming an external MCP server. This
 * module lists them (/api/connectors/list), creates new ones (/api/connectors/create), probes one
 * to see what tools it offers (/api/connectors/probe), and deploys it into a target's .mcp.json
 * (/api/connectors/deploy) so the agentic CLI there reaches the same server Arima does.
 *
 * Reuses the .agent-card classes so connectors sit in the same grid as agents, tools and plugins.
 */
const ConnectorsTab = (function () {
  let targets = [];

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
  }

  async function refresh() {
    const grid = document.getElementById('connectors-grid');
    if (!grid) return;
    grid.innerHTML = '<div class="agents-empty">Loading…</div>';
    if (window.PluginsTab) targets = (await PluginsTab.loadTargets()) || targets;
    try {
      const list = await Arima.api('GET', '/connectors/list') || [];
      if (!list.length) {
        grid.innerHTML = '<div class="agents-empty">No connectors yet. <b>+ New Connector</b> points '
          + 'Arima at an external MCP server — a local <code>stdio</code> subprocess, or an '
          + '<code>sse</code> endpoint.</div>';
        return;
      }
      grid.innerHTML = list.map(card).join('');
      list.forEach(c => wireCard(grid, c));
    } catch (e) {
      grid.innerHTML = `<div class="agents-empty">Failed to load connectors: ${esc(e.message || e)}</div>`;
    }
  }

  function card(c) {
    const deployed = Array.isArray(c.deployedTo) ? c.deployedTo : [];
    return `
      <div class="agent-card connector-card" data-connector-id="${esc(c.id)}">
        <div class="agent-card-top">
          <span class="agent-kind connector">CONNECTOR</span>
          <span class="agent-card-name" title="${esc(c.name)}">${esc(c.name)}</span>
        </div>
        <div class="agent-card-desc">${esc(c.description) || '<span class="muted">No description</span>'}</div>
        <div class="tool-card-sig"><code>${esc(c.endpoint) || '(not configured)'}</code></div>
        <div class="agent-card-meta">
          <span class="agent-card-prov">${esc(c.transport)}</span>
          ${c.remote ? '<span class="connector-remote" title="Reaching this host asks for your approval first">remote</span>'
                     : '<span class="connector-local">local</span>'}
          ${c.enabled ? '' : '<span class="tool-card-warn">disabled</span>'}
          ${deployed.length
            ? `<span class="plugin-card-deployed">deployed: ${deployed.map(esc).join(', ')}</span>`
            : ''}
        </div>
        <div class="agent-card-actions">
          <button class="btn-secondary connector-card-open">Open</button>
          <button class="btn-secondary connector-card-deploy" title="Add to a target's .mcp.json">⤓</button>
          <button class="btn-primary connector-card-probe">⇄ Probe</button>
        </div>
        <div class="agent-card-run-box connector-card-probe-box" hidden>
          <div class="agent-card-run-actions">
            <button class="btn-primary connector-card-go">Probe</button>
            <button class="btn-secondary connector-card-cancel">Close</button>
            <span class="agent-card-status connector-card-status"></span>
          </div>
          <pre class="agent-card-output connector-card-output" hidden></pre>
        </div>
        <div class="agent-card-run-box connector-card-deploy-box" hidden>
          <label class="tool-arg-row">
            <span class="tool-arg-name">target</span>
            <select class="connector-card-target">
              ${targets.map(t => `<option value="${esc(t.key)}">${esc(t.label)}</option>`).join('')}
            </select>
          </label>
          <div class="agent-card-run-actions">
            <button class="btn-primary connector-card-deploy-go">Deploy</button>
            <button class="btn-secondary connector-card-deploy-cancel">Close</button>
            <span class="agent-card-status connector-card-deploy-status"></span>
          </div>
          <pre class="agent-card-output connector-card-deploy-output" hidden></pre>
        </div>
      </div>`;
  }

  function wireCard(container, c) {
    const el = container.querySelector(`.connector-card[data-connector-id="${cssEscape(c.id)}"]`);
    if (!el) return;
    el.querySelector('.connector-card-open')?.addEventListener('click', () => {
      NotebookEditor.loadNotebook(c.id, c.source !== 'mine');
      document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    });

    const probeBox = el.querySelector('.connector-card-probe-box');
    el.querySelector('.connector-card-probe')?.addEventListener('click', () => {
      probeBox.hidden = !probeBox.hidden;
    });
    el.querySelector('.connector-card-cancel')?.addEventListener('click', () => { probeBox.hidden = true; });
    el.querySelector('.connector-card-go')?.addEventListener('click', () => probe(el, c));

    const deployBox = el.querySelector('.connector-card-deploy-box');
    el.querySelector('.connector-card-deploy')?.addEventListener('click', () => {
      deployBox.hidden = !deployBox.hidden;
    });
    el.querySelector('.connector-card-deploy-cancel')?.addEventListener('click', () => {
      deployBox.hidden = true;
    });
    el.querySelector('.connector-card-deploy-go')?.addEventListener('click', () => deploy(el, c));
  }

  async function probe(el, c) {
    const outEl = el.querySelector('.connector-card-output');
    const statusEl = el.querySelector('.connector-card-status');
    const goBtn = el.querySelector('.connector-card-go');

    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Connecting…';
    statusEl.textContent = c.remote ? 'remote — waiting for your approval' : 'connecting';

    try {
      const r = await Arima.api('POST', '/connectors/probe', { connectorId: c.id });
      outEl.textContent = renderProbe(r);
      statusEl.textContent = r && r.success
        ? `${(r.tools || []).length} tool(s)` : 'failed';
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      goBtn.disabled = false; goBtn.textContent = 'Probe';
    }
  }

  /** Format a probe result as the tool catalog the server reported. */
  function renderProbe(r) {
    if (!r || r.success === false) return '⚠ ' + ((r && r.error) || 'probe failed');
    const lines = [`${r.serverName || '(unnamed)'} ${r.serverVersion || ''}`.trim(),
                   `${r.transport} · ${r.endpoint}`, ''];
    const tools = r.tools || [];
    if (!tools.length) {
      lines.push('The server reported no tools.');
    } else {
      tools.forEach(t => {
        const props = (t.inputSchema && t.inputSchema.properties) || {};
        const sig = Object.keys(props).join(', ');
        lines.push(`${t.name}(${sig})`);
        if (t.description) lines.push('  ' + t.description);
      });
    }
    return lines.join('\n');
  }

  async function deploy(el, c) {
    const target = el.querySelector('.connector-card-target')?.value || 'project';
    const outEl = el.querySelector('.connector-card-deploy-output');
    const statusEl = el.querySelector('.connector-card-deploy-status');
    const goBtn = el.querySelector('.connector-card-deploy-go');

    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Deploying…';
    statusEl.textContent = 'writing';

    try {
      const r = await Arima.api('POST', '/connectors/deploy', { connectorId: c.id, target });
      if (!r || r.success === false) {
        outEl.textContent = '⚠ ' + ((r && r.error) || 'deploy failed');
        statusEl.textContent = 'failed';
      } else {
        outEl.textContent = `Added "${r.slug}" to\n${r.path}`;
        statusEl.textContent = 'deployed';
        Arima.setStatus(`Connector ${r.slug} → ${r.targetLabel}`);
        refresh();
        if (window.PluginsTab) PluginsTab.refreshDeployments();
      }
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      goBtn.disabled = false; goBtn.textContent = 'Deploy';
    }
  }

  async function create() {
    const name = prompt('Name your connector:', 'my-connector');
    if (name === null) return;
    const transport = prompt(
      'Transport?\n\nstdio — Arima launches the server as a local subprocess (no network)\n'
      + 'sse   — Arima opens an HTTP endpoint', 'stdio');
    if (transport === null) return;
    try {
      const nb = await Arima.api('POST', '/connectors/create',
        { name: name.trim(), transport: (transport || 'stdio').trim().toLowerCase() });
      if (nb && nb.id) {
        NotebookEditor.loadNotebook(nb.id);
        document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
      }
    } catch (e) {
      Arima.setStatus('Create failed: ' + (e.message || e));
    }
  }

  function init() {
    document.getElementById('agents-new-connector')?.addEventListener('click', create);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { refresh, create, renderProbe };
})();
window.ConnectorsTab = ConnectorsTab;
