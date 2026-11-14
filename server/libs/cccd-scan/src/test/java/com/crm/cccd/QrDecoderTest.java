package com.crm.cccd;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QrDecoderTest {

    private static final String TEXT =
        "079203012345|285123456|Nguyễn Văn An|15081990|Nam|12 Lê Lợi, Bến Nghé, Quận 1, TP.HCM|20042021";

    static BufferedImage qr(String text, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    static byte[] encode(BufferedImage image, String format) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, format, out);
        return out.toByteArray();
    }

    private static BufferedImage rotate(BufferedImage src, int quarterTurns) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = quarterTurns % 2 != 0;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        AffineTransform t = new AffineTransform();
        t.translate(dst.getWidth() / 2.0, dst.getHeight() / 2.0);
        t.rotate(Math.PI / 2 * quarterTurns);
        t.translate(-w / 2.0, -h / 2.0);
        g.drawImage(src, t, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage photoWithQr(BufferedImage qrImage) {
        BufferedImage photo = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 4000, 3000);
        g.drawImage(qrImage, 1500, 900, null);
        g.dispose();
        return photo;
    }

    @Test
    void decodes_plain_qr_png() throws Exception {
        byte[] png = encode(qr(TEXT, 400), "png");

        assertThat(QrDecoder.decode(png)).contains(TEXT);
    }

    @Test
    void decodes_rotated_and_small_qr_in_large_photo() throws Exception {
        // QR about 15 % of the 4000 px width
        BufferedImage small = qr(TEXT, 600);
        for (int turns : new int[] {1, 2}) {
            byte[] jpeg = encode(photoWithQr(rotate(small, turns)), "jpg");

            assertThat(QrDecoder.decode(jpeg)).as("rotated %d quarter turns", turns).contains(TEXT);
        }
    }

    @Test
    void no_qr_returns_empty() throws Exception {
        BufferedImage blank = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        assertThat(QrDecoder.decode(encode(blank, "png"))).isEmpty();
    }

    @Test
    void garbage_bytes_return_empty() {
        assertThat(QrDecoder.decode(new byte[] {1, 2, 3, 4})).isEmpty();
        assertThat(QrDecoder.decode(new byte[0])).isEmpty();
        assertThat(QrDecoder.decode(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1})).isEmpty();
    }

    @Test
    void image_format_is_detected_by_magic_bytes() throws Exception {
        BufferedImage image = qr(TEXT, 100);

        assertThat(ImageFormat.detect(encode(image, "jpg"))).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(encode(image, "png"))).contains(ImageFormat.PNG);
        byte[] heic = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0};
        assertThat(ImageFormat.detect(heic)).isEmpty();
        assertThat(ImageFormat.detect("GIF89a....".getBytes())).isEmpty();
        assertThat(ImageFormat.detect("%PDF-1.7".getBytes())).isEmpty();
        assertThat(ImageFormat.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageFormat.detect(new byte[0])).isEmpty();
    }

    @Test
    void decodes_utf8_payload_without_eci() throws Exception {
        // zxing's writer emits UTF-8 bytes without an ECI segment for the Vietnamese sample
        assertThat(QrDecoder.decode(encode(qr(TEXT, 500), "png"))).contains(TEXT);
    }

    @Test
    void blank_photo_gives_up_within_deadline() throws Exception {
        byte[] jpeg = encode(photoWithQr(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)), "jpg");
        long start = System.nanoTime();

        assertThat(QrDecoder.decode(jpeg)).isEmpty();

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
    }

    @Test
    void expired_deadline_returns_empty_before_any_pass() throws Exception {
        byte[] png = encode(qr(TEXT, 400), "png");
        long[] now = {0};
        java.util.function.LongSupplier clock = () -> now[0]++ * 10_000_000_000L; // each read advances 10 s

        assertThat(QrDecoder.decode(png, java.time.Duration.ofSeconds(4), clock)).isEmpty();
    }
}
