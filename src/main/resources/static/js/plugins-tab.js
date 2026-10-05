/**
 * Plugins + Deployments sections of the Agent Factory.
 *
 * A plugin is a notebook with metadata.kind = "plugin" whose metadata.plugin.members name the
 * agents, skills and tools it ships. Deploying one (/api/plugins/deploy) builds a Claude Code
 * plugin directory into the chosen target; the Deployed section lists every recorded deploy
 * (/api/agents/deployments) and can take any of them back (/api/agents/undeploy).
 *
 * Reuses the .agent-card classes so plugins sit in the same grid as agents and tools.
 */
const PluginsTab = (function () {
  const KIND_ICONS = { agent: '🤖', skill: '⭐', tool: '🔧', plugin: '📦', missing: '⚠' };

  let targets = [];   // [{key, label, root}]

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
  }

  async function loadTargets() {
    if (targets.length) return targets;
    try { targets = await Arima.api('GET', '/plugins/targets') || []; } catch { targets = []; }
    return targets;
  }

  // -- Plugins ---------------------------------------------------------------

  async function refresh() {
    const grid = document.getElementById('plugins-grid');
    if (!grid) return;
    grid.innerHTML = '<div class="agents-empty">Loading…</div>';
    await loadTargets();
    try {
      const plugins = await Arima.api('GET', '/plugins/list') || [];
      if (!plugins.length) {
        grid.innerHTML = '<div class="agents-empty">No plugins yet. <b>+ New Plugin</b> creates one — '
          + 'add agents, skills and tools as members, then deploy the bundle.</div>';
      } else {
        grid.innerHTML = plugins.map(card).join('');
        plugins.forEach(p => wireCard(grid, p));
      }
    } catch (e) {
      grid.innerHTML = `<div class="agents-empty">Failed to load plugins: ${esc(e.message || e)}</div>`;
    }
    refreshDeployments();
  }

  function card(p) {
    const members = Array.isArray(p.members) ? p.members : [];
    const chips = members.length
      ? members.map(m => `<span class="agent-card-tool${m.missing ? ' missing' : ''}"
            title="${m.missing ? 'This member no longer exists' : esc(m.kind)}"
          >${KIND_ICONS[m.kind] || '•'} ${esc(m.name)}</span>`).join('')
      : '<span class="agent-card-tool muted">no members yet</span>';
    const deployed = Array.isArray(p.deployedTo) ? p.deployedTo : [];

    return `
      <div class="agent-card plugin-card" data-plugin-id="${esc(p.id)}">
        <div class="agent-card-top">
          <span class="agent-kind plugin">PLUGIN</span>
          <span class="agent-card-name" title="${esc(p.name)}">${esc(p.name)}</span>
          <span class="plugin-card-ver">v${esc(p.version)}</span>
        </div>
        <div class="agent-card-desc">${esc(p.description) || '<span class="muted">No description</span>'}</div>
        <div class="agent-card-tools">${chips}</div>
        <div class="agent-card-meta">
          <span class="agent-card-count">${members.length} member${members.length === 1 ? '' : 's'}</span>
          ${deployed.length
            ? `<span class="plugin-card-deployed">deployed: ${deployed.map(esc).join(', ')}</span>`
            : ''}
        </div>
        <div class="agent-card-actions">
          <button class="btn-secondary plugin-card-open">Open</button>
          <button class="btn-primary plugin-card-deploy">⤓ Deploy</button>
        </div>
        <div class="agent-card-run-box plugin-card-deploy-box" hidden>
          <label class="tool-arg-row">
            <span class="tool-arg-name">target</span>
            <select class="plugin-card-target">
              ${targets.map(t => `<option value="${esc(t.key)}">${esc(t.label)}</option>`).join('')}
            </select>
          </label>
          <div class="agent-card-run-actions">
            <button class="btn-primary plugin-card-go">Deploy</button>
            <button class="btn-secondary plugin-card-cancel">Close</button>
            <span class="agent-card-status plugin-card-status"></span>
          </div>
          <pre class="agent-card-output plugin-card-output" hidden></pre>
        </div>
      </div>`;
  }

  function wireCard(container, p) {
    const el = container.querySelector(`.plugin-card[data-plugin-id="${cssEscape(p.id)}"]`);
    if (!el) return;
    el.querySelector('.plugin-card-open')?.addEventListener('click', () => {
      NotebookEditor.loadNotebook(p.id, p.source !== 'mine');
      document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    });
    const box = el.querySelector('.plugin-card-deploy-box');
    el.querySelector('.plugin-card-deploy')?.addEventListener('click', () => { box.hidden = !box.hidden; });
    el.querySelector('.plugin-card-cancel')?.addEventListener('click', () => { box.hidden = true; });
    el.querySelector('.plugin-card-go')?.addEventListener('click', () => deploy(el, p));
  }

  async function deploy(el, p) {
    const target = el.querySelector('.plugin-card-target')?.value || 'project';
    const outEl = el.querySelector('.plugin-card-output');
    const statusEl = el.querySelector('.plugin-card-status');
    const goBtn = el.querySelector('.plugin-card-go');

    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Deploying…';
    statusEl.textContent = 'building';

    try {
      const r = await Arima.api('POST', '/plugins/deploy', { pluginId: p.id, target });
      if (!r || r.success === false) {
        outEl.textContent = '⚠ ' + ((r && r.error) || 'deploy failed');
        statusEl.textContent = 'failed';
      } else {
        outEl.textContent = `Wrote ${r.fileCount} file(s) to\n${r.root}`;
        statusEl.textContent = 'deployed';
        Arima.setStatus(`Deployed plugin ${r.slug} → ${r.targetLabel}`);
        refresh();
      }
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      goBtn.disabled = false; goBtn.textContent = 'Deploy';
    }
  }

  async function create() {
    const name = prompt('Name your plugin:', 'my-plugin');
    if (name === null) return;
    try {
      const nb = await Arima.api('POST', '/plugins/create', { name: name.trim() });
      if (nb && nb.id) {
        NotebookEditor.loadNotebook(nb.id);
        document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
      }
    } catch (e) {
      Arima.setStatus('Create failed: ' + (e.message || e));
    }
  }

  // -- Deployments -----------------------------------------------------------

  async function refreshDeployments() {
    const list = document.getElementById('deployments-list');
    if (!list) return;
    try {
      const rows = await Arima.api('GET', '/agents/deployments') || [];
      if (!rows.length) {
        list.innerHTML = '<div class="agents-empty">Nothing deployed yet. Deploying writes an agent, '
          + 'skill or plugin into the files an agentic CLI reads — and records it here so you can undo it.</div>';
        return;
      }
      list.innerHTML = rows.map(row).join('');
      rows.forEach((d, i) => {
        list.querySelector(`.deployment-row[data-idx="${i}"] .deployment-undeploy`)
          ?.addEventListener('click', () => undeploy(d));
      });
    } catch (e) {
      list.innerHTML = `<div class="agents-empty">Failed to load deployments: ${esc(e.message || e)}</div>`;
    }
  }

  function row(d, i) {
    const paths = Array.isArray(d.paths) ? d.paths : [];
    const when = d.deployedAt ? new Date(d.deployedAt).toLocaleString() : '';
    return `
      <div class="deployment-row${d.present ? '' : ' stale'}" data-idx="${i}">
        <span class="agent-kind ${esc(d.kind)}">${esc(String(d.kind).toUpperCase())}</span>
        <div class="deployment-main">
          <div class="deployment-slug">${esc(d.slug)}
            ${d.provider ? `<span class="deployment-prov">${esc(d.provider)}</span>` : ''}
            ${d.present ? '' : '<span class="deployment-stale">files missing</span>'}
          </div>
          <div class="deployment-paths">${paths.map(p => `<code>${esc(p)}</code>`).join(' ')}</div>
        </div>
        <div class="deployment-meta">
          <span class="deployment-target">${esc(d.targetLabel || d.target)}</span>
          <span class="deployment-when">${esc(when)}</span>
        </div>
        <button class="btn-secondary deployment-undeploy">Undeploy</button>
      </div>`;
  }

  async function undeploy(d) {
    if (!confirm(`Remove the deployed files for "${d.slug}" from ${d.targetLabel || d.target}?`)) return;
    try {
      const r = await Arima.api('POST', '/agents/undeploy', { id: d.id, target: d.target });
      Arima.setStatus(`Undeployed ${d.slug} — ${(r && r.removed) || 0} file(s) removed`);
      refreshDeployments();
      if (window.AgentsTab) AgentsTab.refresh();
    } catch (e) {
      Arima.setStatus('Undeploy failed: ' + (e.message || e));
    }
  }

  function init() {
    document.getElementById('agents-new-plugin')?.addEventListener('click', create);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { refresh, refreshDeployments, create, loadTargets };
})();
window.PluginsTab = PluginsTab;
