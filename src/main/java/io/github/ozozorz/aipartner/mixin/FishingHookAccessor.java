package io.github.ozozorz.aipartner.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 暴露原版浮标的咬钩计时和附魔参数，供女仆浮标复用完整原版逻辑。 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
    @Accessor("nibble")
    int aiPartner$getNibble();

    @Mutable
    @Accessor("luck")
    void aiPartner$setLuck(int luck);

    @Mutable
    @Accessor("lureSpeed")
    void aiPartner$setLureSpeed(int lureSpeed);
}
