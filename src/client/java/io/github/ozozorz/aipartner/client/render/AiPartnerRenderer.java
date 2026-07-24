package io.github.ozozorz.aipartner.client.render;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.client.skin.SkinTextureCache;
import net.minecraft.core.ClientAsset;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;

/**
 * 使用瘦臂模型、模组内置女仆皮肤、手持物层和护甲层渲染 AI 女仆。
 */
public final class AiPartnerRenderer
        extends HumanoidMobRenderer<AiPartnerEntity, AvatarRenderState, PlayerModel> {
    private static final Identifier DEFAULT_MAID_TEXTURE =
            AiPartnerMod.id("textures/entity/maid.png");

    public AiPartnerRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.35F);
        addLayer(new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(
                        ModelLayers.PLAYER_SLIM_ARMOR,
                        context.getModelSet(),
                        modelPart -> new PlayerModel(modelPart, true)
                ),
                context.getEquipmentRenderer()
        ));
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(AiPartnerEntity partner, HumanoidArm arm) {
        HumanoidModel.ArmPose specializedPose = super.getArmPose(partner, arm);
        ItemStack heldItem = partner.getItemHeldByArm(arm);
        return specializedPose == HumanoidModel.ArmPose.EMPTY && !heldItem.isEmpty()
                ? HumanoidModel.ArmPose.ITEM
                : specializedPose;
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(AiPartnerEntity entity, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.isSpectator = false;
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showCape = false;
        Identifier bodyTexture = SkinTextureCache.location(entity.getSkinHash()).orElse(DEFAULT_MAID_TEXTURE);
        state.skin = PlayerSkin.insecure(
                new ClientAsset.DownloadedTexture(bodyTexture, "ai-partner-local"),
                null,
                null,
                PlayerModelType.SLIM
        );
        entity.getActiveSpeechBubble().ifPresent(bubble -> {
            state.scoreText = bubble;
            if (state.nameTagAttachment == null) {
                state.nameTagAttachment = entity.getAttachments().getNullable(
                        EntityAttachment.NAME_TAG,
                        0,
                        entity.getYRot(partialTicks)
                );
            }
        });
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
