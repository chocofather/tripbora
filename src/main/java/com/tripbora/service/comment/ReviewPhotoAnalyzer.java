package com.tripbora.service.comment;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local JPEG/PNG measurements only; no upload, re-encoding, subject or aesthetic judgement.
 * The file must remain unchanged while it is being analyzed. Dimensions are encoded pixel
 * dimensions; EXIF rotation is not applied. Transparent pixels are composited onto white.
 * Hashes and flags are review hints: distinct images (especially flat colors) can share a dHash.
 */
@Component
public class ReviewPhotoAnalyzer {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final int MAX_SIDE = 20_000;
    private static final int DECODE_SIDE = 1024;
    private static final int QUALITY_SIDE = 256;

    // Version 1 thresholds, using original dimensions and normalized 0..255 luma.
    // BLUR is four-neighbor Laplacian variance on a fixed 256x256 thumbnail, < 100.
    // These conservative review hints do not establish photographic quality or relevance.
    private static final double BLUR_VARIANCE = 100.0;
    private static final double DARK_BRIGHTNESS = 35.0;
    private static final double BRIGHT_BRIGHTNESS = 220.0;

    public record Analysis(String sha256, long dHash, int width, int height,
                           double brightness, double sharpness, String qualityFlags) { }

    public Analysis analyze(Path file) throws IOException {
        String sha256 = exactHash(file);
        long size = Files.size(file);
        try (FileImageInputStream input = new FileImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!format.equals("JPEG") && !format.equals("PNG")) {
                    throw new IOException("Unsupported image format: " + format);
                }
                verifyTerminator(input, size, format);
                input.seek(0);
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || Math.max(width, height) > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) {
                    throw new IOException("Image dimensions exceed 20000px or 40000000 pixels");
                }
                AtomicBoolean warning = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                ImageReadParam parameter = reader.getDefaultReadParam();
                int step = Math.max(1, (Math.max(width, height) + DECODE_SIDE - 1) / DECODE_SIDE);
                parameter.setSourceSubsampling(step, step, 0, 0);
                BufferedImage decoded = reader.read(0, parameter);
                if (decoded == null || warning.get() || decoded.getWidth() > DECODE_SIDE
                        || decoded.getHeight() > DECODE_SIDE) {
                    throw new IOException("Image could not be decoded safely");
                }
                try {
                    double[][] quality = grayscale(decoded, QUALITY_SIDE, QUALITY_SIDE);
                    double brightness = mean(quality);
                    double sharpness = laplacianVariance(quality);
                    long dHash = differenceHash(grayscale(decoded, 9, 8));
                    return new Analysis(sha256, dHash, width, height, brightness, sharpness,
                            flags(width, height, brightness, sharpness));
                } finally {
                    decoded.flush();
                }
            } catch (RuntimeException exception) {
                throw new IOException("Invalid image data", exception);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Original byte hash only, including formats this runtime cannot decode (for example WebP). */
    public String exactHash(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Image file is not a regular file");
        }
        long size = Files.size(file);
        if (size == 0 || size > MAX_BYTES) {
            throw new IOException("Image file must be between 1 byte and 25 MiB");
        }
        return sha256(file);
    }

    /** Four unsigned 16-bit bands, numbered 0 (least significant) through 3. */
    public static int band(long hash, int band) {
        if (band < 0 || band > 3) throw new IllegalArgumentException("Band must be between 0 and 3");
        return (int) ((hash >>> (band * 16)) & 0xFFFFL);
    }

    public static int distance(long first, long second) {
        return Long.bitCount(first ^ second);
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > MAX_BYTES) throw new IOException("Image file exceeds 25 MiB");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // Some ImageIO readers accept a missing end marker without reporting a decode error.
    // Require conventional JPEG EOI / PNG IEND endings as well as successful decoding.
    private void verifyTerminator(FileImageInputStream input, long size, String format) throws IOException {
        if (format.equals("JPEG")) {
            if (size < 2) throw new IOException("Truncated JPEG");
            input.seek(size - 2);
            if (input.readUnsignedShort() != 0xFFD9) throw new IOException("Truncated JPEG");
        } else {
            if (size < 12) throw new IOException("Truncated PNG");
            input.seek(size - 12);
            if (input.readInt() != 0 || input.readInt() != 0x49454E44 || input.readInt() != 0xAE426082) {
                throw new IOException("Truncated PNG");
            }
        }
    }

    private double[][] grayscale(BufferedImage source, int width, int height) {
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        // Stretch to the same dimensions for every source so measurements are resize independent.
        double[][] luma = new double[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = thumbnail.getRGB(x, y);
            luma[y][x] = (299 * ((pixel >>> 16) & 255) + 587 * ((pixel >>> 8) & 255)
                    + 114 * (pixel & 255)) / 1000.0;
        }
        thumbnail.flush();
        return luma;
    }

    private long differenceHash(double[][] luma) {
        long hash = 0;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            hash = (hash << 1) | (luma[y][x] > luma[y][x + 1] ? 1L : 0L);
        }
        return hash;
    }

    private double mean(double[][] luma) {
        double sum = 0;
        for (double[] row : luma) for (double value : row) sum += value;
        return sum / (luma.length * luma[0].length);
    }

    private double laplacianVariance(double[][] luma) {
        double sum = 0;
        double squares = 0;
        int count = 0;
        for (int y = 1; y < luma.length - 1; y++) for (int x = 1; x < luma[y].length - 1; x++) {
            double value = luma[y - 1][x] + luma[y + 1][x] + luma[y][x - 1] + luma[y][x + 1]
                    - 4 * luma[y][x];
            sum += value;
            squares += value * value;
            count++;
        }
        return Math.max(0, squares / count - Math.pow(sum / count, 2));
    }

    private String flags(int width, int height, double brightness, double sharpness) {
        List<String> flags = new ArrayList<>();
        if (width < 800 || height < 450) flags.add("SMALL");
        if (sharpness < BLUR_VARIANCE) flags.add("BLUR");
        if (brightness < DARK_BRIGHTNESS) flags.add("DARK");
        if (brightness > BRIGHT_BRIGHTNESS) flags.add("BRIGHT");
        double ratio = (double) width / height;
        if (ratio < 0.5 || ratio > 3.0) flags.add("ASPECT");
        return String.join(",", flags);
    }
}
