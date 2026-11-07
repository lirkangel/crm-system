package com.crm.hotel.scan;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Shared decoding and scaling helpers for the QR decoder and the normaliser. */
final class ScanImages {

    /** Decompression-bomb guard: a 10 MB PNG can expand enormously. */
    private static final long MAX_PIXELS = 100_000_000L;

    private ScanImages() {
    }

    /** Decodes JPEG/PNG bytes; throws IllegalArgumentException when unreadable or absurdly large. */
    static BufferedImage read(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Unreadable image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    throw new IllegalArgumentException("Image too large");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("Unreadable image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("Unreadable image");
        }
    }

    /** Opaque RGB copy (alpha flattened onto white), scaled so the long edge is at most {@code maxLongEdge}. */
    static BufferedImage toRgbFitting(BufferedImage src, int maxLongEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        int targetW = w;
        int targetH = h;
        if (longEdge > maxLongEdge) {
            double scale = (double) maxLongEdge / longEdge;
            targetW = Math.max(1, (int) Math.round(w * scale));
            targetH = Math.max(1, (int) Math.round(h * scale));
        }
        BufferedImage current = flatten(src);
        // Halve repeatedly while far above the target: bilinear alone aliases badly on big reductions.
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            current = draw(current, targetW, targetH);
        }
        return current;
    }

    private static BufferedImage flatten(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        return draw(src, src.getWidth(), src.getHeight());
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
