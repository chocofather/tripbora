package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.*;
import com.tripbora.model.CourseDay;
import com.tripbora.model.DestinationType;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseScheduleReadTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseStructureService structure = mock(CourseStructureService.class);
    private final CourseServiceImpl service = new CourseServiceImpl(mapper, new PostContentSanitizer(),
            mock(DestinationLocalizationService.class), mock(ReferenceNameLocalizationService.class),
            new LocalContentLanguageDetector(), mock(DestinationImageService.class), structure,
            mock(CourseCoverStorageService.class), mock(com.tripbora.service.category.CountryCategoryService.class), mock(CourseThemeService.class));

    @Test
    void planTemplateReadsPublicCourseWithoutIncrementingViews() {
        var detail = new CourseDetailDto(); detail.setTitle("제목");
        when(mapper.findCourseDetail(10L, null)).thenReturn(detail);
        var stop = item(50L, 1, null);
        var day = new CourseDay(); day.setId(50L); day.setDayNumber(1);
        when(structure.findDays(10L)).thenReturn(List.of(day));
        when(structure.findCourseStops(10L)).thenReturn(List.of(stop));
        var country = new com.tripbora.model.CountryCategory(); country.setId(5L); country.setRegionName("일본");
        when(structure.findCountries(10L)).thenReturn(List.of(country));
        assertThat(service.getCourseForPlan(10L).getStops()).containsExactly(stop);
        assertThat(detail.getCountryLabel()).isEqualTo("일본");
        verify(mapper, never()).incrementViews(anyLong());
        verify(mapper, never()).findCourseStops(anyLong());
    }

    @Test
    void legacyPlanTemplateIsDayOneAndKeepsRepeatedVisits() {
        when(mapper.findCourseDetail(10L, null)).thenReturn(new CourseDetailDto());
        var first = item(null, 1, null); first.setName("장소");
        var second = item(null, 2, null); second.setName("장소");
        when(mapper.findCourseStops(10L)).thenReturn(List.of(first, second));
        var template = service.getCourseForPlan(10L);
        assertThat(template.getDays()).extracting(CourseDayDto::getDayNumber).containsExactly(1);
        assertThat(template.getDays().get(0).getItems()).containsExactly(first, second);
    }

    @Test
    void hiddenOrDeletedPlanTemplateIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getCourseForPlan(10L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(structure);
    }

    @Test
    void keepsDirectItemsAndSkipsThemWhenChoosingFirstLinkedCover() {
        CourseStopDto direct = item(50L, 1, "/images/default.png");
        direct.setDestinationId(null); direct.setName("직접 숙소"); direct.setItemType("STAY");
        CourseStopDto linked = item(50L, 2, "/uploads/linked.png");
        prepare(List.of(direct, linked));
        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);
        assertThat(detail.getStops()).containsExactly(direct, linked);
        assertThat(direct.getName()).isEqualTo("직접 숙소");
        assertThat(detail.getRepresentativeImageUrl()).isEqualTo("/uploads/linked.png");
    }

    @Test
    void retryPreservesDirectFieldsAndRepeatedNames() {
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1);
        day.setItems(List.of(CourseDirectItemTest.direct("ETC", 1), CourseDirectItemTest.direct("STAY", 2)));
        day.getItems().get(0).setCountryId(8L);
        CourseEditDto retry = service.prepareFormForRetry("제목", "<p>소개</p>", 7L, List.of(day));
        assertThat(retry.getStops()).hasSize(2).allSatisfy(item -> {
            assertThat(item.getDestinationId()).isNull();
            assertThat(item.getName()).isEqualTo("직접 장소");
            var bean = new org.springframework.beans.BeanWrapperImpl(item);
            assertThat(bean.getPropertyValue("address")).isEqualTo("주소");
            assertThat((java.math.BigDecimal) bean.getPropertyValue("latitude")).isEqualByComparingTo("37.5");
            assertThat((java.math.BigDecimal) bean.getPropertyValue("longitude")).isEqualByComparingTo("127");
        });
        assertThat(retry.getStops()).extracting(CourseStopDto::getItemType).containsExactly("ETC", "STAY");
        assertThat(retry.getStops()).extracting(CourseStopDto::getCountryId).containsExactly(8L, 7L);
    }

    @Test
    void readsAllDaysAndItemTimesAndMemosInOrderWithoutLegacy() {
        CourseStopDto first = item(50L, 1, null);
        first.setStartTime("23:00");
        first.setEndTime("01:00");
        first.setMemo("야간 방문");
        CourseStopDto second = item(50L, 2, "/uploads/later.png");
        CourseStopDto third = item(51L, 1, "/uploads/day2.png");
        prepare(List.of(first, second, third));

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);

        assertThat(detail.getDays()).extracting(CourseDayDto::getDayNumber).containsExactly(1, 2);
        assertThat(detail.getDays().get(0).getTitle()).isEqualTo("서울 도심");
        assertThat(detail.getDays().get(0).getItems()).containsExactly(first, second);
        assertThat(detail.getDays().get(1).getItems()).containsExactly(third);
        assertThat(detail.getStops()).containsExactly(first, second, third);
        assertThat(first.getStartTime()).isEqualTo("23:00");
        assertThat(first.getEndTime()).isEqualTo("01:00");
        assertThat(first.getMemo()).isEqualTo("야간 방문");
        assertThat(detail.getRepresentativeImageUrl()).isEqualTo("/images/default.png");
        verify(mapper, never()).findCourseStops(anyLong());
    }

    @Test
    void detailUsesCoverBeforeTheFirstDayFirstDestinationImage() {
        prepare(List.of(item(50L, 1, "/uploads/first.png")));
        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);
        assertThat(detail.getRepresentativeImageUrl()).isEqualTo("/uploads/first.png");
        detail.setCoverImageUrl("/uploads/cover.png");
        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getRepresentativeImageUrl())
                .isEqualTo("/uploads/cover.png");
    }

    @Test
    void retryKeepsSubmittedDayIdsTitlesRevisitsAndTimesWhileSanitizingContent() {
        CourseDayForm day = new CourseDayForm();
        day.setId(50L);
        day.setDayNumber(1);
        day.setTitle("<첫날>");
        CourseItemForm input = new CourseItemForm();
        input.setDestinationId(12L);
        input.setDisplayOrder(1);
        input.setStartTime("23:00");
        input.setEndTime("01:00");
        input.setMemo("<script>그대로 텍스트로 보일 메모</script>");
        day.setItems(List.of(input, input));
        CourseDestinationSnapshotDto source = new CourseDestinationSnapshotDto();
        source.setDestinationId(12L);
        source.setPlaceName("실제 숙소명");
        source.setCountryId(7L);
        source.setType(DestinationType.ACCOMMODATION);
        when(structure.findDestinationSnapshots(List.of(12L))).thenReturn(List.of(source));

        CourseEditDto retry = service.prepareFormForRetry("작성 제목", "<p>소개</p><script>x</script>",
                7L, List.of(day));

        assertThat(retry.getContent()).isEqualTo("<p>소개</p>");
        assertThat(retry.getDays().get(0).getId()).isEqualTo(50L);
        assertThat(retry.getDays().get(0).getTitle()).isEqualTo("<첫날>");
        assertThat(retry.getDays().get(0).getItems()).hasSize(2).allSatisfy(item -> {
            assertThat(item.getName()).isEqualTo("실제 숙소명");
            assertThat(item.getItemType()).isEqualTo("STAY");
            assertThat(item.getStartTime()).isEqualTo("23:00");
            assertThat(item.getEndTime()).isNull();
            assertThat(item.getMemo()).isEqualTo(input.getMemo());
        });
    }

    @Test
    void detailUsesCatalogRegionRulesForDomesticAndForeignCoursesWithSafeFallback() {
        prepare(List.of(item(50L, 1, null)));
        for (String city : List.of("서울", "제주", "오사카")) {
            var country = new CourseCatalogRegionDto();
            country.setStopId(1L); country.setRegionId(100L); country.setCountryNode(true);
            var region = new CourseCatalogRegionDto();
            region.setStopId(1L); region.setRegionId(200L); region.setParentId(100L); region.setRegionName(city);
            var rows = List.of(region, country);
            when(mapper.findRegionsByCourseIds(List.of(10L))).thenReturn(rows);
            var detail = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);
            var card = new CourseCardDto(); card.setRegionSummary(CourseCardDisplayLabels.regionSummary(rows));
            assertThat(detail.getRegionSummary()).isEqualTo(city).isEqualTo(card.getRegionSummary());
            detail.setCountryNames(List.of("별도로 조회한 국가"));
            assertThat(detail.getCountryLabel()).isEqualTo("별도로 조회한 국가");
            assertThat(detail.getRegionSummary()).isEqualTo(city);
        }
        when(mapper.findRegionsByCourseIds(List.of(10L))).thenReturn(List.of());
        var missing = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);
        assertThat(missing.getRegionSummary()).isEmpty();
        missing.setCountryNames(List.of("기존 국가"));
        assertThat(missing.getRegionSummary()).isEqualTo("기존 국가");
    }

    private void prepare(List<CourseStopDto> items) {
        CourseDay first = new CourseDay();
        first.setId(50L); first.setDayNumber(1); first.setTitle("서울 도심");
        CourseDay second = new CourseDay();
        second.setId(51L); second.setDayNumber(2);
        when(structure.findDays(10L)).thenReturn(List.of(first, second));
        when(structure.findCourseStops(10L)).thenReturn(items);
        CourseDetailDto detail = new CourseDetailDto();
        detail.setContent("<p>소개</p>");
        when(mapper.incrementViews(10L)).thenReturn(1);
        when(mapper.findCourseDetail(10L, null)).thenReturn(detail);
    }

    private CourseStopDto item(Long dayId, int order, String image) {
        CourseStopDto item = new CourseStopDto();
        item.setDestinationId(12L);
        item.setCourseDayId(dayId);
        item.setVisitOrder(order);
        item.setImageUrl(image);
        return item;
    }
}
