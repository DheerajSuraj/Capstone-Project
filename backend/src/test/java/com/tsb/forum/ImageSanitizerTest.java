package com.tsb.forum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ImageSanitizer")
class ImageSanitizerTest {

    private static byte[] encode(int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("a normal PNG comes back as a PNG of the same size")
    void png() throws IOException {
        ImageSanitizer.Clean c = ImageSanitizer.clean(encode(640, 480, "png"));
        assertEquals("image/png", c.contentType());
        assertEquals(640, c.width());
        assertEquals(480, c.height());
    }

    @Test
    @DisplayName("big pictures are scaled down to 2000 px on the long side")
    void scaled() throws IOException {
        ImageSanitizer.Clean c = ImageSanitizer.clean(encode(3000, 1500, "jpg"));
        assertEquals("image/jpeg", c.contentType());
        assertEquals(2000, c.width());
        assertEquals(1000, c.height());
    }

    @Test
    @DisplayName("hidden metadata (like GPS in EXIF) and appended bytes do not survive")
    void strips() throws IOException {
        byte[] jpg = encode(200, 100, "jpg");
        byte[] secret = "Exif\0\0GPS 19.07N 72.87E SECRET-LOCATION".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream dirty = new ByteArrayOutputStream();
        dirty.write(jpg, 0, 2);                       // SOI
        dirty.write(0xFF);
        dirty.write(0xE1);                            // APP1 = EXIF
        dirty.write((secret.length + 2) >> 8);
        dirty.write((secret.length + 2) & 0xFF);
        dirty.write(secret);
        dirty.write(jpg, 2, jpg.length - 2);
        dirty.write("<script>alert(1)</script>".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(latin1(dirty.toByteArray()).contains("SECRET-LOCATION"));

        byte[] clean = ImageSanitizer.clean(dirty.toByteArray()).bytes();
        assertFalse(latin1(clean).contains("SECRET-LOCATION"));
        assertFalse(latin1(clean).contains("<script>"));
    }

    @Test
    @DisplayName("a web page renamed .png is refused by its real first bytes")
    void notAnImage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ImageSanitizer.clean("<html><script>x</script></html>".getBytes()));
        assertTrue(e.getMessage().contains("PNG or JPEG"));
    }

    @Test
    @DisplayName("a tiny file claiming huge dimensions is refused before decoding")
    void decompressionBomb() throws IOException {
        byte[] png = encode(1, 1, "png");
        ByteBuffer.wrap(png, 16, 8).putInt(50_000).putInt(50_000); // IHDR width, height
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ImageSanitizer.clean(png));
        assertTrue(e.getMessage().contains("8000"));
    }

    @Test
    @DisplayName("more than 16 megapixels is refused before decoding, with the reason")
    void pixelCap() throws IOException {
        byte[] png = encode(1, 1, "png");
        ByteBuffer.wrap(png, 16, 8).putInt(5000).putInt(4000); // 20 MP, sides under 8000
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ImageSanitizer.clean(png));
        assertTrue(e.getMessage().contains("16 megapixels"), e.getMessage());
    }

    /** A JPEG with an EXIF block saying "turn me" (orientation 1..8). */
    private static byte[] withOrientation(byte[] jpg, int orientation) throws IOException {
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.write("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        exif.write(new byte[]{'M', 'M', 0, 0x2A, 0, 0, 0, 8}); // big-endian TIFF, IFD0 at 8
        exif.write(new byte[]{0, 1});                           // one entry
        exif.write(new byte[]{0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0});
        exif.write(new byte[]{0, 0, 0, 0});                     // no next IFD
        byte[] payload = exif.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.write(payload);
        out.write(jpg, 2, jpg.length - 2);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a phone photo stored sideways (EXIF orientation 6) comes out upright")
    void exifTurn() throws IOException {
        // 40 x 20, left half red, right half blue.
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                img.setRGB(x, y, x < 20 ? 0xFF0000 : 0x0000FF);
            }
        }
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", jpg);
        byte[] turned = withOrientation(jpg.toByteArray(), 6);
        assertEquals(6, ImageSanitizer.exifOrientation(turned));

        ImageSanitizer.Clean c = ImageSanitizer.clean(turned);
        assertEquals(20, c.width());
        assertEquals(40, c.height());
        BufferedImage out = ImageIO.read(new java.io.ByteArrayInputStream(c.bytes()));
        // Turned a quarter to the right: the left (red) half is now on top.
        int top = out.getRGB(10, 5);
        int bottom = out.getRGB(10, 35);
        assertTrue(((top >> 16) & 0xFF) > 200 && (top & 0xFF) < 60, Integer.toHexString(top));
        assertTrue((bottom & 0xFF) > 200 && ((bottom >> 16) & 0xFF) < 60, Integer.toHexString(bottom));
    }

    @Test
    @DisplayName("broken EXIF is ignored, not fatal")
    void brokenExif() throws IOException {
        assertEquals(1, ImageSanitizer.exifOrientation(encode(10, 10, "jpg")));
        byte[] bad = withOrientation(encode(10, 10, "jpg"), 6);
        bad[2 + 4 + 6] = 'X'; // not II or MM any more
        assertEquals(1, ImageSanitizer.exifOrientation(bad));
        assertEquals(10, ImageSanitizer.clean(bad).width());
    }

    @Test
    @DisplayName("empty and oversized uploads are refused")
    void sizes() {
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.clean(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ImageSanitizer.clean(new byte[ImageSanitizer.MAX_BYTES + 1]));
    }
}
