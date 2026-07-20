package io.github.ozozorz.aipartner.client;

import io.github.ozozorz.aipartner.client.render.AiPartnerRenderer;
import io.github.ozozorz.aipartner.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * 客户端入口，只注册实体表现，不参与任务决策。
 */
public final class AiPartnerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(ModEntities.AI_PARTNER, AiPartnerRenderer::new);
    }
}

