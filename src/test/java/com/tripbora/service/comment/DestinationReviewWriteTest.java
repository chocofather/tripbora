package com.tripbora.service.comment;

import com.tripbora.model.*;
import com.tripbora.repository.comment.*;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DestinationReviewWriteTest {
    private final DestinationCommentMapper comments = mock(DestinationCommentMapper.class);
    private final DestinationCommentImageMapper images = mock(DestinationCommentImageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final FileUploadService files = mock(FileUploadService.class);
    private DestinationCommentService service;
    private ReviewImageService photoService;
    private DestinationComment legacy;

    @BeforeEach void setup() {
        User owner = new User(); owner.setId(7L); owner.setUserRole(UserRole.USER); owner.setStatus(UserStatus.ACTIVE);
        Destination destination = new Destination(); destination.setId(10L); destination.setPublicVisible(true);
        when(images.lockUser(7L)).thenReturn(owner);
        when(images.lockDestination(10L)).thenReturn(destination);
        when(users.findById(7L)).thenReturn(owner);
        legacy = new DestinationComment(); legacy.setId(20L); legacy.setUserId(7L);
        legacy.setDestinationId(10L); legacy.setDeleted(false);
        when(comments.findById(20L)).thenReturn(legacy);
        when(images.lockComment(20L)).thenReturn(legacy);
        when(comments.findBlockingReviewId(anyLong(), anyLong(), any())).thenReturn(null);
        service = new DestinationCommentService(mock(DestinationMapper.class), comments, images, users, files,
                new LocalContentLanguageDetector(), TestWithdrawnMemberName.real());
        photoService = new ReviewImageService(images, comments, users, files);
    }

    @ParameterizedTest @ValueSource(ints = {0, 6, -1, 256})
    void rejectsOutOfRangeBeforeFiles(Integer rating) {
        assertThatThrownBy(() -> service.create(10L, 7L, "경험", List.of(), null, rating))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(files);
        verify(comments, never()).insert(any());
    }

    @Test void ratingAndTextArePersistedWithoutChangingPhotos() {
        var terms = mock(com.tripbora.service.policy.GalleryConsentService.class);
        service.setGalleryConsentService(terms);
        service.create(10L, 7L, "방문 경험", List.of(), null, 4);
        verify(comments).insert(argThat(c -> c.getRating() == 4 && c.getParentCommentId() == null));
        var order = inOrder(images, comments);
        order.verify(images).lockUser(7L);
        order.verify(images).lockDestination(10L);
        order.verify(comments).findBlockingReviewId(7L, 10L, null);
        order.verify(comments).insert(any());
        verifyNoInteractions(terms);
    }

    @Test void duplicateReviewAndLegacyConversionAreRejected() {
        when(comments.findBlockingReviewId(7L, 10L, null)).thenReturn(99L);
        when(comments.findBlockingReviewId(7L, 10L, 20L)).thenReturn(99L);
        assertThatThrownBy(() -> service.create(10L, 7L, "또 방문", List.of(), null, 3))
                .hasMessageContaining("409").hasMessageContaining("기존 리뷰");
        assertThatThrownBy(() -> service.updateComment(20L, 7L, "전환", 5, true)).hasMessageContaining("409");
        assertThatThrownBy(() -> photoService.edit(20L, 7L, "전환", null, null, 5, true)).hasMessageContaining("409");
        verify(comments, never()).insert(any()); verify(comments, never()).updateContent(any());
    }

    @Test void legacyNullSurvivesTextOnlyEditInBothPaths() {
        assertThat(service.updateComment(20L, 7L, "수정")).isTrue();
        photoService.edit(20L, 7L, "수정", null, null);
        assertThat(legacy.getRating()).isNull();
        verify(comments, times(2)).updateContent(argThat(c -> c.getRating() == null));
    }

    @Test void legacyCanConvertAndExistingRatingCanChangeInBothPaths() {
        service.updateComment(20L, 7L, "전환", 5, true);
        assertThat(legacy.getRating()).isEqualTo(5);
        photoService.edit(20L, 7L, "다시 평가", null, null, 3, true);
        assertThat(legacy.getRating()).isEqualTo(3);
        verify(images, never()).review(anyLong(), anyString(), anyLong(), any());
        verify(images, never()).updateConsent(anyLong(), anyBoolean(), anyString());
    }

    @Test void omissionPreservesRatedReviewButExplicitRemovalIsRejected() {
        legacy.setRating(4);
        service.updateComment(20L, 7L, "수정");
        photoService.edit(20L, 7L, "수정", null, null);
        assertThat(legacy.getRating()).isEqualTo(4);
        assertThatThrownBy(() -> service.updateComment(20L, 7L, "제거", null, true)).hasMessageContaining("400");
        assertThatThrownBy(() -> photoService.edit(20L, 7L, "제거", null, null, null, true)).hasMessageContaining("400");
    }

    @Test void otherUserCannotChangeRatingInEitherPath() {
        assertThat(service.updateComment(20L, 8L, "변경", 1, true)).isFalse();
        assertThatThrownBy(() -> photoService.edit(20L, 8L, "변경", null, null, 1, true)).hasMessageContaining("403");
        verify(comments, never()).updateContent(any());
    }

    @Test void replyHasNoRatingAndRequiresPublicRootOfSameDestination() {
        service.create(10L, 7L, "답글", List.of(), 20L, null);
        verify(comments).insert(argThat(c -> c.getRating() == null && c.getParentCommentId().equals(20L)));
        assertThatThrownBy(() -> service.create(10L, 7L, "답글", List.of(), 20L, 3)).hasMessageContaining("400");
        legacy.setDestinationId(11L);
        assertThatThrownBy(() -> service.create(10L, 7L, "답글", List.of(), 20L, null)).hasMessageContaining("400");
        legacy.setDestinationId(10L); legacy.setParentCommentId(19L);
        assertThatThrownBy(() -> service.create(10L, 7L, "깊은 답글", List.of(), 20L, null)).hasMessageContaining("400");
        legacy.setParentCommentId(null); legacy.setDeleted(true);
        assertThatThrownBy(() -> service.create(10L, 7L, "삭제 글 답글", List.of(), 20L, null)).hasMessageContaining("400");
        legacy.setDeleted(false); legacy.setModerated(true);
        assertThatThrownBy(() -> service.create(10L, 7L, "숨김 글 답글", List.of(), 20L, null)).hasMessageContaining("400");
    }

    @Test void replyCannotAcquireRatingViaEitherEditPath() {
        legacy.setParentCommentId(19L);
        assertThatThrownBy(() -> service.updateComment(20L, 7L, "답글", 2, true)).hasMessageContaining("400");
        assertThatThrownBy(() -> photoService.edit(20L, 7L, "답글", null, null, 2, true)).hasMessageContaining("400");
    }

    @Test void hiddenReviewCannotBeEditedUntilRestored() {
        legacy.setRating(3); legacy.setDeleted(true); legacy.setModerated(true);
        assertThat(service.updateComment(20L, 7L, "수정", 4, true)).isFalse();
        assertThatThrownBy(() -> photoService.edit(20L, 7L, "수정", null, null, 4, true)).hasMessageContaining("409");
        legacy.setDeleted(false); legacy.setModerated(false);
        assertThat(service.updateComment(20L, 7L, "복구 후 수정", 4, true)).isTrue();
    }

    @Test void blankTextIsRejectedInBothPaths() {
        assertThatThrownBy(() -> service.create(10L, 7L, " ", List.of(), null, 4)).hasMessageContaining("400");
        assertThatThrownBy(() -> service.updateComment(20L, 7L, " ", 4, true)).hasMessageContaining("400");
        assertThatThrownBy(() -> photoService.edit(20L, 7L, " ", null, null, 4, true)).hasMessageContaining("400");
    }

    @Test void simultaneousCreatesUseOneWriterLockAndOnlyOneCanSave() throws Exception {
        var writerLock = new java.util.concurrent.locks.ReentrantLock();
        var firstLocked = new java.util.concurrent.CountDownLatch(1);
        var releaseFirst = new java.util.concurrent.CountDownLatch(1);
        var stored = new java.util.concurrent.atomic.AtomicReference<Long>();
        var savedCount = new java.util.concurrent.atomic.AtomicInteger();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        User owner = users.findById(7L);
        when(images.lockUser(7L)).thenAnswer(call -> {
            attempts.incrementAndGet();
            writerLock.lock();
            if (firstLocked.getCount() == 1) {
                firstLocked.countDown();
                if (!releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("first request timed out");
            }
            return owner;
        });
        when(comments.findBlockingReviewId(7L, 10L, null)).thenAnswer(call -> stored.get());
        doAnswer(call -> { stored.set(99L); savedCount.incrementAndGet(); return null; }).when(comments).insert(any());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Integer> request = () -> {
            try {
                service.create(10L, 7L, "경험", List.of(), null, 4);
                return 200;
            } catch (ResponseStatusException failure) { return failure.getStatusCode().value(); }
            finally {
                // 이 테스트에서는 DB 트랜잭션 종료 시 행 잠금 해제를 모사한다.
                if (writerLock.isHeldByCurrentThread()) writerLock.unlock();
            }
        };
        try {
            var first = executor.submit(request);
            assertThat(firstLocked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(request);
            releaseFirst.countDown();
            assertThat(List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS), second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(attempts).hasValue(2); assertThat(savedCount).hasValue(1);
        } finally { releaseFirst.countDown(); executor.shutdownNow(); }
    }

    @Test void adminRetainsExistingHiddenDestinationWriteException() {
        var destination = new Destination(); destination.setId(10L); destination.setPublicVisible(false);
        when(images.lockDestination(10L)).thenReturn(destination);
        assertThatThrownBy(() -> service.create(10L, 7L, "경험", List.of(), null, 4)).hasMessageContaining("404");
        var admin = new User(); admin.setId(7L); admin.setStatus(UserStatus.ACTIVE); admin.setUserRole(UserRole.ADMIN);
        when(images.lockUser(7L)).thenReturn(admin);
        service.create(10L, 7L, "경험", List.of(), null, 4);
        verify(comments).insert(any());
    }

    @Test void reviewCountIsSeparateFromRepliesAndHiddenThreadSlots() {
        when(comments.countRootComments(10L)).thenReturn(3);
        when(comments.countByDestinationId(10L)).thenReturn(8);
        var statistics = new com.tripbora.dto.DestinationReviewStatistics(); statistics.setReviewCount(2);
        when(comments.findReviewStatistics(10L)).thenReturn(statistics);
        var page = service.getCommentsPaged(10L, null, 100, 5, "latest");
        assertThat(page.getReviewCount()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalCommentCount()).isEqualTo(8);
    }

    private org.springframework.test.web.servlet.MockMvc api() {
        var owner = new User(); owner.setId(7L); owner.setUserRole(UserRole.USER);
        var principal = new com.tripbora.security.CustomUserDetails(owner);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.tripbora.controller.destination.DestinationCommentController(mock(CommentLikeService.class), service,
                        mock(com.tripbora.service.translation.ContentTranslationService.class)),
                new com.tripbora.controller.comment.CommentEditController(photoService))
                .setControllerAdvice(new com.tripbora.controller.comment.ReviewWriteExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.getParameterType() == com.tripbora.security.CustomUserDetails.class;
                    }
                    public Object resolveArgument(org.springframework.core.MethodParameter p,
                            org.springframework.web.method.support.ModelAndViewContainer m,
                            org.springframework.web.context.request.NativeWebRequest r,
                            org.springframework.web.bind.support.WebDataBinderFactory b) { return principal; }
                }).build();
    }

    @Test void bothHttpEditPathsSupportConversionAndRejectClearingOrMalformedRating() throws Exception {
        var mvc = api();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/comments/20")
                .contentType("application/json").content("{\"content\":\"과거 글 전환\",\"rating\":5}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(legacy.getRating()).isEqualTo(5);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, "/comments/20/with-images")
                .param("content", "평가 수정").param("rating", "2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertThat(legacy.getRating()).isEqualTo(2);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/comments/20")
                .contentType("application/json").content("{\"content\":\"수정\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(legacy.getRating()).isEqualTo(2);
        for (String value : List.of("null", "0", "6", "2.5", "\"bad\"")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/comments/20")
                    .contentType("application/json").content("{\"content\":\"수정\",\"rating\":" + value + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        for (String value : List.of("", "0", "6", "2.5", "bad")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, "/comments/20/with-images")
                    .param("content", "수정").param("rating", value))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
    }
    @Test void newRootRequiresRatingBeforeSaving() {
        var comments = mock(DestinationCommentMapper.class);
        var users = mock(UserMapper.class);
        User owner = new User(); owner.setId(7L); owner.setUserRole(UserRole.USER);
        when(users.findById(7L)).thenReturn(owner);
        var service = new DestinationCommentService(mock(DestinationMapper.class), comments,
                mock(DestinationCommentImageMapper.class), users, mock(FileUploadService.class),
                new LocalContentLanguageDetector(), TestWithdrawnMemberName.real());
        assertThatThrownBy(() -> service.create(10L, 7L, "방문 경험", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verify(comments, never()).insert(any());
    }
}
