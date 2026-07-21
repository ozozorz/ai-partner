package io.github.ozozorz.aipartner.client.skin;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.ozozorz.aipartner.AiPartnerMod;
import io.github.ozozorz.aipartner.skin.MaidSkinDataPayload;
import io.github.ozozorz.aipartner.skin.SkinImageValidator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 把服务端校验后的皮肤注册为客户端动态纹理，并按内容哈希复用。
 */
public final class SkinTextureCache {
    private static final Map<String, Identifier> TEXTURES = new LinkedHashMap<>();

    private SkinTextureCache() {
    }

    public static Optional<Identifier> location(String hash) {
        return Optional.ofNullable(TEXTURES.get(hash));
    }

    public static void accept(Minecraft client, MaidSkinDataPayload payload) {
        if (payload.hash().isEmpty() || payload.pngBytes().length == 0 || TEXTURES.containsKey(payload.hash())) {
            return;
        }
        try {
            SkinImageValidator.ValidatedSkin validated = SkinImageValidator.validate(payload.pngBytes());
            if (!validated.sha256().equals(payload.hash())) {
                throw new IllegalArgumentException("Skin payload hash mismatch");
            }
            NativeImage image = NativeImage.read(validated.pngBytes());
            Identifier location = AiPartnerMod.id("dynamic_skin/" + payload.hash());
            client.getTextureManager().register(
                    location,
                    new DynamicTexture(() -> "AI Partner skin " + payload.hash(), image)
            );
            TEXTURES.put(payload.hash(), location);
        } catch (IOException | IllegalArgumentException exception) {
            AiPartnerMod.LOGGER.warn("Rejected invalid synchronized maid skin {}", payload.hash(), exception);
        }
    }

    public static void clear(Minecraft client) {
        for (Identifier location : TEXTURES.values()) {
            client.getTextureManager().release(location);
        }
        TEXTURES.clear();
    }
}
