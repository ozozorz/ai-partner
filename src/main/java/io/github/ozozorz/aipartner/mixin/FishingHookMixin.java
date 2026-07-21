package io.github.ozozorz.aipartner.mixin;

import io.github.ozozorz.aipartner.entity.MaidFishingHookEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 将原版浮标中仅面向玩家的两个生命周期钩子适配到女仆浮标。
 * 普通玩家浮标不会进入这些分支，因而原版行为保持不变。
 */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
    @Inject(method = "shouldStopFishing", at = @At("HEAD"), cancellable = true)
    private void aiPartner$validateMaidOwner(Player ignoredOwner, CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof MaidFishingHookEntity maidHook) {
            callback.setReturnValue(maidHook.shouldStopMaidFishing());
        }
    }

    @Inject(method = "updateOwnerInfo", at = @At("HEAD"), cancellable = true)
    private void aiPartner$leavePlayerFishingSlotUntouched(
            @Nullable FishingHook ignoredHook,
            CallbackInfo callback
    ) {
        if ((Object) this instanceof MaidFishingHookEntity) {
            callback.cancel();
        }
    }
}
