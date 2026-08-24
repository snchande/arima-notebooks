/* ── Notifications ─────────────────────────────────────────────────────────
   Attention bell for cells that are waiting for user input.

   When a cell blocks on stdin (any language) and the user has switched to a
   different browser tab or app, we:
     • light up the header bell with a badge count,
     • fire a desktop/OS Web Notification (browser-local — no server involved),
     • flash the browser-tab title as a fallback.
   Clicking the bell entry or the OS notification jumps straight back to the
   waiting cell (correct Notebook view + tab), scrolls to it, and focuses its
   inline prompt so the user can type immediately.

   Exposes window.Notifications = { inputNeeded, clear }.
   ────────────────────────────────────────────────────────────────────────── */
const Notifications = (() => {
  const pending = new Map();          // cellId -> { sessionId, cellId, tabId, label, at }
  const done = [];                    // finished notebook runs: { id, tabId, notebookName, status, summary, at }
  let doneSeq = 0;
  let permissionAsked = false;
  let titleFlashTimer = null;
  const originalTitle = document.title;

  function esc(s) {
    return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function els() {
    return {
      btn:   document.getElementById('notif-bell'),
      badge: document.getElementById('notif-badge'),
      menu:  document.getElementById('notif-menu'),
      list:  document.getElementById('notif-list'),
    };
  }

  // Ask for OS-notification permission once, lazily (on the first input request).
  function ensurePermission() {
    if (permissionAsked) return;
    permissionAsked = true;
    try {
      if ('Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission();
      }
    } catch { /* unsupported */ }
  }

  function renderBadge() {
    const { badge, btn } = els();
    const n = pending.size + done.length;
    if (badge) {
      badge.textContent = n > 9 ? '9+' : String(n);
      badge.style.display = n > 0 ? '' : 'none';
    }
    if (btn) btn.classList.toggle('has-pending', n > 0);
    renderTitle();
  }

  function renderTitle() {
    if (pending.size > 0 && (document.hidden || !document.hasFocus())) startTitleFlash();
    else stopTitleFlash();
  }

  function startTitleFlash() {
    if (titleFlashTimer) return;
    let on = true;
    titleFlashTimer = setInterval(() => {
      document.title = on ? '● Input needed — Arima' : originalTitle;
      on = !on;
    }, 1000);
  }

  function stopTitleFlash() {
    if (titleFlashTimer) { clearInterval(titleFlashTimer); titleFlashTimer = null; }
    document.title = originalTitle;
  }

  function timeAgo(at) {
    const s = Math.round((Date.now() - at) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    return Math.floor(s / 3600) + 'h ago';
  }

  function renderList() {
    const { list } = els();
    if (!list) return;
    if (pending.size === 0 && done.length === 0) {
      list.innerHTML = `<div class="notif-empty">No notifications</div>`;
      return;
    }
    list.innerHTML = '';

    // Waiting-for-input items first (most urgent).
    [...pending.values()].reverse().forEach(item => {
      const row = document.createElement('button');
      row.className = 'notif-item';
      row.type = 'button';
      row.innerHTML =
        `<span class="notif-item-icon">⌨</span>` +
        `<span class="notif-item-text">` +
          `<strong>Waiting for your input</strong>` +
          `<span class="notif-item-sub">${esc(item.label)}</span>` +
        `</span>`;
      row.addEventListener('click', () => { focusItem(item); closeMenu(); });
      list.appendChild(row);
    });

    // Finished notebook runs (newest first). Click → open that notebook.
    [...done].reverse().forEach(item => {
      const ok = item.status !== 'error';
      const row = document.createElement('button');
      row.className = 'notif-item';
      row.type = 'button';
      row.innerHTML =
        `<span class="notif-item-icon">${ok ? '✅' : '⚠️'}</span>` +
        `<span class="notif-item-text">` +
          `<strong>${ok ? 'Notebook finished' : 'Notebook finished with errors'}</strong>` +
          `<span class="notif-item-sub">${esc(item.notebookName)}${item.summary ? ' · ' + esc(item.summary) : ''} · ${timeAgo(item.at)}</span>` +
        `</span>` +
        `<span class="notif-item-x" title="Dismiss" data-x="${item.id}">×</span>`;
      row.addEventListener('click', (e) => {
        if (e.target && e.target.dataset && e.target.dataset.x) { clearDone(item.id); e.stopPropagation(); return; }
        focusNotebook(item); clearDone(item.id); closeMenu();
      });
      list.appendChild(row);
    });
  }

  function focusItem(item) {
    if (!item) return;
    try { window.focus(); } catch {}
    if (window.NotebookEditor && NotebookEditor.revealCell) {
      NotebookEditor.revealCell(item.tabId, item.cellId);
    }
  }

  // Open the notebook a finished-run notification points at, and show the Notebook tab.
  function focusNotebook(item) {
    if (!item) return;
    try { window.focus(); } catch {}
    document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    if (window.NotebookEditor && NotebookEditor.loadNotebook) {
      // loadNotebook switches to the tab if it's already open, otherwise loads it.
      NotebookEditor.loadNotebook(item.tabId, !!item.isTutorial);
    }
  }

  function fireOsNotification(item) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    try {
      const n = new Notification('Arima — input needed', {
        body: item.label + '\nClick to answer the prompt.',
        tag:  'arima-input-' + item.cellId,
      });
      n.onclick = () => { focusItem(item); n.close(); };
    } catch { /* ignore */ }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  /** A cell has blocked waiting for input. Raise the bell (and OS notification if away). */
  function inputNeeded({ sessionId, cellId, tabId, notebookName }) {
    if (!cellId) return;
    ensurePermission();
    const label = (notebookName ? notebookName + ' · ' : '') + 'a cell is waiting for input';
    const item = { sessionId, cellId, tabId, label, at: Date.now() };
    pending.set(cellId, item);
    renderBadge();
    renderList();
    // Only pop an OS notification when the user is actually away.
    if (document.hidden || !document.hasFocus()) fireOsNotification(item);
  }

  /** The cell's input was provided (or it finished) — drop its notification. */
  function clear(cellId) {
    if (!cellId || !pending.has(cellId)) return;
    pending.delete(cellId);
    renderBadge();
    renderList();
  }

  /**
   * A notebook run (Run All / pipeline) finished. Adds a bell entry the user can click
   * to jump straight to that notebook — key for background/scheduled runs.
   * @param {{tabId:string, notebookName:string, status?:string, summary?:string, isTutorial?:boolean}} info
   */
  function runFinished(info) {
    if (!info || !info.tabId) return;
    ensurePermission();
    const item = {
      id: 'done-' + (++doneSeq),
      tabId: info.tabId,
      notebookName: info.notebookName || 'Notebook',
      status: info.status || 'ok',
      summary: info.summary || '',
      isTutorial: !!info.isTutorial,
      at: Date.now(),
    };
    done.push(item);
    if (done.length > 30) done.shift(); // cap history
    renderBadge();
    renderList();
    // OS notification when the user is away or looking at a different notebook.
    if (document.hidden || !document.hasFocus() || Arima?.state?.currentNotebookId !== info.tabId) {
      fireRunOsNotification(item);
    }
  }

  function fireRunOsNotification(item) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    try {
      const ok = item.status !== 'error';
      const n = new Notification('Arima — ' + (ok ? 'notebook finished' : 'notebook finished with errors'), {
        body: item.notebookName + (item.summary ? '\n' + item.summary : '') + '\nClick to open it.',
        tag: 'arima-run-' + item.id,
      });
      n.onclick = () => { focusNotebook(item); clearDone(item.id); n.close(); };
    } catch { /* ignore */ }
  }

  function clearDone(id) {
    const i = done.findIndex(d => d.id === id);
    if (i >= 0) { done.splice(i, 1); renderBadge(); renderList(); }
  }

  // ── Menu open/close ────────────────────────────────────────────────────────
  function openMenu()  { els().menu?.classList.add('open'); }
  function closeMenu() { els().menu?.classList.remove('open'); }
  function toggleMenu() {
    const { menu } = els();
    if (!menu) return;
    menu.classList.contains('open') ? closeMenu() : (renderList(), openMenu());
  }

  function init() {
    const { btn, menu } = els();
    if (btn) btn.addEventListener('click', (e) => { e.stopPropagation(); toggleMenu(); });
    // Close on outside click / Esc.
    document.addEventListener('click', (e) => {
      if (menu && menu.classList.contains('open') &&
          !menu.contains(e.target) && e.target !== btn && !btn?.contains(e.target)) {
        closeMenu();
      }
    });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenu(); });
    // Stop flashing the title once the user comes back.
    document.addEventListener('visibilitychange', renderTitle);
    window.addEventListener('focus', renderTitle);
    renderBadge();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else { init(); }

  return { inputNeeded, clear, runFinished, clearDone };
})();

window.Notifications = Notifications;
