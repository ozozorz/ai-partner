package io.github.ozozorz.aipartner.life;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 一个带维度和允许半径的活动地点快照。
 */
public record ActivityLocation(String dimensionId, BlockPos position, int radius) {
    public ActivityLocation {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Activity location dimension may not be blank");
        }
        position = position.immutable();
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("Activity location radius must be between 1 and 64");
        }
    }

    public static ActivityLocation at(Level level, BlockPos position, int radius) {
        return new ActivityLocation(level.dimension().identifier().toString(), position, radius);
    }

    public boolean isIn(Level level) {
        return dimensionId.equals(level.dimension().identifier().toString());
    }

    public boolean contains(Level level, BlockPos candidate) {
        return isIn(level) && position.distSqr(candidate) <= (double) radius * radius;
    }

    /**
     * 使用稳定前缀字段保存地点，便于新增独立活动位置而不改变格式。
     */
    public void save(ValueOutput output, String prefix) {
        output.putString(prefix + "Dimension", dimensionId);
        output.putLong(prefix + "Position", position.asLong());
        output.putInt(prefix + "Radius", radius);
    }

    public static Optional<ActivityLocation> load(ValueInput input, String prefix) {
        Optional<String> dimension = input.getString(prefix + "Dimension");
        if (dimension.isEmpty()) {
            return Optional.empty();
        }
        int radius = input.getIntOr(prefix + "Radius", 8);
        if (radius < 1 || radius > 64) {
            return Optional.empty();
        }
        return Optional.of(new ActivityLocation(
                dimension.get(),
                BlockPos.of(input.getLongOr(prefix + "Position", BlockPos.ZERO.asLong())),
                radius
        ));
    }
}
