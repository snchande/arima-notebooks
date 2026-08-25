package com.barista.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One cell's source rendered into a language other than the cell's own mode.
 *
 * Translations are generated on demand by {@code PolyglotService} and stored on the
 * owning {@link Cell}, so a notebook carries them and never regenerates on reopen.
 * {@code edited} marks a translation the user has since changed by hand, which stops
 * a regenerate from silently discarding their work.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CellTranslation {

    private String source = "";
    private String generatedAt = "";
    private String provider = "";
    private String sourceHash = "";
    private boolean edited = false;

    // -- Last run of this translation, persisted so a reopened notebook still shows
    //    both languages' results (and timings) side by side.
    private String  output = "";
    private String  error = "";
    private boolean executed = false;
    private boolean success = false;
    private Long    executionTimeMs;
    private String  lastExecutedAt = "";

    public CellTranslation() {
    }

    public CellTranslation(String source, String generatedAt, String provider, String sourceHash) {
        this.source = source;
        this.generatedAt = generatedAt;
        this.provider = provider;
        this.sourceHash = sourceHash;
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source == null ? "" : source; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    /**
     * Hash of the cell source this was generated from. When it no longer matches the
     * cell's current source, the UI marks the translation stale rather than showing a
     * rendering of code that no longer exists.
     */
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output == null ? "" : output; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error == null ? "" : error; }

    public boolean isExecuted() { return executed; }
    public void setExecuted(boolean executed) { this.executed = executed; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    /** Wall-clock time of the last run, so languages can be compared on speed. */
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(Long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public String getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(String lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }
}
