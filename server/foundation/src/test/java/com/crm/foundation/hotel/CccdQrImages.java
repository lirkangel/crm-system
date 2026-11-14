package com.crm.foundation.hotel;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Test helper: renders a CCCD-style QR payload into a phone-photo-like JPEG (a large off-white canvas with the QR
 * code placed off-centre), so the scan ITs exercise the real decoder.
 */
public final class CccdQrImages {

    public static final String ID_NUMBER = "079090123456";
    public static final String FULL_NAME = "NGUYEN VAN AN";

    private CccdQrImages() {
    }

    /** {@code idNumber|oldIdNumber|fullName|ddMMyyyy|sex|address|ddMMyyyy}. */
    public static String payload(String idNumber, String fullName) {
        return idNumber + "|" + "123456789" + "|" + fullName + "|17051990|Nam|12 Le Loi, Phu Hoi, Hue|10062021";
    }

    public static String payload() {
        return payload(ID_NUMBER, FULL_NAME);
    }

    /** A 3000x2000 JPEG (so it is downscaled when stored) carrying {@code payload}; {@code shade} varies the bytes. */
    public static byte[] jpeg(String payload, int shade) {
        return encode(canvas(payload, 3000, 2000, shade), "jpg");
    }

    public static byte[] jpeg(String payload) {
        return jpeg(payload, 0);
    }

    public static byte[] png(String payload) {
        return encode(canvas(payload, 1200, 800, 0), "png");
    }

    /** A photo with text-ish clutter and no QR code (the front of the card). */
    public static byte[] jpegWithoutQr(int shade) {
        BufferedImage image = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(235 - shade * 5, 232, 225));
        g.fillRect(0, 0, 1600, 1000);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 60));
        g.drawString("CAN CUOC CONG DAN", 300, 300);
        g.dispose();
        return encode(image, "jpg");
    }

    private static BufferedImage canvas(String payload, int width, int height, int shade) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(235 - shade * 5, 232, 225));
        g.fillRect(0, 0, width, height);
        int side = height * 2 / 5;
        g.drawImage(qr(payload, side), width / 2, height / 4, null);
        g.dispose();
        return image;
    }

    private static BufferedImage qr(String payload, int side) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                payload, BarcodeFormat.QR_CODE, side, side, Map.of(EncodeHintType.MARGIN, 4));
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
