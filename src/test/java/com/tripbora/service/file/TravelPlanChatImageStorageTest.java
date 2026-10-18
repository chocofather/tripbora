package com.tripbora.service.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TravelPlanChatImageStorageTest {
    @TempDir Path uploadRoot;

    private TravelPlanChatImageStorage storage() {
        return new TravelPlanChatImageStorage(new FileUploadService(uploadRoot.toString()), uploadRoot.toString());
    }

    @Test
    void storesActualJpegPngAndWebpPrivatelyIgnoringClientNameAndMime() throws Exception {
        TravelPlanChatImageStorage storage = storage();
        for (String format : new String[]{"jpg", "png", "webp"}) {
            byte[] bytes = format.equals("webp") ? Base64.getDecoder().decode(
                    "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89") : image(format);
            var saved = storage.store(7L, file(bytes));
            assertThat(saved.storageKey()).matches("7/[0-9a-f-]{36}\\." + format);
            assertThat(saved.contentType()).isEqualTo("image/" + (format.equals("jpg") ? "jpeg" : format));
            assertThat(saved.byteSize()).isEqualTo(bytes.length);
            assertThat(saved.width()).isEqualTo(format.equals("webp") ? 1 : 6);
            assertThat(saved.height()).isEqualTo(format.equals("webp") ? 1 : 4);
            assertThat(storage.read(saved.storageKey())).startsWith(uploadRoot.resolve("private/travel-plan-chat"));
            assertThat(Files.readAllBytes(storage.read(saved.storageKey()))).isEqualTo(bytes);
            storage.delete(saved.storageKey());
            storage.delete(saved.storageKey());
            assertThatThrownBy(() -> storage.read(saved.storageKey())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsEmptyForgedOversizedAndInvalidPlan() throws Exception {
        TravelPlanChatImageStorage storage = storage();
        for (byte[] bytes : new byte[][]{new byte[0], "<svg/>".getBytes(), new byte[10 * 1024 * 1024 + 1],
                Base64.getDecoder().decode("UklGRgQAAABXRUJQ")}) {
            assertThatThrownBy(() -> storage.store(7L, file(bytes))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.store(0L, file(image("png")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(null, file(image("png")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(7L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(uploadRoot.resolve("private/travel-plan-chat/7")).doesNotExist();
    }

    @Test
    void rejectsPixelBombBeforeSaving() {
        byte[] data = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89");
        // VP8 keyframe width and height: 10000 x 10000, above the 40 million pixel bound.
        data[26] = 0x10; data[27] = 0x27; data[28] = 0x10; data[29] = 0x27;
        assertThatThrownBy(() -> storage().store(7L, file(data))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnmanagedPathsForReadAndDelete() {
        for (String key : new String[]{"../outside.png", "1/../outside.png", "/1/" + UUID.randomUUID() + ".png",
                "1\\" + UUID.randomUUID() + ".png", "1/not-a-uuid.png", "0/" + UUID.randomUUID() + ".png"}) {
            assertThatThrownBy(() -> storage().read(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage().delete(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void staleScanReturnsOnlyOldManagedFilesWithBoundedLimit() throws Exception {
        TravelPlanChatImageStorage storage = storage();
        var old = storage.store(7L, file(image("png")));
        var fresh = storage.store(7L, file(image("jpg")));
        Files.setLastModifiedTime(storage.read(old.storageKey()), FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));
        Files.writeString(uploadRoot.resolve("private/travel-plan-chat/7/unmanaged.txt"), "keep");
        assertThat(storage.staleKeys(200)).containsExactly(old.storageKey()).doesNotContain(fresh.storageKey());
        assertThat(storage.staleKeys(0)).isEmpty();
        assertThat(storage.staleKeys(1)).hasSize(1);
    }

    @Test
    void staleScanAdvancesPastFilesThatRemainReferencedAndWrapsAround() throws Exception {
        TravelPlanChatImageStorage storage = storage();
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String key = storage.store(7L, file(image("png"))).storageKey();
            Files.setLastModifiedTime(storage.read(key), FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));
            keys.add(key);
        }
        keys.sort(String::compareTo);
        assertThat(storage.staleKeys(1)).containsExactly(keys.get(0));
        // The caller leaves referenced candidates on disk: subsequent scans must still advance.
        assertThat(storage.staleKeys(1)).containsExactly(keys.get(1));
        assertThat(storage.staleKeys(1)).containsExactly(keys.get(2));
        assertThat(storage.staleKeys(1)).containsExactly(keys.get(0));
    }

    @Test
    void validatesAndStoresOneSnapshotOfAMutableUpload() throws Exception {
        byte[] original = image("png");
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        MockMultipartFile changing = new MockMultipartFile("image", "image.png", "image/png", original) {
            @Override
            public java.io.InputStream getInputStream() {
                return new java.io.ByteArrayInputStream(reads.getAndIncrement() == 0
                        ? original : "<script>changed</script>".getBytes());
            }
        };
        TravelPlanChatImageStorage storage = storage();
        var saved = storage.store(7L, changing);
        assertThat(Files.readAllBytes(storage.read(saved.storageKey()))).isEqualTo(original);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    void rejectsSymlinkDirectoriesAndFiles() throws Exception {
        Path outside = Files.createDirectory(uploadRoot.resolve("outside"));
        Path base = Files.createDirectories(uploadRoot.resolve("private/travel-plan-chat"));
        try {
            Files.createSymbolicLink(base.resolve("7"), outside);
        } catch (Exception exception) {
            assumeTrue(false, "Symbolic links unavailable on this platform");
        }
        assertThatThrownBy(() -> storage().store(7L, file(image("png")))).isInstanceOf(IllegalArgumentException.class);
        String key = "7/" + UUID.randomUUID() + ".png";
        Files.write(outside.resolve(key.substring(2)), image("png"));
        assertThatThrownBy(() -> storage().read(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage().delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage().staleKeys(200)).isEmpty();
        assertThat(outside.resolve(key.substring(2))).exists();

        Path normalDirectory = Files.createDirectory(base.resolve("8"));
        Path fileLink = normalDirectory.resolve(UUID.randomUUID() + ".png");
        Files.createSymbolicLink(fileLink, outside.resolve(key.substring(2)));
        String linkedKey = "8/" + fileLink.getFileName();
        assertThatThrownBy(() -> storage().read(linkedKey)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage().delete(linkedKey)).isInstanceOf(IllegalArgumentException.class);
        assertThat(storage().staleKeys(200)).isEmpty();
        assertThat(outside.resolve(key.substring(2))).exists();
    }

    @Test
    void unreadableUploadLeavesNoStoredFile() throws Exception {
        byte[] image = image("png");
        MockMultipartFile broken = new MockMultipartFile("image", "image.png", "image/png", image) {
            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                throw new java.io.IOException("simulated upload failure");
            }
        };
        assertThatThrownBy(() -> storage().store(7L, broken)).isInstanceOf(IllegalArgumentException.class);
        assertThat(uploadRoot.resolve("private/travel-plan-chat/7")).doesNotExist();
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("image", "untrusted.svg", "text/html", bytes);
    }

    private byte[] image(String format) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }
}
