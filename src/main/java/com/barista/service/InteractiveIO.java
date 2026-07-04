package com.barista.service;

import com.barista.util.BaristaInput;

import java.util.function.Consumer;

/**
 * Per-execution interactive console context for the subprocess execution services.
 *
 * Created by {@code ShellController} for one cell run and handed to
 * {@link InteractiveProcessRunner}. It mirrors JShell's interactive model: when the running
 * program blocks on stdin, the output printed since the last prompt is flushed to the browser
 * and the inline input prompt is shown (STOMP {@code input_needed}, carrying that buffered
 * text). The user's typed line is read back through the shared {@link BaristaInput} queue.
 *
 *   • {@link #requestInput(String)} — flush buffered output + show the inline prompt.
 *   • {@link #readLine(long)}       — block until the user types a line (or the run is cancelled).
 *   • {@link #bindCanceller}        — register how to abort this run (used by the Stop button).
 *
 * When no {@code InteractiveIO} is supplied (orchestration pipelines, MCP), the runner falls
 * back to non-interactive batch execution — preserving the previous behaviour.
 */
public final class InteractiveIO {

    private final Consumer<String> inputRequester;
    private final Consumer<Runnable> cancellerBinder;

    public InteractiveIO(Consumer<String> inputRequester,
                         Consumer<Runnable> cancellerBinder) {
        this.inputRequester  = inputRequester;
        this.cancellerBinder = cancellerBinder;
    }

    /**
     * Flush the output buffered since the last prompt and ask the browser to show the inline
     * input prompt. {@code bufferedText} may be empty.
     */
    public void requestInput(String bufferedText) {
        if (inputRequester != null) inputRequester.accept(bufferedText == null ? "" : bufferedText);
    }

    /**
     * Block until the user provides a line (shared with JShell stdin), the run is cancelled,
     * or the timeout elapses. Returns {@code null} on cancel/timeout.
     */
    public String readLine(long timeoutMs) throws InterruptedException {
        return BaristaInput.takeLine(timeoutMs);
    }

    /** Register how to abort this run (invoked by the manual Stop button). */
    public void bindCanceller(Runnable canceller) {
        if (cancellerBinder != null) cancellerBinder.accept(canceller);
    }
}
