/*
 * Approval gate for work that arrived from another machine.
 *
 * When local-network access is on, a CLI or agent elsewhere can reach the execution
 * endpoints - and those run code as you. Nothing from off this machine runs until it
 * is approved here. The server raises the browser when something arrives; this module
 * polls as well, so a request is never missed if the window was already open.
 */
const Approvals = (() => {
  'use strict';

  const POLL_IDLE = 4000;   // nothing outstanding
  const POLL_BUSY = 1000;   // something is waiting on a person
  let timer = null;
  let showing = new Set();

  function start() {
    if (timer) return;
    tick();
  }

  async function tick() {
    let next = POLL_IDLE;
    try {
      const res = await Arima.api('GET', '/approvals');
      if (res && res.count > 0) {
        render(res.pending);
        next = POLL_BUSY;
      } else {
        close();
      }
    } catch (_) {
      // The server may be restarting; keep polling quietly.
    }
    timer = setTimeout(tick, next);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** Pull the code out of the captured request body, for a readable diff. */
  function codeOf(item) {
    if (!item.code) return '';
    try {
      const parsed = JSON.parse(item.code);
      return parsed.code || parsed.source || item.code;
    } catch (_) {
      return item.code;
    }
  }

  function render(pending) {
    let host = document.getElementById('approval-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'approval-host';
      document.body.appendChild(host);
    }

    const ids = pending.map(p => p.id).join(',');
    if (host.dataset.ids === ids) return;   // already showing exactly these
    host.dataset.ids = ids;
    showing = new Set(pending.map(p => p.id));

    host.innerHTML = `
      <div class="apv-backdrop">
        <div class="apv-modal" role="dialog" aria-modal="true" aria-labelledby="apv-title">
          <div class="apv-head">
            <div class="apv-badge">Waiting for you</div>
            <h2 id="apv-title">Code arrived from another machine</h2>
            <p>
              Arima runs code with your user account. ${pending.length === 1
                ? 'This request came'
                : `These ${pending.length} requests came`} from outside this computer
              and will not run unless you allow ${pending.length === 1 ? 'it' : 'them'}.
            </p>
          </div>
          <div class="apv-list">
            ${pending.map(item => `
              <div class="apv-item" data-id="${esc(item.id)}">
                <div class="apv-meta">
                  <span class="apv-origin">${esc(item.origin)}</span>
                  <span class="apv-action">${esc(item.action)}</span>
                  ${item.language ? `<span class="apv-lang">${esc(item.language)}</span>` : ''}
                </div>
                <pre class="apv-code">${esc(codeOf(item))}</pre>
                <div class="apv-actions">
                  <button class="apv-deny" data-id="${esc(item.id)}">Refuse</button>
                  <button class="apv-approve" data-id="${esc(item.id)}">Run it</button>
                </div>
              </div>`).join('')}
          </div>
          <div class="apv-foot">
            <button class="apv-deny-all">Refuse everything</button>
            <span class="apv-hint">
              Not expecting this? Refuse, then turn off network access in Settings.
            </span>
          </div>
        </div>
      </div>`;

    host.querySelectorAll('.apv-approve').forEach(b =>
      b.addEventListener('click', () => decide(b.dataset.id, 'approve', b)));
    host.querySelectorAll('.apv-deny').forEach(b =>
      b.addEventListener('click', () => decide(b.dataset.id, 'deny', b)));
    host.querySelector('.apv-deny-all').addEventListener('click', async () => {
      await Arima.api('POST', '/approvals/deny-all').catch(() => {});
      close();
    });

    // The server raises the window; make sure the page itself takes focus too.
    try { window.focus(); } catch (_) {}
  }

  async function decide(id, verb, btn) {
    if (btn) { btn.disabled = true; btn.textContent = verb === 'approve' ? 'Running...' : 'Refusing...'; }
    try {
      await Arima.api('POST', `/approvals/${id}/${verb}`);
    } catch (e) {
      if (btn) { btn.disabled = false; btn.textContent = verb === 'approve' ? 'Run it' : 'Refuse'; }
      return;
    }
    const el = document.querySelector(`.apv-item[data-id="${id}"]`);
    if (el) el.remove();
    showing.delete(id);
    if (showing.size === 0) close();
  }

  function close() {
    const host = document.getElementById('approval-host');
    if (host) { host.innerHTML = ''; delete host.dataset.ids; }
    showing.clear();
  }

  return { start };
})();

document.addEventListener('DOMContentLoaded', () => Approvals.start());
