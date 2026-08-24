/**
 * Arima Notebooks — Languages + Tutorials, in Settings.
 *
 * Tutorials live here now (removed from the top bar). This module renders:
 *   • the Languages selector (writes LangPrefs), and
 *   • the Tutorials catalog — language tabs limited to the user's selected languages,
 *     each tutorial showing its completion % (from Progress), with Open / ▶ Guided.
 *
 * A "＋ Add languages" shortcut jumps to the Languages selector so the user can widen
 * the set at any time. Everything re-renders live on language / progress changes.
 */
const TutorialsSettings = (function () {
  const LANG_LABEL = { jshell:'JShell', java:'Java', javascript:'JavaScript', typescript:'TypeScript',
    csharp:'C#', fsharp:'F#', cpp:'C++', python:'Python' };
  const LANG_ICON  = { jshell:'☕', java:'♨', javascript:'⬡', typescript:'◆', csharp:'◈', fsharp:'◈', cpp:'⚙', python:'🐍' };
  const ORDER = ['jshell', 'java', 'javascript', 'typescript', 'csharp', 'fsharp', 'cpp', 'python'];
  const SUBCAT_ORDER = ['Basics & Foundations', 'Advanced', 'Data Science & Analytics'];

  let tutorials = null;     // cached catalog (demos filtered out)
  let activeLang = null;    // active language tab

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // ── Languages selector ───────────────────────────────────────────────
  // Built once; toggles update in place (no rebuild) so focus/rapid clicks are safe.
  function renderLangGrid() {
    const grid = document.getElementById('lang-select-grid');
    if (!grid || grid.dataset.built) return;
    const sel = new Set(LangPrefs.get());
    grid.innerHTML = ORDER.map(l => `
      <label class="lang-chip${sel.has(l) ? ' on' : ''}">
        <input type="checkbox" value="${l}"${sel.has(l) ? ' checked' : ''}>
        <span class="lang-chip-icon">${LANG_ICON[l]}</span>${LANG_LABEL[l]}
      </label>`).join('');
    grid.querySelectorAll('input[type=checkbox]').forEach(cb => {
      cb.addEventListener('change', () => {
        cb.closest('.lang-chip')?.classList.toggle('on', cb.checked);
        const chosen = Array.from(grid.querySelectorAll('input:checked')).map(x => x.value);
        LangPrefs.set(chosen);
      });
    });
    grid.dataset.built = '1';
  }

  // Reflect external changes (e.g. from the FRE) onto the existing checkboxes.
  function syncLangGrid() {
    const grid = document.getElementById('lang-select-grid');
    if (!grid || !grid.dataset.built) return;
    const sel = new Set(LangPrefs.get());
    grid.querySelectorAll('input[type=checkbox]').forEach(cb => {
      cb.checked = sel.has(cb.value);
      cb.closest('.lang-chip')?.classList.toggle('on', cb.checked);
    });
  }

  // ── Tutorials catalog ────────────────────────────────────────────────
  async function loadTutorials() {
    if (tutorials) return tutorials;
    try {
      const all = await Arima.api('GET', '/notebooks/tutorials') || [];
      // Tutorials only — drop loose demos (no level and not a "<lang>-<level>" id).
      tutorials = all.filter(nb => (nb.metadata?.level != null) || /-\d{3}$/.test(nb.id || ''));
    } catch (e) { tutorials = []; }
    return tutorials;
  }

  function byLang() {
    const groups = {};
    (tutorials || []).forEach(nb => {
      const lang = nb.metadata?.language || 'jshell';
      const sub  = nb.metadata?.subcategory || 'Advanced';
      (groups[lang] = groups[lang] || {});
      (groups[lang][sub] = groups[lang][sub] || []).push(nb);
    });
    return groups;
  }

  async function renderCatalog() {
    const tabsEl = document.getElementById('tut-cat-tabs');
    const listEl = document.getElementById('tut-cat-list');
    if (!tabsEl || !listEl) return;
    await loadTutorials();

    const groups = byLang();
    const selected = LangPrefs.get();
    // Only show tabs for selected languages that actually have tutorials.
    const langs = ORDER.filter(l => selected.includes(l) && groups[l]);

    // Language tabs + the "add languages" shortcut.
    tabsEl.innerHTML = langs.map(l => {
      const count = Object.values(groups[l]).reduce((n, a) => n + a.length, 0);
      return `<button class="tut-cat-tab${l === activeLang ? ' active' : ''}" data-lang="${l}">
        <span>${LANG_ICON[l]}</span> ${LANG_LABEL[l]} <span class="tut-cat-count">${count}</span>
      </button>`;
    }).join('') +
      `<button class="tut-add-langs" id="tut-add-langs" title="Show more languages">＋ Add languages</button>`;

    tabsEl.querySelectorAll('.tut-cat-tab').forEach(t =>
      t.addEventListener('click', () => { activeLang = t.dataset.lang; renderCatalog(); }));
    document.getElementById('tut-add-langs')?.addEventListener('click', jumpToLanguages);

    if (!langs.length) {
      listEl.innerHTML = `<div class="tut-empty">No languages selected —
        <button class="tut-inline-link" id="tut-empty-add">choose languages</button> to see tutorials.</div>`;
      document.getElementById('tut-empty-add')?.addEventListener('click', jumpToLanguages);
      return;
    }
    if (!activeLang || !langs.includes(activeLang)) activeLang = langs[0];

    const subs = groups[activeLang] || {};
    const orderedSubs = [
      ...SUBCAT_ORDER.filter(s => subs[s]),
      ...Object.keys(subs).filter(s => !SUBCAT_ORDER.includes(s)),
    ];
    listEl.innerHTML = orderedSubs.map(sub => `
      <div class="tut-subcat">
        <div class="tut-subcat-label">${esc(sub)}</div>
        ${subs[sub].sort((a, b) => (a.metadata?.level || 0) - (b.metadata?.level || 0))
          .map(row).join('')}
      </div>`).join('');

    listEl.querySelectorAll('.tut-row').forEach(el => {
      const id = el.dataset.id;
      el.querySelector('.tut-open')?.addEventListener('click', () => open(id));
      el.querySelector('.tut-guided')?.addEventListener('click', () => guided(id));
    });
  }

  function row(nb) {
    const st = window.Progress ? Progress.stats(nb.id) : { pct: 0, complete: false, started: false };
    const lvl = nb.metadata?.level ? `<span class="tut-level">${nb.metadata.level}</span>` : '';
    let status;
    if (st.complete) status = `<span class="tut-status done">✓ Complete</span>`;
    else if (st.started) status = `<span class="tut-status">${st.pct}%</span>`;
    else status = `<span class="tut-status muted">Not started</span>`;
    const bar = `<div class="tut-bar"><div class="tut-bar-fill${st.complete ? ' done' : ''}" style="width:${st.complete ? 100 : st.pct}%"></div></div>`;
    return `<div class="tut-row" data-id="${esc(nb.id)}">
      <div class="tut-row-main">
        <div class="tut-row-name">${esc(nb.name)}${lvl}</div>
        <div class="tut-row-desc">${esc(nb.description || '')}</div>
        ${bar}
      </div>
      <div class="tut-row-side">
        ${status}
        <div class="tut-row-actions">
          <button class="btn-secondary tut-open">Open</button>
          <button class="btn-primary tut-guided">▶ Guided</button>
        </div>
      </div>
    </div>`;
  }

  function open(id) {
    // NotebookEditor is a top-level module const (not on window) — reference it by name.
    if (typeof NotebookEditor !== 'undefined') {
      NotebookEditor.loadNotebook(id, true);
      document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    }
  }
  function guided(id) { if (window.TutorialPlayer) TutorialPlayer.launch(id); }

  function jumpToLanguages() {
    const card = document.getElementById('languages-card');
    if (!card) return;
    document.querySelector('.tab-btn[data-tab="settings"]')?.click();
    card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    card.classList.add('pulse');
    setTimeout(() => card.classList.remove('pulse'), 1400);
  }

  // ── Init ─────────────────────────────────────────────────────────────
  function init() {
    renderLangGrid();
    renderCatalog();
    document.addEventListener('arima:langs-changed', () => { syncLangGrid(); renderCatalog(); });
    document.addEventListener('arima:progress-changed', () => renderCatalog());
    // Refresh completion when the user returns to the Settings tab.
    document.querySelector('.tab-btn[data-tab="settings"]')?.addEventListener('click', () => renderCatalog());
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { jumpToLanguages, refresh: renderCatalog };
})();
window.TutorialsSettings = TutorialsSettings;
