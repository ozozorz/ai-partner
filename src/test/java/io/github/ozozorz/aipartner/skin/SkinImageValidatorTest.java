package io.github.ozozorz.aipartner.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证皮肤上传只接受完整的 64×64 PNG，并生成稳定内容哈希。
 */
class SkinImageValidatorTest {
    @Test
    void validatesAndSanitizesExactMinecraftSkin() throws IOException {
        byte[] png = png(64, 64);

        SkinImageValidator.ValidatedSkin first = SkinImageValidator.validate(png);
        SkinImageValidator.ValidatedSkin second = SkinImageValidator.validate(first.pngBytes());

        assertEquals(64, first.sha256().length());
        assertEquals(first.sha256(), second.sha256());
        assertTrue(first.pngBytes().length <= SkinImageValidator.MAX_UPLOAD_BYTES);
    }

    @Test
    void rejectsWrongDimensionsAndNonPngData() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> SkinImageValidator.validate(png(64, 32)));
        assertThrows(IllegalArgumentException.class, () -> SkinImageValidator.validate("not a png".getBytes()));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF55AAFF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
}
