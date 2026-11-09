package com.tripbora.repository.comment;

import com.tripbora.dto.CommentDto;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.translation.DestinationCommentTranslationSource;
import com.tripbora.model.translation.DestinationCommentLanguageBackfillRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface DestinationCommentMapper {
    com.tripbora.dto.DestinationReviewStatistics findReviewStatistics(@Param("destinationId") Long destinationId);
    Long findBlockingReviewId(@Param("userId") Long userId,
                              @Param("destinationId") Long destinationId,
                              @Param("excludeId") Long excludeId);

    int countVisibleReviews(@Param("destinationId") Long destinationId);

    // 댓글 등록
    void insert(DestinationComment comment);

    //특정 여행지의 댓글 목록 조회 (삭제되지 않은 것만)
    List<DestinationComment> findByDestinationId(@Param("destinationId") Long destinationId);

    // 댓글 하나 조회
    DestinationComment findById(@Param("id") Long id);

    DestinationCommentTranslationSource findVisibleTranslationSource(@Param("id") Long id);

    DestinationCommentTranslationSource findVisibleTranslationSourceForUpdate(@Param("id") Long id);

    int correctSourceLanguage(@Param("id") Long id,
                              @Param("sourceLanguage") String sourceLanguage,
                              @Param("updatedAt") java.sql.Timestamp updatedAt);

    List<DestinationCommentLanguageBackfillRow> findUndeterminedLanguagesAfter(
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    int updateDetectedLanguageIfUndetermined(
            @Param("id") Long id,
            @Param("sourceLanguage") String sourceLanguage,
            @Param("content") String content,
            @Param("updatedAt") java.sql.Timestamp updatedAt);

    // 삭제 플래그 업데이트
    void updateDeleted(DestinationComment comment);

    // 댓글 내용 수정 메서드 선언
    void updateContent(DestinationComment comment);

    // 댓글 수 카운트
    int countByDestinationId(@Param("destinationId") Long destinationId);

    // 페이징 대상 원댓글 수 카운트
    int countRootComments(@Param("destinationId") Long destinationId);

    Long findActiveRootIdForLocation(@Param("destinationId") Long destinationId,
                                     @Param("commentId") Long commentId);

    int countRootCommentsBefore(@Param("destinationId") Long destinationId,
                                @Param("rootId") Long rootId);

    // 좋아요 여부 체크
    boolean existsLikeByUserAndComment(@Param("userId") Long userId, @Param("commentId") Long commentId);

    // 기본 정렬 (오래된 순)
    List<DestinationComment> findByDestinationIdWithWriter(@Param("destinationId") Long destinationId);

    // 최신순 정렬
    List<DestinationComment> findByDestinationIdOrderByCreatedAtDesc(@Param("destinationId") Long destinationId);

    // 좋아요순 정렬
    List<DestinationComment> findByDestinationIdOrderByLikesDesc(@Param("destinationId") Long destinationId);

    List<CommentDto> findPagedComments(
            @Param("destinationId") Long destinationId,
            @Param("sort") String sort,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int countComments(@Param("destinationId") Long destinationId);

    List<DestinationComment> findPagedByCreatedAtAsc(
            @Param("destinationId") Long destinationId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    List<DestinationComment> findPagedByCreatedAtDesc(
            @Param("destinationId") Long destinationId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    List<DestinationComment> findPagedByLikesDesc(
            @Param("destinationId") Long destinationId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );


    // 부모 댓글만 페이지네이션 조회
    List<DestinationComment> findPagedParentComments(
            @Param("destinationId") Long destinationId,
            @Param("offset") int offset,
            @Param("limit") int limit,
            @Param("sort") String sort
    );

    // 특정 부모 댓글들의 대댓글 모두 조회
    List<DestinationComment> findRepliesForParents(
            @Param("destinationId") Long destinationId,
            @Param("parentIds") List<Long> parentIds
    );

    // 여러 여행지용 댓글 수 카운트
    List<Map<String, Object>> countByDestinationIds(@Param("destinationIds") List<Long> destinationIds);


}
