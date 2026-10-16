package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.course.CourseStructureMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CourseTransitionTest {
    @Mock CourseMapper legacy;
    @Mock CourseStructureMapper structure;
    @Mock DestinationLocalizationService localization;
    @Mock ReferenceNameLocalizationService referenceNames;
    @Mock DestinationImageService images;
    CourseServiceImpl service;
    CourseStructureService transition;

    @BeforeEach
    void setUp() {
        lenient().when(structure.syncLegacyCountry(anyLong())).thenReturn(1);
        transition = new CourseStructureService(structure);
        service = new CourseServiceImpl(legacy, new PostContentSanitizer(), localization,
                referenceNames, new LocalContentLanguageDetector(), images, transition,
                mock(CourseCoverStorageService.class), mock(com.tripbora.service.category.CountryCategoryService.class), mock(CourseThemeService.class));
    }

    @Test
    void createsTravelerWithMultipleDaysAndDistinctDestinationsWithoutLegacyWrites() {
        prepareWrite(List.of(3L, 2L, 4L, 5L));
        when(legacy.insertCourse(any())).thenAnswer(call -> {
            Course course = call.getArgument(0);
            assertThat(course.getCourseKind()).isEqualTo("TRAVELER");
            assertThat(course.getStatus()).isEqualTo("PUBLISHED");
            course.setId(10L);
            return 1;
        });
        java.util.concurrent.atomic.AtomicLong nextDayId = new java.util.concurrent.atomic.AtomicLong(50L);
        when(structure.insertDay(any())).thenAnswer(call -> {
            CourseDay day = call.getArgument(0);
            assertThat(day.getCourseId()).isEqualTo(10L);
            day.setId(nextDayId.getAndIncrement());
            return 1;
        });
        CourseCreateRequest request = new CourseCreateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDays(List.of(day(null, 1, 3L, 2L), day(null, 2, 4L, 5L)));
        request.getDays().get(0).setTitle("서울 첫날");
        CourseItemForm timedItem = request.getDays().get(0).getItems().get(0);
        timedItem.setStartTime("09:10");
        timedItem.setEndTime("10:30");
        timedItem.setMemo("입구에서 만나기");

        assertThat(service.createCourse(request, 5L)).isEqualTo(10L);
        assertScheduleAndCountry(List.of(3L, 2L, 4L, 5L), List.of(50L, 50L, 51L, 51L));
        ArgumentCaptor<CourseDay> days = ArgumentCaptor.forClass(CourseDay.class);
        verify(structure, times(2)).insertDay(days.capture());
        assertThat(days.getAllValues()).extracting(CourseDay::getDayNumber).containsExactly(1, 2);
        assertThat(days.getAllValues().get(0).getTitle()).isEqualTo("서울 첫날");
        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(structure, times(4)).insertItem(items.capture());
        assertThat(items.getAllValues().get(0).getStartTime()).isEqualTo(LocalTime.of(9, 10));
        assertThat(items.getAllValues().get(0).getEndTime()).isNull();
        assertThat(items.getAllValues().get(0).getMemo()).isEqualTo("입구에서 만나기");
    }

    @Test
    void editKeepsReusedDayIdAndDeletesOnlyRemovedDaysWithoutLegacyWrites() {
        prepareWrite(List.of(3L, 2L, 4L, 5L));
        Course current = new Course();
        current.setId(10L);
        current.setUserId(5L);
        when(legacy.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(legacy.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1), storedDay(51L, 2)));
        when(structure.updateDay(any())).thenReturn(1);
        when(structure.deleteDay(10L, 51L)).thenReturn(1);
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDays(List.of(day(50L, 1, 3L, 2L, 4L, 5L)));

        service.updateCourse(10L, 5L, request);

        assertScheduleAndCountry(List.of(3L, 2L, 4L, 5L), List.of(50L, 50L, 50L, 50L));
        verify(structure, never()).insertDay(any());
        verify(structure).deleteDay(10L, 51L);
        verify(structure, never()).deleteDay(10L, 50L);
        ArgumentCaptor<CourseDay> updated = ArgumentCaptor.forClass(CourseDay.class);
        verify(structure).updateDay(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(50L);
        assertThat(updated.getValue().getDayNumber()).isEqualTo(1);
        var sequence = inOrder(structure);
        sequence.verify(structure).deleteDayItems(50L);
        sequence.verify(structure, times(4)).insertItem(any());
        sequence.verify(structure).deleteCountries(10L);
        sequence.verify(structure).insertCountry(any());
        sequence.verify(structure).syncLegacyCountry(10L);
    }

    @Test
    void reorderingDaysParksExistingNumbersAndKeepsDayIds() {
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1), storedDay(51L, 2)));
        when(structure.findDestinationSnapshots(List.of(3L, 2L)))
                .thenReturn(List.of(snapshot(2L), snapshot(3L)));
        when(structure.updateDay(any())).thenReturn(1);
        when(structure.insertItem(any())).thenReturn(1);
        when(structure.insertCountry(any())).thenReturn(1);

        transition.replaceSchedule(10L, 7L,
                List.of(day(51L, 1, 3L), day(50L, 2, 2L)));

        ArgumentCaptor<CourseDay> days = ArgumentCaptor.forClass(CourseDay.class);
        var sequence = inOrder(structure);
        sequence.verify(structure).parkDayNumbers(eq(10L), anyInt());
        sequence.verify(structure, times(2)).updateDay(days.capture());
        assertThat(days.getAllValues()).extracting(CourseDay::getId).containsExactly(51L, 50L);
        assertThat(days.getAllValues()).extracting(CourseDay::getDayNumber).containsExactly(1, 2);
        verify(structure, never()).insertDay(any());
        verify(structure, never()).deleteDay(anyLong(), anyLong());
    }

    @Test
    void snapshotMapsTypesAndRejectsIncompleteOrOutOfRangeCoordinatePairs() {
        List<DestinationType> types = List.of(DestinationType.ACCOMMODATION,
                DestinationType.RESTAURANTS, DestinationType.CAFE, DestinationType.ATTRACTION);
        List<CourseDestinationSnapshotDto> sources = new java.util.ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            CourseDestinationSnapshotDto source = snapshot((long) i + 1);
            source.setType(types.get(i));
            sources.add(source);
        }
        sources.get(1).setLongitude(null);
        sources.get(2).setLatitude(new BigDecimal("91"));
        sources.get(3).setPlaceName(null);
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1)));
        when(structure.updateDay(any())).thenReturn(1);
        when(structure.findDestinationSnapshots(anyList())).thenReturn(sources);
        when(structure.insertItem(any())).thenReturn(1);
        when(structure.insertCountry(any())).thenReturn(1);

        transition.replaceSchedule(10L, 7L, List.of(day(50L, 1, 1L, 2L, 3L, 4L)));

        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(structure, times(4)).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getItemType)
                .containsExactly("STAY", "FOOD", "FOOD", "PLACE");
        assertThat(items.getAllValues().get(0).getPlaceName()).isEqualTo("등록된 장소 1");
        assertThat(items.getAllValues().get(0).getCountryId()).isEqualTo(7L);
        assertThat(items.getAllValues().get(0).getLatitude()).isEqualByComparingTo("37.5");
        assertThat(items.getAllValues().get(0).getLongitude()).isEqualByComparingTo("127");
        for (int i : List.of(1, 2)) {
            assertThat(items.getAllValues().get(i).getLatitude()).isNull();
            assertThat(items.getAllValues().get(i).getLongitude()).isNull();
        }
        assertThat(items.getAllValues().get(3).getPlaceName()).isNull();
        assertThat(items.getAllValues().get(3).getCountryId()).isEqualTo(7L);
        assertThat(items.getAllValues()).allSatisfy(item -> {
            assertThat(item.getAddress()).isNull();
            assertThat(item.getStartTime()).isNull();
            assertThat(item.getEndTime()).isNull();
            assertThat(item.getMemo()).isNull();
        });
    }

    @Test
    void newStopsWinWithoutReadingLegacyEvenWhenDestinationWasRemoved() {
        prepareDetail();
        CourseStopDto stop = new CourseStopDto();
        stop.setDestinationId(2L);
        stop.setName("새 구조의 장소");
        CourseStopDto unlinked = new CourseStopDto();
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1)));
        stop.setCourseDayId(50L);
        unlinked.setCourseDayId(50L);
        when(structure.findCourseStops(10L)).thenReturn(List.of(stop, unlinked));

        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops())
                .containsExactly(stop, unlinked);
        verify(legacy, never()).findCourseStops(anyLong());
        when(structure.findCourseStops(10L)).thenReturn(List.of(unlinked));
        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops()).containsExactly(unlinked);
        verify(legacy, never()).findCourseStops(anyLong());
    }

    @Test
    void missingDaysFallBackToLegacyAsOneDay() {
        prepareDetail();
        CourseStopDto legacyStop = new CourseStopDto();
        legacyStop.setDestinationId(3L);
        when(legacy.findCourseStops(10L)).thenReturn(List.of(legacyStop));
        when(structure.findDays(10L)).thenReturn(List.of());
        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.KOREAN);
        assertThat(detail.getStops()).containsExactly(legacyStop);
        assertThat(detail.getDays()).hasSize(1);
        assertThat(detail.getDays().get(0).getDayNumber()).isEqualTo(1);
        assertThat(detail.getDays().get(0).getItems()).containsExactly(legacyStop);
    }

    @Test
    void daysWithoutAnyNewItemsStillFallBackToLegacy() {
        prepareDetail();
        CourseStopDto legacyStop = new CourseStopDto();
        legacyStop.setDestinationId(3L);
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1)));
        when(structure.findCourseStops(10L)).thenReturn(List.of());
        when(legacy.findCourseStops(10L)).thenReturn(List.of(legacyStop));

        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops())
                .containsExactly(legacyStop);
        verify(legacy).findCourseStops(10L);
    }

    @Test
    void newWriteFailureEscapesServiceAndRollsBackItsSpringTransaction() {
        prepareWrite(List.of(3L, 2L));
        when(legacy.insertCourse(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ((Course) call.getArgument(0)).setId(10L);
            return 1;
        });
        when(structure.insertDay(any())).thenAnswer(call -> {
            ((CourseDay) call.getArgument(0)).setId(50L);
            return 1;
        });
        when(structure.insertCountry(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("country write failed");
        });
        RecordingTransactionManager manager = new RecordingTransactionManager();
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(transactionAdvice(manager));
        CourseService proxy = (CourseService) factory.getProxy();
        CourseCreateRequest request = new CourseCreateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDays(List.of(day(null, 1, 3L, 2L)));

        assertThatThrownBy(() -> proxy.createCourse(request, 5L))
                .isInstanceOf(IllegalStateException.class).hasMessage("country write failed");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verify(legacy, never()).insertCourseDestination(any());
        verify(legacy, never()).deleteCourseDestinations(any());
        verify(structure, times(2)).insertItem(any());
    }

    @Test
    void editItemWriteFailureRollsBackWithoutTouchingLegacyDestinations() {
        prepareWrite(List.of(3L, 2L));
        Course current = new Course();
        current.setId(10L);
        current.setUserId(5L);
        when(legacy.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(legacy.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1)));
        when(structure.updateDay(any())).thenReturn(1);
        when(structure.insertItem(any())).thenReturn(1, 0);
        RecordingTransactionManager manager = new RecordingTransactionManager();
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(transactionAdvice(manager));
        CourseService proxy = (CourseService) factory.getProxy();
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDays(List.of(day(50L, 1, 3L, 2L)));

        assertThatThrownBy(() -> proxy.updateCourse(10L, 5L, request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Item");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verify(legacy, never()).deleteCourseDestinations(any());
        verify(legacy, never()).insertCourseDestination(any());
        verify(structure, never()).deleteCountries(anyLong());
    }

    @Test
    void helperRequiresTheCallingServiceTransaction() {
        ProxyFactory factory = new ProxyFactory(transition);
        factory.addAdvice(transactionAdvice(new RecordingTransactionManager()));
        CourseStructureService proxy = (CourseStructureService) factory.getProxy();
        assertThatThrownBy(() -> proxy.replaceSchedule(10L, 7L, List.of(day(null, 1, 3L, 2L))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(structure);
    }

    @Test
    void missingSnapshotFailsBeforeSavingSchedule() {
        when(structure.findDestinationSnapshots(List.of(3L, 2L))).thenReturn(List.of(snapshot(3L)));
        assertThatThrownBy(() -> transition.replaceSchedule(10L, 7L, List.of(day(null, 1, 3L, 2L))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("snapshot");
        verify(structure, never()).insertItem(any());
        verify(structure, never()).deleteCountries(anyLong());
    }

    @Test
    void foreignDayIdIsRejectedBeforeAnyScheduleMutation() {
        when(structure.findDestinationSnapshots(List.of(3L))).thenReturn(List.of(snapshot(3L)));
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1)));

        assertThatThrownBy(() -> transition.replaceSchedule(10L, 7L,
                List.of(day(999L, 1, 3L))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) error).getStatusCode())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertNoScheduleMutation();
    }

    @Test
    void duplicateRetainedDayIdIsRejectedBeforeAnyScheduleMutation() {
        when(structure.findDestinationSnapshots(List.of(3L, 2L)))
                .thenReturn(List.of(snapshot(3L), snapshot(2L)));
        when(structure.findDays(10L)).thenReturn(List.of(storedDay(50L, 1), storedDay(51L, 2)));

        assertThatThrownBy(() -> transition.replaceSchedule(10L, 7L,
                List.of(day(50L, 1, 3L), day(50L, 2, 2L))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((org.springframework.web.server.ResponseStatusException) error).getStatusCode())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertNoScheduleMutation();
    }

    private void assertNoScheduleMutation() {
        verify(structure, never()).parkDayNumbers(anyLong(), anyInt());
        verify(structure, never()).insertDay(any());
        verify(structure, never()).updateDay(any());
        verify(structure, never()).deleteDay(anyLong(), anyLong());
        verify(structure, never()).deleteDayItems(anyLong());
        verify(structure, never()).insertItem(any());
        verify(structure, never()).deleteCountries(anyLong());
        verify(structure, never()).insertCountry(any());
    }

    @Test
    void softDeleteDoesNotPhysicallyDeleteNewStructure() {
        Course current = new Course();
        current.setId(10L);
        current.setUserId(5L);
        when(legacy.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(legacy.softDeleteCourse(10L, 5L)).thenReturn(1);
        service.deleteCourse(10L, 5L);
        verifyNoInteractions(structure);
    }

    private void prepareDetail() {
        CourseDetailDto detail = new CourseDetailDto();
        detail.setContent("<p>소개</p>");
        when(legacy.incrementViews(10L)).thenReturn(1);
        when(legacy.findCourseDetail(10L, null)).thenReturn(detail);
    }

    private void prepareWrite(List<Long> destinationIds) {
        when(legacy.countExistingDestinations(destinationIds)).thenReturn(destinationIds.size());
        when(legacy.findDestinationCountries(destinationIds))
                .thenReturn(destinationIds.stream().map(this::country).toList());
        // SELECT 행 순서와 무관하게 입력한 방문 순서를 보존한다.
        java.util.ArrayList<CourseDestinationSnapshotDto> snapshots = new java.util.ArrayList<>(
                destinationIds.stream().map(this::snapshot).toList());
        java.util.Collections.reverse(snapshots);
        when(structure.findDestinationSnapshots(destinationIds)).thenReturn(snapshots);
        when(structure.insertItem(any())).thenReturn(1);
        lenient().when(structure.insertCountry(any())).thenReturn(1);
    }

    private void assertScheduleAndCountry(List<Long> destinationIds, List<Long> dayIds) {
        verify(legacy, never()).insertCourseDestination(any());
        verify(legacy, never()).deleteCourseDestinations(any());
        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(structure, times(destinationIds.size())).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getDestinationId)
                .containsExactlyElementsOf(destinationIds);
        assertThat(items.getAllValues()).extracting(CourseItem::getCourseDayId)
                .containsExactlyElementsOf(dayIds);
        java.util.Map<Long, Integer> orderByDay = new java.util.HashMap<>();
        for (CourseItem item : items.getAllValues()) {
            int expectedOrder = orderByDay.merge(item.getCourseDayId(), 1, Integer::sum);
            assertThat(item.getDisplayOrder()).isEqualTo(expectedOrder);
        }
        ArgumentCaptor<CourseCountry> countries = ArgumentCaptor.forClass(CourseCountry.class);
        verify(structure).insertCountry(countries.capture());
        assertThat(countries.getValue().getCourseId()).isEqualTo(10L);
        assertThat(countries.getValue().getCountryId()).isEqualTo(7L);
        assertThat(countries.getValue().getDisplayOrder()).isEqualTo(1);
    }

    private CourseDayForm day(Long id, int dayNumber, Long... destinationIds) {
        CourseDayForm day = new CourseDayForm();
        day.setId(id);
        day.setDayNumber(dayNumber);
        java.util.ArrayList<CourseItemForm> items = new java.util.ArrayList<>();
        for (Long destinationId : destinationIds) {
            CourseItemForm item = new CourseItemForm();
            item.setDestinationId(destinationId);
            item.setDisplayOrder(items.size() + 1);
            items.add(item);
        }
        day.setItems(items);
        return day;
    }

    private CourseDay storedDay(Long id, int dayNumber) {
        CourseDay day = new CourseDay();
        day.setId(id);
        day.setCourseId(10L);
        day.setDayNumber(dayNumber);
        return day;
    }

    private CourseDestinationCountryDto country(Long destinationId) {
        CourseDestinationCountryDto dto = new CourseDestinationCountryDto();
        dto.setDestinationId(destinationId);
        dto.setCountryId(7L);
        return dto;
    }

    private CourseDestinationSnapshotDto snapshot(Long destinationId) {
        CourseDestinationSnapshotDto dto = new CourseDestinationSnapshotDto();
        dto.setDestinationId(destinationId);
        dto.setType(DestinationType.ATTRACTION);
        dto.setPlaceName("등록된 장소 " + destinationId);
        dto.setCountryId(7L);
        dto.setLatitude(new BigDecimal("37.5"));
        dto.setLongitude(new BigDecimal("127"));
        return dto;
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }

    private TransactionInterceptor transactionAdvice(RecordingTransactionManager manager) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        return advice;
    }
}
