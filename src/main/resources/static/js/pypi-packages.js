/**
 * Arima Notebooks — PyPI Package Manager
 * Find, install, and manage PyPI packages for Python cells.
 */
const PyPiPackageManager = (() => {
    let started = false;

    function init() {
        bindButtons();
    }

    let logSubscribed = false;

    // Called by PackageTabUI when the PyPI sub-tab is shown (lazy first load).
    function refresh() {
        checkPythonStatus();
        loadInstalledPackages();
        ensureLogSubscription();
        started = true;
    }

    // Subscribe to the pip output stream once (well before any install), so the
    // live log never misses early lines due to a just-in-time subscription race.
    function ensureLogSubscription() {
        if (logSubscribed || !(window.Arima && Arima.subscribeToSession)) return;
        Arima.subscribeToSession('pypi-install', (msg) => {
            const log = document.getElementById('pypi-install-log');
            if (msg && msg.type === 'partial_output' && msg.text && log) {
                log.textContent += msg.text;
                log.scrollTop = log.scrollHeight;
            }
        });
        logSubscribed = true;
    }

    async function checkPythonStatus() {
        const el = document.getElementById('pypi-python-status');
        if (!el) return;
        try {
            const data = await Arima.api('GET', '/pypi/status');
            if (data.available) {
                el.innerHTML = `<span class="npm-status-ok">✓ ${escapeHtml(data.version || 'Python detected')}</span>`;
            } else {
                el.innerHTML = `<span class="npm-status-warn">⚠ Python not found — install from ` +
                    `<a href="https://www.python.org/downloads/" target="_blank">python.org</a>, then restart Arima</span>`;
            }
        } catch (e) {
            el.innerHTML = `<span class="npm-status-warn">Could not check Python status</span>`;
        }
    }

    async function loadInstalledPackages() {
        const container = document.getElementById('pypi-pkg-list');
        const countBadge = document.getElementById('pypi-pkg-count');
        if (!container) return;
        try {
            const packages = await Arima.api('GET', '/pypi/packages');
            if (countBadge) countBadge.textContent = packages.length;
            renderPackageList(packages);
        } catch (e) {
            container.innerHTML = `<div class="text-error">Failed to load packages: ${escapeHtml(e.message)}</div>`;
        }
    }

    function renderPackageList(packages) {
        const container = document.getElementById('pypi-pkg-list');
        if (!container) return;
        if (!packages || packages.length === 0) {
            container.innerHTML = '<div class="muted">No PyPI packages installed yet.</div>';
            return;
        }
        container.innerHTML = packages.map(pkg => `
            <div class="pkg-item">
                <div class="pkg-item-info">
                    <div class="pkg-item-name">${escapeHtml(pkg.name)}
                        <span class="pkg-version">${escapeHtml(pkg.version)}</span>
                    </div>
                    <div class="pkg-item-coord">import ${escapeHtml(importName(pkg.name))}</div>
                </div>
                <button class="btn-danger-sm"
                    onclick="PyPiPackageManager.removePackage('${escapeHtml(pkg.name)}')">
                    Remove
                </button>
            </div>
        `).join('');
    }

    // Distribution name → common import name (best-effort hint only).
    function importName(name) {
        return String(name).toLowerCase().replace(/-/g, '_');
    }

    function setInstallStatus(msg, type) {
        const el = document.getElementById('pypi-pkg-status');
        if (!el) return;
        el.textContent = msg;
        el.className = `pkg-status pkg-status-${type}`;
        el.classList.remove('hidden');
    }

    function bindButtons() {
        document.getElementById('btn-pypi-install')?.addEventListener('click', () => installPackage());
        document.getElementById('pypi-pkg-name')?.addEventListener('keydown',
            (e) => { if (e.key === 'Enter') installPackage(); });
        document.getElementById('btn-pypi-search')?.addEventListener('click', () => searchPackages());
        document.getElementById('pypi-pkg-search')?.addEventListener('keydown',
            (e) => { if (e.key === 'Enter') searchPackages(); });

        document.querySelectorAll('.pypi-pill[data-pkg]').forEach(pill => {
            pill.addEventListener('click', () => {
                const input = document.getElementById('pypi-pkg-name');
                if (input) input.value = pill.dataset.pkg;
                installPackage();
            });
        });
    }

    async function installPackage() {
        const input = document.getElementById('pypi-pkg-name');
        const raw = input?.value.trim();
        if (!raw) {
            setInstallStatus('Enter a package name (e.g. requests or numpy==2.0.0)', 'error');
            return;
        }
        // Accept "name==version" or "name".
        let name = raw, version = 'latest';
        const eq = raw.indexOf('==');
        if (eq > 0) { name = raw.substring(0, eq); version = raw.substring(eq + 2); }

        setInstallStatus(`Installing ${name} ${version === 'latest' ? '' : version}… (live log below)`, 'loading');
        Arima.setStatus('Installing PyPI: ' + name);

        // Live, shell-style pip output streams over STOMP (subscribed on tab open).
        ensureLogSubscription();
        const log = document.getElementById('pypi-install-log');
        if (log) { log.hidden = false; log.textContent = ''; }

        try {
            const pkg = await Arima.api('POST', '/pypi/packages/install', { name, version });
            setInstallStatus(`Installed: ${pkg.name} ${pkg.version}`, 'success');
            if (input) input.value = '';
            await loadInstalledPackages();
            Arima.setStatus('PyPI installed: ' + pkg.name);
        } catch (e) {
            setInstallStatus('Install failed — see the log below. ' + (e.message || ''), 'error');
            Arima.setStatus('PyPI install failed');
        }
    }

    async function removePackage(name) {
        if (!confirm(`Remove PyPI package "${name}"?\n\n⚠ Any Python cell that imports it will fail until re-installed.`)) return;
        try {
            await Arima.api('DELETE', `/pypi/packages/${encodeURIComponent(name)}`);
            await loadInstalledPackages();
            Arima.setStatus('PyPI removed: ' + name);
        } catch (e) {
            alert('Failed to remove: ' + e.message);
        }
    }

    async function searchPackages() {
        const input = document.getElementById('pypi-pkg-search');
        const query = input?.value.trim();
        if (!query) return;
        const resultsEl = document.getElementById('pypi-pkg-results');
        if (!resultsEl) return;
        resultsEl.innerHTML = '<div class="muted">Looking up on PyPI…</div>';
        resultsEl.classList.remove('hidden');
        try {
            const results = await Arima.api('GET', `/pypi/packages/search?q=${encodeURIComponent(query)}`);
            if (!results || results.length === 0) {
                resultsEl.innerHTML = `<div class="muted">No package named "${escapeHtml(query)}" on PyPI. ` +
                    `Check the spelling — you can still install any exact name above.</div>`;
                return;
            }
            resultsEl.innerHTML = results.map(pkg => `
                <div class="pkg-search-row">
                    <div>
                        <div class="pkg-search-name">${escapeHtml(pkg.name)} <span class="pkg-version">${escapeHtml(pkg.version)}</span></div>
                        <div class="pkg-search-coord">${escapeHtml(pkg.description || '')}</div>
                    </div>
                    <button class="btn-secondary-sm" onclick="document.getElementById('pypi-pkg-name').value='${escapeHtml(pkg.name)}'">Use</button>
                </div>`).join('');
        } catch (e) {
            resultsEl.innerHTML = `<div class="text-error">Lookup failed: ${escapeHtml(e.message)}</div>`;
        }
    }

    function escapeHtml(text) {
        if (!text) return '';
        return String(text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    document.addEventListener('DOMContentLoaded', init);
    return { refresh, removePackage };
})();
window.PyPiPackageManager = PyPiPackageManager;
