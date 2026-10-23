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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
 * on the way, and turned the right way up: phones store photos sideways and
 * say so in EXIF, which step 3 throws away, so the turn is applied first.
 *
 * <p><b>Memory.</b> A decoded picture takes 3–4 bytes per pixel whatever
 * its file size, so at most {@value #MAX_PIXELS_IN} pixels (16 MP, 64 MB
 * decoded) and at most {@value #MAX_DECODES} pictures decode at once —
 * before, a few posts of four 40 MP files at the same time could take
 * gigabytes and stop the whole server.
 */
public final class ImageSanitizer {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIDE_IN = 8000;
    static final long MAX_PIXELS_IN = 16_000_000L;
    static final int MAX_SIDE_OUT = 2000;
    static final int MAX_DECODES = 2;

    private static final Semaphore DECODING = new Semaphore(MAX_DECODES, true);

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
        try {
            if (!DECODING.tryAcquire(20, TimeUnit.SECONDS)) {
                throw new IllegalArgumentException("Lots of pictures are being uploaded "
                        + "right now. Please try again in a moment.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("The upload was interrupted.");
        }
        try {
            return decodeAndRedraw(input, format);
        } finally {
            DECODING.release();
        }
    }

    private static Clean decodeAndRedraw(byte[] input, String format) {
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
                            + MAX_SIDE_IN + " pixels on a side and 16 megapixels in all "
                            + "(for example 4000 × 4000). This one is " + w + " × " + h + ".");
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
        if (format.equals("jpeg")) {
            out = orient(out, exifOrientation(input));
        }
        byte[] bytes = format.equals("png") ? writePng(out) : writeJpeg(out);
        return new Clean(bytes, format.equals("png") ? "image/png" : "image/jpeg",
                out.getWidth(), out.getHeight());
    }

    /**
     * The EXIF orientation of a JPEG (1 = upright … 8), read straight from
     * its APP1 segment; 1 when there is none or it is malformed. Only this
     * one number is taken from the metadata — everything else is dropped.
     */
    static int exifOrientation(byte[] b) {
        int i = 2; // after SOI
        while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // image data starts: no EXIF before it
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (len < 2 || i + 2 + len > b.length) {
                return 1;
            }
            int seg = i + 4; // segment payload
            if (marker == 0xE1 && len >= 8 && b[seg] == 'E' && b[seg + 1] == 'x'
                    && b[seg + 2] == 'i' && b[seg + 3] == 'f' && b[seg + 4] == 0 && b[seg + 5] == 0) {
                return tiffOrientation(b, seg + 6, i + 2 + len);
            }
            i += 2 + len;
        }
        return 1;
    }

    private static int tiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        long ifd = tiff + u32(b, tiff + 4, little);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int count = u16(b, (int) ifd, little);
        for (int k = 0; k < count; k++) {
            int entry = (int) ifd + 2 + 12 * k;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, entry, little) == 0x0112) { // Orientation, a SHORT
                int v = u16(b, entry + 8, little);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int x = b[at] & 0xFF;
        int y = b[at + 1] & 0xFF;
        return little ? x | (y << 8) : (x << 8) | y;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long v = 0;
        for (int k = 0; k < 4; k++) {
            long byteValue = b[at + (little ? 3 - k : k)] & 0xFF;
            v = (v << 8) | byteValue;
        }
        return v;
    }

    /** Turns/flips a picture as its EXIF orientation says (2–8). */
    static BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> { tx = w - 1 - x; ty = y; }
                    case 3 -> { tx = w - 1 - x; ty = h - 1 - y; }
                    case 4 -> { tx = x; ty = h - 1 - y; }
                    case 5 -> { tx = y; ty = x; }
                    case 6 -> { tx = h - 1 - y; ty = x; }          // turn right
                    case 7 -> { tx = h - 1 - y; ty = w - 1 - x; }
                    default -> { tx = y; ty = w - 1 - x; }         // 8: turn left
                }
                out[ty * dw + tx] = in[y * w + x];
            }
        }
        BufferedImage dst = new BufferedImage(dw, dh, src.getType() == 0
                ? BufferedImage.TYPE_INT_RGB : src.getType());
        dst.setRGB(0, 0, dw, dh, out, 0, dw);
        return dst;
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
