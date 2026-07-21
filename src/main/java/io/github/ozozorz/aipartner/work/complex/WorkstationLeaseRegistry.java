package io.github.ozozorz.aipartner.work.complex;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * 进程内原版工作站租约表，防止多名女仆同时写入同一熔炉；重启后由存档阶段重新获取。
 */
public final class WorkstationLeaseRegistry {
    private static final Map<StationKey, Lease> LEASES = new HashMap<>();

    private WorkstationLeaseRegistry() {
    }

    public static synchronized Optional<UUID> acquire(
            ResourceKey<Level> dimension,
            BlockPos position,
            UUID maidId,
            @Nullable UUID requestedLeaseId
    ) {
        StationKey key = new StationKey(dimension, position.asLong());
        Lease existing = LEASES.get(key);
        if (existing != null) {
            return existing.maidId().equals(maidId)
                    && (requestedLeaseId == null || existing.leaseId().equals(requestedLeaseId))
                    ? Optional.of(existing.leaseId())
                    : Optional.empty();
        }
        UUID leaseId = requestedLeaseId == null ? UUID.randomUUID() : requestedLeaseId;
        LEASES.put(key, new Lease(maidId, leaseId));
        return Optional.of(leaseId);
    }

    public static synchronized boolean isAvailable(
            ResourceKey<Level> dimension,
            BlockPos position,
            UUID maidId
    ) {
        Lease existing = LEASES.get(new StationKey(dimension, position.asLong()));
        return existing == null || existing.maidId().equals(maidId);
    }

    public static synchronized boolean isHeldBy(
            ResourceKey<Level> dimension,
            BlockPos position,
            UUID maidId,
            UUID leaseId
    ) {
        Lease existing = LEASES.get(new StationKey(dimension, position.asLong()));
        return existing != null && existing.maidId().equals(maidId) && existing.leaseId().equals(leaseId);
    }

    public static synchronized void release(
            ResourceKey<Level> dimension,
            BlockPos position,
            UUID maidId,
            @Nullable UUID leaseId
    ) {
        StationKey key = new StationKey(dimension, position.asLong());
        Lease existing = LEASES.get(key);
        if (existing != null
                && existing.maidId().equals(maidId)
                && (leaseId == null || existing.leaseId().equals(leaseId))) {
            LEASES.remove(key);
        }
    }

    /** 测试或服务器关闭清理入口，不应在正常工作阶段清空活动租约。 */
    public static synchronized void clear() {
        LEASES.clear();
    }

    private record StationKey(ResourceKey<Level> dimension, long position) {
    }

    private record Lease(UUID maidId, UUID leaseId) {
    }
}
