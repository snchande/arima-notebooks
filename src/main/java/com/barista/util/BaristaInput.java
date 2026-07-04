package com.barista.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Interactive stdin provider for Arima Notebooks.
 *
 * Supports two modes:
 *
 *  1. Pre-filled (non-interactive):
 *     Call provide(lines) before execution.  Lines are consumed in order without blocking.
 *     Any lines typed in the cell's "Stdin" panel are fed this way.
 *
 *  2. Interactive (real-time):
 *     When the queue is empty and code requests more input, the inputNeededCallback is
 *     fired so the server can notify the browser.  Execution then blocks until the user
 *     types a line and sends it via WebSocket (addLine).  The browser echoes the typed
 *     text and shows subsequent output as it arrives, giving a real terminal feel.
 *
 * The same line queue backs both the JShell {@link #STDIN} stream and the subprocess
 * languages (via {@code InteractiveProcessRunner}, which calls {@link #takeLine}). Only one
 * interactive cell runs at a time, so a single shared queue + WebSocket input handler is
 * sufficient.
 *
 * Usage inside a cell — no imports needed (BaristaInput is always on the classpath):
 *
 *   Scanner sc = new Scanner(System.in);
 *   String name = sc.nextLine();   // prompts user interactively in the browser
 *   int    n    = sc.nextInt();    // prompts again
 */
public class BaristaInput {

    // Per-execution line queue.  LinkedBlockingDeque supports both poll() (non-blocking,
    // for pre-filled lines) and timed poll() (for interactive mode).
    private static final LinkedBlockingDeque<String> LINE_QUEUE = new LinkedBlockingDeque<>();

    // Callback invoked the moment input is needed but the queue is empty.
    // Set by ShellController before each execution; cleared in the finally block.
    private static volatile Runnable inputNeededCallback = null;

    // True while a reader is blocked waiting for the user to type a line. The
    // execution-timeout watcher consults this so time spent waiting on the user is
    // NOT counted against the compute budget (a user thinking at a prompt is never killed).
    private static volatile boolean waitingForInput = false;

    // Set by cancel() (manual Stop / interrupt) to unblock a pending takeLine().
    private static volatile boolean cancelled = false;

    // The singleton InputStream registered with JShell via JShell.Builder.in().
    public static final InputStream STDIN = new InputStream() {

        private byte[] buf = new byte[0];
        private int    pos = 0;

        @Override
        public int read() throws IOException {
            refill();
            return buf[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            refill();
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }

        private void refill() throws IOException {
            if (pos < buf.length) return;
            buf = new byte[0]; pos = 0;

            // Try pre-filled line first (non-blocking)
            String line = LINE_QUEUE.poll();
            if (line == null) {
                // Queue empty — notify the browser so it can show an interactive prompt
                Runnable cb = inputNeededCallback;
                if (cb != null) cb.run();

                // Block this thread until the user types something (or execution is cancelled)
                try {
                    line = takeLine(0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Execution cancelled while waiting for stdin input");
                }
                if (line == null) {
                    throw new IOException("Execution cancelled while waiting for stdin input");
                }
            }
            buf = (line + "\n").getBytes(StandardCharsets.UTF_8);
        }

        @Override public int available() { return LINE_QUEUE.isEmpty() ? 0 : 1; }
        @Override public void close()    { /* keep alive across cells */ }
    };

    /**
     * Pre-feed stdin lines before executing a cell (non-interactive).
     * Called by ShellController with values from the cell's Stdin panel.
     * Each element of {@code lines} corresponds to one call to scanner.nextLine() etc.
     */
    public static void provide(String... lines) {
        LINE_QUEUE.clear();
        cancelled = false;
        waitingForInput = false;
        for (String line : lines) {
            if (line != null) LINE_QUEUE.addLast(line);
        }
    }

    /**
     * Add a single line of input interactively.
     * Called by the WebSocket input handler when the user types in the browser.
     */
    public static void addLine(String line) {
        if (line != null) LINE_QUEUE.addLast(line);
    }

    /**
     * Block until the next line of input is available, execution is cancelled, or the
     * (optional) timeout elapses. Returns the line, or {@code null} on cancel/timeout.
     * Used by both {@link #STDIN} (JShell) and the subprocess InteractiveProcessRunner so
     * they share one queue and one WebSocket input handler.
     *
     * @param timeoutMs maximum time to wait; {@code 0} (or negative) waits indefinitely
     *                  (still interruptible via {@link #cancel()}).
     */
    public static String takeLine(long timeoutMs) throws InterruptedException {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        waitingForInput = true;
        try {
            while (true) {
                if (cancelled) return null;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                // Poll in short slices so cancel() is observed promptly even on an
                // otherwise-indefinite wait.
                String line = LINE_QUEUE.poll(Math.min(remaining, 150), TimeUnit.MILLISECONDS);
                if (line != null) return line;
            }
        } finally {
            waitingForInput = false;
        }
    }

    /** Non-blocking: return a pre-filled/queued line if one is available, else {@code null}. */
    public static String pollLine() {
        return LINE_QUEUE.poll();
    }

    /** True while a reader is blocked waiting for the user to type a line. */
    public static boolean isWaitingForInput() {
        return waitingForInput;
    }

    /** True once {@link #cancel()} has been called for the current run. */
    public static boolean isCancelled() {
        return cancelled;
    }

    /**
     * Register a callback that fires when code requests input but the queue is empty.
     * The callback should notify the browser (e.g. via WebSocket) to show an input prompt.
     * Pass {@code null} to clear the callback after execution.
     */
    public static void setInputNeededCallback(Runnable callback) {
        inputNeededCallback = callback;
    }

    /**
     * Unblock any reader currently waiting in {@link #takeLine} (manual Stop / interrupt).
     * The pending read returns {@code null}, which aborts the stdin read.
     */
    public static void cancel() {
        cancelled = true;
    }

    /** Discard any unconsumed stdin and clear the callback + cancel/waiting state. */
    public static void clear() {
        LINE_QUEUE.clear();
        inputNeededCallback = null;
        cancelled = false;
        waitingForInput = false;
    }
}
