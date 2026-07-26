package com.barista.service;

import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.ExecutionResult;
import com.barista.model.Notebook;
import com.barista.service.UserService;
import com.barista.shell.JShellManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Arima Cell Orchestration Engine.
 *
 * Provides Ant-build-style dependency resolution for notebook cells.
 * Unlike Jupyter's purely positional "Run All Above", Arima tracks named
 * cell anchors, resolves their transitive dependencies, and detects cycles
 * before execution begins.
 *
 * Core concepts:
 *   anchor   — A semantic name given to a cell (e.g. "imports", "data-setup")
 *   depends  — A cell's declared list of anchors it needs to run before itself
 *   pipeline — A PIPELINE cell that defines an ordered workflow of anchors
 *
 * Cross-notebook module system:
 *   Cross-refs use the format: notebook:NOTEBOOK-ID/ANCHOR-NAME
 *   Example: //@ depends: notebook:java-utils/math-utils
 *   When resolved, the foreign cell's source is executed in the current session
 *   before the dependent cell runs. If the foreign cell carries a //@ namespace:
 *   annotation, its source is wrapped in a class named VNS_<namespace-with-underscores>.
 *
 * Execution semantics:
 *   1. Build an anchor→cell map from the notebook
 *   2. For a given target (cell anchor or pipeline steps), compute transitive closure
 *   3. Topologically sort the closure (respecting declared dependency edges)
 *   4. Detect cycles — report clearly rather than hanging
 *   5. Execute cells in sorted order via JShellManager / JavaCompilerService
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final JShellManager jShellManager;
    private final PackageService packageService;
    private final JavaCompilerService javaCompilerService;
    private final NodeJsExecutionService nodeJsExecutionService;
    private final TypeScriptExecutionService typeScriptExecutionService;
    private final DotNetExecutionService dotNetExecutionService;
    private final CppExecutionService cppExecutionService;
    private final NotebookService notebookService;
    private final UserService userService;
    private final AgentService agentService;

    /**
     * Tracks which cross-notebook modules have already been loaded into each session.
     * Key: sessionId → Set of "notebookId/anchor" keys already executed.
     */
    private final Map<String, Set<String>> sessionLoadedModules = new ConcurrentHashMap<>();

    /**
     * Tracks which local cell IDs have already been executed in each session.
     * Used to skip re-running dep cells when forceRun=false (cached mode).
     * Key: sessionId → Set of cellId strings already executed.
     */
    private final Map<String, Set<String>> sessionExecutedCells = new ConcurrentHashMap<>();

    public OrchestrationService(JShellManager jShellManager,
                                 PackageService packageService,
                                 JavaCompilerService javaCompilerService,
                                 NodeJsExecutionService nodeJsExecutionService,
                                 TypeScriptExecutionService typeScriptExecutionService,
                                 DotNetExecutionService dotNetExecutionService,
                                 CppExecutionService cppExecutionService,
                                 NotebookService notebookService,
                                 UserService userService,
                                 AgentService agentService) {
        this.jShellManager = jShellManager;
        this.packageService = packageService;
        this.javaCompilerService = javaCompilerService;
        this.nodeJsExecutionService = nodeJsExecutionService;
        this.typeScriptExecutionService = typeScriptExecutionService;
        this.dotNetExecutionService = dotNetExecutionService;
        this.cppExecutionService = cppExecutionService;
        this.notebookService = notebookService;
        this.userService = userService;
        this.agentService = agentService;
    }

    // ── Cross-notebook support ─────────────────────────────────────────────

    /**
     * Represents a cross-notebook dependency reference.
     * Parsed from strings like "notebook:java-utils/math-utils".
     */
    private record CrossRef(String notebookId, String anchor) {

        /**
         * Parse a cross-notebook ref string of the form "notebook:NOTEBOOK-ID/ANCHOR-NAME".
         * Returns null if the string is not a cross-notebook ref.
         */
        static CrossRef parse(String ref) {
            if (ref == null || !ref.startsWith("notebook:")) return null;
            String body = ref.substring("notebook:".length());
            int slash = body.indexOf('/');
            if (slash < 1 || slash >= body.length() - 1) return null;
            return new CrossRef(body.substring(0, slash), body.substring(slash + 1));
        }

        /** Unique key for tracking loaded state: "notebookId/anchor". */
        String key() { return notebookId + "/" + anchor; }
    }

    /**
     * Derives the class name used when wrapping a namespaced cell.
     * "com.barista.utils.math" → "VNS_com_barista_utils_math"
     */
    private static String namespaceToClassName(String namespace) {
        if (namespace == null || namespace.isBlank()) return null;
        String sanitized = namespace.strip()
                .replace('.', '_')
                .replace('-', '_')
                .replace('/', '_')
                .replaceAll("[^A-Za-z0-9_]", "_");
        return "VNS_" + sanitized;
    }

    /**
     * Load a cross-notebook module into the given session, if not already loaded.
     * Recursively resolves the foreign cell's own cross-notebook dependencies first.
     *
     * @param ref       The cross-notebook reference to load
     * @param sessionId The target session
     * @return PipelineResult indicating success/failure, or null if already loaded
     */
    private PipelineResult loadCrossNotebookModule(CrossRef ref, String sessionId) {
        Set<String> loaded = sessionLoadedModules
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());

        if (loaded.contains(ref.key())) {
            log.debug("[CrossNotebook] Already loaded '{}' into session '{}'", ref.key(), sessionId);
            return null; // already loaded — nothing to do
        }

        log.info("[CrossNotebook] Loading '{}' into session '{}'", ref.key(), sessionId);

        // Load the foreign notebook — check user dir first, then shared examples/tutorials
        Notebook foreignNotebook = notebookService.getNotebook(ref.notebookId(), userService.getCurrentUser().getId())
                .or(() -> notebookService.getTutorial(ref.notebookId()))
                .orElse(null);
        if (foreignNotebook == null) {
            return PipelineResult.error(
                    "Cross-notebook ref '" + ref.key() + "': notebook '" + ref.notebookId() + "' not found.");
        }

        // Find the cell by anchor
        Map<String, Cell> foreignAnchorMap = buildAnchorMap(foreignNotebook);
        Cell foreignCell = foreignAnchorMap.get(ref.anchor());
        if (foreignCell == null) {
            return PipelineResult.error(
                    "Cross-notebook ref '" + ref.key() + "': anchor '" + ref.anchor()
                    + "' not found in notebook '" + ref.notebookId() + "'.");
        }

        // Recursively resolve the foreign cell's own cross-notebook deps first
        List<String> foreignDeps = foreignCell.getDependsOn();
        for (String dep : foreignDeps) {
            CrossRef depRef = CrossRef.parse(dep);
            if (depRef != null) {
                PipelineResult depResult = loadCrossNotebookModule(depRef, sessionId);
                if (depResult != null && !depResult.success()) {
                    return depResult; // propagate failure
                }
            }
        }

        // ── C# / F# cells: cache source for injection rather than executing via JShell ──
        String cellMode = foreignCell.getMode() != null ? foreignCell.getMode() : "jshell";
        if ("csharp".equals(cellMode) || "fsharp".equals(cellMode)) {
            return loadCrossNotebookDotNetModule(ref, foreignCell, foreignAnchorMap, sessionId, loaded);
        }

        // ── JShell / Java cells: execute in the shared JShell session ──
        String source = foreignCell.getSource();
        CellAnnotations foreignAnn = parseAnnotations(source);
        String namespace = foreignAnn.namespace();

        // Strip annotation lines from source
        String strippedSource = stripAnnotationLines(source);

        // Wrap in a class if namespace is present
        String executableSource;
        if (namespace != null && !namespace.isBlank()) {
            String className = namespaceToClassName(namespace);
            executableSource = "class " + className + " {\n" + strippedSource + "\n}";
            log.info("[CrossNotebook] Wrapping '{}' in class '{}'", ref.key(), className);
        } else {
            executableSource = strippedSource;
        }

        // Execute in the current session
        ExecutionResult result = jShellManager.execute(sessionId, executableSource, foreignCell.getId());
        result.setNotebookId(ref.notebookId());
        if (!result.isSuccess()) {
            return PipelineResult.error(
                    "Cross-notebook ref '" + ref.key() + "' execution failed: " + result.getError());
        }

        // Mark as loaded
        loaded.add(ref.key());
        return new PipelineResult(List.of(result), null, true);
    }

    /**
     * Cross-notebook load for C# or F# cells.
     *
     * <p>C# and F# cells run in isolated subprocesses — there is no shared REPL state.
     * Instead of executing the foreign cell, its annotation-stripped source is cached in the
     * DotNet anchor store under {@code "notebook:notebookId/anchor"}. Each local dep of the
     * foreign cell is also recursively cached using fully-qualified cross-notebook keys
     * (e.g. {@code "notebook:shared-utils/cs_dataTypes"}).
     *
     * <p>A synthetic {@code //@ depends:} line is prepended to the cached source listing
     * all the cell's deps with fully-qualified keys. This allows
     * {@code resolveTransitiveDeps} in {@link DotNetExecutionService} to follow the dep
     * chain transitively — using the {@code visited} set to avoid injecting the same type
     * definitions more than once, which would cause a C# compile error.</p>
     *
     * <p>This replaces the old "expanded source" approach that concatenated the entire
     * transitive dep tree into one blob, causing duplicate type definitions when multiple
     * cells shared a common ancestor dep.</p>
     */
    private PipelineResult loadCrossNotebookDotNetModule(CrossRef ref, Cell foreignCell,
                                                          Map<String, Cell> foreignAnchorMap,
                                                          String sessionId, Set<String> loaded) {
        // Step 1: Recursively load each LOCAL dep of the foreign cell as its own
        // fully-qualified cross-notebook ref (e.g. "notebook:shared-utils/cs_dataTypes").
        // Calling loadCrossNotebookModule handles the already-loaded guard at the top.
        for (String dep : foreignCell.getDependsOn()) {
            if (CrossRef.parse(dep) != null) continue; // pure cross-notebook refs handled elsewhere
            CrossRef fqRef = new CrossRef(ref.notebookId(), dep);
            PipelineResult depResult = loadCrossNotebookModule(fqRef, sessionId);
            if (depResult != null && !depResult.success()) return depResult;
        }

        // Step 2: Cache the foreign cell itself (annotation-stripped) with a synthetic
        // //@ depends: line that maps its local dep names to fully-qualified keys.
        // resolveTransitiveDeps() reads these to follow the chain without re-expanding.
        String strippedSource = stripAnnotationLines(foreignCell.getSource());
        List<String> fqDeps = new ArrayList<>();
        for (String dep : foreignCell.getDependsOn()) {
            CrossRef depRef = CrossRef.parse(dep);
            fqDeps.add(depRef != null ? dep : "notebook:" + ref.notebookId() + "/" + dep);
        }
        String cachedSource = fqDeps.isEmpty()
                ? strippedSource
                : "//@ depends: " + String.join(", ", fqDeps) + "\n" + strippedSource;

        String cacheKey = "notebook:" + ref.key();
        dotNetExecutionService.cacheAnchorSource(sessionId, cacheKey, cachedSource);
        loaded.add(ref.key());

        log.info("[CrossNotebook] Cached C#/F# source for '{}' ({} chars, {} fq-deps) in session '{}'",
                ref.key(), cachedSource.length(), fqDeps.size(), sessionId);

        ExecutionResult loadResult = ExecutionResult.builder()
                .sessionId(sessionId).cellId(foreignCell.getId())
                .output("").error("")
                .status("OK").success(true)
                .executionTimeMs(0L).executionCount(0)
                .build();
        loadResult.setNotebookId(ref.notebookId());
        return new PipelineResult(List.of(loadResult), null, true);
    }

    /**
     * Strip leading annotation lines (starting with //@) from source code.
     */
    private static String stripAnnotationLines(String source) {
        if (source == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean pastAnnotations = false;
        for (String line : source.lines().toList()) {
            if (!pastAnnotations && line.strip().startsWith("//@")) {
                continue; // skip annotation lines at the top
            }
            pastAnnotations = true;
            sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Collect all unique cross-notebook refs from the dependency closure of the given anchors.
     * Returns them in the order they are first encountered during a depth-first traversal.
     */
    private List<CrossRef> collectCrossRefs(Set<String> closure, Map<String, Cell> anchorMap) {
        List<CrossRef> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String anchor : closure) {
            Cell cell = anchorMap.get(anchor);
            if (cell == null) continue;
            for (String dep : cell.getDependsOn()) {
                CrossRef ref = CrossRef.parse(dep);
                if (ref != null && seen.add(ref.key())) {
                    ordered.add(ref);
                }
            }
        }
        return ordered;
    }

    /** Clear the cross-notebook module cache for a session (call after kernel restart). */
    public void clearSessionModules(String sessionId) {
        sessionLoadedModules.remove(sessionId);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Execute a PIPELINE cell: resolve its steps, toposort, execute each in order.
     *
     * @return List of results in execution order, keyed by anchor name
     */
    /** Record that a cell was successfully executed in this session (for caching). */
    public void markCellExecuted(String sessionId, String cellId) {
        sessionExecutedCells.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(cellId);
    }

    /** Clear the session's executed-cells cache so the next run re-executes everything. */
    public void clearSessionCache(String sessionId) {
        sessionExecutedCells.remove(sessionId);
        sessionLoadedModules.remove(sessionId);
        log.info("[Orchestration] Cleared session cache for {}", sessionId);
    }

    public PipelineResult executePipeline(Notebook notebook, String pipelineCellId,
                                          String sessionId) {
        return executePipeline(notebook, pipelineCellId, sessionId, false);
    }

    public PipelineResult executePipeline(Notebook notebook, String pipelineCellId,
                                          String sessionId, boolean forceRun) {
        Cell pipelineCell = findCell(notebook, pipelineCellId);
        if (pipelineCell == null || pipelineCell.getType() != CellType.PIPELINE) {
            return PipelineResult.error("Cell not found or not a PIPELINE cell: " + pipelineCellId);
        }

        List<String> steps = pipelineCell.getPipelineSteps();
        if (steps.isEmpty()) {
            // Try to parse from source
            CellAnnotations ann = parseAnnotations(pipelineCell.getSource());
            steps = ann.pipelineSteps();
        }
        if (steps.isEmpty()) {
            return PipelineResult.error("Pipeline cell has no steps defined. Use //@ steps: anchor1, anchor2");
        }

        return executeAnchors(notebook, steps, sessionId, forceRun);
    }

    /**
     * Execute a CODE cell with its declared dependencies resolved first.
     * Runs the transitive dependency closure of the target cell, in topological order.
     *
     * @param targetCellId  The cell to ultimately execute
     */
    public PipelineResult executeWithDependencies(Notebook notebook, String targetCellId,
                                                  String sessionId) {
        return executeWithDependencies(notebook, targetCellId, sessionId, false);
    }

    public PipelineResult executeWithDependencies(Notebook notebook, String targetCellId,
                                                  String sessionId, boolean forceRun) {
        Cell target = findCell(notebook, targetCellId);
        if (target == null) {
            return PipelineResult.error("Cell not found: " + targetCellId);
        }
        String targetAnchor = target.getAnchor();
        if (targetAnchor == null || targetAnchor.isBlank()) {
            // No anchor — just run the cell itself directly
            ExecutionResult r = executeSingleCell(target, sessionId, forceRun);
            return new PipelineResult(List.of(r), null, true);
        }
        return executeAnchors(notebook, List.of(targetAnchor), sessionId, forceRun);
    }

    /**
     * Execute all cells from the top of the notebook down to (and including) the given cell,
     * in document order.  This is the "Run to here" action.
     */
    public PipelineResult executeToHere(Notebook notebook, String targetCellId, String sessionId) {
        List<Cell> allCells = notebook.getCells();
        List<Cell> toRun = new ArrayList<>();
        for (Cell c : allCells) {
            toRun.add(c);
            if (c.getId().equals(targetCellId)) break;
        }

        List<ExecutionResult> results = new ArrayList<>();
        for (Cell c : toRun) {
            if (c.getType() == CellType.CODE) {
                results.add(executeSingleCell(c, sessionId));
            } else if (c.getType() == CellType.PIPELINE) {
                PipelineResult pr = executePipeline(notebook, c.getId(), sessionId);
                results.addAll(pr.results());
                if (!pr.success()) return new PipelineResult(results, pr.error(), false);
            }
            // MARKDOWN cells are skipped
        }
        return new PipelineResult(results, null, true);
    }

    /**
     * Validate the dependency graph for a notebook — check for unknown anchors and cycles.
     * Cross-notebook refs are noted but not treated as errors (they are external).
     * Returns a list of validation messages (empty = valid).
     */
    public List<String> validateGraph(Notebook notebook) {
        Map<String, Cell> anchorMap = buildAnchorMap(notebook);
        List<String> messages = new ArrayList<>();

        for (Cell cell : notebook.getCells()) {
            if (cell.getType() == CellType.MARKDOWN) continue;

            List<String> refs = new ArrayList<>(cell.getDependsOn());
            if (cell.getType() == CellType.PIPELINE) refs.addAll(cell.getPipelineSteps());

            for (String ref : refs) {
                if (CrossRef.parse(ref) != null) {
                    // Cross-notebook reference — note it but don't flag as error
                    messages.add("Note: cross-notebook ref '" + ref + "' in cell "
                            + displayName(cell) + " (external, not validated locally)");
                } else if (!anchorMap.containsKey(ref)) {
                    messages.add("Unknown anchor '" + ref + "' referenced by cell "
                            + displayName(cell));
                }
            }
        }

        // Cycle detection (local anchors only)
        Map<String, Integer> color = new HashMap<>(); // 0=white,1=grey,2=black
        anchorMap.keySet().forEach(k -> color.put(k, 0));

        // Build adjacency list excluding cross-notebook refs
        Map<String, List<String>> adj = new HashMap<>();
        for (Map.Entry<String, Cell> entry : anchorMap.entrySet()) {
            List<String> localDeps = entry.getValue().getDependsOn().stream()
                    .filter(d -> CrossRef.parse(d) == null)
                    .collect(Collectors.toList());
            adj.put(entry.getKey(), localDeps);
        }

        for (String anchor : anchorMap.keySet()) {
            if (color.get(anchor) == 0) {
                List<String> path = new ArrayList<>();
                List<String> cycle = dfsDetectCycle(anchor, adj, color, path);
                if (cycle != null) {
                    messages.add("Cycle detected: " + String.join(" → ", cycle));
                }
            }
        }

        return messages;
    }

    // ── Core execution ────────────────────────────────────────────────────

    private PipelineResult executeAnchors(Notebook notebook, List<String> rootAnchors,
                                          String sessionId) {
        return executeAnchors(notebook, rootAnchors, sessionId, false);
    }

    private PipelineResult executeAnchors(Notebook notebook, List<String> rootAnchors,
                                          String sessionId, boolean forceRun) {
        Map<String, Cell> anchorMap = buildAnchorMap(notebook);

        // Validate all root anchors exist locally
        for (String a : rootAnchors) {
            if (!anchorMap.containsKey(a)) {
                return PipelineResult.error("Unknown cell anchor: '" + a
                        + "'. Name a cell with //@ anchor: " + a);
            }
        }

        // Compute transitive closure (local anchors only)
        Set<String> closure = new LinkedHashSet<>();
        for (String a : rootAnchors) expandClosure(a, anchorMap, closure);

        // Ensure session exists and packages are applied
        boolean freshSession = !jShellManager.hasSession(sessionId);
        if (freshSession) {
            jShellManager.getOrCreateSession(sessionId);
            packageService.applyPackagesToSession(sessionId);
            sessionLoadedModules.remove(sessionId); // fresh session — clear stale module cache
            sessionExecutedCells.remove(sessionId);  // fresh session — nothing is in scope yet
        }
        if (forceRun) {
            // Clean run — clear cache so everything re-executes
            sessionExecutedCells.remove(sessionId);
            sessionLoadedModules.remove(sessionId);
        }

        // ── Load cross-notebook modules BEFORE local execution ──────────
        List<CrossRef> crossRefs = collectCrossRefs(closure, anchorMap);
        List<ExecutionResult> allResults = new ArrayList<>();
        for (CrossRef ref : crossRefs) {
            PipelineResult moduleResult = loadCrossNotebookModule(ref, sessionId);
            if (moduleResult != null) {
                allResults.addAll(moduleResult.results());
                if (!moduleResult.success()) {
                    return new PipelineResult(allResults, moduleResult.error(), false);
                }
            }
        }

        // Build adjacency list for the local closure (excluding cross-notebook deps)
        Map<String, List<String>> adj = new HashMap<>();
        for (String a : closure) {
            Cell c = anchorMap.get(a);
            if (c != null) {
                List<String> localDeps = c.getDependsOn().stream()
                        .filter(d -> CrossRef.parse(d) == null)
                        .filter(closure::contains)
                        .collect(Collectors.toList());
                adj.put(a, localDeps);
            } else {
                adj.put(a, List.of());
            }
        }

        // Detect cycles
        Map<String, Integer> color = new HashMap<>();
        closure.forEach(a -> color.put(a, 0));
        for (String a : closure) {
            if (color.get(a) == 0) {
                List<String> cycle = dfsDetectCycle(a, adj, color, new ArrayList<>());
                if (cycle != null) {
                    return PipelineResult.error(
                            "Cycle detected in dependencies: " + String.join(" → ", cycle)
                            + "\nFix by removing one of the circular //@ depends: references.");
                }
            }
        }

        // Topological sort (Kahn's algorithm)
        List<String> sorted = topologicalSort(closure, adj);

        // Execute in sorted order
        log.info("[Orchestration] Execution plan for {} roots {}: {}", rootAnchors, sessionId, sorted);

        Set<String> executed = sessionExecutedCells.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());

        for (String anchor : sorted) {
            Cell cell = anchorMap.get(anchor);
            if (cell == null) continue;

            // Use cached output if the cell was already run in this session and is not force-run
            if (!forceRun && executed.contains(cell.getId()) && cell.isExecuted()
                    && cell.getOutput() != null && !cell.getOutput().isBlank()) {
                log.info("[Orchestration] Using cached output for #{} ({})", anchor, cell.getId());
                ExecutionResult cached = ExecutionResult.builder()
                        .sessionId(sessionId).cellId(cell.getId())
                        .output(cell.getOutput())
                        .error(cell.getError() != null ? cell.getError() : "")
                        .returnValue(cell.getReturnValue())
                        .status("CACHED").success(true)
                        .executionTimeMs(cell.getLastExecutionTimeMs() != null ? cell.getLastExecutionTimeMs() : 0L)
                        .executionCount(cell.getExecutionCount() != null ? cell.getExecutionCount() : 0)
                        .cached(true)
                        .build();
                allResults.add(cached);
                continue;
            }

            log.info("[Orchestration] Executing: #{} ({})", anchor, cell.getId());
            ExecutionResult r = executeSingleCell(cell, sessionId, false, notebook);
            // Keep the in-memory cell output fresh so a downstream agent cell's {{anchor}} sees it.
            if (r.getOutput() != null) cell.setOutput(r.getOutput());
            executed.add(cell.getId());
            allResults.add(r);
            if (!r.isSuccess()) {
                // Stop on first failure
                return new PipelineResult(allResults,
                        "Execution stopped at '#" + anchor + "': " + r.getError(), false);
            }
        }

        return new PipelineResult(allResults, null, true);
    }

    private ExecutionResult executeSingleCell(Cell cell, String sessionId) {
        return executeSingleCell(cell, sessionId, false, null);
    }

    private ExecutionResult executeSingleCell(Cell cell, String sessionId, boolean forceRun) {
        return executeSingleCell(cell, sessionId, forceRun, null);
    }

    private ExecutionResult executeSingleCell(Cell cell, String sessionId, boolean forceRun, Notebook host) {
        if (cell.getType() == CellType.PIPELINE) {
            // Pipeline cells don't execute themselves directly
            return ExecutionResult.builder()
                    .sessionId(sessionId).cellId(cell.getId())
                    .output("Pipeline cell — use 'Run Pipeline' to execute")
                    .status("SKIP").success(true).executionCount(0).build();
        }

        String mode = cell.getMode() != null ? cell.getMode() : "jshell";
        if ("agent".equals(mode)) {
            // Agent invocation cell — runs the referenced agent, interworking via {{anchor}} / //@ bind.
            return agentService.runAgentCell(host, cell.getSource(), sessionId, cell.getId());
        }
        if ("java".equals(mode)) {
            List<String> cp = packageService.getInstalledPackages().stream()
                    .map(p -> p.getJarPath()).collect(Collectors.toList());
            return javaCompilerService.execute(sessionId, cell.getId(), cell.getSource(), cp);
        } else if ("nodejs".equals(mode)) {
            return nodeJsExecutionService.execute(sessionId, cell.getId(), cell.getSource());
        } else if ("typescript".equals(mode)) {
            return typeScriptExecutionService.execute(sessionId, cell.getId(), cell.getSource());
        } else if ("csharp".equals(mode)) {
            return dotNetExecutionService.executeCSharp(sessionId, cell.getId(), cell.getSource());
        } else if ("fsharp".equals(mode)) {
            return dotNetExecutionService.executeFSharp(sessionId, cell.getId(), cell.getSource());
        } else if ("cpp".equals(mode)) {
            return cppExecutionService.execute(sessionId, cell.getId(), cell.getSource());
        } else {
            return jShellManager.execute(sessionId, cell.getSource(), cell.getId());
        }
    }

    // ── Graph utilities ───────────────────────────────────────────────────

    private Map<String, Cell> buildAnchorMap(Notebook notebook) {
        Map<String, Cell> map = new LinkedHashMap<>();
        for (Cell c : notebook.getCells()) {
            if (c.getAnchor() != null && !c.getAnchor().isBlank()) {
                map.put(c.getAnchor(), c);
            }
        }
        return map;
    }

    private Map<String, List<String>> buildAdjList(Notebook notebook,
                                                    Map<String, Cell> anchorMap) {
        Map<String, List<String>> adj = new HashMap<>();
        for (Map.Entry<String, Cell> entry : anchorMap.entrySet()) {
            List<String> localDeps = entry.getValue().getDependsOn().stream()
                    .filter(d -> CrossRef.parse(d) == null)
                    .collect(Collectors.toList());
            adj.put(entry.getKey(), localDeps);
        }
        return adj;
    }

    private void expandClosure(String anchor, Map<String, Cell> anchorMap,
                                Set<String> visited) {
        if (visited.contains(anchor)) return;
        Cell cell = anchorMap.get(anchor);
        if (cell == null) return;
        visited.add(anchor);
        for (String dep : cell.getDependsOn()) {
            // Only expand local (non-cross-notebook) deps into the closure
            if (CrossRef.parse(dep) == null) {
                expandClosure(dep, anchorMap, visited);
            }
        }
    }

    /** Kahn's algorithm for topological sort. */
    private List<String> topologicalSort(Set<String> nodes, Map<String, List<String>> adj) {
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(n -> inDegree.put(n, 0));

        for (Map.Entry<String, List<String>> e : adj.entrySet()) {
            for (String dep : e.getValue()) {
                // dep is a prerequisite of e.getKey(), so e.getKey() has +1 in-degree
                inDegree.merge(e.getKey(), 1, Integer::sum);
            }
        }

        // Seeds: nodes with no dependencies
        Queue<String> queue = new ArrayDeque<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::offer);

        List<String> sorted = new ArrayList<>();
        // Build reverse adjacency: dep → nodes that depend on dep
        Map<String, List<String>> reversAdj = new HashMap<>();
        nodes.forEach(n -> reversAdj.put(n, new ArrayList<>()));
        adj.forEach((node, deps) -> deps.forEach(dep -> reversAdj.get(dep).add(node)));

        while (!queue.isEmpty()) {
            String n = queue.poll();
            sorted.add(n);
            for (String dependent : reversAdj.getOrDefault(n, List.of())) {
                int newDeg = inDegree.merge(dependent, -1, Integer::sum);
                if (newDeg == 0) queue.offer(dependent);
            }
        }
        return sorted;
    }

    /** DFS cycle detection. Returns the cycle path or null if none found. */
    private List<String> dfsDetectCycle(String node, Map<String, List<String>> adj,
                                         Map<String, Integer> color, List<String> path) {
        color.put(node, 1); // GREY
        path.add(node);
        for (String dep : adj.getOrDefault(node, List.of())) {
            if (!color.containsKey(dep)) continue;
            if (color.get(dep) == 1) { // GREY = cycle
                int start = path.indexOf(dep);
                List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                cycle.add(dep); // close the loop
                return cycle;
            }
            if (color.get(dep) == 0) {
                List<String> cycle = dfsDetectCycle(dep, adj, color, path);
                if (cycle != null) return cycle;
            }
        }
        color.put(node, 2); // BLACK
        path.remove(path.size() - 1);
        return null;
    }

    // ── Annotation parser ─────────────────────────────────────────────────

    public record CellAnnotations(String anchor, List<String> dependsOn,
                                   List<String> pipelineSteps, String description,
                                   String onError, String namespace) {}

    public CellAnnotations parseAnnotations(String source) {
        if (source == null) return empty();
        String anchor = null;
        List<String> dependsOn = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        String description = null;
        String onError = "stop";
        String namespace = null;

        for (String line : source.lines().toList()) {
            String t = line.strip();
            if (!t.startsWith("//@")) break; // only leading annotation lines
            String rest = t.substring(3).strip();
            if (rest.startsWith("anchor:"))          anchor      = rest.substring(7).strip();
            else if (rest.startsWith("pipeline:"))   anchor      = rest.substring(9).strip();
            else if (rest.startsWith("depends:"))    dependsOn   = splitList(rest.substring(8));
            else if (rest.startsWith("steps:"))      steps       = splitList(rest.substring(6));
            else if (rest.startsWith("description:")) description = rest.substring(12).strip();
            else if (rest.startsWith("on-error:"))   onError     = rest.substring(9).strip();
            else if (rest.startsWith("namespace:"))  namespace   = rest.substring(10).strip();
        }
        return new CellAnnotations(anchor, dependsOn, steps, description, onError, namespace);
    }

    private static CellAnnotations empty() {
        return new CellAnnotations(null, List.of(), List.of(), null, "stop", null);
    }

    private static List<String> splitList(String s) {
        return Arrays.stream(s.split(","))
                .map(String::strip).filter(v -> !v.isEmpty()).collect(Collectors.toList());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Cell findCell(Notebook notebook, String cellId) {
        return notebook.getCells().stream()
                .filter(c -> c.getId().equals(cellId)).findFirst().orElse(null);
    }

    private String displayName(Cell c) {
        if (c.getAnchor() != null) return "#" + c.getAnchor();
        return c.getId();
    }

    // ── Result type ───────────────────────────────────────────────────────

    public record PipelineResult(List<ExecutionResult> results, String error, boolean success) {
        static PipelineResult error(String msg) {
            return new PipelineResult(List.of(), msg, false);
        }
    }
}
