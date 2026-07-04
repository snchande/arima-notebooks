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
    const n = pending.size;
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

  function renderList() {
    const { list } = els();
    if (!list) return;
    if (pending.size === 0) {
      list.innerHTML = `<div class="notif-empty">No notifications</div>`;
      return;
    }
    list.innerHTML = '';
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
  }

  function focusItem(item) {
    if (!item) return;
    try { window.focus(); } catch {}
    if (window.NotebookEditor && NotebookEditor.revealCell) {
      NotebookEditor.revealCell(item.tabId, item.cellId);
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

  return { inputNeeded, clear };
})();

window.Notifications = Notifications;
