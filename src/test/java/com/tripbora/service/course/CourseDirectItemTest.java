package com.tripbora.service.course;

import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.course.CourseStructureMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseDirectItemTest {
    @Test
    void storesAllFiveDirectTypesWithoutDestinationQueriesUsingEachItemCountry() {
        CourseStructureMapper mapper = preparedMapper();
        CourseDayForm day = day();
        for (String type : List.of("PLACE", "STAY", "FOOD", "TRANSPORT", "ETC")) {
            CourseItemForm input = direct(type, day.getItems().size() + 1);
            day.getItems().add(input);
        }
        new CourseStructureService(mapper).replaceSchedule(10L, 999L, List.of(day));
        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(mapper, times(5)).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getItemType)
                .containsExactly("PLACE", "STAY", "FOOD", "TRANSPORT", "ETC");
        assertThat(items.getAllValues()).allSatisfy(item -> {
            assertThat(item.getDestinationId()).isNull();
            assertThat(item.getCountryId()).isEqualTo(7L);
            assertThat(item.getPlaceName()).isEqualTo("직접 장소");
            assertThat(item.getAddress()).isEqualTo("주소");
            assertThat(item.getLatitude()).isEqualByComparingTo("37.5");
            assertThat(item.getLongitude()).isEqualByComparingTo("127");
            assertThat(item.getEndTime()).isNull();
        });
        verify(mapper, never()).findDestinationSnapshots(anyList());
    }

    @Test
    void validatesDirectFieldsAndCoordinatePairsButIgnoresForgedLinkedSnapshots() {
        CourseItemForm input = direct("PLACE", 1);
        CourseDayForm day = day(); day.getItems().add(input);
        assertThat(CourseScheduleValidator.validate(List.of(day))).isEmpty();
        for (Object[] invalid : List.of(new Object[]{"itemType", "INVALID"},
                new Object[]{"placeName", " "}, new Object[]{"placeName", "가".repeat(256)},
                new Object[]{"address", "가".repeat(501)}, new Object[]{"latitude", new BigDecimal("90.1")},
                new Object[]{"longitude", new BigDecimal("-180.1")}, new Object[]{"longitude", null})) {
            var bean = new BeanWrapperImpl(input);
            Object before = bean.getPropertyValue((String) invalid[0]);
            bean.setPropertyValue((String) invalid[0], invalid[1]);
            assertThatThrownBy(() -> CourseScheduleValidator.validate(List.of(day)))
                    .isInstanceOf(ResponseStatusException.class);
            bean.setPropertyValue((String) invalid[0], before);
        }
        input.setDestinationId(2L);
        new BeanWrapperImpl(input).setPropertyValue("itemType", "FORGED");
        assertThat(CourseScheduleValidator.validate(List.of(day))).containsExactly(2L);
    }

    @Test
    void mixedItemsUseOnlyTrustedLinkedSnapshot() {
        CourseStructureMapper mapper = preparedMapper();
        CourseDestinationSnapshotDto source = new CourseDestinationSnapshotDto();
        source.setDestinationId(2L); source.setCountryId(7L); source.setPlaceName("실제 장소");
        source.setType(DestinationType.ACCOMMODATION);
        when(mapper.findDestinationSnapshots(List.of(2L))).thenReturn(List.of(source));
        CourseDayForm day = day();
        day.getItems().add(direct("FOOD", 1));
        CourseItemForm linked = direct("TRANSPORT", 2); linked.setDestinationId(2L);
        linked.setCountryId(999L);
        linked.setPlaceName("위조".repeat(300)); linked.setAddress("위조".repeat(300));
        linked.setLatitude(new BigDecimal("999")); linked.setLongitude(null);
        day.getItems().add(linked);
        new CourseStructureService(mapper).replaceSchedule(10L, 7L, List.of(day));
        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(mapper, times(2)).insertItem(items.capture());
        CourseItem trusted = items.getAllValues().get(1);
        assertThat(trusted.getPlaceName()).isEqualTo("실제 장소");
        assertThat(trusted.getItemType()).isEqualTo("STAY");
        assertThat(trusted.getCountryId()).isEqualTo(7L);
        assertThat(trusted.getAddress()).isNull();
        assertThat(trusted.getLatitude()).isNull();
    }

    @Test
    void permitsMissingCoordinatesAndInclusiveWorldCoordinateBoundaries() {
        CourseDayForm day = day(); CourseItemForm input = direct("PLACE", 1); day.getItems().add(input);
        input.setLatitude(null); input.setLongitude(null);
        assertThat(CourseScheduleValidator.validate(List.of(day))).isEmpty();
        input.setLatitude(new BigDecimal("-90")); input.setLongitude(new BigDecimal("180"));
        assertThat(CourseScheduleValidator.validate(List.of(day))).isEmpty();
        input.setLatitude(new BigDecimal("90")); input.setLongitude(new BigDecimal("-180"));
        assertThat(CourseScheduleValidator.validate(List.of(day))).isEmpty();
    }

    @Test
    void invalidDirectItemFailsBeforeDeletingOrWritingAnyScheduleRows() {
        CourseStructureMapper mapper = mock(CourseStructureMapper.class);
        CourseDayForm day = day(); day.setId(50L); day.getItems().add(direct("STAY", 1));
        CourseItemForm invalid = direct("FOOD", 2); invalid.setLatitude(null); day.getItems().add(invalid);
        assertThatThrownBy(() -> new CourseStructureService(mapper).replaceSchedule(10L, 7L, List.of(day)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void directOnlyEditReplacesRetainedDayItemsAndDeletesRemovedDays() {
        CourseStructureMapper mapper = preparedMapper();
        CourseDay retained = new CourseDay(); retained.setId(50L); retained.setDayNumber(1);
        CourseDay removed = new CourseDay(); removed.setId(51L); removed.setDayNumber(2);
        when(mapper.findDays(10L)).thenReturn(List.of(retained, removed));
        when(mapper.deleteDay(10L, 51L)).thenReturn(1); when(mapper.updateDay(any())).thenReturn(1);
        CourseDayForm day = day(); day.setId(50L); day.getItems().add(direct("ETC", 1));
        new CourseStructureService(mapper).replaceSchedule(10L, 7L, List.of(day));
        verify(mapper).deleteDay(10L, 51L);
        verify(mapper).deleteDayItems(50L);
        ArgumentCaptor<CourseItem> item = ArgumentCaptor.forClass(CourseItem.class);
        verify(mapper).insertItem(item.capture());
        assertThat(item.getValue().getCourseDayId()).isEqualTo(50L);
        assertThat(item.getValue().getItemType()).isEqualTo("ETC");
        assertThat(item.getValue().getDestinationId()).isNull();
        verify(mapper, never()).findDestinationSnapshots(anyList());
    }

    @Test
    void storesDistinctCountriesInFirstDayAndItemAppearanceOrder() {
        CourseStructureMapper mapper = preparedMapper();
        CourseDestinationSnapshotDto linkedCountry = new CourseDestinationSnapshotDto();
        linkedCountry.setDestinationId(2L); linkedCountry.setCountryId(7L);
        linkedCountry.setPlaceName("등록 장소"); linkedCountry.setType(DestinationType.ATTRACTION);
        when(mapper.findDestinationSnapshots(List.of(2L))).thenReturn(List.of(linkedCountry));
        CourseDayForm first = day();
        CourseItemForm japan = direct("STAY", 1); japan.setCountryId(8L);
        CourseItemForm linked = direct("PLACE", 2); linked.setDestinationId(2L); linked.setCountryId(999L);
        first.getItems().addAll(List.of(japan, linked));
        CourseDayForm second = day(); second.setDayNumber(2);
        CourseItemForm france = direct("FOOD", 1); france.setCountryId(17L);
        CourseItemForm repeatJapan = direct("ETC", 2); repeatJapan.setCountryId(8L);
        second.getItems().addAll(List.of(france, repeatJapan));

        new CourseStructureService(mapper).replaceSchedule(10L, 999L, List.of(first, second));

        ArgumentCaptor<CourseCountry> countries = ArgumentCaptor.forClass(CourseCountry.class);
        verify(mapper, times(3)).insertCountry(countries.capture());
        assertThat(countries.getAllValues()).extracting(CourseCountry::getCountryId).containsExactly(8L, 7L, 17L);
        assertThat(countries.getAllValues()).extracting(CourseCountry::getDisplayOrder).containsExactly(1, 2, 3);
        verify(mapper).syncLegacyCountry(10L);
    }

    @Test
    void directItemsRequirePositiveIndividualCountryIds() {
        for (Long countryId : java.util.Arrays.asList(null, 0L, -1L)) {
            CourseDayForm day = day(); CourseItemForm item = direct("PLACE", 1);
            item.setCountryId(countryId); day.getItems().add(item);
            assertThatThrownBy(() -> CourseScheduleValidator.validate(List.of(day)))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    private CourseStructureMapper preparedMapper() {
        CourseStructureMapper mapper = mock(CourseStructureMapper.class);
        when(mapper.insertDay(any())).thenAnswer(call -> { ((CourseDay) call.getArgument(0)).setId(50L); return 1; });
        when(mapper.insertItem(any())).thenReturn(1);
        when(mapper.insertCountry(any())).thenReturn(1);
        when(mapper.syncLegacyCountry(anyLong())).thenReturn(1);
        return mapper;
    }

    static CourseItemForm direct(String type, int order) {
        CourseItemForm input = new CourseItemForm(); input.setDisplayOrder(order);
        input.setCountryId(7L);
        var bean = new BeanWrapperImpl(input);
        bean.setPropertyValue("itemType", type); bean.setPropertyValue("placeName", "직접 장소");
        bean.setPropertyValue("address", "주소"); bean.setPropertyValue("latitude", new BigDecimal("37.5"));
        bean.setPropertyValue("longitude", new BigDecimal("127"));
        return input;
    }

    private CourseDayForm day() {
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1); day.setItems(new ArrayList<>()); return day;
    }
}
