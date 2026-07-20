package io.github.ozozorz.aipartner.client.render;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;

/**
 * 临时复用 Minecraft 内置 Alex 瘦手臂模型和官方贴图的女仆渲染器。
 */
public final class AiPartnerRenderer extends MobRenderer<AiPartnerEntity, AvatarRenderState, PlayerModel> {
    private static final Identifier ALEX_TEXTURE = Identifier.withDefaultNamespace("entity/player/slim/alex");

    public AiPartnerRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.35F);
    }

    @Override
    public AvatarRenderState createRenderState() {
        return new AvatarRenderState();
    }

    @Override
    public void extractRenderState(AiPartnerEntity entity, AvatarRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, itemModelResolver);
        state.isSpectator = false;
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showCape = false;
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return ALEX_TEXTURE;
    }
}

