package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewPhase4CleanupTest {
    @Test
    void dedicatedPhotoGalleryIsRemovedWithoutRemovingReviewOrSharedViewer() throws Exception {
        String template = read("templates/destination/detail.html");
        assertThat(template).doesNotContain("photo-review-section", "photo-modal", "comment/modal.js")
                .contains("destination-image-modal", "traveler-reviews", "data-review-id");
        for (String file : new String[]{"init.js", "events.js", "api.js", "render.js"}) {
            assertThat(read("static/js/comment/" + file))
                    .doesNotContain("fetchThumbnails", "renderThumbnails", "initPhotoModal", "bindGalleryEvents", "onThumbnailsReload");
        }
        assertThat(read("static/js/comment/render.js")).contains("comment-image", "data-review-image-id");
        assertThat(read("mapper/DestinationCommentImageMapper.xml"))
                .contains("findApprovedGallery", "findAllImageUrlsByDestinationId")
                .doesNotContain("findGalleryByDestinationId");
        assertThat(Files.exists(Path.of("src/main/resources/static/js/comment/modal.js"))).isFalse();
    }

    private String read(String file) throws Exception {
        return Files.readString(Path.of("src/main/resources", file));
    }
}
