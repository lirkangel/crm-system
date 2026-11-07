package com.crm.hotel.scan;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Re-encodes an upload as a metadata-free JPEG (quality 0.8) with its long edge at most 1600 px. */
public final class ImageNormalizer {

    static final int MAX_LONG_EDGE = 1600;
    /** Decode bound: bigger sources are subsampled while reading, then scaled to 1600 for storage. */
    static final int DECODE_LONG_EDGE = 2400;
    private static final float QUALITY = 0.8f;

    private ImageNormalizer() {
    }

    /** Test seam only (decodes outside the decode permit); production goes through {@link ScanPipeline}. */
    static byte[] toStoredJpeg(byte[] image) {
        return encode(ScanImages.flatten(ScanImages.readBounded(image, DECODE_LONG_EDGE, MAX_LONG_EDGE)));
    }

    /** Encodes an already decoded raster (long edge bounded by the caller) as the stored JPEG. */
    static byte[] encode(BufferedImage source) {
        BufferedImage fitted = ScanImages.toRgbFitting(source, MAX_LONG_EDGE);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                // null metadata: no EXIF/GPS/ICC is carried over from the source
                writer.write(null, new IIOImage(fitted, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Image could not be re-encoded");
        } finally {
            writer.dispose();
        }
    }
}
