package com.barista.model;

import java.util.ArrayList;
import java.util.List;

public class ExecutionResult {

    /** A single observed variable — name, declared type, and current value. */
    public static class Variable {
        private String name;
        private String type;
        private String value;

        public Variable() {}
        public Variable(String name, String type, String value) {
            this.name = name; this.type = type; this.value = value;
        }
        public String getName()  { return name;  }  public void setName(String v)  { this.name  = v; }
        public String getType()  { return type;  }  public void setType(String v)  { this.type  = v; }
        public String getValue() { return value; }  public void setValue(String v) { this.value = v; }
    }

    private String sessionId;
    private String cellId;
    private String output = "";
    private String error = "";
    private String returnValue;
    private String status;
    private boolean success;
    private long executionTimeMs;
    private int executionCount;
    private String notebookId;
    /** True when this result was served from the notebook's saved output (not freshly executed). */
    private boolean cached;

    /** Variables declared by THIS cell during this execution. */
    private List<Variable> localVariables = new ArrayList<>();
    /** Session-wide variables NOT declared by this cell (carried in from earlier cells). */
    private List<Variable> globalVariables = new ArrayList<>();

    public ExecutionResult() {}

    public ExecutionResult(String sessionId, String cellId, String output, String error,
                           String returnValue, String status, boolean success,
                           long executionTimeMs, int executionCount) {
        this.sessionId = sessionId;
        this.cellId = cellId;
        this.output = output;
        this.error = error;
        this.returnValue = returnValue;
        this.status = status;
        this.success = success;
        this.executionTimeMs = executionTimeMs;
        this.executionCount = executionCount;
    }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    /**
     * Build a result for a cell stopped by a runaway guard (output-line cap or time budget),
     * shared by every execution service so the "stopped" experience is identical across languages.
     * {@code status} is "OUTPUT_LIMIT" or "TIMEOUT"; {@code output} is whatever was printed so far.
     */
    public static ExecutionResult stopped(String sessionId, String cellId, String output,
                                          String status, String message, long startMs) {
        return builder()
                .sessionId(sessionId).cellId(cellId)
                .output(output == null ? "" : output)
                .error(message)
                .status(status).success(false)
                .executionTimeMs(System.currentTimeMillis() - startMs)
                .executionCount(0)
                .build();
    }

    public static class Builder {
        private final ExecutionResult r = new ExecutionResult();
        public Builder sessionId(String v)      { r.sessionId = v; return this; }
        public Builder cellId(String v)         { r.cellId = v; return this; }
        public Builder output(String v)         { r.output = v; return this; }
        public Builder error(String v)          { r.error = v; return this; }
        public Builder returnValue(String v)    { r.returnValue = v; return this; }
        public Builder status(String v)         { r.status = v; return this; }
        public Builder success(boolean v)       { r.success = v; return this; }
        public Builder executionTimeMs(long v)  { r.executionTimeMs = v; return this; }
        public Builder executionCount(int v)    { r.executionCount = v; return this; }
        public Builder notebookId(String v)     { r.notebookId = v; return this; }
        public Builder cached(boolean v)        { r.cached = v; return this; }
        public Builder localVariables(List<Variable> v)  { r.localVariables  = v != null ? v : new ArrayList<>(); return this; }
        public Builder globalVariables(List<Variable> v) { r.globalVariables = v != null ? v : new ArrayList<>(); return this; }
        public ExecutionResult build()          { return r; }
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getCellId() { return cellId; }
    public void setCellId(String cellId) { this.cellId = cellId; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getReturnValue() { return returnValue; }
    public void setReturnValue(String returnValue) { this.returnValue = returnValue; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public int getExecutionCount() { return executionCount; }
    public void setExecutionCount(int executionCount) { this.executionCount = executionCount; }

    public String getNotebookId() { return notebookId; }
    public void setNotebookId(String notebookId) { this.notebookId = notebookId; }

    public boolean isCached() { return cached; }
    public void setCached(boolean cached) { this.cached = cached; }

    public List<Variable> getLocalVariables() { return localVariables; }
    public void setLocalVariables(List<Variable> localVariables) {
        this.localVariables = localVariables != null ? localVariables : new ArrayList<>();
    }

    public List<Variable> getGlobalVariables() { return globalVariables; }
    public void setGlobalVariables(List<Variable> globalVariables) {
        this.globalVariables = globalVariables != null ? globalVariables : new ArrayList<>();
    }
}
