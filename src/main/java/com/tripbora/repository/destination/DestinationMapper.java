    package com.tripbora.repository.destination;

    import com.tripbora.dto.AdminDestinationDataStatusCounts;
    import com.tripbora.dto.AdminDestinationListItemDto;
    import com.tripbora.dto.CategoryDestinationCount;
    import com.tripbora.model.Destination;
    import com.tripbora.model.DestinationCategory;
    import com.tripbora.model.DestinationDuplicateIndexRow;
    import com.tripbora.model.DestinationImage;
    import com.tripbora.model.DestinationImageCommonsSource;
    import com.tripbora.model.DestinationTranslation;
    import org.apache.ibatis.annotations.Mapper;
    import org.apache.ibatis.annotations.Param;

    import java.util.Collection;
    import java.util.List;

    @Mapper
    public interface DestinationMapper {
        void insertDestination(Destination destination);

        /** 외부 출처(TourAPI) 여행지가 이미 있는지 본다. 저장 직전 2차 중복 검사에 쓴다. */
        int countByExternalContentId(@Param("sourceType") String sourceType,
                                     @Param("externalContentId") String externalContentId);
        Long findIdByExternalContentId(@Param("sourceType") String sourceType,
                                       @Param("externalContentId") String externalContentId);
        DestinationTranslation findTranslationByDestinationAndLanguage(
                @Param("destinationId") Long destinationId,
                @Param("languageCode") String languageCode);

        /** 외부 후보와 기존 여행지의 중복 판별 색인. 여행지별 언어별 이름 한 줄씩(이름이 없으면 한 줄). */
        List<DestinationDuplicateIndexRow> findDuplicateIndex();

        void insertTranslation(DestinationTranslation translation);

        void insertCategory(DestinationCategory category);

        void insertImage(DestinationImage image);

        void insertImageSource(DestinationImage image);

        /** Commons 사진 출처 전체 행. 이미지 INSERT 와 같은 트랜잭션에서 생성된 이미지 ID로 저장한다. */
        void insertCommonsImageSource(DestinationImageCommonsSource source);

        /** 같은 여행지에 같은 Commons 파일이 이미 연결됐는지 본다. */
        int countCommonsImageSource(@Param("destinationId") Long destinationId,
                                    @Param("commonsFileTitle") String commonsFileTitle);

        /** 같은 여행지에 같은 Pixabay 사진 ID가 이미 연결됐는지 본다. */
        int countPixabayImageSource(@Param("destinationId") Long destinationId,
                                    @Param("pixabayImageId") String pixabayImageId);

        /** 여행지의 외부 식별자. 등록 출처(source_type)가 다르면 null. */
        String findExternalContentIdBySourceType(@Param("destinationId") Long destinationId,
                                                 @Param("sourceType") String sourceType);

        /**
         * 여행지 행을 트랜잭션 끝까지 잠근다. 같은 여행지에 사진을 동시에 추가하는 요청을 차례로 처리해,
         * 중복 검사와 INSERT 사이에 다른 요청이 끼어들지 않게 한다. 없는 여행지면 null.
         */
        Long lockDestinationForImageUpdate(@Param("destinationId") Long destinationId);

        void updateBulkImageSource(@Param("image") DestinationImage image,
                                   @Param("fields") java.util.Set<String> fields);

        void updateBulkImageLegacy(@Param("image") DestinationImage image,
                                   @Param("fields") java.util.Set<String> fields);

        void upsertImageSourcePages(@Param("imageId") Long imageId,
                                    @Param("commonSourceUrl") String commonSourceUrl,
                                    @Param("workPageUrl") String workPageUrl);

        void upsertImageSourceMetadata(@Param("imageId") Long imageId,
                                       @Param("sourceName") String sourceName,
                                       @Param("photographer") String photographer,
                                       @Param("licenseType") String licenseType,
                                       @Param("licenseDetail") String licenseDetail,
                                       @Param("sourceUrl") String sourceUrl);

        Destination findById(Long id);

        int updateVisibility(@Param("id") Long id, @Param("publicVisible") boolean publicVisible);

        List<Destination> findDomestic(); // 국내만

        void insertDestinationCategory(@Param("destinationId") Long destinationId,
                                       @Param("categoryId") Long categoryId);

        // 상세페이지
        Destination findDestinationDetail(Long id);

        // 이미지 url 리스트 조회 (nested select 용)
        List<DestinationImage> findImagesByDestinationId(Long destinationId);

        // 주어진 이미지 주소 중 표시 기준 라이선스가 licenseType 인 것 (출처 행이 있으면 그 값)
        List<String> findImageUrlsByLicenseType(@Param("imageUrls") Collection<String> imageUrls,
                                                @Param("licenseType") String licenseType);

        List<Destination> findAllWithRegion();

        DestinationImage findImageById(Long imageId);
        void deleteImageById(Long imageId);
        void clearMainImagesByDestinationId(Long destinationId);
        void setMainImage(Long imageId);
        void updateImageSlide(@Param("imageId") Long imageId,
                              @Param("isSlide") boolean isSlide);
        void updateImageOrder(@Param("imageId") Long imageId,
                              @Param("orderIndex") int orderIndex);
        void updateImageMetadata(@Param("imageId") Long imageId,
                                 @Param("sourceName") String sourceName,
                                 @Param("photographer") String photographer,
                                 @Param("licenseType") String licenseType,
                                 @Param("licenseDetail") String licenseDetail,
                                 @Param("sourceUrl") String sourceUrl);

        List<Destination> findByCountryCategoryId(@Param("cityId") Long cityId);

        /**
         * 관리자 여행지 목록 한 쪽. sort 는 latest / oldest / name, dataStatus 는 null 또는
         * missing_image / missing_main_image / missing_category / missing_translation / missing_description 이다.
         */
        List<AdminDestinationListItemDto> findAdminDestinationPage(@Param("regionIds") List<Long> regionIds,
                                                                   @Param("destinationType") String destinationType,
                                                                   @Param("dataStatus") String dataStatus,
                                                                   @Param("keyword") String keyword,
                                                                   @Param("chosungPattern") String chosungPattern,
                                                                   @Param("sort") String sort,
                                                                   @Param("offset") long offset,
                                                                   @Param("size") int size,
                                                                   @Param("publicVisible") Boolean publicVisible);

        int countAdminDestinations(@Param("regionIds") List<Long> regionIds,
                                   @Param("destinationType") String destinationType,
                                   @Param("dataStatus") String dataStatus,
                                   @Param("keyword") String keyword,
                                   @Param("chosungPattern") String chosungPattern,
                                   @Param("publicVisible") Boolean publicVisible);

        /** 데이터 상태 필터를 뺀 지금 목록 조건 안의 상태별 건수(집계 한 번). */
        AdminDestinationDataStatusCounts countAdminDestinationDataStatus(@Param("regionIds") List<Long> regionIds,
                                                                         @Param("destinationType") String destinationType,
                                                                         @Param("keyword") String keyword,
                                                                         @Param("chosungPattern") String chosungPattern,
                                                                         @Param("publicVisible") Boolean publicVisible);

        // 조회수 증가
        void incrementViewCount(Long id);

        List<DestinationTranslation> findTranslationsByDestinationId(Long destinationId);

        List<DestinationTranslation> findTranslationsByDestinationIds(
                @Param("destinationIds") Collection<Long> destinationIds);


        void deleteById(Long id); // destinations

        void deleteTranslationsByDestinationId(Long destinationId); // destination_translations

        /** 언어 한 줄만 지운다. 그 언어 입력을 모두 비웠을 때 쓴다. */
        void deleteTranslation(@Param("destinationId") Long destinationId,
                               @Param("languageCode") String languageCode);

        void deleteImagesByDestinationId(Long destinationId); // destination_images

        void deleteCommentsByDestinationId(Long destinationId); // destination_comments

        void deleteDestinationCategoriesByDestinationId(Long destinationId);

        // 댓글 좋아요 관련
        void incrementCommentLikeCount(@Param("commentId") Long commentId);

        void decrementCommentLikeCount(@Param("commentId") Long commentId);

        // 수정 관련
        void updateDestination(Destination destination); // 여행지 기본 정보 수정

        void updateTranslation(DestinationTranslation translation); // 번역 수정(필요시)

        List<Long> findCategoryIdsByDestinationId(Long destinationId); // 카테고리 ID 리스트

        void deleteDestinationCategory(@Param("destinationId") Long destinationId, @Param("categoryId") Long categoryId); // 카테고리 해제

        /** 대표 카테고리 ID. 대표가 없으면 null (여행지당 최대 1개는 UNIQUE 가 보장한다). */
        Long findMainCategoryId(Long destinationId);

        /** 대표 표시만 지운다. 카테고리 연결은 그대로 둔다. */
        void clearMainCategory(Long destinationId);

        /** 이미 연결된 카테고리 하나를 대표로 표시한다. 연결이 없으면 0 을 돌려준다. */
        int markMainCategory(@Param("destinationId") Long destinationId, @Param("categoryId") Long categoryId);


        // 추천 여행지 (같은 지역, 비슷한 카테고리, 자기자신 제외, 랜덤 5개)
        List<Destination> findSimilarDestinations(
                @Param("regionId") Long regionId,
                @Param("categoryIds") List<Long> categoryIds,
                @Param("exceptId") Long exceptId,
                @Param("limit") int limit
        );

        // 페이징. categoryIds 가 있으면 그중 하나라도 등록된 여행지만(모든 카테고리 연결 기준, 중복 없음).
        List<Destination> findByRegionIdsPaged(@Param("regionIds") List<Long> regionIds,
                                               @Param("categoryIds") List<Long> categoryIds,
                                               @Param("offset") int offset,
                                               @Param("size") int size,
                                               @Param("sort") String sort);
        int countByRegionIds(@Param("regionIds") List<Long> regionIds,
                             @Param("categoryIds") List<Long> categoryIds);

        /**
         * 목록 카테고리 필터의 선택지와 여행지 수: 이 지역 범위 여행지에 등록된 카테고리 +
         * 고른 카테고리 중 실제로 있는 것. 없는 카테고리 번호는 나오지 않는다.
         */
        List<CategoryDestinationCount> findCategoryFilterCounts(
                @Param("regionIds") List<Long> regionIds,
                @Param("selectedCategoryIds") List<Long> selectedCategoryIds);

        List<Destination> findDomesticPaged(@Param("offset") int offset,
                                            @Param("size") int size,
                                            @Param("sort") String sort);
        int countDomestic();


        // 특정 루트 지역(대륙/국가/도시 등) 아래의 모든 하위 지역 id 목록 반환
        List<Long> findAllRegionIdsUnder(@Param("rootRegionId") Long rootRegionId);

    }
