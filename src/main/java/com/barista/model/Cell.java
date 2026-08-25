package com.barista.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Cell {

    private String id;
    private CellType type = CellType.CODE;
    /** "jshell" (default) or "java" (full compile+run). Only applies to CODE cells. */
    private String mode = "jshell";
    private String source = "";
    /**
     * Pre-provided stdin for this cell (newline-separated).
     * Values are fed to Scanner / System.in when the cell runs.
     * Saved with the notebook so stdin is remembered across sessions.
     */
    private String stdin = "";
    private String output = "";
    private String returnValue;
    private boolean executed = false;
    private Integer executionCount;
    /** ISO-8601 timestamp of the last successful execution (stored in notebook JSON). */
    private String lastExecutedAt;
    /** Execution time of the last run in milliseconds. */
    private Long lastExecutionTimeMs;
    private String error = "";

    // ── Cell Orchestration fields ────────────────────────────────────────────
    /**
     * Optional semantic anchor name for this cell (slug: letters/digits/hyphens).
     * Declared via {@code //@ anchor: my-name} at the top of the cell source.
     * PIPELINE cells use {@code //@ pipeline: name} instead.
     * When set, other cells can reference this cell by name in their
     * {@code //@ depends:} or {@code //@ steps:} directives.
     */
    private String anchor;

    /**
     * Declared dependency anchors for this cell.
     * Populated from {@code //@ depends: a, b, c} annotation in cell source.
     * Arima resolves these to actual cells at execution time via the anchor map.
     */
    private List<String> dependsOn = new ArrayList<>();

    /**
     * For PIPELINE cells only: the ordered list of anchor names to execute.
     * Populated from {@code //@ steps: a, b, c} annotation.
     * Arima runs a topological sort over the transitive closure of these anchors.
     */
    private List<String> pipelineSteps = new ArrayList<>();

    // -- Polyglot fields ------------------------------------------------------
    /**
     * This cell's source rendered into other languages, keyed by mode
     * ("java", "cpp", "csharp", ...). Generated on demand and saved with the
     * notebook so a reopen costs nothing.
     */
    private Map<String, CellTranslation> translations = new LinkedHashMap<>();

    public Cell() {
        this.id = UUID.randomUUID().toString();
    }

    public Cell(String id, CellType type, String source) {
        this.id = id;
        this.type = type;
        this.source = source;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode == null ? "jshell" : mode; }

    public CellType getType() { return type; }
    public void setType(CellType type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getStdin() { return stdin; }
    public void setStdin(String stdin) { this.stdin = stdin == null ? "" : stdin; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getReturnValue() { return returnValue; }
    public void setReturnValue(String returnValue) { this.returnValue = returnValue; }

    public boolean isExecuted() { return executed; }
    public void setExecuted(boolean executed) { this.executed = executed; }

    public Integer getExecutionCount() { return executionCount; }
    public void setExecutionCount(Integer executionCount) { this.executionCount = executionCount; }

    public String getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(String lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public Long getLastExecutionTimeMs() { return lastExecutionTimeMs; }
    public void setLastExecutionTimeMs(Long lastExecutionTimeMs) { this.lastExecutionTimeMs = lastExecutionTimeMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getAnchor() { return anchor; }
    public void setAnchor(String anchor) { this.anchor = anchor; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn; }

    public Map<String, CellTranslation> getTranslations() { return translations; }
    public void setTranslations(Map<String, CellTranslation> translations) {
        this.translations = translations == null ? new LinkedHashMap<>() : translations;
    }

    public List<String> getPipelineSteps() { return pipelineSteps; }
    public void setPipelineSteps(List<String> pipelineSteps) { this.pipelineSteps = pipelineSteps == null ? new ArrayList<>() : pipelineSteps; }
}
