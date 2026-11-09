package com.tripbora.repository.comment;

import com.tripbora.model.DestinationCommentImage;
import com.tripbora.model.DestinationComment;
import com.tripbora.model.Destination;
import com.tripbora.model.User;
import com.tripbora.dto.ReviewImageAdminDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 여행지 댓글 사진 저장소.
 * 댓글 첨부와 갤러리 활용 동의·심사 상태를 함께 관리한다.
 */
@Mapper
public interface DestinationCommentImageMapper {
    List<com.tripbora.dto.DestinationGalleryReviewCandidate> findGalleryCandidates(
            @Param("destinationId") Long destinationId,
            @Param("anchor") com.tripbora.dto.DestinationGalleryPosition anchor,
            @Param("reverse") boolean reverse, @Param("limit") int limit);
    boolean hasGalleryCandidates(@Param("destinationId") Long destinationId,
            @Param("anchor") com.tripbora.dto.DestinationGalleryPosition anchor,
            @Param("reverse") boolean reverse);
    List<ReviewImageAdminDto> findAdminDestinationPhotoPageFiltered(@Param("destinationId") Long destinationId,
            @Param("status") String status,@Param("sort") String sort,@Param("analysis") String analysis,
            @Param("offset") long offset,@Param("limit") int limit);
    List<Long> findAdminDestinationIds(@Param("query") String query, @Param("sort") String sort,
                                     @Param("offset") long offset, @Param("limit") int limit);
    List<com.tripbora.dto.ReviewDestinationAdminDto> findAdminDestinationStatistics(@Param("ids") List<Long> ids);
    List<com.tripbora.dto.ReviewDestinationAdminDto> findAdminDestinationMetadata(@Param("ids") List<Long> ids);
    List<ReviewImageAdminDto> findAdminDestinationPhotoPage(@Param("destinationId") Long destinationId,
            @Param("status") String status, @Param("sort") String sort,
            @Param("offset") long offset, @Param("limit") int limit);
    long countAdminDestinationSelection(@Param("destinationId") Long destinationId, @Param("ids") List<Long> ids);
    List<Long> findAdminReviewIds(@Param("status") String status, @Param("query") String query,
                                @Param("offset") long offset, @Param("limit") int limit);
    List<ReviewImageAdminDto> findAdminReviewPhotos(@Param("ids") List<Long> ids);
    List<com.tripbora.dto.ReviewPhotoStatusCount> countAdminStatuses(@Param("query") String query);
    long countPendingPhotos();
    /** 업로드 이용조건 또는 과거 선택 동의 근거와 현재 공개 조건. 심사와 승인 유지에 공통 사용한다. */
    boolean photoRights(@Param("imageId") Long imageId);
    List<com.tripbora.dto.ReviewGalleryPhoto> findApprovedGallery(@Param("destinationId") Long destinationId,
            @Param("limit") int limit, @Param("currentTime") java.time.LocalDateTime currentTime);
    List<com.tripbora.dto.ReviewGalleryPhoto> findApprovedGalleryPage(@Param("destinationId") Long destinationId,
            @Param("limit") int limit, @Param("offset") long offset);
    List<ReviewImageAdminDto> findOwnerPhotos(@Param("userId") Long userId,
                                                           @Param("offset") int offset,
                                                           @Param("limit") int limit);

    /** 사진 1건 저장. */
    int insert(DestinationCommentImage image);

    DestinationCommentImage findById(@Param("id") Long id);

    DestinationComment lockComment(@Param("commentId") Long commentId);

    User lockUser(@Param("userId") Long userId);

    Destination lockDestination(@Param("destinationId") Long destinationId);

    int updateConsent(@Param("imageId") Long imageId, @Param("consent") boolean consent,
                      @Param("version") String version);

    int review(@Param("imageId") Long imageId, @Param("status") String status,
               @Param("adminId") Long adminId, @Param("reason") String reason);

    int deleteById(@Param("imageId") Long imageId);

    List<ReviewImageAdminDto> listForAdmin(@Param("status") String status,
                                         @Param("offset") int offset, @Param("limit") int limit);

    boolean isReferenced(@Param("imageUrl") String imageUrl);

    /**
     * 여러 댓글의 사진을 한 번에 조회한다. (댓글 목록 렌더링 시 N+1 방지)
     * comment_id, display_order 오름차순으로 정렬해 돌려준다.
     */
    List<DestinationCommentImage> findByCommentIds(@Param("commentIds") List<Long> commentIds);

    /** 관리 명령의 최신 행 조회. 일반 공개 목록의 snapshot 조회와 구분한다. */
    List<DestinationCommentImage> findByCommentIdForUpdate(@Param("commentId") Long commentId);

    /**
     * 여행지 삭제 lifecycle 전용 조회.
     * 파일 정리가 목적이라 삭제된 댓글의 사진까지 전부, 개수 제한 없이 URL 만 돌려준다.
     */
    List<String> findAllImageUrlsByDestinationId(@Param("destinationId") Long destinationId);
}
