package io.github.ozozorz.aipartner;

import io.github.ozozorz.aipartner.command.MaidCommand;
import io.github.ozozorz.aipartner.conversation.MaidConversationNetworking;
import io.github.ozozorz.aipartner.conversation.MaidConversationService;
import io.github.ozozorz.aipartner.llm.MaidControlLlmGateway;
import io.github.ozozorz.aipartner.evaluation.OfflineLlmEvaluationService;
import io.github.ozozorz.aipartner.experiment.ExperimentBatchRunner;
import io.github.ozozorz.aipartner.experiment.ExperimentEventBridge;
import io.github.ozozorz.aipartner.logging.ExperimentLogger;
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
        ExperimentEventBridge.register();
        MaidCommand.register();
        ExperimentBatchRunner.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MaidConversationService.cancelAll();
            OfflineLlmEvaluationService.getInstance().pauseForServerStop();
            ExperimentLogger.getInstance().flush();
        });
        LOGGER.info(
                "AI Partner initialized for Minecraft 26.1.2; gameplay LLM model={} (readiness is configured per maid)",
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
