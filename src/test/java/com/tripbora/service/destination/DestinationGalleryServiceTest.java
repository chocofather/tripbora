package com.tripbora.service.destination;

import com.tripbora.dto.*;
import com.tripbora.model.DestinationImage;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DestinationGalleryServiceTest {
    private DestinationMapper destinations;
    private DestinationCommentImageMapper reviews;
    private FileUploadService files;
    private DestinationGalleryService service;
    private final List<DestinationImage> admin = new ArrayList<>();
    private final List<DestinationGalleryReviewCandidate> traveler = new ArrayList<>();
    private static final Comparator<DestinationImage> ADMIN = Comparator.comparing(DestinationImage::getOrderIndex)
            .thenComparing(DestinationImage::getId);
    private static final Comparator<DestinationGalleryReviewCandidate> TRAVELER = Comparator
            .comparing(DestinationGalleryReviewCandidate::getGalleryPriority, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DestinationGalleryReviewCandidate::getReviewCreatedAt, Comparator.reverseOrder())
            .thenComparing(DestinationGalleryReviewCandidate::getCommentId, Comparator.reverseOrder())
            .thenComparing(DestinationGalleryReviewCandidate::getDisplayOrder).thenComparing(DestinationGalleryReviewCandidate::getId);

    @BeforeEach void setup() {
        destinations = mock(DestinationMapper.class); reviews = mock(DestinationCommentImageMapper.class);
        files = mock(FileUploadService.class); service = new DestinationGalleryService(destinations, reviews, files);
        when(destinations.isPublicDestination(10L)).thenReturn(true);
        when(files.commentFileExists(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("ok"));
        when(files.destinationFileExists(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("ok"));
        when(destinations.findGalleryCandidates(eq(10L), any(), anyBoolean(), anyInt())).thenAnswer(call ->
                adminRows(call.getArgument(1), call.getArgument(2)).stream().limit((int) call.getArgument(3)).toList());
        when(destinations.hasGalleryCandidates(eq(10L), any(), anyBoolean())).thenAnswer(call ->
                !adminRows(call.getArgument(1), call.getArgument(2)).isEmpty());
        when(reviews.findGalleryCandidates(eq(10L), any(), anyBoolean(), anyInt())).thenAnswer(call ->
                travelerRows(call.getArgument(1), call.getArgument(2)).stream().limit((int) call.getArgument(3)).toList());
        when(reviews.hasGalleryCandidates(eq(10L), any(), anyBoolean())).thenAnswer(call ->
                !travelerRows(call.getArgument(1), call.getArgument(2)).isEmpty());
    }

    private List<DestinationImage> adminRows(DestinationGalleryPosition anchor, boolean reverse) {
        var boundary = new DestinationImage();
        if (anchor != null) { boundary.setId(anchor.getImageId()); boundary.setOrderIndex(anchor.getOrderIndex()); }
        return admin.stream().filter(image -> anchor == null || (reverse ? ADMIN.compare(image, boundary) < 0 : ADMIN.compare(image, boundary) > 0))
                .sorted(reverse ? ADMIN.reversed() : ADMIN).toList();
    }
    private List<DestinationGalleryReviewCandidate> travelerRows(DestinationGalleryPosition anchor, boolean reverse) {
        var boundary = new DestinationGalleryReviewCandidate();
        if (anchor != null) {
            boundary.setId(anchor.getImageId()); boundary.setCommentId(anchor.getCommentId()); boundary.setGalleryPriority(anchor.getPriority());
            boundary.setReviewCreatedAt(anchor.getReviewCreatedAt()); boundary.setDisplayOrder(anchor.getDisplayOrder());
        }
        return traveler.stream().filter(image -> anchor == null || (reverse ? TRAVELER.compare(image, boundary) < 0 : TRAVELER.compare(image, boundary) > 0))
                .sorted(reverse ? TRAVELER.reversed() : TRAVELER).toList();
    }
    private DestinationImage administrator(long id, String url) {
        var image = new DestinationImage(); image.setId(id); image.setDestinationId(10L); image.setOrderIndex((int) (id / 3)); image.setImageUrl(url);
        return image;
    }
    private DestinationGalleryReviewCandidate photo(long id, boolean exists) {
        var photo = new DestinationGalleryReviewCandidate(); photo.setId(id); photo.setCommentId(20L + id / 3);
        photo.setDestinationId(10L); photo.setImageUrl("/uploads/comments/" + (exists ? "ok" : "missing") + id + ".jpg");
        photo.setDisplayOrder((int) (id % 3) + 1); photo.setReviewCreatedAt(LocalDateTime.of(2026, 11, 6, 10, 0));
        photo.setGalleryPriority(id % 4 == 0 ? 1 : null); return photo;
    }
    private List<String> keys(DestinationGalleryPage page) { return page.items().stream().map(DestinationGalleryImage::getGalleryKey).toList(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,0","1,0","0,1","30,0","31,0","30,1","100,100"})
    void exactBoundariesAndAdministratorToTravelerTransitionHaveNoTotalCap(int adminCount,int travelerCount) {
        LongStream.rangeClosed(1,adminCount).forEach(id->admin.add(administrator(id,"https://images.example/"+id)));
        LongStream.rangeClosed(1,travelerCount).forEach(id->traveler.add(photo(id,true)));
        var found=new ArrayList<String>();
        var page=service.page(10L,"ALL",null);
        int requests=0;
        while(true){
            requests++;
            assertThat(page.items()).hasSizeLessThanOrEqualTo(30);
            found.addAll(keys(page));
            if(page.nextCursor()==null)break;
            assertThat(requests).isLessThan(10);
            page=service.page(10L,"ALL",page.nextCursor());
        }
        var expected=new ArrayList<String>();
        admin.stream().sorted(ADMIN).forEach(image->expected.add("admin:"+image.getId()));
        traveler.stream().sorted(TRAVELER).forEach(image->expected.add("review:"+image.getId()));
        assertThat(found).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        assertThat(requests).isEqualTo(Math.max(1,(adminCount+travelerCount+29)/30));
    }

    @Test void walksThousandsOfStablePhotosForwardAndBackwardWithNoDuplicatesOrMissingRows() {
        LongStream.rangeClosed(1, 103).forEach(id -> admin.add(administrator(id, "https://images.example/" + id + ".jpg")));
        LongStream.rangeClosed(1, 1103).forEach(id -> traveler.add(photo(id, true)));
        var pages = new ArrayList<DestinationGalleryPage>();
        DestinationGalleryPage page = service.page(10L, "ALL", null);
        assertThat(page.previousCursor()).isNull();
        var all = new ArrayList<String>();
        do {
            pages.add(page); assertThat(page.items()).hasSizeLessThanOrEqualTo(30); all.addAll(keys(page));
            if (page.nextCursor() == null) break;
            page = service.page(10L, "ALL", page.nextCursor());
        } while (pages.size() < 50);
        List<String> expected = new ArrayList<>();
        admin.stream().sorted(ADMIN).forEach(image -> expected.add("admin:" + image.getId()));
        traveler.stream().sorted(TRAVELER).forEach(image -> expected.add("review:" + image.getId()));
        assertThat(all).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        for (int index = pages.size() - 1; index > 0; index--) {
            page = service.page(10L, "ALL", page.previousCursor());
            assertThat(keys(page)).containsExactlyElementsOf(keys(pages.get(index - 1)));
        }
        assertThat(page.previousCursor()).isNull();
    }

    @Test void missingFilesConsumeSharedBudgetAndEmptyPageStillMakesProgress() {
        LongStream.rangeClosed(1, 305).forEach(id -> admin.add(administrator(id, "/uploads/destinations/missing" + id + ".jpg")));
        traveler.add(photo(1, true));
        var first = service.page(10L, "ALL", null);
        assertThat(first.items()).isEmpty(); assertThat(first.nextCursor()).isNotNull();
        verify(files, times(300)).destinationFileExists(anyString()); verify(files, never()).commentFileExists(anyString());
        var second = service.page(10L, "ALL", first.nextCursor());
        assertThat(keys(second)).containsExactly("review:1"); assertThat(second.nextCursor()).isNull();
    }

    @Test void missingTravelerFilesAlsoContinueBeyondBudgetAndEndExactlyAtBoundary() {
        LongStream.rangeClosed(1, 301).forEach(id -> {
            var photo = photo(id, id == 301); photo.setGalleryPriority(null); photo.setCommentId(20L); photo.setDisplayOrder(1);
            traveler.add(photo);
        });
        var first = service.page(10L, "TRAVELER", null);
        assertThat(first.items()).isEmpty(); assertThat(first.nextCursor()).isNotNull();
        verify(files, times(300)).commentFileExists(anyString());
        var second = service.page(10L, "TRAVELER", first.nextCursor());
        assertThat(keys(second)).containsExactly("review:301"); assertThat(second.nextCursor()).isNull();
    }

    @Test void approvalRevocationAndDeletionAreReflectedWhenAnEvictedWindowIsRequestedAgain() {
        LongStream.rangeClosed(1, 70).forEach(id -> admin.add(administrator(id, "https://images.example/" + id)));
        var first = service.page(10L, "ADMIN", null);
        var second = service.page(10L, "ADMIN", first.nextCursor());
        admin.removeIf(image -> image.getId() == 2);
        var reloaded = service.page(10L, "ADMIN", second.previousCursor());
        assertThat(keys(reloaded)).doesNotContain("admin:2");
        when(destinations.isPublicDestination(10L)).thenReturn(false);
        assertThatThrownBy(() -> service.page(10L, "ADMIN", first.nextCursor()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test void malformedOversizedAndCrossScopeCursorsAreRejectedBeforeQueries() {
        admin.add(administrator(1, "https://images.example/a")); admin.add(administrator(2, "https://images.example/b"));
        LongStream.rangeClosed(3, 31).forEach(id -> admin.add(administrator(id, "https://images.example/" + id)));
        String cursor = service.page(10L, "ADMIN", null).nextCursor();
        clearInvocations(destinations, reviews, files);
        for (String value : new String[]{"", "!bad", "x".repeat(1025), Base64.getUrlEncoder().encodeToString("1|10|ADMIN|N|A|-1|1|||".getBytes())}) {
            assertThatThrownBy(() -> service.page(10L, "ADMIN", value))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> service.page(11L, "ADMIN", cursor)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page(10L, "ALL", cursor)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.page(10L, "bad", null)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(destinations, reviews, files);
    }

    @Test void externalAdminImagesAreNeverFetchedAndUnsafeUrlsAreSkippedWhileMetadataSurvives() {
        var image = administrator(4, "https://images.example/photo"); image.setSourceName("Official"); image.setLicenseType("KOGL_TYPE_3");
        image.setCustomAttribution("Credit"); image.setWorkPageUrl("https://example.com/work"); image.setCardPositionX(23);
        admin.addAll(List.of(administrator(1, "javascript:alert(1)"), administrator(2, "https://user@example.com/private"),
                administrator(3, "//example.com/photo"), image));
        var page = service.page(10L, "ADMIN", null);
        assertThat(keys(page)).containsExactly("admin:4");
        assertThat(page.items().get(0).getCustomAttribution()).isEqualTo("Credit");
        assertThat(page.items().get(0).isNoDerivatives()).isTrue(); assertThat(page.items().get(0).getCardPositionX()).isEqualTo(23);
        verify(files, never()).commentFileExists(anyString());
    }

    @Test void sharedBudgetAlsoAppliesToMissingCandidatesAcrossTheAdministratorTravelerBoundary() {
        LongStream.rangeClosed(1, 150).forEach(id -> admin.add(administrator(id, "/uploads/destinations/missing" + id + ".jpg")));
        LongStream.rangeClosed(1, 180).forEach(id -> {
            var photo = photo(id, false); photo.setGalleryPriority(null); photo.setCommentId(20L); photo.setDisplayOrder(1);
            traveler.add(photo);
        });
        var page = service.page(10L, "ALL", null);
        assertThat(page.items()).isEmpty(); assertThat(page.nextCursor()).isNotNull();
        verify(files, times(150)).destinationFileExists(anyString()); verify(files, times(150)).commentFileExists(anyString());
        var end = service.page(10L, "ALL", page.nextCursor());
        assertThat(end.items()).isEmpty(); assertThat(end.nextCursor()).isNull();
    }

    @Test void aFullyDeletedForwardWindowStillOffersARequeriedPreviousWindow() {
        LongStream.rangeClosed(1, 31).forEach(id -> admin.add(administrator(id, "https://images.example/" + id)));
        var first = service.page(10L, "ADMIN", null);
        admin.removeIf(image -> image.getId() >= 30);
        var empty = service.page(10L, "ADMIN", first.nextCursor());
        assertThat(empty.items()).isEmpty(); assertThat(empty.nextCursor()).isNull(); assertThat(empty.previousCursor()).isNotNull();
        assertThat(service.page(10L, "ADMIN", empty.previousCursor()).items()).hasSize(29);
    }
}
