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
    private static final String BATCH_ID = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)
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
        Context context = new Context(
                UUID.randomUUID(),
                BATCH_ID,
                scenario.id(),
                scenario.expectedOutcome(),
                anchor.immutable(),
                player.level().getSeed(),
                player.level().dimension().identifier().toString()
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
            String dimension
    ) {
    }
}
