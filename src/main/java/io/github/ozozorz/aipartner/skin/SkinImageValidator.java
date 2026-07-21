package io.github.ozozorz.aipartner.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * 对用户上传的 64×64 PNG 做尺寸、格式、体积和完整解码校验，并剥离元数据。
 */
public final class SkinImageValidator {
    public static final int SKIN_WIDTH = 64;
    public static final int SKIN_HEIGHT = 64;
    public static final int MAX_UPLOAD_BYTES = 256 * 1024;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private SkinImageValidator() {
    }

    /**
     * 返回重新编码的安全 PNG 和内容哈希；非法输入不会进入实体或世界存储。
     */
    public static ValidatedSkin validate(byte[] uploadedBytes) {
        if (uploadedBytes == null || uploadedBytes.length < PNG_SIGNATURE.length) {
            throw new IllegalArgumentException("Skin upload is empty or truncated");
        }
        if (uploadedBytes.length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Skin upload exceeds the maximum byte size");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (uploadedBytes[index] != PNG_SIGNATURE[index]) {
                throw new IllegalArgumentException("Skin must be a PNG image");
            }
        }

        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(uploadedBytes));
            if (decoded == null
                    || decoded.getWidth() != SKIN_WIDTH
                    || decoded.getHeight() != SKIN_HEIGHT) {
                throw new IllegalArgumentException("Skin must be exactly 64x64 pixels");
            }
            BufferedImage sanitized = new BufferedImage(SKIN_WIDTH, SKIN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SKIN_HEIGHT; y++) {
                for (int x = 0; x < SKIN_WIDTH; x++) {
                    sanitized.setRGB(x, y, decoded.getRGB(x, y));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
            if (!ImageIO.write(sanitized, "PNG", output)) {
                throw new IllegalArgumentException("No PNG encoder is available");
            }
            byte[] safePng = output.toByteArray();
            return new ValidatedSkin(safePng, sha256(safePng));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Skin PNG could not be decoded", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ValidatedSkin(byte[] pngBytes, String sha256) {
        public ValidatedSkin {
            pngBytes = pngBytes.clone();
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }
}
