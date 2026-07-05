package com.barista.model;

public class BaristaSettings {

    // ── AI Provider ────────────────────────────────────────────────────
    /** "claude_cli" (default) or "github_copilot" */
    private String aiProvider = "claude_cli";

    // ── Claude AI (claude_cli provider) ───────────────────────────────
    private String anthropicApiKey   = "";
    private String claudeModel       = "claude-sonnet-4-6";
    private int    claudeMaxTokens   = 4096;

    // ── GitHub Copilot (github_copilot provider) ───────────────────────
    private String githubToken         = "";
    private String githubCopilotModel  = "gpt-4o";

    // ── Antigravity CLI (gemini_cli provider key, retained for compatibility) ──
    private String geminiModel = "gemini-2.5-flash";

    // ── Editor ─────────────────────────────────────────────────────────
    private String  theme              = "dark";
    private int     editorFontSize     = 14;
    private boolean showLineNumbers    = true;
    private boolean focusExecutingCell = true;

    // ── Notebook Behaviour ─────────────────────────────────────────────
    private String  defaultCellMode        = "jshell";  // jshell | java | nodejs
    private int     autoSaveIntervalSecs   = 30;        // 0 = disabled
    private boolean autoClearOutputOnRun   = false;
    private boolean confirmBeforeDelete    = true;
    private boolean confirmBeforeRestart   = true;

    // ── Execution ──────────────────────────────────────────────────────
    private int     maxExecutionTimeMs = 30000;
    private int     maxOutputLines     = 20000;
    private boolean enableInlineCharts = true;
    private boolean wrapLongLines      = false;

    // ── Console ────────────────────────────────────────────────────────
    private int     consoleFontSize      = 13;
    private int     consoleHistorySize   = 500;
    private boolean enableAutoComplete   = true;

    // ── Storage & Server ───────────────────────────────────────────────
    private String notebooksDir = "notebooks";
    private int    serverPort   = 8585;

    public BaristaSettings() {}

    // ── Getters & Setters ──────────────────────────────────────────────

    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String v) { this.aiProvider = v == null ? "claude_cli" : v; }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String v) { this.githubToken = v == null ? "" : v; }

    public String getGithubCopilotModel() { return githubCopilotModel; }
    public void setGithubCopilotModel(String v) { this.githubCopilotModel = v == null ? "gpt-4o" : v; }

    public String getGeminiModel() { return geminiModel; }
    public void setGeminiModel(String v) { this.geminiModel = v == null ? "gemini-2.5-flash" : v; }

    public String getAnthropicApiKey() { return anthropicApiKey; }
    public void setAnthropicApiKey(String v) { this.anthropicApiKey = v; }

    public String getClaudeModel() { return claudeModel; }
    public void setClaudeModel(String v) { this.claudeModel = v; }

    public int getClaudeMaxTokens() { return claudeMaxTokens; }
    public void setClaudeMaxTokens(int v) { this.claudeMaxTokens = v; }

    public String getTheme() { return theme; }
    public void setTheme(String v) { this.theme = v; }

    public int getEditorFontSize() { return editorFontSize; }
    public void setEditorFontSize(int v) { this.editorFontSize = v; }

    public boolean isShowLineNumbers() { return showLineNumbers; }
    public void setShowLineNumbers(boolean v) { this.showLineNumbers = v; }

    public boolean isFocusExecutingCell() { return focusExecutingCell; }
    public void setFocusExecutingCell(boolean v) { this.focusExecutingCell = v; }

    public String getDefaultCellMode() { return defaultCellMode; }
    public void setDefaultCellMode(String v) { this.defaultCellMode = v; }

    public int getAutoSaveIntervalSecs() { return autoSaveIntervalSecs; }
    public void setAutoSaveIntervalSecs(int v) { this.autoSaveIntervalSecs = v; }

    public boolean isAutoClearOutputOnRun() { return autoClearOutputOnRun; }
    public void setAutoClearOutputOnRun(boolean v) { this.autoClearOutputOnRun = v; }

    public boolean isConfirmBeforeDelete() { return confirmBeforeDelete; }
    public void setConfirmBeforeDelete(boolean v) { this.confirmBeforeDelete = v; }

    public boolean isConfirmBeforeRestart() { return confirmBeforeRestart; }
    public void setConfirmBeforeRestart(boolean v) { this.confirmBeforeRestart = v; }

    public int getMaxExecutionTimeMs() { return maxExecutionTimeMs; }
    public void setMaxExecutionTimeMs(int v) { this.maxExecutionTimeMs = v; }

    public int getMaxOutputLines() { return maxOutputLines; }
    public void setMaxOutputLines(int v) { this.maxOutputLines = v; }

    public boolean isEnableInlineCharts() { return enableInlineCharts; }
    public void setEnableInlineCharts(boolean v) { this.enableInlineCharts = v; }

    public boolean isWrapLongLines() { return wrapLongLines; }
    public void setWrapLongLines(boolean v) { this.wrapLongLines = v; }

    public int getConsoleFontSize() { return consoleFontSize; }
    public void setConsoleFontSize(int v) { this.consoleFontSize = v; }

    public int getConsoleHistorySize() { return consoleHistorySize; }
    public void setConsoleHistorySize(int v) { this.consoleHistorySize = v; }

    public boolean isEnableAutoComplete() { return enableAutoComplete; }
    public void setEnableAutoComplete(boolean v) { this.enableAutoComplete = v; }

    public String getNotebooksDir() { return notebooksDir; }
    public void setNotebooksDir(String v) { this.notebooksDir = v; }

    public int getServerPort() { return serverPort; }
    public void setServerPort(int v) { this.serverPort = v; }
}
