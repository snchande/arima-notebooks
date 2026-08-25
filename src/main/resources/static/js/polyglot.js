/*
 * Polyglot - read a cell in a language you are learning, next to the one you know.
 *
 * Each code cell grows a tab strip: its own language first, then whichever
 * comparison languages the user picked in Settings. Selecting another tab asks the
 * AI provider for that rendering once, stores it on the cell, and from then on it
 * loads instantly and travels with the notebook. Translations are real cells - they
 * can be edited and run - because being able to execute the comparison is the point.
 */
const Polyglot = (() => {
  'use strict';

  const LABEL = {
    jshell: 'JShell', java: 'Java', nodejs: 'JS', typescript: 'TS',
    csharp: 'C#', fsharp: 'F#', cpp: 'C++', python: 'Python'
  };
  const ICON = {
    jshell: '☕', java: '♨', nodejs: '⬡', typescript: '◆',
    csharp: '◈', fsharp: '◇', cpp: '⚙', python: '🐍'
  };
  const CM_MODE = {
    nodejs: 'text/javascript', typescript: 'text/typescript',
    csharp: 'text/x-csharp', fsharp: 'text/x-fsharp',
    cpp: 'text/x-c++src', python: 'text/x-python',
    java: 'text/x-java', jshell: 'text/x-java'
  };

  /** cellId -> { mode, cm, compare } for the tab currently on screen. */
  const active = new Map();
  /** Source text -> sourceHash last seen from the server, for staleness checks. */
  const _hashes = new Map();

  const settings = () => (Arima.state && Arima.state.settings) || {};

  function enabled() {
    const s = settings();
    return s.polyglotEnabled !== false && compareModes().length > 0;
  }

  function compareModes() {
    const raw = settings().polyglotCompareLanguages || '';
    return raw.split(',').map(m => m.trim()).filter(m => m && LABEL[m]);
  }

  function hashOf(src) { return _hashes.has(src) ? _hashes.get(src) : null; }
  function rememberHash(src, h) { _hashes.set(src, h); }

  /**
   * CodeMirror measures the viewport when it is constructed. Building one inside a
   * container that was only just inserted (or was display:none) leaves it sized to a
   * handful of lines, which is what clipped the compare panes. A refresh once layout
   * has settled makes it draw its full height.
   */
  function unclip(cm) {
    const go = () => { try { cm.refresh(); } catch (_) {} };
    requestAnimationFrame(go);
    setTimeout(go, 60);
  }

  /* ── Public entry point, called once per code cell from renderCell ── */
  function attach(cell, div, bodyWrap) {
    if (!enabled() || cell.type !== 'CODE' || cell.mode === 'agent') return;

    const others = compareModes().filter(m => m !== cell.mode);
    if (!others.length) return;

    const strip = document.createElement('div');
    strip.className = 'pg-strip';
    strip.id = `pg-strip-${cell.id}`;

    const tabs = [cell.mode, ...others];
    strip.innerHTML =
      tabs.map(m => {
        const isNative = m === cell.mode;
        const has = !isNative && cell.translations && cell.translations[m];
        return `<button class="pg-tab${isNative ? ' active native' : ''}"
                        data-mode="${m}" data-cell="${cell.id}"
                        title="${isNative ? "This cell's own language" : 'Show this cell in ' + LABEL[m]}">
                  <span class="pg-ico">${ICON[m] || '◈'}</span>${LABEL[m]}${has ? '<span class="pg-dot"></span>' : ''}
                </button>`;
      }).join('') +
      `<span class="pg-spacer"></span>
       <button class="pg-compare" data-cell="${cell.id}" title="Show both languages side by side">
         <svg viewBox="0 0 16 16" width="12" height="12" fill="none">
           <rect x="1.5" y="2.5" width="5" height="11" rx="1" stroke="currentColor" stroke-width="1.3"/>
           <rect x="9.5" y="2.5" width="5" height="11" rx="1" stroke="currentColor" stroke-width="1.3"/>
         </svg> Compare
       </button>`;

    bodyWrap.parentNode.insertBefore(strip, bodyWrap);

    strip.querySelectorAll('.pg-tab').forEach(btn => {
      btn.addEventListener('click', e => {
        e.stopPropagation();
        selectTab(cell, div, bodyWrap, btn.dataset.mode);
      });
    });
    strip.querySelector('.pg-compare').addEventListener('click', e => {
      e.stopPropagation();
      toggleCompare(cell, div, bodyWrap);
    });

    if (settings().polyglotSideBySide) toggleCompare(cell, div, bodyWrap, others[0]);
  }

  /* ── Tab switching ─────────────────────────────────────────────── */
  async function selectTab(cell, div, bodyWrap, mode) {
    const strip = document.getElementById(`pg-strip-${cell.id}`);
    strip.querySelectorAll('.pg-tab').forEach(b =>
      b.classList.toggle('active', b.dataset.mode === mode));

    clearViews(cell.id);
    div.classList.remove('pg-comparing');

    if (mode === cell.mode) {
      bodyWrap.style.display = '';
      active.delete(cell.id);
      return;
    }

    bodyWrap.style.display = 'none';
    const panel = document.createElement('div');
    panel.className = 'pg-panel';
    panel.id = `pg-panel-${cell.id}`;
    bodyWrap.parentNode.insertBefore(panel, bodyWrap.nextSibling);

    const existing = cell.translations && cell.translations[mode];
    if (existing && existing.source) renderTranslation(cell, panel, mode, existing);
    else await generate(cell, panel, mode);
  }

  function clearViews(cellId) {
    document.getElementById(`pg-panel-${cellId}`)?.remove();
    document.getElementById(`pg-compare-${cellId}`)?.remove();
  }

  async function generate(cell, panel, mode) {
    panel.innerHTML =
      `<div class="pg-loading">
         <span class="pg-spin"></span>
         Rendering this cell in ${LABEL[mode]}...
         <span class="pg-hint">asking your AI provider - this happens once per cell</span>
       </div>`;
    try {
      const t = await Arima.api('POST', '/llm/translate', {
        source: cell.source || '', from: cell.mode, to: mode
      });
      cell.translations = cell.translations || {};
      cell.translations[mode] = t;
      rememberHash(cell.source || '', t.sourceHash);
      renderTranslation(cell, panel, mode, t);
      if (typeof NotebookEditor !== 'undefined' && NotebookEditor.markDirty) NotebookEditor.markDirty();

      const tab = document.querySelector(`#pg-strip-${cell.id} .pg-tab[data-mode="${mode}"]`);
      if (tab && !tab.querySelector('.pg-dot')) {
        tab.insertAdjacentHTML('beforeend', '<span class="pg-dot"></span>');
      }
    } catch (err) {
      panel.innerHTML =
        `<div class="pg-error">
           Could not render this cell in ${LABEL[mode]}.
           <div class="pg-error-detail">${(err && err.message) || err}</div>
           <button class="pg-retry">Try again</button>
         </div>`;
      panel.querySelector('.pg-retry').addEventListener('click',
        e => { e.stopPropagation(); generate(cell, panel, mode); });
    }
  }

  /** Build the editor + actions + output for one translated language. */
  function renderTranslation(cell, panel, mode, translation) {
    const known = hashOf(cell.source || '');
    const stale = translation.sourceHash && known && translation.sourceHash !== known;

    panel.innerHTML =
      `${stale ? `<div class="pg-stale-bar">The ${LABEL[cell.mode]} cell changed since this was generated.
                   <button class="pg-regen">Regenerate</button></div>` : ''}
       <div class="pg-editor" id="pg-ed-${cell.id}-${mode}"></div>
       <div class="pg-actions">
         <button class="pg-run">Run ${LABEL[mode]}</button>
         <button class="pg-regen-btn" title="Generate this translation again">Regenerate</button>
         <span class="pg-meta">${translation.edited ? 'edited by you'
            : 'generated' + (translation.provider ? ' via ' + translation.provider : '')}</span>
       </div>
       <div class="pg-output" id="pg-out-${cell.id}-${mode}"></div>`;

    const host = panel.querySelector(`#pg-ed-${cell.id}-${mode}`);
    const ta = document.createElement('textarea');
    ta.value = translation.source || '';
    host.appendChild(ta);

    const cm = CodeMirror.fromTextArea(ta, {
      mode: CM_MODE[mode] || 'text/plain',
      theme: 'barista-dark',
      lineNumbers: settings().showLineNumbers !== false,
      matchBrackets: true,
      viewportMargin: Infinity,
      extraKeys: { 'Shift-Enter': () => runInto(mode, cm.getValue(), outOf(cell, mode), translation) }
    });
    cm.on('change', () => {
      translation.source = cm.getValue();
      translation.edited = true;
      if (typeof NotebookEditor !== 'undefined' && NotebookEditor.markDirty) NotebookEditor.markDirty();
    });
    unclip(cm);
    paintStored(outOf(cell, mode), mode, translation);

    active.set(cell.id, { mode, cm });

    panel.querySelector('.pg-run').addEventListener('click', e => {
      e.stopPropagation(); runInto(mode, cm.getValue(), outOf(cell, mode), translation);
    });
    panel.querySelectorAll('.pg-regen, .pg-regen-btn').forEach(b =>
      b.addEventListener('click', e => { e.stopPropagation(); generate(cell, panel, mode); }));

    return cm;
  }

  const outOf = (cell, mode) => document.getElementById(`pg-out-${cell.id}-${mode}`);

  /* ── Running ───────────────────────────────────────────────────── */
  function fmtMs(ms) {
    if (ms == null) return '';
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`;
  }

  /** Paint a stored or fresh result, including how long that language took. */
  function paint(outEl, mode, ok, text, ms, when) {
    if (!outEl) return;
    outEl.className = `pg-output ${ok ? 'ok' : 'err'}`;
    outEl.innerHTML =
      `<div class="pg-out-head">
         <span class="pg-out-lang">${ICON[mode] || '◈'} ${LABEL[mode]}</span>
         <span class="pg-out-status ${ok ? 'ok' : 'err'}">${ok ? 'ok' : 'failed'}</span>
         ${ms != null ? `<span class="pg-out-ms">${fmtMs(ms)}</span>` : ''}
         ${when ? `<span class="pg-out-when">${when}</span>` : ''}
       </div>
       <pre class="pg-out-body"></pre>`;
    outEl.querySelector('.pg-out-body').textContent = text;
  }

  /**
   * Run one side and persist the result onto `store` - a CellTranslation for a
   * translated tab, or the Cell itself for the native one - so a reopened notebook
   * still shows both languages' output and timing side by side.
   */
  async function runInto(mode, code, outEl, store) {
    if (!outEl) return null;
    outEl.className = 'pg-output running';
    outEl.textContent = `Running ${LABEL[mode]}...`;
    try {
      const res = await Arima.api('POST', '/shell/execute', {
        sessionId: `polyglot-${mode}`,
        cellId: `pg-${mode}-${Date.now()}`,
        mode, code
      });
      const ok   = !!res.success;
      const text = (ok ? (res.output || '(no output)') : (res.error || 'failed')).trimEnd();
      const ms   = res.executionTimeMs != null ? res.executionTimeMs : null;
      const when = new Date().toLocaleString();

      if (store) {
        store.output   = ok ? (res.output || '') : '';
        store.error    = ok ? '' : (res.error || '');
        store.executed = true;
        store.success  = ok;
        store.lastExecutedAt = new Date().toISOString();
        if ('executionTimeMs' in store || store.source !== undefined) store.executionTimeMs = ms;
        store.lastExecutionTimeMs = ms;
        if (typeof NotebookEditor !== 'undefined' && NotebookEditor.markDirty) NotebookEditor.markDirty();
      }

      paint(outEl, mode, ok, text, ms, when);
      return { ok, text, ms };
    } catch (err) {
      const text = (err && err.message) || String(err);
      paint(outEl, mode, false, text, null, null);
      return { ok: false, text, ms: null };
    }
  }

  /** Show the result stored in the notebook, if this side has been run before. */
  function paintStored(outEl, mode, store) {
    if (!outEl || !store || !store.executed) return;
    const ok = store.success !== false && !store.error;
    const ms = store.executionTimeMs != null ? store.executionTimeMs : store.lastExecutionTimeMs;
    const when = store.lastExecutedAt
      ? new Date(store.lastExecutedAt).toLocaleString() : '';
    paint(outEl, mode, ok, (ok ? (store.output || '(no output)') : store.error).trimEnd(), ms, when);
  }

  /* ── Side by side ──────────────────────────────────────────────── */
  function toggleCompare(cell, div, bodyWrap, forceMode) {
    const open = document.getElementById(`pg-compare-${cell.id}`);
    if (open && !forceMode) {
      open.remove();
      bodyWrap.style.display = '';
      div.classList.remove('pg-comparing');
      return;
    }
    clearViews(cell.id);

    const others = compareModes().filter(m => m !== cell.mode);
    const mode = forceMode || active.get(cell.id)?.mode || others[0];
    if (!mode) return;

    bodyWrap.style.display = 'none';
    div.classList.add('pg-comparing');

    const wrap = document.createElement('div');
    wrap.className = 'pg-compare-wrap';
    wrap.id = `pg-compare-${cell.id}`;
    wrap.innerHTML =
      `<div class="pg-compare-bar">
         <button class="pg-run-both" title="Run both languages and compare their output">
           Run both
         </button>
         <span class="pg-verdict" id="pg-verdict-${cell.id}"></span>
         <span class="pg-spacer"></span>
         <button class="pg-close-compare" title="Back to a single editor">Close</button>
       </div>
       <div class="pg-compare-cols">
         <div class="pg-side pg-left">
           <div class="pg-side-head">
             <span>${ICON[cell.mode] || '◈'} ${LABEL[cell.mode]}</span>
             <span class="pg-side-tag">your language</span>
             <span class="pg-spacer"></span>
             <button class="pg-run-left">Run</button>
           </div>
           <div class="pg-side-body" id="pg-left-${cell.id}"></div>
           <div class="pg-output" id="pg-out-${cell.id}-${cell.mode}"></div>
         </div>
         <div class="pg-side pg-right">
           <div class="pg-side-head">
             <select class="pg-side-pick">
               ${others.map(m => `<option value="${m}"${m === mode ? ' selected' : ''}>${ICON[m] || '◈'} ${LABEL[m]}</option>`).join('')}
             </select>
           </div>
           <div class="pg-side-body" id="pg-right-${cell.id}"></div>
         </div>
       </div>`;
    bodyWrap.parentNode.insertBefore(wrap, bodyWrap.nextSibling);

    // Left: the cell's own source, fully rendered and still editable.
    const lta = document.createElement('textarea');
    lta.value = cell.source || '';
    wrap.querySelector(`#pg-left-${cell.id}`).appendChild(lta);
    const lcm = CodeMirror.fromTextArea(lta, {
      mode: CM_MODE[cell.mode] || 'text/x-java',
      theme: 'barista-dark',
      lineNumbers: settings().showLineNumbers !== false,
      matchBrackets: true,
      viewportMargin: Infinity,
      extraKeys: { 'Shift-Enter': () => runInto(cell.mode, lcm.getValue(), outOf(cell, cell.mode), cell) }
    });
    lcm.on('change', () => {
      cell.source = lcm.getValue();
      const main = (typeof NotebookEditor !== 'undefined' && NotebookEditor.getEditor)
        ? NotebookEditor.getEditor(cell.id) : null;
      if (main && main.getValue() !== cell.source) main.setValue(cell.source);
      if (typeof NotebookEditor !== 'undefined' && NotebookEditor.markDirty) NotebookEditor.markDirty();
    });
    unclip(lcm);
    paintStored(outOf(cell, cell.mode), cell.mode, cell);

    const rightPanel = document.createElement('div');
    rightPanel.className = 'pg-panel embedded';
    rightPanel.id = `pg-panel-${cell.id}`;
    wrap.querySelector(`#pg-right-${cell.id}`).appendChild(rightPanel);

    let rightMode = mode;
    let rightCm = null;

    const fill = async (m) => {
      rightMode = m;
      const t = cell.translations && cell.translations[m];
      if (t && t.source) rightCm = renderTranslation(cell, rightPanel, m, t);
      else { await generate(cell, rightPanel, m); rightCm = active.get(cell.id)?.cm || null; }
    };
    fill(mode);

    wrap.querySelector('.pg-side-pick').addEventListener('change', e => {
      e.stopPropagation();
      rightPanel.innerHTML = '';
      fill(e.target.value);
    });

    wrap.querySelector('.pg-run-left').addEventListener('click', e => {
      e.stopPropagation();
      runInto(cell.mode, lcm.getValue(), outOf(cell, cell.mode), cell);
    });

    wrap.querySelector('.pg-run-both').addEventListener('click', async e => {
      e.stopPropagation();
      const verdict = document.getElementById(`pg-verdict-${cell.id}`);
      verdict.className = 'pg-verdict';
      verdict.textContent = '';

      const rightOut = outOf(cell, rightMode);
      const rightSrc = rightCm ? rightCm.getValue()
                               : (cell.translations?.[rightMode]?.source || '');

      const [l, r] = await Promise.all([
        runInto(cell.mode, lcm.getValue(), outOf(cell, cell.mode)),
        rightOut ? runInto(rightMode, rightSrc, rightOut) : Promise.resolve(null)
      ]);

      if (!l || !r) return;
      const same = l.ok && r.ok && l.text.trim() === r.text.trim();
      const label = same ? 'Same output'
                         : (l.ok && r.ok ? 'Output differs' : 'One side failed');

      // Both timings together - the reason to run them side by side at all.
      let timing = '';
      if (l.ms != null && r.ms != null) {
        const faster = l.ms <= r.ms ? cell.mode : rightMode;
        const ratio  = Math.max(l.ms, r.ms) / Math.max(1, Math.min(l.ms, r.ms));
        timing = ` · ${LABEL[cell.mode]} ${fmtMs(l.ms)} vs ${LABEL[rightMode]} ${fmtMs(r.ms)}` +
                 ` · ${LABEL[faster]} faster by ${ratio.toFixed(1)}x`;
      }
      verdict.className = `pg-verdict ${same ? 'same' : 'diff'}`;
      verdict.textContent = label + timing;
    });

    wrap.querySelector('.pg-close-compare').addEventListener('click', e => {
      e.stopPropagation();
      wrap.remove();
      bodyWrap.style.display = '';
      div.classList.remove('pg-comparing');
    });
  }

  return { attach, compareModes, LABEL, ICON };
})();
