package com.tripbora.service.course;

import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogCountryDto;
import com.tripbora.dto.CourseCatalogRegionDto;
import com.tripbora.dto.CourseThemeDto;
import com.tripbora.repository.course.CourseCatalogMapper;
import com.tripbora.service.destination.DestinationImageService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseCatalogServiceTest {
    private final CourseCatalogMapper mapper = mock(CourseCatalogMapper.class);
    private final CourseThemeService themes = mock(CourseThemeService.class);
    private final DestinationImageService images = mock(DestinationImageService.class);
    private final CourseCatalogService service = new CourseCatalogService(mapper, themes, images, new StaticMessageSource());

    @Test
    void clampsPageBeforeReadingAndLoadsOrderedCountriesAndThemesInFixedBatches() {
        when(mapper.countCourses("OFFICIAL", 2L, 3L)).thenReturn(25);
        var official = card(20L, "OFFICIAL", "/uploads/course/cover/20.jpg");
        var traveler = card(10L, "TRAVELER", null);
        when(mapper.findCourses("OFFICIAL", 2L, 3L, "views", 12, 24)).thenReturn(List.of(official, traveler));
        when(mapper.findCountriesByCourseIds(List.of(20L, 10L))).thenReturn(List.of(
                country(20L, "국가 A"), country(20L, "국가 B"), country(10L, "국가 B")));
        var first = new CourseThemeDto(); first.setId(3L); first.setName("역사·문화");
        var second = new CourseThemeDto(); second.setId(7L); second.setName("쇼핑");
        when(themes.findByCourseIds(List.of(20L, 10L))).thenReturn(Map.of(20L, List.of(first, second), 10L, List.of(second)));

        var page = service.browse("ALL", 2L, 3L, "views", 99);

        assertThat(page.getTotalCount()).isEqualTo(25);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getCurrentPage()).isEqualTo(3);
        assertThat(page.getCourses()).containsExactly(official, traveler);
        assertThat(official.getCountryNames()).containsExactly("국가 A", "국가 B");
        assertThat(traveler.getCountryNames()).containsExactly("국가 B");
        assertThat(official.getCountryLabel()).isEqualTo("국가 A · 국가 B");
        assertThat(official.getThemes()).containsExactly(first, second);
        assertThat(traveler.getThemes()).containsExactly(second);
        assertThat(official.getRepresentativeImageUrl()).isEqualTo("/uploads/course/cover/20.jpg");
        assertThat(traveler.getRepresentativeImageUrl()).isEqualTo("/images/default.png");
        assertThat(official.getDetailUrl()).isEqualTo("/courses/20");
        var order = inOrder(mapper, themes);
        order.verify(mapper).countCourses("OFFICIAL", 2L, 3L);
        order.verify(mapper).findCourses("OFFICIAL", 2L, 3L, "views", 12, 24);
        order.verify(mapper).findCountriesByCourseIds(List.of(20L, 10L));
        order.verify(mapper).findRegionsByCourseIds(List.of(20L, 10L));
        order.verify(themes).findByCourseIds(List.of(20L, 10L));
        verifyNoMoreInteractions(mapper, themes);
        verify(images).markNoDerivatives(eq(List.of(official, traveler)), any(), any());
    }

    @Test
    void invalidFiltersAndNegativePageNormalizeAndEmptyCatalogSkipsPageAndMetadataReads() {
        when(mapper.countCourses("OFFICIAL", null, null)).thenReturn(0);
        var page = service.browse("invalid", -1L, 0L, "likes", -5);
        assertThat(page.getKind()).isEqualTo("OFFICIAL");
        assertThat(page.getSort()).isEqualTo("latest");
        assertThat(page.getCountryId()).isNull();
        assertThat(page.getThemeId()).isNull();
        assertThat(page.getCurrentPage()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getCourses()).isEmpty();
        verify(mapper).countCourses("OFFICIAL", null, null);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(themes);
        verifyNoInteractions(images);
    }

    @Test
    void validKindIsNormalizedAndEmptyPageDoesNotReadMetadata() {
        when(mapper.countCourses("TRAVELER", 8L, null)).thenReturn(13);
        when(mapper.findCourses("TRAVELER", 8L, null, "latest", 12, 0)).thenReturn(List.of());
        var page = service.browse(" traveler ", 8L, null, null, Integer.MIN_VALUE);
        assertThat(page.getKind()).isEqualTo("TRAVELER");
        assertThat(page.getCourses()).isEmpty();
        assertThat(page.getCurrentPage()).isEqualTo(1);
        verify(mapper).countCourses("TRAVELER", 8L, null);
        verify(mapper).findCourses("TRAVELER", 8L, null, "latest", 12, 0);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(themes);
        verifyNoInteractions(images);
    }

    @Test
    void blankRepresentativeAndMissingMembershipOrThemeStaySafe() {
        when(mapper.countCourses("OFFICIAL", null, null)).thenReturn(1);
        var card = card(5L, "OFFICIAL", " ");
        when(mapper.findCourses("OFFICIAL", null, null, "latest", 12, 0)).thenReturn(List.of(card));
        when(mapper.findCountriesByCourseIds(List.of(5L))).thenReturn(List.of());
        when(themes.findByCourseIds(List.of(5L))).thenReturn(Map.of());
        service.browse("OFFICIAL", null, null, "latest", 1);
        assertThat(card.getCountryLabel()).isEmpty();
        assertThat(card.getThemes()).isEmpty();
        assertThat(card.getRepresentativeImageUrl()).isEqualTo("/images/default.png");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesNoDerivativeDisplayPolicyThroughExistingBatchHelper() {
        var card = card(6L, "TRAVELER", "/uploads/destination/original.jpg");
        when(mapper.countCourses("OFFICIAL", null, null)).thenReturn(1);
        when(mapper.findCourses("OFFICIAL", null, null, "latest", 12, 0)).thenReturn(List.of(card));
        when(mapper.findCountriesByCourseIds(List.of(6L))).thenReturn(List.of());
        when(themes.findByCourseIds(List.of(6L))).thenReturn(Map.of());
        doAnswer(invocation -> {
            var getUrl = (java.util.function.Function<CourseCardDto, String>) invocation.getArgument(1);
            var mark = (java.util.function.BiConsumer<CourseCardDto, Boolean>) invocation.getArgument(2);
            assertThat(getUrl.apply(card)).isEqualTo("/uploads/destination/original.jpg");
            mark.accept(card, true);
            return null;
        }).when(images).markNoDerivatives(eq(List.of(card)), any(), any());
        service.browse(null, null, null, null, 1);
        assertThat(card.isImageNoDerivatives()).isTrue();
    }

    @Test
    void linkedRegionBatchOnlyOverridesCountryFallbackForCardsWithRealRegionPaths() {
        var linked = card(15L, "OFFICIAL", null);
        var direct = card(16L, "OFFICIAL", null);
        when(mapper.countCourses("OFFICIAL", null, null)).thenReturn(2);
        when(mapper.findCourses("OFFICIAL", null, null, "latest", 12, 0)).thenReturn(List.of(linked, direct));
        when(mapper.findCountriesByCourseIds(List.of(15L, 16L))).thenReturn(List.of(
                country(15L, "대한민국"), country(16L, "일본")));
        var region = regionNode(15L, 5L, 45L, 40L, "KR-SEOUL", "KR", "서울");
        var country = regionNode(15L, 5L, 40L, null, "KR", "KR", "대한민국");
        when(mapper.findRegionsByCourseIds(List.of(15L, 16L))).thenReturn(List.of(region, country));
        when(themes.findByCourseIds(List.of(15L, 16L))).thenReturn(Map.of());

        service.browse("OFFICIAL", null, null, "latest", 1);

        assertThat(linked.getRegionSummary()).isEqualTo("서울");
        assertThat(direct.getRegionSummary()).isEqualTo("일본");
        assertThat(linked.getDurationLabel()).isEmpty();
        verify(mapper).findRegionsByCourseIds(List.of(15L, 16L));
    }

    private CourseCatalogRegionDto regionNode(Long courseId, Long stopId, Long id, Long parentId,
                                              String code, String countryCode, String name) {
        var region = new CourseCatalogRegionDto();
        region.setCourseId(courseId); region.setStopId(stopId); region.setRegionId(id);
        region.setParentId(parentId); region.setRegionCode(code); region.setCountryNode(code.equals(countryCode));
        region.setRegionName(name);
        return region;
    }

    private CourseCardDto card(Long id, String kind, String image) {
        var card = new CourseCardDto(); card.setId(id); card.setCourseKind(kind);
        card.setRepresentativeImageUrl(image); return card;
    }

    private CourseCatalogCountryDto country(Long courseId, String name) {
        var country = new CourseCatalogCountryDto(); country.setCourseId(courseId); country.setCountryName(name);
        return country;
    }
}
