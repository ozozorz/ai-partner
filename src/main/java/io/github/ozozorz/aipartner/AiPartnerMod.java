package io.github.ozozorz.aipartner;

import io.github.ozozorz.aipartner.command.MaidCommand;
import io.github.ozozorz.aipartner.registry.ModEntities;
import io.github.ozozorz.aipartner.registry.ModMemoryModules;
import io.github.ozozorz.aipartner.registry.ModMenus;
import io.github.ozozorz.aipartner.registry.ModItems;
import io.github.ozozorz.aipartner.registry.ModSensorTypes;
import io.github.ozozorz.aipartner.skin.MaidSkinNetworking;
import io.github.ozozorz.aipartner.world.OutpostMaidGeneration;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Partner 的通用入口，负责注册实体、Brain 组件、皮肤网络与命令。
 */
public final class AiPartnerMod implements ModInitializer {
    public static final String MOD_ID = "ai-partner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModMemoryModules.register();
        ModSensorTypes.register();
        ModEntities.register();
        ModItems.register();
        ModMenus.register();
        OutpostMaidGeneration.register();
        MaidSkinNetworking.registerServer();
        MaidCommand.register();
        LOGGER.info("AI Partner initialized for Minecraft 26.1.2 with skill-based maid AI");
    }

    /**
     * 创建模块命名空间下的资源标识。
     *
     * @param path 资源路径
     * @return 完整资源标识
     */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
