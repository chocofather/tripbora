package com.tripbora.service.comment;

import com.tripbora.model.Destination;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.DestinationCommentImage;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DB와 파일 시스템의 경계를 검증한다. 실제 DB에는 연결하지 않는다. */
@ExtendWith(MockitoExtension.class)
class ReviewImageServiceTest {
    @Test void uploadLicenseOnlyPhotoCanBeApprovedAndKeptWithoutLegacyConsentFields() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryLicensePolicyId(700L);
        image.setGalleryLicenseAcceptedAt(java.time.LocalDateTime.of(2026, 11, 6, 12, 0));
        image.setGalleryConsentAt(null);
        image.setGalleryConsentVersion(null);
        when(images.review(30L, "APPROVED", 99L, null)).thenReturn(1);
        service.review(30L, 99L, "APPROVE", null);
        image.setGalleryStatus("APPROVED");
        service.review(30L, 99L, "KEEP", null);
        verify(images).review(30L, "APPROVED", 99L, null);
        verify(images, never()).deleteById(anyLong());
        verifyNoInteractions(files);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"add", "edit", "editWithRating"})
    void uploadTransactionsReadCurrentConsentAfterWaitingForTheOwnerLock(String operation) {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(tx);
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxy = (ReviewImageService) factory.getProxy();
        assertThatThrownBy(() -> {
            switch (operation) {
                case "add" -> proxy.add(20L, 7L, List.of());
                case "edit" -> proxy.edit(20L, 7L, "리뷰", List.of(), List.of());
                case "editWithRating" -> proxy.edit(20L, 7L, "리뷰", List.of(), List.of(), 5, true);
            }
        }).isInstanceOf(ResponseStatusException.class);
        verify(manager).getTransaction(argThat(definition -> definition.getIsolationLevel()
                == org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED));
    }
    @Test void aCurrentMemberAgreementCannotAuthorizeAnUnrelatedPhotoSnapshot() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(Timestamp.valueOf("2026-10-30 12:00:00"));
        image.setGalleryConsentVersion("old-material-basis");
        when(images.photoRights(30L)).thenReturn(false);
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }
    @Test void pendingRejectionNeedsNoApprovalConsentOrPublicReviewAndPreservesOriginal() {
        givenAdmin();image.setGalleryStatus("PENDING");comment.setDeleted(true);comment.setModerated(true);comment.setParentCommentId(5L);
        when(images.lockUser(7L)).thenReturn(null);when(images.lockDestination(10L)).thenReturn(null);
        var consent=mock(com.tripbora.service.policy.GalleryConsentService.class);service.setGalleryConsentService(consent);
        service.review(30L,99L,"REJECT","사용하지 않을 사진");
        verify(images).review(30L,"REJECTED",99L,"사용하지 않을 사진");
        verifyNoInteractions(consent,files);verify(images,never()).deleteById(anyLong());
        verify(comments,never()).updateContent(any());assertThat(comment.getDeleted()).isTrue();
        assertThatThrownBy(()->service.review(30L,99L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void legacyPendingPhotoWithoutOwnerCanBeRejectedWithDestinationCommentImageLocks() {
        givenAdmin();comment.setUserId(null);image.setGalleryStatus("PENDING");
        service.review(30L,99L,"REJECT","중복 사진");
        verify(images,never()).lockUser(isNull());
        var order=inOrder(images);order.verify(images).lockDestination(10L);order.verify(images).lockComment(20L);
        order.verify(images).findByCommentIdForUpdate(20L);order.verify(images).review(30L,"REJECTED",99L,"중복 사진");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"consent","withdrawn","deleted","hidden","restricted","private","reply"})
    void eachApprovalRestrictionStillAllowsPendingRejection(String restriction) {
        givenAdmin();image.setGalleryStatus("PENDING");image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));image.setGalleryConsentVersion("v1");
        switch(restriction) {
            case "consent" -> { image.setGalleryConsentAt(null); when(images.photoRights(30L)).thenReturn(false); }
            case "withdrawn" -> { image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis())); when(images.photoRights(30L)).thenReturn(false); }
            case "deleted" -> comment.setDeleted(true);
            case "hidden" -> comment.setModerated(true);
            case "restricted" -> when(images.lockUser(7L)).thenReturn(null);
            case "private" -> when(images.lockDestination(10L)).thenReturn(null);
            case "reply" -> comment.setParentCommentId(5L);
        }
        assertThatThrownBy(()->service.review(30L,99L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        service.review(30L,99L,"REJECT","갤러리 활용 제외");
        verify(images).review(30L,"REJECTED",99L,"갤러리 활용 제외");verify(images,never()).deleteById(anyLong());verifyNoInteractions(files);
    }
    @Test void revocationDoesNotAcquireNewApprovalConsentQualification() {
        givenAdmin();image.setGalleryStatus("APPROVED");comment.setDeleted(true);comment.setModerated(true);
        when(images.lockUser(7L)).thenReturn(null);when(images.lockDestination(10L)).thenReturn(null);
        var consent=mock(com.tripbora.service.policy.GalleryConsentService.class);service.setGalleryConsentService(consent);
        service.review(30L,99L,"REVOKE","갤러리 승인 취소");
        verify(images).review(30L,"REVOKED",99L,"갤러리 승인 취소");verifyNoInteractions(consent,files);
    }
    @Test void rejectionChecksLockedPendingStateAndReasonLengthBeforeWriting() {
        givenAdmin();image.setGalleryStatus("PENDING");
        assertThatThrownBy(()->service.review(30L,99L,"REJECT","x".repeat(501))).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        image.setGalleryStatus("APPROVED");
        assertThatThrownBy(()->service.review(30L,99L,"REJECT","반려")).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        verify(images,never()).review(anyLong(),anyString(),anyLong(),any());
    }
    @Test void replyPhotosCannotBeApprovedOrKeptForTheRootReviewGallery() {
        givenAdmin();comment.setParentCommentId(5L);image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));image.setGalleryConsentVersion("v1");
        assertThatThrownBy(()->service.review(30L,99L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        image.setGalleryStatus("APPROVED");
        assertThatThrownBy(()->service.review(30L,99L,"KEEP",null)).isInstanceOf(ResponseStatusException.class);
        verify(images,never()).review(anyLong(),anyString(),anyLong(),any());
    }
    @Test void keepingApprovedRepresentativeChecksConsentWithoutChangingOriginalOrPriority() {
        givenAdmin();image.setGalleryStatus("APPROVED");image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));image.setGalleryConsentVersion("v1");
        service.review(30L,99L,"KEEP",null);
        verify(images,never()).review(anyLong(),anyString(),anyLong(),any());
        verify(images,never()).deleteById(anyLong());verifyNoInteractions(files);
        image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis()));
        when(images.photoRights(30L)).thenReturn(false);
        assertThatThrownBy(()->service.review(30L,99L,"KEEP",null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void aSecondReviewerCannotApproveAnAlreadyProcessedPhoto() {
        givenAdmin(); image.setGalleryStatus("PENDING"); image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis())); image.setGalleryConsentVersion("v1");
        when(images.review(30L,"APPROVED",99L,null)).thenAnswer(invocation -> { image.setGalleryStatus("APPROVED"); return 1; });
        service.review(30L,99L,"APPROVE",null);
        assertThatThrownBy(()->service.review(30L,99L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        verify(images,times(1)).review(30L,"APPROVED",99L,null);
    }
    @Test void moderationBlocksPendingPhotoApproval() {
        givenAdmin(); comment.setModerated(true);
        image.setGalleryStatus("PENDING"); image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis())); image.setGalleryConsentVersion("v1");
        assertThatThrownBy(()->service.review(30L,99L,"APPROVE",null)).isInstanceOf(ResponseStatusException.class);
        verify(images,never()).review(anyLong(),anyString(),anyLong(),any());
    }
    @Test
    void memberWithdrawalBlocksApprovalEvenIfImageSnapshotWasPending() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("v1");
        var memberConsent = mock(com.tripbora.service.policy.GalleryConsentService.class);
        service.setGalleryConsentService(memberConsent);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT))
                .when(memberConsent).requirePhotoValid(30L);
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }
    @Test
    void removingLegacySharedUrlKeepsFileWhileAnotherImageReferencesIt() {
        givenOwner();
        when(images.deleteById(30L)).thenReturn(1);
        when(images.isReferenced(image.getImageUrl())).thenReturn(true);
        service.remove(20L, 30L, 7L);
        verify(files, never()).deleteCommentFile(anyString());
    }
    @Mock DestinationCommentImageMapper images;
    @Mock DestinationCommentMapper comments;
    @Mock UserMapper users;
    @Mock FileUploadService files;
    ReviewImageService service;
    DestinationComment comment;
    DestinationCommentImage image;

    @BeforeEach
    void setUp() {
        service = new ReviewImageService(images, comments, users, files);
        comment = new DestinationComment();
        comment.setId(20L);
        comment.setUserId(7L);
        comment.setDestinationId(10L);
        comment.setDeleted(false);
        image = photo(30L, 1);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void givenOwner() {
        User owner = new User();
        owner.setId(7L);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setUserRole(UserRole.USER);
        Destination destination = new Destination();
        destination.setId(10L);
        destination.setPublicVisible(true);
        lenient().when(comments.findById(20L)).thenReturn(comment);
        lenient().when(images.lockUser(7L)).thenReturn(owner);
        lenient().when(images.lockDestination(10L)).thenReturn(destination);
        lenient().when(images.lockComment(20L)).thenReturn(comment);
        lenient().when(images.findByCommentIdForUpdate(20L)).thenReturn(List.of(image));
    }

    private void givenAdmin() {
        givenOwner();
        lenient().when(images.photoRights(30L)).thenReturn(true);
        User admin = new User();
        admin.setId(99L);
        admin.setUserRole(UserRole.ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        when(users.findById(99L)).thenReturn(admin);
        when(images.findById(30L)).thenReturn(image);
    }

    private com.tripbora.service.policy.GalleryConsentService givenUploadLicense() {
        var terms = mock(com.tripbora.service.policy.GalleryConsentService.class);
        var license = new com.tripbora.service.policy.GalleryConsentService.UploadLicenseGrant(700L,
                java.time.LocalDateTime.parse("2026-11-06T10:00:00.123456"));
        service.setGalleryConsentService(terms);
        lenient().when(terms.requireUploadLicense(eq(7L), nullable(Long.class))).thenReturn(license);
        lenient().doAnswer(call -> {
            DestinationCommentImage photo = call.getArgument(0);
            photo.setGalleryStatus("PENDING");
            photo.setGalleryLicensePolicyId(license.policyVersionId());
            photo.setGalleryLicenseAcceptedAt(license.acceptedAt());
            return null;
        }).when(terms).applyToNewImage(any(DestinationCommentImage.class), eq(license));
        return terms;
    }

    private DestinationCommentImage photo(long id, int order) {
        DestinationCommentImage photo = new DestinationCommentImage();
        photo.setId(id);
        photo.setCommentId(20L);
        photo.setDisplayOrder(order);
        photo.setImageUrl("/uploads/comments/00000000-0000-4000-8000-000000000030.jpg");
        return photo;
    }

    @Test
    void anotherMembersImageCannotBeDeleted() {
        givenOwner();
        User stranger = new User();
        stranger.setId(8L);
        stranger.setStatus(UserStatus.ACTIVE);
        lenient().when(images.lockUser(8L)).thenReturn(stranger);
        assertThatThrownBy(() -> service.remove(20L, 30L, 8L))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verifyNoInteractions(files);
    }

    @Test
    void anotherMembersImageConsentCannotBeChanged() {
        givenOwner();
        assertThatThrownBy(() -> service.consent(20L, 30L, 8L, true, true))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void imageMustBelongToRequestedComment() {
        givenOwner();
        assertThatThrownBy(() -> service.remove(20L, 31L, 7L))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
    }

    @Test
    void sixthPhotoIsRejectedBeforeSavingAFile() {
        givenOwner();
        when(images.findByCommentIdForUpdate(20L))
                .thenReturn(List.of(image, photo(31L, 2), photo(32L, 3), photo(33L, 4), photo(34L, 5)));
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> service.add(20L, 7L, List.of(upload)))
                .isInstanceOf(RuntimeException.class);
        verify(files, never()).saveFile(any(), anyString());
        verify(images, never()).insert(any());
    }

    @Test
    void fifthPhotoCanBeAddedToFourExistingAttachments() {
        givenOwner();
        var terms = givenUploadLicense();
        when(images.findByCommentIdForUpdate(20L))
                .thenReturn(List.of(image, photo(31L, 2), photo(32L, 3), photo(33L, 4)));
        var upload = new MockMultipartFile("images", "fifth.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(upload))
                .thenReturn(new FileUploadService.ValidatedImage("jpg", "image/jpeg", 640, 480));
        when(files.saveFile(upload, "comments")).thenReturn("/uploads/comments/fifth.jpg");
        when(images.insert(any(DestinationCommentImage.class))).thenReturn(1);
        service.add(20L, 7L, List.of(upload), 700L);
        verify(terms).requireUploadLicense(7L, 700L);
        verify(images).insert(argThat(photo -> photo.getDisplayOrder() == 5
                && "PENDING".equals(photo.getGalleryStatus()) && Long.valueOf(700L).equals(photo.getGalleryLicensePolicyId())
                && photo.getGalleryLicenseAcceptedAt() != null && photo.getGalleryConsentId() == null
                && photo.getGalleryConsentAt() == null && photo.getGalleryConsentVersion() == null));
    }

    @Test
    void galleryConsentRequiresRightsConfirmation() {
        givenOwner();
        assertThatThrownBy(() -> service.consent(20L, 30L, 7L, true, false))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void deletedCommentCannotRequestGalleryUse() {
        givenOwner();
        comment.setDeleted(true);
        assertThatThrownBy(() -> service.consent(20L, 30L, 7L, true, true))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void individualRemovalDeletesFileOnlyAfterCommit() {
        givenOwner();
        when(images.deleteById(30L)).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        service.remove(20L, 30L, 7L);
        verify(images).deleteById(30L);
        verify(files, never()).deleteCommentFile(anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(files).deleteCommentFile(image.getImageUrl());
    }

    @Test
    void removalRollbackPreservesOriginalFile() {
        givenOwner();
        when(images.deleteById(30L)).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        service.remove(20L, 30L, 7L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(files, never()).deleteCommentFile(anyString());
    }

    @Test
    void approvalWithoutConsentIsRejected() {
        givenAdmin();
        image.setGalleryStatus("NONE");
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void approvalAfterConsentWithdrawalIsRejected() {
        givenAdmin();
        image.setGalleryStatus("REVOKED");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("2026-10-30");
        image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis()));
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void validPendingPhotoCanBeApproved() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("2026-10-30");
        when(images.review(eq(30L), eq("APPROVED"), eq(99L), any())).thenReturn(1);
        service.review(30L, 99L, "APPROVE", null);
        verify(images).review(eq(30L), eq("APPROVED"), eq(99L), any());
    }

    @Test
    void rejectionRequiresReason() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("review-gallery-20261030-v1");
        assertThatThrownBy(() -> service.review(30L, 99L, "REJECT", " "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void hiddenOriginalCommentCannotBeApproved() {
        givenAdmin();
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("2026-10-30");
        comment.setDeleted(true);
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void approvalUsesLockedImageStateInsteadOfEarlierLookup() {
        givenAdmin();
        DestinationCommentImage earlier = photo(30L, 1);
        earlier.setGalleryStatus("PENDING");
        earlier.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        earlier.setGalleryConsentVersion("review-gallery-20261030-v1");
        when(images.findById(30L)).thenReturn(earlier);
        image.setGalleryStatus("REVOKED");
        image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis()));
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images).findByCommentIdForUpdate(20L);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }

    @Test
    void newlyUploadedFileIsRemovedWhenTransactionRollsBackAfterServiceReturns() {
        givenOwner();
        givenUploadLicense();
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(upload))
                .thenReturn(new FileUploadService.ValidatedImage("jpg", "image/jpeg", 640, 480));
        when(files.saveFile(upload, "comments")).thenReturn("/uploads/comments/new.jpg");
        when(images.insert(any(DestinationCommentImage.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        service.add(20L, 7L, List.of(upload));
        verify(files, never()).deleteCommentFile(anyString());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(files).deleteCommentFile("/uploads/comments/new.jpg");
    }

    @Test
    void databaseInsertFailureRemovesAlreadyStoredFile() {
        givenOwner();
        givenUploadLicense();
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(upload))
                .thenReturn(new FileUploadService.ValidatedImage("jpg", "image/jpeg", 640, 480));
        when(files.saveFile(upload, "comments")).thenReturn("/uploads/comments/new.jpg");
        when(images.insert(any(DestinationCommentImage.class))).thenReturn(0);
        assertThatThrownBy(() -> service.add(20L, 7L, List.of(upload)))
                .isInstanceOf(IllegalStateException.class);
        verify(files).deleteCommentFile("/uploads/comments/new.jpg");
    }

    @Test
    void overlyLargePixelDimensionsAreRejectedBeforeFileStorage() {
        givenOwner();
        givenUploadLicense();
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(upload))
                .thenReturn(new FileUploadService.ValidatedImage("jpg", "image/jpeg", 10000, 5000));
        assertThatThrownBy(() -> service.add(20L, 7L, List.of(upload)))
                .isInstanceOf(ResponseStatusException.class);
        verify(files, never()).saveFile(any(), anyString());
    }

    @Test void staleTermsForAdditionalPhotosAreRejectedBeforeStorageOrInsert() {
        givenOwner();
        var terms = givenUploadLicense();
        when(terms.requireUploadLicense(7L, 699L)).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT));
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> service.add(20L, 7L, List.of(upload), 699L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(files);
        verify(images, never()).insert(any());
    }

    @Test void missingTermsServiceCannotSilentlyStoreUnlicensedPhotos() {
        givenOwner();
        var upload = new MockMultipartFile("images", "photo.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> service.add(20L, 7L, List.of(upload), 700L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        verifyNoInteractions(files);
        verify(images, never()).insert(any());
    }

    @Test
    void firstConsentRequestsNewReviewWithServerVersion() {
        givenOwner();
        when(images.updateConsent(eq(30L), eq(true), anyString())).thenReturn(1);
        service.consent(20L, 30L, 7L, true, true);
        verify(images).updateConsent(30L, true, ReviewImageService.CONSENT_VERSION);
    }

    @Test
    void consentingAgainAfterWithdrawalRequiresNewReview() {
        givenOwner();
        image.setGalleryStatus("REVOKED");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis() - 10000));
        image.setGalleryConsentWithdrawnAt(new Timestamp(System.currentTimeMillis()));
        when(images.updateConsent(eq(30L), eq(true), anyString())).thenReturn(1);
        service.consent(20L, 30L, 7L, true, true);
        verify(images).updateConsent(30L, true, ReviewImageService.CONSENT_VERSION);
    }

    @Test
    void validExistingConsentDoesNotResetAnApproval() {
        givenOwner();
        image.setGalleryStatus("APPROVED");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("review-gallery-20261030-v1");
        service.consent(20L, 30L, 7L, true, true);
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
    }

    @Test
    void hiddenDestinationCannotBeApproved() {
        givenAdmin();
        Destination hidden = new Destination();
        hidden.setId(10L);
        hidden.setPublicVisible(false);
        when(images.lockDestination(10L)).thenReturn(hidden);
        image.setGalleryStatus("PENDING");
        image.setGalleryConsentAt(new Timestamp(System.currentTimeMillis()));
        image.setGalleryConsentVersion("review-gallery-20261030-v1");
        assertThatThrownBy(() -> service.review(30L, 99L, "APPROVE", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
    }
}
