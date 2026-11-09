package com.tripbora.service.destination;

import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DestinationGalleryFileReadTest {
    @TempDir Path root;
    @Test void administratorExistenceCheckOnlyReadsManagedRegularFiles() throws Exception {
        var files = new FileUploadService(root.toString());
        Files.createDirectories(root.resolve("destinations"));
        Files.write(root.resolve("destinations/photo.jpg"), new byte[]{1});
        Files.write(root.resolve("private.jpg"), new byte[]{1});
        assertThat(files.destinationFileExists("/uploads/destinations/photo.jpg")).isTrue();
        for (String path : new String[]{null, "/uploads/destinations/../private.jpg", "/uploads/diaries/private.jpg",
                "/uploads/destinations/missing.jpg", "https://example.com/photo", "/uploads/destinations/..\\private.jpg"}) {
            assertThat(files.destinationFileExists(path)).isFalse();
        }
    }
    @Test void symbolicLinksAreRejected() throws Exception {
        var files = new FileUploadService(root.toString());
        Files.createDirectories(root.resolve("destinations"));
        Path secret = Files.write(root.resolve("private.jpg"), new byte[]{1});
        try { Files.createSymbolicLink(root.resolve("destinations/link.jpg"), secret); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) { Assumptions.abort("Symbolic links unavailable"); }
        assertThat(files.destinationFileExists("/uploads/destinations/link.jpg")).isFalse();
    }
}
