package io.github.ozozorz.aipartner.skin;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 在当前世界目录按内容哈希保存校验后的皮肤，避免把大字节数组写进实体 NBT。
 */
public final class MaidSkinStore {
    private static final String HASH_PATTERN = "[0-9a-f]{64}";

    private MaidSkinStore() {
    }

    public static void save(MinecraftServer server, SkinImageValidator.ValidatedSkin skin) throws IOException {
        Path directory = skinDirectory(server);
        Files.createDirectories(directory);
        Path target = directory.resolve(skin.sha256() + ".png");
        try {
            Files.write(target, skin.pngBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // 内容寻址文件已经存在时无需覆盖。
        }
    }

    public static Optional<byte[]> load(MinecraftServer server, String hash) {
        if (hash == null || !hash.matches(HASH_PATTERN)) {
            return Optional.empty();
        }
        Path target = skinDirectory(server).resolve(hash + ".png");
        try {
            if (!Files.isRegularFile(target)
                    || Files.size(target) > SkinImageValidator.MAX_UPLOAD_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static Path skinDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("ai-partner").resolve("skins");
    }
}
