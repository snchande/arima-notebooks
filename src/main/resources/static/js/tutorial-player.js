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
  let active = false;    // player open? hard gate — nothing narrates when false

  // Narration voice + delivery. Chosen from the best voice the browser exposes;
  // the learner can override in the header. Persisted across sessions.
  let voices = [];
  let chosenVoice = null;
  let rate = parseFloat(localStorage.getItem('arima.tts.rate') || '0.95') || 0.95;

  // The player walks a list of "slides": an objective intro first, then one per cell.
  let slides = [];

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

    // Slide 0 is a synthesized objective overview; the rest are the cells.
    slides = [{ kind: 'intro' }].concat(cells.map(c => ({ kind: 'cell', cell: c })));

    idx = 0; mode = 'autopilot'; paused = false; asking = false; history = [];
    document.getElementById('tp-name').textContent = nb.name || 'Tutorial';
    setModeUI('autopilot');
    open();
    renderStep(0);
    startNarration();
  }

  // Pull section headings from the markdown cells → "what you'll learn" bullets.
  function learningPoints() {
    const pts = [];
    cells.forEach(c => {
      if (String(c.type).toUpperCase() !== 'MARKDOWN') return;
      (c.source || '').split('\n').forEach(line => {
        const m = line.match(/^#{1,3}\s+(.+)/);
        if (!m) return;
        let t = m[1].replace(/[*_`]/g, '').replace(/^\d+[.)]\s*/, '').trim();
        if (!t) return;
        if (nb.name && t.toLowerCase() === nb.name.toLowerCase()) return; // skip the title
        if (/^(next|you did it|you'?re all set|recap|prerequisites?)/i.test(t)) return;
        pts.push(t);
      });
    });
    // De-dupe, cap to keep the intro tight.
    return [...new Set(pts)].slice(0, 6);
  }

  function introHtml() {
    const pts = learningPoints();
    const desc = (nb.description || '').trim();
    const learn = pts.length
      ? `<div class="tp-intro-learn-label">By the end, you'll be able to:</div>
         <ul class="tp-intro-learn">${pts.map(p => `<li>${esc(p)}</li>`).join('')}</ul>`
      : '';
    return `<div class="tp-intro">
      <div class="tp-intro-eyebrow">🎧 Guided tutorial</div>
      <h1 class="tp-intro-title">${esc(nb.name || 'Tutorial')}</h1>
      ${desc ? `<p class="tp-intro-obj">${esc(desc)}</p>` : ''}
      ${learn}
      <div class="tp-intro-meta">${cells.length} step${cells.length === 1 ? '' : 's'} ·
        narrated hands-free in <b>Autopilot</b>, or drive it yourself in <b>Interactive</b> ·
        ask a question any time.</div>
    </div>`;
  }

  function introNarration() {
    const pts = learningPoints();
    let s = `Welcome to ${nb.name || 'this tutorial'}. `;
    if (nb.description) s += stripMd(nb.description) + ' ';
    if (pts.length) s += `By the end, you'll be able to: ${pts.slice(0, 5).join('; ')}. `;
    s += `There are ${cells.length} steps. Let's begin.`;
    return s;
  }

  function open() {
    active = true;
    const o = document.getElementById('tutorial-player');
    o.classList.add('open');
    o.setAttribute('aria-hidden', 'false');
  }

  function close() {
    active = false;                 // hard gate: nothing may narrate once closed
    paused = true; asking = false;
    clearTimeout(advanceTimer); advanceTimer = null;
    stopSpeaking();
    stopRecognition();
    const o = document.getElementById('tutorial-player');
    o.classList.remove('open');
    o.setAttribute('aria-hidden', 'true');
  }

  // ── Rendering ────────────────────────────────────────────────────────
  function renderStep(i) {
    idx = Math.max(0, Math.min(i, slides.length - 1));
    const slide = slides[idx];
    const stage = document.getElementById('tp-stage');

    if (slide.kind === 'intro') {
      stage.innerHTML = introHtml();
    } else {
      const cell = slide.cell;
      const isMd = String(cell.type).toUpperCase() === 'MARKDOWN';
      if (isMd) {
        stage.innerHTML = `<div class="tp-md">${safeMarked(cell.source)}</div>`;
      } else {
        const lang = LANG_LABEL[cell.mode] || cell.mode || 'code';
        const out = (cell.output || '').trim();
        stage.innerHTML = `<div class="tp-code-wrap">
          <div class="tp-code-lang">${esc(lang)}${cell.anchor ? ' · ' + esc(cell.anchor) : ''}</div>
          <pre class="tp-code" id="tp-code-block"><code>${esc(stripAnnotations(cell.source))}</code></pre>
          ${out ? `<div class="tp-out-label">Output</div><pre class="tp-out">${esc(out)}</pre>` : ''}
        </div>`;
      }
    }
    stage.scrollTop = 0;

    document.getElementById('tp-step').textContent =
      idx === 0 ? 'Overview' : `${idx} / ${cells.length}`;
    document.getElementById('tp-progress-bar').style.width =
      `${((idx + 1) / slides.length) * 100}%`;
    document.getElementById('tp-prev').disabled = idx === 0;
    document.getElementById('tp-next').disabled = idx === slides.length - 1;
  }

  // Build the spoken narration for the current slide.
  function narrationFor(i) {
    const slide = slides[i];
    if (slide.kind === 'intro') return introNarration();
    const cell = slide.cell;
    if (String(cell.type).toUpperCase() === 'MARKDOWN') return mdToSpeech(cell.source);

    // Code cell — describe what the code does, drawing on its own comments.
    const lang = LANG_LABEL[cell.mode] || 'code';
    const desc = codeDescription(cell.source);
    const out = (cell.output || '').trim();
    let s = `Now look at this ${lang} code. `;
    if (desc) s += desc + ' ';
    else s += 'Read through the highlighted code — ask me to explain any part. ';
    if (out) s += 'Below the code, you can see the output it produces.';
    return s.trim();
  }

  // Derive a spoken description of a code cell from its //@ description annotation
  // or its leading comment lines (so we refer to what the code actually does).
  function codeDescription(source) {
    const lines = (source || '').split('\n');
    for (const line of lines) {                       // //@ description: ...
      const m = line.trim().match(/^\/\/@\s*description:\s*(.+)/i);
      if (m) return m[1].trim();
    }
    const comments = [];
    for (const raw of lines) {
      const t = raw.trim();
      if (t.startsWith('//@')) continue;              // annotation, not prose
      const m = t.match(/^(#|\/\/)\s?(.*)/);          // # …  or  // …
      if (m && m[2].trim()) comments.push(m[2].trim());
      else if (t && !m) break;                        // stop at first real code line
    }
    if (comments.length) return comments.slice(0, 3).join('. ') + '.';
    return '';
  }

  // Strip leading //@ anchor/depends/description annotation lines from displayed code.
  function stripAnnotations(source) {
    const lines = (source || '').split('\n');
    let i = 0;
    while (i < lines.length && lines[i].trim().startsWith('//@')) i++;
    return lines.slice(i).join('\n');
  }

  // ── Narration (TTS) ──────────────────────────────────────────────────
  function startNarration() {
    paused = false;
    updatePlayBtn();
    const text = narrationFor(idx);
    highlightCode(slides[idx].kind === 'cell'); // pulse the code block while we talk about it
    if (!synth) { setCaption(text); return; }    // no speech → still show the teleprompter text
    speak(text, onNarrationEnd);
  }

  // Add/remove a "being discussed" highlight on the current code block.
  function highlightCode(on) {
    const block = document.getElementById('tp-code-block');
    if (block) block.classList.toggle('tp-code-speaking', !!on);
  }

  function onNarrationEnd() {
    if (!active || paused || asking) return;
    if (mode === 'autopilot' && idx < slides.length - 1) {
      advanceTimer = setTimeout(() => { if (active && !paused && !asking) next(true); }, AUTO_ADVANCE_MS);
    }
  }

  // Speak text one sentence at a time — natural pacing, and it sidesteps the
  // Chrome long-utterance cutoff. The chosen voice + rate apply to every chunk.
  let speakToken = 0;
  function speak(text, onend) {
    if (!synth || !active) { if (onend) onend(); return; }
    stopSpeaking();
    const chunks = chunkSentences(text);
    setCaptionChunks(chunks, -1);
    const token = ++speakToken;
    let i = 0;
    const sayNext = () => {
      // Stop immediately if superseded, cancelled, or the player has closed.
      if (token !== speakToken || !active) return;
      if (i >= chunks.length) { setCaptionChunks(chunks, -1); if (onend) onend(); return; }
      const cur = i++;
      setCaptionChunks(chunks, cur);   // teleprompter: highlight the sentence being spoken
      const u = new SpeechSynthesisUtterance(chunks[cur]);
      if (chosenVoice) u.voice = chosenVoice;
      u.rate = rate; u.pitch = 1.0; u.volume = 1.0;
      u.onend = sayNext;
      u.onerror = sayNext;
      synth.speak(u);
    };
    sayNext();
  }

  // Hard stop. Bump the token so any in-flight queue aborts, then cancel — twice,
  // because Chrome occasionally lets an already-buffered (remote) utterance slip
  // through a single cancel().
  function stopSpeaking() {
    speakToken++;
    if (!synth) return;
    try { synth.cancel(); } catch {}
    setTimeout(() => { try { synth.cancel(); } catch {} }, 40);
  }

  function chunkSentences(text) {
    const raw = String(text || '').split(/(?<=[.!?])\s+/);
    const out = [];
    let buf = '';
    for (const s of raw) {
      if ((buf + ' ' + s).trim().length > 240) { if (buf) out.push(buf.trim()); buf = s; }
      else buf = buf ? buf + ' ' + s : s;
    }
    if (buf.trim()) out.push(buf.trim());
    return out.length ? out : [String(text || '')];
  }

  // ── Voice selection ──────────────────────────────────────────────────
  function loadVoices() {
    if (!synth) return;
    voices = synth.getVoices() || [];
    if (!voices.length) return;
    const savedName = localStorage.getItem('arima.tts.voice');
    const saved = savedName && voices.find(v => v.name === savedName);
    chosenVoice = saved || pickBestVoice(voices);
    populateVoicePicker();
  }

  // Rank the available voices so the most natural one wins by default. The big
  // levers: neural/"Natural" voices and non-local (streamed) voices — both are far
  // less robotic than the bundled SAPI "Desktop" voices.
  function rankVoice(v) {
    const n = (v.name || '').toLowerCase();
    const lang = (v.lang || '').toLowerCase();
    let s = 0;
    if (!/^en\b|^en[-_]/.test(lang)) s -= 120;         // strongly prefer English
    if (/natural|neural/.test(n))    s += 120;          // Win11 / neural voices — the best
    if (v.localService === false)    s += 70;           // streamed voices are far smoother
    if (/google/.test(n))            s += 60;           // Chrome's Google voices
    if (/online/.test(n))            s += 45;
    if (/(aria|jenny|guy|ava|emma|sonia|libby|michelle|ryan|natasha|clara|andrew|brian)/.test(n)) s += 30;
    if (lang === 'en-us')            s += 12;
    if (/desktop/.test(n))           s -= 60;           // old SAPI desktop voices — robotic
    if (/(david|zira|mark|hazel|espeak)/.test(n)) s -= 40;
    return s;
  }
  function pickBestVoice(vs) {
    return vs.slice().sort((a, b) => rankVoice(b) - rankVoice(a))[0] || null;
  }

  function populateVoicePicker() {
    const sel = document.getElementById('tp-voice');
    if (!sel) return;
    const ranked = voices.slice().sort((a, b) => rankVoice(b) - rankVoice(a));
    sel.innerHTML = ranked.map(v =>
      `<option value="${esc(v.name)}"${chosenVoice && v.name === chosenVoice.name ? ' selected' : ''}>` +
      `${esc(shortVoiceName(v))}</option>`).join('');
    const rateSel = document.getElementById('tp-rate');
    if (rateSel) rateSel.value = String(rate);
  }
  function shortVoiceName(v) {
    // Trim the vendor noise so the dropdown reads cleanly.
    let n = (v.name || '').replace(/^Microsoft\s+/i, '').replace(/\s*\(Natural\)/i, ' · Natural')
      .replace(/Online\s*/i, '').replace(/Google\s+/i, 'Google ');
    return `${n} (${v.lang})`;
  }

  // ── Navigation & playback ────────────────────────────────────────────
  function next(fromAuto) {
    clearTimeout(advanceTimer);
    if (idx >= slides.length - 1) { if (!fromAuto) stopSpeaking(); return; }
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

    const slide = slides[idx];
    const cell = slide && slide.kind === 'cell' ? slide.cell : null;
    const context = cell
      ? `They are on step ${idx} of ${cells.length}. The current cell ` +
        `(${cell.type}${cell.mode ? '/' + cell.mode : ''}) contains:\n\n${cell.source}\n\n`
      : `They are on the overview slide (what the tutorial covers).\n\n`;
    const systemPrompt =
      `You are a friendly, concise tutorial guide inside Arima Notebooks. The learner is working ` +
      `through the tutorial "${nb.name}". ` + context +
      `Answer their question clearly and briefly (2-4 sentences unless they ask for more). ` +
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
  // Teleprompter: show the whole narration with the sentence being spoken highlighted.
  function setCaptionChunks(chunks, activeIdx) {
    const c = document.getElementById('tp-caption');
    if (!c) return;
    c.innerHTML = chunks.map((chunk, i) =>
      i === activeIdx ? `<span class="tp-cap-active">${esc(chunk)}</span>` : esc(chunk)
    ).join(' ');
    const active = c.querySelector('.tp-cap-active');
    if (active) active.scrollIntoView({ block: 'nearest' });
  }
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

    // Voice + speed pickers.
    if (synth) {
      loadVoices();
      // Voices load asynchronously in Chrome — refresh when they arrive.
      synth.onvoiceschanged = loadVoices;
    }
    document.getElementById('tp-voice')?.addEventListener('change', (e) => {
      chosenVoice = voices.find(v => v.name === e.target.value) || chosenVoice;
      try { localStorage.setItem('arima.tts.voice', e.target.value); } catch {}
      // Preview the newly chosen voice.
      speak('This is how the narration will sound.', null);
    });
    document.getElementById('tp-rate')?.addEventListener('change', (e) => {
      rate = parseFloat(e.target.value) || 0.95;
      try { localStorage.setItem('arima.tts.rate', String(rate)); } catch {}
      speak('This is the new narration speed.', null);
    });

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

    // Stop narration if the tab is hidden or the page is navigated/closed.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden && active) { paused = true; stopSpeaking(); clearTimeout(advanceTimer); updatePlayBtn(); }
    });
    window.addEventListener('pagehide', () => { active = false; stopSpeaking(); });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  return { launch, close };
})();
window.TutorialPlayer = TutorialPlayer;
