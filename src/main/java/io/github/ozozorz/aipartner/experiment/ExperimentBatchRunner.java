package io.github.ozozorz.aipartner.experiment;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.config.AiPartnerConfig;
import io.github.ozozorz.aipartner.contract.FailureCode;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.experiment.ExperimentBatchStore.Checkpoint;
import io.github.ozozorz.aipartner.experiment.ExperimentBatchStore.EpisodeResult;
import io.github.ozozorz.aipartner.experiment.ExperimentBatchStore.PlanItem;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioJudge.Verdict;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioService.Observation;
import io.github.ozozorz.aipartner.experiment.ExperimentScenarioService.SafetySnapshot;
import io.github.ozozorz.aipartner.experiment.ExperimentSessionRegistry.BatchMetadata;
import io.github.ozozorz.aipartner.experiment.VariantExecutionService.SubmissionResult;
import io.github.ozozorz.aipartner.llm.LlmGateway;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
import io.github.ozozorz.aipartner.service.PartnerService;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 在服务器 tick 上自动重置、提交、扰动和判定场景，并在每个 episode 后写可恢复检查点。
 */
public final class ExperimentBatchRunner {
    private static final ExperimentBatchRunner INSTANCE = new ExperimentBatchRunner();
    private static final int RESET_SETTLE_TICKS = 2;
    private static final int TERMINAL_SETTLE_TICKS = 5;
    private static final int CANCEL_SETTLE_TICKS = 10;
    private static final List<String> PRETEST_LLM_SCENARIOS = List.of(
            "collect_normal",
            "collect_missing_tool",
            "composite_normal",
            "target_removed_after_accept",
            "recoverable_target_change"
    );

    private @Nullable Checkpoint checkpoint;
    private @Nullable ActiveEpisode active;
    private @Nullable CompletableFuture<SubmissionResult> pendingSubmission;
    private long lastLlmRequestEpochMillis;

    private ExperimentBatchRunner() {
    }

    /**
     * 注册单个服务端 tick 回调；批次不运行时该回调只做一次空判断。
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(INSTANCE::tick);
    }

    public static ExperimentBatchRunner getInstance() {
        return INSTANCE;
    }

    /**
     * 运行完整预实验：Rule-BT 18 场景一次，随后 B1 与 P 各 5 个诊断场景。
     */
    public synchronized OperationResult startPretest(ServerPlayer player, @Nullable String requestedBatchId) {
        if (!LlmGateway.getInstance().isEnabled()) {
            return OperationResult.failed("LLM is not configured; use rule-bt first or configure the frozen model");
        }
        return startNew(player, requestedBatchId, "PRETEST", createPretestPlan(), false);
    }

    /**
     * 先单独运行 Rule-BT 全部 18 场景，便于在产生模型费用前检查地图和判定器。
     */
    public synchronized OperationResult startRuleBtPretest(
            ServerPlayer player,
            @Nullable String requestedBatchId
    ) {
        return startNew(
                player,
                requestedBatchId,
                "RULE_BT_PRETEST",
                buildSingleVariantPlan(SystemVariant.RULE_BT, 1),
                false
        );
    }

    /**
     * 创建三套主系统的 18×3×重复次数计划；系统顺序按场景和重复号确定性轮换。
     */
    public synchronized OperationResult startMain(
            ServerPlayer player,
            int repetitions,
            @Nullable String requestedBatchId
    ) {
        if (repetitions < 3 || repetitions > 5) {
            return OperationResult.failed("Main repetitions must be between 3 and 5");
        }
        return startNew(player, requestedBatchId, "MAIN", createMainPlan(repetitions), true);
    }

    /**
     * 生成可单元测试的预实验计划，顺序严格保持 Rule-BT 全量在两个小模型样本之前。
     */
    static List<PlanItem> createPretestPlan() {
        List<PlanItem> plan = new ArrayList<>();
        for (ExperimentScenario scenario : ExperimentScenarioRegistry.all()) {
            plan.add(new PlanItem(SystemVariant.RULE_BT.name(), scenario.id(), 1));
        }
        for (SystemVariant variant : List.of(SystemVariant.LLM_SCHEMA, SystemVariant.MAID_IBC)) {
            for (String scenarioId : PRETEST_LLM_SCENARIOS) {
                plan.add(new PlanItem(variant.name(), scenarioId, 1));
            }
        }
        return List.copyOf(plan);
    }

    /**
     * 生成 18×3×repetitions 主实验计划，并使用确定性轮换抵消固定运行顺序。
     */
    static List<PlanItem> createMainPlan(int repetitions) {
        List<SystemVariant> variants = List.of(
                SystemVariant.RULE_BT,
                SystemVariant.LLM_SCHEMA,
                SystemVariant.MAID_IBC
        );
        List<PlanItem> plan = new ArrayList<>();
        List<ExperimentScenario> scenarios = ExperimentScenarioRegistry.all();
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (int scenarioIndex = 0; scenarioIndex < scenarios.size(); scenarioIndex++) {
                ExperimentScenario scenario = scenarios.get(scenarioIndex);
                int rotation = (scenarioIndex + repetition - 1) % variants.size();
                for (int offset = 0; offset < variants.size(); offset++) {
                    SystemVariant variant = variants.get((rotation + offset) % variants.size());
                    plan.add(new PlanItem(variant.name(), scenario.id(), repetition));
                }
            }
        }
        return List.copyOf(plan);
    }

    /**
     * 运行单个系统（包括 A2）的全场景批次，用于消融或诊断。
     */
    public synchronized OperationResult startVariant(
            ServerPlayer player,
            SystemVariant variant,
            int repetitions,
            @Nullable String requestedBatchId
    ) {
        if (repetitions < 1 || repetitions > 5) {
            return OperationResult.failed("Variant repetitions must be between 1 and 5");
        }
        boolean requiresFreeze = variant == SystemVariant.MAID_IBC_A2_NO_RUNTIME_MONITORING;
        return startNew(
                player,
                requestedBatchId,
                requiresFreeze ? "ABLATION_A2" : "VARIANT_DIAGNOSTIC",
                buildSingleVariantPlan(variant, repetitions),
                requiresFreeze
        );
    }

    /**
     * 从 results.jsonl 和 checkpoint.json 的一致前缀继续，活动 episode 会安全重跑。
     */
    public synchronized OperationResult resume(ServerPlayer player, String batchId) {
        if (checkpoint != null) {
            return OperationResult.failed("Another experiment batch is already active");
        }
        try {
            Checkpoint loaded = ExperimentBatchStore.readCheckpoint(batchId);
            if (!loaded.playerId().equals(player.getUUID().toString())) {
                return OperationResult.failed("The original experiment player must resume this batch");
            }
            if ("COMPLETED".equals(loaded.status()) || "ABORTED".equals(loaded.status())) {
                return OperationResult.failed("Batch is already terminal: " + loaded.status());
            }
            ExperimentProtocol.Snapshot current = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
            if (!current.fingerprint().equals(loaded.protocolFingerprint())) {
                return OperationResult.failed("Protocol fingerprint changed since the checkpoint");
            }
            if ("MAIN".equals(loaded.batchKind()) || "ABLATION_A2".equals(loaded.batchKind())) {
                ExperimentFreezeService.requireCurrentLock();
            }
            if (loaded.plan().stream().anyMatch(item -> SystemVariant.parse(item.systemVariant())
                    .map(SystemVariant::usesLlm).orElse(false)) && !LlmGateway.getInstance().isEnabled()) {
                return OperationResult.failed("Frozen LLM is not available");
            }
            checkpoint = loaded.withProgress(loaded.nextIndex(), "RUNNING", "resumed");
            ExperimentBatchStore.writeCheckpoint(checkpoint);
            ExperimentLogger.getInstance().logBatchEvent(
                    checkpoint.batchId(),
                    checkpoint.batchKind(),
                    "RESUMED",
                    checkpoint.nextIndex(),
                    checkpoint.plan().size(),
                    checkpoint.protocolFingerprint(),
                    "checkpoint_resume"
            );
            return OperationResult.succeeded(checkpoint.batchId(), checkpoint.plan().size(), checkpoint.nextIndex());
        } catch (IOException | RuntimeException exception) {
            return OperationResult.failed(exception.getMessage());
        }
    }

    /**
     * 中止当前批次并停止活动任务；已落盘 episode 保持可审计。
     */
    public synchronized OperationResult abort(ServerPlayer player) {
        if (checkpoint == null) {
            return OperationResult.failed("No active experiment batch");
        }
        if (!checkpoint.playerId().equals(player.getUUID().toString())) {
            return OperationResult.failed("Only the batch player may abort this run");
        }
        PartnerService.findOwnedPartner(player)
                .ifPresent(partner -> partner.resetForExperiment(player, false));
        if (pendingSubmission != null) {
            pendingSubmission.cancel(true);
            pendingSubmission = null;
        }
        try {
            checkpoint = checkpoint.withProgress(checkpoint.nextIndex(), "ABORTED", "aborted_by_player");
            ExperimentBatchStore.writeCheckpoint(checkpoint);
            ExperimentBatchStore.writeSummary(checkpoint);
            ExperimentLogger.getInstance().logBatchEvent(
                    checkpoint.batchId(),
                    checkpoint.batchKind(),
                    "ABORTED",
                    checkpoint.nextIndex(),
                    checkpoint.plan().size(),
                    checkpoint.protocolFingerprint(),
                    "aborted_by_player"
            );
            String batchId = checkpoint.batchId();
            int total = checkpoint.plan().size();
            int next = checkpoint.nextIndex();
            checkpoint = null;
            active = null;
            ExperimentSessionRegistry.clear(player);
            return OperationResult.succeeded(batchId, total, next);
        } catch (IOException exception) {
            return OperationResult.failed(exception.getMessage());
        }
    }

    public synchronized Status status() {
        if (checkpoint == null) {
            return new Status(false, "", "IDLE", "", 0, 0, "NONE");
        }
        return new Status(
                true,
                checkpoint.batchId(),
                checkpoint.status(),
                checkpoint.batchKind(),
                checkpoint.nextIndex(),
                checkpoint.plan().size(),
                active == null ? "BETWEEN_EPISODES" : active.phase.name()
        );
    }

    private OperationResult startNew(
            ServerPlayer player,
            @Nullable String requestedBatchId,
            String batchKind,
            List<PlanItem> plan,
            boolean requireFreeze
    ) {
        if (checkpoint != null) {
            return OperationResult.failed("Another experiment batch is already active");
        }
        try {
            ExperimentProtocol.Snapshot snapshot = ExperimentProtocol.verifyAndSnapshot(AiPartnerConfig.get());
            if (requireFreeze) {
                ExperimentFreezeService.FreezeLock lock = ExperimentFreezeService.requireCurrentLock();
                if (!lock.snapshot().fingerprint().equals(snapshot.fingerprint())) {
                    return OperationResult.failed("Current protocol does not match the v0.4 freeze lock");
                }
            }
            boolean usesLlm = plan.stream().anyMatch(item -> SystemVariant.parse(item.systemVariant())
                    .map(SystemVariant::usesLlm).orElse(false));
            if (usesLlm && !LlmGateway.getInstance().isEnabled()) {
                return OperationResult.failed("Frozen LLM is not configured");
            }
            String batchId = requestedBatchId == null || requestedBatchId.isBlank()
                    ? generateBatchId(batchKind)
                    : requestedBatchId;
            ExperimentBatchStore.batchDirectory(batchId);
            if (java.nio.file.Files.exists(ExperimentBatchStore.batchDirectory(batchId).resolve("checkpoint.json"))) {
                return OperationResult.failed("Batch id already exists; use resume instead");
            }
            checkpoint = Checkpoint.create(
                    batchId,
                    batchKind,
                    player.getUUID(),
                    snapshot.fingerprint(),
                    plan
            );
            ExperimentBatchStore.writeCheckpoint(checkpoint);
            ExperimentLogger.getInstance().logBatchEvent(
                    batchId,
                    batchKind,
                    "STARTED",
                    0,
                    plan.size(),
                    snapshot.fingerprint(),
                    "new_batch"
            );
            return OperationResult.succeeded(batchId, plan.size(), 0);
        } catch (IOException | RuntimeException exception) {
            checkpoint = null;
            return OperationResult.failed(exception.getMessage());
        }
    }

    private synchronized void tick(MinecraftServer server) {
        if (checkpoint == null || !"RUNNING".equals(checkpoint.status())) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(checkpoint.playerId()));
        if (player == null) {
            pause("player_offline");
            return;
        }
        try {
            if (active == null) {
                if (checkpoint.nextIndex() >= checkpoint.plan().size()) {
                    completeBatch(player);
                } else {
                    beginEpisode(player);
                }
                return;
            }
            active.elapsedTicks++;
            switch (active.phase) {
                case RESET_SETTLE -> tickResetSettle(server, player);
                case WAITING_SUBMISSION -> tickWaitingSubmission();
                case RUNNING -> tickRunning(player);
                case TERMINAL_SETTLE -> tickTerminalSettle(player);
            }
        } catch (IOException | RuntimeException exception) {
            AiPartnerMod.LOGGER.error("Experiment batch paused after an unexpected error", exception);
            PartnerService.findOwnedPartner(player)
                    .ifPresent(partner -> partner.cancelActiveTaskForExperiment(player, "batch_paused_after_error"));
            pause("error:" + exception.getClass().getSimpleName());
        }
    }

    private void beginEpisode(ServerPlayer player) throws IOException {
        PlanItem item = checkpoint.plan().get(checkpoint.nextIndex());
        SystemVariant variant = SystemVariant.parse(item.systemVariant())
                .orElseThrow(() -> new IllegalStateException("Unknown system variant in checkpoint"));
        ExperimentScenario scenario = ExperimentScenarioRegistry.find(item.scenarioId())
                .orElseThrow(() -> new IllegalStateException("Unknown scenario in checkpoint"));
        BatchMetadata metadata = new BatchMetadata(
                checkpoint.batchId(),
                variant.name(),
                item.repetition(),
                checkpoint.nextIndex(),
                checkpoint.batchKind(),
                checkpoint.protocolFingerprint()
        );
        ExperimentScenarioService.Result reset = ExperimentScenarioService.reset(player, scenario, metadata);
        if (!reset.successful() || reset.context() == null) {
            throw new IllegalStateException("Scenario reset failed: " + reset.error());
        }
        SafetySnapshot snapshot = ExperimentScenarioService.captureSafetySnapshot(player, reset.context());
        active = new ActiveEpisode(
                item,
                variant,
                scenario,
                reset.context(),
                snapshot,
                Phase.RESET_SETTLE
        );
    }

    private void tickResetSettle(MinecraftServer server, ServerPlayer player) {
        if (active.elapsedTicks < RESET_SETTLE_TICKS) {
            return;
        }
        if (active.variant.usesLlm()) {
            long minimumInterval = ExperimentProtocol.definition().gameBatchMinLlmIntervalMillis();
            if (System.currentTimeMillis() - lastLlmRequestEpochMillis < minimumInterval) {
                return;
            }
            lastLlmRequestEpochMillis = System.currentTimeMillis();
        }
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .orElseThrow(() -> new IllegalStateException("Experiment partner disappeared after reset"));
        active.phase = Phase.WAITING_SUBMISSION;
        UUID episodeId = active.context.episodeId();
        pendingSubmission = VariantExecutionService.submit(
                active.variant,
                player,
                partner,
                active.scenario.instruction()
        );
        pendingSubmission.whenComplete((submission, throwable) -> server.execute(() -> {
            synchronized (ExperimentBatchRunner.this) {
                if (active == null || !active.context.episodeId().equals(episodeId)) {
                    return;
                }
                pendingSubmission = null;
                if (throwable != null || submission == null) {
                    active.submission = new SubmissionResult(
                            active.variant,
                            null,
                            null,
                            false,
                            false,
                            "MODEL_CALLBACK_ERROR",
                            null,
                            null
                    );
                } else {
                    active.submission = submission;
                }
            }
        }));
    }

    private void tickWaitingSubmission() {
        if (active.submission == null) {
            return;
        }
        if (!active.submission.scheduled() || active.submission.contract() == null) {
            active.phase = Phase.TERMINAL_SETTLE;
            active.settleTicks = TERMINAL_SETTLE_TICKS;
            return;
        }
        active.phase = Phase.RUNNING;
        active.runTicks = 0;
    }

    private void tickRunning(ServerPlayer player) {
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .orElseThrow(() -> new IllegalStateException("Experiment partner disappeared while running"));
        active.runTicks++;

        if (active.scenario.setup() == ExperimentScenario.Setup.CANCEL_COLLECT && !active.cancellationApplied) {
            if (partner.activeExecutionState().contains("NAVIGATE") || active.runTicks >= 20) {
                partner.cancelActiveTaskForExperiment(player, "scheduled_experiment_cancel");
                active.cancellationApplied = true;
                active.phase = Phase.TERMINAL_SETTLE;
                active.settleTicks = CANCEL_SETTLE_TICKS;
                return;
            }
        }

        if (active.scenario.disturbance() != ExperimentScenario.Disturbance.NONE
                && !active.disturbanceAttempted
                && partner.activeExecutionState().contains("NAVIGATE")) {
            active.disturbanceAttempted = true;
            ExperimentScenarioService.Result result = ExperimentScenarioService.disturb(player);
            active.disturbanceApplied = result.successful();
        }

        if (active.submission.contract().status().isTerminal()) {
            active.phase = Phase.TERMINAL_SETTLE;
            active.settleTicks = TERMINAL_SETTLE_TICKS;
            return;
        }
        int maximumTicks = ExperimentProtocol.definition().taskTimeoutSeconds() * 20 + 200;
        if (active.runTicks > maximumTicks) {
            partner.failActiveContract(FailureCode.TIMEOUT);
            active.phase = Phase.TERMINAL_SETTLE;
            active.settleTicks = TERMINAL_SETTLE_TICKS;
        }
    }

    private void tickTerminalSettle(ServerPlayer player) throws IOException {
        if (active.settleTicks-- > 0) {
            return;
        }
        AiPartnerEntity partner = PartnerService.findOwnedPartner(player)
                .orElseThrow(() -> new IllegalStateException("Experiment partner disappeared before judgment"));
        Observation observation = ExperimentScenarioService.observe(
                player,
                active.context,
                active.scenario,
                active.initialSnapshot
        );
        Verdict verdict = ExperimentScenarioJudge.judge(
                active.scenario,
                active.submission,
                active.initialSnapshot,
                observation,
                partner.getRuntimeRecoveryCount(),
                active.disturbanceApplied
        );
        ExperimentLogger.getInstance().logEpisodeResult(
                player,
                partner,
                active.context,
                active.scenario.instruction(),
                active.submission,
                verdict,
                active.elapsedTicks
        );
        ExperimentBatchStore.appendResult(checkpoint.batchId(), new EpisodeResult(
                checkpoint.nextIndex(),
                active.context.episodeId().toString(),
                active.variant.name(),
                active.scenario.id(),
                active.item.repetition(),
                verdict.expectedOutcome(),
                verdict.actualOutcome(),
                verdict.passed(),
                verdict.excludedOperationalError(),
                verdict.goalSatisfied(),
                verdict.safetySatisfied(),
                verdict.ibcConsistent(),
                verdict.runtimeRecoveries(),
                verdict.disturbanceApplied(),
                active.elapsedTicks
        ));
        checkpoint = checkpoint.withProgress(checkpoint.nextIndex() + 1, "RUNNING", "episode_complete");
        ExperimentBatchStore.writeCheckpoint(checkpoint);
        active = null;
    }

    private void completeBatch(ServerPlayer player) throws IOException {
        checkpoint = checkpoint.withProgress(checkpoint.plan().size(), "COMPLETED", "all_episodes_complete");
        ExperimentBatchStore.writeCheckpoint(checkpoint);
        ExperimentBatchStore.Summary summary = ExperimentBatchStore.writeSummary(checkpoint);
        ExperimentLogger.getInstance().logBatchEvent(
                checkpoint.batchId(),
                checkpoint.batchKind(),
                "COMPLETED",
                checkpoint.nextIndex(),
                checkpoint.plan().size(),
                checkpoint.protocolFingerprint(),
                "passed=" + summary.passed() + ",excluded=" + summary.excluded() + ",ibcr=" + summary.ibcr()
        );
        player.sendSystemMessage(Component.literal(
                "AI Partner batch " + checkpoint.batchId() + " completed: "
                        + summary.completedEpisodes() + "/" + summary.plannedEpisodes()
                        + ", excluded=" + summary.excluded()
        ));
        PartnerService.findOwnedPartner(player)
                .ifPresent(partner -> partner.resetForExperiment(player, false));
        ExperimentSessionRegistry.clear(player);
        checkpoint = null;
        active = null;
    }

    private void pause(String reason) {
        if (checkpoint == null) {
            return;
        }
        if (pendingSubmission != null) {
            pendingSubmission.cancel(true);
            pendingSubmission = null;
        }
        try {
            checkpoint = checkpoint.withProgress(checkpoint.nextIndex(), "PAUSED", reason);
            ExperimentBatchStore.writeCheckpoint(checkpoint);
            ExperimentLogger.getInstance().logBatchEvent(
                    checkpoint.batchId(),
                    checkpoint.batchKind(),
                    "PAUSED",
                    checkpoint.nextIndex(),
                    checkpoint.plan().size(),
                    checkpoint.protocolFingerprint(),
                    reason
            );
        } catch (IOException exception) {
            AiPartnerMod.LOGGER.error("Failed to persist paused experiment batch", exception);
        }
        checkpoint = null;
        active = null;
    }

    static List<PlanItem> buildSingleVariantPlan(SystemVariant variant, int repetitions) {
        List<PlanItem> plan = new ArrayList<>();
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (ExperimentScenario scenario : ExperimentScenarioRegistry.all()) {
                plan.add(new PlanItem(variant.name(), scenario.id(), repetition));
            }
        }
        return List.copyOf(plan);
    }

    private static String generateBatchId(String kind) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        return "v04-" + kind.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + "-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private enum Phase {
        RESET_SETTLE,
        WAITING_SUBMISSION,
        RUNNING,
        TERMINAL_SETTLE
    }

    /**
     * 当前 episode 的易失运行状态；崩溃后从对应 plan_index 重新重置即可恢复。
     */
    private static final class ActiveEpisode {
        private final PlanItem item;
        private final SystemVariant variant;
        private final ExperimentScenario scenario;
        private final ExperimentSessionRegistry.Context context;
        private final SafetySnapshot initialSnapshot;
        private Phase phase;
        private @Nullable SubmissionResult submission;
        private long elapsedTicks;
        private int runTicks;
        private int settleTicks;
        private boolean disturbanceAttempted;
        private boolean disturbanceApplied;
        private boolean cancellationApplied;

        private ActiveEpisode(
                PlanItem item,
                SystemVariant variant,
                ExperimentScenario scenario,
                ExperimentSessionRegistry.Context context,
                SafetySnapshot initialSnapshot,
                Phase phase
        ) {
            this.item = item;
            this.variant = variant;
            this.scenario = scenario;
            this.context = context;
            this.initialSnapshot = initialSnapshot;
            this.phase = phase;
        }
    }

    public record OperationResult(boolean successful, String error, String batchId, int total, int nextIndex) {
        private static OperationResult succeeded(String batchId, int total, int nextIndex) {
            return new OperationResult(true, "", batchId, total, nextIndex);
        }

        private static OperationResult failed(@Nullable String error) {
            return new OperationResult(false, error == null ? "unknown_error" : error, "", 0, 0);
        }
    }

    public record Status(
            boolean active,
            String batchId,
            String status,
            String batchKind,
            int nextIndex,
            int total,
            String phase
    ) {
    }
}
