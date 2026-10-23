package com.tripbora.repository.recommend;

import com.tripbora.dto.SeasonDestinationDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DestinationRecommendMapper {

    /**
     * 메인 계절 큐레이션 탭: 카테고리 이름(categories.name, UNIQUE)이 연결된 여행지 N개.
     * 대표 카테고리만이 아니라 연결된 카테고리 전체를 보고, 여행지 season 은 조건에 넣지 않는다.
     * @param categoryName 테마 카테고리 기본(한국어) 이름 (예: "단풍 명소")
     */
    List<SeasonDestinationDto> findByCurationCategoryName(@Param("categoryName") String categoryName,
                                                          @Param("limit") int limit);

    /**
     * 특정 시즌 전체(카테고리 무관) 추천 여행지 리스트 (limit N개)
     */
    List<SeasonDestinationDto> findBySeason(@Param("season") String season,
                                            @Param("limit") int limit);

    /**
     * 카테고리 이름(categories.name, UNIQUE)이 등록된 여행지 중 대표 사진이 있는 곳 N개.
     * 메인에서 무작위 선정한 ID의 카드 정보를 일괄 조회한다. 공개·이미지 조건도 다시 확인한다.
     */
    List<SeasonDestinationDto> findByCategoryName(@Param("categoryName") String categoryName,
                                                  @Param("limit") int limit,
                                                  @Param("destinationIds") List<Long> destinationIds);

    // 무작위 정렬 없이 적격 후보 ID와 국내 여부만 읽는다.
    List<SeasonDestinationDto> findHomeLandmarkCandidates(@Param("categoryName") String categoryName);

    /**
     * 메인 '지금 뜨는 여행지': destination_view_daily 의 기간 합계 상위 N곳 (국내 + 해외 전체).
     * 합계 내림차순, 같으면 여행지 id 오름차순이다. (요청마다 바뀌지 않는다)
     * fromDate/toDate 는 애플리케이션이 Asia/Seoul 기준으로 정해 넘긴다. 양 끝 날짜를 포함한다.
     */
    List<SeasonDestinationDto> findTrendingByViewDate(@Param("fromDate") LocalDate fromDate,
                                                      @Param("toDate") LocalDate toDate,
                                                      @Param("limit") int limit);

    /**
     * 메인 계절 인기 탭: 여행지 season 이 같거나 categoryNames 중 하나라도 연결된 곳 중
     * destination_view_daily 기간 합계 상위 N곳. (OR 조건, 카테고리가 여러 개여도 여행지는 한 번만 나온다)
     * 기간 조회 기록이 없는 여행지도 0으로 보고 후보에 넣는다. 합계 내림차순, 같으면 여행지 id 오름차순이다.
     * fromDate/toDate 는 애플리케이션이 Asia/Seoul 기준으로 정해 넘긴다. 양 끝 날짜를 포함한다.
     * @param categoryNames 계절 카테고리 기본(한국어) 이름. 비어 있으면 season 만 본다.
     */
    List<SeasonDestinationDto> findSeasonPopularByViewDate(@Param("season") String season,
                                                           @Param("categoryNames") List<String> categoryNames,
                                                           @Param("fromDate") LocalDate fromDate,
                                                           @Param("toDate") LocalDate toDate,
                                                           @Param("limit") int limit);

    /**
     * 카테고리 PK로 카테고리명 조회 (뱃지 이름 등)
     */
    String findCategoryNameById(@Param("categoryId") Long categoryId);
}
