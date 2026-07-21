package io.github.ozozorz.aipartner.client;

import io.github.ozozorz.aipartner.client.render.AiPartnerRenderer;
import io.github.ozozorz.aipartner.client.render.MaidFishingHookRenderer;
import io.github.ozozorz.aipartner.client.screen.AiPartnerScreen;
import io.github.ozozorz.aipartner.client.skin.MaidSkinClient;
import io.github.ozozorz.aipartner.registry.ModEntities;
import io.github.ozozorz.aipartner.registry.ModMenus;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * 客户端入口，只注册实体表现，不参与任务决策。
 */
public final class AiPartnerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(ModEntities.AI_PARTNER, AiPartnerRenderer::new);
        EntityRenderers.register(ModEntities.MAID_FISHING_HOOK, MaidFishingHookRenderer::new);
        MenuScreens.register(ModMenus.AI_PARTNER, AiPartnerScreen::new);
        MaidSkinClient.register();
    }
}
