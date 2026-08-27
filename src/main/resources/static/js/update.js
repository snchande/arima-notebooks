/*
 * Update notice.
 *
 * Checks once on load whether master is ahead of the checked-out copy, and offers to
 * install it. Installing pulls, rebuilds and restarts, so it is always the user's
 * decision - the banner never acts on its own, and it can be dismissed for the
 * session without nagging.
 */
const UpdateNotice = (() => {
  'use strict';

  const DISMISS_KEY = 'arima.update.dismissed';

  async function check() {
    let info;
    try {
      info = await Arima.api('GET', '/update/check');
    } catch (_) {
      return;                       // offline, or not a git checkout
    }
    if (!info || !info.available) return;

    let dismissed = null;
    try { dismissed = sessionStorage.getItem(DISMISS_KEY); } catch (_) {}
    if (dismissed === String(info.commitsBehind)) return;

    render(info);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function render(info) {
    const host = document.createElement('div');
    host.id = 'update-banner';
    host.className = 'upd-banner';

    const n = info.commitsBehind;
    const changes = (info.changes || []).slice(0, 5);

    host.innerHTML = `
      <div class="upd-main">
        <span class="upd-dot"></span>
        <div class="upd-text">
          <strong>A newer version of Arima is available</strong>
          <span class="upd-sub">${n} change${n === 1 ? '' : 's'} on master${
            info.currentVersion ? ` &middot; you are on ${esc(info.currentVersion)}` : ''}</span>
          ${changes.length ? `<ul class="upd-changes">${
            changes.map(c => `<li>${esc(c)}</li>`).join('')}</ul>` : ''}
          ${info.blocked ? `<div class="upd-blocked">${esc(info.blocked)}</div>` : ''}
        </div>
      </div>
      <div class="upd-actions">
        ${info.blocked ? '' : '<button class="upd-apply">Update and restart</button>'}
        <button class="upd-later">Not now</button>
      </div>`;

    document.body.appendChild(host);

    host.querySelector('.upd-later').addEventListener('click', () => {
      try { sessionStorage.setItem(DISMISS_KEY, String(n)); } catch (_) {}
      host.remove();
    });

    const apply = host.querySelector('.upd-apply');
    if (apply) apply.addEventListener('click', () => startUpdate(host));
  }

  async function startUpdate(host) {
    host.classList.add('upd-working');
    host.querySelector('.upd-actions').innerHTML = '';
    const text = host.querySelector('.upd-text');
    text.innerHTML = '<strong>Updating</strong><span class="upd-sub">Pulling the latest code...</span>';

    try {
      await Arima.api('POST', '/update/apply', {});
    } catch (e) {
      text.innerHTML = `<strong>Update failed</strong><span class="upd-sub">${
        esc((e && e.message) || e)}</span>`;
      return;
    }
    followProgress(text);
  }

  /**
   * The server rebuilds and then restarts, so this poll is expected to start failing
   * - that is the signal the restart began, not an error.
   */
  function followProgress(text) {
    let sawRestart = false;
    const poll = setInterval(async () => {
      try {
        const st = await Arima.api('GET', '/update/status');
        const last = (st.log || []).slice(-1)[0] || 'Working...';
        text.innerHTML = `<strong>Updating</strong><span class="upd-sub">${esc(last)}</span>`;
        if (st.state === 'restarting') sawRestart = true;
        if (st.state === 'failed') {
          clearInterval(poll);
          text.innerHTML = `<strong>Update failed</strong><span class="upd-sub">${esc(last)}</span>`;
        }
      } catch (_) {
        // Unreachable: the restart is under way. Wait for it to come back.
        if (sawRestart) {
          clearInterval(poll);
          text.innerHTML = '<strong>Restarting</strong><span class="upd-sub">Reconnecting...</span>';
          waitForServer();
        }
      }
    }, 1500);
  }

  function waitForServer() {
    const retry = setInterval(async () => {
      try {
        await Arima.api('GET', '/system/info');
        clearInterval(retry);
        location.reload();
      } catch (_) { /* still down */ }
    }, 2000);
  }

  return { check };
})();

document.addEventListener('DOMContentLoaded', () => {
  // Slight delay so the notebook UI paints first.
  setTimeout(() => UpdateNotice.check(), 1200);
});
