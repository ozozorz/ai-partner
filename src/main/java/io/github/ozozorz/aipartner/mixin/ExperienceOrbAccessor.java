package io.github.ozozorz.aipartner.mixin;

import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 允许女仆按原版逐个消费合并经验球中的内部计数。
 */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbAccessor {
    @Accessor("count")
    int aiPartner$getCount();

    @Accessor("count")
    void aiPartner$setCount(int count);
}
