package io.github.ozozorz.aipartner.registry;

import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.inventory.AiPartnerMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.MenuType;

/**
 * 注册 AI Partner 的服务端菜单类型及其实体编号打开数据。
 */
public final class ModMenus {
    public static final MenuType<AiPartnerMenu> AI_PARTNER = registerAiPartnerMenu();

    private ModMenus() {
    }

    public static void register() {
        AiPartnerMod.LOGGER.info("Registered AI Partner menu type");
    }

    private static MenuType<AiPartnerMenu> registerAiPartnerMenu() {
        ResourceKey<MenuType<?>> key = ResourceKey.create(Registries.MENU, AiPartnerMod.id("ai_partner"));
        ExtendedMenuType<AiPartnerMenu, Integer> type = new ExtendedMenuType<>(
                AiPartnerMenu::new,
                ByteBufCodecs.VAR_INT
        );
        return Registry.register(BuiltInRegistries.MENU, key, type);
    }
}
