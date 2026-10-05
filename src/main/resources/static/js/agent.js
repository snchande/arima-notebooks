/**
 * The definition banner — what you get above the cells when the open notebook is a definition
 * rather than an ordinary notebook.
 *
 * A definition is a normal notebook with metadata.kind set to one of four values, and each gets the
 * editor it needs:
 *
 *   agent / skill — name · description · provider · tool grants, a Run dock, and Deploy
 *   tool          — name · description · language · a parameter table, and a Call dock
 *   plugin        — name · description · version · member picker, and Deploy
 *
 * Runs stream over the existing STOMP channel (partial_output, cellId "__agent_run__"); nothing
 * here is a new endpoint or topic.
 *
 * Backend: /api/agents/{create,run,deploy,providers}, /api/tools/{create,invoke},
 * /api/plugins/{create,deploy,targets}.
 */
const Agent = (function () {
  const RUN_CELL_ID = '__agent_run__';
  const PROVIDERS = [['claude', 'Claude'], ['copilot', 'Copilot'], ['gemini', 'Antigravity']];
  const TOOL_MODES = [
    ['jshell', 'JShell'], ['java', 'Java'], ['nodejs', 'JavaScript'], ['typescript', 'TypeScript'],
    ['csharp', 'C#'], ['fsharp', 'F#'], ['cpp', 'C++'], ['python', 'Python']
  ];
  const PARAM_TYPES = ['string', 'integer', 'number', 'boolean'];
  const KIND_ICONS = { agent: '🤖', skill: '⭐', tool: '🔧', plugin: '📦' };

  let current = null;      // the open definition notebook (same ref as NotebookEditor's notebook)
  let avail = {};          // provider -> available
  let targets = [];        // deploy targets: [{key, label, root}]
  let unsub = null;        // STOMP unsubscribe for the active run

  function init() {
    document.getElementById('btn-add-agent')?.addEventListener('click', () => create('agent'));
    document.getElementById('btn-add-skill')?.addEventListener('click', () => create('skill'));
    Arima.api('GET', '/agents/providers').then(a => { avail = a || {}; }).catch(() => {});
    Arima.api('GET', '/plugins/targets').then(t => { targets = t || []; }).catch(() => {});
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
    if (kind === 'agent' || kind === 'skill') renderAgentUI(nb, kind);
    else if (kind === 'tool') renderToolUI(nb);
    else if (kind === 'plugin') renderPluginUI(nb);
  }

  function removeUI() {
    if (unsub) { try { unsub(); } catch {} unsub = null; }
    document.getElementById('agent-banner')?.remove();
    document.getElementById('agent-dock')?.remove();
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /** Insert a banner above the cells and a dock below them. */
  function mount(bannerHtml, dockHtml) {
    const scroll = document.getElementById('cells-scroll');
    const cells = document.getElementById('cells-container');
    if (!scroll || !cells) return false;

    const banner = document.createElement('div');
    banner.id = 'agent-banner';
    banner.className = 'agent-banner';
    banner.innerHTML = bannerHtml;
    scroll.insertBefore(banner, cells);

    if (dockHtml) {
      const dock = document.createElement('div');
      dock.id = 'agent-dock';
      dock.className = 'agent-dock';
      dock.innerHTML = dockHtml;
      scroll.appendChild(dock);
    }
    return true;
  }

  function targetOptions() {
    return targets.map(t => `<option value="${esc(t.key)}">${esc(t.label)}</option>`).join('')
      || '<option value="project">this project</option>';
  }

  // ── Agents & skills ──────────────────────────────────────────────────

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

  function renderAgentUI(nb, kind) {
    const toolChips = kind === 'agent'
      ? `<span class="agent-tools">${tools().map(t => `<span class="agent-chip">${esc(t)}<button class="chip-x" data-tool="${esc(t)}" title="Remove">×</button></span>`).join('')}
           <button class="agent-chip add" id="agent-add-tool">+ tool</button></span>`
      : '';

    const banner = `
      <div class="agent-banner-row">
        <span class="agent-kind ${kind}">${kind === 'skill' ? 'SKILL' : 'AGENT'}</span>
        <input id="agent-name" class="agent-name" value="${esc(nb.name)}" spellcheck="false" />
        <label class="agent-prov">provider
          <select id="agent-provider">${providerOptions(defaultProvider())}</select>
        </label>
        <label class="agent-prov">deploy to
          <select id="agent-target">${targetOptions()}</select>
        </label>
        <button id="agent-deploy" class="agent-export">⤓ Deploy</button>
      </div>
      <input id="agent-desc" class="agent-desc" value="${esc(nb.description)}" placeholder="One-line description (frontmatter)" />
      ${toolChips}
      <div class="agent-hint">Write this ${kind}'s instructions in the markdown cells below — they become the system prompt.${
        kind === 'agent' ? ' A grant that matches a tool you authored in Arima gets its full signature in the prompt.' : ''}</div>`;

    const dock = `
      <div class="agent-dock-head">▶ Run ${kind}</div>
      <textarea id="agent-task" class="agent-task" rows="2" placeholder="Give the ${kind} a task…"></textarea>
      <div class="agent-dock-actions">
        <button id="agent-run" class="agent-run">▶ Run</button>
        <span id="agent-run-status" class="agent-run-status"></span>
      </div>
      <pre id="agent-output" class="agent-output" hidden></pre>`;

    if (!mount(banner, dock)) return;
    wireAgent(nb, kind);
  }

  function wireAgent(nb, kind) {
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

    document.getElementById('agent-deploy')?.addEventListener('click', () =>
      deployDefinition(provEl.value, document.getElementById('agent-target')?.value));
    document.getElementById('agent-run')?.addEventListener('click', () => run(provEl.value));
    document.getElementById('agent-task')?.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); run(provEl.value); }
    });

    // Tool grants (agents only)
    document.getElementById('agent-add-tool')?.addEventListener('click', () => {
      const t = prompt('Tool name — a CLI built-in (Read, Grep, Bash) or an Arima tool you authored:');
      if (!t) return;
      nb.metadata = nb.metadata || {}; nb.metadata.agent = nb.metadata.agent || {};
      nb.metadata.agent.tools = tools().concat(t.trim());
      NotebookEditor.save();
      removeUI(); renderAgentUI(nb, kind);
    });
    document.querySelectorAll('#agent-banner .chip-x').forEach(b =>
      b.addEventListener('click', () => {
        nb.metadata.agent.tools = tools().filter(x => x !== b.dataset.tool);
        NotebookEditor.save();
        removeUI(); renderAgentUI(nb, kind);
      }));
  }

  async function deployDefinition(provider, target) {
    try {
      const r = await Arima.api('POST', '/agents/deploy',
        { notebookId: current.id, provider, target: target || 'project' });
      if (r && r.success) Arima.setStatus(`Deployed → ${(r.paths || [r.path]).join(', ')} (${r.targetLabel})`);
      else Arima.setStatus('Deploy failed: ' + ((r && r.error) || 'unknown'));
    } catch (e) { Arima.setStatus('Deploy failed: ' + (e.message || e)); }
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

  // ── Tools ────────────────────────────────────────────────────────────

  function toolMeta() {
    current.metadata = current.metadata || {};
    current.metadata.tool = current.metadata.tool || { mode: 'python', params: [] };
    if (!Array.isArray(current.metadata.tool.params)) current.metadata.tool.params = [];
    return current.metadata.tool;
  }

  function slugify(s) {
    return String(s || '').toLowerCase().trim()
      .replace(/[^a-z0-9]+/g, '-').replace(/(^-+|-+$)/g, '') || 'unnamed';
  }

  function renderToolUI(nb) {
    const meta = toolMeta();
    const params = meta.params;
    const sig = params.map(p => `${esc(p.name)}${p.required ? '' : '?'}`).join(', ');

    const rows = params.length ? params.map((p, i) => `
      <tr data-i="${i}">
        <td><input class="tparam-name" value="${esc(p.name)}" spellcheck="false" placeholder="name" /></td>
        <td><select class="tparam-type">${PARAM_TYPES.map(t =>
              `<option value="${t}"${t === (p.type || 'string') ? ' selected' : ''}>${t}</option>`).join('')}</select></td>
        <td><input class="tparam-desc" value="${esc(p.description)}" placeholder="what it is" /></td>
        <td class="tparam-req-cell"><input class="tparam-req" type="checkbox"${p.required ? ' checked' : ''} /></td>
        <td><input class="tparam-def" value="${esc(p.default)}" placeholder="default" /></td>
        <td><button class="tparam-del" title="Remove">×</button></td>
      </tr>`).join('')
      : '<tr class="tparam-empty"><td colspan="6">No parameters. The body runs with nothing bound.</td></tr>';

    const banner = `
      <div class="agent-banner-row">
        <span class="agent-kind tool">TOOL</span>
        <input id="agent-name" class="agent-name" value="${esc(nb.name)}" spellcheck="false" />
        <label class="agent-prov">language
          <select id="tool-mode">${TOOL_MODES.map(([k, l]) =>
            `<option value="${k}"${k === meta.mode ? ' selected' : ''}>${l}</option>`).join('')}</select>
        </label>
        <label class="agent-prov">deploy to
          <select id="agent-target">${targetOptions()}</select>
        </label>
        <button id="tool-deploy" class="agent-export" title="Expose this tool to MCP clients via a plugin bundle">⤓ Deploy</button>
      </div>
      <input id="agent-desc" class="agent-desc" value="${esc(nb.description)}" placeholder="One-line description — agents read this to decide when to call it" />
      <div class="tool-sig">MCP name: <code>arima_${esc(slugify(nb.name).replace(/-/g, '_'))}(${sig})</code></div>
      <table class="tool-params">
        <thead><tr><th>name</th><th>type</th><th>description</th><th>req</th><th>default</th><th></th></tr></thead>
        <tbody id="tool-param-rows">${rows}</tbody>
      </table>
      <div class="agent-banner-row">
        <button class="agent-chip add" id="tool-add-param">+ parameter</button>
      </div>
      <div class="agent-hint">Each parameter is bound as a variable of the same name before the code
        cells below run, in ${esc((TOOL_MODES.find(m => m[0] === meta.mode) || [, meta.mode])[1])}.
        Whatever the tool prints is its return value.</div>`;

    const dock = `
      <div class="agent-dock-head">▶ Call tool</div>
      <div id="tool-args" class="tool-args"></div>
      <div class="agent-dock-actions">
        <button id="tool-call" class="agent-run">▶ Call</button>
        <span id="agent-run-status" class="agent-run-status"></span>
      </div>
      <pre id="agent-output" class="agent-output" hidden></pre>`;

    if (!mount(banner, dock)) return;
    wireTool(nb);
  }

  function wireTool(nb) {
    const nameEl = document.getElementById('agent-name');
    const descEl = document.getElementById('agent-desc');
    const modeEl = document.getElementById('tool-mode');

    const persistHeader = () => {
      nb.name = nameEl.value.trim() || nb.name;
      nb.description = descEl.value.trim();
      toolMeta().mode = modeEl.value;
      NotebookEditor.save();
      removeUI(); renderToolUI(nb);
    };
    nameEl?.addEventListener('change', persistHeader);
    descEl?.addEventListener('change', persistHeader);
    modeEl?.addEventListener('change', persistHeader);

    // Parameter table — read every row back on any change, so order is preserved.
    const readParams = () => {
      const rows = Array.from(document.querySelectorAll('#tool-param-rows tr[data-i]'));
      toolMeta().params = rows.map(tr => ({
        name: tr.querySelector('.tparam-name').value.trim(),
        type: tr.querySelector('.tparam-type').value,
        description: tr.querySelector('.tparam-desc').value.trim(),
        required: tr.querySelector('.tparam-req').checked,
        default: tr.querySelector('.tparam-def').value.trim()
      })).filter(p => p.name);
      NotebookEditor.save();
    };
    document.querySelectorAll('#tool-param-rows input, #tool-param-rows select')
      .forEach(el => el.addEventListener('change', readParams));
    document.querySelectorAll('#tool-param-rows .tparam-del').forEach(btn =>
      btn.addEventListener('click', () => {
        const i = Number(btn.closest('tr').dataset.i);
        toolMeta().params.splice(i, 1);
        NotebookEditor.save();
        removeUI(); renderToolUI(nb);
      }));

    document.getElementById('tool-add-param')?.addEventListener('click', () => {
      readParams();
      toolMeta().params.push({ name: 'arg' + (toolMeta().params.length + 1), type: 'string',
                               description: '', required: true, default: '' });
      NotebookEditor.save();
      removeUI(); renderToolUI(nb);
    });

    document.getElementById('tool-deploy')?.addEventListener('click', () => {
      Arima.setStatus('A tool reaches a CLI through a plugin: add it as a member of a plugin in the '
        + 'Agents tab, then deploy that. It is already callable over MCP as arima_'
        + slugify(nb.name).replace(/-/g, '_') + '.');
    });

    renderToolArgs();
    document.getElementById('tool-call')?.addEventListener('click', callTool);
  }

  /** The call form in the dock — one input per declared parameter. */
  function renderToolArgs() {
    const wrap = document.getElementById('tool-args');
    if (!wrap) return;
    const params = toolMeta().params;
    if (!params.length) {
      wrap.innerHTML = '<div class="tool-args-empty">This tool takes no parameters.</div>';
      return;
    }
    wrap.innerHTML = params.map(p => {
      const def = p.default == null ? '' : p.default;
      const input = p.type === 'boolean'
        ? `<select class="tool-arg" data-arg="${esc(p.name)}">
             <option value="false"${def === 'true' ? '' : ' selected'}>false</option>
             <option value="true"${def === 'true' ? ' selected' : ''}>true</option>
           </select>`
        : `<input class="tool-arg" data-arg="${esc(p.name)}"
                 type="${p.type === 'integer' || p.type === 'number' ? 'number' : 'text'}"
                 ${p.type === 'number' ? 'step="any"' : ''}
                 value="${esc(def)}" placeholder="${esc(p.description) || esc(p.type)}" />`;
      return `
        <label class="tool-arg-row">
          <span class="tool-arg-name">${esc(p.name)}${p.required ? '<b class="tool-arg-req">*</b>' : ''}
            <span class="tool-arg-type">${esc(p.type)}</span></span>
          ${input}
        </label>`;
    }).join('');
  }

  async function callTool() {
    const outEl = document.getElementById('agent-output');
    const statusEl = document.getElementById('agent-run-status');
    const btn = document.getElementById('tool-call');

    const args = {};
    document.querySelectorAll('#tool-args .tool-arg')
      .forEach(input => { args[input.dataset.arg] = input.value; });

    outEl.hidden = false; outEl.textContent = '';
    btn.disabled = true; btn.textContent = '● Calling…';
    statusEl.textContent = 'running';

    try {
      const r = await Arima.api('POST', '/tools/invoke', {
        toolId: current.id, args,
        sessionId: Arima.state.currentSessionId || ('nb-' + current.id)
      });
      if (!r || r.success === false) {
        outEl.textContent = '⚠ ' + ((r && r.error) || 'call failed');
        statusEl.textContent = 'failed';
      } else {
        const body = [r.output, r.returnValue ? 'return: ' + r.returnValue : '']
          .filter(s => s && String(s).trim()).join('\n');
        outEl.textContent = body || '(no output)';
        statusEl.textContent = 'done in ' + (r.executionTimeMs || 0) + 'ms';
      }
    } catch (e) {
      outEl.textContent = 'Error: ' + (e.message || e);
      statusEl.textContent = 'error';
    } finally {
      btn.disabled = false; btn.textContent = '▶ Call';
    }
  }

  // ── Plugins ──────────────────────────────────────────────────────────

  function pluginMeta() {
    current.metadata = current.metadata || {};
    current.metadata.plugin = current.metadata.plugin || { version: '0.1.0', members: [] };
    if (!Array.isArray(current.metadata.plugin.members)) current.metadata.plugin.members = [];
    return current.metadata.plugin;
  }

  function renderPluginUI(nb) {
    const meta = pluginMeta();
    const chips = meta.members.length
      ? meta.members.map(id => `<span class="agent-chip" data-member="${esc(id)}">${esc(id)}<button class="chip-x" data-member="${esc(id)}" title="Remove">×</button></span>`).join('')
      : '<span class="agent-hint-inline">No members yet.</span>';

    const banner = `
      <div class="agent-banner-row">
        <span class="agent-kind plugin">PLUGIN</span>
        <input id="agent-name" class="agent-name" value="${esc(nb.name)}" spellcheck="false" />
        <label class="agent-prov">version
          <input id="plugin-version" class="plugin-version" value="${esc(meta.version)}" spellcheck="false" />
        </label>
        <label class="agent-prov">deploy to
          <select id="agent-target">${targetOptions()}</select>
        </label>
        <button id="plugin-deploy" class="agent-export">⤓ Deploy</button>
      </div>
      <input id="agent-desc" class="agent-desc" value="${esc(nb.description)}" placeholder="One-line description (plugin.json description)" />
      <div class="plugin-members">
        <span class="plugin-members-label">members</span>
        ${chips}
        <button class="agent-chip add" id="plugin-add-member">+ member</button>
      </div>
      <div class="agent-hint">Deploying builds a Claude Code plugin directory — <code>.claude-plugin/plugin.json</code>,
        one file per member agent and skill, a command per member tool, and an <code>.mcp.json</code> pointing
        back at Arima so the tools stay in one place. These markdown cells become the README.</div>`;

    if (!mount(banner, null)) return;
    wirePlugin(nb);
  }

  function wirePlugin(nb) {
    const nameEl = document.getElementById('agent-name');
    const descEl = document.getElementById('agent-desc');
    const verEl = document.getElementById('plugin-version');
    const persist = () => {
      nb.name = nameEl.value.trim() || nb.name;
      nb.description = descEl.value.trim();
      pluginMeta().version = verEl.value.trim() || '0.1.0';
      NotebookEditor.save();
    };
    nameEl?.addEventListener('change', persist);
    descEl?.addEventListener('change', persist);
    verEl?.addEventListener('change', persist);

    document.getElementById('plugin-add-member')?.addEventListener('click', addMember);
    document.querySelectorAll('#agent-banner .chip-x[data-member]').forEach(b =>
      b.addEventListener('click', () => {
        pluginMeta().members = pluginMeta().members.filter(x => x !== b.dataset.member);
        NotebookEditor.save();
        removeUI(); renderPluginUI(nb);
      }));

    document.getElementById('plugin-deploy')?.addEventListener('click', () =>
      deployPlugin(document.getElementById('agent-target')?.value));
  }

  /** Offer the agents, skills and tools that exist, and take the one the user names. */
  async function addMember() {
    let candidates = [];
    try {
      const [agents, toolList] = await Promise.all([
        Arima.api('GET', '/agents/list').catch(() => []),
        Arima.api('GET', '/tools/list').catch(() => [])
      ]);
      candidates = (agents || []).map(a => ({ id: a.id, name: a.name, kind: a.kind }))
        .concat((toolList || []).map(t => ({ id: t.id, name: t.name, kind: 'tool' })));
    } catch { /* fall through to a free-text prompt */ }

    const listing = candidates.length
      ? candidates.map(c => `${KIND_ICONS[c.kind] || '•'} ${c.id} — ${c.name}`).join('\n')
      : '(none found — type an id anyway)';
    const id = prompt('Add a member by id:\n\n' + listing);
    if (!id) return;
    const trimmed = id.trim();
    if (pluginMeta().members.includes(trimmed)) return;
    pluginMeta().members.push(trimmed);
    NotebookEditor.save();
    removeUI(); renderPluginUI(current);
  }

  async function deployPlugin(target) {
    try {
      const r = await Arima.api('POST', '/plugins/deploy',
        { pluginId: current.id, target: target || 'project' });
      if (r && r.success) {
        Arima.setStatus(`Deployed plugin ${r.slug} → ${r.targetLabel} (${r.fileCount} files)`);
      } else {
        Arima.setStatus('Deploy failed: ' + ((r && r.error) || 'unknown'));
      }
    } catch (e) { Arima.setStatus('Deploy failed: ' + (e.message || e)); }
  }

  // Run init now if the DOM is already parsed (this script loads after the toolbar markup),
  // otherwise wait for DOMContentLoaded.
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { onNotebookLoaded };
})();
window.Agent = Agent;
