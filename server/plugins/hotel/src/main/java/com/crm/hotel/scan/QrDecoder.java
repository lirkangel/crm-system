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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Finds and decodes a QR code in a photo. Memory and time are bounded: the image is decoded subsampled
 * (long edge at most 2400 px), the cheapest passes run first, at most two decodes run concurrently and a
 * wall-clock deadline ends the search. Never logs decoded text; returns empty on bad input.
 */
public final class QrDecoder {

    static final int DECODE_LONG_EDGE = 2400;
    private static final int[] SCALES = {1600, DECODE_LONG_EDGE, 800};
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(4);
    private static final Semaphore PERMITS = new Semaphore(2);

    private QrDecoder() {
    }

    /** Test seam: still under the permit and deadline, but production goes through {@link ScanPipeline}. */
    static Optional<String> decode(byte[] image) {
        return decode(image, DEFAULT_TIMEOUT, System::nanoTime);
    }

    static Optional<String> decode(byte[] image, Duration timeout, LongSupplier nanoClock) {
        return run(image, timeout, nanoClock, false).map(ScanPipeline.Processed::qrText);
    }

    /**
     * Decodes the image once, under a decode permit and the deadline: finds the QR code and, when {@code withJpeg}
     * and a code was found, encodes the stored JPEG from the same raster. Empty when there is no readable QR.
     *
     * @throws ScanBusyException when no decode slot frees up within 5 seconds
     */
    static Optional<ScanPipeline.Processed> run(
            byte[] image, Duration timeout, LongSupplier nanoClock, boolean withJpeg) {
        boolean acquired;
        try {
            acquired = PERMITS.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScanBusyException();
        }
        if (!acquired) {
            throw new ScanBusyException();
        }
        try {
            long deadline = nanoClock.getAsLong() + timeout.toNanos();
            return search(image, deadline, nanoClock, withJpeg);
        } finally {
            PERMITS.release();
        }
    }

    private static Optional<ScanPipeline.Processed> search(
            byte[] image, long deadline, LongSupplier clock, boolean withJpeg) {
        BufferedImage source;
        try {
            // flatten once: every scaling step, rotation and the stored JPEG reuse this single INT_RGB raster
            source = ScanImages.flatten(ScanImages.readBounded(image, DECODE_LONG_EDGE, ImageNormalizer.MAX_LONG_EDGE));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            int longEdge = Math.max(source.getWidth(), source.getHeight());
            // distinct scales (bounded original = its own long edge), in the order 1600, original, 800
            List<Integer> edges = new ArrayList<>();
            for (int scale : SCALES) {
                int edge = scale == DECODE_LONG_EDGE ? longEdge : scale;
                if ((scale == DECODE_LONG_EDGE || edge < longEdge) && !edges.contains(edge)) {
                    edges.add(edge);
                }
            }
            List<BufferedImage> copies = new ArrayList<>();
            // pass 1: upright at each scale
            for (int edge : edges) {
                if (clock.getAsLong() > deadline) {
                    return Optional.empty();
                }
                BufferedImage copy = ScanImages.toRgbFitting(source, edge);
                copies.add(copy);
                Optional<String> text = tryDecode(copy);
                if (text.isPresent()) {
                    return processed(text.get(), source, withJpeg);
                }
            }
            // pass 2: rotations, only at 1600 and at the bounded original (never at 800)
            for (int i = 0; i < edges.size(); i++) {
                int edge = edges.get(i);
                if (edge != 1600 && edge != longEdge) {
                    continue;
                }
                BufferedImage current = copies.get(i);
                for (int turn = 1; turn < 4; turn++) {
                    if (clock.getAsLong() > deadline) {
                        return Optional.empty();
                    }
                    current = rotate90(current);
                    Optional<String> text = tryDecode(current);
                    if (text.isPresent()) {
                        return processed(text.get(), source, withJpeg);
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<ScanPipeline.Processed> processed(String text, BufferedImage source, boolean withJpeg) {
        return Optional.of(new ScanPipeline.Processed(text, withJpeg ? ImageNormalizer.encode(source) : null));
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
