/**
 * Tools section of the Agent Factory.
 *
 * A tool is a notebook with metadata.kind = "tool": its code cells are the body, and
 * metadata.tool.params is the signature. This module lists them (/api/tools/list), creates new
 * ones (/api/tools/create), and calls one inline with a generated argument form
 * (/api/tools/invoke) so you can see it work before an agent ever reaches for it.
 *
 * Rendering deliberately reuses the .agent-card classes — tools sit in the same grid as agents.
 */
const ToolsTab = (function () {
  const MODE_LABELS = {
    jshell: 'JShell', java: 'Java', nodejs: 'JavaScript', typescript: 'TypeScript',
    csharp: 'C#', fsharp: 'F#', cpp: 'C++', python: 'Python'
  };

  let tools = [];

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function cssEscape(s) {
    return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, '\\$&');
  }

  async function refresh() {
    const grid = document.getElementById('tools-grid');
    if (!grid) return;
    grid.innerHTML = '<div class="agents-empty">Loading…</div>';
    try {
      tools = await Arima.api('GET', '/tools/list') || [];
      if (!tools.length) {
        grid.innerHTML = '<div class="agents-empty">No tools yet. <b>+ New Tool</b> creates one — '
          + 'a notebook whose code cells are the body and whose parameters are bound as variables.</div>';
        return;
      }
      grid.innerHTML = tools.map(card).join('');
      tools.forEach(t => wireCard(grid, t));
    } catch (e) {
      grid.innerHTML = `<div class="agents-empty">Failed to load tools: ${esc(e.message || e)}</div>`;
    }
  }

  function card(t) {
    const params = Array.isArray(t.params) ? t.params : [];
    const sig = params.length
      ? params.map(p => `${esc(p.name)}${p.required ? '' : '?'}`).join(', ')
      : '';
    const chips = params.length
      ? `<div class="agent-card-tools">${params.map(p =>
          `<span class="agent-card-tool" title="${esc(p.description) || ''}">${esc(p.name)}: ${esc(p.type)}</span>`
        ).join('')}</div>`
      : '<div class="agent-card-tools"><span class="agent-card-tool muted">no parameters</span></div>';

    return `
      <div class="agent-card tool-card" data-tool-id="${esc(t.id)}">
        <div class="agent-card-top">
          <span class="agent-kind tool">TOOL</span>
          <span class="agent-card-name" title="${esc(t.name)}">${esc(t.name)}</span>
        </div>
        <div class="agent-card-desc">${esc(t.description) || '<span class="muted">No description</span>'}</div>
        <div class="tool-card-sig"><code>arima_${esc(String(t.slug).replace(/-/g, '_'))}(${sig})</code></div>
        ${chips}
        <div class="agent-card-meta">
          <span class="agent-card-prov">${esc(MODE_LABELS[t.mode] || t.mode)}</span>
          ${t.hasBody ? '' : '<span class="tool-card-warn">no code cells yet</span>'}
        </div>
        <div class="agent-card-actions">
          <button class="btn-secondary tool-card-open">Open</button>
          <button class="btn-primary tool-card-call"${t.hasBody ? '' : ' disabled'}>▶ Call</button>
        </div>
        <div class="agent-card-run-box tool-card-call-box" hidden>
          <div class="tool-card-args"></div>
          <div class="agent-card-run-actions">
            <button class="btn-primary tool-card-go">Call</button>
            <button class="btn-secondary tool-card-cancel">Close</button>
            <span class="agent-card-status tool-card-status"></span>
          </div>
          <pre class="agent-card-output tool-card-output" hidden></pre>
        </div>
      </div>`;
  }

  /** One labelled input per declared parameter — the tool's call form. */
  function argForm(t) {
    const params = Array.isArray(t.params) ? t.params : [];
    if (!params.length) return '<div class="tool-args-empty">This tool takes no parameters.</div>';
    return params.map(p => {
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

  function wireCard(container, t) {
    const el = container.querySelector(`.tool-card[data-tool-id="${cssEscape(t.id)}"]`);
    if (!el) return;
    el.querySelector('.tool-card-open')?.addEventListener('click', () => open(t));

    const box = el.querySelector('.tool-card-call-box');
    el.querySelector('.tool-card-call')?.addEventListener('click', () => {
      if (box.hidden) {
        el.querySelector('.tool-card-args').innerHTML = argForm(t);
        box.hidden = false;
        el.querySelector('.tool-arg')?.focus();
      } else {
        box.hidden = true;
      }
    });
    el.querySelector('.tool-card-cancel')?.addEventListener('click', () => { box.hidden = true; });
    el.querySelector('.tool-card-go')?.addEventListener('click', () => call(el, t));
  }

  function open(t) {
    NotebookEditor.loadNotebook(t.id, t.source !== 'mine');
    document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
  }

  async function call(el, t) {
    const outEl = el.querySelector('.tool-card-output');
    const statusEl = el.querySelector('.tool-card-status');
    const goBtn = el.querySelector('.tool-card-go');

    const args = {};
    el.querySelectorAll('.tool-arg').forEach(input => { args[input.dataset.arg] = input.value; });

    outEl.hidden = false; outEl.textContent = '';
    goBtn.disabled = true; goBtn.textContent = '● Calling…';
    statusEl.textContent = (MODE_LABELS[t.mode] || t.mode) + ' · running';

    try {
      const r = await Arima.api('POST', '/tools/invoke',
        { toolId: t.id, args, sessionId: 'tools-tab-' + t.id });
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
      goBtn.disabled = false; goBtn.textContent = 'Call';
    }
  }

  /** Create a tool, asking which language its body is written in. */
  async function create() {
    const name = prompt('Name your tool:', 'my-tool');
    if (name === null) return;
    const mode = prompt(
      'Which language is the body written in?\n' + Object.keys(MODE_LABELS).join(' · '), 'python');
    if (mode === null) return;
    try {
      const nb = await Arima.api('POST', '/tools/create',
        { name: name.trim(), mode: (mode || 'python').trim().toLowerCase() });
      if (nb && nb.id) {
        NotebookEditor.loadNotebook(nb.id);
        document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
      }
    } catch (e) {
      Arima.setStatus('Create failed: ' + (e.message || e));
    }
  }

  /** The tool catalog, for the plugin member picker. */
  function catalog() { return tools.slice(); }

  function init() {
    document.getElementById('agents-new-tool')?.addEventListener('click', create);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { refresh, create, catalog };
})();
window.ToolsTab = ToolsTab;
