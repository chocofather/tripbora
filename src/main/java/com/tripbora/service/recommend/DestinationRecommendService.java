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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /*
      계절 인기 탭에서 season 외에 후보로 넣는 카테고리 (categories.name, 기본 한국어 이름).
      화면 탭 라벨이 아니라 실제 카테고리 이름이다. (예: '가을 꽃 여행' 탭 → '가을 꽃 명소')
      여기 없는 계절은 season 만 본다.
    */
    static final Map<String, List<String>> SEASON_POPULAR_CATEGORY_NAMES = Map.of(
            "FALL", List.of("가을", "단풍 명소", "가을 꽃 명소", "가을 풍경 여행"));

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

    // 후보 ID만 섞은 뒤 최대 18곳의 카드·번역을 각각 일괄 조회한다.
    public List<SeasonDestinationDto> findHomeLandmarks(SupportedLanguage requestedLanguage) {
        List<SeasonDestinationDto> candidates = recommendMapper.findHomeLandmarkCandidates(LANDMARK_CATEGORY_NAME);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, SeasonDestinationDto> unique = new LinkedHashMap<>();
        candidates.forEach(candidate -> unique.putIfAbsent(candidate.getId(), candidate));
        List<Long> domestic = new ArrayList<>();
        List<Long> overseas = new ArrayList<>();
        unique.values().forEach(candidate ->
                (candidate.isDomestic() ? domestic : overseas).add(candidate.getId()));
        Collections.shuffle(domestic);
        Collections.shuffle(overseas);
        // 변경금지 사진도 후보에 남겨 두고, 아치를 채울 수 있는 대표 사진을 우선한다.
        Comparator<Long> imagePriority = Comparator.comparing(id -> unique.get(id).isImageNoDerivatives());
        domestic.sort(imagePriority);
        overseas.sort(imagePriority);
        List<Long> selected = new ArrayList<>();
        int half = HOME_LANDMARK_LIMIT / 2;
        selected.addAll(domestic.subList(0, Math.min(half, domestic.size())));
        selected.addAll(overseas.subList(0, Math.min(half, overseas.size())));
        List<Long> remaining = new ArrayList<>(domestic.subList(Math.min(half, domestic.size()), domestic.size()));
        remaining.addAll(overseas.subList(Math.min(half, overseas.size()), overseas.size()));
        Collections.shuffle(remaining);
        remaining.sort(imagePriority);
        selected.addAll(remaining.subList(0, Math.min(HOME_LANDMARK_LIMIT - selected.size(), remaining.size())));
        // 첫 카드도 고정되지 않도록 국내·해외를 합친 뒤 표시 순서를 다시 섞는다.
        Collections.shuffle(selected);
        List<SeasonDestinationDto> cards = new ArrayList<>(recommendMapper.findByCategoryName(
                LANDMARK_CATEGORY_NAME, HOME_LANDMARK_LIMIT, selected));
        cards.sort(Comparator.comparingInt(card -> selected.indexOf(card.getId())));
        return localize(cards, requestedLanguage);
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

    /**
     * 메인 계절 섹션 '가을 인기 여행지' 같은 계절 인기 탭: 여행지 season 이 같거나
     * 그 계절 카테고리(SEASON_POPULAR_CATEGORY_NAMES) 중 하나라도 연결된 곳을 고른다.
     * 기간은 '지금 뜨는 여행지'와 같이 today-6일 ~ today (양 끝 포함)이고, 조회 기록이 없는 곳은 0으로 본다.
     */
    public List<SeasonDestinationDto> findSeasonPopularDestinations(
            String season, LocalDate today, int limit, SupportedLanguage requestedLanguage) {
        LocalDate fromDate = today.minusDays(TRENDING_DAYS - 1);
        List<String> categoryNames = season == null
                ? List.of()
                : SEASON_POPULAR_CATEGORY_NAMES.getOrDefault(season, List.of());
        return localize(recommendMapper.findSeasonPopularByViewDate(
                        season, categoryNames, fromDate, today, limit),
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
