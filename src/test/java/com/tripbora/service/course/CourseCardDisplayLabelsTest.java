package com.tripbora.service.course;

import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogRegionDto;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCardDisplayLabelsTest {
    @Test
    void regionSummaryPreservesVisitOrderDeduplicatesIdsAndLimitsToTwoRegions() {
        assertThat(CourseCardDisplayLabels.regionSummary(paths(
                path(1L, "KR", 10L, 41L, "서울", null), path(2L, "KR", 10L, 41L, "서울", 411L),
                path(3L, "KR", 10L, 72L, "제주", 721L), path(4L, "KR", 10L, 83L, "부산", null),
                path(5L, "KR", 10L, 94L, "서울", null))))
                .isEqualTo("서울 · 제주 +2");
    }

    @Test
    void foreignPathsChooseCityBelowCountryRegardlessOfLeafDepthOrContinentAncestor() {
        assertThat(CourseCardDisplayLabels.regionSummary(paths(
                path(1L, "JP", 100L, 105L, "도쿄", 107L),
                path(2L, "FR", 200L, 210L, "파리", null)))).isEqualTo("도쿄 · 파리");
    }

    @Test
    void regionAndLeafCodesMayBeMissingWhenCountryIdentityAndParentIdsAreKnown() {
        var domestic = path(1L, "KR", 10L, 41L, "서울", 411L);
        domestic.get(0).setRegionCode(null);
        domestic.get(1).setRegionCode(" ");
        var foreign = path(2L, "JP", 100L, 105L, "도쿄", null);
        foreign.get(0).setRegionCode(null);
        assertThat(CourseCardDisplayLabels.regionSummary(paths(domestic, foreign))).isEqualTo("서울 · 도쿄");
    }

    @Test
    void ambiguousCountryMetadataDoesNotGuessWhichRegionToShow() {
        var path = path(1L, "JP", 100L, 105L, "도쿄", null);
        path.get(2).setCountryNode(true);
        assertThat(CourseCardDisplayLabels.regionSummary(path)).isEmpty();
    }

    @Test
    void countryFallbackAppearsOnlyWhenNoStructuredRegionIsAvailable() {
        var card = new CourseCardDto();
        card.setCountryNames(List.of("대한민국", "일본"));
        assertThat(card.getRegionSummary()).isEqualTo("대한민국 · 일본");
        card.setRegionSummary(CourseCardDisplayLabels.regionSummary(paths(
                path(1L, "KR", 10L, null, "주소에서 추측한 지역", null),
                path(2L, "KR", 10L, 12L, " ", null))));
        assertThat(card.getRegionSummary()).isEqualTo("대한민국 · 일본");
        card.setRegionSummary(CourseCardDisplayLabels.regionSummary(path(1L, "JP", 20L, 35L, "도쿄", null)));
        assertThat(card.getRegionSummary()).isEqualTo("도쿄");
    }

    @Test
    void durationUsesMessageSourceForDayTripAndNightsAndHandlesMissingDays() {
        var messages = new StaticMessageSource();
        messages.addMessage("course.list.duration.dayTrip", Locale.KOREAN, "당일치기");
        messages.addMessage("course.list.duration.nightsDays", Locale.KOREAN, "{0}박{1}일");
        messages.addMessage("course.list.duration.dayTrip", Locale.ENGLISH, "Day trip");
        messages.addMessage("course.list.duration.nightsDays", Locale.ENGLISH, "{0} nights, {1} days");
        assertThat(CourseCardDisplayLabels.durationLabel(1, messages, Locale.KOREAN)).isEqualTo("당일치기");
        assertThat(CourseCardDisplayLabels.durationLabel(2, messages, Locale.KOREAN)).isEqualTo("1박2일");
        assertThat(CourseCardDisplayLabels.durationLabel(3, messages, Locale.KOREAN)).isEqualTo("2박3일");
        assertThat(CourseCardDisplayLabels.durationLabel(4, messages, Locale.KOREAN)).isEqualTo("3박4일");
        assertThat(CourseCardDisplayLabels.durationLabel(3, messages, Locale.ENGLISH)).isEqualTo("2 nights, 3 days");
        assertThat(CourseCardDisplayLabels.durationLabel(null, messages, Locale.KOREAN)).isEmpty();
        assertThat(CourseCardDisplayLabels.durationLabel(0, messages, Locale.KOREAN)).isEmpty();
        assertThat(CourseCardDisplayLabels.durationLabel(-1, messages, Locale.KOREAN)).isEmpty();
    }

    @SafeVarargs
    private final List<CourseCatalogRegionDto> paths(List<CourseCatalogRegionDto>... paths) {
        return Stream.of(paths).flatMap(List::stream).toList();
    }

    private List<CourseCatalogRegionDto> path(Long stopId, String countryCode, Long countryId,
                                            Long regionId, String regionName, Long leafId) {
        var country = node(stopId, countryId, 999L, countryCode, countryCode, "국가");
        var region = node(stopId, regionId, countryId, countryCode + "-REGION", countryCode, regionName);
        var continent = node(stopId, 999L, null, "CONTINENT", countryCode, "대륙");
        return leafId == null ? List.of(region, country, continent) : List.of(
                node(stopId, leafId, regionId, countryCode + "-REGION-LEAF", countryCode, "하위 시군구"),
                region, country, continent);
    }

    private CourseCatalogRegionDto node(Long stopId, Long id, Long parentId, String code,
                                       String countryCode, String name) {
        var node = new CourseCatalogRegionDto();
        node.setStopId(stopId); node.setRegionId(id); node.setParentId(parentId);
        node.setRegionCode(code); node.setCountryNode(code.equals(countryCode)); node.setRegionName(name);
        return node;
    }
}
