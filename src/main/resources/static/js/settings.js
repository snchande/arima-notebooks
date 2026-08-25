/**
 * Arima Notebooks - Settings Panel
 * Load and save application settings.
 */

const SettingsPanel = (() => {
    function init() {
        bindButtons();
        loadSettings();
        loadServerStatus(); // populate version badge immediately on startup
    }

    async function loadSettings() {
        try {
            const settings = await Arima.api('GET', '/settings');
            populateForm(settings);
        } catch (e) {
            console.error('Failed to load settings:', e);
        }
    }

    function populateForm(s) {
        // AI provider
        const provider = s.aiProvider || 'claude_cli';
        const radios = document.querySelectorAll('input[name="ai-provider"]');
        radios.forEach(r => { r.checked = (r.value === provider); });
        _applyProviderVisibility(provider);

        setVal('s-model',         s.claudeModel);
        setVal('s-copilot-model', s.githubCopilotModel);
        setVal('s-gemini-model',  s.geminiModel);
        setVal('s-tokens',        s.claudeMaxTokens);
        setVal('s-fontsize',      s.editorFontSize);
        setCheck('s-linenums',    s.showLineNumbers !== false);
        setCheck('s-focus-cell',  s.focusExecutingCell !== false);

        setCheck('s-pg-enabled', s.polyglotEnabled !== false);
        setCheck('s-pg-sbs',     !!s.polyglotSideBySide);
        setVal('s-pg-dominant',  s.polyglotDominantLanguage || '');
        const picked = (s.polyglotCompareLanguages || '').split(',').map(x => x.trim());
        document.querySelectorAll('.s-pg-lang').forEach(el => { el.checked = picked.includes(el.value); });
        // Apply theme swatches
        applyThemeSwatch(s.theme || 'dark');
    }

    function setVal(id, val) {
        const el = document.getElementById(id);
        if (el && val != null) el.value = val;
    }

    function setCheck(id, checked) {
        const el = document.getElementById(id);
        if (el) el.checked = checked;
    }

    async function saveSettings() {
        const providerRadio = document.querySelector('input[name="ai-provider"]:checked');
        const provider = providerRadio?.value || 'claude_cli';

        const settings = {
            aiProvider:         provider,
            claudeModel:        document.getElementById('s-model')?.value,
            githubCopilotModel: document.getElementById('s-copilot-model')?.value,
            geminiModel:        document.getElementById('s-gemini-model')?.value,
            claudeMaxTokens:    parseInt(document.getElementById('s-tokens')?.value) || 4096,
            theme:              document.getElementById('s-theme')?.value,
            editorFontSize:     parseInt(document.getElementById('s-fontsize')?.value) || 14,
            showLineNumbers:    document.getElementById('s-linenums')?.checked ?? true,
            focusExecutingCell: document.getElementById('s-focus-cell')?.checked ?? true,
            polyglotEnabled:    document.getElementById('s-pg-enabled')?.checked ?? true,
            polyglotSideBySide: document.getElementById('s-pg-sbs')?.checked ?? false,
            polyglotDominantLanguage: document.getElementById('s-pg-dominant')?.value || '',
            polyglotCompareLanguages: Array.from(document.querySelectorAll('.s-pg-lang:checked'))
                                           .map(el => el.value).join(','),
        };

        try {
            const saved = await Arima.api('PUT', '/settings', settings);
            Arima.state.settings = { ...Arima.state.settings, ...saved };

            // Apply theme immediately
            applyThemeSwatch(saved.theme || 'dark');

            // Update AI model badge
            const badge = document.getElementById('ai-model-badge');
            if (badge) {
                if (saved.aiProvider === 'copilot_cli') badge.textContent = 'Copilot';
                else if (saved.aiProvider === 'gemini_cli') badge.textContent = 'Antigravity';
                else badge.textContent = saved.claudeModel || 'Claude';
            }

            const flash = document.getElementById('saved-flash');
            if (flash) {
                flash.classList.remove('hidden');
                setTimeout(() => flash.classList.add('hidden'), 3000);
            }

            Arima.setStatus('Settings saved');
            // Refresh AI intro greeting to reflect the new provider
            if (typeof AIAssistant !== 'undefined' && AIAssistant._updateIntroProvider) {
                AIAssistant._updateIntroProvider();
            }
        } catch (e) {
            alert('Failed to save settings: ' + e.message);
        }
    }

    async function loadServerStatus() {
        const container = document.getElementById('s-status-body');
        if (!container) return;
        container.innerHTML = '<div class="muted">Loading…</div>';

        try {
            const status = await Arima.api('GET', '/settings/status');
            const activeProvider = status.aiProvider || 'claude_cli';
            // Update status-bar version badge
            const vBadge = document.getElementById('sb-version');
            if (vBadge) {
                vBadge.textContent = 'v' + (status.version || '1.0.0');
                vBadge.title = 'Version ' + (status.version || '1.0.0');
            }
            const buildBadge = document.getElementById('sb-build-date');
            if (buildBadge && status.buildTimestamp) {
                const d = new Date(status.buildTimestamp);
                const fmtDate = isNaN(d) ? status.buildTimestamp
                    : d.toLocaleDateString(undefined, { year:'numeric', month:'short', day:'numeric' })
                      + ' ' + d.toLocaleTimeString(undefined, { hour:'2-digit', minute:'2-digit' });
                buildBadge.textContent = 'built ' + fmtDate;
                buildBadge.title = 'Build timestamp: ' + status.buildTimestamp;
            }
            // Update inline gh/gemini CLI status in the settings cards
            _updateGhCliStatus(status.githubCopilotAvailable, status.githubCopilotStatus);
            _updateGeminiCliStatus(status.geminiCliAvailable, status.geminiCliStatus);
            const providerLabel = { claude_cli: 'Claude CLI', copilot_cli: 'Copilot SDK', gemini_cli: 'Antigravity CLI' }[activeProvider] || activeProvider;
            container.innerHTML = `
                <table class="status-table">
                    <tr><td>Version</td><td><strong>v${status.version || '1.0.0'}</strong></td></tr>
                    <tr><td>Built</td><td>${_fmtBuildDate(status.buildTimestamp)}</td></tr>
                    <tr><td>Java Version</td><td>${status.javaVersion || 'Unknown'}</td></tr>
                    <tr><td>Java Home</td><td style="font-size:11px;word-break:break-all">${status.javaHome || '—'}</td></tr>
                    <tr><td>AI Provider</td><td>${providerLabel}</td></tr>
                    <tr><td>Claude CLI</td><td>${status.claudeCliAvailable ? '✓ Found' : '✗ Not found — run <code>claude auth</code>'}</td></tr>
                    <tr><td>Claude Model</td><td>${status.claudeModel || '—'}</td></tr>
                    <tr><td>Copilot SDK</td><td>${status.githubCopilotAvailable ? '✓ copilot CLI found' : '✗ copilot CLI not found — install the <a href="https://github.com/github/copilot-cli" target="_blank">GitHub Copilot CLI</a>'}</td></tr>
                    <tr><td>Copilot Auth</td><td style="font-size:11.5px">${status.githubCopilotStatus || '—'}</td></tr>
                    <tr><td>Antigravity CLI</td><td>${status.geminiCliAvailable ? '✓ Found' : '✗ Not found — <a href="https://antigravity.google/docs/cli-install" target="_blank">install agy</a>'}</td></tr>
                    <tr><td>Antigravity Status</td><td style="font-size:11.5px">${status.geminiCliStatus || '—'}</td></tr>
                    <tr><td>C++ Compiler</td><td>${status.cppAvailable ? '✓ ' + (status.cppCompilerDetail || 'Found') : '✗ Not found — see Packages → C++ for setup'}</td></tr>
                    <tr><td>Theme</td><td>${status.theme || 'dark'}</td></tr>
                </table>
                <button class="btn-secondary" id="btn-refresh-status" style="margin-top:8px">Refresh</button>`;
            document.getElementById('btn-refresh-status')?.addEventListener('click', loadServerStatus);
        } catch (e) {
            container.innerHTML = `<div class="text-error">Failed: ${e.message}</div>
                <button class="btn-secondary" id="btn-refresh-status" style="margin-top:8px">Retry</button>`;
            document.getElementById('btn-refresh-status')?.addEventListener('click', loadServerStatus);
        }
    }

    // ── Auth Settings ──────────────────────────────────────────────────

    async function loadAuthSettings() {
        try {
            // User info (to show current user banner)
            const user = await Arima.api('GET', '/user/me');
            _renderAuthBanner(user);
            _setAuthModeRadio(user.authMode || 'local');

            // OAuth credentials
            const cfg = await Arima.api('GET', '/user/oauth-config');
            _populateOAuthFields(cfg);
        } catch (e) {
            console.warn('[Settings] Could not load auth settings:', e.message);
        }
    }

    function _renderAuthBanner(user) {
        const avatarEl = document.getElementById('auth-banner-avatar');
        const nameEl   = document.getElementById('auth-banner-name');
        const subEl    = document.getElementById('auth-banner-sub');
        if (!nameEl) return;
        if (user.authenticated) {
            nameEl.textContent = user.name || user.id;
            subEl.textContent  = user.email || (user.authProvider === 'LOCAL' ? 'Local user (OS account)' : user.authProvider);
            if (user.avatarUrl && avatarEl) {
                avatarEl.innerHTML = `<img src="${user.avatarUrl}" alt="${user.name}" style="width:100%;height:100%;object-fit:cover;border-radius:50%">`;
            } else if (avatarEl) {
                const initials = (user.name || '?').split(' ').map(w => w[0]).slice(0,2).join('').toUpperCase();
                avatarEl.textContent = initials;
            }
        }
    }

    function _setAuthModeRadio(mode) {
        const local = document.getElementById('auth-mode-local');
        const oauth = document.getElementById('auth-mode-oauth');
        if (local) local.checked = mode === 'local';
        if (oauth) oauth.checked = mode === 'oauth';
    }

    function _populateOAuthFields(cfg) {
        setValue('oauth-google-id',    cfg.googleClientId || '');
        setValue('oauth-google-secret',cfg.googleClientSecret || '');
        setValue('oauth-ms-id',        cfg.microsoftClientId || '');
        setValue('oauth-ms-secret',    cfg.microsoftClientSecret || '');
        setValue('oauth-ms-tenant',    cfg.microsoftTenantId || 'common');
        setValue('oauth-fb-id',        cfg.facebookClientId || '');
        setValue('oauth-fb-secret',    cfg.facebookClientSecret || '');

        _setProviderStatus('google',    cfg.googleConfigured);
        _setProviderStatus('microsoft', cfg.microsoftConfigured);
        _setProviderStatus('facebook',  cfg.facebookConfigured);
    }

    function _setProviderStatus(provider, configured) {
        const el = document.getElementById(`${provider}-status`);
        if (!el) return;
        el.textContent = configured ? '✓ Configured' : '✗ Not configured';
        el.className   = 'auth-provider-status ' + (configured ? 'configured' : 'not-configured');
    }

    function setValue(id, val) {
        const el = document.getElementById(id);
        if (el) el.value = val;
    }

    async function saveAuthSettings() {
        // Save auth mode change
        const modeEl = document.querySelector('input[name="auth-mode"]:checked');
        const mode = modeEl?.value || 'local';

        // Save OAuth credentials
        const creds = {
            googleClientId:      document.getElementById('oauth-google-id')?.value.trim() || '',
            googleClientSecret:  document.getElementById('oauth-google-secret')?.value.trim() || '',
            microsoftClientId:   document.getElementById('oauth-ms-id')?.value.trim() || '',
            microsoftClientSecret: document.getElementById('oauth-ms-secret')?.value.trim() || '',
            microsoftTenantId:   document.getElementById('oauth-ms-tenant')?.value.trim() || 'common',
            facebookClientId:    document.getElementById('oauth-fb-id')?.value.trim() || '',
            facebookClientSecret:document.getElementById('oauth-fb-secret')?.value.trim() || '',
        };

        try {
            const [modeResult, credsResult] = await Promise.all([
                Arima.api('PUT', '/settings/auth-mode', { mode }),
                Arima.api('PUT', '/user/oauth-config', creds),
            ]);

            // Update provider status badges
            _setProviderStatus('google',    credsResult.googleConfigured);
            _setProviderStatus('microsoft', credsResult.microsoftConfigured);
            _setProviderStatus('facebook',  credsResult.facebookConfigured);

            // Show restart notice if mode actually changed
            const needsRestart = modeResult.restartRequired || credsResult.restartRequired;
            const notice = document.getElementById('auth-restart-notice');
            if (notice) notice.classList.toggle('hidden', !needsRestart);

            const flash = document.getElementById('auth-saved-flash');
            if (flash) { flash.classList.remove('hidden'); setTimeout(() => flash.classList.add('hidden'), 3000); }

            Arima.setStatus(needsRestart ? 'Auth settings saved — restart to apply' : 'Auth settings saved');
        } catch (e) {
            alert('Failed to save auth settings: ' + e.message);
        }
    }

    // ── Utility exposed globally for Show/Hide buttons in HTML ──────────

    function applyThemeSwatch(theme) {
        document.documentElement.setAttribute('data-theme', theme || 'dark');
        // Update hidden input value
        const hidden = document.getElementById('s-theme');
        if (hidden) hidden.value = theme || 'dark';
        // Update active swatch
        document.querySelectorAll('.theme-swatch').forEach(sw => {
            sw.classList.toggle('active', sw.dataset.theme === theme);
        });
    }

    function _fmtBuildDate(iso) {
        if (!iso) return '—';
        try {
            const d = new Date(iso);
            return d.toLocaleDateString(undefined, { year:'numeric', month:'short', day:'numeric' })
                 + ' ' + d.toLocaleTimeString(undefined, { hour:'2-digit', minute:'2-digit' });
        } catch { return iso; }
    }

    function _applyProviderVisibility(provider) {
        const claudeFields  = document.getElementById('ai-claude-fields');
        const copilotFields = document.getElementById('ai-copilot-fields');
        const geminiFields  = document.getElementById('ai-gemini-fields');
        if (claudeFields)  claudeFields.style.display  = provider === 'claude_cli'     ? '' : 'none';
        if (copilotFields) copilotFields.style.display = provider === 'copilot_cli' ? '' : 'none';
        if (geminiFields)  geminiFields.style.display  = provider === 'gemini_cli'  ? '' : 'none';
        if (provider === 'copilot_cli') _refreshGhCliStatus();
        if (provider === 'gemini_cli')     _refreshGeminiCliStatus();
    }

    function _refreshGhCliStatus() {
        Arima.api('GET', '/settings/status').then(status => {
            _updateGhCliStatus(status.githubCopilotAvailable, status.githubCopilotStatus);
        }).catch(() => {});
    }

    function _updateGhCliStatus(available, detail) {
        const dot  = document.getElementById('gh-cli-status-dot');
        const text = document.getElementById('gh-cli-status-text');
        if (!dot || !text) return;
        const isAuthed = available && detail && detail.startsWith('✓');
        dot.style.background  = isAuthed ? '#22c55e' : (available ? '#f59e0b' : '#ef4444');
        text.textContent = detail || (available ? 'gh found — run: gh auth login' : 'gh CLI not found');
    }

    function _refreshGeminiCliStatus() {
        Arima.api('GET', '/settings/status').then(status => {
            _updateGeminiCliStatus(status.geminiCliAvailable, status.geminiCliStatus);
        }).catch(() => {});
    }

    function _updateGeminiCliStatus(available, detail) {
        const dot  = document.getElementById('gemini-cli-status-dot');
        const text = document.getElementById('gemini-cli-status-text');
        if (!dot || !text) return;
        const isOk = available && detail && detail.startsWith('✓');
        dot.style.background  = isOk ? '#22c55e' : (available ? '#f59e0b' : '#ef4444');
        text.textContent = detail || (available ? 'agy found — run: agy to sign in' : 'Antigravity CLI (agy) not found');
    }

    function bindButtons() {
        document.getElementById('btn-save-settings')?.addEventListener('click', saveSettings);
        document.getElementById('btn-refresh-status')?.addEventListener('click', loadServerStatus);

        // Guided Tour (FRE) controls
        const replayToggle = document.getElementById('s-replay-tour');
        if (replayToggle && typeof FRE !== 'undefined') {
            replayToggle.checked = FRE.replayPending();
            replayToggle.addEventListener('change', () => FRE.setReplayNextLaunch(replayToggle.checked));
        }
        document.getElementById('btn-start-tour')?.addEventListener('click', () => {
            if (typeof FRE !== 'undefined') FRE.replay();
        });


        // AI provider radio toggle
        document.getElementById('ai-provider-picker')?.addEventListener('change', e => {
            if (e.target.name === 'ai-provider') _applyProviderVisibility(e.target.value);
        });

        // Theme swatch clicks — live preview
        document.getElementById('theme-picker')?.addEventListener('click', (e) => {
            const swatch = e.target.closest('.theme-swatch');
            if (swatch) applyThemeSwatch(swatch.dataset.theme);
        });

        // Load auth settings when the Settings tab is activated
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.dataset.tab === 'settings') loadAuthSettings();
            });
        });
    }

    return { init, saveAuthSettings };
})();

/** Toggle password field visibility — called from inline HTML onclick */
function toggleSecret(inputId, btn) {
    const input = document.getElementById(inputId);
    if (!input) return;
    input.type = input.type === 'password' ? 'text' : 'password';
    btn.textContent = input.type === 'password' ? 'Show' : 'Hide';
}

document.addEventListener('DOMContentLoaded', () => SettingsPanel.init());
