package io.github.ozozorz.aipartner;

import io.github.ozozorz.aipartner.command.MaidCommand;
import io.github.ozozorz.aipartner.conversation.MaidConversationNetworking;
import io.github.ozozorz.aipartner.conversation.MaidConversationService;
import io.github.ozozorz.aipartner.llm.MaidControlLlmGateway;
import io.github.ozozorz.aipartner.registry.ModEntities;
import io.github.ozozorz.aipartner.registry.ModMenus;
import io.github.ozozorz.aipartner.skin.MaidSkinNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Partner 的服务端与通用入口，负责注册实体和命令。
 */
public final class AiPartnerMod implements ModInitializer {
    public static final String MOD_ID = "ai-partner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModEntities.register();
        ModMenus.register();
        MaidSkinNetworking.registerServer();
        MaidConversationNetworking.registerServer();
        MaidConversationService.register();
        MaidCommand.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MaidConversationService.cancelAll());
        LOGGER.info(
                "AI Partner initialized for Minecraft 26.1.2; gameplay LLM model={} (driver mode is per maid; credentials are server-owned)",
                MaidControlLlmGateway.getInstance().model()
        );
    }

    /**
     * 创建模组命名空间下的资源标识。
     *
     * @param path 资源路径
     * @return 完整资源标识
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
