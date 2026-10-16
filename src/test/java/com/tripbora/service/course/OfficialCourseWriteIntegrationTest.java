package com.tripbora.service.course;

import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.course.*;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** DB 없이 실제 공용 검증/일정 snapshot/국가/테마 저장 서비스를 연결한다. */
class OfficialCourseWriteIntegrationTest {
    @Test void officialCreationReusesMixedLinkedAndDirectScheduleAcrossThreeCountries() {
        CourseMapper courses = mock(CourseMapper.class);
        CourseStructureMapper schedule = mock(CourseStructureMapper.class);
        CourseThemeMapper themes = mock(CourseThemeMapper.class);
        CountryCategoryService countries = mock(CountryCategoryService.class);
        CourseStructureService structure = new CourseStructureService(schedule);
        CourseThemeService themeService = new CourseThemeService(themes);
        CourseServiceImpl shared = new CourseServiceImpl(courses, new PostContentSanitizer(), null, null,
                new LocalContentLanguageDetector(), null, structure, mock(CourseCoverStorageService.class), countries, themeService);
        OfficialCourseAdminService service = new OfficialCourseAdminService(courses, shared, structure, themeService,
                mock(CourseCoverStorageService.class));
        OfficialCourseForm form = new OfficialCourseForm();
        form.setTitle("  세 나라 코스  "); form.setSummary("요약"); form.setThemeIds(List.of(2L, 1L));
        CourseItemForm first = linked(11L, 1); CourseItemForm second = linked(22L, 2);
        CourseItemForm direct = new CourseItemForm(); direct.setDisplayOrder(3); direct.setCountryId(9L);
        direct.setItemType("TRANSPORT"); direct.setPlaceName("  국경 이동  "); direct.setMemo("기차"); direct.setStartTime("10:30");
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1); day.setItems(List.of(first, second, direct));
        form.setDays(List.of(day));
        when(courses.countExistingDestinations(List.of(11L, 22L))).thenReturn(2);
        when(courses.findDestinationCountries(List.of(11L, 22L))).thenReturn(List.of(country(11L, 7L), country(22L, 8L)));
        CountryCategory directCountry = new CountryCategory(); directCountry.setId(9L);
        when(countries.getCourseCountries()).thenReturn(List.of(directCountry));
        when(schedule.findDestinationSnapshots(List.of(11L, 22L))).thenReturn(List.of(snapshot(11L, 7L), snapshot(22L, 8L)));
        when(schedule.findDays(10L)).thenReturn(List.of());
        when(courses.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        when(schedule.insertDay(any())).thenAnswer(call -> { ((CourseDay) call.getArgument(0)).setId(100L); return 1; });
        when(schedule.insertItem(any())).thenReturn(1); when(schedule.insertCountry(any())).thenReturn(1);
        when(schedule.syncLegacyCountry(10L)).thenReturn(1);
        CourseThemeDto theme1 = new CourseThemeDto(); theme1.setId(1L);
        CourseThemeDto theme2 = new CourseThemeDto(); theme2.setId(2L);
        when(themes.findActiveThemes()).thenReturn(List.of(theme1, theme2));
        when(themes.insertMapping(anyLong(), anyLong(), anyInt())).thenReturn(1);
        var manager = new CourseTransitionTest.RecordingTransactionManager();

        Long createdId = new TransactionTemplate(manager).execute(status -> service.create(form, 5L));
        assertThat(createdId).isEqualTo(10L);

        var savedCountries = ArgumentCaptor.forClass(CourseCountry.class); verify(schedule, times(3)).insertCountry(savedCountries.capture());
        assertThat(savedCountries.getAllValues()).extracting(CourseCountry::getCountryId).containsExactly(7L, 8L, 9L);
        var items = ArgumentCaptor.forClass(CourseItem.class); verify(schedule, times(3)).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getPlaceName).containsExactly("여행지11", "여행지22", "국경 이동");
        assertThat(items.getAllValues()).extracting(CourseItem::getDestinationId).containsExactly(11L, 22L, null);
        assertThat(items.getAllValues().get(2).getStartTime()).hasToString("10:30");
        verify(themes).insertMapping(10L, 2L, 1); verify(themes).insertMapping(10L, 1L, 2);
        verify(courses, never()).insertCourseDestination(any());
        assertThat(manager.commits).isEqualTo(1); assertThat(manager.rollbacks).isZero();
    }

    private CourseItemForm linked(Long id, int order) {
        CourseItemForm item = new CourseItemForm(); item.setDestinationId(id); item.setDisplayOrder(order); return item;
    }
    private CourseDestinationCountryDto country(Long id, Long country) {
        CourseDestinationCountryDto dto = new CourseDestinationCountryDto(); dto.setDestinationId(id); dto.setCountryId(country); return dto;
    }
    private CourseDestinationSnapshotDto snapshot(Long id, Long country) {
        CourseDestinationSnapshotDto dto = new CourseDestinationSnapshotDto(); dto.setDestinationId(id); dto.setCountryId(country);
        dto.setPlaceName("여행지" + id); dto.setType(DestinationType.ATTRACTION); return dto;
    }
}
