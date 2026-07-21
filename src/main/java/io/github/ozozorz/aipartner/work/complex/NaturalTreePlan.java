package io.github.ozozorz.aipartner.work.complex;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 一次已经通过保守自然树判定的有限原木集合；顺序固定为从高到低，根部最后处理。
 */
public record NaturalTreePlan(List<BlockPos> orderedLogs) {
    public NaturalTreePlan {
        orderedLogs = orderedLogs.stream().map(BlockPos::immutable).toList();
        if (orderedLogs.isEmpty() || orderedLogs.size() > NaturalTreePolicy.MAX_LOGS) {
            throw new IllegalArgumentException("Natural tree plan size is outside the safe range");
        }
    }
}
