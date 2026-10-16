package com.tripbora.service.course;

import com.tripbora.dto.CourseDayForm;
import com.tripbora.dto.CourseDestinationSnapshotDto;
import com.tripbora.dto.CourseItemForm;
import com.tripbora.model.CourseCountry;
import com.tripbora.model.CourseDay;
import com.tripbora.model.CourseItem;
import com.tripbora.model.DestinationType;
import com.tripbora.repository.course.CourseStructureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CourseMultiCountryTest {
    private final CourseStructureMapper mapper = mock(CourseStructureMapper.class);
    private final CourseStructureService service = new CourseStructureService(mapper);

    @Test
    void mixedItemsDeriveDistinctCountriesInFirstAppearanceOrderAndIgnoreLinkedClientCountry() {
        prepare();
        var source = new CourseDestinationSnapshotDto(); source.setDestinationId(8L);
        source.setCountryId(20L); source.setPlaceName("오사카"); source.setType(DestinationType.ATTRACTION);
        when(mapper.findDestinationSnapshots(List.of(8L))).thenReturn(List.of(source));
        var linked = direct(999L); linked.setDestinationId(8L);
        service.replaceSchedule(7L, 999L, List.of(day(1, linked, direct(30L)), day(2, direct(40L), direct(20L))));
        assertThat(savedCountries()).containsExactly(20L, 30L, 40L);
        var items = ArgumentCaptor.forClass(CourseItem.class); verify(mapper, times(4)).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getCountryId).containsExactly(20L, 30L, 40L, 20L);
        verify(mapper).deleteCountries(7L); verify(mapper).syncLegacyCountry(7L);
    }

    @Test
    void singleCountryIsSavedOnceAndReorderingOrRemovingItemsRebuildsTheSet() {
        prepare();
        service.replaceSchedule(7L, null, List.of(day(1, direct(20L), direct(20L))));
        assertThat(savedCountries()).containsExactly(20L);
        clearInvocations(mapper);
        service.replaceSchedule(7L, null, List.of(day(1, direct(30L), direct(20L))));
        assertThat(savedCountries()).containsExactly(30L, 20L);
        verify(mapper).deleteCountries(7L);
        clearInvocations(mapper);
        service.replaceSchedule(7L, null, List.of(day(1, direct(20L))));
        assertThat(savedCountries()).containsExactly(20L);
        verify(mapper).deleteCountries(7L);
    }

    @Test
    void directCountryIsRequiredAndCannotFallBackToTheLegacyCourseCountry() {
        assertThatThrownBy(() -> service.replaceSchedule(7L, 20L, List.of(day(1, direct(null)))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void unresolvedLinkedCountryIsRejectedBeforeDeletingAnyExistingSchedule() {
        var source = new CourseDestinationSnapshotDto(); source.setDestinationId(8L);
        source.setPlaceName("국가 없음"); source.setType(DestinationType.ATTRACTION);
        when(mapper.findDestinationSnapshots(List.of(8L))).thenReturn(List.of(source));
        var linked = direct(20L); linked.setDestinationId(8L);
        assertThatThrownBy(() -> service.replaceSchedule(7L, 20L, List.of(day(1, linked))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(mapper, never()).deleteCountries(anyLong()); verify(mapper, never()).deleteDay(anyLong(), anyLong());
    }

    private void prepare() {
        when(mapper.findDays(7L)).thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100);
        when(mapper.insertDay(any())).thenAnswer(invocation -> {
            ((CourseDay) invocation.getArgument(0)).setId(ids.incrementAndGet()); return 1;
        });
        when(mapper.insertItem(any())).thenReturn(1); when(mapper.insertCountry(any())).thenReturn(1);
        when(mapper.syncLegacyCountry(7L)).thenReturn(1);
    }

    private List<Long> savedCountries() {
        var rows = ArgumentCaptor.forClass(CourseCountry.class);
        verify(mapper, atLeastOnce()).insertCountry(rows.capture());
        for (int i = 0; i < rows.getAllValues().size(); i++) {
            assertThat(rows.getAllValues().get(i).getDisplayOrder()).isEqualTo(i + 1);
        }
        return rows.getAllValues().stream().map(CourseCountry::getCountryId).toList();
    }

    private CourseItemForm direct(Long countryId) {
        var item = new CourseItemForm(); item.setCountryId(countryId); item.setItemType("STAY"); item.setPlaceName("숙소"); return item;
    }

    private CourseDayForm day(int number, CourseItemForm... items) {
        var day = new CourseDayForm(); day.setDayNumber(number); day.setItems(List.of(items));
        for (int i = 0; i < items.length; i++) items[i].setDisplayOrder(i + 1);
        return day;
    }
}
