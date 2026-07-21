package io.github.ozozorz.aipartner.experiment;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 保存当前服务器进程中的实验批次与玩家场景上下文，供命令和异步日志关联。
 */
public final class ExperimentSessionRegistry {
    private static final String MANUAL_BATCH_ID = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    private static final ConcurrentHashMap<UUID, Context> ACTIVE = new ConcurrentHashMap<>();

    private ExperimentSessionRegistry() {
    }

    /**
     * 为一次场景重置创建新的 episode，并替换该玩家的旧实验上下文。
     */
    public static Context begin(
            ServerPlayer player,
            ExperimentScenario scenario,
            BlockPos anchor
    ) {
        return begin(player, scenario, anchor, BatchMetadata.manual());
    }

    /**
     * 为自动批处理创建带计划位置、重复号和协议指纹的 episode 上下文。
     */
    public static Context begin(
            ServerPlayer player,
            ExperimentScenario scenario,
            BlockPos anchor,
            BatchMetadata metadata
    ) {
        Context context = new Context(
                UUID.randomUUID(),
                metadata.batchId(),
                scenario.id(),
                scenario.expectedOutcome(),
                anchor.immutable(),
                player.level().getSeed(),
                player.level().dimension().identifier().toString(),
                metadata.systemVariant(),
                metadata.repetition(),
                metadata.planIndex(),
                metadata.batchKind(),
                metadata.protocolFingerprint()
        );
        ACTIVE.put(player.getUUID(), context);
        return context;
    }

    public static Optional<Context> current(ServerPlayer player) {
        return Optional.ofNullable(ACTIVE.get(player.getUUID()));
    }

    public static void clear(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
    }

    /**
     * 单次实验的不可变日志上下文。
     */
    public record Context(
            UUID episodeId,
            String batchId,
            String scenarioId,
            String expectedOutcome,
            BlockPos anchor,
            long worldSeed,
            String dimension,
            String systemVariant,
            int repetition,
            int planIndex,
            String batchKind,
            String protocolFingerprint
    ) {
    }

    /**
     * 自动化计划写入场景上下文的不可变批次元数据。
     */
    public record BatchMetadata(
            String batchId,
            String systemVariant,
            int repetition,
            int planIndex,
            String batchKind,
            String protocolFingerprint
    ) {
        public BatchMetadata {
            if (batchId == null || !batchId.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Invalid experiment batch id");
            }
        }

        private static BatchMetadata manual() {
            return new BatchMetadata(MANUAL_BATCH_ID, "MANUAL", 0, -1, "MANUAL", "UNFROZEN");
        }
    }
}
