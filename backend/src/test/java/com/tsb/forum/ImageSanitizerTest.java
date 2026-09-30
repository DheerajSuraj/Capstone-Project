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
    @DisplayName("empty and oversized uploads are refused")
    void sizes() {
        assertThrows(IllegalArgumentException.class, () -> ImageSanitizer.clean(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ImageSanitizer.clean(new byte[ImageSanitizer.MAX_BYTES + 1]));
    }
}
