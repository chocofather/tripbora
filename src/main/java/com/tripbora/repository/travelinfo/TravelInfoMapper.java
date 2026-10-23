package com.tripbora.repository.travelinfo;

import com.tripbora.dto.AdminTravelInfoListItemDto;
import com.tripbora.dto.HomeFestivalDto;
import com.tripbora.dto.HomeFestivalCandidate;
import com.tripbora.dto.TravelInfoDetailDto;
import com.tripbora.dto.TravelInfoListItemDto;
import com.tripbora.model.InfoPeriod;
import com.tripbora.model.InfoImage;
import com.tripbora.model.TravelInfo;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.model.TravelInfoTranslation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TravelInfoMapper {

    List<HomeFestivalCandidate> findHomeFestivalCandidates(@Param("today") LocalDate today);

    List<HomeFestivalDto> findHomeFestivals(@Param("periodIds") List<Long> periodIds);

    /** Home Hero. 메인 추천으로 고른 일반 여행정보·여행가이드를 노출 순서대로 최대 limit 개. */
    List<TravelInfoListItemDto> findHomeHeroItems(@Param("limit") int limit);

    List<AdminTravelInfoListItemDto> findAdminList(
            @Param("scope") TravelInfoScope scope,
            @Param("contentType") TravelInfoContentType contentType,
            @Param("categoryId") Long categoryId);

    List<TravelInfoListItemDto> findPublicList(
            @Param("scope") TravelInfoScope scope,
            @Param("contentType") TravelInfoContentType contentType,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("keywordPattern") String keywordPattern,
            @Param("koreanPattern") String koreanPattern,
            @Param("eventStatus") String eventStatus,
            @Param("sort") String sort,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long countPublicList(
            @Param("scope") TravelInfoScope scope,
            @Param("contentType") TravelInfoContentType contentType,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("keywordPattern") String keywordPattern,
            @Param("koreanPattern") String koreanPattern,
            @Param("eventStatus") String eventStatus);

    int incrementPublicViews(@Param("id") Long id);

    TravelInfoDetailDto findPublicDetailById(@Param("id") Long id);

    Long findPublicBookmarkTargetForUpdate(@Param("id") Long id);

    TravelInfo findById(Long id);

    TravelInfo findByIdForUpdate(Long id);

    int insertTravelInfo(TravelInfo travelInfo);

    int updateTravelInfo(TravelInfo travelInfo);

    /**
     * STRUCTURED 글의 원문 블록 JSON 만 바꾼다. 작성 방식이 STRUCTURED 인 줄만 바뀌며 바뀐 줄 수를 돌려준다.
     * 제목·파생 content 는 같은 트랜잭션의 updateTravelInfo 가 맡는다.
     */
    int updateStructuredContent(@Param("id") Long id,
                                @Param("structuredContent") String structuredContent);

    /** 그 본문 이미지 url 을 아직 쓰는 STRUCTURED 글 수. 0 일 때만 파일을 지운다. */
    int countStructuredContentReferences(@Param("imageUrl") String imageUrl);

    /** 메인 추천 노출 여부와 순서만 바꾼다. 축제 화면도 쓰는 updateTravelInfo 에 섞지 않는다. */
    int updateHomeFeatured(@Param("id") Long id,
                           @Param("homeFeatured") boolean homeFeatured,
                           @Param("homeFeaturedOrder") Integer homeFeaturedOrder);

    int deleteTravelInfo(Long id);

    List<InfoPeriod> findPeriodsByInfoId(Long infoId);

    int insertPeriod(InfoPeriod period);

    int deletePeriodsByInfoId(Long infoId);

    InfoImage findMainImageByInfoId(Long infoId);

    List<InfoImage> findImagesByInfoId(Long infoId);

    List<String> findMainImageUrlsByInfoId(Long infoId);

    int insertInfoImage(InfoImage infoImage);

    int clearThumbnailsByInfoId(Long infoId);

    int setThumbnailByIdAndInfoId(@Param("imageId") Long imageId,
                                  @Param("infoId") Long infoId);

    int deleteMainImagesByInfoId(Long infoId);

    /** 상세 화면용. 여행정보 한 건의 번역을 언어 코드 순으로 읽는다. */
    List<TravelInfoTranslation> findTranslationsByInfoId(Long infoId);

    /** 목록 화면용. 여러 여행정보의 번역을 한 번에 읽어 언어 대체에서 N+1 이 생기지 않게 한다. */
    List<TravelInfoTranslation> findTranslationsByInfoIds(@Param("infoIds") List<Long> infoIds);

    /** 관리자 저장용. 언어 한 줄이 단위이며 UNIQUE(travel_info_id, language_code) 를 따른다. */
    int insertTranslation(TravelInfoTranslation translation);

    int updateTranslation(TravelInfoTranslation translation);

    int deleteTranslation(@Param("travelInfoId") Long travelInfoId,
                          @Param("languageCode") String languageCode);
}
