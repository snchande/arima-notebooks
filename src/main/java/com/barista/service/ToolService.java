package com.barista.service;

import com.barista.model.AgentSpec;
import com.barista.model.Cell;
import com.barista.model.CellType;
import com.barista.model.ExecutionResult;
import com.barista.model.Notebook;
import com.barista.model.PackageInfo;
import com.barista.model.ToolSpec;
import com.barista.shell.JShellManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Author / inspect / invoke <em>tools</em> — the callable half of the agentic surface.
 *
 * A tool notebook is a normal {@link Notebook} with {@code metadata.kind = "tool"} and a
 * {@code metadata.tool} block declaring its execution mode and parameters. Invoking one binds the
 * caller's arguments as language-native variables, prepends them to the notebook's code cells, and
 * runs the result through that language's existing execution service — so a tool can be written in
 * any of the eight languages Arima already executes, and the unified {@link ExecutionResult}
 * contract is preserved.
 *
 * Tools are the bridge to the outside: {@link com.barista.controller.McpController} advertises each
 * one to MCP clients with its own JSON Schema, so an external agent calls an Arima-authored tool by
 * name.
 */
@Service
public class ToolService {

    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    /** Modes a tool body may be written in — the eight Arima executes. */
    public static final List<String> MODES = List.of(
            "jshell", "java", "nodejs", "typescript", "csharp", "fsharp", "cpp", "python");

    private final NotebookService notebookService;
    private final UserService userService;
    private final PackageService packageService;
    private final JShellManager jShellManager;
    private final JavaCompilerService javaCompilerService;
    private final NodeKernelService nodeKernelService;
    private final TypeScriptExecutionService typeScriptExecutionService;
    private final DotNetExecutionService dotNetExecutionService;
    private final CppExecutionService cppExecutionService;
    private final PythonKernelService pythonKernelService;

    public ToolService(NotebookService notebookService,
                       UserService userService,
                       PackageService packageService,
                       JShellManager jShellManager,
                       JavaCompilerService javaCompilerService,
                       NodeKernelService nodeKernelService,
                       TypeScriptExecutionService typeScriptExecutionService,
                       DotNetExecutionService dotNetExecutionService,
                       CppExecutionService cppExecutionService,
                       PythonKernelService pythonKernelService) {
        this.notebookService = notebookService;
        this.userService = userService;
        this.packageService = packageService;
        this.jShellManager = jShellManager;
        this.javaCompilerService = javaCompilerService;
        this.nodeKernelService = nodeKernelService;
        this.typeScriptExecutionService = typeScriptExecutionService;
        this.dotNetExecutionService = dotNetExecutionService;
        this.cppExecutionService = cppExecutionService;
        this.pythonKernelService = pythonKernelService;
    }

    // -- Catalog ---------------------------------------------------------------

    /**
     * Every tool definition available to the current user — their own tool notebooks plus the
     * built-in samples. Each entry is the card projection the Tools section renders:
     * {@code id, name, slug, description, mode, params, paramCount, source}.
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        String userId = userService.getCurrentUser().getId();
        for (Map<String, Object> meta : notebookService.listNotebooks(userId)) {
            toSpecIfTool(meta).ifPresent(t -> out.add(card(t, "mine")));
        }
        for (Map<String, Object> meta : notebookService.listTutorials()) {
            toSpecIfTool(meta).ifPresent(t -> out.add(card(t, "sample")));
        }
        return out;
    }

    /** Projections of every tool notebook, in catalog order. */
    public List<ToolSpec> specs() {
        String userId = userService.getCurrentUser().getId();
        List<ToolSpec> out = new ArrayList<>();
        for (Map<String, Object> meta : notebookService.listNotebooks(userId)) {
            toSpecIfTool(meta).ifPresent(out::add);
        }
        for (Map<String, Object> meta : notebookService.listTutorials()) {
            toSpecIfTool(meta).ifPresent(out::add);
        }
        return out;
    }

    /** The spec for one tool, by notebook id. */
    public Optional<ToolSpec> spec(String toolId) {
        return load(toolId).filter(this::isTool).map(this::toSpec);
    }

    /** The spec for one tool, by its MCP slug (e.g. {@code fetch-quote}). */
    public Optional<ToolSpec> specBySlug(String slug) {
        if (slug == null) return Optional.empty();
        return specs().stream().filter(t -> t.slug().equals(slug)).findFirst();
    }

    private Optional<ToolSpec> toSpecIfTool(Map<String, Object> meta) {
        Object nbMeta = meta.get("metadata");
        if (!(nbMeta instanceof Map<?, ?> m) || !"tool".equals(str(m.get("kind")))) {
            return Optional.empty();
        }
        return spec(str(meta.get("id")));
    }

    private Map<String, Object> card(ToolSpec t, String source) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", t.id());
        card.put("name", t.name());
        card.put("slug", t.slug());
        card.put("description", t.description());
        card.put("mode", t.mode());
        card.put("params", paramMaps(t));
        card.put("paramCount", t.params().size());
        card.put("hasBody", !t.body().isBlank());
        card.put("source", source);
        return card;
    }

    /** The declared parameters as plain maps — what the UI and the API hand back. */
    public List<Map<String, Object>> paramMaps(ToolSpec t) {
        return t.params().stream().map(p -> {
            Map<String, Object> pm = new LinkedHashMap<String, Object>();
            pm.put("name", p.name());
            pm.put("type", p.type());
            pm.put("description", p.description());
            pm.put("required", p.required());
            pm.put("default", p.defaultValue());
            return pm;
        }).collect(Collectors.toList());
    }

    // -- Authoring -------------------------------------------------------------

    /**
     * Create a tool notebook, pre-seeded with a docs cell and a runnable body in {@code mode} that
     * already echoes its one starter parameter — so "+ New Tool" produces something that runs.
     */
    public Notebook create(String name, String mode) {
        String userId = userService.getCurrentUser().getId();
        String toolMode = MODES.contains(mode) ? mode : "python";
        String nbName = (name == null || name.isBlank()) ? "New Tool" : name.trim();

        Notebook nb = notebookService.createNotebook(nbName, userId);
        nb.setDescription("Describe what this tool does in one line — agents read this to decide when to call it.");
        nb.getMetadata().put("kind", "tool");

        Map<String, Object> toolMeta = new LinkedHashMap<>();
        toolMeta.put("mode", toolMode);
        toolMeta.put("params", List.of(paramMap("input", "string", "The tool's input", true, null)));
        nb.getMetadata().put("tool", toolMeta);

        Cell docs = new Cell();
        docs.setType(CellType.MARKDOWN);
        docs.setSource("# " + nbName + "\n\nWhat this tool does, and when an agent should reach for it.\n\n"
                + "**Parameters** are declared in the banner above; each one is bound as a variable of the "
                + "same name before the code cells below run. Whatever the tool prints is its return value.");

        Cell body = new Cell();
        body.setType(CellType.CODE);
        body.setMode(toolMode);
        body.setSource(starterBody(toolMode));

        nb.getCells().clear();
        nb.getCells().add(docs);
        nb.getCells().add(body);
        return notebookService.saveNotebook(nb, userId);
    }

    private Map<String, Object> paramMap(String name, String type, String desc,
                                         boolean required, String def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("description", desc);
        m.put("required", required);
        if (def != null) m.put("default", def);
        return m;
    }

    private String starterBody(String mode) {
        return switch (mode) {
            case "python"     -> "print(f\"you passed: {input}\")";
            case "nodejs"     -> "console.log(`you passed: ${input}`);";
            case "typescript" -> "console.log(`you passed: ${input}`);";
            case "csharp"     -> "Console.WriteLine($\"you passed: {input}\");";
            case "fsharp"     -> "printfn \"you passed: %s\" input";
            case "cpp"        -> "std::cout << \"you passed: \" << input << std::endl;";
            default           -> "System.out.println(\"you passed: \" + input);";
        };
    }

    // -- Invocation ------------------------------------------------------------

    /**
     * Invoke a tool. Validates {@code args} against the declared parameters, binds them as
     * language-native variables, and runs the body through the execution service for its mode.
     * The tool's stdout is its return value.
     *
     * @throws IllegalArgumentException if the tool is unknown, its mode is unsupported, or a
     *                                  required argument is missing
     */
    public ExecutionResult invoke(String toolId, Map<String, Object> args, String sessionId) {
        ToolSpec tool = spec(toolId)
                .orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolId));
        return invoke(tool, args, sessionId);
    }

    /** Invoke an already-resolved tool spec. */
    public ExecutionResult invoke(ToolSpec tool, Map<String, Object> args, String sessionId) {
        if (tool.body().isBlank()) {
            throw new IllegalArgumentException("Tool '" + tool.name()
                    + "' has no code cells — add the implementation before calling it.");
        }
        Map<String, Object> bound = bind(tool, args == null ? Map.of() : args);
        String code = preamble(tool, bound) + tool.body();
        String session = (sessionId == null || sessionId.isBlank())
                ? "tool-" + tool.slug() : sessionId;
        String cellId = "__tool_" + tool.slug() + "__";

        log.info("Invoking tool '{}' ({}) with {} arg(s)", tool.slug(), tool.mode(), bound.size());
        return run(tool.mode(), session, cellId, code);
    }

    /** Validate and default the supplied arguments against the declared parameters. */
    private Map<String, Object> bind(ToolSpec tool, Map<String, Object> args) {
        Map<String, Object> bound = new LinkedHashMap<>();
        for (ToolSpec.Param p : tool.params()) {
            Object v = args.get(p.name());
            boolean missing = (v == null)
                    || (v instanceof String s && s.isBlank() && !"string".equals(p.type()));
            if (missing) {
                if (p.defaultValue() != null && !p.defaultValue().isBlank()) {
                    v = p.defaultValue();
                } else if (p.required()) {
                    throw new IllegalArgumentException(
                            "Tool '" + tool.name() + "' requires parameter '" + p.name() + "'.");
                } else {
                    v = switch (p.type()) {
                        case "integer" -> 0L;
                        case "number"  -> 0.0d;
                        case "boolean" -> Boolean.FALSE;
                        default        -> "";
                    };
                }
            }
            bound.put(p.name(), coerce(p, v));
        }
        return bound;
    }

    private Object coerce(ToolSpec.Param p, Object v) {
        String s = String.valueOf(v).trim();
        try {
            return switch (p.type()) {
                case "integer" -> (v instanceof Number n) ? n.longValue() : Long.parseLong(s);
                case "number"  -> (v instanceof Number n) ? n.doubleValue() : Double.parseDouble(s);
                case "boolean" -> (v instanceof Boolean b) ? b : Boolean.parseBoolean(s);
                default        -> String.valueOf(v);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + p.name() + "' expects a "
                    + p.type() + ", got: " + s);
        }
    }

    /** Declare each bound argument as a variable in the tool's own language. */
    String preamble(ToolSpec tool, Map<String, Object> bound) {
        if (bound.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolSpec.Param p : tool.params()) {
            sb.append(declare(tool.mode(), p, bound.get(p.name()))).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    private String declare(String mode, ToolSpec.Param p, Object v) {
        String name = p.name();
        return switch (mode) {
            case "python" -> name + " = " + pyLiteral(p, v);
            case "nodejs", "typescript" -> "const " + name + " = " + jsLiteral(p, v) + ";";
            case "csharp" -> csharpType(p) + " " + name + " = " + csLiteral(p, v) + ";";
            case "fsharp" -> "let " + name + " = " + fsLiteral(p, v);
            case "cpp"    -> cppType(p) + " " + name + " = " + cppLiteral(p, v) + ";";
            default       -> javaType(p) + " " + name + " = " + javaLiteral(p, v) + ";";
        };
    }

    private String pyLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "boolean" -> Boolean.TRUE.equals(v) ? "True" : "False";
            case "integer", "number" -> String.valueOf(v);
            default -> quote(String.valueOf(v));
        };
    }

    private String jsLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "boolean", "integer", "number" -> String.valueOf(v);
            default -> quote(String.valueOf(v));
        };
    }

    private String javaType(ToolSpec.Param p) {
        return switch (p.type()) {
            case "integer" -> "long";
            case "number"  -> "double";
            case "boolean" -> "boolean";
            default        -> "String";
        };
    }

    private String javaLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "integer" -> v + "L";
            case "number", "boolean" -> String.valueOf(v);
            default -> quote(String.valueOf(v));
        };
    }

    private String csharpType(ToolSpec.Param p) {
        return switch (p.type()) {
            case "integer" -> "long";
            case "number"  -> "double";
            case "boolean" -> "bool";
            default        -> "string";
        };
    }

    private String csLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "boolean" -> Boolean.TRUE.equals(v) ? "true" : "false";
            case "integer", "number" -> String.valueOf(v);
            default -> quote(String.valueOf(v));
        };
    }

    private String fsLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "boolean" -> Boolean.TRUE.equals(v) ? "true" : "false";
            case "integer" -> v + "L";
            case "number"  -> String.valueOf(v);
            default        -> quote(String.valueOf(v));
        };
    }

    private String cppType(ToolSpec.Param p) {
        return switch (p.type()) {
            case "integer" -> "long long";
            case "number"  -> "double";
            case "boolean" -> "bool";
            default        -> "std::string";
        };
    }

    private String cppLiteral(ToolSpec.Param p, Object v) {
        return switch (p.type()) {
            case "boolean" -> Boolean.TRUE.equals(v) ? "true" : "false";
            case "integer", "number" -> String.valueOf(v);
            default -> quote(String.valueOf(v));
        };
    }

    /** A double-quoted literal that is safe in every language a tool body may be written in. */
    private String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    /** Dispatch to the execution service for {@code mode} — the same routing ShellController uses. */
    private ExecutionResult run(String mode, String sessionId, String cellId, String code) {
        return switch (mode) {
            case "java" -> javaCompilerService.execute(sessionId, cellId, code,
                    packageService.getInstalledPackages().stream()
                            .map(PackageInfo::getJarPath).collect(Collectors.toList()));
            case "nodejs"     -> nodeKernelService.execute(sessionId, cellId, code, false);
            case "typescript" -> typeScriptExecutionService.execute(sessionId, cellId, code);
            case "csharp"     -> dotNetExecutionService.executeCSharp(sessionId, cellId, code);
            case "fsharp"     -> dotNetExecutionService.executeFSharp(sessionId, cellId, code);
            case "cpp"        -> cppExecutionService.execute(sessionId, cellId, code);
            case "python"     -> pythonKernelService.execute(sessionId, cellId, code, "");
            case "jshell"     -> {
                if (!jShellManager.hasSession(sessionId)) {
                    jShellManager.getOrCreateSession(sessionId);
                    packageService.applyPackagesToSession(sessionId);
                }
                yield jShellManager.execute(sessionId, code, cellId);
            }
            default -> throw new IllegalArgumentException("Unsupported tool mode: " + mode);
        };
    }

    /** Flatten an invocation into the text an MCP client sees. */
    public String renderResult(ToolSpec tool, ExecutionResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tool: ").append(tool.slug()).append(" (").append(tool.mode()).append(")\n");
        sb.append("Status: ").append(r.isSuccess() ? "SUCCESS" : "ERROR").append("\n");
        if (r.getOutput() != null && !r.getOutput().isBlank()) {
            sb.append("Output:\n").append(r.getOutput().strip()).append("\n");
        }
        if (r.getReturnValue() != null && !r.getReturnValue().isBlank()) {
            sb.append("Return value: ").append(r.getReturnValue()).append("\n");
        }
        if (!r.isSuccess() && r.getError() != null && !r.getError().isBlank()) {
            sb.append("Error:\n").append(r.getError().strip()).append("\n");
        }
        sb.append("Execution time: ").append(r.getExecutionTimeMs()).append("ms");
        return sb.toString();
    }

    // -- internals -------------------------------------------------------------

    /** Project a tool notebook into its spec: signature from metadata, body from the code cells. */
    ToolSpec toSpec(Notebook nb) {
        Map<String, Object> meta = nb.getMetadata() == null ? Map.of() : nb.getMetadata();
        String mode = "python";
        List<ToolSpec.Param> params = List.of();
        if (meta.get("tool") instanceof Map<?, ?> t) {
            if (t.get("mode") instanceof String m && MODES.contains(m)) mode = m;
            params = readParams(t.get("params"));
        }
        String body = nb.getCells().stream()
                .filter(c -> c.getType() == CellType.CODE)
                .map(Cell::getSource)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"))
                .strip();

        return new ToolSpec(nb.getId(), nb.getName(), AgentSpec.slugify(nb.getName()),
                nb.getDescription(), mode, params, body);
    }

    private List<ToolSpec.Param> readParams(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ToolSpec.Param> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) continue;
            out.add(new ToolSpec.Param(
                    name.trim(),
                    ToolSpec.Param.typeOf(str(m.get("type"))),
                    str(m.get("description")),
                    Boolean.parseBoolean(String.valueOf(m.get("required"))),
                    str(m.get("default"))));
        }
        return out;
    }

    private boolean isTool(Notebook nb) {
        return nb.getMetadata() != null && "tool".equals(str(nb.getMetadata().get("kind")));
    }

    private Optional<Notebook> load(String id) {
        if (id == null) return Optional.empty();
        return notebookService.getNotebook(id, userService.getCurrentUser().getId())
                .or(() -> notebookService.getTutorial(id));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
