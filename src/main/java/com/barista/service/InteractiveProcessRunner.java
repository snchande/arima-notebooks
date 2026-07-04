package com.barista.service;

import com.barista.model.BaristaSettings;
import com.barista.util.BaristaInput;
import com.barista.util.VariableInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared runner for the subprocess execution services (Node.js, TypeScript, C#/F#, C++,
 * compiled Java). Centralizes the process lifecycle so every language gets the same three
 * guarantees:
 *
 *   1. Interactive stdin — when an {@link InteractiveIO} is supplied, the runner mirrors
 *      JShell's model: it buffers program output, and when the program blocks on stdin it
 *      flushes the buffered output to the browser and shows the inline input prompt (the same
 *      terminal UX JShell already has). Detection uses an output-quiescence heuristic:
 *      subprocesses give no PTY "read requested" signal, so after the program has printed
 *      something and then gone quiet for {@link #QUIESCENCE_MS}, we treat it as waiting for
 *      input. This can occasionally prompt during a long silent computation that had already
 *      produced output — an accepted limitation of PTY-less detection. A cell that never
 *      prompts flushes nothing, so its output renders through the normal (non-interactive)
 *      path with charts/HTML/variables intact.
 *
 *   2. Runaway protection — a wall-clock compute budget ({@link BaristaSettings#getMaxExecutionTimeMs()})
 *      that a never-ending loop cannot exceed, plus an output-line cap
 *      ({@link BaristaSettings#getMaxOutputLines()}) that a runaway printer cannot exceed. Time
 *      spent blocked waiting on the user is NOT counted against the compute budget.
 *
 *   3. Manual stop — the run registers a canceller (destroy the process + unblock the input
 *      wait) so the Stop button can abort it immediately.
 *
 * With {@code io == null} the runner behaves like the previous inline code: stdin is closed
 * (EOF), output is captured in full, and the hard timeout applies — preserving the
 * non-interactive behaviour used by orchestration pipelines and MCP.
 */
@Service
public class InteractiveProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(InteractiveProcessRunner.class);

    /** Idle period after some output before we assume the program is waiting for input. */
    private static final long QUIESCENCE_MS = 350;
    /** Poll granularity for the run loop / input waits. */
    private static final long SLICE_MS = 120;
    /**
     * How long an already-answered follow-up prompt may sit unanswered before we send EOF by
     * closing the process's stdin. This lets runtimes that don't exit while stdin stays open
     * (notably Node.js/TypeScript, whose event loop is kept alive by an open stdin pipe) finish
     * after their last read, without cutting off a genuine multi-prompt program (the user has
     * this long to answer each follow-up). Exit-on-return languages (Java, C#, F#, C++) exit on
     * their own and never reach this.
     */
    private static final long TRAILING_PROMPT_TTL_MS = 4000;

    /**
     * The interactive console for the cell running on the current thread, if any. Bound by
     * {@code ShellController} around a subprocess execution and read by the runner — so the
     * language services need no new method parameters and non-interactive callers
     * (orchestration pipelines, MCP) simply see {@code null}.
     */
    private static final ThreadLocal<InteractiveIO> CURRENT = new ThreadLocal<>();

    public static void bind(InteractiveIO io) { CURRENT.set(io); }
    public static void unbind()               { CURRENT.remove(); }
    public static InteractiveIO current()     { return CURRENT.get(); }

    private final SettingsService settingsService;

    public InteractiveProcessRunner(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Result of a subprocess run. {@code stdout} is the output not already flushed to the browser. */
    public record ProcRun(String stdout, String stderr, int exitCode,
                          boolean timedOut, boolean truncated) {}

    /** Execution limits, resolved from settings. */
    public record ExecLimits(long maxExecutionTimeMs, int maxOutputLines) {}

    public ExecLimits limitsFromSettings() {
        BaristaSettings s = settingsService.getSettings();
        long t = s.getMaxExecutionTimeMs() > 0 ? s.getMaxExecutionTimeMs() : 30_000;
        int  l = s.getMaxOutputLines()    > 0 ? s.getMaxOutputLines()    : 1_000;
        return new ExecLimits(t, l);
    }

    /**
     * Run {@code pb} using the interactive console bound to the current thread (if any) and
     * limits from settings. This is the call the language services use — one line replaces
     * their previous start/capture/waitFor/destroy block.
     */
    public ProcRun run(ProcessBuilder pb) throws IOException, InterruptedException {
        return run(pb, current(), limitsFromSettings());
    }

    /** Convenience overload using limits derived from current settings. */
    public ProcRun run(ProcessBuilder pb, InteractiveIO io)
            throws IOException, InterruptedException {
        return run(pb, io, limitsFromSettings());
    }

    /**
     * Start {@code pb}, buffer/collect its output, service interactive stdin (if {@code io} is
     * non-null), and enforce the runaway guards. Never throws on a runaway program — it is
     * killed and reported via {@link ProcRun#timedOut()} / {@link ProcRun#truncated()}.
     */
    public ProcRun run(ProcessBuilder pb, InteractiveIO io, ExecLimits limits)
            throws IOException, InterruptedException {

        pb.redirectErrorStream(false);
        Process process = pb.start();

        if (io != null) {
            io.bindCanceller(() -> {
                BaristaInput.cancel();          // unblock a pending input wait
                process.destroyForcibly();      // kill the process
            });
        }

        // Output printed since the last flush-to-browser. For a non-prompting run this holds
        // the full stdout; once a prompt flushes it, it holds only the tail after that prompt.
        final StringBuilder pending = new StringBuilder();
        final StringBuilder stderr  = new StringBuilder();
        final AtomicInteger lineCount        = new AtomicInteger(0);
        final AtomicBoolean truncated        = new AtomicBoolean(false);
        final AtomicLong    lastOutputAt     = new AtomicLong(System.currentTimeMillis());
        final AtomicLong    outputSinceInput = new AtomicLong(0);

        Thread outT = readerThread(process.getInputStream(), pending, lineCount, truncated,
                lastOutputAt, outputSinceInput, limits, true);
        Thread errT = readerThread(process.getErrorStream(), stderr, lineCount, truncated,
                lastOutputAt, outputSinceInput, limits, false);
        outT.start();
        errT.start();

        boolean timedOut;
        if (io == null) {
            timedOut = waitNonInteractive(process, limits, truncated);
        } else {
            timedOut = waitInteractive(process, io, limits, truncated, pending,
                    lastOutputAt, outputSinceInput);
        }

        outT.join(2000);
        errT.join(2000);

        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            process.destroyForcibly();
            exitCode = -1;
        }

        if (truncated.get()) {
            synchronized (pending) {
                pending.append("\n… output truncated (limit ")
                       .append(limits.maxOutputLines()).append(" lines)\n");
            }
        }

        String tail;
        synchronized (pending) { tail = pending.toString(); }
        return new ProcRun(tail, stderr.toString(), exitCode, timedOut, truncated.get());
    }

    // ── Non-interactive: close stdin, wait with a hard budget ──────────────────────────
    private boolean waitNonInteractive(Process process, ExecLimits limits, AtomicBoolean truncated)
            throws InterruptedException {
        try { process.getOutputStream().close(); } catch (IOException ignore) {}
        long deadline = System.currentTimeMillis() + limits.maxExecutionTimeMs();
        while (process.isAlive()) {
            if (truncated.get()) { process.destroyForcibly(); return false; }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { process.destroyForcibly(); return true; }
            if (process.waitFor(Math.min(remaining, SLICE_MS), TimeUnit.MILLISECONDS)) break;
        }
        return false;
    }

    // ── Interactive: input-aware compute budget + quiescence-driven prompts ────────────
    private boolean waitInteractive(Process process, InteractiveIO io, ExecLimits limits,
                                    AtomicBoolean truncated, StringBuilder pending,
                                    AtomicLong lastOutputAt, AtomicLong outputSinceInput)
            throws InterruptedException {

        final long budget = limits.maxExecutionTimeMs();
        long computeElapsed = 0;
        boolean promptShown = false;
        long    promptShownAt = 0;
        int     answered = 0;
        boolean stdinClosed = false;
        OutputStream procIn = process.getOutputStream();

        while (process.isAlive()) {
            if (BaristaInput.isCancelled()) { process.destroyForcibly(); return false; }
            if (truncated.get())            { process.destroyForcibly(); return false; }

            if (promptShown) {
                // Waiting on the user — this time is excluded from the compute budget.
                String line = io.readLine(SLICE_MS);
                if (line != null) {
                    writeLine(procIn, line);
                    answered++;
                    promptShown = false;
                    outputSinceInput.set(0);
                    lastOutputAt.set(System.currentTimeMillis());
                } else if (!stdinClosed && answered >= 1
                        && System.currentTimeMillis() - promptShownAt >= TRAILING_PROMPT_TTL_MS) {
                    // A follow-up prompt has gone unanswered — likely the program already
                    // finished but its runtime keeps running while stdin is open. Send EOF so
                    // it can exit; a genuine reader gets end-of-input.
                    try { procIn.close(); } catch (IOException ignore) {}
                    stdinClosed = true;
                }
                continue;
            }

            // The program printed something and then went quiet → assume it wants input.
            long idle = System.currentTimeMillis() - lastOutputAt.get();
            if (outputSinceInput.get() > 0 && idle >= QUIESCENCE_MS) {
                // Pre-filled stdin-panel lines are fed silently — no browser prompt.
                String preFilled = BaristaInput.pollLine();
                if (preFilled != null) {
                    writeLine(procIn, preFilled);
                    outputSinceInput.set(0);
                    lastOutputAt.set(System.currentTimeMillis());
                    continue;
                }
                // Flush the output printed since the last prompt, then show the prompt.
                String flush;
                synchronized (pending) { flush = pending.toString(); pending.setLength(0); }
                io.requestInput(stripVarDump(flush));
                promptShown = true;
                promptShownAt = System.currentTimeMillis();
                continue;
            }

            // Real compute time — counts toward the budget.
            if (process.waitFor(SLICE_MS, TimeUnit.MILLISECONDS)) break;
            computeElapsed += SLICE_MS;
            if (computeElapsed >= budget) {
                process.destroyForcibly();
                return true;
            }
        }
        return false;
    }

    /**
     * Remove the VariableInspector dump block from live prompt text. Some runtimes (notably
     * Node.js with an async read) run the injected variable-dump trailer before the read
     * completes, so its sentinel block can land in the flushed prompt output — strip it so the
     * user only sees real program output.
     */
    private String stripVarDump(String s) {
        int begin = s.indexOf(VariableInspector.BEGIN_MARKER);
        if (begin < 0) return s;
        int end = s.indexOf(VariableInspector.END_MARKER, begin);
        if (end < 0) return s.substring(0, begin);            // dump straddles the flush boundary
        return s.substring(0, begin) + s.substring(end + VariableInspector.END_MARKER.length());
    }

    private void writeLine(OutputStream procIn, String line) {
        try {
            procIn.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            procIn.flush();
        } catch (IOException e) {
            // process closed stdin / exited between prompt and answer — ignore
        }
    }

    // ── Stream reader: accumulate into the buffer, update timing, enforce the line cap ─
    private Thread readerThread(InputStream is, StringBuilder sink,
                                AtomicInteger lineCount, AtomicBoolean truncated,
                                AtomicLong lastOutputAt, AtomicLong outputSinceInput,
                                ExecLimits limits, boolean isStdout) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (truncated.get()) continue; // drain and discard once capped
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);

                    int newlines = 0;
                    for (int i = 0; i < chunk.length(); i++) {
                        if (chunk.charAt(i) == '\n') newlines++;
                    }
                    if (lineCount.addAndGet(newlines) > limits.maxOutputLines()) {
                        truncated.set(true);
                        continue;
                    }

                    synchronized (sink) { sink.append(chunk); }

                    if (isStdout) {
                        lastOutputAt.set(System.currentTimeMillis());
                        outputSinceInput.incrementAndGet();
                    }
                }
            } catch (IOException ignore) {
                // stream closed as the process exited
            }
        });
        t.setDaemon(true);
        return t;
    }
}
