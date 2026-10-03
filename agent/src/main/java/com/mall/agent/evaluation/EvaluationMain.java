package com.mall.agent.evaluation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mall.agent.config.AgentConfig;
import com.mall.agent.config.ModelProperties;
import com.mall.agent.knowledge.DemoProductManifest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.ChatModel;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/** Separate classpath entrypoint. stdout contains exactly the frozen safe WorkerResult. */
public final class EvaluationMain {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> CONFIG_FIELDS = Set.of("schemaVersion", "runId", "caseId", "trialId", "caseFile",
            "bindingFile", "productManifest", "workDir", "requestAllowance", "reportedTokenAllowance");
    private EvaluationMain() { }

    public static void main(String[] args) {
        PrintStream protocol = System.out;
        try {
            if (args.length!=2 || !args[0].equals("--config")) throw new IllegalArgumentException("Invalid worker invocation");
            Map<String, String> env = System.getenv();
            for (String key : Set.of("MERCHANT_JWT_SECRET", "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD", "MALL_WORKER_ID", "MALL_DATACENTER_ID"))
                if (env.get(key)!=null && !env.get(key).isBlank()) throw new IllegalArgumentException("Backend environment refused");
            Path root = Path.of("").toAbsolutePath().normalize();
            Loaded loaded = load(Path.of(args[1]), root.resolve("eval/runs"));
            // Initialize SDK/logging only after private diagnostic redirection.
            runLoaded(loaded, () -> {
                JsonNode c = loaded.spec.document();
                boolean real = !c.path("mode").asText().equals("CONTROLLED") || c.path("control").path("usesRealModel").asBoolean();
                if (!real) return null;
                Properties properties = new Properties();
                try (var stream = EvaluationMain.class.getClassLoader().getResourceAsStream("agent.properties")) {
                    if (stream != null) properties.load(stream);
                }
                properties.setProperty("model.baseUrl", required(env, "MODEL_BASE_URL"));
                properties.setProperty("model.apiKey", required(env, "MODEL_API_KEY"));
                properties.setProperty("model.name", required(env, "MODEL_NAME"));
                return AgentConfig.chatModel(ModelProperties.from(properties));
            }, alias -> {
                if (!alias.equals(loaded.bindings.path("activeActor").textValue())) throw new IllegalArgumentException("Unexpected actor");
                Path jar = root.resolve("mcp-server/target/mcp-server.jar");
                if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("Missing MCP artifact");
                return AgentConfig.mcpClient(jar.toString(), env.getOrDefault("SUPERMALL_BASE_URL", "http://localhost:8081"), required(env, "SUPERMALL_TOKEN"));
            }, protocol);
        } catch (Throwable failure) {
            // Invalid private configuration has no trusted identity from which to create a wire result.
            // The parent treats absent/malformed stdout as ERROR. Never print private exception text.
            System.err.println("FIXTURE_ERROR");
            System.exit(1);
        }
    }

    static void run(Path config, Path runsRoot, Function<String, McpClient> clients, ChatModel model, PrintStream output) throws Exception {
        runLoaded(load(config, runsRoot), () -> model, clients, output);
    }

    @FunctionalInterface private interface ModelFactory { ChatModel create() throws Exception; }
    private static void runLoaded(Loaded loaded, ModelFactory models, Function<String, McpClient> clients, PrintStream protocol) throws Exception {
        PrintStream oldOut = System.out, oldErr = System.err;
        JsonNode result;
        TrialExecutor.OutputDirectory output = TrialExecutor.prepareOutputDirectory(loaded.workDir);
        try (PrintStream diagnostics = new PrintStream(Files.newOutputStream(output.path().resolve("worker-private.log")), true, StandardCharsets.UTF_8)) {
            System.setOut(diagnostics); System.setErr(diagnostics);
            try {
                result = new TrialExecutor(clients, models.create()).execute(loaded.spec, loaded.bindings, output,
                        loaded.config.path("requestAllowance").intValue(), loaded.config.path("reportedTokenAllowance").longValue());
            } catch (Throwable failure) {
                failure.printStackTrace(diagnostics);
                throw failure;
            } finally { System.setOut(oldOut); System.setErr(oldErr); }
        }
        protocol.println(JSON.writeValueAsString(result));
    }

    private record Loaded(JsonNode config, CaseSpec spec, JsonNode bindings, Path workDir) { }
    private static Loaded load(Path configPath, Path runsRoot) throws Exception {
        Path scope = runsRoot.toAbsolutePath().normalize().toRealPath();
        Path actualConfig = configPath.toAbsolutePath().normalize().toRealPath();
        if (!actualConfig.startsWith(scope)) throw new IllegalArgumentException("Config escapes run scope");
        JsonNode config = read(actualConfig);
        Set<String> fields = new HashSet<>(); config.fieldNames().forEachRemaining(fields::add);
        if (!config.isObject() || !fields.equals(CONFIG_FIELDS) || !config.path("schemaVersion").isInt() || config.path("schemaVersion").intValue()!=1)
            throw new IllegalArgumentException("Invalid WorkerConfig");
        for (String field : Set.of("runId", "trialId")) if (!config.path(field).isTextual()
                || !config.path(field).textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IllegalArgumentException("Invalid worker identity");
        if (!config.path("caseId").isTextual() || !config.path("caseId").textValue().matches("[A-Z][A-Z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid case identity");
        Path trial = scope.resolve(config.path("runId").textValue()).resolve(config.path("trialId").textValue()).normalize();
        if (!actualConfig.getParent().equals(trial) || !trial.toRealPath().equals(trial)) throw new IllegalArgumentException("Config trial mismatch");
        for (String field : Set.of("requestAllowance", "reportedTokenAllowance")) if (!config.path(field).isIntegralNumber()
                || !config.path(field).canConvertToInt() || config.path(field).intValue()<0) throw new IllegalArgumentException("Invalid worker allowance");
        CaseSpec spec = CaseSpec.parse(read(resolve(trial, config.path("caseFile"), false)));
        JsonNode bindings = read(resolve(trial, config.path("bindingFile"), false));
        if (!spec.document().path("caseId").equals(config.path("caseId"))) throw new IllegalArgumentException("Case identity mismatch");
        for (String field : Set.of("runId", "caseId", "trialId")) if (!bindings.path(field).equals(config.path(field))) throw new IllegalArgumentException("Binding identity mismatch");
        Path manifest = resolve(trial, config.path("productManifest"), false);
        Set<Long> expected = new HashSet<>(); bindings.path("products").forEach(row -> expected.add(Long.parseLong(row.path("productId").textValue())));
        if (!DemoProductManifest.load(manifest).equals(expected)) throw new IllegalArgumentException("Product allowlist mismatch");
        Path workDir = resolve(trial, config.path("workDir"), true);
        return new Loaded(config, spec, bindings, workDir);
    }

    private static Path resolve(Path trial, JsonNode value, boolean directory) throws Exception {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().contains("\\") || value.textValue().contains(":")) throw new IllegalArgumentException("Invalid relative path");
        String[] segments = value.textValue().split("/", -1);
        for (String segment : segments) if (segment.isBlank() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("Invalid relative path");
        Path path = trial.resolve(value.textValue()).normalize();
        if (!path.startsWith(trial) || path.equals(trial)) throw new IllegalArgumentException("Path escapes trial");
        if (directory) Files.createDirectories(path);
        Path real = path.toRealPath();
        if (!real.startsWith(trial) || !real.equals(path) || directory && !Files.isDirectory(real)
                || !directory && !Files.isRegularFile(real)) throw new IllegalArgumentException("Path escapes trial");
        return real;
    }
    private static JsonNode read(Path file) throws Exception { return JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)); }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key); if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing worker environment"); return value;
    }
}
