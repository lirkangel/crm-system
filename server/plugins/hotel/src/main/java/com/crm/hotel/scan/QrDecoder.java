package com.crm.hotel.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Finds and decodes a QR code in a photo. Never throws on bad input and never logs decoded text. */
public final class QrDecoder {

    private static final int[] LONG_EDGES = {1600, 800};

    private QrDecoder() {
    }

    public static Optional<String> decode(byte[] image) {
        BufferedImage source;
        try {
            source = ScanImages.read(image);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            Optional<String> found = tryAllRotations(ScanImages.toRgbFitting(source, Integer.MAX_VALUE));
            for (int i = 0; found.isEmpty() && i < LONG_EDGES.length; i++) {
                if (Math.max(source.getWidth(), source.getHeight()) > LONG_EDGES[i]) {
                    found = tryAllRotations(ScanImages.toRgbFitting(source, LONG_EDGES[i]));
                }
            }
            return found;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> tryAllRotations(BufferedImage image) {
        BufferedImage current = image;
        for (int turn = 0; turn < 4; turn++) {
            Optional<String> text = tryDecode(current);
            if (text.isPresent()) {
                return text;
            }
            current = rotate90(current);
        }
        return Optional.empty();
    }

    private static Optional<String> tryDecode(BufferedImage image) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.FALSE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        LuminanceSource luminance = new BufferedImageLuminanceSource(image);
        MultiFormatReader reader = new MultiFormatReader();
        try {
            return Optional.of(reader.decode(new BinaryBitmap(new HybridBinarizer(luminance)), hints).getText());
        } catch (NotFoundException e) {
            return Optional.empty();
        } finally {
            reader.reset();
        }
    }

    private static BufferedImage rotate90(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst.setRGB(h - 1 - y, x, src.getRGB(x, y));
            }
        }
        return dst;
    }
}
