/**
 * Arima Notebooks — Core App
 * Tab navigation · WebSocket · REST helpers · global state
 */
const Arima = (() => {
  const state = {
    stompClient: null,
    connected:   false,
    currentNotebookId:  null,
    currentSessionId:   null,
    settings: {},
  };

  /* ── Tab navigation ─────────────────────────────── */
  function initTabs() {
    const btns   = document.querySelectorAll('.tab-btn');
    const panels = document.querySelectorAll('.tab-panel');
    const nbToolbar = document.getElementById('nb-toolbar');

    btns.forEach(btn => {
      btn.addEventListener('click', (ev) => {
        // Buttons without a data-tab (e.g. the Home tab) manage their own view — skip.
        const tab = btn.dataset.tab;
        if (!tab) return;
        btns.forEach(b => b.classList.remove('active'));
        panels.forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById(`panel-${tab}`)?.classList.add('active');
        // Only show notebook toolbar on notebook tab
        nbToolbar.toggleAttribute('data-hidden', tab !== 'notebook');

        // Home is an overlay that lives *inside* the notebook panel, so activating
        // that panel is not enough - without this the Notebook tab looked dead,
        // because Home stayed painted on top of the workspace it had just revealed.
        if (tab === 'notebook' && window.Home) {
          if (Arima.state.currentNotebookId) {
            Home.hide();
          } else if (ev.isTrusted) {
            // Nothing open yet. Only a real user gesture opens the dialog: several
            // modules (Home.go, notifications, agents) click this tab programmatically
            // just to switch panels, and they must not trigger a "New notebook" prompt.
            // The route is the same one "+ New" and the Home chip take, so every
            // entry point lands on the identical dialog.
            Home.show();
            document.getElementById('btn-new')?.click();
          }
        }
      });
    });
  }

  /* ── Right-edge slide-out overlays (AI panel + Variable Inspector) ──
     Both panels share a single backdrop. The FAB launches the AI panel
     and is itself draggable. Opening one panel closes the other so they
     never overlap. Click on the backdrop, press Esc, or click the
     panel's × button to dismiss. */
  function initAiToggle() {
    const aiSidebar = document.getElementById('ai-sidebar');
    const inspector = document.getElementById('var-inspector');
    const fab       = document.getElementById('ai-fab');
    const backdrop  = document.getElementById('ai-backdrop');
    if (!aiSidebar || !fab || !backdrop) return;

    // ── Refresh the shared backdrop + FAB + var-tab position ──────────
    // The vertical "Variables" tab stays parked just left of the AI panel
    // whenever AI is open — we expose its current width as the CSS var
    // --ai-w-current and the tab's `right:` rule reads it.
    function refreshOverlayUI() {
      const aiOpen = !aiSidebar.classList.contains('hidden');
      const inspectorOpen = inspector && !inspector.classList.contains('hidden');
      backdrop.classList.toggle('visible', aiOpen || inspectorOpen);
      fab.classList.toggle('hidden', aiOpen);
      const aiWidth = aiOpen ? aiSidebar.offsetWidth : 0;
      document.documentElement.style.setProperty('--ai-w-current', aiWidth + 'px');
    }

    function setAiOpen(open) {
      // AI and the Variables drawer can now coexist — when both are open
      // the drawer auto-positions to the LEFT of the AI panel via
      // --ai-w-current. We no longer force-close the drawer on AI open.
      aiSidebar.classList.toggle('hidden', !open);
      refreshOverlayUI();
    }

    function setInspectorOpen(open) {
      if (!inspector) return;
      inspector.classList.toggle('hidden', !open);
      document.body.classList.toggle('var-drawer-open', open);
      refreshOverlayUI();
    }

    // Keep the tab offset accurate while the user resizes the AI panel
    // via its drag handle. ResizeObserver fires on every animation frame.
    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(refreshOverlayUI).observe(aiSidebar);
    }
    window.addEventListener('resize', refreshOverlayUI);

    fab.addEventListener('click', () => {
      // Suppress the click that trails a drag
      if (fab.dataset.justDragged === '1') {
        fab.dataset.justDragged = '0';
        return;
      }
      setAiOpen(true);
    });

    backdrop.addEventListener('click', () => {
      setAiOpen(false);
      setInspectorOpen(false);
    });

    document.getElementById('btn-ai-close')?.addEventListener('click', () => setAiOpen(false));
    document.getElementById('btn-vi-close')?.addEventListener('click', () => setInspectorOpen(false));

    document.addEventListener('keydown', e => {
      if (e.key !== 'Escape') return;
      if (!aiSidebar.classList.contains('hidden')) { setAiOpen(false); return; }
      if (inspector && !inspector.classList.contains('hidden')) { setInspectorOpen(false); return; }
    });

    // Public API — other modules drive the overlays through these.
    Arima.openAi          = () => setAiOpen(true);
    Arima.closeAi         = () => setAiOpen(false);
    Arima.openInspector   = () => setInspectorOpen(true);
    Arima.closeInspector  = () => setInspectorOpen(false);

    initFabAnchor(fab);
  }

  /* ── FAB positioning ──────────────────────────────────────────────
     Default ("anchored"): the launcher parks directly beneath the
     Shutdown button and re-anchors on every layout change.

     Drag is an override, stored as an OFFSET from that anchor rather
     than as absolute viewport coordinates. That keeps a dragged FAB
     resize-stable too — it travels with the Shutdown button instead of
     drifting and getting clamped into a corner it never leaves.
     Dropping it back near the anchor (within SNAP_BACK px) clears the
     override and returns it to anchored mode. */
  const FAB_GAP        = 14;   // px between the Shutdown button and the FAB
  const FAB_OFFSET_KEY = 'ai-fab-offset';
  const SNAP_BACK      = 24;   // drop this close to the anchor to reset

  function initFabAnchor(fab) {
    // Retire the old absolute-position cache from the drag-anywhere era.
    try { localStorage.removeItem('ai-fab-pos'); } catch { /* private mode */ }

    let offset = readFabOffset();

    const place = () => positionFab(fab, offset);
    place();

    window.addEventListener('resize', place);
    // Catches layout shifts that don't fire a window resize — the topbar
    // collapsing its button labels, fonts loading, a scrollbar appearing.
    const shutdownBtn = document.getElementById('btn-shutdown');
    if (shutdownBtn && typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(place).observe(shutdownBtn);
    }
    window.addEventListener('load', place);

    initFabDrag(fab, {
      getOffset: () => offset,
      setOffset: (next) => {
        offset = next;
        if (next) {
          try { localStorage.setItem(FAB_OFFSET_KEY, JSON.stringify(next)); } catch { /* ignore */ }
        } else {
          try { localStorage.removeItem(FAB_OFFSET_KEY); } catch { /* ignore */ }
        }
        place();
      },
    });

    Arima.repositionFab = place;
    /** Drop the drag override and return the FAB under the Shutdown button. */
    Arima.resetFabPosition = () => {
      offset = null;
      try { localStorage.removeItem(FAB_OFFSET_KEY); } catch { /* ignore */ }
      place();
    };
  }

  function readFabOffset() {
    try {
      const saved = JSON.parse(localStorage.getItem(FAB_OFFSET_KEY) || 'null');
      if (saved && Number.isFinite(saved.dx) && Number.isFinite(saved.dy)) return saved;
    } catch { /* ignore corrupt saved value */ }
    return null;
  }

  /** Anchor point: right edges flush with the Shutdown button, FAB_GAP below. */
  function fabAnchorPoint(w, h) {
    const rect = document.getElementById('btn-shutdown')?.getBoundingClientRect();
    if (rect && rect.width > 0) {
      return { x: rect.right - w, y: rect.bottom + FAB_GAP };
    }
    return { x: window.innerWidth - w - 24, y: 24 + h };  // topbar not laid out yet
  }

  /** Place the FAB at the anchor, plus the drag offset when one is set. */
  function positionFab(fab, offset) {
    const w = fab.offsetWidth  || 58;
    const h = fab.offsetHeight || 58;
    const a = fabAnchorPoint(w, h);
    const x = a.x + (offset?.dx || 0);
    const y = a.y + (offset?.dy || 0);

    const maxX = Math.max(4, window.innerWidth  - w - 4);
    const maxY = Math.max(4, window.innerHeight - h - 4);
    fab.style.left   = Math.max(4, Math.min(maxX, x)) + 'px';
    fab.style.top    = Math.max(4, Math.min(maxY, y)) + 'px';
    fab.style.right  = 'auto';
    fab.style.bottom = 'auto';
  }

  /** Drag-to-override. Records the drop point as an offset from the anchor. */
  function initFabDrag(fab, { setOffset }) {
    let dragging = false, moved = false;
    let startX = 0, startY = 0, grabX = 0, grabY = 0;
    const DRAG_THRESHOLD = 4;  // px of motion before it counts as a drag

    fab.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      const rect = fab.getBoundingClientRect();
      grabX  = e.clientX - rect.left;
      grabY  = e.clientY - rect.top;
      startX = e.clientX;
      startY = e.clientY;
      dragging = true;
      moved    = false;
      e.preventDefault();
    });

    document.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      if (!moved && Math.hypot(e.clientX - startX, e.clientY - startY) < DRAG_THRESHOLD) return;
      if (!moved) { moved = true; fab.classList.add('dragging'); }
      // Free-follow the cursor while dragging; the drop is what gets stored.
      fab.style.left   = (e.clientX - grabX) + 'px';
      fab.style.top    = (e.clientY - grabY) + 'px';
      fab.style.right  = 'auto';
      fab.style.bottom = 'auto';
    });

    document.addEventListener('mouseup', () => {
      if (!dragging) return;
      dragging = false;
      if (!moved) return;

      fab.classList.remove('dragging');
      fab.dataset.justDragged = '1';

      const rect = fab.getBoundingClientRect();
      const a = fabAnchorPoint(rect.width, rect.height);
      const dx = rect.left - a.x;
      const dy = rect.top  - a.y;

      // Dropped back on the anchor → clear the override entirely.
      setOffset(Math.hypot(dx, dy) < SNAP_BACK ? null : { dx, dy });
    });
  }

  /* ── WebSocket (STOMP / SockJS) ─────────────────── */
  function initWebSocket() {
    const client = new StompJs.Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 3000,
      onConnect: () => {
        state.connected = true;
        state.stompClient = client;
        setWsStatus('connected');
        // Notify lifecycle module — covers reconnect after restart
        window.ServerLifecycle?.onWsConnect?.();
      },
      onDisconnect: () => {
        state.connected = false;
        setWsStatus('disconnected');
        // Notify lifecycle module — covers Ctrl+C, stop script, or UI-triggered shutdown
        window.ServerLifecycle?.onWsDisconnect?.();
      },
      onStompError: () => {
        state.connected = false;
        setWsStatus('disconnected');
        window.ServerLifecycle?.onWsDisconnect?.();
      },
    });
    client.activate();
    state.stompClient = client;
    setWsStatus('connecting');
  }

  function setWsStatus(s) {
    const dot   = document.getElementById('ws-dot');
    const label = document.getElementById('ws-label');
    if (!dot) return;
    dot.className = 'ws-dot ' + s;
    label.textContent = s;
  }

  /* ── REST helpers ───────────────────────────────── */
  async function api(method, path, body) {
    const opts = { method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await fetch('/api' + path, opts);
    const text = await res.text();
    if (!res.ok) {
      const msg = (() => { try { return JSON.parse(text).error || text; } catch { return text; } })();
      throw new Error(msg || `HTTP ${res.status}`);
    }
    return text ? JSON.parse(text) : null;
  }

  /* ── Status bar ─────────────────────────────────── */
  function setStatus(msg, level) {
    const el = document.getElementById('sb-msg');
    if (el) el.textContent = msg;
  }

  function initClock() {
    const el = document.getElementById('sb-time');
    if (!el) return;
    const tick = () => { el.textContent = new Date().toLocaleTimeString(); };
    tick(); setInterval(tick, 1000);
  }

  function markDirty(dirty) {
    document.getElementById('dirty-dot')?.classList.toggle('visible', dirty);
  }

  /* ── Load initial settings ──────────────────────── */
  async function loadSettings() {
    try {
      state.settings = await api('GET', '/settings');
      const badge = document.getElementById('ai-model-badge');
      if (badge) badge.textContent = state.settings.claudeModel || 'claude-sonnet-4-6';
      // Apply theme
      if (state.settings.theme === 'light') {
        document.documentElement.setAttribute('data-theme', 'light');
      }
    } catch (e) {
      console.warn('[Arima] Could not load settings:', e.message);
    }
  }

  /* ── Keyboard shortcuts ─────────────────────────── */
  function initShortcuts() {
    document.addEventListener('keydown', e => {
      if ((e.ctrlKey || e.metaKey) && e.key === 's') {
        e.preventDefault();
        document.getElementById('btn-save')?.click();
      }
      if ((e.ctrlKey || e.metaKey) && e.key === '\\') {
        e.preventDefault();
        const open = !document.getElementById('ai-sidebar')?.classList.contains('hidden');
        open ? Arima.closeAi?.() : Arima.openAi?.();
      }
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === 'V') {
        e.preventDefault();
        document.getElementById('btn-paste-cell')?.click();
      }
    });
  }

  /* ── Splash screen ──────────────────────────────── */
  function initSplash() {
    // Hard fallback — splash will ALWAYS disappear after 4 seconds no matter what
    setTimeout(hideSplash, 4000);

    // Scatter random stars across the starfield
    const container = document.getElementById('splash-stars');
    if (!container) return;
    for (let i = 0; i < 90; i++) {
      const s = document.createElement('div');
      const big = Math.random() < 0.12;
      s.className = 'splash-star';
      s.style.cssText = [
        `left:${(Math.random()*100).toFixed(1)}%`,
        `top:${(Math.random()*100).toFixed(1)}%`,
        `width:${big ? 2 : 1}px`,
        `height:${big ? 2 : 1}px`,
        `--delay:${(Math.random()*4).toFixed(2)}s`,
        `--dur:${(1.4 + Math.random()*2.2).toFixed(2)}s`,
        `--lo:${(0.05 + Math.random()*0.15).toFixed(2)}`,
        `--hi:${(0.5  + Math.random()*0.5).toFixed(2)}`,
      ].join(';');
      container.appendChild(s);
    }
  }

  function setSplashMsg(text) {
    const el = document.getElementById('splash-msg');
    if (el) el.textContent = text;
  }

  function hideSplash() {
    const splash = document.getElementById('barista-splash');
    if (!splash || splash.style.display === 'none') return;
    splash.style.transition = 'opacity 0.6s ease';
    splash.style.opacity = '0';
    splash.style.pointerEvents = 'none';
    setTimeout(() => { splash.style.display = 'none'; }, 650);
  }

  function showShutdownSplash() {
    const splash = document.getElementById('barista-splash');
    if (!splash) return;
    // Ensure splash is visible and styled as shutdown
    splash.style.display = '';
    splash.style.opacity = '1';
    splash.style.pointerEvents = '';
    splash.classList.remove('splash-hidden', 'splash-hiding');
    splash.classList.add('splash-shutdown');
    setSplashMsg('Shutting down…');
    // Drain the progress bar
    setTimeout(() => {
      const bar = splash.querySelector('.splash-loader-bar');
      if (bar) bar.classList.add('draining');
    }, 100);
    // After server stops, show "Arima is Off" state with restart instructions
    setTimeout(() => {
      setSplashMsg('Arima is Off');
      const brand = splash.querySelector('.splash-brand');
      if (!brand || brand.querySelector('.splash-shutdown-info')) return;

      // Restart instructions block
      const info = document.createElement('div');
      info.className = 'splash-shutdown-info';
      info.innerHTML = `
        <p class="shutdown-subtitle">The server has stopped gracefully.</p>
        <div class="shutdown-restart-box">
          <span class="shutdown-restart-label">To restart Arima Notebooks:</span>
          <code class="shutdown-cmd">scripts\\start.bat</code>
          <span class="shutdown-restart-or">or from project root:</span>
          <code class="shutdown-cmd">mvn spring-boot:run</code>
        </div>`;
      brand.appendChild(info);

      // Reconnect button that polls the server
      const btn = document.createElement('button');
      btn.className = 'splash-reconnect';
      btn.innerHTML = '<span class="reconnect-dot"></span> Reconnect';
      brand.appendChild(btn);

      let polling = false;
      btn.onclick = () => {
        if (polling) return;
        polling = true;
        btn.innerHTML = '<span class="reconnect-dot polling"></span> Waiting for server…';
        btn.style.opacity = '0.7';
        btn.style.cursor = 'default';
        const timer = setInterval(async () => {
          try {
            const r = await fetch('/api/settings/status', { cache: 'no-store' });
            if (r.ok) { clearInterval(timer); location.reload(); }
          } catch { /* server not up yet */ }
        }, 2500);
      };
    }, 2000);
  }

  /* ── Shutdown ───────────────────────────────────── */
  function initDocs() {
    // Docs moved from the toolbar into the account menu; both ids are bound so a
    // stale cached page keeps working.
    ['btn-docs'].forEach(id => {
      document.getElementById(id)?.addEventListener('click', () => {
        UserAuth?.closeMenu?.();
        DocsPanel?.show('usage');
      });
    });
  }

  function initShutdown() {
    document.getElementById('btn-shutdown')?.addEventListener('click', async () => {
      if (!confirm('Shut down Arima Notebooks?\n\nThe server will stop and this page will go offline.')) return;

      const btn = document.getElementById('btn-shutdown');
      btn.disabled = true;
      setStatus('Shutting down…');

      showShutdownSplash();

      try {
        await api('POST', '/settings/shutdown');
      } catch { /* connection drop after shutdown is expected */ }
    });
  }

  /* ── Boot ───────────────────────────────────────── */
  async function init() {
    initSplash();
    setSplashMsg('Starting up…');

    initTabs();
    initAiToggle();
    initWebSocket();
    initClock();
    initShortcuts();
    initShutdown();
    initDocs();

    try {
      setSplashMsg('Loading settings…');
      await loadSettings();

      setSplashMsg('Signing in…');
      await UserAuth.init();

      setSplashMsg('Fetching server info…');
      try {
        const status = await api('GET', '/settings/status');
        const el = document.getElementById('sb-java');
        if (el) el.textContent = `Java ${status.javaVersion}`;
      } catch { /* ignore */ }

      setSplashMsg('Ready ✦');
    } catch (e) {
      setSplashMsg('Error — retrying…');
      console.error('[Arima] Init error:', e);
    } finally {
      // Always hide the splash — even if something above threw
      setTimeout(hideSplash, 700);
    }
  }

  /* ── WebSocket session subscription ─────────── */
  // Returns an unsubscribe function.  Call it after execution completes.
  function subscribeToSession(sessionId, callback) {
    if (state.stompClient && state.connected) {
      const sub = state.stompClient.subscribe(`/topic/shell/${sessionId}`, (msg) => {
        try { callback(JSON.parse(msg.body)); } catch(e) { /* ignore */ }
      });
      return () => { try { sub.unsubscribe(); } catch { /* ignore */ } };
    }
    // WS not ready yet — poll until connected
    let sub = null;
    const waitForWs = setInterval(() => {
      if (state.connected && state.stompClient) {
        clearInterval(waitForWs);
        sub = state.stompClient.subscribe(`/topic/shell/${sessionId}`, (msg) => {
          try { callback(JSON.parse(msg.body)); } catch(e) { /* ignore */ }
        });
      }
    }, 100);
    return () => {
      clearInterval(waitForWs);
      try { sub?.unsubscribe(); } catch { /* ignore */ }
    };
  }

  /* ── Send a message to a shell session via WebSocket ── */
  function sendToShell(sessionId, destination, payload) {
    if (!state.stompClient || !state.connected) return;
    state.stompClient.publish({
      destination: `/app/shell/${sessionId}/${destination}`,
      body: JSON.stringify(payload),
    });
  }

  return { init, state, api, setStatus, markDirty, subscribeToSession, sendToShell };
})();

// Run init — works whether DOM is already ready or still loading
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => Arima.init());
} else {
  Arima.init();
}

/* ── Variable Inspector ────────────────────────────────────────────
   Accumulates variables across cell runs, piling each cell's locals
   into its own collapsible section. Sections are sorted by document
   order (the same order the cells appear in the notebook), so users
   can scan top-to-bottom and match what they see in the notebook.

   Bi-directional linking:
     • Click a section header  → scroll to & focus the source cell.
     • Focus a cell (via run, click, or arrow-keys) → scroll the
       inspector to that cell's section and flash it.

   Coexistence with AI:
     The drawer slides in to the LEFT of the AI panel when AI is open
     (positioned via the --ai-w-current CSS var, which app.js keeps in
     sync with the AI panel's current width). The right-edge tab hides
     while the drawer is open. Clicking outside (backdrop) closes the
     drawer; AI stays open. */
const VarInspector = (() => {
  // cellId -> { cellAnchor, locals, globals, ts }
  const _byCell = new Map();

  function escapeHtml(s) {
    return String(s ?? '')
      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  function _renderRows(rows) {
    if (!rows || rows.length === 0) return '';
    const head = `<thead><tr><th>Name</th><th>Type</th><th>Value</th></tr></thead>`;
    const body = '<tbody>' + rows.map(v => {
      const valClass = v.value === 'null'             ? 'vi-value null'
                     : v.value === '<unavailable>'    ? 'vi-value unavailable'
                     : v.value === 'undefined'        ? 'vi-value null'
                     : 'vi-value';
      return `<tr>
        <td class="vi-name">${escapeHtml(v.name)}</td>
        <td class="vi-type">${escapeHtml(v.type)}</td>
        <td><div class="${valClass}">${escapeHtml(v.value)}</div></td>
      </tr>`;
    }).join('') + '</tbody>';
    return head + body;
  }

  function _fmtTimeAgo(ts) {
    const s = Math.floor((Date.now() - ts) / 1000);
    if (s < 5) return 'just now';
    if (s < 60) return s + 's ago';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    return Math.floor(s / 3600) + 'h ago';
  }

  /** Return cell IDs in notebook document order. */
  function _orderedCellIds() {
    const ids = Array.from(document.querySelectorAll('.cell[id^="cell-"]'))
      .map(el => el.id.replace(/^cell-/, ''));
    // Move any tracked cells that don't appear in the DOM (deleted, switched
    // notebook, etc.) to the end so they don't get lost — they'll be culled
    // on the next notebook reload via clear().
    const known = new Set(ids);
    const extras = Array.from(_byCell.keys()).filter(k => !known.has(k));
    return [...ids.filter(id => _byCell.has(id)), ...extras];
  }

  function _renderAll() {
    const list = document.getElementById('vi-cell-list');
    const empty = document.getElementById('vi-empty');
    if (!list) return;

    if (_byCell.size === 0) {
      list.innerHTML = '';
      empty?.classList.remove('hidden');
      return;
    }
    empty?.classList.add('hidden');

    const orderedIds = _orderedCellIds();
    list.innerHTML = orderedIds.map(cellId => {
      const e = _byCell.get(cellId);
      const label = e.cellAnchor ? '#' + e.cellAnchor : cellId;
      const meta = `${e.locals.length} local${e.locals.length === 1 ? '' : 's'}`;
      const time = _fmtTimeAgo(e.ts);
      const table = _renderRows(e.locals);
      return `
        <section class="vi-cell-section" id="vi-section-${escapeHtml(cellId)}" data-cell-id="${escapeHtml(cellId)}">
          <button class="vi-cell-header" type="button" data-cell-id="${escapeHtml(cellId)}"
                  title="Scroll to & focus cell ${escapeHtml(label)}">
            <span class="vi-cell-dot"></span>
            <span class="vi-cell-name">${escapeHtml(label)}</span>
            <span class="vi-cell-meta">${meta}</span>
            <span class="vi-cell-time">${time}</span>
            <svg class="vi-cell-jump" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <div class="vi-cell-body">
            ${table || '<div class="vi-empty" style="padding:14px">No locals captured.</div>'}
          </div>
        </section>
      `;
    }).join('');

    // Wire each header to focus its cell
    list.querySelectorAll('.vi-cell-header').forEach(h => {
      h.addEventListener('click', () => {
        const cid = h.dataset.cellId;
        window.NotebookEditor?.focusCell?.(cid);
      });
    });
  }

  function _renderTab() {
    const tab = document.getElementById('var-tab');
    if (!tab) return;
    if (_byCell.size === 0) {
      tab.classList.add('hidden');
      return;
    }
    // Tab label shows the most recently-updated cell + total cell count
    const latest = Array.from(_byCell.entries()).sort((a,b) => b[1].ts - a[1].ts)[0];
    const latestLabel = latest[1].cellAnchor ? '#' + latest[1].cellAnchor : latest[0];
    const countLabel  = _byCell.size === 1 ? latestLabel : `${_byCell.size} cells`;
    const labelEl = document.getElementById('vt-cell-label');
    if (labelEl) labelEl.textContent = countLabel;
    tab.classList.remove('hidden');
  }

  /** Push a cell's variables in. Empty payloads remove the cell's section. */
  function update(payload) {
    const cellId = payload?.cellId;
    if (!cellId) return;
    const locals  = payload.locals  || [];
    const globals = payload.globals || [];
    if (locals.length === 0 && globals.length === 0) {
      _byCell.delete(cellId);
    } else {
      _byCell.set(cellId, {
        cellAnchor: payload.cellAnchor || null,
        locals, globals,
        ts: Date.now(),
      });
    }
    _renderAll();
    _renderTab();
  }

  /** Open the drawer (re-renders so timestamps and counts are current). */
  function open() {
    _renderAll();
    _renderTab();
    Arima.openInspector?.();
  }

  /** Hide the drawer AND the tab. Tab returns on the next cell run. */
  function dismissTab() {
    _byCell.clear();
    document.getElementById('var-tab')?.classList.add('hidden');
    Arima.closeInspector?.();
    _renderAll();
  }

  /** Clear the inspector — called when switching notebooks. */
  function clear() {
    _byCell.clear();
    _renderAll();
    document.getElementById('var-tab')?.classList.add('hidden');
  }

  /**
   * Scroll the drawer to (and flash) the section for `cellId`. Called by
   * NotebookEditor when a cell gets focus, so the inspector mirrors what
   * the user is looking at. Silently no-ops when the drawer is closed.
   */
  function scrollToCell(cellId) {
    const drawer = document.getElementById('var-inspector');
    if (!drawer || drawer.classList.contains('hidden')) return;
    if (!cellId || !_byCell.has(cellId)) return;
    const sec = document.getElementById('vi-section-' + cellId);
    if (!sec) return;
    sec.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    sec.classList.remove('flash');
    void sec.offsetWidth; // restart animation
    sec.classList.add('flash');
    setTimeout(() => sec.classList.remove('flash'), 1500);
  }

  // ── Wire DOM hooks once the page is ready ─────────────────────────
  function init() {
    const tab = document.getElementById('var-tab');
    if (tab) {
      tab.addEventListener('click', (e) => {
        if (e.target.closest('#vt-close')) return;
        open();
      });
    }
    document.getElementById('vt-close')?.addEventListener('click', (e) => {
      e.stopPropagation();
      dismissTab();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else { init(); }

  return { update, open, dismissTab, clear, scrollToCell };
})();
window.VarInspector = VarInspector;

/* ── Error Log ─────────────────────────────────────────────────────── */
const ErrorLog = (() => {
  const entries = [];

  function add(source, message, detail) {
    const ts = new Date().toLocaleTimeString();
    entries.push({ ts, source, message, detail });
    _render();
    // Show button with count
    const btn = document.getElementById('sb-err-btn');
    if (btn) { btn.style.display = ''; document.getElementById('sb-err-count').textContent = entries.length; }
  }

  function clear() {
    entries.length = 0;
    _render();
    const btn = document.getElementById('sb-err-btn');
    if (btn) btn.style.display = 'none';
  }

  function toggle() {
    const panel = document.getElementById('error-log-panel');
    if (!panel) return;
    panel.style.display = panel.style.display === 'none' ? '' : 'none';
  }

  function _render() {
    const body  = document.getElementById('elp-body');
    const count = document.getElementById('elp-count');
    if (!body) return;
    if (count) count.textContent = entries.length + (entries.length === 1 ? ' error' : ' errors');
    body.innerHTML = entries.slice().reverse().map(e => `
      <div class="elp-entry">
        <div class="elp-entry-header">
          <span class="elp-ts">${e.ts}</span>
          <span class="elp-source">${e.source || ''}</span>
        </div>
        <div class="elp-msg">${e.message || ''}</div>
        ${e.detail ? `<pre class="elp-detail">${e.detail}</pre>` : ''}
      </div>`).join('');
  }

  return { add, clear, toggle };
})();

/* ── UserAuth ──────────────────────────────────────────────────────── */
const UserAuth = (() => {
  let _user = null;   // { id, name, firstName, email, avatarUrl, authProvider }
  let _authMode = 'local';
  let _menuOpen = false;

  /* Called once during boot — resolves identity and updates the UI */
  async function init() {
    try {
      const data = await Arima.api('GET', '/user/me');
      _authMode = data.authMode || 'local';

      if (data.authenticated) {
        _user = { id: data.id, name: data.name, firstName: data.firstName,
                  email: data.email, avatarUrl: data.avatarUrl,
                  authProvider: (data.authProvider || 'LOCAL').toUpperCase() };
        _renderWidget();
        // Prompt for email if not set (non-blocking, deferred)
        if (!_user.email) setTimeout(_maybePromptEmail, 3000);
      } else if (_authMode === 'oauth') {
        // Show login modal — user must authenticate
        showLogin();
      }
      // local + not authenticated should not happen, but handle gracefully
    } catch (e) {
      console.warn('[UserAuth] Could not load user:', e.message);
    }
  }

  /**
   * The account widget is owned by user-menu.js now. This used to reach into its
   * elements directly and, once that markup was rebuilt, every getElementById
   * returned null - the first `.textContent =` threw a TypeError, which aborted the
   * rest of this module's setup and left the whole toolbar and home page unresponsive.
   * Rendering is delegated, and nothing here can take the page down again.
   */
  function _renderWidget() {
    if (!_user) return;
    try {
      if (window.UserMenu) UserMenu.paint(_user);
    } catch (e) {
      console.warn('[UserAuth] could not render the account widget:', e);
    }
  }

  function greeting(name) {
    const h = new Date().getHours();
    const tod = h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
    return `${tod}, ${name || 'there'}!`;
  }

  /* Kept as thin delegates: the account menu is owned by user-menu.js now. */
  function toggleMenu() { if (window.UserMenu) UserMenu.toggle ? UserMenu.toggle() : UserMenu.close(); }
  function closeMenu()  { if (window.UserMenu) UserMenu.close(); }


  /* Show the OAuth login modal */
  async function showLogin() {
    const modal = document.getElementById('login-modal');
    if (!modal) return;
    await renderProviders();
    modal.style.display = 'flex';
  }

  function hideLogin() {
    const modal = document.getElementById('login-modal');
    if (modal) modal.style.display = 'none';
  }

  /* Render available OAuth provider buttons from server config */
  async function renderProviders() {
    const container = document.getElementById('login-providers');
    if (!container) return;
    try {
      const cfg = await Arima.api('GET', '/user/oauth-config');
      const providers = [
        { id: 'google',    label: 'Continue with Google',    configured: cfg.googleConfigured,
          icon: `<svg viewBox="0 0 24 24" width="20" height="20"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>` },
        { id: 'microsoft', label: 'Continue with Microsoft', configured: cfg.microsoftConfigured,
          icon: `<svg viewBox="0 0 24 24" width="20" height="20"><path d="M11.4 11.4H0V0h11.4v11.4z" fill="#F35325"/><path d="M24 11.4H12.6V0H24v11.4z" fill="#81BC06"/><path d="M11.4 24H0V12.6h11.4V24z" fill="#05A6F0"/><path d="M24 24H12.6V12.6H24V24z" fill="#FFBA08"/></svg>` },
        { id: 'facebook',  label: 'Continue with Facebook',  configured: cfg.facebookConfigured,
          icon: `<svg viewBox="0 0 24 24" width="20" height="20"><path d="M24 12.073C24 5.405 18.627 0 12 0S0 5.405 0 12.073c0 6.023 4.388 11.017 10.125 11.927V15.563H7.078v-3.49h3.047V9.43c0-3.016 1.792-4.681 4.533-4.681 1.312 0 2.686.235 2.686.235v2.953H15.83c-1.491 0-1.956.927-1.956 1.874v2.25h3.328l-.532 3.49h-2.796v8.437C19.612 23.09 24 18.096 24 12.073z" fill="#1877F2"/></svg>` },
      ];

      container.innerHTML = providers.map(p => {
        if (p.configured) {
          return `<a class="login-provider-btn" href="/oauth2/authorization/${p.id}">
            ${p.icon}<span>${p.label}</span>
          </a>`;
        } else {
          return `<button class="login-provider-btn disabled" title="Configure credentials in Settings → Authentication" disabled>
            ${p.icon}<span>${p.label}</span>
            <span class="login-not-configured">Not configured</span>
          </button>`;
        }
      }).join('');
    } catch (e) {
      container.innerHTML = `<p class="login-loading" style="color:var(--red)">Could not load providers</p>`;
    }
  }

  /* User chose to continue without logging in (local fallback in oauth mode) */
  async function continueLocal() {
    hideLogin();
    // Reload user info which will now use anonymous local fallback
    await init();
  }

  /* Show the email capture prompt */
  function showEmailPrompt() {
    closeMenu();
    const modal = document.getElementById('email-prompt');
    if (modal) {
      const inp = document.getElementById('email-input');
      if (inp && _user?.email) inp.value = _user.email;
      modal.style.display = 'flex';
    }
  }

  function hideEmailPrompt() {
    const modal = document.getElementById('email-prompt');
    if (modal) modal.style.display = 'none';
  }

  async function saveEmail() {
    const inp = document.getElementById('email-input');
    const email = inp?.value?.trim();
    if (!email) return;
    try {
      const resp = await Arima.api('PUT', '/user/me/email', { email });
      if (_user) _user.email = resp.email;
      if (window.UserMenu) UserMenu.refresh();
      hideEmailPrompt();
      Arima.setStatus('Email saved ✓');
    } catch (e) {
      Arima.setStatus('Could not save email: ' + e.message);
    }
  }

  async function logout() {
    try { await Arima.api('POST', '/user/logout'); } catch { /* ignore */ }
    _user = null;
    // In oauth mode: show login modal so user can pick their account again
    // In local mode: there's no session to clear so just reload for safety
    if (_authMode === 'oauth') {
      _resetWidget();
      await showLogin();
    } else {
      window.location.reload();
    }
  }

  /** Sign out then immediately show the provider selection modal */
  async function switchAccount() {
    closeMenu();
    try { await Arima.api('POST', '/user/logout'); } catch { /* ignore */ }
    _user = null;
    _resetWidget();
    await showLogin();
  }

  function _resetWidget() {
    const avatarEl = document.getElementById('user-avatar');
    if (avatarEl) avatarEl.textContent = '?';
    const nameEl = document.getElementById('user-name');
    if (nameEl) nameEl.textContent = '—';
    const provEl = document.getElementById('user-provider');
    if (provEl) { provEl.textContent = ''; provEl.className = 'user-provider'; }
  }

  function _maybePromptEmail() {
    // Only prompt in oauth mode when email is still missing
    if (_authMode === 'oauth' && _user && !_user.email) showEmailPrompt();
  }

  /* Expose current user to other modules */
  function getUser() { return _user; }

  return { init, getUser, showLogin, hideLogin, toggleMenu, closeMenu, showEmailPrompt, hideEmailPrompt, saveEmail, logout, continueLocal };
})();

/* Published on `window` explicitly: a top-level `const` creates a global binding
   but NOT a window property, so `if (window.X)` guards elsewhere never fired. */
window.Arima = Arima;
window.UserAuth = UserAuth;
