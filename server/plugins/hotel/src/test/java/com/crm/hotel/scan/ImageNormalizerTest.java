package com.crm.hotel.scan;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageNormalizerTest {

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void downscales_long_edge_to_1600_and_outputs_jpeg() throws Exception {
        byte[] stored = ImageNormalizer.toStoredJpeg(png(4000, 3000));

        assertThat(ImageFormat.detect(stored)).contains(ImageFormat.JPEG);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(out.getWidth()).isEqualTo(1600);
        assertThat(out.getHeight()).isEqualTo(1200);
    }

    @Test
    void portrait_long_edge_is_height() throws Exception {
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(ImageNormalizer.toStoredJpeg(png(1000, 3000))));

        assertThat(out.getHeight()).isEqualTo(1600);
        assertThat(out.getWidth()).isEqualTo(533);
    }

    @Test
    void small_image_kept_size_but_jpeg() throws Exception {
        byte[] stored = ImageNormalizer.toStoredJpeg(png(640, 480));

        assertThat(ImageFormat.detect(stored)).contains(ImageFormat.JPEG);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(stored));
        assertThat(out.getWidth()).isEqualTo(640);
        assertThat(out.getHeight()).isEqualTo(480);
    }

    @Test
    void unreadable_input_throws_illegal_argument() {
        assertThatThrownBy(() -> ImageNormalizer.toStoredJpeg(new byte[] {1, 2, 3}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
