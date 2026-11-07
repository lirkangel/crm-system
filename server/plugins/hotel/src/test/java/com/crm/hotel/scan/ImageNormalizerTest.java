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

    @Test
    void rejects_over_50mp_without_decoding() throws Exception {
        // signature + IHDR declaring 10000x6000 (60 MP); a few dozen bytes, no raster behind it
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(ihdr);
        data.write(new byte[] {'I', 'H', 'D', 'R'});
        data.writeInt(10000);
        data.writeInt(6000);
        data.write(new byte[] {8, 2, 0, 0, 0});
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ihdr.toByteArray());
        out.writeInt(13);
        out.write(ihdr.toByteArray());
        out.writeInt((int) crc.getValue());

        assertThatThrownBy(() -> ImageNormalizer.toStoredJpeg(bytes.toByteArray()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("too large");
    }

    @Test
    void flattens_alpha_png_to_white() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB); // fully transparent
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(ImageNormalizer.toStoredJpeg(png.toByteArray())));

        int rgb = out.getRGB(100, 50);
        assertThat((rgb >> 16) & 0xFF).isGreaterThan(240);
        assertThat((rgb >> 8) & 0xFF).isGreaterThan(240);
        assertThat(rgb & 0xFF).isGreaterThan(240);
    }
}
