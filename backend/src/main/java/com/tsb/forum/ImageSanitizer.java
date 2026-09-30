package com.tsb.forum;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Every uploaded picture passes through here before it is stored.
 *
 * <p>An upload is untrusted bytes with a file name attached; the name and the
 * browser's "content type" are both whatever the sender typed. So:
 * <ol>
 *   <li><b>Check what it really is</b> from its first bytes (PNG or JPEG
 *       only). An HTML page renamed {@code cat.png} is refused here.</li>
 *   <li><b>Check its size before decoding.</b> The header states the
 *       dimensions; a tiny file claiming to be 50,000 × 50,000 pixels (a
 *       "decompression bomb") is refused without allocating that memory.</li>
 *   <li><b>Redraw it.</b> The pixels are decoded and written out as a brand
 *       new file. Nothing from the original survives except the picture:
 *       no EXIF (phone photos carry GPS location), no comments, no bytes
 *       appended after the image data.</li>
 * </ol>
 * Large pictures are scaled down to {@value #MAX_SIDE_OUT} px on the long side
 * on the way.
 */
public final class ImageSanitizer {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIDE_IN = 8000;
    static final long MAX_PIXELS_IN = 40_000_000L;
    static final int MAX_SIDE_OUT = 2000;

    /** A clean image, ready to store and serve. */
    public record Clean(byte[] bytes, String contentType, int width, int height) {
    }

    private ImageSanitizer() {
    }

    public static Clean clean(byte[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("The image is empty.");
        }
        if (input.length > MAX_BYTES) {
            throw new IllegalArgumentException("Images must be 5 MB or smaller.");
        }
        String format = sniff(input);
        if (format == null) {
            throw new IllegalArgumentException("Only PNG or JPEG images can be uploaded.");
        }

        BufferedImage image;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("That image format cannot be read.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // ignore metadata while reading
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE_IN || h > MAX_SIDE_IN
                        || (long) w * h > MAX_PIXELS_IN) {
                    throw new IllegalArgumentException("Images can be at most "
                            + MAX_SIDE_IN + " pixels on each side.");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae) {
                throw iae;
            }
            throw new IllegalArgumentException("That file is not a readable image.");
        }

        BufferedImage out = scaleDown(image, format.equals("png"));
        byte[] bytes = format.equals("png") ? writePng(out) : writeJpeg(out);
        return new Clean(bytes, format.equals("png") ? "image/png" : "image/jpeg",
                out.getWidth(), out.getHeight());
    }

    /** "png", "jpeg", or null — from the file's own first bytes. */
    static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8
                && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        return null;
    }

    /** Redraws into a fresh image (smaller if needed). Always a new raster. */
    private static BufferedImage scaleDown(BufferedImage src, boolean keepAlpha) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE_OUT / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!keepAlpha) {
                g.setColor(java.awt.Color.WHITE); // JPEG has no transparency
                g.fillRect(0, 0, nw, nh);
            }
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] writePng(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode PNG", e);
        }
    }

    private static byte[] writeJpeg(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(img, null, null), p); // no metadata
            ios.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not encode JPEG", e);
        } finally {
            writer.dispose();
        }
    }
}
