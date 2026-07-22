package io.github.ozozorz.aipartner.core.task;

import io.github.ozozorz.aipartner.entity.PartnerMode;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;

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

    /** Reads a legacy snapshot so existing worlds can migrate to the current task format. */
    default MaidTaskSnapshot readLegacySnapshot(ValueInput input) {
        return MaidTaskSnapshot.empty();
    }

    /**
     * 声明当前任务是否允许后台拾取指定物品。
     */
    default boolean acceptsPickup(Item item) {
        return false;
    }
}
