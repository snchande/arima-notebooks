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

  const settings = () => (Arima.state && Arima.state.settings) || {};

  function enabled() {
    const s = settings();
    return s.polyglotEnabled !== false && compareModes().length > 0;
  }

  function compareModes() {
    const raw = settings().polyglotCompareLanguages || '';
    return raw.split(',').map(m => m.trim()).filter(m => m && LABEL[m]);
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
        const stale = !isNative && isStale(cell, m);
        return `<button class="pg-tab${isNative ? ' active native' : ''}${stale ? ' stale' : ''}"
                        data-mode="${m}" data-cell="${cell.id}"
                        title="${isNative ? 'This cell\'s own language' : 'Show this cell in ' + LABEL[m]}">
                  <span class="pg-ico">${ICON[m] || '◈'}</span>${LABEL[m]}${isNative ? '' :
                  (cell.translations && cell.translations[m] ? '<span class="pg-dot"></span>' : '')}
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

    if (settings().polyglotSideBySide && others.length) {
      toggleCompare(cell, div, bodyWrap, others[0]);
    }
  }

  /** A translation generated from source that has since changed is flagged, not hidden. */
  function isStale(cell, mode) {
    const t = cell.translations && cell.translations[mode];
    return !!(t && t.sourceHash && t.sourceHash !== hashOf(cell.source || ''));
  }

  /* The server stores a SHA-256 prefix; the browser cannot cheaply reproduce it
     synchronously, so staleness is tracked against the source seen at load time. */
  const _hashes = new Map();
  function hashOf(src) {
    if (_hashes.has(src)) return _hashes.get(src);
    return null;
  }
  function rememberHash(src, h) { _hashes.set(src, h); }

  /* ── Tab switching ─────────────────────────────────────────────── */
  async function selectTab(cell, div, bodyWrap, mode) {
    const strip = document.getElementById(`pg-strip-${cell.id}`);
    strip.querySelectorAll('.pg-tab').forEach(b =>
      b.classList.toggle('active', b.dataset.mode === mode));

    clearPanel(cell.id);
    if (mode === cell.mode) {
      bodyWrap.style.display = '';
      active.delete(cell.id);
      return;
    }

    bodyWrap.style.display = 'none';
    const panel = makePanel(cell.id);
    bodyWrap.parentNode.insertBefore(panel, bodyWrap.nextSibling);

    const existing = cell.translations && cell.translations[mode];
    if (existing && existing.source) {
      renderTranslation(cell, panel, mode, existing);
    } else {
      await generate(cell, panel, mode);
    }
  }

  function makePanel(cellId) {
    const p = document.createElement('div');
    p.className = 'pg-panel';
    p.id = `pg-panel-${cellId}`;
    return p;
  }

  function clearPanel(cellId) {
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
        source: cell.source || '',
        from: cell.mode,
        to: mode
      });
      cell.translations = cell.translations || {};
      cell.translations[mode] = t;
      rememberHash(cell.source || '', t.sourceHash);
      renderTranslation(cell, panel, mode, t);
      NotebookEditor.markDirty && NotebookEditor.markDirty();
      document.querySelector(`#pg-strip-${cell.id} .pg-tab[data-mode="${mode}"]`)
        ?.insertAdjacentHTML('beforeend', '<span class="pg-dot"></span>');
    } catch (err) {
      panel.innerHTML =
        `<div class="pg-error">
           Could not render this cell in ${LABEL[mode]}.
           <div class="pg-error-detail">${(err && err.message) || err}</div>
           <button class="pg-retry">Try again</button>
         </div>`;
      panel.querySelector('.pg-retry').addEventListener('click', () => generate(cell, panel, mode));
    }
  }

  function renderTranslation(cell, panel, mode, translation, opts = {}) {
    const stale = translation.sourceHash &&
                  hashOf(cell.source || '') &&
                  translation.sourceHash !== hashOf(cell.source || '');

    panel.innerHTML =
      `${stale ? `<div class="pg-stale-bar">The ${LABEL[cell.mode]} cell changed since this was generated.
                   <button class="pg-regen">Regenerate</button></div>` : ''}
       <div class="pg-editor" id="pg-ed-${cell.id}-${mode}"></div>
       <div class="pg-actions">
         <button class="pg-run">Run this ${LABEL[mode]} cell</button>
         <button class="pg-regen-btn" title="Generate this translation again">Regenerate</button>
         <span class="pg-meta">${translation.edited ? 'edited by you' :
            'generated' + (translation.provider ? ' via ' + translation.provider : '')}</span>
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
      extraKeys: { 'Shift-Enter': () => runTranslation(cell, mode, cm, panel) }
    });
    cm.on('change', () => {
      translation.source = cm.getValue();
      translation.edited = true;
      NotebookEditor.markDirty && NotebookEditor.markDirty();
    });

    active.set(cell.id, { mode, cm, compare: !!opts.compare });

    panel.querySelector('.pg-run').addEventListener('click', e => {
      e.stopPropagation(); runTranslation(cell, mode, cm, panel);
    });
    panel.querySelectorAll('.pg-regen, .pg-regen-btn').forEach(b =>
      b.addEventListener('click', e => { e.stopPropagation(); generate(cell, panel, mode); }));
  }

  /* ── Running a translated cell ─────────────────────────────────── */
  async function runTranslation(cell, mode, cm, panel) {
    const out = panel.querySelector(`#pg-out-${cell.id}-${mode}`);
    out.className = 'pg-output running';
    out.textContent = `Running ${LABEL[mode]}...`;
    try {
      const res = await Arima.api('POST', '/shell/execute', {
        sessionId: `polyglot-${mode}`,
        cellId: `${cell.id}-${mode}`,
        mode,
        code: cm.getValue()
      });
      const ok = res.success;
      out.className = `pg-output ${ok ? 'ok' : 'err'}`;
      out.textContent = (ok ? (res.output || '(no output)') : (res.error || 'failed')).trimEnd();
    } catch (err) {
      out.className = 'pg-output err';
      out.textContent = (err && err.message) || String(err);
    }
  }

  /* ── Side-by-side ──────────────────────────────────────────────── */
  function toggleCompare(cell, div, bodyWrap, forceMode) {
    const existing = document.getElementById(`pg-compare-${cell.id}`);
    if (existing && !forceMode) {
      existing.remove();
      bodyWrap.style.display = '';
      div.classList.remove('pg-comparing');
      return;
    }
    clearPanel(cell.id);

    const others = compareModes().filter(m => m !== cell.mode);
    const mode = forceMode || (active.get(cell.id)?.mode) || others[0];
    if (!mode) return;

    bodyWrap.style.display = 'none';
    div.classList.add('pg-comparing');

    const wrap = document.createElement('div');
    wrap.className = 'pg-compare-wrap';
    wrap.id = `pg-compare-${cell.id}`;
    wrap.innerHTML =
      `<div class="pg-side pg-left">
         <div class="pg-side-head">${ICON[cell.mode] || '◈'} ${LABEL[cell.mode]}
           <span class="pg-side-tag">your language</span></div>
         <div class="pg-side-body" id="pg-left-${cell.id}"></div>
       </div>
       <div class="pg-side pg-right">
         <div class="pg-side-head">
           <select class="pg-side-pick">
             ${others.map(m => `<option value="${m}"${m === mode ? ' selected' : ''}>${ICON[m] || '◈'} ${LABEL[m]}</option>`).join('')}
           </select>
         </div>
         <div class="pg-side-body" id="pg-right-${cell.id}"></div>
       </div>`;
    bodyWrap.parentNode.insertBefore(wrap, bodyWrap.nextSibling);

    const leftHost = wrap.querySelector(`#pg-left-${cell.id}`);
    const lta = document.createElement('textarea');
    lta.value = cell.source || '';
    leftHost.appendChild(lta);
    const lcm = CodeMirror.fromTextArea(lta, {
      mode: CM_MODE[cell.mode] || 'text/x-java',
      theme: 'barista-dark',
      lineNumbers: settings().showLineNumbers !== false,
      viewportMargin: Infinity,
      readOnly: false
    });
    lcm.on('change', () => {
      cell.source = lcm.getValue();
      NotebookEditor.markDirty && NotebookEditor.markDirty();
    });

    const rightPanel = document.createElement('div');
    rightPanel.className = 'pg-panel embedded';
    rightPanel.id = `pg-panel-${cell.id}`;
    wrap.querySelector(`#pg-right-${cell.id}`).appendChild(rightPanel);

    const fill = async (m) => {
      const existingT = cell.translations && cell.translations[m];
      if (existingT && existingT.source) renderTranslation(cell, rightPanel, m, existingT, { compare: true });
      else await generate(cell, rightPanel, m);
    };
    fill(mode);

    wrap.querySelector('.pg-side-pick').addEventListener('change', e => {
      e.stopPropagation();
      rightPanel.innerHTML = '';
      fill(e.target.value);
    });
  }

  return { attach, compareModes, LABEL, ICON };
})();
