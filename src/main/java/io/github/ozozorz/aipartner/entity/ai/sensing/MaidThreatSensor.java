package io.github.ozozorz.aipartner.entity.ai.sensing;

import com.google.common.collect.ImmutableSet;
import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;

/**
 * 从自身受击和主人战斗记录中选择防御目标，不主动扫描中立生物。
 */
public final class MaidThreatSensor extends Sensor<AiPartnerEntity> {
    public MaidThreatSensor() {
        super(5);
    }

    @Override
    protected void doTick(ServerLevel level, AiPartnerEntity maid) {
        Brain<AiPartnerEntity> brain = maid.getBrain();
        if (!maid.isTame() || maid.isInventoryMenuOpen()) {
            brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            maid.clearBrainCombatTarget();
            return;
        }

        LivingEntity current = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (maid.isLegalCombatTarget(current)) {
            return;
        }
        maid.selectDefensiveThreat().ifPresentOrElse(
                target -> brain.setMemory(MemoryModuleType.ATTACK_TARGET, target),
                () -> brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
        );
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.ATTACK_TARGET);
    }
}
