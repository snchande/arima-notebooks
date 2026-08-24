/**
 * Arima Notebooks — Landing dashboard (home).
 *
 * Shown in the Notebook tab whenever no notebook is open. A "home page" for the
 * agentic era: an ask box (chat), your recent notebooks, the MCP tool catalog
 * (every notebook/cell is agent-invokable), what's new, and a language tidbit/quiz.
 */
const Home = (function () {
  const MCP_TOOLS = [
    ['barista_list_notebooks', 'List all notebooks'],
    ['barista_read_notebook', 'Read a notebook’s cells + anchors'],
    ['barista_execute_code', 'Run code in a JShell session'],
    ['barista_run_pipeline', 'Run a pipeline, resolving dependencies'],
    ['barista_search_cells', 'Search cells by anchor or content'],
    ['barista_load_module', 'Load a cell module into a session'],
    ['barista_create_notebook', 'Create a new notebook'],
    ['barista_append_cell', 'Append (and optionally run) a cell'],
    ['barista_list_agents', 'List agents & skills'],
    ['barista_run_agent', 'Run an agent against a task'],
  ];

  const TIDBITS = [
    ['☕', 'Why "Barista"?', 'Arima is built on <b>Java</b> — and like a barista serving what you order, the <b>Barista</b> engine is the pure-Java core that serves every capability: running cells, pipelines, packages, and the MCP server. Java is the coffee; Barista brews it.'],
    ['🐍', 'Python', 'Python is named after Monty Python’s Flying Circus — not the snake. Install any PyPI package from the Packages tab and <code>import</code> it.'],
    ['☕', 'JShell', 'JShell (Java’s REPL) shares state across cells in a notebook — declare a variable once, use it everywhere below.'],
    ['⬡', 'JavaScript', 'Every JS cell runs in a fresh Node.js process; installed npm modules are on NODE_PATH so <code>require()</code> just works.'],
    ['◆', 'TypeScript', 'TS cells use Node’s built-in type-stripping (22.6+) — no build step. Add <code>tsc</code> for full type-checking.'],
    ['◈', 'C# / F#', 'C# and F# run on the .NET SDK; NuGet packages are auto-injected — no <code>#r</code> needed for C#.'],
    ['⚙', 'C++', '26 standard headers are pre-included in every C++ cell, and MSVC/GCC/Clang are auto-detected.'],
    ['🤖', 'Agents', 'An agent in Arima <em>is</em> a notebook. You can run it from the Agents tab, in a pipeline, or over MCP.'],
  ];

  // Language count is derived from LangPrefs (single source of truth) so it
  // auto-updates as new language modules are added — never hard-coded.
  function langCount() { return (window.LangPrefs && LangPrefs.count()) || 8; }
  function langWord()  { return (window.LangPrefs && LangPrefs.countWord()) || 'eight'; }

  function buildQuiz() {
    const n = langCount(), w = langWord();
    const cap = w.charAt(0).toUpperCase() + w.slice(1);
    return [
    { q: `How many languages can a single Arima notebook mix, cell by cell?`,
      options: ['Just one', 'Three', cap], answer: 2,
      explain: `${cap} (${n}) languages — switchable per cell.` },
    { q: 'How does an external AI agent drive Arima?',
      options: ['Screen scraping', 'The built-in MCP server', 'It can’t'], answer: 1,
      explain: 'Arima exposes an MCP server — notebooks, cells, pipelines, packages, and agents are all tool-invokable.' },
    { q: 'What links cells into a runnable workflow?',
      options: ['//@ anchor & //@ depends', 'Magic', 'Cell order only'], answer: 0,
      explain: `Name a cell with //@ anchor and declare //@ depends — Arima topologically runs the graph across all ${langWord()} languages.` },
    { q: 'Arima is nicknamed "brewed by Barista". Why?',
      options: ['A coffee sponsor', 'It’s built on Java (coffee); Barista is the Java engine', 'Random codename'], answer: 1,
      explain: 'Arima runs on Java. The Barista engine is the pure-Java core that serves every capability — Java is the coffee, Barista brews it.' },
    { q: 'Where do Python libraries come from in Arima?',
      options: ['Bundled only', 'PyPI, via the Packages tab', 'You can’t add any'], answer: 1,
      explain: 'Install any package from the Python Package Index (PyPI) in the Packages → PyPI tab; it’s isolated on PYTHONPATH.' },
    ];
  }

  function esc(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

  // ── Learn progress (points + no-repeat) persisted locally ────────────
  function lget(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } }
  function lset(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  function points() { return lget('arima.home.points', 0); }
  function addPoints(n) { lset('arima.home.points', points() + n); }
  // Pick an unseen index from a list; when all seen, reset and start over.
  function pickUnseen(key, len) {
    let seen = lget(key, []);
    if (seen.length >= len) seen = [];
    const pool = []; for (let i = 0; i < len; i++) if (!seen.includes(i)) pool.push(i);
    const idx = pool[Math.floor(Math.random() * pool.length)];
    seen.push(idx); lset(key, seen);
    return idx;
  }

  function show() {
    const h = document.getElementById('home-view');
    if (h) { h.hidden = false; render(); }
    // Hide the (possibly empty) cell area so Home fills the canvas even with notebooks open.
    const cc = document.getElementById('cells-container'); if (cc) cc.style.display = 'none';
    // Highlight the Home tab.
    document.querySelectorAll('.tab-nav .tab-btn').forEach(b => b.classList.remove('active'));
    document.getElementById('btn-home-tab')?.classList.add('active');
  }
  function hide() {
    const h = document.getElementById('home-view'); if (h) h.hidden = true;
    const cc = document.getElementById('cells-container'); if (cc) cc.style.display = '';
    document.getElementById('btn-home-tab')?.classList.remove('active');
  }
  // Return to Home from anywhere (brand click / Home tab), keeping open notebooks in their tabs.
  function go() {
    document.querySelector('.tab-btn[data-tab="notebook"]')?.click();
    show();
  }

  async function render() {
    const el = document.getElementById('home-view');
    if (!el) return;

    el.innerHTML = `
      <div class="home-wrap">
        <div class="home-hero">
          <div class="home-logo">☕</div>
          <h1>Welcome to <span>Arima Notebooks</span></h1>
          <p class="home-sub">${langCount()} languages · one canvas · built for the agentic era — people and AI agents build on the same living code. Ask, build, or open a notebook to begin.</p>
          <form class="home-ask" id="home-ask-form">
            <input id="home-ask-input" class="home-ask-input" type="text" autocomplete="off"
              placeholder="Ask Arima to build a notebook, explain code, or answer a question…">
            <button class="home-ask-btn" type="submit">Ask</button>
          </form>
          <div class="home-quick">
            <button class="home-chip" data-new>＋ New notebook</button>
            <button class="home-chip" data-view="tutorials">📚 Tutorials</button>
            <button class="home-chip" data-view="config">⚙️ Configuration</button>
            <button class="home-chip" data-tab="agents">🤖 Agents</button>
          </div>
        </div>

        <div class="home-grid">
          <section class="home-card home-recent">
            <div class="home-card-hd"><h2>Recent notebooks</h2><button class="home-link" data-new>＋ New</button></div>
            <div id="home-recent-list" class="home-recent-list"><div class="home-muted">Loading…</div></div>
          </section>

          <section class="home-card home-whatsnew">
            <div class="home-card-hd"><h2>What’s new</h2><span class="home-badge">v4.0.0</span></div>
            <div class="home-wn-title">🐍 Python — the eighth language</div>
            <ul class="home-wn-list">
              <li>Python cells + <b>PyPI</b> package manager</li>
              <li>Guided tutorial player with narration</li>
              <li>Data science, metrics &amp; reporting tutorials</li>
            </ul>
            <button class="home-link" id="home-wn-more">See all updates →</button>
          </section>

          <section class="home-card home-mcp">
            <div class="home-card-hd"><h2>MCP tool catalog</h2><span class="home-badge agent">agentic</span></div>
            <p class="home-muted">Every notebook &amp; cell is invokable by agents over the built-in MCP server
              (<code>/api/mcp/sse</code>). Point Claude Desktop / Claude Code at it:</p>
            <div class="home-mcp-list">
              ${MCP_TOOLS.map(([n, d]) => `<div class="home-mcp-tool"><code>${esc(n)}</code><span>${esc(d)}</span></div>`).join('')}
            </div>
          </section>

          <section class="home-card home-learn" id="home-learn"></section>
        </div>
      </div>`;

    wireCommon();
    renderLearn();
    loadRecent();
  }

  function wireCommon() {
    const form = document.getElementById('home-ask-form');
    form?.addEventListener('submit', (e) => { e.preventDefault(); ask(document.getElementById('home-ask-input').value); });
    document.querySelectorAll('#home-view [data-new]').forEach(b => b.addEventListener('click', () =>
      document.getElementById('btn-new')?.click()));
    document.querySelectorAll('#home-view [data-view]').forEach(b => b.addEventListener('click', () =>
      window.SettingsNav && SettingsNav.show(b.dataset.view)));
    document.querySelectorAll('#home-view [data-tab]').forEach(b => b.addEventListener('click', () =>
      document.querySelector(`.tab-btn[data-tab="${b.dataset.tab}"]`)?.click()));
    document.getElementById('home-wn-more')?.addEventListener('click', () => window.Welcome && Welcome.openReleaseNotes());
  }

  // "Did you know?" + quiz — no-repeat tidbits, points, and Next navigation.
  function renderLearn() {
    const card = document.getElementById('home-learn');
    if (!card) return;
    const quizzes = buildQuiz();
    const tIdx = pickUnseen('arima.home.tidbitSeen', TIDBITS.length);
    const qIdx = pickUnseen('arima.home.quizSeen', quizzes.length);
    const tidbit = TIDBITS[tIdx];
    const quiz = quizzes[qIdx];

    card.innerHTML = `
      <div class="home-card-hd">
        <h2>Did you know?</h2>
        <span class="home-points" title="Points earned from quizzes">⭐ ${points()} pts</span>
      </div>
      <div class="home-tidbit"><span class="home-tidbit-icon">${tidbit[0]}</span>
        <div><b>${esc(tidbit[1])}</b><p>${tidbit[2]}</p></div>
      </div>
      <div class="home-quiz" id="home-quiz">
        <div class="home-quiz-q">${esc(quiz.q)}</div>
        <div class="home-quiz-opts">
          ${quiz.options.map((o, i) => `<button class="home-quiz-opt" data-i="${i}">${esc(o)}</button>`).join('')}
        </div>
        <div class="home-quiz-result" hidden></div>
      </div>
      <div class="home-learn-foot"><button class="home-link" id="home-next-insight">Next insight →</button></div>`;

    let answered = false;
    card.querySelectorAll('.home-quiz-opt').forEach(opt => opt.addEventListener('click', () => {
      if (answered) return;
      answered = true;
      const i = Number(opt.dataset.i);
      const correct = i === quiz.answer;
      card.querySelectorAll('.home-quiz-opt').forEach((o, j) => {
        o.disabled = true;
        if (j === quiz.answer) o.classList.add('correct');
        else if (j === i) o.classList.add('wrong');
      });
      if (correct) addPoints(10);
      const res = card.querySelector('.home-quiz-result');
      const badge = card.querySelector('.home-points');
      if (badge) badge.textContent = `⭐ ${points()} pts`;
      if (res) { res.hidden = false; res.innerHTML = (correct ? '✅ Correct! +10 pts. ' : '❌ Not quite. ') + esc(quiz.explain); }
    }));
    card.querySelector('#home-next-insight')?.addEventListener('click', renderLearn);
  }

  function ask(q) {
    q = (q || '').trim();
    if (!q) return;
    document.getElementById('ai-fab')?.click(); // open the AI panel
    setTimeout(() => {
      const inp = document.getElementById('ai-input');
      if (inp) { inp.value = q; document.getElementById('btn-ai-send')?.click(); }
    }, 300);
  }

  async function loadRecent() {
    const list = document.getElementById('home-recent-list');
    if (!list) return;
    try {
      const nbs = (await Arima.api('GET', '/notebooks')) || [];
      nbs.sort((a, b) => String(b.modified || '').localeCompare(String(a.modified || '')));
      const recent = nbs.slice(0, 6);
      if (!recent.length) {
        list.innerHTML = `<div class="home-muted">No notebooks yet — <button class="home-link" data-new>create your first</button>.</div>`;
        list.querySelector('[data-new]')?.addEventListener('click', () => document.getElementById('btn-new')?.click());
        return;
      }
      const icon = { jshell:'☕', java:'♨', javascript:'⬡', nodejs:'⬡', typescript:'◆', csharp:'◈', fsharp:'◈', cpp:'⚙', python:'🐍', agent:'🤖' };
      list.innerHTML = recent.map(nb => {
        const lang = nb.metadata?.language || 'jshell';
        return `<button class="home-recent-item" data-id="${esc(nb.id)}">
          <span class="home-recent-icon">${icon[lang] || '📓'}</span>
          <span class="home-recent-main">
            <span class="home-recent-name">${esc(nb.name)}</span>
            <span class="home-recent-meta">${nb.cellCount || 0} cell${nb.cellCount === 1 ? '' : 's'}</span>
          </span></button>`;
      }).join('');
      list.querySelectorAll('.home-recent-item').forEach(it => it.addEventListener('click', () =>
        window.NotebookEditor && NotebookEditor.loadNotebook(it.dataset.id)));
    } catch (e) {
      list.innerHTML = `<div class="home-muted">Couldn’t load notebooks.</div>`;
    }
  }

  // On first load, if no notebook is open yet, show the dashboard.
  function init() {
    if (!document.querySelector('#cells-container .cell')) show();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => setTimeout(init, 150));
  else setTimeout(init, 150);

  return { render, show, hide, go };
})();
window.Home = Home;
