package io.github.ozozorz.aipartner.client.skin;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.ozozorz.aipartner.skin.MaidSkinDataPayload;
import io.github.ozozorz.aipartner.skin.MaidSkinUploadPayload;
import io.github.ozozorz.aipartner.skin.SkinImageValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

/**
 * 注册本地 `/maid-skin` 文件上传命令和动态皮肤接收器。
 */
public final class MaidSkinClient {
    private MaidSkinClient() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                MaidSkinDataPayload.TYPE,
                (payload, context) -> context.client().execute(() -> SkinTextureCache.accept(context.client(), payload))
        );
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SkinTextureCache.clear(client));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("maid-skin")
                        .then(ClientCommands.literal("upload")
                                .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                                        .executes(context -> upload(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "path")
                                        ))))
                        .then(ClientCommands.literal("clear")
                                .executes(context -> clear(context.getSource())))
        ));
    }

    private static int upload(FabricClientCommandSource source, String rawPath) {
        if (!ClientPlayNetworking.canSend(MaidSkinUploadPayload.TYPE)) {
            source.sendError(Component.translatable("message.ai-partner.skin_channel_unavailable"));
            return 0;
        }
        String normalizedPath = rawPath.strip();
        if (normalizedPath.length() >= 2
                && normalizedPath.startsWith("\"")
                && normalizedPath.endsWith("\"")) {
            normalizedPath = normalizedPath.substring(1, normalizedPath.length() - 1);
        }
        try {
            Path path = Path.of(normalizedPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || Files.size(path) > SkinImageValidator.MAX_UPLOAD_BYTES) {
                source.sendError(Component.translatable("message.ai-partner.skin_invalid_file"));
                return 0;
            }
            SkinImageValidator.ValidatedSkin validated = SkinImageValidator.validate(Files.readAllBytes(path));
            ClientPlayNetworking.send(new MaidSkinUploadPayload(validated.pngBytes()));
            source.sendFeedback(Component.translatable("message.ai-partner.skin_uploading"));
            return 1;
        } catch (IOException | RuntimeException exception) {
            source.sendError(Component.translatable(
                    "message.ai-partner.skin_invalid",
                    exception.getMessage()
            ));
            return 0;
        }
    }

    private static int clear(FabricClientCommandSource source) {
        if (!ClientPlayNetworking.canSend(MaidSkinUploadPayload.TYPE)) {
            source.sendError(Component.translatable("message.ai-partner.skin_channel_unavailable"));
            return 0;
        }
        ClientPlayNetworking.send(new MaidSkinUploadPayload(new byte[0]));
        return 1;
    }
}
