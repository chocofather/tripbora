package com.tripbora.service.recommend;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.model.DestinationTranslation;
import com.tripbora.repository.recommend.DestinationRecommendMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationRecommendServiceLocalizationTest {

    @Mock private DestinationRecommendMapper recommendMapper;
    @Mock private DestinationService destinationService;
    @Mock private ReferenceNameLocalizationService referenceNameLocalizationService;

    @Test
    void localizesSeasonDestinationNameRegionAndExistingCategoryInBatches() {
        SeasonDestinationDto palace = new SeasonDestinationDto();
        palace.setId(15L);
        palace.setName("경복궁");
        palace.setRegionId(235L);
        palace.setRegionName("종로구");
        palace.setCategoryId(7L);
        palace.setCategoryName("랜드마크");
        palace.setSeason("SPRING");
        palace.setImageUrl("/palace.jpg");
        when(recommendMapper.findByCurationCategoryName("랜드마크", 5))
                .thenReturn(List.of(palace));
        when(destinationService.resolveLocalizedContentByDestinationIds(
                List.of(15L), SupportedLanguage.JAPANESE))
                .thenReturn(Map.of(15L, translation(15L, "景福宮")));
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                Map.of(235L, "종로구"), SupportedLanguage.JAPANESE))
                .thenReturn(Map.of(235L, "鐘路区"));
        when(referenceNameLocalizationService.localizeCategories(
                List.of(7L), SupportedLanguage.JAPANESE))
                .thenReturn(Map.of(7L, "ランドマーク"));

        DestinationRecommendService service = new DestinationRecommendService(
                recommendMapper, destinationService, referenceNameLocalizationService);

        SeasonDestinationDto result = service.findByCurationCategoryName(
                "랜드마크", 5, SupportedLanguage.JAPANESE).get(0);

        assertThat(result.getName()).isEqualTo("景福宮");
        assertThat(result.getRegionName()).isEqualTo("鐘路区");
        assertThat(result.getCategoryName()).isEqualTo("ランドマーク");
        assertThat(result.getSeason()).isEqualTo("SPRING");
        assertThat(result.getImageUrl()).isEqualTo("/palace.jpg");
        verify(destinationService).resolveLocalizedContentByDestinationIds(
                List.of(15L), SupportedLanguage.JAPANESE);
        verify(referenceNameLocalizationService).localizeCountryCategoryNames(
                Map.of(235L, "종로구"), SupportedLanguage.JAPANESE);
        verify(referenceNameLocalizationService).localizeCategories(
                List.of(7L), SupportedLanguage.JAPANESE);
    }

    @Test
    void localizesParentRegionInTheSameBatchAsTheRegion() {
        SeasonDestinationDto gongju = new SeasonDestinationDto();
        gongju.setId(21L);
        gongju.setName("공산성");
        gongju.setRegionId(371L);
        gongju.setRegionName("공주시");
        gongju.setParentRegionId(49L);
        gongju.setParentRegionName("충남");
        SeasonDestinationDto tokyo = new SeasonDestinationDto();
        tokyo.setId(22L);
        tokyo.setName("아사쿠사");
        tokyo.setRegionId(92L);
        tokyo.setRegionName("도쿄");
        tokyo.setParentRegionId(8L);
        tokyo.setParentRegionName("일본");
        when(recommendMapper.findBySeason("FALL", 5)).thenReturn(List.of(gongju, tokyo));
        when(destinationService.resolveLocalizedContentByDestinationIds(
                List.of(21L, 22L), SupportedLanguage.ENGLISH)).thenReturn(Map.of());
        Map<Long, String> baseRegionNames = new java.util.LinkedHashMap<>();
        baseRegionNames.put(371L, "공주시");
        baseRegionNames.put(49L, "충남");
        baseRegionNames.put(92L, "도쿄");
        baseRegionNames.put(8L, "일본");
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                baseRegionNames, SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(371L, "Gongju-si", 49L, "Chungnam", 92L, "Tokyo", 8L, "Japan"));
        when(referenceNameLocalizationService.localizeCategories(
                List.of(), SupportedLanguage.ENGLISH)).thenReturn(Map.of());

        DestinationRecommendService service = new DestinationRecommendService(
                recommendMapper, destinationService, referenceNameLocalizationService);

        List<SeasonDestinationDto> result = service.findBySeason("FALL", 5, SupportedLanguage.ENGLISH);

        assertThat(result.get(0).getParentRegionName()).isEqualTo("Chungnam");
        assertThat(result.get(0).getRegionName()).isEqualTo("Gongju-si");
        assertThat(result.get(1).getParentRegionName()).isEqualTo("Japan");
        assertThat(result.get(1).getRegionName()).isEqualTo("Tokyo");
        // 상위 지역을 따로 조회하지 않고 지역과 같은 한 번의 호출로 번역한다.
        verify(referenceNameLocalizationService).localizeCountryCategoryNames(
                baseRegionNames, SupportedLanguage.ENGLISH);
    }

    @Test
    void homeLandmarksAskForTheLandmarkCategoryByNameAndLocalizeInOneBatch() {
        SeasonDestinationDto bigBen = new SeasonDestinationDto();
        bigBen.setId(31L);
        bigBen.setName("빅벤");
        bigBen.setRegionId(120L);
        bigBen.setRegionName("런던");
        bigBen.setParentRegionId(12L);
        bigBen.setParentRegionName("영국");
        SeasonDestinationDto palace = new SeasonDestinationDto();
        palace.setId(15L);
        palace.setName("경복궁");
        palace.setRegionId(235L);
        palace.setRegionName("종로구");
        palace.setParentRegionId(10L);
        palace.setParentRegionName("서울");
        // 카테고리 번호를 코드에 두지 않고 categories.name(UNIQUE) 으로 고른다.
        // 첫 화면 여섯 장 + 레일로 넘겨 볼 카드까지 최대 18곳이다.
        when(recommendMapper.findByCategoryName("랜드마크", 18)).thenReturn(List.of(bigBen, palace));
        when(destinationService.resolveLocalizedContentByDestinationIds(
                List.of(31L, 15L), SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(31L, translation(31L, "Big Ben"),
                        15L, translation(15L, "Gyeongbokgung Palace")));
        Map<Long, String> baseRegionNames = new java.util.LinkedHashMap<>();
        baseRegionNames.put(120L, "런던");
        baseRegionNames.put(12L, "영국");
        baseRegionNames.put(235L, "종로구");
        baseRegionNames.put(10L, "서울");
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                baseRegionNames, SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(120L, "London", 12L, "United Kingdom",
                        235L, "Jongno-gu", 10L, "Seoul"));
        when(referenceNameLocalizationService.localizeCategories(
                List.of(), SupportedLanguage.ENGLISH)).thenReturn(Map.of());

        DestinationRecommendService service = new DestinationRecommendService(
                recommendMapper, destinationService, referenceNameLocalizationService);

        List<SeasonDestinationDto> result = service.findHomeLandmarks(SupportedLanguage.ENGLISH);

        assertThat(result).extracting(SeasonDestinationDto::getName)
                .containsExactly("Big Ben", "Gyeongbokgung Palace");
        assertThat(result).extracting(SeasonDestinationDto::getParentRegionName)
                .containsExactly("United Kingdom", "Seoul");
        assertThat(result).extracting(SeasonDestinationDto::getRegionName)
                .containsExactly("London", "Jongno-gu");
        // 카드마다 따로 읽지 않고 여행지 이름·지역 이름을 각각 한 번에 번역한다.
        verify(destinationService).resolveLocalizedContentByDestinationIds(
                List.of(31L, 15L), SupportedLanguage.ENGLISH);
        verify(referenceNameLocalizationService).localizeCountryCategoryNames(
                baseRegionNames, SupportedLanguage.ENGLISH);
    }

    @Test
    void trendingAsksForTheLastSevenKstDaysIncludingTodayAndLocalizesParentRegions() {
        SeasonDestinationDto palace = new SeasonDestinationDto();
        palace.setId(15L);
        palace.setName("경복궁");
        palace.setRegionId(235L);
        palace.setRegionName("종로구");
        palace.setParentRegionId(10L);
        palace.setParentRegionName("서울");
        // 오늘 포함 7일 = 6일 전(9/24) ~ 오늘(9/30). 7일 전(9/23)은 BETWEEN 아래 경계 밖이라 빠진다.
        when(recommendMapper.findTrendingByViewDate(
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 30), 5))
                .thenReturn(List.of(palace));
        when(destinationService.resolveLocalizedContentByDestinationIds(
                List.of(15L), SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(15L, translation(15L, "Gyeongbokgung Palace")));
        Map<Long, String> baseRegionNames = new java.util.LinkedHashMap<>();
        baseRegionNames.put(235L, "종로구");
        baseRegionNames.put(10L, "서울");
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                baseRegionNames, SupportedLanguage.ENGLISH))
                .thenReturn(Map.of(235L, "Jongno-gu", 10L, "Seoul"));
        when(referenceNameLocalizationService.localizeCategories(
                List.of(), SupportedLanguage.ENGLISH)).thenReturn(Map.of());

        DestinationRecommendService service = new DestinationRecommendService(
                recommendMapper, destinationService, referenceNameLocalizationService);

        List<SeasonDestinationDto> result = service.findTrendingDestinations(
                LocalDate.of(2026, 9, 30), 5, SupportedLanguage.ENGLISH);

        assertThat(result).extracting(SeasonDestinationDto::getName).containsExactly("Gyeongbokgung Palace");
        assertThat(result.get(0).getParentRegionName()).isEqualTo("Seoul");
        assertThat(result.get(0).getRegionName()).isEqualTo("Jongno-gu");
    }

    @Test
    void trendingQueryUsesTheGivenDateRangeWithAStableOrderAndNoDatabaseClock() throws IOException {
        String xml;
        try (var in = getClass().getResourceAsStream("/mapper/DestinationRecommendMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String query = xml.substring(xml.indexOf("<select id=\"findTrendingByViewDate\""));
        query = query.substring(0, query.indexOf("</select>"));

        assertThat(query)
                .contains("FROM destination_view_daily dvd")
                .contains("WHERE dvd.view_date BETWEEN #{fromDate} AND #{toDate}")
                .contains("ORDER BY recent_views DESC, dvd.destination_id ASC")
                .contains("ORDER BY recent.recent_views DESC, recent.destination_id ASC")
                .contains("LEFT JOIN country_categories pcc ON cc.parent_id = pcc.id")
                .doesNotContain("RAND()", "CURDATE()", "NOW()", "region_id IN");
    }

    private DestinationTranslation translation(Long destinationId, String name) {
        DestinationTranslation translation = new DestinationTranslation();
        translation.setDestinationId(destinationId);
        translation.setName(name);
        return translation;
    }
}
