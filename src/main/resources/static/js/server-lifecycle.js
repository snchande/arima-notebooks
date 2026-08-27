/**
 * Arima Notebooks — Server Lifecycle
 *
 * Shutdown, restart, and automatic reconnect with notebook restore.
 *
 * ── Triggered from UI ─────────────────────────────────────────────────────
 *  Restart:  save → POST /api/system/restart → overlay → poll health → reload page
 *  Shutdown: save → POST /api/system/shutdown → overlay (stopped message, no reload)
 *
 * ── Detected automatically (Ctrl+C / kill / stop script) ──────────────────
 *  WebSocket drops and stays down > 5 s → overlay → poll health
 *    • Server comes back  → reload page (restart scenario)
 *    • Server stays down  → "stopped" message at 35 s (shutdown scenario)
 *
 * ── Preserving your work ──────────────────────────────────────────────────
 *  Before any shutdown/restart, the current notebook is saved to disk.
 *  The notebook ID is stored in sessionStorage so it re-opens automatically
 *  after the page reloads, showing exactly what you had before.
 */

const ServerLifecycle = (() => {

    const RESTART_URL        = '/api/system/restart';
    const SHUTDOWN_URL       = '/api/system/shutdown';
    const HEALTH_URL         = '/actuator/health';

    const POLL_INTERVAL_MS   = 2000;   // how often to check health
    const WS_THRESHOLD_MS    = 5000;   // show overlay N ms after WS drops
    const CANCEL_AFTER_MS    = 15000;  // show Cancel button after 15 s
    const SHUTDOWN_ASSUME_MS = 35000;  // give up waiting after 35 s

    // 'idle' | 'restarting' | 'shutting_down'
    let _mode            = 'idle';
    let _polling         = false;
    let _disconnectTimer = null;
    let _cancelTimer     = null;

    // ── WS hooks (called by app.js) ──────────────────────────────────────

    function onWsDisconnect() {
        if (_mode !== 'idle') return;          // intentional lifecycle already running
        clearTimeout(_disconnectTimer);
        _disconnectTimer = setTimeout(() => {
            if (_mode !== 'idle') return;
            _mode = 'restarting';              // unknown disconnect → treat as restart
            _showOverlay('Server disconnected', 'Arima lost connection — checking if it will come back…', true);
            _startHealthPolling();
        }, WS_THRESHOLD_MS);
    }

    function onWsConnect() {
        // Cancel pending "show overlay" timer on quick reconnect (STOMP blip)
        clearTimeout(_disconnectTimer);
    }

    // ── Public API (UI buttons) ──────────────────────────────────────────

    async function restart() {
        if (!confirm(
            'Restart Arima Notebooks?\n\n' +
            'Your notebook is saved first. The page reloads automatically when Arima is back.\n\n' +
            'Tip: run Arima via start.sh / start.bat for automatic server restart.\n' +
            'With "mvn spring-boot:run" you will need to restart the server manually.'
        )) return;

        _mode = 'restarting';
        clearTimeout(_disconnectTimer);

        await _saveCurrentNotebook();
        _showOverlay('Restarting Arima…', 'Notebook saved. Waiting for the server to come back…', true);

        try { await fetch(RESTART_URL, { method: 'POST' }); } catch (_) {}

        _startHealthPolling();
    }

    async function shutdown() {
        if (!confirm(
            'Shut down Arima Notebooks?\n\n' +
            'Your notebook is saved first. Run start.sh / start.bat to start Arima again.'
        )) return;

        _mode = 'shutting_down';
        clearTimeout(_disconnectTimer);

        await _saveCurrentNotebook();
        _showOverlay(
            'Arima is shutting down…',
            'The server is stopping. Run start.sh / start.bat to start it again.',
            false
        );

        try { await fetch(SHUTDOWN_URL, { method: 'POST' }); } catch (_) {}
        // No polling — shutdown is final
    }

    function cancelReconnect() {
        _polling = false;
        clearTimeout(_disconnectTimer);
        clearTimeout(_cancelTimer);
        _mode = 'idle';
        _hideOverlay();
    }

    // ── Health polling ───────────────────────────────────────────────────

    async function _startHealthPolling() {
        if (_polling) return;
        _polling = true;
        const start = Date.now();

        // Give the server a moment to start shutting down before the first probe
        await _sleep(2500);

        while (_polling && _mode !== 'idle') {
            // Timed out — server is likely fully stopped, not just restarting
            if (Date.now() - start > SHUTDOWN_ASSUME_MS) {
                _assumeShutdown();
                break;
            }

            try {
                const res = await fetch(HEALTH_URL, { cache: 'no-store' });
                if (res.ok && _polling && _mode !== 'idle') {
                    _polling = false;
                    await _onServerBack();
                    return;
                }
            } catch (_) {
                // Still down — keep polling
            }

            await _sleep(POLL_INTERVAL_MS);
        }

        _polling = false;
    }

    // ── Server state callbacks ───────────────────────────────────────────

    async function _onServerBack() {
        _updateSub('Server is back — reloading your notebook…');

        // Store notebook ID so init() can re-open it after the page reloads
        const nbId = Arima?.state?.currentNotebook?.id;
        if (nbId) sessionStorage.setItem('barista-restart-restore', nbId);

        await _sleep(700);
        _mode = 'idle';
        window.location.reload();
    }

    function _assumeShutdown() {
        _polling = false;
        _mode    = 'idle';
        clearTimeout(_cancelTimer);

        document.querySelector('.reconnect-spinner')?.style.setProperty('display', 'none');
        document.getElementById('reconnect-dots')?.style.setProperty('display', 'none');

        _updateTitle('Server stopped');
        _updateSub(
            'Arima is not responding.\n' +
            'If you used start.sh / start.bat, the server may restart automatically.\n' +
            'Otherwise restart it in your terminal, then click Refresh Page.'
        );

        const dismissBtn = document.getElementById('reconnect-cancel');
        if (dismissBtn) {
            dismissBtn.textContent  = 'Dismiss';
            dismissBtn.style.display = '';
        }
        // Show "Refresh Page" so the user can come back after manually restarting
        const refreshBtn = document.getElementById('reconnect-refresh');
        if (refreshBtn) refreshBtn.style.display = '';
    }

    // ── Overlay helpers ──────────────────────────────────────────────────

    function _showOverlay(title, sub, spinning) {
        _updateTitle(title);
        _updateSub(sub);

        const spinner   = document.querySelector('.reconnect-spinner');
        const dots      = document.getElementById('reconnect-dots');
        const cancelBtn = document.getElementById('reconnect-cancel');

        const refreshBtn = document.getElementById('reconnect-refresh');

        if (spinner)    spinner.style.display    = spinning ? '' : 'none';
        if (dots)       dots.style.display       = spinning ? '' : 'none';
        if (cancelBtn)  { cancelBtn.textContent  = 'Cancel'; cancelBtn.style.display  = 'none'; }
        if (refreshBtn) refreshBtn.style.display = 'none';

        document.getElementById('reconnect-overlay')?.classList.remove('hidden');

        if (spinning) {
            clearTimeout(_cancelTimer);
            _cancelTimer = setTimeout(() => {
                if (cancelBtn) {
                    cancelBtn.textContent  = 'Cancel';
                    cancelBtn.style.display = '';
                }
            }, CANCEL_AFTER_MS);
        }
    }

    function _hideOverlay() {
        document.getElementById('reconnect-overlay')?.classList.add('hidden');
        clearTimeout(_cancelTimer);
    }

    function _updateTitle(t) {
        const el = document.getElementById('reconnect-title');
        if (el) el.textContent = t;
    }

    function _updateSub(s) {
        const el = document.getElementById('reconnect-sub');
        if (el) el.textContent = s;
    }

    function _sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

    // ── Save helper ──────────────────────────────────────────────────────

    async function _saveCurrentNotebook() {
        try {
            if (typeof NotebookEditor !== 'undefined' && NotebookEditor.save) {
                await NotebookEditor.save();
            }
        } catch (e) {
            console.warn('[ServerLifecycle] Pre-shutdown save failed:', e);
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────

    function init() {
        document.getElementById('btn-lifecycle-restart')
            ?.addEventListener('click', restart);
        document.getElementById('btn-lifecycle-shutdown')
            ?.addEventListener('click', shutdown);
        // Toolbar power button
        document.getElementById('btn-shutdown')
            ?.addEventListener('click', shutdown);

        // After a page reload that followed a restart, re-open the notebook
        // that was open before — so the user sees exactly what they had.
        const restoreId = sessionStorage.getItem('barista-restart-restore');
        if (restoreId) {
            sessionStorage.removeItem('barista-restart-restore');
            // Delay slightly so NotebookEditor.init() finishes first
            setTimeout(async () => {
                try {
                    const nb = await Arima.api('GET', `/notebooks/${restoreId}`);
                    if (nb && typeof NotebookEditor !== 'undefined') {
                        NotebookEditor.loadNotebook(nb);
                        Arima.setStatus('Notebook restored after restart');
                    }
                } catch (e) {
                    console.warn('[ServerLifecycle] Notebook restore failed:', e);
                }
            }, 1200);
        }
    }

    document.addEventListener('DOMContentLoaded', init);

    return { restart, shutdown, cancelReconnect, onWsConnect, onWsDisconnect };
})();

/* Published on `window` explicitly: a top-level `const` creates a global binding
   but NOT a window property, so `if (window.X)` guards elsewhere would never fire. */
window.ServerLifecycle = ServerLifecycle;
