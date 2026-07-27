/**
 * Arima Notebooks — Guided Tutorial Player
 *
 * Turns any tutorial notebook into a narrated, multimodal walkthrough. Two modes:
 *   • Autopilot   — hands-free: narrates each step and auto-advances.
 *   • Interactive — self-paced: you drive with Prev/Next and narration plays per step.
 * Switch between them anytime. In either mode you can interrupt and ask a question by
 * voice or text — the question, with the current step as context, goes to the active AI
 * provider (POST /api/llm/chat) and the answer is shown and read back aloud.
 *
 * All audio is browser-native (Web Speech API): speechSynthesis for narration (TTS) and
 * SpeechRecognition for voice input (STT). No new server endpoints, no external hosts.
 */
const TutorialPlayer = (function () {
  const LANG_LABEL = { jshell:'JShell', java:'Java', javascript:'JavaScript', nodejs:'JavaScript',
    typescript:'TypeScript', csharp:'C#', fsharp:'F#', cpp:'C++', agent:'agent' };
  const AUTO_ADVANCE_MS = 900; // pause between steps in autopilot

  const synth = window.speechSynthesis || null;
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition || null;

  let nb = null;         // the tutorial notebook
  let cells = [];        // cells to present
  let idx = 0;           // current step index
  let mode = 'autopilot';
  let paused = false;    // narration/auto-advance paused
  let asking = false;    // a Q&A request is in flight or being spoken
  let history = [];      // running chat history for the AI
  let advanceTimer = null;
  let recog = null;      // active SpeechRecognition instance

  // ── Lifecycle ────────────────────────────────────────────────────────
  async function launch(tutorialId) {
    try {
      Arima.setStatus('Loading tutorial…');
      nb = await Arima.api('GET', `/notebooks/tutorials/${tutorialId}`);
    } catch (e) {
      // Fall back to a personal notebook id if it wasn't a tutorial.
      try { nb = await Arima.api('GET', `/notebooks/${tutorialId}`); }
      catch (e2) { Arima.setStatus('Could not load tutorial: ' + (e.message || e)); return; }
    }
    cells = (nb.cells || []).filter(c => (c.source || '').trim().length);
    if (!cells.length) { Arima.setStatus('This notebook has no content to present.'); return; }

    idx = 0; mode = 'autopilot'; paused = false; asking = false; history = [];
    document.getElementById('tp-name').textContent = nb.name || 'Tutorial';
    setModeUI('autopilot');
    open();
    renderStep(0);
    startNarration();
  }

  function open() {
    const o = document.getElementById('tutorial-player');
    o.classList.add('open');
    o.setAttribute('aria-hidden', 'false');
  }

  function close() {
    stopSpeaking();
    stopRecognition();
    clearTimeout(advanceTimer);
    const o = document.getElementById('tutorial-player');
    o.classList.remove('open');
    o.setAttribute('aria-hidden', 'true');
  }

  // ── Rendering ────────────────────────────────────────────────────────
  function renderStep(i) {
    idx = Math.max(0, Math.min(i, cells.length - 1));
    const cell = cells[idx];
    const stage = document.getElementById('tp-stage');
    const isMd = String(cell.type).toUpperCase() === 'MARKDOWN';

    let body;
    if (isMd) {
      body = `<div class="tp-md">${safeMarked(cell.source)}</div>`;
    } else {
      const lang = LANG_LABEL[cell.mode] || cell.mode || 'code';
      const out = (cell.output || '').trim();
      body = `<div class="tp-code-wrap">
        <div class="tp-code-lang">${esc(lang)}${cell.anchor ? ' · ' + esc(cell.anchor) : ''}</div>
        <pre class="tp-code"><code>${esc(cell.source)}</code></pre>
        ${out ? `<div class="tp-out-label">Output</div><pre class="tp-out">${esc(out)}</pre>` : ''}
      </div>`;
    }
    stage.innerHTML = body;
    stage.scrollTop = 0;

    document.getElementById('tp-step').textContent = `${idx + 1} / ${cells.length}`;
    document.getElementById('tp-progress-bar').style.width =
      `${((idx + 1) / cells.length) * 100}%`;
    document.getElementById('tp-prev').disabled = idx === 0;
    document.getElementById('tp-next').disabled = idx === cells.length - 1;
  }

  // Build the spoken narration for the current cell.
  function narrationFor(cell) {
    const isMd = String(cell.type).toUpperCase() === 'MARKDOWN';
    if (isMd) return mdToSpeech(cell.source);
    const lang = LANG_LABEL[cell.mode] || 'code';
    const out = (cell.output || '').trim();
    let s = `Here is a ${lang} code cell. Ask me to explain it if you'd like.`;
    if (out) s += ' It produces some output shown below the code.';
    return s;
  }

  // ── Narration (TTS) ──────────────────────────────────────────────────
  function startNarration() {
    if (!synth) { setCaption('Narration needs a browser with speech support (try Chrome).'); return; }
    paused = false;
    updatePlayBtn();
    speak(narrationFor(cells[idx]), onNarrationEnd);
  }

  function onNarrationEnd() {
    if (paused || asking) return;
    if (mode === 'autopilot' && idx < cells.length - 1) {
      advanceTimer = setTimeout(() => { if (!paused && !asking) next(true); }, AUTO_ADVANCE_MS);
    }
  }

  function speak(text, onend) {
    if (!synth) { if (onend) onend(); return; }
    stopSpeaking();
    setCaption(text);
    const u = new SpeechSynthesisUtterance(text);
    u.rate = 1.0; u.pitch = 1.0;
    u.onend = () => { if (onend) onend(); };
    u.onerror = () => { if (onend) onend(); };
    synth.speak(u);
  }

  function stopSpeaking() { try { synth && synth.cancel(); } catch {} }

  // ── Navigation & playback ────────────────────────────────────────────
  function next(fromAuto) {
    clearTimeout(advanceTimer);
    if (idx >= cells.length - 1) { if (!fromAuto) stopSpeaking(); return; }
    renderStep(idx + 1);
    if (!paused) startNarration(); else setCaption('');
  }
  function prev() {
    clearTimeout(advanceTimer);
    if (idx <= 0) return;
    renderStep(idx - 1);
    if (!paused) startNarration(); else setCaption('');
  }

  function togglePlay() {
    paused = !paused;
    if (paused) {
      stopSpeaking(); clearTimeout(advanceTimer);
    } else {
      startNarration();
    }
    updatePlayBtn();
  }

  function updatePlayBtn() {
    const b = document.getElementById('tp-play');
    b.textContent = paused ? '▶ Play' : '⏸ Pause';
  }

  // ── Mode switch ──────────────────────────────────────────────────────
  function setMode(m) {
    mode = m;
    setModeUI(m);
    clearTimeout(advanceTimer);
    if (m === 'autopilot' && !paused && !asking) {
      // resume hands-free from the current step
      startNarration();
    }
  }
  function setModeUI(m) {
    document.getElementById('tp-mode-autopilot').classList.toggle('active', m === 'autopilot');
    document.getElementById('tp-mode-autopilot').setAttribute('aria-selected', m === 'autopilot');
    document.getElementById('tp-mode-interactive').classList.toggle('active', m === 'interactive');
    document.getElementById('tp-mode-interactive').setAttribute('aria-selected', m === 'interactive');
    document.getElementById('tutorial-player').classList.toggle('interactive', m === 'interactive');
  }

  // ── Agentic Q&A (voice + text) ───────────────────────────────────────
  async function ask(text) {
    const q = (text || '').trim();
    if (!q) return;
    // Interrupt narration/auto-advance while we answer.
    const wasAutopilot = mode === 'autopilot' && !paused;
    asking = true;
    stopSpeaking();
    clearTimeout(advanceTimer);

    const log = document.getElementById('tp-qa-log');
    document.getElementById('tp-qa').hidden = false;
    log.insertAdjacentHTML('beforeend', `<div class="tp-msg tp-user">${esc(q)}</div>`);
    const thinking = document.createElement('div');
    thinking.className = 'tp-msg tp-assistant tp-thinking';
    thinking.textContent = '…';
    log.appendChild(thinking);
    log.scrollTop = log.scrollHeight;
    document.getElementById('tp-ask-input').value = '';

    const cell = cells[idx];
    const systemPrompt =
      `You are a friendly, concise tutorial guide inside Arima Notebooks. The learner is working ` +
      `through the tutorial "${nb.name}". They are on step ${idx + 1} of ${cells.length}. ` +
      `The current cell (${cell.type}${cell.mode ? '/' + cell.mode : ''}) contains:\n\n${cell.source}\n\n` +
      `Answer their question about this step clearly and briefly (2-4 sentences unless they ask for more). ` +
      `Plain prose suitable to be read aloud — avoid long code dumps.`;

    try {
      history.push({ role: 'user', content: q });
      const r = await Arima.api('POST', '/llm/chat', { message: q, systemPrompt, history });
      const answer = (r && r.response) || 'No response.';
      history.push({ role: 'assistant', content: answer });
      thinking.classList.remove('tp-thinking');
      thinking.innerHTML = safeMarked(answer);
      log.scrollTop = log.scrollHeight;

      // Read the answer aloud, then resume if we were on autopilot.
      asking = true;
      speak(stripMd(answer), () => {
        asking = false;
        if (wasAutopilot && mode === 'autopilot' && !paused) onNarrationEnd();
      });
    } catch (e) {
      thinking.classList.remove('tp-thinking');
      thinking.innerHTML = `<span class="tp-err">Couldn't reach the AI provider: ${esc(e.message || e)}. ` +
        `Check Settings → AI Provider.</span>`;
      asking = false;
    }
  }

  // ── Voice input (STT) ────────────────────────────────────────────────
  function toggleMic() {
    if (recog) { stopRecognition(); return; }
    if (!SR) { setCaption('Voice input needs a browser with speech recognition (try Chrome).'); return; }
    // Asking by voice interrupts the narration immediately.
    stopSpeaking(); clearTimeout(advanceTimer);
    recog = new SR();
    recog.lang = 'en-US';
    recog.interimResults = true;
    recog.continuous = false;
    const micBtn = document.getElementById('tp-mic');
    const input = document.getElementById('tp-ask-input');
    micBtn.classList.add('listening');
    recog.onresult = (e) => {
      let t = '';
      for (let i = 0; i < e.results.length; i++) t += e.results[i][0].transcript;
      input.value = t;
      if (e.results[e.results.length - 1].isFinal) {
        stopRecognition();
        ask(t);
      }
    };
    recog.onerror = () => stopRecognition();
    recog.onend = () => { micBtn.classList.remove('listening'); recog = null; };
    try { recog.start(); } catch { stopRecognition(); }
  }
  function stopRecognition() {
    document.getElementById('tp-mic')?.classList.remove('listening');
    if (recog) { try { recog.stop(); } catch {} recog = null; }
  }

  // ── Helpers ──────────────────────────────────────────────────────────
  function setCaption(t) { const c = document.getElementById('tp-caption'); if (c) c.textContent = t || ''; }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function safeMarked(md) {
    try { return window.marked ? marked.parse(md || '') : esc(md); }
    catch { return esc(md); }
  }
  // Strip markdown to plain speakable text.
  function stripMd(md) {
    return String(md || '')
      .replace(/```[\s\S]*?```/g, ' (code) ')
      .replace(/`([^`]+)`/g, '$1')
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/^#{1,6}\s*/gm, '')
      .replace(/[*_>#|]/g, '')
      .replace(/\r?\n{2,}/g, '. ')
      .replace(/\r?\n/g, ' ')
      .replace(/\s{2,}/g, ' ')
      .trim();
  }
  function mdToSpeech(md) {
    const s = stripMd(md);
    return s.length > 1200 ? s.slice(0, 1200) + '…' : s;
  }

  // ── Wiring ───────────────────────────────────────────────────────────
  function init() {
    document.getElementById('tp-close')?.addEventListener('click', close);
    document.getElementById('tp-prev')?.addEventListener('click', () => prev());
    document.getElementById('tp-next')?.addEventListener('click', () => next(false));
    document.getElementById('tp-play')?.addEventListener('click', togglePlay);
    document.getElementById('tp-mode-autopilot')?.addEventListener('click', () => setMode('autopilot'));
    document.getElementById('tp-mode-interactive')?.addEventListener('click', () => setMode('interactive'));
    document.getElementById('tp-mic')?.addEventListener('click', toggleMic);
    document.getElementById('tp-ask-send')?.addEventListener('click', () =>
      ask(document.getElementById('tp-ask-input').value));

    const input = document.getElementById('tp-ask-input');
    input?.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); ask(input.value); }
      e.stopPropagation(); // don't let Space/arrows drive playback while typing
    });
    // Focusing the ask box interrupts autopilot so the learner can think.
    input?.addEventListener('focus', () => {
      if (mode === 'autopilot' && !paused && !asking) { stopSpeaking(); clearTimeout(advanceTimer); }
    });

    document.addEventListener('keydown', (e) => {
      const o = document.getElementById('tutorial-player');
      if (!o || !o.classList.contains('open')) return;
      if (e.target && e.target.id === 'tp-ask-input') return;
      if (e.key === 'Escape') { close(); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); next(false); }
      else if (e.key === 'ArrowLeft')  { e.preventDefault(); prev(); }
      else if (e.key === ' ')          { e.preventDefault(); togglePlay(); }
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { launch, close };
})();
window.TutorialPlayer = TutorialPlayer;
