package com.tripbora.service.comment;

import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.comment.ReviewGalleryPriorityMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.policy.GalleryConsentService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 DB/파일을 사용하지 않고 우선순위 명령의 권한·잠금·버전 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ReviewGalleryPriorityServiceTest {
    @Test void uploadLicenseOnlyPhotoCanChangePriority() {
        givenEligiblePhoto();
        image.setGalleryConsentAt(null);
        image.setGalleryConsentVersion(null);
        image.setGalleryLicensePolicyId(700L);
        image.setGalleryLicenseAcceptedAt(java.time.LocalDateTime.of(2026, 11, 6, 12, 0));
        when(priorities.update(30L, 1, 0L)).thenReturn(1);
        service.setPriority(10L, 30L, 99L, 1, 0L);
        verify(consent).requirePhotoValid(30L);
        verify(priorities).update(30L, 1, 0L);
    }
    @Mock DestinationCommentImageMapper images;
    @Mock DestinationCommentMapper comments;
    @Mock UserMapper users;
    @Mock ReviewGalleryPriorityMapper priorities;
    @Mock GalleryConsentService consent;
    ReviewGalleryPriorityService service;
    User admin;
    User owner;
    Destination destination;
    DestinationComment comment;
    DestinationCommentImage image;

    @BeforeEach
    void setUp() {
        service = new ReviewGalleryPriorityService(images, comments, users, priorities, consent);
        admin = new User();
        admin.setId(99L);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setUserRole(UserRole.ADMIN);
        owner = new User();
        owner.setId(7L);
        owner.setStatus(UserStatus.ACTIVE);
        destination = new Destination();
        destination.setId(10L);
        destination.setPublicVisible(true);
        comment = new DestinationComment();
        comment.setId(20L);
        comment.setUserId(7L);
        comment.setDestinationId(10L);
        comment.setDeleted(false);
        image = new DestinationCommentImage();
        image.setId(30L);
        image.setCommentId(20L);
        image.setGalleryStatus("APPROVED");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("20261030-v1");
    }

    private void givenEligiblePhoto() {
        lenient().when(users.findById(99L)).thenReturn(admin);
        lenient().when(images.findById(30L)).thenReturn(image);
        lenient().when(comments.findById(20L)).thenReturn(comment);
        lenient().when(images.lockUser(7L)).thenReturn(owner);
        lenient().when(images.lockDestination(10L)).thenReturn(destination);
        lenient().when(images.lockComment(20L)).thenReturn(comment);
        lenient().when(images.findByCommentIdForUpdate(20L)).thenReturn(List.of(image));
    }

    private void assertRejected(HttpStatus status, Long destinationId, Integer priority, long version) {
        assertThatThrownBy(() -> service.setPriority(destinationId, 30L, 99L, priority, version))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(status));
        verifyNoInteractions(priorities);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 30})
    void approvedPhotoPriorityChangesOnlyPriorityUsingSubmittedVersion(int priority) {
        givenEligiblePhoto();
        when(priorities.update(30L, priority, 4L)).thenReturn(1);

        service.setPriority(10L, 30L, 99L, priority, 4L);

        var order = inOrder(images, consent, priorities);
        order.verify(images).findById(30L);
        order.verify(images).lockUser(7L);
        order.verify(images).lockDestination(10L);
        order.verify(images).lockComment(20L);
        order.verify(images).findByCommentIdForUpdate(20L);
        order.verify(consent).requirePhotoValid(30L);
        order.verify(priorities).update(30L, priority, 4L);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
        verify(images, never()).deleteById(anyLong());
        verify(images, never()).insert(any());
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
        verify(comments, never()).updateContent(any());
        assertThat(image.getGalleryStatus()).isEqualTo("APPROVED");
    }

    @Test
    void nullPriorityClearsOnlyTheGalleryPreference() {
        givenEligiblePhoto();
        when(priorities.update(30L, null, 4L)).thenReturn(1);
        service.setPriority(10L, 30L, 99L, null, 4L);
        verify(priorities).update(30L, null, 4L);
        verify(images, never()).deleteById(anyLong());
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void anonymousActorIsForbiddenBeforeLookingUpPhoto() {
        assertThatThrownBy(() -> service.setPriority(10L, 30L, null, 1, 0L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(images, comments, users, consent, priorities);
    }

    @Test
    void ordinaryMemberCannotSetPriority() {
        when(users.findById(99L)).thenReturn(admin);
        admin.setUserRole(UserRole.USER);
        assertRejected(HttpStatus.FORBIDDEN, 10L, 1, 0);
        verifyNoInteractions(images, comments, consent);
    }

    @Test
    void inactiveAdministratorCannotSetPriority() {
        when(users.findById(99L)).thenReturn(admin);
        admin.setStatus(UserStatus.RESTRICTED);
        assertRejected(HttpStatus.FORBIDDEN, 10L, 1, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 31})
    void outOfRangePriorityIsRejected(int priority) {
        when(users.findById(99L)).thenReturn(admin);
        assertRejected(HttpStatus.BAD_REQUEST, 10L, priority, 0);
        verifyNoInteractions(images, comments, consent);
    }

    @Test
    void negativeVersionIsRejected() {
        when(users.findById(99L)).thenReturn(admin);
        assertRejected(HttpStatus.BAD_REQUEST, 10L, 1, -1);
    }

    @Test
    void imageFromAnotherDestinationCannotBeChanged() {
        givenEligiblePhoto();
        assertRejected(HttpStatus.NOT_FOUND, 11L, 1, 0);
    }

    @Test
    void missingPhotoIsNotFound() {
        when(users.findById(99L)).thenReturn(admin);
        assertRejected(HttpStatus.NOT_FOUND, 10L, 1, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "PENDING", "REJECTED", "REVOKED"})
    void onlyCurrentlyApprovedPhotosCanBePrioritized(String status) {
        givenEligiblePhoto();
        image.setGalleryStatus(status);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void clearingAlsoRequiresAnApprovedVisiblePhoto() {
        givenEligiblePhoto();
        image.setGalleryStatus("REVOKED");
        assertRejected(HttpStatus.CONFLICT, 10L, null, 0);
    }

    @Test
    void withdrawnPhotoConsentBlocksPriorityChange() {
        givenEligiblePhoto();
        image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis()));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(consent).requirePhotoValid(30L);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void missingPhotoConsentBlocksPriorityChange() {
        givenEligiblePhoto();
        image.setGalleryConsentAt(null);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(consent).requirePhotoValid(30L);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void missingPhotoConsentVersionBlocksPriorityChange() {
        givenEligiblePhoto();
        image.setGalleryConsentVersion(null);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(consent).requirePhotoValid(30L);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void currentMemberConsentIsRequiredEvenForAnApprovedSnapshot() {
        givenEligiblePhoto();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(consent).requirePhotoValid(30L);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void hiddenDestinationBlocksPriorityChange() {
        givenEligiblePhoto();
        destination.setPublicVisible(false);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void deletedReviewBlocksPriorityChange() {
        givenEligiblePhoto();
        comment.setDeleted(true);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void moderatedReviewBlocksPriorityChange() {
        givenEligiblePhoto();
        comment.setModerated(true);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void replyPhotoOutsideTheReviewGalleryCannotBePrioritized() {
        givenEligiblePhoto();
        comment.setParentCommentId(18L);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void inactiveOwnerBlocksPriorityChange() {
        givenEligiblePhoto();
        owner.setStatus(UserStatus.WITHDRAWAL_PENDING);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void changedLockedReviewScopeCannotReuseEarlierLookup() {
        givenEligiblePhoto();
        DestinationComment changed = new DestinationComment();
        changed.setId(20L);
        changed.setUserId(8L);
        changed.setDestinationId(10L);
        when(images.lockComment(20L)).thenReturn(changed);
        assertRejected(HttpStatus.CONFLICT, 10L, 1, 0);
    }

    @Test
    void missingLockedPhotoCannotReuseEarlierLookup() {
        givenEligiblePhoto();
        when(images.findByCommentIdForUpdate(20L)).thenReturn(List.of());
        assertRejected(HttpStatus.NOT_FOUND, 10L, 1, 0);
    }

    @Test
    void staleVersionDoesNotSilentlyOverwriteAnotherAdministrator() {
        givenEligiblePhoto();
        when(priorities.update(30L, 2, 4L)).thenReturn(0);
        assertThatThrownBy(() -> service.setPriority(10L, 30L, 99L, 2, 4L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(priorities).update(30L, 2, 4L);
        verifyNoMoreInteractions(priorities);
    }

    @Test
    void mapperBindsNullablePriorityAndUsesVersionCompareAndIncrement() throws Exception {
        Configuration config = new Configuration();
        try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/ReviewGalleryPriorityMapper.xml"))) {
            new XMLMapperBuilder(input, config, "priority", config.getSqlFragments()).parse();
        }
        Map<String, Object> values = new HashMap<>();
        values.put("imageId", 30L);
        values.put("priority", null);
        values.put("expectedVersion", 4L);
        var bound = config.getMappedStatement(ReviewGalleryPriorityMapper.class.getName() + ".update")
                .getBoundSql(values);
        assertThat(bound.getSql().replaceAll("\\s+", " "))
                .contains("UPDATE destination_comment_images", "gallery_priority = ?",
                        "gallery_priority_version = gallery_priority_version + 1", "WHERE id = ?",
                        "gallery_priority_version = ?", "gallery_status = 'APPROVED'")
                .doesNotContain("DELETE", "INSERT", "reviewed_by", "review_reason", "display_order");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("priority", "imageId", "expectedVersion");
    }
}
