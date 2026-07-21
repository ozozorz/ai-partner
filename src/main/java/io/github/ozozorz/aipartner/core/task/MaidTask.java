package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.entity.PartnerMode;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 所有有界女仆工作的统一生命周期。
 */
public interface MaidTask {
    String id();

    void start(MaidTaskContext context);

    void restore(MaidTaskContext context, MaidTaskSnapshot snapshot);

    void tick(MaidTaskContext context);

    void pauseForTick();

    void stop();

    boolean isRunning();

    PartnerMode displayedMode();

    /**
     * 在底层执行器延迟恢复前，根据快照给出正确的客户端显示模式。
     */
    default PartnerMode restoredDisplayedMode(MaidTaskSnapshot snapshot) {
        return displayedMode();
    }

    String executionState();

    MaidTaskSnapshot snapshot();

    /**
     * v0.4 任务可覆盖此方法，把旧字段迁移成统一快照。
     */
    default MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.empty();
    }

    /**
     * v0.5 过渡期继续写旧字段，便于现有研究工具读取。
     */
    default void writeLegacySnapshot(ValueOutput output, MaidTaskSnapshot snapshot) {
    }

    /**
     * 声明当前任务是否允许后台拾取指定物品。
     */
    default boolean acceptsPickup(Item item) {
        return false;
    }
}
