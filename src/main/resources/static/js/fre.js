/* ════════════════════════════════════════════════════════════════
   Arima Notebooks — First Run Experience (FRE)
   ----------------------------------------------------------------
   An interactive, spotlight-guided walkthrough of the whole product
   that runs once on the very first launch, BEFORE the user lands on
   the notebook canvas. It drives real navigation (switches tabs,
   opens the AI panel) and highlights each section in place.

   • Auto-runs on first load (localStorage flag `arima.fre.done`).
   • Can be replayed any time from Settings → Guided Tour
     ("Start tour now", or "Show on next launch").
   • Fully client-side; no backend or notebook state is touched.
   ════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  const LS_DONE   = 'arima.fre.done';    // "1" once the tour is finished/skipped
  const LS_REPLAY = 'arima.fre.replay';  // "1" = force the tour on next launch

  // ── Navigation helpers ──────────────────────────────────────────
  function switchTab(tab) {
    document.querySelector(`.tab-btn[data-tab="${tab}"]`)?.click();
  }
  function openAI(open) {
    const sidebar = document.getElementById('ai-sidebar');
    if (!sidebar) return;
    const isOpen = !sidebar.classList.contains('hidden');
    if (open && !isOpen) document.getElementById('ai-fab')?.click();
    if (!open && isOpen) document.getElementById('ai-backdrop')?.click();
  }

  // ── Tour script ─────────────────────────────────────────────────
  // Each step: { target, title, body, before?, place? }
  //   target : CSS selector to spotlight (null = centered card)
  //   before : fn run before the step is shown (navigate/setup)
  //   place  : preferred tooltip side ('bottom'|'top'|'auto')
  const STEPS = [
    {
      target: null,
      title: 'Welcome to Arima Notebooks',
      body: 'A modern, multi-language notebook for the agentic era — a cross-platform execution plane where people and AI agents collaborate on the same living code. Take a 60-second tour of the workspace before you dive in.',
      before: () => { switchTab('notebook'); openAI(false); }
    },
    {
      target: null,
      langPicker: true,
      title: 'Which languages do you use?',
      body: 'Arima supports seven languages — but you probably don’t need them all. Pick the ones you work with and we’ll tailor the tutorials to you. You can change this anytime in <b>Settings &rarr; Languages</b>.',
      before: () => { switchTab('notebook'); openAI(false); }
    },
    {
      target: '.tab-nav',
      title: 'Your workspaces',
      body: 'Everything lives behind these tabs — <b>Notebook</b> to build, <b>Console</b> for quick REPL experiments, <b>Agents</b> to author and run agents, <b>Packages</b> to add libraries, and <b>Settings</b> (which is also home to your <b>Tutorials</b>).',
      place: 'bottom'
    },
    {
      target: '#cells-scroll',
      title: 'The notebook canvas',
      body: 'Write code and prose side by side in <b>cells</b>. Run one with <kbd>Ctrl</kbd>+<kbd>Enter</kbd> and see live output stream back instantly.',
      before: () => switchTab('notebook')
    },
    {
      target: '#nb-toolbar',
      title: 'Every language is first-class',
      body: 'Add code or markdown cells and switch each cell between <b>seven languages</b> — JShell, Java, JavaScript, TypeScript, C#, F#, and C++ — with more on the way. No language is a second-class citizen.',
      before: () => switchTab('notebook'),
      place: 'bottom'
    },
    {
      target: '#btn-add-pipeline',
      title: 'Pipelines — unique to Arima Notebooks',
      body: 'Give any cell a <code>//@ anchor:</code> name, then declare <code>//@ depends:</code> on other anchors. Arima Notebooks builds a dependency graph and runs the cells in <b>topological order</b> — a real workflow, across all seven languages.',
      before: () => switchTab('notebook'),
      place: 'bottom'
    },
    {
      target: '#notebook-selector',
      title: 'Reference &amp; reuse across notebooks',
      body: 'Named cell anchors aren\u2019t just local. With <b>cross-notebook references</b> (<code>//@ depends: notebook:id/anchor</code>) one notebook can reuse a cell from another — so notebooks compose like reusable building blocks instead of copy-paste.',
      before: () => switchTab('notebook'),
      place: 'bottom'
    },
    {
      target: '#btn-validate-graph',
      title: 'Run it as a workflow',
      body: '<b>Run All</b> executes the whole graph in order, <b>Validate</b> checks for unknown anchors and cycles, and <b>Step</b> walks you through cell by cell.',
      before: () => switchTab('notebook'),
      place: 'bottom'
    },
    {
      target: '#panel-console',
      title: 'Interactive console',
      body: 'Need a scratchpad? The Console is a live REPL — JShell, Java, or JavaScript — with code completion, separate from your notebook.',
      before: () => switchTab('console')
    },
    {
      target: '#panel-packages',
      title: 'Bring your libraries',
      body: 'Install <b>Maven</b>, <b>npm</b>, and <b>NuGet</b> packages — or set up a C++ compiler — and use them immediately in your cells.',
      before: () => switchTab('packages')
    },
    {
      target: '#ai-fab',
      title: 'Your AI co-pilots',
      body: 'Press <kbd>Ctrl</kbd>+<kbd>\\</kbd> to open the AI panel. Claude, GitHub Copilot, and Antigravity all run as <b>local subprocesses — no API keys</b>. Agents and humans work on the very same notebook.',
      before: () => { switchTab('notebook'); openAI(false); },
      place: 'left'
    },
    {
      target: '#tutorials-card',
      title: 'Learn by example',
      body: 'Find <b>Tutorials</b> in <b>Settings</b> — dozens of ready-made, hands-on lessons for the languages you picked. Press <b>▶ Guided</b> on any one for a narrated walkthrough, and watch your <b>completion %</b> climb as you run the cells.',
      before: () => switchTab('settings'),
      place: 'top'
    },
    {
      target: '#fre-tour-setting',
      title: 'Replay this tour anytime',
      body: 'You can relaunch this walkthrough whenever you like from <b>Settings → Guided Tour</b>, or have it show again on your next launch.',
      before: () => switchTab('settings'),
      place: 'top'
    },
    {
      target: null,
      title: 'You\u2019re all set',
      body: 'Arima Notebooks is <b>open source</b> — built to be reshaped. Explore, extend it, and contribute back to help shape the future of notebooks. Happy building!',
      before: () => switchTab('notebook')
    }
  ];

  // ── DOM scaffold ─────────────────────────────────────────────────
  let idx = 0;
  let els = null;

  function build() {
    if (els) return els;
    const block = document.createElement('div');
    block.id = 'fre-block';
    const ring = document.createElement('div');
    ring.id = 'fre-ring';
    const tip = document.createElement('div');
    tip.id = 'fre-tip';
    tip.innerHTML = `
      <div class="fre-tip-badge">Guided tour</div>
      <h3 class="fre-tip-title"></h3>
      <p class="fre-tip-body"></p>
      <div class="fre-tip-foot">
        <div class="fre-dots"></div>
        <div class="fre-btns">
          <button class="fre-skip" type="button">Skip</button>
          <button class="fre-back" type="button">Back</button>
          <button class="fre-next" type="button">Next</button>
        </div>
      </div>`;
    document.body.appendChild(block);
    document.body.appendChild(ring);
    document.body.appendChild(tip);

    tip.querySelector('.fre-skip').addEventListener('click', () => FRE.end(true));
    tip.querySelector('.fre-back').addEventListener('click', () => FRE.prev());
    tip.querySelector('.fre-next').addEventListener('click', () => FRE.next());

    els = {
      block, ring, tip,
      title: tip.querySelector('.fre-tip-title'),
      body:  tip.querySelector('.fre-tip-body'),
      dots:  tip.querySelector('.fre-dots'),
      back:  tip.querySelector('.fre-back'),
      next:  tip.querySelector('.fre-next'),
    };
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    document.addEventListener('keydown', onKey, true);
    return els;
  }

  function onKey(e) {
    if (!isActive()) return;
    if (e.key === 'Escape')      { e.preventDefault(); FRE.end(true); }
    else if (e.key === 'ArrowRight' || e.key === 'Enter') { e.preventDefault(); FRE.next(); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); FRE.prev(); }
  }

  function isActive() { return document.body.classList.contains('fre-on'); }

  function renderDots() {
    els.dots.innerHTML = STEPS.map((_, i) =>
      `<span class="fre-dot${i === idx ? ' on' : ''}"></span>`).join('');
  }

  let curTarget = null;

  function show() {
    const step = STEPS[idx];
    if (step.before) { try { step.before(); } catch (_) {} }
    // Let tab/panel switches settle before measuring.
    setTimeout(() => {
      els.title.innerHTML = step.title;
      els.body.innerHTML  = step.body;
      if (step.langPicker) injectLangPicker();
      els.back.style.visibility = idx === 0 ? 'hidden' : 'visible';
      els.next.textContent = idx === STEPS.length - 1 ? 'Finish' : 'Next';
      renderDots();
      curTarget = step.target ? document.querySelector(step.target) : null;
      reposition();
    }, 140);
  }

  // Language picker embedded in the FRE (writes LangPrefs live).
  function injectLangPicker() {
    const LABEL = { jshell:'JShell', java:'Java', javascript:'JavaScript', typescript:'TypeScript',
      csharp:'C#', fsharp:'F#', cpp:'C++' };
    const ICON = { jshell:'☕', java:'♨', javascript:'⬡', typescript:'◆', csharp:'◈', fsharp:'◈', cpp:'⚙' };
    const order = (window.LangPrefs && LangPrefs.ALL) || Object.keys(LABEL);
    // Default everything checked on first run so nothing feels hidden by surprise.
    const sel = new Set(window.LangPrefs ? LangPrefs.get() : order);
    const grid = document.createElement('div');
    grid.className = 'fre-lang-grid';
    grid.innerHTML = order.map(l => `
      <label class="lang-chip${sel.has(l) ? ' on' : ''}">
        <input type="checkbox" value="${l}"${sel.has(l) ? ' checked' : ''}>
        <span class="lang-chip-icon">${ICON[l] || ''}</span>${LABEL[l] || l}
      </label>`).join('');
    grid.addEventListener('change', () => {
      grid.querySelectorAll('.lang-chip').forEach(c =>
        c.classList.toggle('on', c.querySelector('input').checked));
      const chosen = Array.from(grid.querySelectorAll('input:checked')).map(x => x.value);
      if (window.LangPrefs) LangPrefs.set(chosen);
    });
    els.body.appendChild(grid);
    // Persist the default (all) immediately so the choice is recorded even if unchanged.
    if (window.LangPrefs && !LangPrefs.isSet()) LangPrefs.set(order);
  }

  function reposition() {
    if (!isActive() || !els) return;
    const step = STEPS[idx];
    const ring = els.ring, tip = els.tip;
    const vw = window.innerWidth, vh = window.innerHeight;
    const gap = 14, pad = 8;

    if (curTarget && curTarget.offsetParent !== null) {
      const r = curTarget.getBoundingClientRect();
      ring.style.display = 'block';
      ring.style.top    = (r.top - pad) + 'px';
      ring.style.left   = (r.left - pad) + 'px';
      ring.style.width  = (r.width + pad * 2) + 'px';
      ring.style.height = (r.height + pad * 2) + 'px';

      const tw = 340, th = tip.offsetHeight || 200;
      let side = step.place || 'bottom';
      const below = vh - r.bottom, above = r.top;
      if (side === 'auto') side = below > th + gap ? 'bottom' : 'top';
      if (side === 'bottom' && below < th + gap) side = 'top';
      if (side === 'top' && above < th + gap) side = 'bottom';

      let top, left;
      if (side === 'left') {
        left = r.left - tw - gap;
        top  = r.top + r.height / 2 - th / 2;
      } else if (side === 'right') {
        left = r.right + gap;
        top  = r.top + r.height / 2 - th / 2;
      } else if (side === 'top') {
        top  = r.top - th - gap;
        left = r.left + r.width / 2 - tw / 2;
      } else { // bottom
        top  = r.bottom + gap;
        left = r.left + r.width / 2 - tw / 2;
      }
      left = Math.max(gap, Math.min(left, vw - tw - gap));
      top  = Math.max(gap, Math.min(top, vh - th - gap));
      tip.style.left = left + 'px';
      tip.style.top  = top + 'px';
      tip.classList.remove('fre-centered');
    } else {
      // No target → dim everything, center the card.
      ring.style.display = 'none';
      tip.classList.add('fre-centered');
      tip.style.left = '';
      tip.style.top  = '';
    }
  }

  // ── Public API ───────────────────────────────────────────────────
  const FRE = {
    start() {
      build();
      idx = 0;
      document.body.classList.add('fre-on');
      show();
    },
    next() {
      if (idx >= STEPS.length - 1) { this.end(false); return; }
      idx++; show();
    },
    prev() {
      if (idx === 0) return;
      idx--; show();
    },
    end(skipped) {
      document.body.classList.remove('fre-on');
      if (els) els.ring.style.display = 'none';
      localStorage.setItem(LS_DONE, '1');
      localStorage.removeItem(LS_REPLAY);
      switchTab('notebook');
      void skipped;
    },
    // Called from Settings.
    replay() {
      localStorage.removeItem(LS_DONE);
      this.start();
    },
    setReplayNextLaunch(on) {
      if (on) localStorage.setItem(LS_REPLAY, '1');
      else localStorage.removeItem(LS_REPLAY);
    },
    replayPending() { return localStorage.getItem(LS_REPLAY) === '1'; },
    isDone() { return localStorage.getItem(LS_DONE) === '1'; }
  };

  window.FRE = FRE;

  // ── Auto-run on first launch ─────────────────────────────────────
  function auto() {
    const first  = localStorage.getItem(LS_DONE) !== '1';
    const replay = localStorage.getItem(LS_REPLAY) === '1';
    if (first || replay) {
      // Suppress the Welcome modal's own first-run auto-popup; the tour
      // owns the first-run moment. Mark it seen so it won't also fire.
      try {
        localStorage.setItem('barista.guide.seen', '1');
        localStorage.setItem('barista.guide.version', (window.Welcome && Welcome.version) || '');
      } catch (_) {}
      // Re-check at fire time so the flag can be cleared before we start
      // (e.g. by an automated walkthrough that drives the tour itself).
      setTimeout(() => {
        if (isActive()) return;
        const stillFirst  = localStorage.getItem(LS_DONE) !== '1';
        const stillReplay = localStorage.getItem(LS_REPLAY) === '1';
        if (stillFirst || stillReplay) FRE.start();
      }, 700);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', auto);
  } else {
    auto();
  }
})();
