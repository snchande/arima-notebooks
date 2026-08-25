/**
 * Arima Notebooks — Cell Orchestration Engine (client-side)
 *
 * Mirrors the server-side OrchestrationService but runs in the browser for:
 *   - Instant annotation parsing as the user types
 *   - Real-time dependency badge updates
 *   - Staleness detection (cell edited since last run)
 *   - Pipeline step validation before sending to server
 *
 * The //@ annotation DSL:
 *   //@ anchor: my-name          — names this cell as a reusable anchor
 *   //@ depends: imports, setup  — declares prerequisite cells (by anchor name)
 *   //@ pipeline: full-run       — names a PIPELINE cell's own anchor
 *   //@ steps: a, b, c           — PIPELINE cell: ordered steps to execute
 *   //@ description: text        — human-readable label for the cell header
 *   //@ on-error: stop|continue  — pipeline error handling (default: stop)
 */
const Orchestration = (() => {

    // ── State ─────────────────────────────────────────────────────────────
    // anchor → { status: 'pending'|'running'|'ok'|'error'|'stale', count, ts }
    const depStatus = {};

    // ── Annotation Parser ─────────────────────────────────────────────────

    /**
     * Parse //@ annotation lines from the start of cell source.
     * Stops at the first line that doesn't start with //@.
     */
    function parseAnnotations(source) {
        const result = {
            anchor: null, dependsOn: [], pipelineSteps: [],
            description: null, onError: 'stop'
        };
        if (!source) return result;

        for (const rawLine of source.split('\n')) {
            const line = rawLine.trim();
            if (!line.startsWith('//@')) break;
            const rest = line.slice(3).trim();

            if (rest.startsWith('anchor:'))
                result.anchor = rest.slice(7).trim();
            else if (rest.startsWith('pipeline:'))
                result.anchor = rest.slice(9).trim();
            else if (rest.startsWith('depends:'))
                result.dependsOn = splitList(rest.slice(8));
            else if (rest.startsWith('steps:'))
                result.pipelineSteps = splitList(rest.slice(6));
            else if (rest.startsWith('description:'))
                result.description = rest.slice(12).trim();
            else if (rest.startsWith('on-error:'))
                result.onError = rest.slice(9).trim();
        }
        return result;
    }

    function splitList(s) {
        return s.split(',').map(x => x.trim()).filter(Boolean);
    }

    // ── Anchor Map ────────────────────────────────────────────────────────

    /**
     * Build a map of anchor → cell from all cells in the current notebook.
     * Called after any cell is edited or the notebook is loaded.
     */
    function buildAnchorMap(cells) {
        const map = {};
        cells.forEach(cell => {
            if (cell.anchor) map[cell.anchor] = cell;
        });
        return map;
    }

    // ── Graph Utilities ───────────────────────────────────────────────────

    /** Compute the full transitive dependency closure for a list of root anchors. */
    function expandClosure(roots, anchorMap) {
        const visited = new Set();
        function expand(anchor) {
            if (visited.has(anchor)) return;
            visited.add(anchor);
            const cell = anchorMap[anchor];
            if (!cell) return;
            (cell.dependsOn || []).forEach(expand);
        }
        roots.forEach(expand);
        return visited;
    }

    /** Kahn's topological sort over the closure. Returns sorted anchor list. */
    function topologicalSort(closure, anchorMap) {
        const inDegree = {};
        const reverseAdj = {};
        closure.forEach(a => { inDegree[a] = 0; reverseAdj[a] = []; });

        closure.forEach(a => {
            const cell = anchorMap[a];
            if (!cell) return;
            (cell.dependsOn || []).filter(d => closure.has(d)).forEach(dep => {
                inDegree[a] = (inDegree[a] || 0) + 1;
                reverseAdj[dep].push(a);
            });
        });

        const queue = [...closure].filter(a => inDegree[a] === 0);
        const sorted = [];
        while (queue.length > 0) {
            const n = queue.shift();
            sorted.push(n);
            (reverseAdj[n] || []).forEach(dep => {
                inDegree[dep]--;
                if (inDegree[dep] === 0) queue.push(dep);
            });
        }
        return sorted;
    }

    /** DFS cycle detection. Returns cycle path array or null. */
    function detectCycle(roots, anchorMap) {
        const color = {}; // 0=white, 1=grey, 2=black
        Object.keys(anchorMap).forEach(k => { color[k] = 0; });

        function dfs(anchor, path) {
            color[anchor] = 1;
            path.push(anchor);
            const cell = anchorMap[anchor];
            for (const dep of (cell?.dependsOn || [])) {
                if (!(dep in color)) continue;
                if (color[dep] === 1) {
                    const start = path.indexOf(dep);
                    return [...path.slice(start), dep];
                }
                if (color[dep] === 0) {
                    const cycle = dfs(dep, path);
                    if (cycle) return cycle;
                }
            }
            color[anchor] = 2;
            path.pop();
            return null;
        }

        for (const root of roots) {
            if (color[root] === 0) {
                const cycle = dfs(root, []);
                if (cycle) return cycle;
            }
        }
        return null;
    }

    /** Validate pipeline steps and return list of error strings. */
    function validatePipeline(steps, anchorMap) {
        const errors = [];
        steps.forEach(step => {
            if (!anchorMap[step]) {
                errors.push(`Unknown anchor '#${step}' — name a cell with //@ anchor: ${step}`);
            }
        });
        if (errors.length === 0) {
            const cycle = detectCycle(steps, anchorMap);
            if (cycle) errors.push(`Cycle detected: ${cycle.join(' → ')}`);
        }
        return errors;
    }

    // ── Status Tracking ───────────────────────────────────────────────────

    function markRunning(anchor) {
        if (!anchor) return;
        depStatus[anchor] = { status: 'running', count: 0, ts: Date.now() };
        refreshBadges(anchor);
    }

    function markOk(anchor, executionCount) {
        if (!anchor) return;
        depStatus[anchor] = { status: 'ok', count: executionCount, ts: Date.now() };
        refreshBadges(anchor);
    }

    function markError(anchor) {
        if (!anchor) return;
        depStatus[anchor] = { status: 'error', count: 0, ts: Date.now() };
        refreshBadges(anchor);
    }

    function markStale(anchor) {
        if (!anchor) return;
        const existing = depStatus[anchor];
        if (existing && existing.status === 'ok') {
            depStatus[anchor] = { ...existing, status: 'stale' };
            refreshBadges(anchor);
        }
    }

    /** Status classes a badge may carry — swapped out on every refresh. */
    const STATUS_CLASSES = ['pending', 'running', 'ok', 'error', 'stale'];

    /**
     * Apply a status to one badge WITHOUT clobbering its other classes.
     * Assigning className outright used to strip `dep-link` / `cross` /
     * `step-status`, which silently disabled click-to-jump on every badge as
     * soon as the first status refresh ran.
     */
    function applyBadgeStatus(badge, anchor, s) {
        STATUS_CLASSES.forEach(c => badge.classList.remove(c));
        badge.classList.add('dep-badge', s.status);
        badge.title = badgeTitle(anchor, s)
            + (badge.classList.contains('dep-link') ? ' — click to jump to this cell' : '');
    }

    /** Update all dependency badge elements that reference this anchor. */
    function refreshBadges(changedAnchor) {
        document.querySelectorAll(`.dep-badge[data-anchor="${changedAnchor}"]`).forEach(badge => {
            applyBadgeStatus(badge, changedAnchor, depStatus[changedAnchor] || { status: 'pending' });
        });
    }

    function refreshAllBadges() {
        document.querySelectorAll('.dep-badge[data-anchor]').forEach(badge => {
            const anchor = badge.dataset.anchor;
            applyBadgeStatus(badge, anchor, depStatus[anchor] || { status: 'pending' });
        });
    }

    function badgeTitle(anchor, s) {
        const labels = { pending: 'Not yet executed', running: 'Running…',
                         ok: `OK (run #${s.count})`, error: 'Last run had errors',
                         stale: 'Source changed since last run — re-run recommended' };
        return `#${anchor}: ${labels[s.status] || s.status}`;
    }

    function getStatus(anchor) {
        return depStatus[anchor] || { status: 'pending' };
    }

    // ── Execution Plan Preview ─────────────────────────────────────────────

    /**
     * Build an ordered execution plan for a set of root anchors.
     * Returns { plan: string[], errors: string[] }
     */
    function buildExecutionPlan(roots, anchorMap) {
        const errors = validatePipeline(roots, anchorMap);
        if (errors.length > 0) return { plan: [], errors };

        const closure = expandClosure(roots, anchorMap);
        const sorted  = topologicalSort(closure, anchorMap);
        return { plan: sorted, errors: [] };
    }

    // ── Public API ────────────────────────────────────────────────────────

    return {
        parseAnnotations,
        buildAnchorMap,
        expandClosure,
        topologicalSort,
        detectCycle,
        validatePipeline,
        buildExecutionPlan,
        markRunning,
        markOk,
        markError,
        markStale,
        refreshAllBadges,
        getStatus,
    };
})();
