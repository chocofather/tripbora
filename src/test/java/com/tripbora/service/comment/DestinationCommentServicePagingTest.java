package com.tripbora.service.comment;

import com.tripbora.dto.CommentDto;
import com.tripbora.dto.PageResult;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.User;
import com.tripbora.repository.comment.DestinationCommentImageMapper;
import com.tripbora.repository.comment.DestinationCommentMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.user.TestWithdrawnMemberName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationCommentServicePagingTest {
    @Test
    void detailReviewsOnlyNeverLoadRepliesAndKeepRootPagination() {
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(3);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest"))
                .thenReturn(List.of(comment(1L, null, false)));
        var result = service.getCommentsPaged(10L, null, 0, 5, "latest", true);
        assertThat(result.getContent()).extracting(CommentDto::getId).containsExactly(1L);
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(commentMapper, never()).findRepliesForParents(anyLong(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test void statisticsStaySeparateFromThreadPaginationAndRefreshAfterHideAndRestore() {
        var visible = new com.tripbora.dto.DestinationReviewStatistics();
        visible.setReviewCount(4); visible.setRatingCount(3);
        visible.setAverageRating(new java.math.BigDecimal("4.3333"));
        visible.setRating3Count(1); visible.setRating5Count(2);
        var hidden = new com.tripbora.dto.DestinationReviewStatistics();
        hidden.setReviewCount(3); hidden.setRatingCount(2);
        hidden.setAverageRating(new java.math.BigDecimal("4.0000")); hidden.setRating3Count(1); hidden.setRating5Count(1);
        when(commentMapper.findReviewStatistics(10L)).thenReturn(visible, hidden, visible);
        var first = service.getCommentsPaged(10L, null, 0, 5, "latest");
        var second = service.getCommentsPaged(10L, null, 0, 5, "latest");
        var third = service.getCommentsPaged(10L, null, 0, 5, "latest");
        assertThat(first.getReviewCount()).isEqualTo(4);
        assertThat(first.getReviewStatistics().getRatingCount()).isEqualTo(3);
        assertThat(first.getReviewStatistics().getAverageRating()).isEqualByComparingTo("4.3333");
        assertThat(second.getReviewCount()).isEqualTo(3);
        assertThat(second.getReviewStatistics().getDistribution()).containsExactly(0, 0, 1, 0, 1);
        assertThat(third.getReviewStatistics()).isEqualTo(visible);
    }

    @Test void noRatingsKeepsNullAverageAndFiveZeroBuckets() {
        var page = service.getCommentsPaged(10L, null, 0, 5, "latest");
        assertThat(page.getReviewStatistics().getAverageRating()).isNull();
        assertThat(page.getReviewStatistics().getDistribution()).containsExactly(0, 0, 0, 0, 0);
    }

    @Test void everySortIsWhitelistedAndForwardedWithStablePaging() {
        when(commentMapper.countRootComments(10L)).thenReturn(7);
        for (String sort : List.of("latest", "oldest", "likes", "rating_desc", "rating_asc")) {
            service.getCommentsPaged(10L, null, 1, 5, sort);
            verify(commentMapper).findPagedParentComments(10L, 5, 5, sort);
        }
        service.getCommentsPaged(10L, null, 1, 5, "rating DESC;bad");
        verify(commentMapper, org.mockito.Mockito.times(2)).findPagedParentComments(10L, 5, 5, "latest");
    }

    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private DestinationCommentMapper commentMapper;
    @Mock
    private DestinationCommentImageMapper commentImageMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private FileUploadService fileUploadService;

    private DestinationCommentService service;

    @BeforeEach
    void setUp() {
        service = new DestinationCommentService(
                destinationMapper, commentMapper, commentImageMapper, userMapper, fileUploadService,
                new LocalContentLanguageDetector(), TestWithdrawnMemberName.real());
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void outOfRangePageKeepsThreadAndActiveCommentTotals() {
        when(commentMapper.countRootComments(10L)).thenReturn(6);
        when(commentMapper.countByDestinationId(10L)).thenReturn(12);

        PageResult<CommentDto> result = service.getCommentsPaged(10L, null, 2, 5, "latest");

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(6);
        assertThat(result.getTotalCommentCount()).isEqualTo(12);
        verify(commentMapper, never()).findPagedParentComments(anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void unexpectedEmptyContentPageDoesNotOverwriteKnownTotals() {
        when(commentMapper.countRootComments(10L)).thenReturn(6);
        when(commentMapper.countByDestinationId(10L)).thenReturn(9);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest")).thenReturn(List.of());

        PageResult<CommentDto> result = service.getCommentsPaged(10L, null, 0, 5, "unknown");

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(6);
        assertThat(result.getTotalCommentCount()).isEqualTo(9);
        verify(commentMapper).findPagedParentComments(10L, 0, 5, "latest");
    }

    @Test
    void moderatedParentAndReplyKeepTheirFlagOnThePagedResponse() {
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(1);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest"))
                .thenReturn(List.of(comment(1L, null, true)));
        when(commentMapper.findRepliesForParents(10L, List.of(1L)))
                .thenReturn(List.of(comment(2L, 1L, true), comment(3L, 1L, false)));

        PageResult<CommentDto> result = service.getCommentsPaged(10L, null, 0, 5, "latest");

        // 부모/대댓글 모두 플레이스홀더 렌더링에 필요한 moderated 가 유지된다
        assertThat(result.getContent())
                .extracting(CommentDto::getId, CommentDto::isModerated)
                .containsExactly(
                        tuple(1L, true),
                        tuple(2L, true),
                        tuple(3L, false));
    }

    @Test
    void rootAndReplyLocationsUseTheVisibleRootGroup() {
        when(commentMapper.findActiveRootIdForLocation(10L, 30L)).thenReturn(30L);
        when(commentMapper.countRootCommentsBefore(10L, 30L)).thenReturn(0);
        when(commentMapper.findActiveRootIdForLocation(10L, 36L)).thenReturn(31L);
        when(commentMapper.countRootCommentsBefore(10L, 31L)).thenReturn(6);

        assertThat(service.getCommentLocation(10L, 30L))
                .hasValueSatisfying(location -> assertThat(location.getPage()).isEqualTo(1));
        assertThat(service.getCommentLocation(10L, 36L))
                .hasValueSatisfying(location -> assertThat(location.getPage()).isEqualTo(2));
        verify(commentMapper).countRootCommentsBefore(10L, 31L);
    }

    @Test
    void deletedTargetWrongDestinationOrReplyBelowDeletedRootHasNoLocation() {
        when(commentMapper.findActiveRootIdForLocation(10L, 36L)).thenReturn(null);

        assertThat(service.getCommentLocation(10L, 36L)).isEmpty();
        verify(commentMapper, never()).countRootCommentsBefore(anyLong(), anyLong());
    }

    @Test
    void commentDtoOffersTranslationOnlyForKnownDifferentSourceLanguage() {
        DestinationComment korean = comment(1L, null, false);
        korean.setSourceLanguage("ko");
        when(commentMapper.countRootComments(10L)).thenReturn(1);
        when(commentMapper.countByDestinationId(10L)).thenReturn(1);
        when(commentMapper.findPagedParentComments(10L, 0, 5, "latest"))
                .thenReturn(List.of(korean));
        LocaleContextHolder.setLocale(Locale.KOREAN);

        CommentDto sameLanguage = service.getCommentsPaged(10L, null, 0, 5, "latest")
                .getContent().get(0);
        assertThat(sameLanguage.isTranslationAvailable()).isFalse();

        korean.setContent("정말 좋은 식당입니다. The food was very delicious.");
        assertThat(service.getCommentsPaged(10L,null,0,5,"latest").getContent().get(0).isTranslationAvailable()).isTrue();
        korean.setContent("내용 1");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        CommentDto differentLanguage = service.getCommentsPaged(10L, null, 0, 5, "latest")
                .getContent().get(0);
        assertThat(differentLanguage.isTranslationAvailable()).isTrue();

        korean.setSourceLanguage("und");
        CommentDto unknownLanguage = service.getCommentsPaged(10L, null, 0, 5, "latest")
                .getContent().get(0);
        assertThat(unknownLanguage.isTranslationAvailable()).isFalse();
    }

    /** 관리자 조치 댓글은 deleted = 1 이면서 moderated = true 로 내려온다. */
    private DestinationComment comment(long id, Long parentId, boolean moderated) {
        DestinationComment comment = new DestinationComment();
        comment.setId(id);
        comment.setParentCommentId(parentId);
        comment.setContent(moderated ? null : "내용 " + id);
        comment.setLikes(0);
        comment.setDeleted(moderated);
        comment.setModerated(moderated);
        comment.setUserId(7L);
        comment.setDestinationId(10L);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);

        User writer = new User();
        writer.setId(7L);
        writer.setNickname(moderated ? null : "여행자");
        comment.setWriter(writer);
        return comment;
    }
}
