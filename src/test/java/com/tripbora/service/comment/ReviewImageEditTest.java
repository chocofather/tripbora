package com.tripbora.service.comment;

import com.tripbora.model.*;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 본문과 사진 변경을 하나의 저장 명령으로 처리할 때의 권한·정합성·파일 경계. */
@ExtendWith(MockitoExtension.class)
class ReviewImageEditTest {
    @Test void omittedPhotoParametersAllowTextOnlyEdit() {
        service.edit(20L, 7L, "본문 수정", null, null);
        verify(comments).updateContent(argThat(c -> "본문 수정".equals(c.getContent())));
        verify(images, never()).deleteById(anyLong());
    }
    @Mock DestinationCommentImageMapper images;
    @Mock DestinationCommentMapper comments;
    @Mock UserMapper users;
    @Mock FileUploadService files;
    @Mock com.tripbora.service.policy.GalleryConsentService uploadTerms;
    ReviewImageService service;
    DestinationComment comment;
    List<DestinationCommentImage> stored;

    @BeforeEach void setup() {
        service = new ReviewImageService(images, comments, users, files);
        service.setGalleryConsentService(uploadTerms);
        var license = new com.tripbora.service.policy.GalleryConsentService.UploadLicenseGrant(700L,
                java.time.LocalDateTime.parse("2026-11-06T10:00:00.123456"));
        lenient().when(uploadTerms.requireUploadLicense(eq(7L), nullable(Long.class))).thenReturn(license);
        lenient().doAnswer(call -> {
            DestinationCommentImage photo = call.getArgument(0);
            photo.setGalleryStatus("PENDING");
            photo.setGalleryLicensePolicyId(license.policyVersionId());
            photo.setGalleryLicenseAcceptedAt(license.acceptedAt());
            return null;
        }).when(uploadTerms).applyToNewImage(any(DestinationCommentImage.class), eq(license));
        comment = new DestinationComment();
        comment.setId(20L);
        comment.setUserId(7L);
        comment.setDestinationId(10L);
        comment.setDeleted(false);
        comment.setContent("원래 내용");
        stored = new ArrayList<>(List.of(photo(30L, 1)));
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
        lenient().when(images.findByCommentIdForUpdate(20L)).thenAnswer(call -> List.copyOf(stored));
        lenient().when(images.deleteById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            return stored.removeIf(photo -> photo.getId() == id) ? 1 : 0;
        });
    }

    @AfterEach void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    private DestinationCommentImage photo(long id, int slot) {
        DestinationCommentImage photo = new DestinationCommentImage();
        photo.setId(id);
        photo.setCommentId(20L);
        photo.setDisplayOrder(slot);
        photo.setImageUrl("/uploads/comments/00000000-0000-4000-8000-0000000000" + id + ".jpg");
        return photo;
    }

    private MockMultipartFile upload() {
        MockMultipartFile upload = new MockMultipartFile("images", "new.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(upload))
                .thenReturn(new FileUploadService.ValidatedImage("jpg", "image/jpeg", 640, 480));
        when(files.saveFile(upload, "comments")).thenReturn("/uploads/comments/new.jpg");
        when(images.insert(any(DestinationCommentImage.class))).thenReturn(1);
        return upload;
    }

    @Test void otherMemberCannotEditTextOrPhotos() {
        assertThatThrownBy(() -> service.edit(20L, 8L, "변경", List.of(30L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
        verifyNoInteractions(files);
    }

    @Test void foreignImageIdIsRejectedBeforeAnyMutation() {
        assertThatThrownBy(() -> service.edit(20L, 7L, "변경", List.of(999L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
        verifyNoInteractions(files);
    }

    @Test void duplicateRemovalIdsAreRejectedBeforeMutation() {
        assertThatThrownBy(() -> service.edit(20L, 7L, "변경", List.of(30L, 30L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
    }

    @Test void finalPhotoCountIsCheckedBeforeDeletingAnything() {
        stored.add(photo(31L, 2));
        stored.add(photo(32L, 3));
        stored.add(photo(33L, 4));
        stored.add(photo(34L, 5));
        var incoming = new MockMultipartFile("images", "new.jpg", "image/jpeg", new byte[]{1});
        assertThatThrownBy(() -> service.edit(20L, 7L, "변경", List.of(), List.of(incoming)))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
        verifyNoInteractions(files);
    }

    @Test void replacementAtFivePhotoLimitUsesFreedFifthSlot() {
        stored.add(photo(31L, 2));
        stored.add(photo(32L, 3));
        stored.add(photo(33L, 4));
        stored.add(photo(34L, 5));
        var incoming = upload();
        service.edit(20L, 7L, "사진과 본문 변경", List.of(34L), List.of(incoming), null, false, 700L);
        verify(comments).updateContent(argThat(c -> c.getContent().equals("사진과 본문 변경")));
        verify(images).deleteById(34L);
        verify(images).insert(argThat(photo -> photo.getDisplayOrder() == 5 && photo.getCommentId() == 20L
                && "PENDING".equals(photo.getGalleryStatus()) && Long.valueOf(700L).equals(photo.getGalleryLicensePolicyId())
                && photo.getGalleryLicenseAcceptedAt() != null && photo.getGalleryConsentId() == null
                && photo.getGalleryConsentAt() == null && photo.getGalleryConsentVersion() == null));
    }

    @Test void fourExistingPhotosAndOneNewPhotoCanBeSavedTogether() {
        stored.add(photo(31L, 2));
        stored.add(photo(32L, 3));
        stored.add(photo(33L, 4));
        service.edit(20L, 7L, "다섯 번째 사진", List.of(), List.of(upload()));
        verify(images).insert(argThat(photo -> photo.getDisplayOrder() == 5));
        verify(images, never()).deleteById(anyLong());
    }

    @Test void textOnlyEditKeepsAllPhotosUntouched() {
        service.edit(20L, 7L, "본문만 변경", List.of(), List.of());
        verify(comments).updateContent(argThat(c -> c.getContent().equals("본문만 변경")));
        verify(images, never()).deleteById(anyLong());
        verify(images, never()).insert(any());
        verifyNoInteractions(files);
        verifyNoInteractions(uploadTerms);
    }

    @Test void staleUploadTermsCannotDeleteExistingPhotoOrChangeText() {
        var incoming = new MockMultipartFile("images", "new.jpg", "image/jpeg", new byte[]{1});
        when(uploadTerms.requireUploadLicense(7L, 699L)).thenThrow(new ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.edit(20L, 7L, "사진 교체", List.of(30L), List.of(incoming), null, false, 699L))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        verify(images, never()).deleteById(anyLong());
        verify(images, never()).insert(any());
        verify(comments, never()).updateContent(any());
        verifyNoInteractions(files);
    }

    @Test void blankContentCannotDeletePhotos() {
        assertThatThrownBy(() -> service.edit(20L, 7L, "   ", List.of(30L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
    }

    @Test void overlongContentCannotDeletePhotos() {
        assertThatThrownBy(() -> service.edit(20L, 7L, "가".repeat(2001), List.of(30L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
    }

    @Test void hiddenCommentCannotBeEdited() {
        comment.setDeleted(true);
        assertThatThrownBy(() -> service.edit(20L, 7L, "변경", List.of(30L), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
    }

    @Test void invalidNewPhotoDoesNotRemoveExistingPhotoOrChangeText() {
        var incoming = new MockMultipartFile("images", "fake.jpg", "image/jpeg", new byte[]{1});
        when(files.validatedGeneralImageDetails(incoming))
                .thenThrow(new com.tripbora.service.file.UnsupportedImageFormatException("지원하지 않는 사진"));
        assertThatThrownBy(() -> service.edit(20L, 7L, "변경", List.of(30L), List.of(incoming)))
                .isInstanceOf(com.tripbora.service.file.UnsupportedImageFormatException.class);
        verify(images, never()).deleteById(anyLong());
        verify(comments, never()).updateContent(any());
        verify(files, never()).saveFile(any(), anyString());
    }

    @Test void failureSavingTextCleansNewUploadButNeverDeletesOldFileBeforeCommit() {
        var incoming = upload();
        String oldUrl = stored.get(0).getImageUrl();
        doThrow(new IllegalStateException("DB 실패")).when(comments).updateContent(any());
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> service.edit(20L, 7L, "교체", List.of(30L), List.of(incoming)))
                .isInstanceOf(IllegalStateException.class);
        verify(files).deleteCommentFile("/uploads/comments/new.jpg");
        verify(files, never()).deleteCommentFile(oldUrl);
    }

    @Test void replacementRollbackCleansNewFileAndPreservesOldFile() {
        var incoming = upload();
        String oldUrl = stored.get(0).getImageUrl();
        TransactionSynchronizationManager.initSynchronization();
        service.edit(20L, 7L, "교체", List.of(30L), List.of(incoming));
        verify(files, never()).deleteCommentFile(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(files).deleteCommentFile("/uploads/comments/new.jpg");
        verify(files, never()).deleteCommentFile(oldUrl);
    }

    @Test void deletedOldFileIsRemovedOnlyAfterCommit() {
        String oldUrl = stored.get(0).getImageUrl();
        TransactionSynchronizationManager.initSynchronization();
        service.edit(20L, 7L, "사진 삭제", List.of(30L), List.of());
        verify(files, never()).deleteCommentFile(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(files).deleteCommentFile(oldUrl);
    }
}
