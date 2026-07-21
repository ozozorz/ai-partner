package io.github.ozozorz.aipartner.work;

import java.util.Optional;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 一个持续工作模式的目标匹配、动态复验和原子动作策略。
 */
public interface MaidWorkRule {
    MaidWorkMode mode();

    default boolean scansBlocks() {
        return true;
    }

    default boolean matchesBlock(MaidWorkContext context, BlockPos position, BlockState state) {
        return false;
    }

    default Optional<WorkTarget> findEntityTarget(MaidWorkContext context) {
        return Optional.empty();
    }

    default boolean prioritizesEntityTargets() {
        return false;
    }

    /**
     * 返回规则内部已经批准的下一目标，例如一棵树中按安全顺序处理的下一段原木。
     */
    default Optional<WorkTarget> findPriorityTarget(MaidWorkContext context) {
        return Optional.empty();
    }

    boolean isStillValid(MaidWorkContext context, WorkTarget target);

    WorkActionResult perform(MaidWorkContext context, WorkTarget target);

    default int successCooldownTicks() {
        return 20;
    }

    /**
     * 熔炉、钓鱼等长流程可自行管理阶段；普通原子规则继续使用控制器通用状态机。
     */
    default boolean managesOwnExecution() {
        return false;
    }

    /**
     * 是否会直接改变方块、流体或其他受 mobGriefing 保护的世界状态。
     * 仅操作已有工作站或执行原版钓鱼的规则可返回 false。
     */
    default boolean requiresMobGriefing() {
        return true;
    }

    default void tickManaged(MaidWorkContext context) {
        throw new IllegalStateException("Managed work rule did not implement tickManaged");
    }

    default String managedExecutionState() {
        return "IDLE";
    }

    /** 临时被日程、GUI、有限任务或战斗覆盖时清理不能后台存在的资源。 */
    default void onSuspended(MaidWorkContext context) {
    }

    /** 玩家切换到其他工作模式时释放租约、浮标和可重算计划。 */
    default void onDeselected(ServerLevel level, AiPartnerEntity partner) {
    }

    /** 保存规则自己的最小稳定阶段；临时扫描游标仍不进入存档。 */
    default void save(ValueOutput output) {
    }

    /** 从实体存档恢复规则阶段，并在下一服务端 tick 重新验证世界状态。 */
    default void load(ValueInput input) {
    }
}
