package com.tripbora.service.comment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewPhotoAnalyzerTest {
    @TempDir Path directory;
    private final ReviewPhotoAnalyzer analyzer = new ReviewPhotoAnalyzer();

    @Test
    void hashesOriginalBytesAndDoesNotChangeTheFile() throws Exception {
        Path file = write("original.png", pattern(1200, 800, false));
        byte[] before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        Path copy = directory.resolve("copy.png");
        Files.copy(file, copy);

        var result = analyzer.analyze(file);

        assertThat(result.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(before)));
        assertThat(analyzer.analyze(copy)).isEqualTo(result);
        assertThat(result.width()).isEqualTo(1200);
        assertThat(result.height()).isEqualTo(800);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    @Test
    void exactHashWorksWithoutDecoderAndEnforcesTheSameFileBounds() throws Exception {
        byte[] bytes = new byte[]{0, 7, 42, -1, 12};
        Path file = directory.resolve("opaque.webp");
        Path copy = directory.resolve("copy.webp");
        Files.write(file, bytes);
        Files.write(copy, bytes);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        assertThat(analyzer.exactHash(file)).isEqualTo(expected);
        assertThat(analyzer.exactHash(copy)).isEqualTo(expected);
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        assertThatThrownBy(() -> analyzer.analyze(file)).isInstanceOf(IOException.class);

        Path empty = Files.createFile(directory.resolve("empty.webp"));
        Path oversized = directory.resolve("oversized.webp");
        try (RandomAccessFile output = new RandomAccessFile(oversized.toFile(), "rw")) {
            output.setLength(25L * 1024 * 1024 + 1);
        }
        for (Path invalid : new Path[]{empty, oversized, directory, directory.resolve("missing.webp")}) {
            assertThatThrownBy(() -> analyzer.exactHash(invalid)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void resizedAndRecompressedPatternHasSimilarHashButDifferentByteHash() throws Exception {
        BufferedImage original = pattern(1200, 800, false);
        var png = analyzer.analyze(write("large.png", original));
        var jpeg = analyzer.analyze(write("small.jpg", resize(original, 600, 400)));

        assertThat(jpeg.sha256()).isNotEqualTo(png.sha256());
        assertThat(ReviewPhotoAnalyzer.distance(png.dHash(), jpeg.dHash())).isLessThanOrEqualTo(4);
        assertThat(jpeg.brightness()).isCloseTo(png.brightness(), org.assertj.core.data.Offset.offset(3.0));
    }

    @Test
    void oppositeStructureHasDistantHash() throws Exception {
        var first = analyzer.analyze(write("first.png", pattern(900, 600, false)));
        var second = analyzer.analyze(write("second.png", pattern(900, 600, true)));
        assertThat(ReviewPhotoAnalyzer.distance(first.dHash(), second.dHash())).isGreaterThan(40);
    }

    @Test
    void uniformColorsCanCollideAndBlurFlagDoesNotJudgeTheirSubject() throws Exception {
        var red = analyzer.analyze(write("red.png", solid(900, 600, Color.RED)));
        var green = analyzer.analyze(write("green.png", solid(900, 600, Color.GREEN)));
        assertThat(red.dHash()).isEqualTo(green.dHash());
        assertThat(red.sha256()).isNotEqualTo(green.sha256());
        assertThat(red.brightness()).isLessThan(green.brightness());
        assertThat(red.sharpness()).isCloseTo(0, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(red.qualityFlags()).contains("BLUR");
    }

    @Test
    void flagsUseOriginalDimensionsAndNormalizedBrightness() throws Exception {
        var dark = analyzer.analyze(write("dark.png", solid(799, 449, Color.BLACK)));
        var bright = analyzer.analyze(write("bright.png", solid(800, 450, Color.WHITE)));
        var wide = analyzer.analyze(write("wide.png", solid(1201, 400, Color.GRAY)));
        var tall = analyzer.analyze(write("tall.png", solid(400, 801, Color.GRAY)));
        assertThat(dark.qualityFlags()).isEqualTo("SMALL,BLUR,DARK");
        assertThat(dark.brightness()).isZero();
        assertThat(bright.qualityFlags()).isEqualTo("BLUR,BRIGHT");
        assertThat(bright.brightness()).isCloseTo(255, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(wide.qualityFlags()).contains("ASPECT");
        assertThat(tall.qualityFlags()).contains("ASPECT");
    }

    @Test
    void detailedPatternIsSharperThanUniformImage() throws Exception {
        BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++) {
            image.setRGB(x, y, ((x / 16 + y / 16) % 2 == 0 ? Color.BLACK : Color.WHITE).getRGB());
        }
        var result = analyzer.analyze(write("detail.png", image));
        assertThat(result.sharpness()).isGreaterThan(100);
        assertThat(result.qualityFlags()).doesNotContain("BLUR");
    }

    @Test
    void transparentPixelsAreMeasuredAgainstWhiteAndBrightnessThresholdsAreStrict() throws Exception {
        var transparent = analyzer.analyze(write("transparent.png",
                new BufferedImage(800, 450, BufferedImage.TYPE_INT_ARGB)));
        var lowBoundary = analyzer.analyze(write("low-boundary.png", solid(800, 450, new Color(35, 35, 35))));
        var highBoundary = analyzer.analyze(write("high-boundary.png", solid(800, 450, new Color(220, 220, 220))));
        assertThat(transparent.brightness()).isEqualTo(255);
        assertThat(transparent.qualityFlags()).contains("BRIGHT").doesNotContain("DARK");
        assertThat(lowBoundary.qualityFlags()).doesNotContain("DARK");
        assertThat(highBoundary.qualityFlags()).doesNotContain("BRIGHT");
    }

    @Test
    void rejectsUnsupportedCorruptAndTruncatedFilesWithoutChangingThem() throws Exception {
        Path text = directory.resolve("fake.png");
        Files.writeString(text, "not an image");
        Path gif = directory.resolve("unsupported.gif");
        ImageIO.write(solid(20, 20, Color.GRAY), "gif", gif.toFile());
        Path png = write("truncated.png", pattern(100, 80, false));
        byte[] bytes = Files.readAllBytes(png);
        Files.write(png, Arrays.copyOf(bytes, bytes.length / 2));
        Path jpeg = write("truncated.jpg", pattern(100, 80, false));
        byte[] jpegBytes = Files.readAllBytes(jpeg);
        Files.write(jpeg, Arrays.copyOf(jpegBytes, jpegBytes.length - 2));
        Path corrupt = write("corrupt.png", pattern(100, 80, false));
        byte[] corruptBytes = Files.readAllBytes(corrupt);
        for (int position = 8; position < corruptBytes.length - 12;) {
            int length = ByteBuffer.wrap(corruptBytes).getInt(position);
            if (ByteBuffer.wrap(corruptBytes).getInt(position + 4) == 0x49444154) {
                corruptBytes[position + 8] = 0; // Invalid zlib header in a genuine generated PNG.
                break;
            }
            position += 12 + length;
        }
        Files.write(corrupt, corruptBytes);
        for (Path file : new Path[]{text, gif, png, jpeg, corrupt}) {
            byte[] before = Files.readAllBytes(file);
            assertThatThrownBy(() -> analyzer.analyze(file)).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
        }
    }

    @Test
    void rejectsOversizedByteLengthAndDimensionsBeforeDecoding() throws Exception {
        Path oversized = directory.resolve("oversized.png");
        try (RandomAccessFile file = new RandomAccessFile(oversized.toFile(), "rw")) {
            file.setLength(25L * 1024 * 1024 + 1);
        }
        assertThatThrownBy(() -> analyzer.analyze(oversized)).isInstanceOf(IOException.class);
        for (int[] size : new int[][]{{20001, 1}, {10000, 4001}}) {
            Path file = write("dimensions-" + size[0] + ".png", solid(1, 1, Color.BLACK));
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(16, size[0]).putInt(20, size[1]);
            CRC32 crc = new CRC32();
            crc.update(bytes, 12, 17);
            ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
            Files.write(file, bytes);
            assertThatThrownBy(() -> analyzer.analyze(file)).isInstanceOf(IOException.class)
                    .hasMessageContaining("dimensions");
        }
    }

    @Test
    void bandsAndDistanceHandleSigned64BitHashes() {
        long hash = 0xFEDCBA9876543210L;
        assertThat(ReviewPhotoAnalyzer.band(hash, 0)).isEqualTo(0x3210);
        assertThat(ReviewPhotoAnalyzer.band(hash, 1)).isEqualTo(0x7654);
        assertThat(ReviewPhotoAnalyzer.band(hash, 2)).isEqualTo(0xBA98);
        assertThat(ReviewPhotoAnalyzer.band(hash, 3)).isEqualTo(0xFEDC);
        assertThat(ReviewPhotoAnalyzer.distance(0L, -1L)).isEqualTo(64);
        assertThat(ReviewPhotoAnalyzer.distance(Long.MIN_VALUE, 0L)).isEqualTo(1);
        assertThatThrownBy(() -> ReviewPhotoAnalyzer.band(hash, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewPhotoAnalyzer.band(hash, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    private Path write(String name, BufferedImage image) throws IOException {
        Path file = directory.resolve(name);
        assertThat(ImageIO.write(image, name.endsWith("jpg") ? "jpeg" : "png", file.toFile())).isTrue();
        return file;
    }

    private BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private BufferedImage pattern(int width, int height, boolean reverse) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int value = (int) (30 + 190.0 * x / (width - 1) + 20 * Math.sin(6.0 * y / height));
            if (reverse) value = 255 - value;
            image.setRGB(x, y, new Color(value, value, value).getRGB());
        }
        return image;
    }

    private BufferedImage resize(BufferedImage original, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(original, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }
}
