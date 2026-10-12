package com.tripbora.service.recommend;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.model.DestinationTranslation;
import com.tripbora.repository.recommend.DestinationRecommendMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class DestinationRecommendService {

    /*
      메인 랜드마크 카드가 고르는 카테고리. categories.name 은 UNIQUE 이고 기본(한국어) 이름이라
      번호 대신 이 이름으로 찾는다. 관리자 화면에서 이름을 바꾸면 여기도 같이 바꿔야 한다.
    */
    static final String LANDMARK_CATEGORY_NAME = "랜드마크";
    // 첫 화면에 여섯 장이 보이고, 나머지는 레일을 넘겨 본다.
    static final int HOME_LANDMARK_LIMIT = 18;
    // 지금 뜨는 여행지 기간: 오늘 포함 7일
    static final int TRENDING_DAYS = 7;

    private final DestinationRecommendMapper recommendMapper;
    private final DestinationService destinationService;
    private final ReferenceNameLocalizationService referenceNameLocalizationService;

    // 계절 큐레이션 탭: 테마 카테고리가 연결된 여행지 N개 (여행지 season 은 보지 않는다)
    public List<SeasonDestinationDto> findByCurationCategoryName(
            String categoryName, int limit, SupportedLanguage requestedLanguage) {
        return localize(recommendMapper.findByCurationCategoryName(categoryName, limit),
                requestedLanguage);
    }

    // 시즌별 여행지 N개 (카테고리 조건 없음)
    public List<SeasonDestinationDto> findBySeason(
            String season, int limit, SupportedLanguage requestedLanguage) {
        return localize(recommendMapper.findBySeason(season, limit), requestedLanguage);
    }

    // 메인 랜드마크 카드 (최대 6곳). 이름·지역 번역은 계절 추천과 같이 한 번에 읽는다.
    public List<SeasonDestinationDto> findHomeLandmarks(SupportedLanguage requestedLanguage) {
        return localize(recommendMapper.findByCategoryName(LANDMARK_CATEGORY_NAME, HOME_LANDMARK_LIMIT),
                requestedLanguage);
    }

    /**
     * 메인 '지금 뜨는 여행지': 오늘(KST) 포함 최근 7일 일별 조회 합계 상위 limit 곳.
     * today 는 호출한 쪽이 Asia/Seoul 기준으로 넘긴다. 기간은 today-6일 ~ today (양 끝 포함)이다.
     * 이름·지역(상위 지역 포함) 번역은 계절 추천·랜드마크와 같이 한 번에 읽는다.
     */
    public List<SeasonDestinationDto> findTrendingDestinations(
            LocalDate today, int limit, SupportedLanguage requestedLanguage) {
        LocalDate fromDate = today.minusDays(TRENDING_DAYS - 1);
        return localize(recommendMapper.findTrendingByViewDate(fromDate, today, limit),
                requestedLanguage);
    }

    // 카테고리 이름 조회
    public String getCategoryName(Long categoryId) {
        return recommendMapper.findCategoryNameById(categoryId);
    }

    private List<SeasonDestinationDto> localize(
            List<SeasonDestinationDto> destinations,
            SupportedLanguage requestedLanguage) {
        List<SeasonDestinationDto> available = destinations == null ? List.of() : destinations;
        List<Long> destinationIds = available.stream()
                .filter(Objects::nonNull)
                .map(SeasonDestinationDto::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, DestinationTranslation> localizedContent =
                destinationService.resolveLocalizedContentByDestinationIds(
                        destinationIds, requestedLanguage);
        Map<Long, String> baseRegionNames = new LinkedHashMap<>();
        List<Long> categoryIds = available.stream()
                .filter(Objects::nonNull)
                .map(SeasonDestinationDto::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        for (SeasonDestinationDto destination : available) {
            if (destination != null && destination.getRegionId() != null) {
                baseRegionNames.putIfAbsent(
                        destination.getRegionId(), destination.getRegionName());
            }
            // 상위 지역도 같은 country_categories 이름이라 한 번에 번역을 읽는다.
            if (destination != null && destination.getParentRegionId() != null) {
                baseRegionNames.putIfAbsent(
                        destination.getParentRegionId(), destination.getParentRegionName());
            }
        }
        Map<Long, String> localizedRegionNames =
                referenceNameLocalizationService.localizeCountryCategoryNames(
                        baseRegionNames, requestedLanguage);
        Map<Long, String> localizedCategoryNames =
                referenceNameLocalizationService.localizeCategories(
                        categoryIds, requestedLanguage);

        for (SeasonDestinationDto destination : available) {
            if (destination == null) {
                continue;
            }
            DestinationTranslation content = localizedContent.get(destination.getId());
            if (content != null && content.getName() != null && !content.getName().isBlank()) {
                destination.setName(content.getName());
            }
            if (destination.getRegionId() != null) {
                destination.setRegionName(localizedRegionNames.getOrDefault(
                        destination.getRegionId(), destination.getRegionName()));
            }
            if (destination.getParentRegionId() != null) {
                destination.setParentRegionName(localizedRegionNames.getOrDefault(
                        destination.getParentRegionId(), destination.getParentRegionName()));
            }
            if (destination.getCategoryId() != null) {
                destination.setCategoryName(localizedCategoryNames.getOrDefault(
                        destination.getCategoryId(), destination.getCategoryName()));
            }
        }
        return available;
    }
}
