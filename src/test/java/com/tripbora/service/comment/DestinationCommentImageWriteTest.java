package com.tripbora.service.comment;

import com.tripbora.model.DestinationComment;
import com.tripbora.model.DestinationCommentImage;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** STEP C: 신규 댓글 사진은 destination_comment_images 에만 저장된다. */
@ExtendWith(MockitoExtension.class)
class DestinationCommentImageWriteTest {
    @Test
    void newCommentPhotosReceiveUploadLicenseSnapshotBeforeInsert() {
        givenCommentIsInserted(50L);
        givenWriterExists();
        User active = new User();
        active.setId(7L);
        active.setStatus(com.tripbora.model.UserStatus.ACTIVE);
        when(commentImageMapper.lockUser(7L)).thenReturn(active);
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(1);
        service.create(10L, 7L, "사진 후기", List.of(image("photo.jpg")), null, 4, 700L);
        verify(uploadTerms).requireUploadLicense(7L, 700L);
        verify(commentImageMapper).insert(org.mockito.ArgumentMatchers.argThat(photo ->
                "PENDING".equals(photo.getGalleryStatus()) && Long.valueOf(700L).equals(photo.getGalleryLicensePolicyId())
                        && license.acceptedAt().equals(photo.getGalleryLicenseAcceptedAt())
                        && photo.getGalleryConsentId() == null && photo.getGalleryConsentAt() == null
                        && photo.getGalleryConsentVersion() == null));
    }

    @Test
    void originalCommentUploadIsCleanedOnLateTransactionRollback() throws Exception {
        givenCommentIsInserted(50L);
        givenWriterExists();
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(10L, 7L, "사진 후기", List.of(image("photo.jpg")), null, 4);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            try (var files = Files.list(uploadDir.resolve("comments"))) {
                assertThat(files).isEmpty();
            }
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private DestinationCommentMapper commentMapper;
    @Mock
    private DestinationCommentImageMapper commentImageMapper;
    @Mock
    private UserMapper userMapper;

    @TempDir
    Path uploadDir;

    private DestinationCommentService service;
    private com.tripbora.service.policy.GalleryConsentService uploadTerms;
    private final com.tripbora.service.policy.GalleryConsentService.UploadLicenseGrant license =
            new com.tripbora.service.policy.GalleryConsentService.UploadLicenseGrant(700L,
                    java.time.LocalDateTime.parse("2026-11-06T10:00:00.123456"));

    @BeforeEach
    void setUp() {
        service = new DestinationCommentService(
                destinationMapper, commentMapper, commentImageMapper, userMapper,
                new FileUploadService(uploadDir.toString()), new LocalContentLanguageDetector(),
                TestWithdrawnMemberName.real());
        ReflectionTestUtils.setField(service, "uploadPath", uploadDir.toString());
        uploadTerms = org.mockito.Mockito.mock(com.tripbora.service.policy.GalleryConsentService.class);
        service.setGalleryConsentService(uploadTerms);
        // 파일 경계 테스트는 유효한 약관 근거를 고정한다. 버전 누락/충돌은 별도 테스트에서 거절한다.
        org.mockito.Mockito.lenient().when(uploadTerms.requireUploadLicense(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(license);
        org.mockito.Mockito.lenient().doAnswer(call -> {
            DestinationCommentImage photo = call.getArgument(0);
            photo.setGalleryStatus("PENDING");
            photo.setGalleryLicensePolicyId(license.policyVersionId());
            photo.setGalleryLicenseAcceptedAt(license.acceptedAt());
            return null;
        }).when(uploadTerms).applyToNewImage(any(DestinationCommentImage.class), org.mockito.ArgumentMatchers.eq(license));
        User active = new User(); active.setId(7L); active.setStatus(com.tripbora.model.UserStatus.ACTIVE);
        var destination = new com.tripbora.model.Destination(); destination.setId(10L); destination.setPublicVisible(true);
        org.mockito.Mockito.lenient().when(commentImageMapper.lockUser(7L)).thenReturn(active);
        org.mockito.Mockito.lenient().when(commentImageMapper.lockDestination(10L)).thenReturn(destination);
        org.mockito.Mockito.lenient().when(commentMapper.findBlockingReviewId(any(), any(), any())).thenReturn(null);
    }

    @Test
    void imagesAreStoredInOrderInTheirOwnTable() {
        givenCommentIsInserted(50L);
        givenWriterExists();
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(1);

        service.create(10L, 7L, "사진 후기",
                List.of(image("a.jpg"), image("b.jpg"), image("c.jpg")), null, 4);

        ArgumentCaptor<DestinationCommentImage> captor =
                ArgumentCaptor.forClass(DestinationCommentImage.class);
        verify(commentImageMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DestinationCommentImage::getCommentId)
                .containsExactly(50L, 50L, 50L);
        assertThat(captor.getAllValues())
                .extracting(DestinationCommentImage::getDisplayOrder)
                .containsExactly(1, 2, 3);
        assertThat(captor.getAllValues())
                .allSatisfy(image -> assertThat(image.getImageUrl()).startsWith("/uploads/comments/"));

        // 댓글 자체는 한 번만 저장된다 (사진은 별도 테이블)
        verify(commentMapper).insert(any(DestinationComment.class));
        ArgumentCaptor<DestinationComment> commentCaptor = ArgumentCaptor.forClass(DestinationComment.class);
        verify(commentMapper).insert(commentCaptor.capture());
        assertThat(commentCaptor.getValue().getSourceLanguage()).isEqualTo("ko");
    }

    @Test void createResponseKeepsNewPhotoIdAlongsideLegacyImageUrls() {
        givenCommentIsInserted(50L);givenWriterExists();
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenAnswer(call->{
            call.getArgument(0,DestinationCommentImage.class).setId(70L);return 1;
        });
        var result=service.create(10L,7L,"방문 사진",List.of(image("photo.jpg")),null,4);
        assertThat(result.getPhotos()).extracting(com.tripbora.dto.CommentImageDto::getId).containsExactly(70L);
        assertThat(result.getPhotos().get(0).getImageUrl()).isEqualTo(result.getImageUrls().get(0));
    }

    @Test
    void emptyFilesAreIgnoredWhenCountingAttachments() {
        givenCommentIsInserted(50L);
        givenWriterExists();

        service.create(10L, 7L, "사진 없음",
                Arrays.asList(emptyImage(), null, emptyImage()), null, 4);

        verify(commentImageMapper, never()).insert(any());
        org.mockito.Mockito.verifyNoInteractions(uploadTerms);
    }

    @Test void missingOrStaleUploadTermsRejectBeforeFileAndDatabaseWrites() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT)).when(uploadTerms)
                .requireUploadLicense(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.nullable(Long.class));
        for (Long expected : Arrays.asList(null, 699L)) {
            assertThatThrownBy(() -> service.create(10L, 7L, "사진 후기", List.of(image("photo.jpg")), null, 4, expected))
                    .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
        }
        verify(commentMapper, never()).insert(any());
        verify(commentImageMapper, never()).insert(any());
        assertThat(Files.exists(uploadDir.resolve("comments"))).isFalse();
    }

    @Test
    void moreThanFiveImagesAreRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> service.create(10L, 7L, "사진 6장",
                java.util.stream.IntStream.range(0, 6).mapToObj(i -> image(i + ".jpg")).toList(), null, 4))
                .isInstanceOf(CommentImageLimitException.class)
                .hasMessage("사진은 최대 5장까지 첨부할 수 있습니다.");

        verify(commentMapper, never()).insert(any());
        verify(commentImageMapper, never()).insert(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void oneToFivePhotosAreStoredInTheirOriginalOrder(int count) {
        givenCommentIsInserted(50L);
        givenWriterExists();
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(1);
        service.create(10L, 7L, "사진 후기",
                java.util.stream.IntStream.range(0, count).mapToObj(i -> image(i + ".jpg")).toList(), null, 4);
        var captor = ArgumentCaptor.forClass(DestinationCommentImage.class);
        verify(commentImageMapper, org.mockito.Mockito.times(count)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(DestinationCommentImage::getDisplayOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, count).boxed().toList());
    }

    @Test
    void storedFilesAreRemovedWhenImageRowInsertFails() throws Exception {
        givenCommentIsInserted(50L);
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(0);

        assertThatThrownBy(() -> service.create(10L, 7L, "실패", List.of(image("a.jpg")), null, 4))
                .isInstanceOf(IllegalStateException.class);

        // 업로드된 실제 파일이 남지 않아야 한다
        try (var files = Files.list(uploadDir.resolve("comments"))) {
            assertThat(files).isEmpty();
        }
    }

    /**
     * 여행지 댓글 사진도 공통 이미지 검증을 거친다.
     * 원본 파일명은 저장 이름에 쓰지 않고, 확장자는 판별한 형식으로 정한다.
     */
    @Test
    void storedImageNamesIgnoreTheOriginalFileName() {
        givenCommentIsInserted(50L);
        givenWriterExists();
        when(commentImageMapper.insert(any(DestinationCommentImage.class))).thenReturn(1);

        service.create(10L, 7L, "사진 후기", List.of(image("../evil name.html")), null, 4);

        ArgumentCaptor<DestinationCommentImage> captor =
                ArgumentCaptor.forClass(DestinationCommentImage.class);
        verify(commentImageMapper).insert(captor.capture());
        assertThat(captor.getValue().getImageUrl())
                .matches("^/uploads/comments/[0-9a-f-]{36}\\.jpg$");
    }

    /**
     * HTML·스크립트 SVG·사진으로 위장한 파일은 받지 않는다.
     * 앞서 받은 정상 사진까지 지우고, 댓글도 저장하지 않으며, 폴더에 아무것도 남기지 않는다.
     */
    @Test
    void nonImageFilesAreRejectedAndLeaveNothingBehind() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (MultipartFile rejected : List.of(
                new MockMultipartFile("images", "x.html", "text/html", html),
                new MockMultipartFile("images", "x.svg", "image/svg+xml", svg),
                new MockMultipartFile("images", "x.jpg", "image/jpeg", html))) {
            assertThatThrownBy(() -> service.create(10L, 7L, "위장 파일",
                    List.of(image("ok.jpg"), rejected), null, 4))
                    .isInstanceOf(com.tripbora.service.file.UnsupportedImageFormatException.class);
        }

        verify(commentMapper, never()).insert(any());
        verify(commentImageMapper, never()).insert(any());
        Path comments = uploadDir.resolve("comments");
        if (Files.exists(comments)) {
            try (var files = Files.list(comments)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void createStillRunsInOneTransaction() throws NoSuchMethodException {
        Transactional transactional = DestinationCommentService.class
                .getMethod("create", Long.class, Long.class, String.class, List.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
    }

    @Test
    void editRedetectsAndPersistsSourceLanguage() {
        DestinationComment comment = new DestinationComment();
        comment.setId(50L);
        comment.setUserId(7L);
        comment.setDeleted(false);
        comment.setDestinationId(10L);
        when(commentMapper.findById(50L)).thenReturn(comment);
        when(commentImageMapper.lockComment(50L)).thenReturn(comment);

        assertThat(service.updateComment(
                50L, 7L, "This updated comment is written clearly in English.")).isTrue();

        ArgumentCaptor<DestinationComment> captor = ArgumentCaptor.forClass(DestinationComment.class);
        verify(commentMapper).updateContent(captor.capture());
        assertThat(captor.getValue().getSourceLanguage()).isEqualTo("en");
    }

    /** insert 는 void 이므로 doAnswer 로 PK 채움만 흉내 낸다. */
    private void givenCommentIsInserted(long commentId) {
        doAnswer(invocation -> {
            invocation.getArgument(0, DestinationComment.class).setId(commentId);
            return null;
        }).when(commentMapper).insert(any(DestinationComment.class));
    }

    private void givenWriterExists() {
        User user = new User();
        user.setId(7L);
        user.setNickname("여행자");
        user.setUserRole(UserRole.USER);
        when(userMapper.findById(7L)).thenReturn(user);
    }

    /** 실제로 펼쳐지는 작은 JPEG. 댓글 사진도 공통 이미지 검증을 거치므로 진짜 사진이어야 한다. */
    private MultipartFile image(String name) {
        try {
            java.awt.image.BufferedImage drawn =
                    new java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(drawn, "jpg", bytes);
            return new MockMultipartFile("images", name, "image/jpeg", bytes.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MultipartFile emptyImage() {
        return new MockMultipartFile("images", "", "image/jpeg", new byte[0]);
    }
}
