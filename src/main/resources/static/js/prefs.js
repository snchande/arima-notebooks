/**
 * Arima Notebooks — shared client preferences (localStorage, no backend).
 *
 *  • LangPrefs — which programming languages the user wants to see. Chosen in the
 *    First Run Experience and editable in Settings. Used to filter the tutorial
 *    catalog. Default (unset) = all languages, so nothing is hidden until the user
 *    actively narrows it.
 *  • Progress — per-notebook execution progress. A tutorial is "complete" once all
 *    its code cells have been executed at least once.
 *
 * Both fire a document CustomEvent on change so open views can live-refresh.
 */
(function () {
  'use strict';

  // ── Language preferences ────────────────────────────────────────────
  const LANG_KEY = 'arima.languages';
  const ALL_LANGS = ['jshell', 'java', 'javascript', 'typescript', 'csharp', 'fsharp', 'cpp', 'python'];

  window.LangPrefs = {
    ALL: ALL_LANGS.slice(),
    all() { return ALL_LANGS.slice(); },
    /** Selected languages; if never set, everything is considered selected. */
    get() {
      try {
        const v = JSON.parse(localStorage.getItem(LANG_KEY));
        if (Array.isArray(v)) return v.filter(x => ALL_LANGS.includes(x));
      } catch (_) {}
      return ALL_LANGS.slice();
    },
    /** True once the user has made an explicit choice (FRE or Settings). */
    isSet() {
      try { return Array.isArray(JSON.parse(localStorage.getItem(LANG_KEY))); }
      catch (_) { return false; }
    },
    set(arr) {
      const clean = (arr || []).filter(x => ALL_LANGS.includes(x));
      localStorage.setItem(LANG_KEY, JSON.stringify(clean));
      document.dispatchEvent(new CustomEvent('arima:langs-changed', { detail: clean }));
    },
    isSelected(lang) { return this.get().includes(lang); },
  };

  // ── Per-notebook execution progress ─────────────────────────────────
  // Shape: { [notebookId]: { d: [executedCellId, …], t: totalCodeCells } }
  const PROG_KEY = 'arima.progress';

  function load() { try { return JSON.parse(localStorage.getItem(PROG_KEY)) || {}; } catch (_) { return {}; } }
  function save(m) { try { localStorage.setItem(PROG_KEY, JSON.stringify(m)); } catch (_) {} }

  window.Progress = {
    /** Record that a code cell was executed. */
    record(nbId, cellId) {
      if (!nbId || !cellId) return;
      const m = load();
      const entry = m[nbId] || { d: [], t: 0 };
      if (!entry.d.includes(cellId)) {
        entry.d.push(cellId);
        m[nbId] = entry;
        save(m);
        document.dispatchEvent(new CustomEvent('arima:progress-changed', { detail: { nbId } }));
      }
    },
    /** Remember how many code cells a notebook has (learned when it is opened). */
    setTotal(nbId, total) {
      if (!nbId || !(total >= 0)) return;
      const m = load();
      const entry = m[nbId] || { d: [], t: 0 };
      if (entry.t !== total) {
        entry.t = total;
        m[nbId] = entry;
        save(m);
        document.dispatchEvent(new CustomEvent('arima:progress-changed', { detail: { nbId } }));
      }
    },
    /** Progress for a notebook: {done, total, pct, complete, started}. */
    stats(nbId) {
      const e = load()[nbId];
      if (!e) return { done: 0, total: 0, pct: 0, complete: false, started: false };
      const total = e.t || 0;
      const done = Math.min(e.d.length, total || e.d.length);
      const pct = total > 0 ? Math.round((done / total) * 100) : (done > 0 ? 100 : 0);
      return { done, total, pct, complete: total > 0 && done >= total, started: e.d.length > 0 };
    },
    reset(nbId) {
      const m = load();
      if (m[nbId]) { delete m[nbId]; save(m); document.dispatchEvent(new CustomEvent('arima:progress-changed', { detail: { nbId } })); }
    },
  };
})();
