package com.tripbora.service.comment;

import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReviewImageCleanupTest {
    @TempDir Path root;
    private static final String NAME = "00000000-0000-4000-8000-000000000030.jpg";
    private static final String URL = "/uploads/comments/" + NAME;

    private Path file(boolean stale) throws Exception {
        Path file = Files.createDirectories(root.resolve("comments")).resolve(NAME);
        Files.write(file, new byte[]{1});
        if (stale) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));
        return file;
    }

    @Test void staleUnreferencedFileIsDeleted() throws Exception {
        Path file = file(true);
        var images = mock(DestinationCommentImageMapper.class);
        new ReviewImageCleanup(images, new FileUploadService(root.toString()), root.toString()).clean();
        verify(images).isReferenced(URL);
        assertThat(file).doesNotExist();
    }

    @Test void softDeletedReviewsStillCountAsReferences() throws Exception {
        Path file = file(true);
        var images = mock(DestinationCommentImageMapper.class);
        when(images.isReferenced(URL)).thenReturn(true);
        new ReviewImageCleanup(images, new FileUploadService(root.toString()), root.toString()).clean();
        assertThat(file).exists();
    }

    @Test void databaseFailurePreservesFileAndNextSweepRetries() throws Exception {
        Path file = file(true);
        var images = mock(DestinationCommentImageMapper.class);
        when(images.isReferenced(URL)).thenThrow(new IllegalStateException()).thenReturn(false);
        var cleanup = new ReviewImageCleanup(images, new FileUploadService(root.toString()), root.toString());
        cleanup.clean();
        assertThat(file).exists();
        cleanup.clean();
        assertThat(file).doesNotExist();
    }

    @Test void freshUploadsAreNeverSwept() throws Exception {
        Path file = file(false);
        var images = mock(DestinationCommentImageMapper.class);
        new ReviewImageCleanup(images, new FileUploadService(root.toString()), root.toString()).clean();
        assertThat(file).exists();
        verifyNoInteractions(images);
    }
}
