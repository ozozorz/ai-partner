package io.github.ozozorz.aipartner.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.contract.ContractCompiler;
import io.github.ozozorz.aipartner.contract.SchemaOnlyContractCompiler;
import io.github.ozozorz.aipartner.contract.TaskDefinition;
import io.github.ozozorz.aipartner.contract.TaskDefinitionRegistry;
import io.github.ozozorz.aipartner.contract.TaskContract;
import io.github.ozozorz.aipartner.contract.TaskContractValidatorRegistry;
import io.github.ozozorz.aipartner.contract.validation.CollectAndDepositContractValidator;
import io.github.ozozorz.aipartner.contract.validation.CollectBlockContractValidator;
import io.github.ozozorz.aipartner.contract.validation.DepositItemContractValidator;
import io.github.ozozorz.aipartner.core.action.BreakBlockAction;
import io.github.ozozorz.aipartner.core.action.NavigateAction;
import io.github.ozozorz.aipartner.core.action.TransferItemAction;
import io.github.ozozorz.aipartner.core.behavior.MaidBehaviorController;
import io.github.ozozorz.aipartner.core.behavior.ManualDirective;
import io.github.ozozorz.aipartner.core.event.MaidDomainEvents;
import io.github.ozozorz.aipartner.core.order.MaidOrderService;
import io.github.ozozorz.aipartner.core.task.MaidTaskRegistry;
import io.github.ozozorz.aipartner.core.task.MaidTaskRuntime;
import io.github.ozozorz.aipartner.core.task.MaidTaskSnapshot;
import io.github.ozozorz.aipartner.core.task.TaskExecutionPolicy;
import io.github.ozozorz.aipartner.evaluation.OfflineEvaluationDataset;
import io.github.ozozorz.aipartner.evaluation.OfflineLlmEvaluationService;
import io.github.ozozorz.aipartner.evaluation.OfflineMetricsCalculator;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.executor.CollectAndDepositExecutor;
import io.github.ozozorz.aipartner.executor.CollectBlockExecutor;
import io.github.ozozorz.aipartner.executor.DepositItemExecutor;
import io.github.ozozorz.aipartner.gameplay.task.CollectAndDepositMaidTask;
import io.github.ozozorz.aipartner.gameplay.task.CollectBlockMaidTask;
import io.github.ozozorz.aipartner.gameplay.task.DepositItemMaidTask;
import io.github.ozozorz.aipartner.job.AllowedTargets;
import io.github.ozozorz.aipartner.job.ContainerTargets;
import io.github.ozozorz.aipartner.job.JobType;
import io.github.ozozorz.aipartner.llm.LlmGateway;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import io.github.ozozorz.aipartner.registry.ModContractValidators;
import io.github.ozozorz.aipartner.registry.ModTasks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 加载 v0.4 冻结实验协议，并为模型参数、资源与安全边界生成可复核指纹。
 */
public final class ExperimentProtocol {
    public static final String RESOURCE_PATH = "/assets/ai-partner/evaluation/experiment_protocol_v0_4.json";
    public static final String PROMPT_RESOURCE = "/assets/ai-partner/prompts/job_parser_system.txt";
    public static final String SCHEMA_RESOURCE = "/assets/ai-partner/schema/job_spec.schema.json";
    private static final List<Class<?>> IMPLEMENTATION_ROOTS = List.of(
            ExperimentProtocol.class,
            ContractCompiler.class,
            SchemaOnlyContractCompiler.class,
            AiPartnerEntity.class,
            MaidBehaviorController.class,
            ManualDirective.class,
            MaidOrderService.class,
            MaidDomainEvents.class,
            MaidTaskRegistry.class,
            MaidTaskRuntime.class,
            MaidTaskSnapshot.class,
            TaskExecutionPolicy.class,
            TaskContractValidatorRegistry.class,
            ModTasks.class,
            ModContractValidators.class,
            CollectBlockContractValidator.class,
            DepositItemContractValidator.class,
            CollectAndDepositContractValidator.class,
            CollectBlockMaidTask.class,
            DepositItemMaidTask.class,
            CollectAndDepositMaidTask.class,
            NavigateAction.class,
            BreakBlockAction.class,
            TransferItemAction.class,
            CollectBlockExecutor.class,
            DepositItemExecutor.class,
            CollectAndDepositExecutor.class,
            ExperimentBatchRunner.class,
            ExperimentBatchStore.class,
            ExperimentFreezeService.class,
            ExperimentScenarioJudge.class,
            ExperimentScenarioService.class,
            ExperimentEventBridge.class,
            VariantExecutionService.class,
            OfflineLlmEvaluationService.class,
            OfflineMetricsCalculator.class,
            LlmGateway.class,
            ExperimentLogger.class
    );
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Definition DEFINITION = loadDefinition();

    private ExperimentProtocol() {
    }

    public static Definition definition() {
        return DEFINITION;
    }

    /**
     * 验证当前运行配置和代码边界仍与冻结协议一致；不一致时禁止开始实验。
     */
    public static Snapshot verifyAndSnapshot(AiPartnerConfig config) {
        Objects.requireNonNull(config, "config");
        require(DEFINITION.protocolVersion().equals("0.4"), "unexpected protocol version");
        require(config.llmEnabled() == DEFINITION.llmEnabled(), "LLM enabled flag differs from frozen protocol");
        require(config.endpoint().equals(DEFINITION.endpoint()), "endpoint differs from frozen protocol");
        require(config.model().equals(DEFINITION.model()), "model differs from frozen protocol");
        require(config.apiKeyEnvironmentVariable().equals(DEFINITION.apiKeyEnvironmentVariable()),
                "API key environment variable differs from frozen protocol");
        require(Double.compare(config.temperature(), DEFINITION.temperature()) == 0,
                "temperature differs from frozen protocol");
        require(config.timeoutSeconds() == DEFINITION.llmTimeoutSeconds(),
                "LLM timeout differs from frozen protocol");
        require(config.maxRetries() == DEFINITION.maxGatewayRetries(),
                "gateway retries differ from frozen protocol");
        require(config.maxOutputTokens() == DEFINITION.maxOutputTokens(),
                "max output tokens differ from frozen protocol");
        require(config.requestJsonResponseFormat() == DEFINITION.requestJsonResponseFormat(),
                "JSON response mode differs from frozen protocol");
        require(TaskContract.FailurePolicy.DEFAULT.timeoutSeconds() == DEFINITION.taskTimeoutSeconds(),
                "task timeout differs from frozen protocol");
        require(TaskContract.FailurePolicy.DEFAULT.maxLocalRetries() == DEFINITION.maxLocalRetries(),
                "local retry boundary differs from frozen protocol");
        require(TaskContract.FailurePolicy.DEFAULT.maxLlmReplans() == DEFINITION.maxLlmReplans(),
                "LLM replan boundary differs from frozen protocol");
        require(AllowedTargets.MAX_COLLECT_RADIUS == DEFINITION.maximumRadius(),
                "collect radius boundary differs from frozen protocol");
        require(ContainerTargets.MAX_DEPOSIT_RADIUS == DEFINITION.maximumRadius(),
                "deposit radius boundary differs from frozen protocol");
        require(AllowedTargets.suggestedBlockIds().equals(DEFINITION.allowedTargets()),
                "target whitelist differs from frozen protocol");
        verifyTaskDefinitionBoundaries();
        require(ExperimentScenarioRegistry.all().size() == 18, "scenario count differs from frozen protocol");

        String promptHash = sha256(readResource(PROMPT_RESOURCE));
        String schemaHash = sha256(readResource(SCHEMA_RESOURCE));
        OfflineEvaluationDataset.Loaded dataset = OfflineEvaluationDataset.load();
        String scenarioHash = sha256(canonicalScenarios());
        String safetyHash = sha256(canonicalSafetyBoundary());
        String protocolHash = sha256(readResource(RESOURCE_PATH));
        String implementationHash = implementationSha256();
        String fingerprint = sha256(String.join("\n",
                protocolHash,
                promptHash,
                schemaHash,
                dataset.sha256(),
                scenarioHash,
                safetyHash,
                implementationHash
        ));
        return new Snapshot(
                DEFINITION.protocolVersion(),
                Instant.now().toString(),
                DEFINITION.model(),
                DEFINITION.temperature(),
                DEFINITION.llmTimeoutSeconds(),
                DEFINITION.taskTimeoutSeconds(),
                protocolHash,
                promptHash,
                schemaHash,
                dataset.sha256(),
                scenarioHash,
                safetyHash,
                implementationHash,
                fingerprint
        );
    }

    private static void verifyTaskDefinitionBoundaries() {
        for (JobType type : List.of(JobType.COLLECT_BLOCK, JobType.DEPOSIT_ITEM, JobType.COLLECT_AND_DEPOSIT)) {
            TaskDefinition definition = TaskDefinitionRegistry.get(type);
            require(definition.minimumQuantity() == DEFINITION.minimumQuantity(),
                    type + " minimum quantity differs from frozen protocol");
            require(definition.maximumQuantity() == DEFINITION.maximumQuantity(),
                    type + " maximum quantity differs from frozen protocol");
            require(definition.minimumRadius() == DEFINITION.minimumRadius(),
                    type + " minimum radius differs from frozen protocol");
            require(definition.maximumRadius() == DEFINITION.maximumRadius(),
                    type + " maximum radius differs from frozen protocol");
            require(definition.allowedTargets().equals(Set.copyOf(DEFINITION.allowedTargets())),
                    type + " target whitelist differs from frozen protocol");
        }
    }

    private static String canonicalScenarios() {
        StringBuilder value = new StringBuilder();
        for (ExperimentScenario scenario : ExperimentScenarioRegistry.all()) {
            value.append(scenario.id()).append('|')
                    .append(scenario.instruction()).append('|')
                    .append(scenario.expectedOutcome()).append('|')
                    .append(scenario.setup().name()).append('|')
                    .append(scenario.disturbance().name()).append('\n');
        }
        return value.toString();
    }

    private static String canonicalSafetyBoundary() {
        return String.join("|",
                Integer.toString(DEFINITION.minimumQuantity()),
                Integer.toString(DEFINITION.maximumQuantity()),
                Integer.toString(DEFINITION.minimumRadius()),
                Integer.toString(DEFINITION.maximumRadius()),
                String.join(",", DEFINITION.allowedTargets()),
                "single_accessible_chest",
                "mob_griefing_required",
                "no_friendly_attack",
                "server_authoritative_actions"
        );
    }

    private static Definition loadDefinition() {
        Definition definition = GSON.fromJson(readResource(RESOURCE_PATH), Definition.class);
        if (definition == null
                || definition.protocolVersion() == null
                || definition.model() == null
                || definition.allowedTargets() == null
                || definition.allowedTargets().isEmpty()) {
            throw new IllegalStateException("Frozen experiment protocol is incomplete");
        }
        return definition;
    }

    private static String readResource(String path) {
        return new String(readResourceBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String path) {
        try (InputStream input = ExperimentProtocol.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing frozen resource " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read frozen resource " + path, exception);
        }
    }

    /**
     * 对实验闭环的执行、判定、日志和离线评分实现及其嵌套类生成稳定哈希。
     */
    private static String implementationSha256() {
        TreeSet<String> resourcePaths = new TreeSet<>();
        for (Class<?> root : IMPLEMENTATION_ROOTS) {
            collectClassResources(root, resourcePaths);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String resourcePath : resourcePaths) {
                digest.update(resourcePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(readResourceBytes(resourcePath));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void collectClassResources(Class<?> type, Set<String> resources) {
        resources.add("/" + type.getName().replace('.', '/') + ".class");
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectClassResources(nested, resources);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Experiment protocol mismatch: " + message);
        }
    }

    /**
     * JSON 中声明的冻结参数和保守费用核算上限。
     */
    public record Definition(
            String protocolVersion,
            boolean llmEnabled,
            String endpoint,
            String model,
            String apiKeyEnvironmentVariable,
            double temperature,
            int llmTimeoutSeconds,
            int taskTimeoutSeconds,
            int maxGatewayRetries,
            int maxLocalRetries,
            int maxLlmReplans,
            int maxOutputTokens,
            boolean requestJsonResponseFormat,
            int offlineRequestsPerMinute,
            int offlineMaxAttemptsPerCase,
            double offlineDefaultCostCapUsd,
            double accountingInputUsdPerMillionTokens,
            double accountingOutputUsdPerMillionTokens,
            long gameBatchMinLlmIntervalMillis,
            int minimumQuantity,
            int maximumQuantity,
            int minimumRadius,
            int maximumRadius,
            List<String> allowedTargets
    ) {
        public Definition {
            allowedTargets = allowedTargets == null ? List.of() : List.copyOf(allowedTargets);
        }
    }

    /**
     * 一次实验进程实际使用的完整资源哈希，可写入冻结锁和每个批次清单。
     */
    public record Snapshot(
            String protocolVersion,
            String capturedAt,
            String model,
            double temperature,
            int llmTimeoutSeconds,
            int taskTimeoutSeconds,
            String protocolSha256,
            String promptSha256,
            String schemaSha256,
            String datasetSha256,
            String scenariosSha256,
            String safetyBoundarySha256,
            String implementationSha256,
            String fingerprint
    ) {
    }
}
