/**
 * Arima Notebooks — Notebook Editor
 *
 * Execution modes per CODE cell:
 *   jshell (default) — JShell snippets, variables shared across all cells in session
 *   java             — Full Java compile+run, each cell is independent
 *
 * Cell Orchestration (//@ annotations):
 *   //@ anchor: name        — Names this cell as a reusable anchor
 *   //@ depends: a, b       — Declares prerequisite anchors
 *   //@ pipeline: name      — PIPELINE cell: names the pipeline
 *   //@ steps: a, b, c      — PIPELINE cell: ordered execution plan
 *   //@ description: text   — Label shown in cell header
 */
const NotebookEditor = (() => {
  let notebook   = null;
  let editors    = {};     // cellId → CodeMirror instance
  let autoSave   = null;
  let anchorMap  = {};     // anchor → cell (rebuilt on every render + annotation change)
  let focusedCellId = null; // currently focused cell (for AI context)

  // ── Notebook tab state ─────────────────────────────────────────────────
  const tabStore  = new Map(); // notebookId → notebook object (saved state)
  const tabOrder  = [];        // ordered list of open tab IDs
  let   activeTabId = null;

  /* ── Mode helpers ─────────────────────────────────── */
  function modeLabelFor(mode) {
    return { jshell:'JShell', java:'Java', nodejs:'JS', typescript:'TS', csharp:'C#', fsharp:'F#', cpp:'C++' }[mode] || 'JShell';
  }
  function _baseCmMode(mode) {
    if (mode === 'nodejs')     return 'text/javascript';
    if (mode === 'typescript') return 'text/typescript';
    if (mode === 'csharp')     return 'text/x-csharp';
    if (mode === 'fsharp')     return 'text/x-fsharp';
    if (mode === 'cpp')        return 'text/x-c++src';
    return 'text/x-java';
  }
  function cmModeFor(mode) {
    return { name: 'barista-semantic', base: _baseCmMode(mode) };
  }

  // ── Semantic overlay ─────────────────────────────────
  // Runs on top of the base mode to highlight function calls and type names.
  (function registerArimaOverlay() {
    try {
      if (CodeMirror.modes['barista-semantic']) return;
      CodeMirror.defineMode('barista-semantic', function(config, modeConfig) {
        const base = CodeMirror.getMode(config, modeConfig.base || 'text/x-java');
        const overlay = {
          token(stream) {
            // @Annotation or #preprocessor → v-annot
            if (stream.peek() === '@' || stream.peek() === '#') {
              stream.next();
              stream.eatWhile(/[\w$]/);
              return 'v-annot';
            }
            // PascalCase identifier → v-type
            if (/[A-Z]/.test(stream.peek() || '')) {
              stream.eatWhile(/[\w$]/);
              return 'v-type';
            }
            // lowercase word — check if followed by '(' (function call)
            if (/[a-z_$]/.test(stream.peek() || '')) {
              const start = stream.pos;
              stream.eatWhile(/[\w$]/);
              const count = stream.pos - start;
              if (count > 0) {
                const after = stream.string.slice(stream.pos).trimStart();
                if (after.startsWith('(')) return 'v-call';
                stream.backUp(count);
              }
            }
            stream.next();
            return null;
          }
        };
        return CodeMirror.overlayMode(base, overlay, false);
      });
    } catch(e) {
      // overlay addon not loaded — fall back to base mode string
      CodeMirror.defineMode('barista-semantic', function(config, modeConfig) {
        return CodeMirror.getMode(config, modeConfig.base || 'text/x-java');
      });
    }
  })();

  /* ── Init ─────────────────────────────────────────── */
  function init() {
    refreshList();
    bindToolbar();
    document.getElementById('notebook-selector')?.addEventListener('change', e => {
      if (e.target.value) loadNotebook(e.target.value);
    });
    initNotebookBrowser();

    // ESC: restore any maximized cell (global handler)
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        const maximized = document.querySelector('.cell-maximized');
        if (maximized) {
          const cellId = maximized.dataset.cellId;
          if (cellId) restoreCell(cellId);
          e.stopPropagation();
        }
      }
    }, true); // capture phase so it runs before CM's own ESC handler
  }

  /* ── Notebook browser ─────────────────────────────── */
  function initNotebookBrowser() {
    const overlay  = document.getElementById('notebook-browser');
    const closeBtn = document.getElementById('nb-browser-close');
    const searchEl = document.getElementById('nb-browser-search');
    const listEl   = document.getElementById('nb-browser-list');
    let personal   = [];
    let tutorials  = [];
    let activeFolder = null; // null = All, or a folder name string
    let activeTag    = null; // null = All, or a tag string

    const LANG_LABEL = { jshell:'JShell', java:'Java', javascript:'JavaScript', nodejs:'JS', typescript:'TypeScript', csharp:'C#', fsharp:'F#', cpp:'C++' };
    const LANG_ICON  = { jshell:'☕', java:'♨', javascript:'⬡', nodejs:'⬡', typescript:'◆', csharp:'◈', fsharp:'◈', cpp:'⚙' };
    const SUBCAT_ORDER = ['Basics & Foundations', 'Advanced', 'Data Science & Analytics', 'Examples & Demos'];

    document.getElementById('btn-browse-notebooks')?.addEventListener('click', async () => {
      overlay.classList.add('open');
      searchEl.value = '';
      activeFolder = null; activeTag = null;
      listEl.innerHTML = '<div class="nb-browser-empty">Loading…</div>';
      try {
        [personal, tutorials] = await Promise.all([
          Arima.api('GET', '/notebooks'),
          Arima.api('GET', '/notebooks/tutorials')
        ]);
        renderBrowser('');
      } catch(e) {
        listEl.innerHTML = '<div class="nb-browser-empty">Could not load notebooks.</div>';
      }
      searchEl.focus();
    });

    closeBtn?.addEventListener('click',  () => overlay.classList.remove('open'));
    overlay?.addEventListener('click',   e  => { if (e.target === overlay) overlay.classList.remove('open'); });
    searchEl?.addEventListener('input',  ()  => renderBrowser(searchEl.value.toLowerCase()));
    document.addEventListener('keydown', e  => { if (e.key === 'Escape') overlay.classList.remove('open'); });

    // Helpers to read folder/tags from notebook metadata
    function nbFolder(nb) { return (nb.metadata?.folder || '').trim(); }
    function nbTags(nb)   { return Array.isArray(nb.metadata?.tags) ? nb.metadata.tags : []; }

    function renderBrowser(q) {
      // Apply search + folder + tag filters
      let filtPersonal = personal.filter(nb => nb.name.toLowerCase().includes(q));
      if (activeFolder) filtPersonal = filtPersonal.filter(nb => nbFolder(nb) === activeFolder);
      if (activeTag)    filtPersonal = filtPersonal.filter(nb => nbTags(nb).includes(activeTag));
      const filtTutorials = tutorials.filter(nb => nb.name.toLowerCase().includes(q));

      // Collect all unique folders and tags across all personal notebooks
      const allFolders = [...new Set(personal.map(nbFolder).filter(Boolean))].sort();
      const allTags    = [...new Set(personal.flatMap(nbTags))].sort();

      let html = '';

      // ── My Notebooks section ──────────────────────────────────
      html += `<div class="nbb-section">
        <div class="nbb-section-hdr">
          <span class="nbb-section-title">My Notebooks</span>
          <button class="nbb-action-btn" id="nbb-new-btn">+ New Notebook</button>
        </div>`;

      // Folder filter pills
      if (allFolders.length) {
        html += `<div class="nbb-folder-pills">
          <button class="nbb-folder-pill${!activeFolder ? ' active' : ''}" data-folder="">All</button>`;
        allFolders.forEach(f => {
          html += `<button class="nbb-folder-pill${activeFolder===f ? ' active' : ''}" data-folder="${f.replace(/"/g,'&quot;')}">
            <svg viewBox="0 0 16 16" fill="none" width="11" height="11"><path d="M1 3h5l1.5 2H15v9H1V3z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
            ${f}
          </button>`;
        });
        html += `</div>`;
      }

      // Tag filter pills
      if (allTags.length) {
        html += `<div class="nbb-tag-filter-row">`;
        if (activeTag) html += `<button class="nbb-tag-filter-pill active" data-tag="${activeTag.replace(/"/g,'&quot;')}"># ${activeTag} ×</button>`;
        else allTags.forEach(t => {
          html += `<button class="nbb-tag-filter-pill" data-tag="${t.replace(/"/g,'&quot;')}"># ${t}</button>`;
        });
        html += `</div>`;
      }

      if (!filtPersonal.length) {
        html += `<div class="nb-browser-empty small">No personal notebooks${q||activeFolder||activeTag ? ' matching filters' : ' yet — create one!'}.</div>`;
      } else if (activeFolder) {
        // Single folder view — no grouping header needed
        html += `<div class="nb-browser-card-list">${filtPersonal.map(nb => cardHtml(nb, false)).join('')}</div>`;
      } else {
        // Group by folder, then ungrouped
        const withFolder    = filtPersonal.filter(nb => nbFolder(nb));
        const withoutFolder = filtPersonal.filter(nb => !nbFolder(nb));
        const byFolder      = {};
        withFolder.forEach(nb => { const f = nbFolder(nb); (byFolder[f] = byFolder[f]||[]).push(nb); });

        Object.keys(byFolder).sort().forEach(folder => {
          html += `<div class="nbb-folder-group">
            <div class="nbb-folder-group-hdr">
              <svg viewBox="0 0 16 16" fill="none" width="12" height="12"><path d="M1 3h5l1.5 2H15v9H1V3z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
              ${folder}
            </div>
            <div class="nb-browser-card-list">${byFolder[folder].map(nb => cardHtml(nb, false)).join('')}</div>
          </div>`;
        });
        if (withoutFolder.length) {
          if (withFolder.length) html += `<div class="nbb-folder-group-hdr nbb-uncat">Uncategorized</div>`;
          html += `<div class="nb-browser-card-list">${withoutFolder.map(nb => cardHtml(nb, false)).join('')}</div>`;
        }
      }
      html += `</div>`;

      // ── Anchor explorer — cells named with //@ anchor: across open notebooks ──
      const openAnchors = [];
      tabStore.forEach((nb) => {
        if (nb?.cells) {
          nb.cells.forEach(cell => {
            if (cell.anchor) openAnchors.push({ nb: nb.name, anchor: cell.anchor, mode: cell.mode || 'jshell' });
          });
        }
      });
      if (openAnchors.length) {
        html += `<div class="nbb-section nbb-anchors-section">
          <div class="nbb-section-hdr">
            <span class="nbb-section-title">Cell Anchors</span>
            <span class="nbb-section-note">open notebooks · use in <code class="nbb-inline-code">//@ depends:</code></span>
          </div>
          <div class="nbb-anchor-grid">`;
        openAnchors.forEach(a => {
          const icon = { jshell: '☕', java: '♨', nodejs: '⬡', typescript: '◆', csharp: '◈', fsharp: '◈', cpp: '⚙' }[a.mode] || '◈';
          html += `<div class="nbb-anchor-item" title="From notebook: ${a.nb}">
            <span class="nbb-anchor-icon">${icon}</span>
            <code class="nbb-anchor-code">${a.anchor}</code>
            <span class="nbb-anchor-nb">${a.nb}</span>
          </div>`;
        });
        html += `</div>
          <div class="nbb-ref-examples">
            <span class="nbb-ref-label">Quick reference:</span>
            <code class="nbb-ref-eg">//@ anchor: ${openAnchors[0]?.anchor || 'setup'}</code><span class="nbb-ref-sep">→ name a cell</span>
            <code class="nbb-ref-eg">//@ depends: ${openAnchors[0]?.anchor || 'setup'}</code><span class="nbb-ref-sep">→ declare dependency</span>
          </div>
        </div>`;
      }

      // ── Arima Tutorials section ───────────────────────────────
      html += `<div class="nbb-section nbb-tutorials">
        <div class="nbb-section-hdr">
          <span class="nbb-section-title">Arima Tutorials</span>
          <span class="nbb-section-note">${filtTutorials.length} notebooks · read-only</span>
        </div>`;

      if (!filtTutorials.length) {
        html += `<div class="nb-browser-empty small">No tutorials${q ? ' matching "'+q+'"' : ''}.</div>`;
      } else {
        // Group by language, then subcategory
        const byLang = {};
        filtTutorials.forEach(nb => {
          const meta = nb.metadata || {};
          const lang = meta.language || 'jshell';
          const sub  = meta.subcategory || 'Advanced';
          if (!byLang[lang]) byLang[lang] = {};
          if (!byLang[lang][sub]) byLang[lang][sub] = [];
          byLang[lang][sub].push(nb);
        });

        ['jshell','java','javascript','typescript','csharp','fsharp','cpp'].forEach(lang => {
          if (!byLang[lang]) return;
          html += `<div class="nbb-lang-group">
            <div class="nbb-lang-hdr">${LANG_ICON[lang]||''} ${LANG_LABEL[lang]||lang}</div>`;

          SUBCAT_ORDER.forEach(subcat => {
            const items = byLang[lang][subcat];
            if (!items?.length) return;
            html += `<div class="nbb-subcat">
              <div class="nbb-subcat-label">${subcat}</div>
              <div class="nb-browser-card-list">${items.map(nb => cardHtml(nb, true)).join('')}</div>
            </div>`;
          });
          html += `</div>`;
        });
      }
      html += `</div>`;

      listEl.innerHTML = html;

      // Bind card clicks (open notebook)
      listEl.querySelectorAll('.nb-browser-card').forEach(card => {
        card.addEventListener('click', (e) => {
          if (e.target.closest('.nbb-delete-btn')) return; // handled by delete handler
          if (e.target.closest('.nbb-meta-btn'))   return; // handled by meta handler
          overlay.classList.remove('open');
          loadNotebook(card.dataset.id, card.dataset.tutorial === 'true');
        });
      });

      // Bind folder pills
      listEl.querySelectorAll('.nbb-folder-pill').forEach(pill => {
        pill.addEventListener('click', (e) => {
          e.stopPropagation();
          activeFolder = pill.dataset.folder || null;
          renderBrowser(searchEl.value.toLowerCase());
        });
      });

      // Bind tag filter pills
      listEl.querySelectorAll('.nbb-tag-filter-pill').forEach(pill => {
        pill.addEventListener('click', (e) => {
          e.stopPropagation();
          activeTag = activeTag === pill.dataset.tag ? null : pill.dataset.tag;
          renderBrowser(searchEl.value.toLowerCase());
        });
      });

      // Bind meta-edit buttons (folder + tags)
      listEl.querySelectorAll('.nbb-meta-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const id      = btn.dataset.metaId;
          const name    = btn.dataset.metaName;
          const folder  = btn.dataset.metaFolder;
          const tagsStr = btn.dataset.metaTags;

          const newFolder = prompt(`Folder for "${name}"\n(leave blank for uncategorized):`, folder);
          if (newFolder === null) return; // cancelled
          const newTags   = prompt(`Tags for "${name}"\n(comma-separated, e.g. ml, tutorial):`, tagsStr);
          if (newTags === null) return;

          const tags = newTags.split(',').map(t => t.trim()).filter(Boolean);
          try {
            await Arima.api('PATCH', `/notebooks/${id}/metadata`, { folder: newFolder.trim(), tags });
            const nb = personal.find(n => n.id === id);
            if (nb) {
              nb.metadata = nb.metadata || {};
              nb.metadata.folder = newFolder.trim();
              nb.metadata.tags   = tags;
            }
            renderBrowser(searchEl.value.toLowerCase());
            Arima.setStatus(`Updated tags for: ${name}`);
          } catch (err) {
            alert('Could not update metadata: ' + (err.message || String(err)));
          }
        });
      });

      // Bind delete buttons (personal notebooks only)
      listEl.querySelectorAll('.nbb-delete-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const id   = btn.dataset.deleteId;
          const name = btn.dataset.deleteName;
          if (!confirm(`Delete "${name}"?\n\nThis cannot be undone.`)) return;
          try {
            await Arima.api('DELETE', `/notebooks/${id}`);
            if (tabStore.has(id)) closeTab(id);
            personal = personal.filter(n => n.id !== id);
            await refreshList();
            renderBrowser(searchEl.value.toLowerCase());
            Arima.setStatus(`Deleted: ${name}`);
          } catch (err) {
            alert('Could not delete notebook: ' + (err.message || String(err)));
          }
        });
      });

      // New notebook button
      document.getElementById('nbb-new-btn')?.addEventListener('click', async () => {
        overlay.classList.remove('open');
        const name = prompt('Notebook name:', 'Untitled Notebook');
        if (!name) return;
        const nb = await Arima.api('POST', '/notebooks', { name });
        await refreshList(); loadNotebook(nb.id);
      });
    }

    function cardHtml(nb, isTutorial) {
      const meta   = nb.metadata || {};
      const cells  = nb.cellCount ?? 0;
      const level  = meta.level  ? `<span class="nbb-level">${meta.level}</span>` : '';
      const lang   = meta.language ? `<span class="nbb-lang-tag ${meta.language}">${LANG_ICON[meta.language]||''}</span>` : '';
      const ro     = isTutorial ? `<span class="nbb-ro-tag" title="Read-only tutorial">tutorial</span>` : '';
      const safeName = nb.name.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
      const tags   = nbTags(nb);
      const tagHtml = tags.map(t => `<span class="nbb-tag-pill">${t}</span>`).join('');
      const del    = isTutorial ? '' : `<button class="nbb-delete-btn" data-delete-id="${nb.id}" data-delete-name="${safeName}" title="Delete this notebook">
        <svg viewBox="0 0 16 16" fill="none" width="11" height="11"><path d="M2 4h12M5 4V2h6v2M6 7v5M10 7v5M3 4l1 10h8l1-10" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>`;
      const editMeta = isTutorial ? '' : `<button class="nbb-meta-btn" data-meta-id="${nb.id}" data-meta-name="${safeName}"
        data-meta-folder="${(meta.folder||'').replace(/"/g,'&quot;')}"
        data-meta-tags="${tags.join(',').replace(/"/g,'&quot;')}" title="Manage folder &amp; tags">
        <svg viewBox="0 0 16 16" fill="none" width="11" height="11"><path d="M3 4h2M3 8h6M3 12h4M9 2l4 4-4.5 4.5H5V7L9 2z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>`;
      return `<div class="nb-browser-card" data-id="${nb.id}" data-tutorial="${isTutorial}">
        <div class="nb-browser-card-name">${nb.name}${level}${tagHtml}</div>
        <div class="nb-browser-card-meta">${cells} cell${cells!==1?'s':''} ${lang}${ro}${editMeta}${del}</div>
      </div>`;
    }
  }

  /* ── Notebook list ────────────────────────────────── */
  async function refreshList() {
    try {
      const list = await Arima.api('GET', '/notebooks');
      const sel  = document.getElementById('notebook-selector');
      const cur  = sel.value;
      sel.innerHTML = '<option value="">— select notebook —</option>';
      list.forEach(nb => {
        const opt = document.createElement('option');
        opt.value = nb.id; opt.textContent = nb.name;
        sel.appendChild(opt);
      });
      if (cur) sel.value = cur;
    } catch(e) { console.error('List failed:', e); }
  }

  /* ── Load notebook (opens in a new tab or switches to existing) ───────── */
  async function loadNotebook(id, isTutorial) {
    // If already open, just switch to it
    if (tabStore.has(id)) { switchTab(id); return; }

    Arima.setStatus('Loading…');
    try {
      let nb, readOnly = false;
      if (isTutorial) {
        // Check if user already has a saved copy (e.g. from prior execution)
        // If so, load that instead of the clean template — output will be preserved
        try {
          nb = await Arima.api('GET', `/notebooks/${id}`);
          readOnly = false; // user's own copy — writable
        } catch (_) {
          nb = await Arima.api('GET', `/notebooks/tutorials/${id}`);
          readOnly = true;  // original template — read-only until first save
        }
      } else {
        nb = await Arima.api('GET', `/notebooks/${id}`);
        readOnly = false;
      }
      nb._readOnly = readOnly;
      // Save current state before switching
      if (activeTabId && notebook) { syncSources(); tabStore.set(activeTabId, notebook); }
      // Register new tab
      if (!tabOrder.includes(id)) tabOrder.push(id);
      tabStore.set(id, nb);
      activeTabId = id;
      notebook = nb;
      _activateTab(id);
      if (!isTutorial) setupAutoSave();
      // Hide the delete button for tutorials — they are read-only shared resources
      const delBtn = document.getElementById('btn-delete-notebook');
      if (delBtn) { delBtn.style.display = isTutorial ? 'none' : ''; }
      Arima.setStatus(`Loaded: ${nb.name}${isTutorial ? ' (tutorial — read-only)' : ''}`);
    } catch(e) {
      Arima.setStatus('Load error: ' + e.message);
    }
  }

  /** Load a notebook into a background tab without switching to it. */
  async function loadNotebookBackground(id) {
    if (tabStore.has(id)) return;
    try {
      const nb = await Arima.api('GET', `/notebooks/${id}`);
      if (!tabStore.has(id)) {
        tabOrder.push(id);
        tabStore.set(id, nb);
        renderTabStrip();
        Arima.setStatus(`Opened background tab: ${nb.name}`);
      }
    } catch(e) { /* silently ignore background load failures */ }
  }

  /** Shared logic for making a tab active (notebook var must already be set). */
  function _activateTab(id) {
    Arima.state.currentNotebookId = id;
    Arima.state.currentSessionId  = `nb-${id}`;
    document.getElementById('notebook-selector').value = id;
    document.getElementById('sb-session').textContent  = `Session: nb-${id}`;
    document.getElementById('empty-state')?.remove();
    syncAnnotations();
    render();
    renderTabStrip();
    // Wipe the per-notebook Variable Inspector state — cell IDs from the
    // previous notebook would be dead links and confuse the user.
    window.VarInspector?.clear?.();
  }

  /** Switch to an already-open tab by ID. */
  function switchTab(id) {
    if (id === activeTabId || !tabStore.has(id)) return;
    if (activeTabId && notebook) { syncSources(); tabStore.set(activeTabId, notebook); }
    activeTabId = id;
    notebook = tabStore.get(id);
    _activateTab(id);
    if (!notebook._readOnly) setupAutoSave();
    const delBtn = document.getElementById('btn-delete-notebook');
    if (delBtn) delBtn.style.display = notebook._readOnly ? 'none' : '';
    Arima.setStatus(`Switched to: ${notebook.name}`);
  }

  /** Close a tab. Switches to nearest neighbor if it was active. */
  function closeTab(id) {
    const idx = tabOrder.indexOf(id);
    if (idx < 0) return;
    tabOrder.splice(idx, 1);
    tabStore.delete(id);

    if (id === activeTabId) {
      activeTabId = null;
      notebook = null;
      editors = {};
      const nextId = tabOrder[Math.min(idx, tabOrder.length - 1)];
      if (nextId) {
        activeTabId = nextId;
        notebook = tabStore.get(nextId);
        _activateTab(nextId);
        setupAutoSave();
      } else {
        // No more tabs — show empty state
        Arima.state.currentNotebookId = null;
        Arima.state.currentSessionId  = null;
        document.getElementById('notebook-selector').value = '';
        document.getElementById('sb-session').textContent = 'No session';
        const container = document.getElementById('cells-container');
        if (container) container.innerHTML = `<div class="empty-state" id="empty-state">
          <div class="empty-icon"><svg viewBox="0 0 48 48" fill="none"><rect x="8" y="6" width="32" height="36" rx="3" stroke="currentColor" stroke-width="2"/><path d="M16 16h16M16 22h16M16 28h10" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></div>
          <h3>No notebook open</h3><p>Select a notebook from the dropdown, or create a new one.</p>
          <button class="btn-primary" id="btn-create-first">Create New Notebook</button></div>`;
        renderTabStrip();
      }
    } else {
      renderTabStrip();
    }
  }

  /** Render the notebook tab strip. */
  function renderTabStrip() {
    const bar = document.getElementById('notebook-tabs-bar');
    if (!bar) return;
    if (tabOrder.length === 0) { bar.innerHTML = ''; return; }
    bar.innerHTML = tabOrder.map(id => {
      const nb = tabStore.get(id);
      const name = nb?.name || id;
      const isActive = id === activeTabId;
      return `<div class="nb-tab${isActive ? ' active' : ''}" data-tab-id="${id}" title="${escapeHtml(name)}">
        <span class="nb-tab-name">${escapeHtml(name)}</span>
        <span class="nb-tab-close" data-close-id="${id}">×</span>
      </div>`;
    }).join('');
    bar.querySelectorAll('.nb-tab').forEach(tab => {
      tab.addEventListener('click', e => {
        if (!e.target.classList.contains('nb-tab-close')) switchTab(tab.dataset.tabId);
      });
    });
    bar.querySelectorAll('.nb-tab-close').forEach(btn => {
      btn.addEventListener('click', e => { e.stopPropagation(); closeTab(btn.dataset.closeId); });
    });
  }

  /* ── Sync annotation fields from cell sources ─────── */
  function syncAnnotations() {
    if (!notebook) return;
    notebook.cells.forEach(cell => {
      if (cell.type === 'CODE' || cell.type === 'PIPELINE') {
        const ann = Orchestration.parseAnnotations(cell.source);
        cell.anchor       = ann.anchor || cell.anchor || null;
        cell.dependsOn    = ann.dependsOn.length ? ann.dependsOn : (cell.dependsOn || []);
        cell.pipelineSteps = ann.pipelineSteps.length ? ann.pipelineSteps : (cell.pipelineSteps || []);
      }
    });
    rebuildAnchorMap();
  }

  function rebuildAnchorMap() {
    anchorMap = Orchestration.buildAnchorMap(notebook?.cells || []);
    Orchestration.refreshAllBadges();
  }

  /* ── Render entire notebook ───────────────────────── */
  function render() {
    const container = document.getElementById('cells-container');
    container.innerHTML = '';
    editors = {};
    if (!notebook?.cells?.length) {
      container.innerHTML = `<div class="empty-state">
        <div class="empty-icon"><svg viewBox="0 0 48 48" fill="none"><rect x="8" y="6" width="32" height="36" rx="3" stroke="currentColor" stroke-width="2"/><path d="M16 16h16M16 22h16M16 28h10" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></div>
        <h3>Empty notebook</h3><p>Add a code or markdown cell to get started.</p></div>`;
      return;
    }
    notebook.cells.forEach((cell, i) => renderCell(cell, i, container));
    rebuildAnchorMap();

    // Show "last session" summary banner for notebooks that have prior execution state
    _showLastSessionSummary();
  }

  /** Notebook-level execution summary from persisted cell state (shown on load). */
  function _showLastSessionSummary() {
    clearNotebookStats();
    if (!notebook?.cells) return;
    const codeCells = notebook.cells.filter(c => (c.type === 'CODE' || c.type === 'PIPELINE') && c.executed && c.lastExecutedAt);
    if (codeCells.length === 0) return;

    // Find the most-recent execution timestamp across all cells
    const latestTs = codeCells.reduce((best, c) =>
      (!best || c.lastExecutedAt > best) ? c.lastExecutedAt : best, null);

    const ok     = codeCells.filter(c => !c.error).length;
    const errors = codeCells.length - ok;
    const tsLabel = latestTs ? _fmtDateTime(latestTs) : '—';

    const container = document.getElementById('cells-container');
    if (!container) return;

    const banner = document.createElement('div');
    banner.id = 'notebook-stats-banner';
    banner.className = 'notebook-stats-banner prev-session' + (errors === 0 ? ' all-ok' : ' has-errors');
    banner.innerHTML = `
      <div class="nsb-dismiss" onclick="document.getElementById('notebook-stats-banner')?.remove()" title="Dismiss">✕</div>
      <div class="nsb-headline">
        <span class="nsb-icon">${errors === 0 ? '✓' : '⚠'}</span>
        <span class="nsb-title">Last session — ${tsLabel}</span>
      </div>
      <div class="nsb-grid">
        <div class="nsb-stat"><div class="nsb-val">${codeCells.length}</div><div class="nsb-lbl">Cells run</div></div>
        <div class="nsb-stat"><div class="nsb-val ok">${ok}</div><div class="nsb-lbl">Passed ✓</div></div>
        ${errors > 0 ? `<div class="nsb-stat"><div class="nsb-val err">${errors}</div><div class="nsb-lbl">Failed ✗</div></div>` : ''}
      </div>`;
    container.insertBefore(banner, container.firstChild);
  }

  /** Format an ISO timestamp to a readable local date+time string. */
  function _fmtDateTime(iso) {
    try {
      const d = new Date(iso);
      const now = new Date();
      const today = now.toDateString() === d.toDateString();
      const yesterday = new Date(now - 86400000).toDateString() === d.toDateString();
      const datePart = today ? 'Today'
                     : yesterday ? 'Yesterday'
                     : d.toLocaleDateString(undefined, { month:'short', day:'numeric', year: d.getFullYear() !== now.getFullYear() ? 'numeric' : undefined });
      const timePart = d.toLocaleTimeString(undefined, { hour:'2-digit', minute:'2-digit', second:'2-digit' });
      return `${datePart} ${timePart}`;
    } catch { return iso; }
  }

  /* ── Render one cell ──────────────────────────────── */
  function renderCell(cell, index, container) {
    const isCode     = cell.type === 'CODE';
    const isPipeline = cell.type === 'PIPELINE';
    const isMd       = cell.type === 'MARKDOWN';

    if (isCode && !cell.mode) cell.mode = 'jshell';

    const div = document.createElement('div');
    div.id        = `cell-${cell.id}`;
    div.className = `cell type-${cell.type.toLowerCase()}`;
    if (isCode) div.classList.add('mode-' + (cell.mode || 'jshell'));
    div.dataset.cellId = cell.id;

    const anchorBadge = cell.anchor
      ? `<span class="cell-anchor-name" id="anchor-badge-${cell.id}">${cell.anchor}</span>`
      : `<span class="cell-anchor-name placeholder" id="anchor-badge-${cell.id}" title="Click to set anchor name">+ anchor</span>`;

    div.innerHTML = `
      <div class="cell-header">
        <span class="cell-badge ${cell.type.toLowerCase()}${isCode ? ' mode-'+cell.mode : ''}" id="badge-${cell.id}">${
          isPipeline ? '⬡ Pipeline' : isCode ? (({ cpp:'⚙', nodejs:'⬡', typescript:'◆', jshell:'☕', java:'♨' }[cell.mode] || '◈') + ' ' + modeLabelFor(cell.mode)) : '✎ Markdown'}</span>
        <span class="cell-count" id="cnt-${cell.id}">${cell.executionCount ? `[${cell.executionCount}]` : '[ ]'}</span>
        <span class="cell-timing" id="timing-${cell.id}">${cell.executionTimeMs ? `✓ ${cell.executionTimeMs}ms` : ''}</span>
        ${(isCode || isPipeline) ? anchorBadge : ''}
        <div class="cell-actions">
          ${isCode ? `
          <button class="mode-toggle-btn" id="mode-btn-${cell.id}" title="Cycle mode: JShell → Java → JS → TS → C# → F# → C++ → JShell">
            <span class="mode-label">${modeLabelFor(cell.mode)}</span>
          </button>
          <button class="cell-btn run-to-deps-btn" id="run-deps-btn-${cell.id}" title="Run with dependencies — uses cached output for already-run deps" style="display:none">
            <svg viewBox="0 0 16 16"><path d="M2 8h12M9 4l5 4-5 4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button class="cell-btn clean-run-btn" id="clean-run-btn-${cell.id}" title="Clean run — force re-execute all deps from scratch" style="display:none">
            <svg viewBox="0 0 16 16"><path d="M3 8a5 5 0 1 1 10 0 5 5 0 0 1-10 0zm3-1l4 2-4 2V7z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round"/><path d="M12.5 3.5L13.5 2.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>
          </button>
          <button class="cell-btn run-to-here-btn" title="Run from top to here">
            <svg viewBox="0 0 16 16"><path d="M3 2l10 6-10 6V2z" fill="currentColor" opacity=".5"/><path d="M9 2l4 6-4 6V2z" fill="currentColor"/></svg>
          </button>
          <button class="cell-btn run-btn" title="Run cell (Shift+Enter)">
            <svg viewBox="0 0 16 16"><path d="M3 2l12 6-12 6V2z" fill="currentColor"/></svg>
          </button>
          <button class="cell-btn cell-stop-btn" id="cell-stop-${cell.id}" title="Stop this cell" style="display:none">
            <svg viewBox="0 0 16 16"><rect x="4" y="4" width="8" height="8" rx="1.5" fill="currentColor"/></svg>
          </button>
` : ''}
          ${isPipeline ? `
          <button class="cell-btn run-pipeline-btn" title="Run pipeline — uses cached output for already-run steps">
            <svg viewBox="0 0 16 16"><path d="M1 4h14M1 8h14M1 12h14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/><circle cx="4" cy="4" r="1.5" fill="currentColor"/><circle cx="8" cy="8" r="1.5" fill="currentColor"/><circle cx="12" cy="12" r="1.5" fill="currentColor"/></svg>
          </button>
          <button class="cell-btn clean-run-pipeline-btn" title="Clean run pipeline — force re-execute all steps from scratch">
            <svg viewBox="0 0 16 16"><path d="M3 8a5 5 0 1 1 10 0 5 5 0 0 1-10 0zm3-1l4 2-4 2V7z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round"/><path d="M12.5 3.5L13.5 2.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>
          </button>
          <button class="cell-btn run-to-here-btn" title="Run to here (including pipeline)">
            <svg viewBox="0 0 16 16"><path d="M3 2l10 6-10 6V2z" fill="currentColor" opacity=".5"/><path d="M9 2l4 6-4 6V2z" fill="currentColor"/></svg>
          </button>` : ''}
          ${isCode ? `
          <button class="cell-btn copy-code-btn" title="Copy cell code to clipboard">
            <svg viewBox="0 0 16 16" fill="none"><rect x="5" y="5" width="8" height="9" rx="1" stroke="currentColor" stroke-width="1.3"/><path d="M3 11V2h8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button class="cell-btn dup-btn" title="Duplicate cell">
            <svg viewBox="0 0 16 16" fill="none"><rect x="2" y="4" width="8" height="9" rx="1" stroke="currentColor" stroke-width="1.3"/><rect x="6" y="2" width="8" height="9" rx="1.5" stroke="currentColor" stroke-width="1.3"/></svg>
          </button>` : ''}
          <button class="cell-btn ai-btn" title="Ask AI about this cell">
            <svg viewBox="0 0 16 16"><path d="M8 1c3.87 0 7 2.69 7 6s-3.13 6-7 6c-1.08 0-2.1-.23-3-.65L1 14l.65-4C1.23 9.1 1 8.08 1 7c0-3.31 3.13-6 7-6z" stroke="currentColor" stroke-width="1.3" fill="none"/></svg>
          </button>
          <button class="cell-btn" title="Move up" onclick="NotebookEditor.moveUp('${cell.id}')">
            <svg viewBox="0 0 16 16"><path d="M8 12V4M4 8l4-4 4 4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button class="cell-btn" title="Move down" onclick="NotebookEditor.moveDown('${cell.id}')">
            <svg viewBox="0 0 16 16"><path d="M8 4v8M4 8l4 4 4-4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          ${(isCode || isPipeline) ? `
          <button class="cell-btn" title="Add cross-notebook dependency" onclick="NotebookEditor._openCrossNbPicker('${cell.id}')">
            <svg viewBox="0 0 16 16" fill="none"><path d="M6 3H3a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h3M10 13h3a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2h-3M5 8h6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>` : ''}
          <button class="cell-btn del-btn" title="Delete cell" onclick="NotebookEditor.deleteCell('${cell.id}')">
            <svg viewBox="0 0 16 16"><path d="M2 4h12M5 4V2h6v2M3 4l1 10h8l1-10" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" fill="none"/></svg>
          </button>
          <button class="cell-btn cell-maximize-btn" id="max-btn-${cell.id}" title="Maximize cell (ESC to restore)">
            <svg viewBox="0 0 16 16" fill="none"><path d="M2 6V2h4M10 2h4v4M14 10v4h-4M6 14H2v-4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </div>
      </div>
      <div class="cell-desc-bar" id="desc-${cell.id}"></div>
      <div class="cell-body-wrap" id="body-${cell.id}"></div>`;

    container.appendChild(div);

    const bodyWrap = document.getElementById(`body-${cell.id}`);
    if (isCode)     buildCodeCell(cell, div, bodyWrap);
    else if (isMd)  buildMdCell(cell, div, bodyWrap);
    else if (isPipeline) buildPipelineCell(cell, div, bodyWrap);

    // Show "last run at …" footer if the cell was previously executed (loaded from notebook)
    if (isCode && cell.executed && cell.lastExecutedAt && bodyWrap) {
      const prevFooter = document.createElement('div');
      const ok  = !cell.error;
      const dur = cell.lastExecutionTimeMs ? formatDuration(cell.lastExecutionTimeMs) : null;
      const ts  = _fmtDateTime(cell.lastExecutedAt);
      prevFooter.className = `cell-exec-footer ${ok ? 'ok' : 'err'} prev-run`;
      prevFooter.innerHTML =
        `<span class="cef-icon">${ok ? '✓' : '✗'}</span>` +
        `<span class="cef-label">${ok ? 'Last run' : 'Last run (error)'}</span>` +
        (dur ? `<span class="cef-time">${dur}</span><span class="cef-sep">·</span>` : '') +
        `<span class="cef-run">Run #${cell.executionCount || '?'}</span>` +
        `<span class="cef-sep">·</span>` +
        `<span class="cef-ts">${ts}</span>`;
      bodyWrap.appendChild(prevFooter);
    }

    // Anchor name click to edit
    const ab = div.querySelector(`#anchor-badge-${cell.id}`);
    if (ab && (isCode || isPipeline)) {
      ab.addEventListener('click', (e) => { e.stopPropagation(); editAnchor(cell, ab); });
    }

    // ── Description bar (shown when cell is not maximized) ───────────────
    updateDescBar(cell, div);

    // ── Maximize / restore ────────────────────────────────────────────────
    const maxBtn = div.querySelector(`#max-btn-${cell.id}`);
    maxBtn?.addEventListener('click', e => { e.stopPropagation(); maximizeCell(cell.id); });

    // Click to focus
    div.addEventListener('click', () => {
      document.querySelectorAll('.cell').forEach(c => c.classList.remove('focused'));
      div.classList.add('focused');
    });
  }

  /** Build the one-line description strip shown below the cell header when not maximized */
  function updateDescBar(cell, div) {
    const bar = div.querySelector(`#desc-${cell.id}`);
    if (!bar) return;
    // Try //@ description: annotation first, then first non-annotation code line
    let desc = '';
    if (cell.source) {
      const ann = Orchestration.parseAnnotations(cell.source);
      if (ann.description) {
        desc = ann.description;
      } else {
        // First non-empty, non-annotation line of source
        const firstLine = cell.source.split('\n')
          .find(l => l.trim() && !l.trim().startsWith('//@'));
        if (firstLine) desc = firstLine.trim().slice(0, 120);
      }
    }
    if (desc) {
      bar.textContent = desc;
      bar.style.display = '';
    } else {
      bar.style.display = 'none';
    }
  }

  /** Make a cell fill the full viewport */
  function maximizeCell(cellId) {
    const div = document.getElementById(`cell-${cellId}`);
    if (!div) return;
    // Already maximized → restore
    if (div.classList.contains('cell-maximized')) { restoreCell(cellId); return; }
    // Restore any currently maximized cell first
    document.querySelectorAll('.cell-maximized').forEach(c =>
      c.classList.remove('cell-maximized'));
    div.classList.add('cell-maximized');
    _setFocusedCell(cellId);
    // Refresh CodeMirror layout inside maximized cell
    const cm = editors[cellId];
    if (cm) setTimeout(() => cm.refresh(), 50);
    // Update maximize button icon to restore icon
    const btn = div.querySelector(`#max-btn-${cellId}`);
    if (btn) btn.title = 'Restore cell (ESC)';
  }

  function restoreCell(cellId) {
    const div = document.getElementById(`cell-${cellId}`);
    if (div) {
      div.classList.remove('cell-maximized');
      const btn = div.querySelector(`#max-btn-${cellId}`);
      if (btn) btn.title = 'Maximize cell (ESC to restore)';
      const cm = editors[cellId];
      if (cm) setTimeout(() => cm.refresh(), 50);
    }
  }

  /* ── Edit anchor name inline ──────────────────────── */
  function editAnchor(cell, badgeEl) {
    const current = cell.anchor || '';
    const input = document.createElement('input');
    input.type  = 'text';
    input.value = current;
    input.className = 'anchor-edit-input';
    input.placeholder = 'anchor-name';
    input.title = 'Enter an anchor name (letters, digits, hyphens).\nOther cells can then reference this cell via //@ depends: ' + (current || 'name');
    badgeEl.replaceWith(input);
    input.focus();
    input.select();

    function commit() {
      const raw  = input.value.trim().toLowerCase().replace(/[^a-z0-9-_]/g, '-').replace(/^-+|-+$/g, '');
      cell.anchor = raw || null;
      // Sync to source if anchor line exists
      syncAnchorToSource(cell);
      rebuildAnchorMap();
      render(); // re-render to update badge display
      Arima.markDirty(true);
    }
    input.addEventListener('blur', commit);
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); input.blur(); }
      if (e.key === 'Escape') { input.value = current; input.blur(); }
    });
  }

  /** If the cell source has a //@ anchor: line, update it to match cell.anchor */
  function syncAnchorToSource(cell) {
    if (!cell.source) return;
    const lines = cell.source.split('\n');
    // Find existing anchor/pipeline annotation line
    const anchorIdx = lines.findIndex(l => l.trim().startsWith('//@') &&
        (l.includes('anchor:') || (cell.type === 'PIPELINE' && l.includes('pipeline:'))));

    if (cell.anchor) {
      const directive = cell.type === 'PIPELINE' ? 'pipeline' : 'anchor';
      const newLine = `//@${directive}: ${cell.anchor}`;
      if (anchorIdx >= 0) {
        lines[anchorIdx] = newLine;
      } else {
        lines.unshift(newLine); // prepend
      }
    } else if (anchorIdx >= 0) {
      lines.splice(anchorIdx, 1); // remove anchor line
    }
    cell.source = lines.join('\n');
    // Update CodeMirror if open
    const cm = editors[cell.id];
    if (cm && cm.getValue() !== cell.source) cm.setValue(cell.source);
  }

  /* ── User drag-to-resize / minimize for a cell editor ─────────────────
     The user, not Arima, decides the editor height. Dragging the handle sets an
     explicit height on the CodeMirror scroller (inline style wins over the CSS
     focus/blur caps); double-clicking toggles a minimized state and back. */
  function attachEditorResizer(editorDiv, cm, handle) {
    const MIN_H = 48;
    const scroller = () => editorDiv.querySelector('.CodeMirror-scroll');

    let startY = 0, startH = 0, dragging = false;

    const onMove = (e) => {
      if (!dragging) return;
      const h = Math.max(MIN_H, startH + (e.clientY - startY));
      const s = scroller();
      if (s) { s.style.height = h + 'px'; s.style.maxHeight = h + 'px'; }
      e.preventDefault();
    };
    const onUp = () => {
      dragging = false;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.body.style.cursor = '';
      cm.refresh();
    };

    handle.addEventListener('mousedown', (e) => {
      const s = scroller();
      if (!s) return;
      e.preventDefault();
      startY = e.clientY;
      startH = s.getBoundingClientRect().height;
      dragging = true;
      editorDiv.classList.add('user-sized');
      editorDiv.classList.remove('minimized');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
      document.body.style.cursor = 'ns-resize';
    });

    handle.addEventListener('dblclick', (e) => {
      e.preventDefault();
      const s = scroller();
      if (!s) return;
      if (editorDiv.classList.toggle('minimized')) {
        editorDiv.classList.add('user-sized');
        s.style.height = MIN_H + 'px';
        s.style.maxHeight = MIN_H + 'px';
      } else {
        // Restore: hand height back to the CSS focus/blur defaults.
        editorDiv.classList.remove('user-sized');
        s.style.height = '';
        s.style.maxHeight = '';
      }
      cm.refresh();
    });
  }

  /* ── Build CODE cell ──────────────────────────────── */
  function buildCodeCell(cell, div, bodyWrap) {
    const editorDiv = document.createElement('div');
    editorDiv.className = 'cell-editor';
    const ta = document.createElement('textarea');
    ta.value = cell.source || '';
    editorDiv.appendChild(ta);
    bodyWrap.appendChild(editorDiv);

    const settings = Arima.state.settings || {};
    const cm = CodeMirror.fromTextArea(ta, {
      mode: cmModeFor(cell.mode), theme: 'barista-dark',
      lineNumbers: settings.showLineNumbers !== false,
      matchBrackets: true, autoCloseBrackets: true,
      styleActiveLine: true,
      indentUnit: 4, indentWithTabs: false,
      viewportMargin: Infinity,
      extraKeys: {
        'Shift-Enter': () => executeCell(cell.id),
        'Ctrl-Enter':  () => executeCell(cell.id),
      }
    });
    editors[cell.id] = cm;

    // User-controlled editor height: a drag handle at the bottom lets the user set any
    // height (drag), and double-click toggles minimize/restore. Their choice wins over
    // the focus/blur defaults — Arima does not hardcode the size.
    const resizer = document.createElement('div');
    resizer.className = 'cell-editor-resize';
    resizer.title = 'Drag to resize · double-click to minimize / restore';
    editorDiv.appendChild(resizer);
    attachEditorResizer(editorDiv, cm, resizer);

    // Expand on focus, collapse on blur — refresh CM so it recalculates line layout
    cm.on('focus', () => {
      _setFocusedCell(cell.id);
      // Wait for CSS transition then refresh so all lines render
      setTimeout(() => cm.refresh(), 260);
    });
    cm.on('blur', () => {
      // Only collapse if another cell isn't already focused
      setTimeout(() => {
        if (focusedCellId === cell.id) _setFocusedCell(null);
        cm.refresh();
      }, 100);
    });

    // Parse annotations and update dep badges on every change (debounced)
    let annotationTimer = null;
    cm.on('change', () => {
      Arima.markDirty(true);
      // If cell had been run, mark stale when source changes
      if (cell.anchor && cell.executionCount) Orchestration.markStale(cell.anchor);
      clearTimeout(annotationTimer);
      annotationTimer = setTimeout(() => {
        cell.source = cm.getValue();
        const ann = Orchestration.parseAnnotations(cell.source);
        const wasAnchor = cell.anchor;
        cell.anchor       = ann.anchor;
        cell.dependsOn    = ann.dependsOn;
        if (wasAnchor !== ann.anchor) {
          // Update anchor badge
          const ab = div.querySelector(`#anchor-badge-${cell.id}`);
          if (ab) {
            ab.textContent = ann.anchor || '+ anchor';
            ab.classList.toggle('placeholder', !ann.anchor);
          }
        }
        // Show/hide run-with-deps and clean-run buttons
        const depsBtn  = document.getElementById(`run-deps-btn-${cell.id}`);
        const cleanBtn = document.getElementById(`clean-run-btn-${cell.id}`);
        const hasDeps  = ann.dependsOn.length > 0;
        if (depsBtn)  depsBtn.style.display  = hasDeps ? '' : 'none';
        if (cleanBtn) cleanBtn.style.display = hasDeps ? '' : 'none';
        rebuildAnchorMap();
        renderDepBadges(cell, bodyWrap);
        updateDescBar(cell, div);
      }, 300);
    });

    // Show dep badges for already-declared dependencies
    renderDepBadges(cell, bodyWrap);

    // Show/hide run-with-deps and clean-run buttons
    const depsBtn  = document.getElementById(`run-deps-btn-${cell.id}`);
    const cleanBtn = document.getElementById(`clean-run-btn-${cell.id}`);
    const hasDepsInitial = cell.dependsOn && cell.dependsOn.length > 0;
    if (depsBtn  && hasDepsInitial) depsBtn.style.display  = '';
    if (cleanBtn && hasDepsInitial) cleanBtn.style.display = '';

    // Restore existing output
    if (cell.output || cell.error || cell.returnValue) {
      showOutput(cell.id, { output: cell.output, error: cell.error,
                            returnValue: cell.returnValue, success: !cell.error }, bodyWrap);
    }

    // Button handlers
    div.querySelector('.run-btn')?.addEventListener('click', () => executeCell(cell.id));
    div.querySelector('.cell-stop-btn')?.addEventListener('click', (e) => { e.stopPropagation(); stopCell(cell.id); });
    div.querySelector('.run-to-here-btn')?.addEventListener('click', () => runToHere(cell.id));
    document.getElementById(`run-deps-btn-${cell.id}`)?.addEventListener('click', () => runWithDeps(cell.id, false));
    document.getElementById(`clean-run-btn-${cell.id}`)?.addEventListener('click', () => runWithDeps(cell.id, true));

    const modeBtn = div.querySelector(`#mode-btn-${cell.id}`);
    modeBtn?.addEventListener('click', e => {
      e.stopPropagation();
      const modeOrder = ['jshell', 'java', 'nodejs', 'typescript', 'csharp', 'fsharp', 'cpp'];
      const oldMode = cell.mode;
      const idx = modeOrder.indexOf(cell.mode);
      cell.mode = modeOrder[(idx + 1) % modeOrder.length];

      modeBtn.querySelector('.mode-label').textContent = modeLabelFor(cell.mode);

      const badge = document.getElementById(`badge-${cell.id}`);
      if (badge) {
        badge.textContent = ({ cpp:'⚙', nodejs:'⬡', typescript:'◆', jshell:'☕', java:'♨' }[cell.mode] || '◈') + ' ' + modeLabelFor(cell.mode);
        badge.className = `cell-badge code mode-${cell.mode}`;
      }
      const cellDiv = document.getElementById(`cell-${cell.id}`);
      if (cellDiv) modeOrder.forEach(m => cellDiv.classList.toggle('mode-' + m, cell.mode === m));

      // Switch CodeMirror syntax highlighting
      const cm = editors[cell.id];
      if (cm) cm.setOption('mode', cmModeFor(cell.mode));

      Arima.markDirty(true);

      // Offer AI code conversion if cell has meaningful content
      const src = cm?.getValue().trim() || '';
      if (src.length > 15 && oldMode !== cell.mode) {
        _offerConversion(cell.id, oldMode, cell.mode, src, cm);
      }
    });

    div.querySelector('.ai-btn')?.addEventListener('click', () => {
      const lang = modeLabelFor(cell.mode);
      AIAssistant?.sendWithContext(cm.getValue(), `Explain this ${lang} code and suggest improvements`);
    });

    div.querySelector('.copy-code-btn')?.addEventListener('click', async () => {
      const source = cm.getValue();
      try {
        await navigator.clipboard.writeText(source);
        Arima.setStatus('Cell code copied to clipboard');
      } catch(e) { Arima.setStatus('Copy failed: ' + e.message); }
    });

    div.querySelector('.dup-btn')?.addEventListener('click', () => {
      if (!notebook) return;
      syncSources();
      const idx = notebook.cells.findIndex(c => c.id === cellId);
      const orig = notebook.cells[idx];
      const dup = {
        id: `cell-${Date.now()}`, type: orig.type, mode: orig.mode || 'jshell',
        source: orig.source, output: '', error: '', executed: false,
        anchor: null, dependsOn: [...(orig.dependsOn||[])], pipelineSteps: []
      };
      notebook.cells.splice(idx + 1, 0, dup);
      render();
      Arima.markDirty(true);
      setTimeout(() => document.getElementById(`cell-${dup.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 50);
      Arima.setStatus('Cell duplicated');
    });
  }

  /* ── Build PIPELINE cell ──────────────────────────── */
  function buildPipelineCell(cell, div, bodyWrap) {
    if (!cell.pipelineSteps) cell.pipelineSteps = [];

    // Source editor (hidden by default, toggled via "Edit" button)
    const editorWrap = document.createElement('div');
    editorWrap.className = 'pipeline-editor-wrap hidden';
    const ta = document.createElement('textarea');
    ta.value = cell.source || '';
    editorWrap.appendChild(ta);
    bodyWrap.appendChild(editorWrap);

    const cm = CodeMirror.fromTextArea(ta, {
      mode: cmModeFor('jshell'), theme: 'barista-dark',
      lineNumbers: false, viewportMargin: Infinity,
      extraKeys: { 'Escape': () => togglePipelineEditor(cell, div, false) }
    });
    editors[cell.id] = cm;

    cm.on('change', () => {
      cell.source = cm.getValue();
      const ann = Orchestration.parseAnnotations(cell.source);
      cell.anchor       = ann.anchor;
      cell.pipelineSteps = ann.pipelineSteps;
      rebuildAnchorMap();
      renderPipelineSteps(cell, div);
      Arima.markDirty(true);
    });

    // Visual pipeline steps display
    const stepsDiv = document.createElement('div');
    stepsDiv.className = 'pipeline-steps-wrap';
    stepsDiv.id = `pipeline-steps-${cell.id}`;
    bodyWrap.appendChild(stepsDiv);

    renderPipelineSteps(cell, div);

    // Restore previous run output (persisted as plain-text summary in cell.output)
    if (cell.executed && cell.lastExecutedAt && cell.output) {
      _restorePipelineOutput(cell, stepsDiv);
    }

    // Button handlers
    div.querySelector('.run-pipeline-btn')?.addEventListener('click', () => runPipeline(cell.id, false));
    div.querySelector('.clean-run-pipeline-btn')?.addEventListener('click', () => runPipeline(cell.id, true));
    div.querySelector('.run-to-here-btn')?.addEventListener('click', () => runToHere(cell.id));
    div.querySelector('.ai-btn')?.addEventListener('click', () => {
      AIAssistant?.sendWithContext(cell.source, 'Explain this pipeline and suggest improvements');
    });
  }

  /** Re-render the saved pipeline output summary on notebook reload */
  function _restorePipelineOutput(cell, stepsDiv) {
    stepsDiv.querySelectorAll('.pipeline-agg-output, .pipeline-prev-run-footer').forEach(e => e.remove());

    // Parse the stored plain-text summary back into entries
    const lines  = (cell.output || '').split('\n');
    const ok     = !cell.error;
    const ts     = _fmtDateTime(cell.lastExecutedAt);
    const dur    = cell.lastExecutionTimeMs ? formatDuration(cell.lastExecutionTimeMs) : null;

    // "Last run" footer for the pipeline cell
    const footer = document.createElement('div');
    footer.className = `pipeline-prev-run-footer cell-exec-footer ${ok ? 'ok' : 'err'} prev-run`;
    footer.innerHTML =
      `<span class="cef-icon">${ok ? '✓' : '✗'}</span>` +
      `<span class="cef-label">${ok ? 'Pipeline last run' : 'Pipeline last run (error)'}</span>` +
      (dur ? `<span class="cef-time">${dur}</span><span class="cef-sep">·</span>` : '') +
      `<span class="cef-ts">${ts}</span>`;

    // Rebuild the aggregated output block from the stored text
    const entries = [];
    let current = null;
    for (const line of lines) {
      const hdrMatch = line.match(/^(.*?)\s+\[(ok|failed|cached)\]$/);
      if (hdrMatch) {
        if (current) entries.push(current);
        current = { label: hdrMatch[1], status: hdrMatch[2], lines: [] };
      } else if (current && line.trim()) {
        current.lines.push(line);
      }
    }
    if (current) entries.push(current);

    if (entries.length > 0) {
      const block = document.createElement('div');
      block.className = 'pipeline-agg-output';
      const cachedCnt = entries.filter(e => e.status === 'cached').length;
      const hdr = document.createElement('div');
      hdr.className = 'pipeline-agg-header';
      hdr.textContent = `Previous run — ${entries.length} step${entries.length !== 1 ? 's' : ''}` +
                        (cachedCnt > 0 ? ` (${cachedCnt} cached)` : '') +
                        ` · ${ts}`;
      block.appendChild(hdr);
      entries.forEach(e => {
        const row = document.createElement('div');
        row.className = `pipeline-agg-row ${e.status === 'failed' ? 'has-error' : ''}`;
        const lbl = document.createElement('span');
        lbl.className = 'pipeline-agg-label';
        lbl.textContent = e.label;
        if (e.status === 'cached') {
          const badge = document.createElement('span');
          badge.className = 'pipeline-agg-cached-badge';
          badge.textContent = 'cached';
          lbl.appendChild(badge);
        }
        row.appendChild(lbl);
        if (e.lines.length) {
          const pre = document.createElement('pre');
          const isError = e.lines[0].startsWith('ERROR:');
          pre.className = `pipeline-agg-pre${isError ? ' pipeline-agg-err' : ''}`;
          pre.textContent = isError ? e.lines[0].slice(7) : e.lines.join('\n');
          row.appendChild(pre);
        }
        block.appendChild(row);
      });
      stepsDiv.appendChild(block);
    }

    stepsDiv.appendChild(footer);
  }

  function renderPipelineSteps(cell, div) {
    const stepsDiv = document.getElementById(`pipeline-steps-${cell.id}`);
    if (!stepsDiv) return;

    const steps = cell.pipelineSteps || [];
    const { errors } = Orchestration.buildExecutionPlan(steps, anchorMap);

    if (steps.length === 0) {
      stepsDiv.innerHTML = `<div class="pipeline-empty">
        <span>No steps defined.</span>
        <span class="pipeline-hint">Add <code>//@ steps: anchor1, anchor2</code> and <code>//@ pipeline: name</code> to this cell's source. Click ✎ to edit.</span>
      </div>`;
      return;
    }

    // Show execution plan (toposorted)
    const { plan } = Orchestration.buildExecutionPlan(steps, anchorMap);

    let html = `<div class="pipeline-steps-list">`;
    if (errors.length > 0) {
      html += `<div class="pipeline-error"><strong>⚠ Pipeline errors:</strong><ul>` +
              errors.map(e => `<li>${escapeHtml(e)}</li>`).join('') + `</ul></div>`;
    }

    // Show planned execution order
    html += `<div class="pipeline-plan-label">Execution plan (${plan.length} step${plan.length !== 1 ? 's' : ''}):</div>`;
    plan.forEach((anchor, i) => {
      const s = Orchestration.getStatus(anchor);
      const targetCell = anchorMap[anchor];
      const cellDesc = targetCell?.source?.split('\n').find(l => !l.trim().startsWith('//@') && l.trim()) || '';
      html += `<div class="pipeline-step-row" data-anchor="${anchor}">
        <span class="step-num">${i + 1}.</span>
        <span class="step-anchor">#${escapeHtml(anchor)}</span>
        <span class="step-preview">${escapeHtml(cellDesc.substring(0, 40))}${cellDesc.length > 40 ? '…' : ''}</span>
        <span class="dep-badge ${s.status} step-status" data-anchor="${anchor}" title="${anchor}: ${s.status}"></span>
      </div>`;
    });
    html += `</div>`;

    // Edit toggle button
    html += `<div class="pipeline-edit-row">
      <button class="btn-secondary-sm pipeline-edit-btn" id="edit-pipeline-${cell.id}">✎ Edit pipeline source</button>
    </div>`;

    stepsDiv.innerHTML = html;

    document.getElementById(`edit-pipeline-${cell.id}`)?.addEventListener('click', () => {
      togglePipelineEditor(cell, div, true);
    });
  }

  function togglePipelineEditor(cell, div, show) {
    const editorWrap = div.querySelector('.pipeline-editor-wrap');
    const stepsDiv   = document.getElementById(`pipeline-steps-${cell.id}`);
    editorWrap?.classList.toggle('hidden', !show);
    stepsDiv?.classList.toggle('hidden', show);
    if (show) editors[cell.id]?.refresh();
  }

  /* ── Build MARKDOWN cell ──────────────────────────── */
  function buildMdCell(cell, div, bodyWrap) {
    const rendered = document.createElement('div');
    rendered.className = 'cell-md-rendered';
    rendered.innerHTML = cell.source ? marked.parse(cell.source) : '<span style="color:var(--text-3)">Click to edit…</span>';

    const editor = document.createElement('textarea');
    editor.className = 'cell-md-edit';
    editor.value = cell.source || '';

    bodyWrap.appendChild(rendered);
    bodyWrap.appendChild(editor);

    rendered.addEventListener('click', () => {
      rendered.classList.add('editing'); editor.classList.add('editing'); editor.focus();
    });
    editor.addEventListener('blur', () => {
      cell.source = editor.value;
      rendered.innerHTML = editor.value ? marked.parse(editor.value) : '<span style="color:var(--text-3)">Click to edit…</span>';
      rendered.classList.remove('editing'); editor.classList.remove('editing');
      Arima.markDirty(true);
    });
    editor.addEventListener('keydown', e => { if (e.key === 'Escape') editor.blur(); });
    div.querySelector('.ai-btn')?.addEventListener('click', () => {
      AIAssistant?.sendWithContext(cell.source, 'Improve this markdown documentation');
    });
  }

  /* ── Dependency badges ────────────────────────────── */
  function renderDepBadges(cell, bodyWrap) {
    bodyWrap.querySelectorAll('.dep-badges-row').forEach(el => el.remove());
    const deps = cell.dependsOn || [];
    if (deps.length === 0) return;

    const row = document.createElement('div');
    row.className = 'dep-badges-row';
    row.innerHTML = `<span class="dep-badges-label">needs:</span>` +
      deps.map(d => {
        const s = Orchestration.getStatus(d);
        return `<span class="dep-badge ${s.status}" data-anchor="${escapeHtml(d)}"
          title="${d}: ${s.status}">${escapeHtml(d)}</span>`;
      }).join('');
    // Insert before the output section (direct child of bodyWrap), or at end
    const firstOutput = bodyWrap.querySelector(':scope > .cell-output-section');
    if (firstOutput) bodyWrap.insertBefore(row, firstOutput);
    else bodyWrap.appendChild(row);
  }

  /* ── Cell focus indicator ────────────────────────── */
  function _setFocusedCell(cellId) {
    if (focusedCellId === cellId) return;
    // Collapse previously focused cell
    if (focusedCellId) {
      const prev = document.getElementById(`cell-${focusedCellId}`);
      prev?.classList.remove('cell-focused');
      // Refresh its CM after the collapse transition
      setTimeout(() => editors[focusedCellId]?.refresh(), 270);
    }
    focusedCellId = cellId;
    AIAssistant?.updateCellContext(cellId);
    // Mirror focus in the Variable Inspector: scroll its corresponding
    // section into view + flash. Silently no-ops when the drawer is
    // closed or the cell has no vars yet.
    window.VarInspector?.scrollToCell?.(cellId);
    if (!cellId) return;
    const el = document.getElementById(`cell-${cellId}`);
    el?.classList.add('cell-focused');
    // Refresh the newly expanded cell's CM after transition
    setTimeout(() => editors[cellId]?.refresh(), 270);
  }

  /* ── Scroll & focus helpers ───────────────────────── */

  /**
   * Scroll the cells pane so `cellId` is near the TOP of the viewport (with padding),
   * giving the user a clear view of the cell + all output below it.
   */
  function scrollCellToTop(cellId) {
    const cellEl = document.getElementById(`cell-${cellId}`);
    const scroll = document.getElementById('cells-scroll');
    if (!cellEl || !scroll) return;
    const cellTop    = cellEl.getBoundingClientRect().top;
    const scrollTop  = scroll.getBoundingClientRect().top;
    const offset     = cellTop - scrollTop;
    // If cell is already visible in the top half of the viewport, don't force-scroll
    const scrollH    = scroll.clientHeight;
    if (offset >= 0 && offset <= scrollH * 0.35) return; // already in good position
    const newTop = scroll.scrollTop + offset - 72; // 72px breathing room from top
    scroll.scrollTo({ top: Math.max(0, newTop), behavior: 'smooth' });
  }

  /**
   * Scroll the cells pane so `cellId` is in the upper-center of the viewport.
   * Always scrolls if the cell is in the lower half or off-screen below.
   */
  function scrollCellIntoCenter(cellId) {
    const cellEl = document.getElementById(`cell-${cellId}`);
    const scroll = document.getElementById('cells-scroll');
    if (!cellEl || !scroll) return;
    const cellRect   = cellEl.getBoundingClientRect();
    const scrollRect = scroll.getBoundingClientRect();
    const offset     = cellRect.top - scrollRect.top;  // cell top relative to scroll area
    const scrollH    = scroll.clientHeight;
    // Already in the upper portion of viewport — no need to scroll
    if (offset >= 60 && offset <= scrollH * 0.40) return;
    // Scroll so cell appears at ~30% from top of viewport
    const target = scroll.scrollTop + offset - scrollH * 0.30;
    scroll.scrollTo({ top: Math.max(0, target), behavior: 'smooth' });
  }

  /**
   * Flash the output section of `cellId` to draw the user's eye to the result.
   */
  function flashOutput(cellId) {
    const body    = document.getElementById(`body-${cellId}`);
    const section = body?.querySelector('.cell-output-section');
    if (!section) return;
    section.classList.remove('output-attention');
    void section.offsetWidth;               // force reflow → restart animation
    section.classList.add('output-attention');
  }

  /* ── Cell execution UX helpers ───────────────────── */
  const SPIN_CHARS = ['⠋','⠙','⠹','⠸','⠼','⠴','⠦','⠧','⠇','⠏'];

  function setRunningIndicator(cellId, source) {
    clearRunningIndicator(cellId);
    const body = document.getElementById(`body-${cellId}`);
    if (!body) return;

    // Extract meaningful code lines (skip annotations, blank lines, pure comments)
    const codeLines = (source || '').split('\n')
      .map(l => l.trim())
      .filter(l => l && !l.startsWith('//@') && l !== '{' && l !== '}');

    const firstLine = codeLines[0] || 'preparing…';

    const el = document.createElement('div');
    el.className = 'cell-run-anim';
    el.id = `run-anim-${cellId}`;
    el.innerHTML = `
      <div class="run-header">
        <span class="run-spinner" id="run-spinner-${cellId}">⠋</span>
        <span class="run-label">&nbsp;Executing</span>
        <span class="run-prog-pct" id="run-ptext-${cellId}">0%</span>
        <button class="run-stop-btn" id="run-stop-${cellId}" type="button" title="Stop this cell (interrupts a never-ending loop or a stuck input wait)">■ Stop</button>
      </div>
      <div class="run-progress-track">
        <div class="run-progress-fill" id="run-prog-${cellId}" style="width:0%"></div>
      </div>
      <div class="run-current-line" id="run-line-${cellId}">→&nbsp;<span>${escapeHtml(firstLine)}</span></div>`;
    body.insertBefore(el, body.firstChild);

    // Stop button — interrupt this cell (never-ending loop or stuck input wait).
    const stopBtn = document.getElementById(`run-stop-${cellId}`);
    if (stopBtn) stopBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      Arima.sendToShell(Arima.state.currentSessionId, 'interrupt', { cellId });
      stopBtn.textContent = '■ Stopping…';
      stopBtn.disabled = true;
    });

    let tick = 0, lineIdx = 0, progress = 0;
    const timer = setInterval(() => {
      const sp    = document.getElementById(`run-spinner-${cellId}`);
      const fill  = document.getElementById(`run-prog-${cellId}`);
      const ptext = document.getElementById(`run-ptext-${cellId}`);
      const line  = document.getElementById(`run-line-${cellId}`);
      if (!sp || !sp.parentNode) { clearInterval(timer); return; }

      // Spinner
      sp.textContent = SPIN_CHARS[++tick % SPIN_CHARS.length];

      // Asymptotic progress — approaches 95%, jumps to 100% when done
      progress = Math.min(progress + (95 - progress) * 0.07, 94);
      if (fill)  fill.style.width  = progress.toFixed(1) + '%';
      if (ptext) ptext.textContent = Math.round(progress) + '%';

      // Cycle through code lines as "currently executing"
      if (codeLines.length > 0) {
        lineIdx = (lineIdx + 1) % codeLines.length;
        if (line) {
          const ln = codeLines[lineIdx];
          line.innerHTML = `→&nbsp;<span>${escapeHtml(ln.length > 72 ? ln.slice(0, 72) + '…' : ln)}</span>`;
        }
      }
    }, 130);
    el.dataset.timerId = String(timer);
  }

  function clearRunningIndicator(cellId) {
    const el = document.getElementById(`run-anim-${cellId}`);
    if (!el) return;
    if (el.dataset.timerId) clearInterval(Number(el.dataset.timerId));
    // Snap to 100% briefly before removing
    const fill  = document.getElementById(`run-prog-${cellId}`);
    const ptext = document.getElementById(`run-ptext-${cellId}`);
    const line  = document.getElementById(`run-line-${cellId}`);
    const sp    = document.getElementById(`run-spinner-${cellId}`);
    if (fill)  fill.style.width = '100%';
    if (ptext) ptext.textContent = '100%';
    if (line)  line.innerHTML = '✓&nbsp;<span>Done</span>';
    if (sp)    sp.textContent = '✓';
    setTimeout(() => el.remove(), 400);
  }

  function updateTimingBadge(cellId, state, timeMs) {
    const el = document.getElementById(`timing-${cellId}`);
    if (!el) return;
    const dur = (timeMs != null && timeMs > 0) ? formatDuration(timeMs) : null;
    if (state === 'running') {
      el.className = 'cell-timing running';
      el.textContent = '● running…';
    } else if (state === 'ok') {
      el.className = 'cell-timing ok';
      el.textContent = dur ? `✓ ${dur}` : '✓';
    } else if (state === 'error') {
      el.className = 'cell-timing err';
      el.textContent = dur ? `✗ ${dur}` : '✗ failed';
    } else if (state === 'err-net') {
      el.className = 'cell-timing err';
      el.textContent = '✗ network error';
    } else {
      el.className = 'cell-timing';
      el.textContent = '';
    }
  }

  function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

  function formatDuration(ms) {
    if (ms == null) return '—';
    if (ms < 1000)  return ms + 'ms';
    if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
    const m = Math.floor(ms / 60000), s = ((ms % 60000) / 1000).toFixed(2);
    return `${m}m ${s}s`;
  }

  function clearNotebookStats() {
    document.getElementById('notebook-stats-banner')?.remove();
  }

  function showNotebookStats({ executed, ok, errors, totalMs, cellStats }) {
    clearNotebookStats();
    const container = document.getElementById('cells-container');
    if (!container) return;

    const fastest = cellStats.length ? cellStats.reduce((a,b) => a.ms < b.ms ? a : b) : null;
    const slowest = cellStats.length ? cellStats.reduce((a,b) => a.ms > b.ms ? a : b) : null;
    const avgMs   = cellStats.length ? Math.round(cellStats.reduce((s,c) => s + c.ms, 0) / cellStats.length) : 0;
    const allOk   = errors === 0;
    const ts      = new Date().toLocaleTimeString();

    const banner = document.createElement('div');
    banner.id = 'notebook-stats-banner';
    banner.className = 'notebook-stats-banner' + (allOk ? ' all-ok' : ' has-errors');
    banner.innerHTML = `
      <div class="nsb-dismiss" onclick="document.getElementById('notebook-stats-banner')?.remove()" title="Dismiss">✕</div>
      <div class="nsb-headline">
        <span class="nsb-icon">${allOk ? '✓' : '⚠'}</span>
        <span class="nsb-title">${allOk ? 'Notebook Completed Successfully' : 'Notebook Completed with Errors'}</span>
        <span class="nsb-ts">${ts}</span>
      </div>
      <div class="nsb-grid">
        <div class="nsb-stat">
          <div class="nsb-val">${executed}</div>
          <div class="nsb-lbl">Cells Run</div>
        </div>
        <div class="nsb-stat">
          <div class="nsb-val ok">${ok}</div>
          <div class="nsb-lbl">Passed ✓</div>
        </div>
        ${errors > 0 ? `<div class="nsb-stat">
          <div class="nsb-val err">${errors}</div>
          <div class="nsb-lbl">Failed ✗</div>
        </div>` : ''}
        <div class="nsb-stat highlight">
          <div class="nsb-val time">${formatDuration(totalMs)}</div>
          <div class="nsb-lbl">Total Time</div>
        </div>
        <div class="nsb-stat">
          <div class="nsb-val">${formatDuration(avgMs)}</div>
          <div class="nsb-lbl">Avg / Cell</div>
        </div>
      </div>
      ${cellStats.length > 1 ? `
      <div class="nsb-breakdown">
        ${[...cellStats].sort((a, b) => a.ms - b.ms).map(c => `
          <div class="nsb-cell-row ${c.err ? 'err' : 'ok'}">
            <span class="nsb-cell-icon">${c.err ? '✗' : '✓'}</span>
            <span class="nsb-cell-name">${escapeHtml(c.label)}</span>
            <span class="nsb-cell-dur">${formatDuration(c.ms)}</span>
          </div>`).join('')}
      </div>` : ''}`;
    container.appendChild(banner);
    // Scroll the stats banner to the top of the viewport so the full summary is visible
    setTimeout(() => {
      const scroll = document.getElementById('cells-scroll');
      if (!scroll) return;
      const newTop = scroll.scrollTop
                   + banner.getBoundingClientRect().top
                   - scroll.getBoundingClientRect().top
                   - 12;
      scroll.scrollTo({ top: Math.max(0, newTop), behavior: 'smooth' });
    }, 80);
  }

  /* ── Notebook run lifecycle (Stop / abort) ─────────
     Complements the per-cell Stop button: this halts a whole-notebook run
     (Run All / Run-to-here) and interrupts whichever cell is executing.
     _seqRun is non-null while a sequence is in flight; _runningCellId names
     the single cell the kernel is currently executing, for a targeted interrupt. */
  let _seqRun = null;
  let _runningCellId = null;

  // A run started — reveal the toolbar Stop button, disable Run All.
  function _runUiActive() {
    const stop = document.getElementById('btn-stop-run');
    if (stop) {
      stop.style.display = '';
      stop.disabled = false;
      const label = document.getElementById('stop-run-label');
      if (label) label.textContent = 'Stop';
    }
    document.getElementById('btn-run-all')?.setAttribute('disabled', '');
  }

  // Nothing left running — hide Stop, re-enable Run All.
  function _runUiIdleIfDone() {
    if (_seqRun || _runningCellId) return;
    const stop = document.getElementById('btn-stop-run');
    if (stop) {
      stop.style.display = 'none';
      stop.disabled = false;
      const label = document.getElementById('stop-run-label');
      if (label) label.textContent = 'Stop';
    }
    document.getElementById('btn-run-all')?.removeAttribute('disabled');
  }

  // Per-cell Stop: interrupt just this cell (leaves any Run-All sequence to continue
  // to the next cell — the notebook-level Stop is what aborts the whole run).
  function stopCell(cellId) {
    if (!Arima.state.currentSessionId) return;
    Arima.sendToShell(Arima.state.currentSessionId, 'interrupt', { cellId });
    const btn = document.getElementById(`cell-stop-${cellId}`);
    if (btn) { btn.disabled = true; btn.title = 'Stopping…'; }
  }

  // Toolbar Stop: abort any running sequence, then interrupt the current cell.
  function stopRun() {
    if (_seqRun) _seqRun.aborted = true;
    if (_runningCellId && Arima.state.currentSessionId) {
      Arima.sendToShell(Arima.state.currentSessionId, 'interrupt', { cellId: _runningCellId });
    }
    const stop = document.getElementById('btn-stop-run');
    if (stop) {
      stop.disabled = true;
      const label = document.getElementById('stop-run-label');
      if (label) label.textContent = 'Stopping…';
    }
    Arima.setStatus('Stopping…');
  }

  async function runAllSequential(cells, { delay = 0 } = {}) {
    clearNotebookStats();
    const run = { aborted: false };
    _seqRun = run;
    _runUiActive();
    const t0 = Date.now();
    let executed = 0, ok = 0, errors = 0, aborted = false;
    const cellStats = [];

    try {
      for (let i = 0; i < cells.length; i++) {
        if (run.aborted) { aborted = true; break; }
        const cell = cells[i];
        if (cell.type !== 'CODE' && cell.type !== 'PIPELINE') continue;

        const cellT0 = Date.now();
        if (cell.type === 'CODE') await executeCell(cell.id);
        else await runPipeline(cell.id);
        const cellMs = Date.now() - cellT0;

        if (cell.type === 'CODE') {
          executed++;
          const div = document.getElementById(`cell-${cell.id}`);
          const hasErr = div?.classList.contains('has-error');
          if (hasErr) errors++; else ok++;
          cellStats.push({ label: cell.anchor || cell.id, ms: cellMs, err: hasErr });
        }

        if (run.aborted) { aborted = true; break; }
        // delay between cells (skip after last)
        const isLast = cells.slice(i + 1).every(c => c.type === 'MARKDOWN');
        if (delay > 0 && !isLast) await sleep(delay);
      }
    } finally {
      _seqRun = null;
      _runUiIdleIfDone();
    }

    return { executed, ok, errors, aborted, totalMs: Date.now() - t0, cellStats };
  }

  /* ── Execute a single cell ────────────────────────── */
  async function executeCell(cellId) {
    if (!notebook || !Arima.state.currentSessionId) { alert('Open a notebook first.'); return; }
    const cell = notebook.cells.find(c => c.id === cellId);
    if (!cell || cell.type !== 'CODE') return;

    // Auto-route through dependency resolver if cell has cross-notebook deps
    const hasCrossNotebookDep = cell.dependsOn?.some(d => d.startsWith('notebook:'));
    if (hasCrossNotebookDep) { await runWithDeps(cellId); return; }

    const cm = editors[cellId];
    if (cm) cell.source = cm.getValue();

    const div = document.getElementById(`cell-${cellId}`);
    div?.classList.add('running');
    div?.classList.remove('has-error', 'success-flash');
    clearOutput(cellId);
    _setFocusedCell(cellId);

    scrollCellIntoCenter(cellId);
    setRunningIndicator(cellId, cell.source);
    updateTimingBadge(cellId, 'running');

    if (cell.anchor) Orchestration.markRunning(cell.anchor);
    const modeLabel = modeLabelFor(cell.mode);
    Arima.setStatus(`Running [${modeLabel}]…`);
    document.getElementById('kernel-info').textContent = `${modeLabel} running…`;

    // ── Interactive stdin via WebSocket ──────────────────────────────────
    // Subscribe to the session topic BEFORE sending the REST request.
    // The server fires partial_output / input_needed events while the REST
    // call is still in flight (the HTTP thread blocks on JShell execution).
    const sessionId = Arima.state.currentSessionId;
    let hadInteractiveInput = false;
    let hadStreamed = false;
    const bodyWrap = document.getElementById(`body-${cellId}`);

    const unsubWs = Arima.subscribeToSession(sessionId, (msg) => {
      if (msg.cellId !== cellId) return; // ignore other cells
      if (msg.type === 'partial_output') {
        // Live progress: show output as it is produced. This is an ephemeral preview —
        // the authoritative full output replaces it via applyResult() on completion.
        if (hadInteractiveInput) return;   // interactive terminal owns the display
        if (!hadStreamed) { clearRunningIndicator(cellId); hadStreamed = true; }
        appendStreamPreview(bodyWrap, msg.text);
        return;
      }
      if (msg.type === 'input_needed') {
        // Switching to interactive input — drop the streaming preview, the terminal takes over.
        removeStreamPreview(bodyWrap); hadStreamed = false;
        // Stop the running animation — the terminal prompt is the new "waiting" indicator
        if (!hadInteractiveInput) clearRunningIndicator(cellId);
        hadInteractiveInput = true;
        // Append any output the program printed before blocking (e.g. "What's your name? ")
        // then immediately show the prompt — single message guarantees correct order.
        if (msg.text) appendInteractiveChunk(bodyWrap, msg.text);
        showInteractivePrompt(bodyWrap, sessionId, cellId);
        // Grab the user's attention if they've switched to another tab/app.
        if (window.Notifications) {
          const nbName = (typeof notebook !== 'undefined' && notebook) ? notebook.name : '';
          Notifications.inputNeeded({ sessionId, cellId, tabId: activeTabId, notebookName: nbName });
        }
      }
    });

    _runningCellId = cellId;
    _runUiActive();
    // Reset the persistent per-cell Stop button for this fresh run.
    const cellStopBtn = document.getElementById(`cell-stop-${cellId}`);
    if (cellStopBtn) { cellStopBtn.disabled = false; cellStopBtn.title = 'Stop this cell'; }

    try {
      const result = await Arima.api('POST', '/shell/execute', {
        sessionId,
        code: cell.source, cellId, mode: cell.mode || 'jshell',
        stdin: ''
      });

      unsubWs();
      clearRunningIndicator(cellId);
      removeStreamPreview(bodyWrap);   // ephemeral live preview → replaced by the full result below
      if (window.Notifications) Notifications.clear(cellId);

      // Remove any lingering input prompt (shouldn't exist if execution finished)
      bodyWrap?.querySelector('.stdin-interactive-prompt')?.remove();

      if (hadInteractiveInput) {
        // Output was streamed incrementally — append only the final chunk
        if (result.output?.trim()) appendInteractiveChunk(bodyWrap, result.output);
        applyResultInteractive(cell, result, div);
      } else {
        applyResult(cell, result, div);
      }

      if (cell.anchor) {
        result.success ? Orchestration.markOk(cell.anchor, result.executionCount)
                       : Orchestration.markError(cell.anchor);
      }
      const RUNAWAY_LABEL = { OUTPUT_LIMIT: 'Stopped · output limit', TIMEOUT: 'Stopped · time limit', STOPPED: 'Stopped' };
      const statusLabel = RUNAWAY_LABEL[result.status] ? RUNAWAY_LABEL[result.status]
                        : result.status === 'COMPILE_ERROR' ? 'Compile error'
                        : result.success ? 'Done' : 'Runtime error';
      Arima.setStatus(`[${modeLabel}] ${statusLabel} — ${result.executionTimeMs}ms`);
      document.getElementById('kernel-info').textContent =
        result.success ? `[${result.executionCount}] OK` : `[${result.executionCount}] Error`;
    } catch(e) {
      unsubWs();
      clearRunningIndicator(cellId);
      removeStreamPreview(bodyWrap);
      if (window.Notifications) Notifications.clear(cellId);
      bodyWrap?.querySelector('.stdin-interactive-prompt')?.remove();
      div?.classList.remove('running');
      div?.classList.add('has-error');
      updateTimingBadge(cellId, 'error', null);
      if (cell.anchor) Orchestration.markError(cell.anchor);
      const errMsg = e.message || 'Unknown error';
      Arima.setStatus('Error: ' + errMsg);
      ErrorLog.add(cell.anchor || cellId, errMsg, null);
    } finally {
      _runningCellId = null;
      _runUiIdleIfDone();
    }
  }

  // Get (or lazily create) the single terminal container for this cell's interactive session.
  // All output chunks, input prompts and echoes live inside it so they appear in sequence.
  function getOrCreateTerminal(bodyWrap) {
    let t = bodyWrap.querySelector('.cell-terminal');
    if (!t) {
      t = document.createElement('div');
      t.className = 'cell-terminal';
      bodyWrap.appendChild(t);
    }
    return t;
  }

  // Live streaming preview — an ephemeral <pre> that fills with output while a cell runs,
  // then is removed and replaced by the authoritative full result. Keeps only a bounded tail
  // so a runaway printer can't grow the DOM without limit.
  const STREAM_PREVIEW_MAX = 20000;
  function appendStreamPreview(bodyWrap, text) {
    if (!bodyWrap || !text) return;
    let pre = bodyWrap.querySelector('.cell-stream-preview');
    if (!pre) {
      pre = document.createElement('pre');
      pre.className = 'cell-stream-preview';
      bodyWrap.appendChild(pre);
    }
    let next = pre.textContent + text;
    if (next.length > STREAM_PREVIEW_MAX) next = '… (streaming — earlier output trimmed)\n' + next.slice(-STREAM_PREVIEW_MAX);
    pre.textContent = next;
    pre.scrollTop = pre.scrollHeight;
  }
  function removeStreamPreview(bodyWrap) {
    bodyWrap?.querySelector('.cell-stream-preview')?.remove();
  }

  // Append a chunk of program output (printed before / between stdin prompts)
  function appendInteractiveChunk(bodyWrap, text) {
    if (!bodyWrap || !text) return;
    const terminal = getOrCreateTerminal(bodyWrap);
    const pre = document.createElement('pre');
    pre.className = 'cell-interactive-chunk';
    pre.textContent = text;
    terminal.appendChild(pre);
    terminal.scrollIntoView({ block: 'nearest' });
  }

  // Show the live terminal-style input prompt inside the terminal container
  function showInteractivePrompt(bodyWrap, sessionId, cellId) {
    if (!bodyWrap) return;
    const terminal = getOrCreateTerminal(bodyWrap);
    terminal.querySelector('.stdin-interactive-prompt')?.remove(); // deduplicate

    const row = document.createElement('div');
    row.className = 'stdin-interactive-prompt';
    row.innerHTML =
      `<span class="sip-caret">▶</span>` +
      `<input class="sip-input" type="text" autocomplete="off" spellcheck="false">`;
    terminal.appendChild(row);
    terminal.scrollIntoView({ block: 'nearest' });

    const input = row.querySelector('.sip-input');
    input.focus();

    input.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      const line = input.value;

      // Replace the prompt with an echo of what the user typed
      const echo = document.createElement('div');
      echo.className = 'stdin-interactive-echo';
      echo.textContent = line;
      row.replaceWith(echo);

      // Send the line to the server → unblocks ArimaInput.take()
      Arima.sendToShell(sessionId, 'input', { line });
      if (window.Notifications && cellId) Notifications.clear(cellId);
    });
  }

  // Apply execution result when interactive stdin was used.
  // Unlike applyResult(), does NOT replace existing output — only appends error
  // (if any) and the execution footer, since output was already streamed in chunks.
  function applyResultInteractive(cell, result, div) {
    cell.output         = result.output || '';
    cell.error          = result.error  || '';
    cell.returnValue    = result.returnValue;
    cell.executed       = true;
    cell.executionCount = result.executionCount;

    const cnt = document.getElementById(`cnt-${cell.id}`);
    if (cnt) cnt.textContent = `[${result.executionCount}]`;

    const ms = result.executionTimeMs;
    updateTimingBadge(cell.id, result.success ? 'ok' : 'error', ms > 0 ? ms : null);

    const bodyWrap = document.getElementById(`body-${cell.id}`);

    // Show error if any (appended below the streamed output)
    if (result.error?.trim()) {
      const errSec = document.createElement('div');
      errSec.className = 'cell-output-section cell-interactive-error';
      errSec.innerHTML =
        `<div class="cout-label err-label">Error</div>` +
        `<pre class="cell-stderr">${result.error.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')}</pre>`;
      bodyWrap?.appendChild(errSec);
    }

    if (bodyWrap) renderDepBadges(cell, bodyWrap);

    // Execution footer
    if (bodyWrap) {
      bodyWrap.querySelectorAll('.cell-exec-footer').forEach(e => e.remove());
      const ok  = result.success;
      const dur = formatDuration(result.executionTimeMs);
      const now = new Date();
      // Persist the timestamp in the cell model so it survives save/reload
      cell.lastExecutedAt    = now.toISOString();
      cell.lastExecutionTimeMs = result.executionTimeMs || 0;
      const ts  = _fmtDateTime(cell.lastExecutedAt);
      const STOP_LABEL = { STOPPED: 'Stopped', OUTPUT_LIMIT: 'Stopped · output limit', TIMEOUT: 'Stopped · time limit' };
      const runLabel = STOP_LABEL[result.status] ? STOP_LABEL[result.status] : (ok ? 'Completed' : 'Failed');
      const footer = document.createElement('div');
      footer.className = `cell-exec-footer ${ok ? 'ok' : 'err'}`;
      footer.innerHTML =
        `<span class="cef-icon">${ok ? '✓' : '✗'}</span>` +
        `<span class="cef-label">${runLabel}</span>` +
        `<span class="cef-time">${dur}</span>` +
        `<span class="cef-sep">·</span>` +
        `<span class="cef-run">Run #${result.executionCount}</span>` +
        `<span class="cef-sep">·</span>` +
        `<span class="cef-ts">${ts}</span>`;
      bodyWrap.appendChild(footer);
    }

    if (div) {
      div.classList.remove('running');
      if (result.success) {
        div.classList.remove('has-error');
        div.classList.add('success-flash');
        setTimeout(() => div?.classList.remove('success-flash'), 1500);
      } else {
        div.classList.add('has-error');
      }
    }
    setTimeout(() => { scrollCellToTop(cell.id); flashOutput(cell.id); }, 60);

    // Surface variables via the persistent tab (interactive run path).
    const locals  = result.localVariables  || [];
    const globals = result.globalVariables || [];
    if ((locals.length || globals.length) && window.VarInspector) {
      VarInspector.update({
        cellId: cell.id,
        cellAnchor: cell.anchor,
        locals, globals,
      });
    }

    save().catch(() => Arima.markDirty(true));
  }

  /* ── Run with declared dependencies ──────────────── */
  async function runWithDeps(cellId, forceRun = false) {
    if (!notebook) return;
    const cell = notebook.cells.find(c => c.id === cellId);
    if (!cell || !cell.anchor) { await executeCell(cellId); return; }

    Arima.setStatus(forceRun ? 'Clean run — re-executing all dependencies…' : 'Resolving dependencies…');
    const div = document.getElementById(`cell-${cellId}`);
    div?.classList.add('running');
    setRunningIndicator(cellId, cell.source || '');
    updateTimingBadge(cellId, 'running');
    try {
      await save(); // save current state before orchestration
      const resp = await Arima.api('POST', '/shell/execute-with-deps', {
        notebookId: notebook.id,
        cellId,
        sessionId: Arima.state.currentSessionId,
        forceRun: forceRun ? 'true' : 'false'
      });
      div?.classList.remove('running');
      clearRunningIndicator(cellId);
      await handlePipelineResponse(resp, cellId);
    } catch(e) {
      clearRunningIndicator(cellId);
      div?.classList.remove('running');
      div?.classList.add('has-error');
      updateTimingBadge(cellId, 'err-net', null);
      if (cell.anchor) Orchestration.markError(cell.anchor);
      const errMsg = e.message || 'Dependency run failed';
      Arima.setStatus('Dependency run failed: ' + errMsg);
      ErrorLog.add(cell.anchor || cellId, 'Dependency run failed: ' + errMsg, null);
    }
  }

  /* ── Run pipeline cell ────────────────────────────── */
  async function runPipeline(cellId, forceRun = false) {
    if (!notebook) return;
    Arima.setStatus(forceRun ? 'Clean run pipeline — re-executing all steps…' : 'Running pipeline…');
    const pipelineCell = notebook.cells.find(c => c.id === cellId);
    const div = document.getElementById(`cell-${cellId}`);
    div?.classList.add('running');
    if (pipelineCell) setRunningIndicator(cellId, pipelineCell.source || '');
    updateTimingBadge(cellId, 'running');
    try {
      await save();
      const resp = await Arima.api('POST', '/shell/execute-pipeline', {
        notebookId: notebook.id,
        pipelineCellId: cellId,
        sessionId: Arima.state.currentSessionId,
        forceRun: forceRun ? 'true' : 'false'
      });
      div?.classList.remove('running');
      clearRunningIndicator(cellId);
      await handlePipelineResponse(resp, cellId);
      // Update pipeline cell timing based on overall success
      if (resp?.success) {
        const totalMs = (resp.results || []).reduce((s, r) => s + (r.executionTimeMs || 0), 0);
        updateTimingBadge(cellId, 'ok', totalMs > 0 ? totalMs : null);
      } else {
        updateTimingBadge(cellId, 'error', null);
      }
    } catch(e) {
      clearRunningIndicator(cellId);
      div?.classList.remove('running');
      div?.classList.add('has-error');
      updateTimingBadge(cellId, 'err-net', null);
      if (pipelineCell?.anchor) Orchestration.markError(pipelineCell.anchor);
      const errMsg = e.message || 'Pipeline failed';
      Arima.setStatus('Pipeline failed: ' + errMsg);
      ErrorLog.add(pipelineCell?.anchor || cellId, 'Pipeline failed: ' + errMsg, null);
    }
  }

  /* ── Run to here ──────────────────────────────────── */
  async function runToHere(cellId) {
    if (!notebook) return;
    Arima.setStatus('Running to here…');
    document.getElementById('kernel-info').textContent = 'Running…';
    // Collect cells from top up to and including target
    const cells = [];
    for (const cell of notebook.cells) {
      cells.push(cell);
      if (cell.id === cellId) break;
    }
    const stats = await runAllSequential(cells, { delay: 2500 });
    Arima.setStatus(stats.aborted
      ? `Stopped — ${stats.ok} OK, ${stats.errors} error(s) before stop`
      : `Run to here — ${stats.ok} OK, ${stats.errors} error(s), ${(stats.totalMs/1000).toFixed(2)}s`);
    showNotebookStats(stats);
  }

  /* ── Handle pipeline/orchestration response ───────── */
  async function handlePipelineResponse(resp, pipelineCellId) {
    if (!resp) return;
    const results = resp.results || [];

    // Gather cross-notebook results to animate before applying local results
    const crossResults = results.filter(r => r.notebookId && r.notebookId !== notebook?.id);

    // Show cross-notebook popups one at a time (sequential, timed)
    if (crossResults.length > 0) {
      const uniqueNbs = [...new Set(crossResults.map(r => r.notebookId))];
      for (const nbId of uniqueNbs) {
        loadNotebookBackground(nbId);
      }
      await showCrossNotebookPopups(crossResults);
    }

    // Apply each result to the corresponding cell
    results.forEach(result => {
      const cell = notebook.cells.find(c => c.id === result.cellId);
      if (cell) {
        const div = document.getElementById(`cell-${result.cellId}`);
        applyResult(cell, result, div);
        if (cell.anchor) {
          result.success ? Orchestration.markOk(cell.anchor, result.executionCount)
                         : Orchestration.markError(cell.anchor);
        }
      }
    });

    // Persist pipeline execution state so it survives close/reopen
    if (pipelineCellId) {
      const pipelineCell = notebook.cells.find(c => c.id === pipelineCellId);
      if (pipelineCell) {
        const now = new Date();
        pipelineCell.executed        = true;
        pipelineCell.lastExecutedAt  = now.toISOString();
        // Store a plain-text summary of step results in cell.output for reload display
        pipelineCell.output = _buildPipelineOutputSummary(results, resp);
        pipelineCell.error  = resp.success ? '' : (resp.error || 'Pipeline failed');
        pipelineCell.lastExecutionTimeMs =
          results.reduce((s, r) => s + (r.executionTimeMs || 0), 0);
      }
      showPipelineAggregatedOutput(pipelineCellId, results, resp);
    }

    // Log any failed cells to the error log
    const failed = results.filter(r => !r.success && (r.error || r.output));
    failed.forEach(r => {
      const failedCell = notebook.cells.find(c => c.id === r.cellId);
      const label = failedCell?.anchor || r.cellId || 'cell';
      const errText = r.error || r.output || 'Execution failed';
      ErrorLog.add(label, errText.substring(0, 300), null);
    });

    if (resp.success) {
      const freshCount  = results.filter(r => !r.cached).length;
      const cachedCount = results.filter(r => r.cached).length;
      const summary = cachedCount > 0
        ? `${freshCount} run, ${cachedCount} from cache`
        : `${results.length} cell(s) executed`;
      Arima.setStatus(`Pipeline complete — ${summary}`);
      document.getElementById('kernel-info').textContent = `Pipeline OK (${results.length})`;
    } else {
      const errMsg = resp.error || 'unknown error';
      Arima.setStatus(`Pipeline stopped: ${errMsg}`);
      document.getElementById('kernel-info').textContent = 'Pipeline error';
      ErrorLog.add('pipeline', errMsg, null);
    }

    // Refresh pipeline step displays
    notebook.cells.filter(c => c.type === 'PIPELINE').forEach(c => {
      renderPipelineSteps(c, document.getElementById(`cell-${c.id}`));
    });

    // Save all cell state (output + timestamps) immediately
    save().catch(() => Arima.markDirty(true));
  }

  /** Build a plain-text summary of pipeline results for storage in cell.output */
  function _buildPipelineOutputSummary(results, resp) {
    const lines = [];
    results.forEach(r => {
      const localCell = notebook.cells.find(c => c.id === r.cellId);
      const anchor = localCell?.anchor || r.cellId || '?';
      const nbId   = r.notebookId && r.notebookId !== notebook?.id ? r.notebookId : null;
      const label  = nbId ? `[${nbId}] #${anchor}` : `#${anchor}`;
      const status = r.cached ? 'cached' : (r.success ? 'ok' : 'failed');
      lines.push(`${label} [${status}]`);
      if (r.output?.trim()) lines.push(r.output.trim());
      if (r.error?.trim())  lines.push('ERROR: ' + r.error.trim());
    });
    if (!resp.success && resp.error) lines.push('Pipeline stopped: ' + resp.error);
    return lines.join('\n');
  }

  /* ── Show aggregated output inside pipeline cell ─── */
  function showPipelineAggregatedOutput(pipelineCellId, results, resp) {
    const stepsDiv = document.getElementById(`pipeline-steps-${pipelineCellId}`);
    if (!stepsDiv) return;

    // Remove any previous aggregated output block
    stepsDiv.querySelectorAll('.pipeline-agg-output').forEach(e => e.remove());

    // Build output entries for results that have something to show
    const cachedCount = results.filter(r => r.cached).length;
    const entries = results.map(r => {
      const localCell = notebook.cells.find(c => c.id === r.cellId);
      const anchor = localCell?.anchor || r.cellId || '?';
      const nbId   = r.notebookId && r.notebookId !== notebook?.id ? r.notebookId : null;
      const label  = nbId ? `[${nbId}] ${anchor}` : anchor;
      const text   = (r.output || '').trim();
      const err    = (r.error  || '').trim();
      return { label, text, err, ok: r.success, cached: !!r.cached };
    }).filter(e => e.text || e.err);

    if (entries.length === 0 && resp.success) return; // nothing to show

    const block = document.createElement('div');
    block.className = 'pipeline-agg-output';

    const header = document.createElement('div');
    header.className = 'pipeline-agg-header';
    const cachedNote = cachedCount > 0 ? ` (${cachedCount} cached)` : '';
    header.textContent = resp.success
      ? `Pipeline output — ${results.length} step${results.length !== 1 ? 's' : ''}${cachedNote}`
      : `Pipeline stopped — ${resp.error || 'error'}`;
    block.appendChild(header);

    if (entries.length === 0) {
      const none = document.createElement('div');
      none.className = 'pipeline-agg-none';
      none.textContent = 'No output produced.';
      block.appendChild(none);
    } else {
      entries.forEach(e => {
        const row = document.createElement('div');
        row.className = `pipeline-agg-row ${e.ok ? '' : 'has-error'}`;

        const lbl = document.createElement('span');
        lbl.className = 'pipeline-agg-label';
        lbl.textContent = '#' + e.label;
        if (e.cached) {
          const badge = document.createElement('span');
          badge.className = 'pipeline-agg-cached-badge';
          badge.textContent = 'cached';
          lbl.appendChild(badge);
        }
        row.appendChild(lbl);

        if (e.text) {
          const pre = document.createElement('pre');
          pre.className = 'pipeline-agg-pre';
          pre.textContent = e.text;
          row.appendChild(pre);
        }
        if (e.err) {
          const pre = document.createElement('pre');
          pre.className = 'pipeline-agg-pre pipeline-agg-err';
          pre.textContent = e.err;
          row.appendChild(pre);
        }
        block.appendChild(row);
      });
    }

    stepsDiv.appendChild(block);
    block.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  /* ── Cross-notebook execution popups ─────────────── */
  // Shows a small non-blocking popup card for each cross-notebook step.
  // Cards appear in sequence with a short pause, then auto-dismiss.
  async function showCrossNotebookPopups(crossResults) {
    // Group by notebook so we show at most one card per notebook
    const byNb = new Map();
    crossResults.forEach(r => {
      if (!byNb.has(r.notebookId)) byNb.set(r.notebookId, []);
      byNb.get(r.notebookId).push(r);
    });

    let container = document.getElementById('cross-nb-popup-container');
    if (!container) {
      container = document.createElement('div');
      container.id = 'cross-nb-popup-container';
      document.body.appendChild(container);
    }

    for (const [nbId, nbResults] of byNb) {
      await _showCrossNbCard(container, nbId, nbResults);
      // Pause between cards so they don't all pile up at once
      if (byNb.size > 1) await _sleep(700);
    }
  }

  function _showCrossNbCard(container, nbId, results) {
    return new Promise(resolve => {
      const nbObj = tabStore.get(nbId);
      const nbName = nbObj?.name || nbId;
      const anchors = results.map(r => {
        const anchor = r.cellId || '?';
        const ok = r.success;
        return `<span class="cross-nb-anchor ${ok ? 'ok' : 'err'}">${escapeHtml(anchor)}</span>`;
      }).join('');

      const card = document.createElement('div');
      card.className = 'cross-nb-popup';
      card.innerHTML =
        `<div class="cross-nb-popup-icon">◈</div>` +
        `<div class="cross-nb-popup-body">` +
          `<div class="cross-nb-popup-title">${escapeHtml(nbName)}</div>` +
          `<div class="cross-nb-popup-anchors">${anchors}</div>` +
        `</div>` +
        `<button class="cross-nb-popup-close" title="Dismiss">✕</button>`;

      container.appendChild(card);

      // Animate in
      requestAnimationFrame(() => card.classList.add('visible'));

      const dismiss = () => {
        card.classList.remove('visible');
        card.classList.add('dismissing');
        setTimeout(() => { card.remove(); resolve(); }, 350);
      };

      card.querySelector('.cross-nb-popup-close').addEventListener('click', dismiss);
      // Auto-dismiss after 3 s
      setTimeout(dismiss, 3000);
    });
  }

  function _sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

  /* ── Apply execution result to cell & DOM ─────────── */
  function applyResult(cell, result, div) {
    cell.output         = result.output || '';
    cell.error          = result.error  || '';
    cell.returnValue    = result.returnValue;
    cell.executed       = true;
    cell.executionCount = result.executionCount;
    const _now = new Date();
    cell.lastExecutedAt      = _now.toISOString();
    cell.lastExecutionTimeMs = result.executionTimeMs || 0;

    const cnt = document.getElementById(`cnt-${cell.id}`);
    if (cnt) cnt.textContent = `[${result.executionCount}]`;

    // Always update timing badge regardless of execution path
    const ms = result.executionTimeMs;
    updateTimingBadge(cell.id, result.success ? 'ok' : 'error',
      (ms != null && ms > 0) ? ms : null);

    const bodyWrap = document.getElementById(`body-${cell.id}`);
    if (bodyWrap) showOutput(cell.id, result, bodyWrap);
    if (bodyWrap) renderDepBadges(cell, bodyWrap);

    // ── Prominent cell execution footer ──
    if (bodyWrap) {
      bodyWrap.querySelectorAll('.cell-exec-footer').forEach(e => e.remove());
      const ok  = result.success;
      const dur = formatDuration(result.executionTimeMs);
      const ts  = _fmtDateTime(cell.lastExecutedAt);
      const STOP_LABEL = { STOPPED: 'Stopped', OUTPUT_LIMIT: 'Stopped · output limit', TIMEOUT: 'Stopped · time limit' };
      const runLabel = STOP_LABEL[result.status] ? STOP_LABEL[result.status]
                     : result.status === 'COMPILE_ERROR' ? 'Compile Error'
                     : ok ? 'Completed' : 'Failed';
      const footer = document.createElement('div');
      footer.className = `cell-exec-footer ${ok ? 'ok' : 'err'}`;
      footer.innerHTML =
        `<span class="cef-icon">${ok ? '✓' : '✗'}</span>` +
        `<span class="cef-label">${runLabel}</span>` +
        `<span class="cef-time">${dur}</span>` +
        `<span class="cef-sep">·</span>` +
        `<span class="cef-run">Run #${result.executionCount}</span>` +
        `<span class="cef-sep">·</span>` +
        `<span class="cef-ts">${ts}</span>`;
      bodyWrap.appendChild(footer);
    }

    if (div) {
      div.classList.remove('running');
      if (result.success) {
        div.classList.remove('has-error');
        div.classList.add('success-flash');
        setTimeout(() => div?.classList.remove('success-flash'), 1500);
      } else {
        div.classList.add('has-error');
      }
    }

    // Scroll cell to top and pulse the output to draw the user's eye
    setTimeout(() => {
      scrollCellToTop(cell.id);
      flashOutput(cell.id);
    }, 60);

    // Surface the runtime variables. We don't auto-open the panel — we just
    // pop the persistent "Variables" tab on the right edge, labeled with this
    // cell. The user clicks the tab to slide the panel out. Clicking the cell
    // label inside the panel scrolls back to & focuses this cell.
    const locals  = result.localVariables  || [];
    const globals = result.globalVariables || [];
    if ((locals.length || globals.length) && window.VarInspector) {
      VarInspector.update({
        cellId: cell.id,
        cellAnchor: cell.anchor,
        locals, globals,
      });
    }

    // Save immediately so output persists if the user closes the notebook
    save().catch(() => Arima.markDirty(true));
  }

  function clearOutput(cellId) {
    const bodyWrap = document.getElementById(`body-${cellId}`);
    if (!bodyWrap) return;
    bodyWrap.querySelectorAll('.cell-output-section, .cell-exec-footer').forEach(el => el.remove());
    updateTimingBadge(cellId, 'clear');
  }

  function showOutput(cellId, result, container) {
    if (!container) container = document.getElementById(`body-${cellId}`);
    if (!container) return;
    container.querySelectorAll('.cell-output-section').forEach(el => el.remove());

    const hasOutput = (result.output && result.output.trim())
                   || (result.returnValue && result.returnValue !== 'null' && result.returnValue)
                   || (result.error && result.error.trim());
    if (!hasOutput) return;

    const section = document.createElement('div');
    section.className = 'cell-output-section';

    // Header bar
    const hdr = document.createElement('div');
    hdr.className = 'cell-output-header ' + (result.success ? 'ok' : 'err');
    hdr.innerHTML = result.success
      ? `<span class="coh-dot ok"></span><span class="coh-label">Output</span>`
      : `<span class="coh-dot err"></span><span class="coh-label">Error</span>`;
    if (result.executionTimeMs != null) {
      const badge = document.createElement('span');
      badge.className = 'coh-time';
      badge.textContent = formatDuration(result.executionTimeMs);
      hdr.appendChild(badge);
    }
    section.appendChild(hdr);

    if (result.output && result.output.trim()) {
      const el = document.createElement('div');
      el.className = 'cell-output stdout';
      // Split output into lines — detect BARISTA_IMG / BARISTA_HTML sentinels
      let lines = result.output.split('\n');
      // Guard the DOM against a huge (e.g. runaway) output — render only the last slice.
      const MAX_RENDER_LINES = 5000;
      if (lines.length > MAX_RENDER_LINES) {
        const total = lines.length;
        lines = lines.slice(-MAX_RENDER_LINES);
        const note = document.createElement('div');
        note.className = 'cell-output-trim-note';
        note.textContent = `⚠ Output too large — showing the last ${MAX_RENDER_LINES.toLocaleString()} of ${total.toLocaleString()} lines.`;
        el.appendChild(note);
      }
      let textBuf = [];
      const flushText = () => {
        const t = textBuf.join('\n').trimEnd();
        if (t) { const span = document.createElement('pre'); span.textContent = t; el.appendChild(span); }
        textBuf = [];
      };
      lines.forEach(line => {
        if (line.startsWith('BARISTA_IMG:')) {
          flushText();
          const img = document.createElement('img');
          img.src = line.slice(10);
          img.className = 'barista-chart-img';
          img.alt = 'Chart output';
          el.appendChild(img);
        } else if (line.startsWith('BARISTA_HTML:')) {
          flushText();
          const wrap = document.createElement('div');
          wrap.className = 'barista-html-output';
          wrap.innerHTML = line.slice(11);   // already sanitised server-side
          el.appendChild(wrap);
        } else {
          textBuf.push(line);
        }
      });
      flushText();
      section.appendChild(el);
    }
    if (result.returnValue && result.returnValue !== 'null' && result.returnValue) {
      const el = document.createElement('div');
      el.className = 'cell-output retval';
      el.textContent = result.returnValue;
      section.appendChild(el);
    }
    if (result.error && result.error.trim()) {
      const el = document.createElement('div');
      el.className = 'cell-output error';
      if (result.status === 'COMPILE_ERROR') {
        el.classList.add('compile-error');
        el.innerHTML = '<strong>Compile error:</strong><pre>' + escapeHtml(result.error) + '</pre>';
      } else {
        el.textContent = result.error;
      }
      section.appendChild(el);
    }

    container.appendChild(section);
  }

  function escapeHtml(text) {
    if (!text) return '';
    return String(text).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  /* ── Run all cells sequentially ──────────────────── */
  async function runAll() {
    if (!notebook) return;
    Arima.setStatus('Running all cells…');
    const stats = await runAllSequential(notebook.cells, { delay: 2500 });
    Arima.setStatus(stats.aborted
      ? `Stopped — ${stats.ok} OK, ${stats.errors} error(s) before stop`
      : `Done — ${stats.ok} OK, ${stats.errors} error(s), ${(stats.totalMs/1000).toFixed(2)}s`);
    showNotebookStats(stats);
  }

  /* ── Save ─────────────────────────────────────────── */
  async function save() {
    if (!notebook) return;
    syncSources();
    syncAnnotations();
    try {
      await Arima.api('PUT', `/notebooks/${notebook.id}`, notebook);
      Arima.markDirty(false);
      Arima.setStatus(`Saved: ${notebook.name}`);
    } catch(e) { Arima.setStatus('Save failed: ' + e.message); }
  }

  function syncSources() {
    notebook?.cells.forEach(cell => {
      const cm = editors[cell.id];
      if (cm) cell.source = cm.getValue();
    });
  }

  /* ── Add cells ────────────────────────────────────── */
  function addCell(type) {
    if (!notebook) { alert('Open a notebook first.'); return; }
    let source = '';
    if (type === 'PIPELINE') {
      source = '//@ pipeline: my-pipeline\n//@ steps: anchor1, anchor2\n//@ description: My pipeline';
    }
    const cell = {
      id: `cell-${Date.now()}`, type: type || 'CODE', mode: 'jshell',
      source, output: '', error: '', executed: false,
      anchor: null, dependsOn: [], pipelineSteps: []
    };
    notebook.cells.push(cell);
    const container = document.getElementById('cells-container');
    container.querySelector('.empty-state')?.remove();
    renderCell(cell, notebook.cells.length - 1, container);
    document.getElementById(`cell-${cell.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    Arima.markDirty(true);

    // Open pipeline editor immediately so user can start editing
    if (type === 'PIPELINE') {
      setTimeout(() => {
        const cellDiv = document.getElementById(`cell-${cell.id}`);
        if (cellDiv) togglePipelineEditor(cell, cellDiv, true);
      }, 100);
    }
  }

  function addCellWithSource(type, source) {
    if (!notebook) return;
    const cell = {
      id: `cell-${Date.now()}`, type: type || 'CODE', mode: 'jshell',
      source: source || '', output: '', error: '', executed: false,
      anchor: null, dependsOn: [], pipelineSteps: []
    };
    notebook.cells.push(cell);
    const container = document.getElementById('cells-container');
    container.querySelector('.empty-state')?.remove();
    renderCell(cell, notebook.cells.length - 1, container);
    document.getElementById(`cell-${cell.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    Arima.markDirty(true);
  }

  /* ── Cell operations ──────────────────────────────── */
  function deleteCell(cellId) {
    if (!notebook) return;
    syncSources();
    notebook.cells = notebook.cells.filter(c => c.id !== cellId);
    delete editors[cellId];
    render(); Arima.markDirty(true);
  }

  function moveUp(cellId) {
    if (!notebook) return;
    syncSources();
    const idx = notebook.cells.findIndex(c => c.id === cellId);
    if (idx <= 0) return;
    [notebook.cells[idx-1], notebook.cells[idx]] = [notebook.cells[idx], notebook.cells[idx-1]];
    render(); Arima.markDirty(true);
  }

  function moveDown(cellId) {
    if (!notebook) return;
    syncSources();
    const idx = notebook.cells.findIndex(c => c.id === cellId);
    if (idx < 0 || idx >= notebook.cells.length - 1) return;
    [notebook.cells[idx], notebook.cells[idx+1]] = [notebook.cells[idx+1], notebook.cells[idx]];
    render(); Arima.markDirty(true);
  }

  function clearOutputs() {
    notebook?.cells.forEach(c => {
      c.output = ''; c.error = ''; c.returnValue = null; c.executed = false;
    });
    render();
  }

  /* ── Auto-save ────────────────────────────────────── */
  function setupAutoSave() {
    clearInterval(autoSave);
    const interval = (Arima.state.settings?.autoSaveIntervalSecs || 30) * 1000;
    if (interval > 0) {
      autoSave = setInterval(() => {
        if (document.getElementById('dirty-dot')?.classList.contains('visible')) save();
      }, interval);
    }
  }

  /* ── Insert code from AI ──────────────────────────── */
  function insertCodeFromAI(code) {
    if (!notebook) { alert('Open a notebook first.'); return; }
    const cell = {
      id: `cell-${Date.now()}`, type: 'CODE', mode: 'jshell',
      source: code, output: '', error: '', executed: false,
      anchor: null, dependsOn: [], pipelineSteps: []
    };
    notebook.cells.push(cell);
    const container = document.getElementById('cells-container');
    container.querySelector('.empty-state')?.remove();
    renderCell(cell, notebook.cells.length - 1, container);
    document.getElementById(`cell-${cell.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    Arima.markDirty(true);
    Arima.setStatus('Code inserted from AI');
  }

  /* ── Toolbar bindings ─────────────────────────────── */
  function bindToolbar() {
    document.getElementById('btn-new')?.addEventListener('click', async () => {
      const name = prompt('Notebook name:', 'Untitled Notebook');
      if (!name) return;
      const nb = await Arima.api('POST', '/notebooks', { name });
      await refreshList(); loadNotebook(nb.id);
    });
    document.getElementById('btn-create-first')?.addEventListener('click', async () => {
      const name = prompt('Notebook name:', 'My Notebook');
      if (!name) return;
      const nb = await Arima.api('POST', '/notebooks', { name });
      await refreshList(); loadNotebook(nb.id);
    });
    document.getElementById('btn-save')?.addEventListener('click', save);
    document.getElementById('btn-run-all')?.addEventListener('click', runAll);
    document.getElementById('btn-stop-run')?.addEventListener('click', stopRun);
    document.getElementById('btn-add-code')?.addEventListener('click', () => addCell('CODE'));
    document.getElementById('btn-add-md')?.addEventListener('click', () => addCell('MARKDOWN'));
    document.getElementById('btn-paste-cell')?.addEventListener('click', async () => {
      if (!notebook) { alert('Open a notebook first.'); return; }
      try {
        const text = await navigator.clipboard.readText();
        if (!text?.trim()) { Arima.setStatus('Clipboard is empty'); return; }
        addCellWithSource('CODE', text.trim());
        Arima.setStatus('Cell pasted from clipboard');
      } catch(e) { Arima.setStatus('Clipboard read failed — check browser permissions'); }
    });
    document.getElementById('btn-add-pipeline')?.addEventListener('click', () => addCell('PIPELINE'));
    document.getElementById('btn-clear-out')?.addEventListener('click', clearOutputs);
    document.getElementById('btn-restart')?.addEventListener('click', async () => {
      if (!Arima.state.currentSessionId) return;
      if (!confirm('Restart the JShell kernel? All variables will be lost.')) return;
      // Abort any in-flight run and interrupt the current cell before the kernel resets,
      // so a Run All in progress does not keep firing cells at a restarting session.
      stopRun();
      await Arima.api('POST', `/shell/${Arima.state.currentSessionId}/restart`);
      // Reset all dep statuses to pending
      Object.keys(depStatus || {}).forEach(k => delete depStatus[k]);
      clearOutputs();
      Arima.setStatus('Kernel restarted — run cells again to restore state');
    });
    // Validate dep graph
    document.getElementById('btn-validate-graph')?.addEventListener('click', async () => {
      if (!notebook) return;
      await save();
      try {
        const r = await Arima.api('GET', `/shell/validate-graph/${notebook.id}`);
        if (r.valid) {
          Arima.setStatus('✓ Dependency graph is valid — no cycles or missing anchors');
        } else {
          alert('Dependency graph issues:\n\n' + r.errors.join('\n'));
          Arima.setStatus('⚠ Graph has ' + r.errors.length + ' issue(s)');
        }
      } catch(e) { Arima.setStatus('Validation failed: ' + e.message); }
    });

    document.getElementById('btn-delete-notebook')?.addEventListener('click', async () => {
      if (!notebook) return;
      if (notebook._readOnly) return; // tutorials cannot be deleted
      if (!confirm(`Delete "${notebook.name}"?\n\nThis cannot be undone.`)) return;
      await deleteNotebook(notebook.id);
    });
  }

  /* ── Step Navigator ───────────────────────────────── */
  let stepIdx = -1;

  function stepStart() {
    if (!notebook || notebook.cells.length === 0) return;
    stepIdx = 0;
    document.getElementById('step-nav').style.display = '';
    document.getElementById('btn-step-mode')?.classList.add('active');
    _stepGoto(0);
  }

  function stepClose() {
    document.getElementById('step-nav').style.display = 'none';
    document.getElementById('btn-step-mode')?.classList.remove('active');
    document.querySelectorAll('.cell.step-focus').forEach(el => el.classList.remove('step-focus'));
    stepIdx = -1;
  }

  async function stepAction() {
    if (!notebook || stepIdx < 0) return;
    const cell = notebook.cells[stepIdx];
    if (!cell) { _stepGoto(-1); return; }

    if (cell.type === 'CODE') {
      await executeCell(cell.id);
    } else if (cell.type === 'PIPELINE') {
      await runPipeline(cell.id);
    }
    // Markdown: just advance (no execution)
    _stepGoto(stepIdx + 1);
  }

  function stepPrev() {
    if (stepIdx > 0) _stepGoto(stepIdx - 1);
  }

  function _stepGoto(idx) {
    if (!notebook) return;
    // Remove focus from all cells
    document.querySelectorAll('.cell.step-focus').forEach(el => el.classList.remove('step-focus'));

    if (idx < 0 || idx >= notebook.cells.length) {
      // End reached
      stepIdx = -1;
      _updateStepUI(null);
      return;
    }
    stepIdx = idx;
    const cell = notebook.cells[idx];
    const div = document.getElementById(`cell-${cell.id}`);
    div?.classList.add('step-focus');
    scrollCellToTop(cell.id);
    _updateStepUI(cell);
  }

  function _updateStepUI(cell) {
    const numEl    = document.getElementById('step-num');
    const typeEl   = document.getElementById('step-type');
    const actionBtn = document.getElementById('step-action-btn');
    const prevBtn   = document.getElementById('step-prev-btn');
    if (!actionBtn) return;

    if (!cell) {
      if (numEl)  numEl.textContent  = '✓ Done';
      if (typeEl) typeEl.textContent = 'All cells executed';
      actionBtn.textContent = '↩ Restart';
      actionBtn.className   = 'step-btn step-action step-restart';
      actionBtn.onclick     = () => _stepGoto(0);
      return;
    }

    const total = notebook?.cells.length || 0;
    const pos   = stepIdx + 1;
    if (numEl)  numEl.textContent  = `${pos} / ${total}`;

    let typeLabel, actionLabel, actionClass;
    if (cell.type === 'MARKDOWN') {
      typeLabel   = '✎ Markdown';
      actionLabel = 'Next →';
      actionClass = 'step-btn step-action step-next';
    } else if (cell.type === 'PIPELINE') {
      typeLabel   = '⬡ Pipeline' + (cell.anchor ? ` · ${cell.anchor}` : '');
      actionLabel = '▶ Run Pipeline & Next';
      actionClass = 'step-btn step-action step-run';
    } else {
      const mode  = cell.mode === 'java' ? 'Java' : 'JShell';
      typeLabel   = `◈ Code · ${mode}` + (cell.anchor ? ` · ${cell.anchor}` : '');
      actionLabel = '▶ Run & Next';
      actionClass = 'step-btn step-action step-run';
    }
    if (typeEl) typeEl.textContent = typeLabel;
    actionBtn.textContent = actionLabel;
    actionBtn.className   = actionClass;
    actionBtn.onclick     = () => NotebookEditor.stepAction();

    // Disable prev at start
    if (prevBtn) prevBtn.disabled = stepIdx === 0;
  }

  /** Return context about the current notebook + focused cell for the AI assistant */
  function getContext() {
    if (!notebook) return null;
    const cellIdx = focusedCellId
      ? notebook.cells.findIndex(c => c.id === focusedCellId)
      : -1;
    const cell = cellIdx >= 0 ? notebook.cells[cellIdx] : null;

    const anchors = notebook.cells
      .filter(c => c.type === 'CODE' && c.anchor)
      .map(c => ({ anchor: c.anchor, mode: c.mode || 'jshell', description: c.description || null }));

    // Summarise all cells so AI understands the notebook structure
    const allCells = notebook.cells.map((c, i) => ({
      index:    i,
      id:       c.id,
      type:     c.type,
      mode:     c.mode || 'jshell',
      anchor:   c.anchor || null,
      description: c.description || null,
      dependsOn: c.dependsOn || [],
      hasOutput: !!(c.output?.trim()),
      hasError:  !!(c.error?.trim()),
      executed:  c.executed || false,
      // Include source only for short cells (<= 3 lines) so context stays concise
      snippet: (c.source || '').split('\n').length <= 3 ? (c.source || '').trim() : null,
    }));

    return {
      notebookName: notebook.name,
      notebookId:   notebook.id,
      notebookDesc: notebook.description || null,
      cellCount:    notebook.cells.length,
      anchors,
      allCells,
      cell: cell ? {
        id:          cell.id,
        index:       cellIdx,
        mode:        cell.mode || 'jshell',
        source:      editors[cell.id]?.getValue() ?? cell.source ?? '',
        output:      cell.output ?? '',
        error:       cell.error ?? '',
        anchor:      cell.anchor ?? null,
        description: cell.description ?? null,
        dependsOn:   cell.dependsOn ?? [],
        executionCount:   cell.executionCount ?? 0,
        lastExecutedAt:   cell.lastExecutedAt ?? null,
        lastExecutionTimeMs: cell.lastExecutionTimeMs ?? null,
      } : null,
    };
  }

  /** Replace the focused cell's source with AI-provided code */
  function applyCodeToCell(cellId, code) {
    const targetId = cellId || focusedCellId;
    if (!targetId) { insertCodeFromAI(code); return null; }
    const cm = editors[targetId];
    if (cm) {
      const prev = cm.getValue();
      cm.setValue(code);
      Arima.markDirty(true);
      Arima.setStatus('Cell updated from AI');
      document.getElementById(`cell-${targetId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return prev;
    }
    return null;
  }

  // ── Language conversion helpers ──────────────────────────────────────

  function _offerConversion(cellId, fromMode, toMode, code, cm) {
    const cellDiv = document.getElementById(`cell-${cellId}`);
    if (!cellDiv) return;
    // Remove any existing banner first
    cellDiv.querySelector('.convert-banner')?.remove();

    const fromLabel = modeLabelFor(fromMode);
    const toLabel   = modeLabelFor(toMode);
    const banner = document.createElement('div');
    banner.className = 'convert-banner';
    banner.innerHTML =
      `<span>Convert code from <strong>${fromLabel}</strong> → <strong>${toLabel}</strong>?</span>` +
      `<button class="btn-cv-yes">Convert</button>` +
      `<button class="btn-cv-no">Keep as-is</button>`;

    // Insert at top of cell body
    const body = cellDiv.querySelector('.cell-body') || cellDiv;
    body.prepend(banner);

    banner.querySelector('.btn-cv-yes')?.addEventListener('click', async () => {
      banner.innerHTML = '<span class="cv-spinner">Converting…</span>';
      try {
        const converted = await _convertCode(fromMode, toMode, code);
        if (converted && cm) cm.setValue(converted);
        banner.remove();
        Arima.setStatus(`Converted to ${toLabel}`);
        Arima.markDirty(true);
      } catch (err) {
        banner.innerHTML =
          `<span style="color:#f38ba8">Conversion failed: ${err.message}</span>` +
          `<button class="btn-cv-no">Dismiss</button>`;
        banner.querySelector('.btn-cv-no')?.addEventListener('click', () => banner.remove());
      }
    });
    banner.querySelector('.btn-cv-no')?.addEventListener('click', () => banner.remove());

    // Auto-dismiss after 15 s if ignored
    setTimeout(() => { if (banner.parentNode) banner.remove(); }, 15000);
  }

  async function _convertCode(fromMode, toMode, code) {
    const fromLabel = modeLabelFor(fromMode);
    const toLabel   = modeLabelFor(toMode);
    const resp = await Arima.api('POST', '/llm/chat', {
      message: `Convert the following ${fromLabel} code to ${toLabel}.\nReturn ONLY the converted code inside a single code block — no explanation.\n\n\`\`\`\n${code}\n\`\`\``,
      systemPrompt: 'You are a precise code converter. Faithfully translate code between languages, preserving all logic and structure. Respond with exactly one fenced code block containing the converted code and nothing else.'
    });
    const text = (resp.response || '').trim();
    // Extract code from ```...``` block
    const match = text.match(/```(?:\w+)?\n?([\s\S]*?)```/);
    return match ? match[1].trim() : text;
  }

  /** Delete a notebook by id — closes its tab if open, then removes from server */
  async function deleteNotebook(id) {
    const nb = tabStore.get(id);
    const name = nb?.name || id;
    await Arima.api('DELETE', `/notebooks/${id}`);
    if (tabStore.has(id)) closeTab(id);
    await refreshList();
    Arima.setStatus(`Deleted: ${name}`);
  }

  /* ── Cross-notebook dependency picker ──────────────── */
  let _crossNbTargetCellId = null;

  function _openCrossNbPicker(cellId) {
    _crossNbTargetCellId = cellId;
    const modal = document.getElementById('cross-nb-picker');
    if (!modal) return;
    const nbSel     = document.getElementById('cross-nb-nb-sel');
    const ancSel    = document.getElementById('cross-nb-anchor-sel');
    const preview   = document.getElementById('cross-nb-preview');
    const insertBtn = document.getElementById('cross-nb-insert-btn');
    nbSel.innerHTML     = '<option value="">Loading notebooks…</option>';
    ancSel.innerHTML    = '<option value="">Select cell anchor…</option>';
    ancSel.disabled     = true;
    preview.textContent = '';
    insertBtn.disabled  = true;
    modal.classList.remove('hidden');
    Arima.api('GET', '/notebooks').then(list => {
      const others = (list || []).filter(nb => nb.id !== notebook?.id);
      nbSel.innerHTML = '<option value="">Select notebook…</option>' +
        others.map(nb => `<option value="${_escAttr(nb.id)}">${_escHtml(nb.name)}</option>`).join('');
    }).catch(() => { nbSel.innerHTML = '<option value="">Failed to load notebooks</option>'; });
  }

  function _closeCrossNbPicker() {
    document.getElementById('cross-nb-picker')?.classList.add('hidden');
    _crossNbTargetCellId = null;
  }

  async function _onCrossNbNotebookChange() {
    const nbId      = document.getElementById('cross-nb-nb-sel')?.value;
    const ancSel    = document.getElementById('cross-nb-anchor-sel');
    const insertBtn = document.getElementById('cross-nb-insert-btn');
    const preview   = document.getElementById('cross-nb-preview');
    ancSel.innerHTML    = '<option value="">Loading cells…</option>';
    ancSel.disabled     = true;
    insertBtn.disabled  = true;
    preview.textContent = '';
    if (!nbId) { ancSel.innerHTML = '<option value="">Select cell anchor…</option>'; return; }
    try {
      const nb = await Arima.api('GET', `/notebooks/${nbId}`);
      const anchored = (nb.cells || []).filter(c => c.anchor && (c.type === 'CODE' || c.type === 'PIPELINE'));
      if (!anchored.length) {
        ancSel.innerHTML = '<option value="">No anchored cells in this notebook</option>';
        return;
      }
      ancSel.innerHTML = '<option value="">Select cell anchor…</option>' +
        anchored.map(c => {
          const meta = (c.mode ? ` · ${c.mode}` : '') + (c.description ? ` — ${c.description}` : '');
          return `<option value="${_escAttr(c.anchor)}">${_escHtml(c.anchor + meta)}</option>`;
        }).join('');
      ancSel.disabled = false;
    } catch { ancSel.innerHTML = '<option value="">Failed to load cells</option>'; }
  }

  function _onCrossNbAnchorChange() {
    const nbId   = document.getElementById('cross-nb-nb-sel')?.value;
    const anchor = document.getElementById('cross-nb-anchor-sel')?.value;
    const preview   = document.getElementById('cross-nb-preview');
    const insertBtn = document.getElementById('cross-nb-insert-btn');
    if (nbId && anchor) {
      preview.textContent = `//@ depends: notebook:${nbId}/${anchor}`;
      insertBtn.disabled  = false;
    } else {
      preview.textContent = '';
      insertBtn.disabled  = true;
    }
  }

  function _insertCrossNbRef() {
    const nbId   = document.getElementById('cross-nb-nb-sel')?.value;
    const anchor = document.getElementById('cross-nb-anchor-sel')?.value;
    if (!nbId || !anchor || !_crossNbTargetCellId) return;
    const ref  = `notebook:${nbId}/${anchor}`;
    const cell = notebook?.cells?.find(c => c.id === _crossNbTargetCellId);
    if (!cell) return;
    const lines  = (cell.source || '').split('\n');
    const depIdx = lines.findIndex(l => /^\/\/@\s*depends:/.test(l.trim()));
    if (depIdx >= 0) {
      lines[depIdx] = lines[depIdx].trimEnd() + ', ' + ref;
    } else {
      const lastAnn = lines.reduce((last, l, i) => /^\/\/@/.test(l.trim()) ? i : last, -1);
      lines.splice(lastAnn + 1, 0, `//@ depends: ${ref}`);
    }
    cell.source = lines.join('\n');
    const cm = editors[_crossNbTargetCellId];
    if (cm) cm.setValue(cell.source);
    cell.dependsOn = Orchestration.parseAnnotations(cell.source).dependsOn;
    rebuildAnchorMap();
    const depsBtn  = document.getElementById(`run-deps-btn-${_crossNbTargetCellId}`);
    const cleanBtn = document.getElementById(`clean-run-btn-${_crossNbTargetCellId}`);
    if (depsBtn)  depsBtn.style.display = '';
    if (cleanBtn) cleanBtn.style.display = '';
    Arima.markDirty(true);
    _closeCrossNbPicker();
    Arima.setStatus('Cross-notebook reference added: ' + ref);
  }

  function _escAttr(s) { return String(s).replace(/"/g,'&quot;').replace(/'/g,'&#39;'); }
  function _escHtml(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

  /** Scroll to and focus a cell. Triggered by the Variable Inspector
   *  cell-tag click so users can jump from "vars belong to cell X" to
   *  the cell itself. Plays a soft pulse animation around the cell. */
  function focusCell(cellId) {
    if (!cellId) return;
    const el = document.getElementById(`cell-${cellId}`);
    if (!el) return;
    scrollCellIntoCenter(cellId);
    _setFocusedCell(cellId);
    el.classList.remove('focus-flash');
    // Force reflow so the animation restarts even if class was just removed
    void el.offsetWidth;
    el.classList.add('focus-flash');
    setTimeout(() => el.classList.remove('focus-flash'), 1500);
  }

  /**
   * Bring a specific cell fully into focus from anywhere in the app — used when the user
   * clicks a "cell is waiting for input" notification. Switches to the Notebook view, opens
   * the owning notebook tab if needed, scrolls/flashes the cell, and focuses its live prompt.
   */
  function revealCell(tabId, cellId) {
    // Switch to the top-level Notebook view.
    document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    // Switch to the owning notebook tab if it's a different one.
    if (tabId && tabId !== activeTabId && tabStore.has(tabId)) switchTab(tabId);
    // Let any tab switch render, then focus the cell + its input prompt.
    setTimeout(() => {
      focusCell(cellId);
      const sip = document.querySelector(`#body-${cellId} .stdin-interactive-prompt .sip-input`);
      if (sip) sip.focus();
    }, 60);
  }

  return {
    init, loadNotebook, save, deleteCell, deleteNotebook, moveUp, moveDown, addCell, addCellWithSource,
    insertCodeFromAI, applyCodeToCell, executeCell, getContext, focusCell, revealCell,
    runToHere, runPipeline, runWithDeps, switchTab, closeTab,
    stepStart, stepClose, stepAction, stepPrev,
    _openCrossNbPicker, _closeCrossNbPicker, _insertCrossNbRef,
    _onCrossNbNotebookChange, _onCrossNbAnchorChange
  };
})();

document.addEventListener('DOMContentLoaded', () => NotebookEditor.init());
