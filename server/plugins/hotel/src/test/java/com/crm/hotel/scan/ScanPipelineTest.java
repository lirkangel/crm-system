package com.crm.hotel.scan;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ScanPipelineTest {

    private static final String TEXT = "079203012345|285123456|An|15081990|Nam|Hue|20042021";

    private static byte[] photoWithQr() throws Exception {
        BufferedImage photo = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 4000, 3000);
        g.drawImage(QrDecoderTest.qr(TEXT, 600), 1500, 900, null);
        g.dispose();
        return QrDecoderTest.encode(photo, "jpg");
    }

    @Test
    void decodes_the_image_once_and_stores_the_downscaled_jpeg_from_the_same_raster() throws Exception {
        byte[] upload = photoWithQr();
        int before = ScanImages.READS.get();

        ScanPipeline.Processed processed = new ScanPipeline().process(upload).orElseThrow();

        assertThat(ScanImages.READS.get() - before).as("image decodes").isEqualTo(1);
        assertThat(processed.qrText()).isEqualTo(TEXT);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(processed.storedJpeg()));
        assertThat(Math.max(stored.getWidth(), stored.getHeight())).isEqualTo(1600);
    }

    @Test
    void no_qr_means_empty_and_no_jpeg_is_produced() throws Exception {
        byte[] blank = QrDecoderTest.encode(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "jpg");
        int before = ScanImages.READS.get();

        assertThat(new ScanPipeline().process(blank)).isEmpty();
        assertThat(ScanImages.READS.get() - before).isEqualTo(1);
    }
}
