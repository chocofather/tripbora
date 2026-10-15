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
        transition = new CourseStructureService(structure);
        service = new CourseServiceImpl(legacy, new PostContentSanitizer(), localization,
                referenceNames, new LocalContentLanguageDetector(), images, transition);
    }

    @Test
    void createsTravelerAndBothStopStructuresInInputOrder() {
        prepareWrite();
        when(legacy.insertCourse(any())).thenAnswer(call -> {
            Course course = call.getArgument(0);
            assertThat(course.getCourseKind()).isEqualTo("TRAVELER");
            assertThat(course.getStatus()).isEqualTo("PUBLISHED");
            course.setId(10L);
            return 1;
        });
        when(structure.insertDay(any())).thenAnswer(call -> {
            CourseDay day = call.getArgument(0);
            assertThat(day.getCourseId()).isEqualTo(10L);
            assertThat(day.getDayNumber()).isEqualTo(1);
            assertThat(day.getTitle()).isNull();
            day.setId(50L);
            return 1;
        });
        CourseCreateRequest request = new CourseCreateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDestinationIds(List.of(3L, 2L));

        assertThat(service.createCourse(request, 5L)).isEqualTo(10L);
        assertBothOrdersAndCountry();
    }

    @Test
    void editKeepsDayIdAndReplacesItemsAndCountryAlongsideLegacy() {
        prepareWrite();
        Course current = new Course();
        current.setId(10L);
        current.setUserId(5L);
        when(legacy.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(legacy.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
        CourseDay day = new CourseDay();
        day.setId(50L);
        when(structure.findDayOne(10L)).thenReturn(day);
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDestinationIds(List.of(3L, 2L));

        service.updateCourse(10L, 5L, request);

        assertBothOrdersAndCountry();
        verify(legacy).deleteCourseDestinations(10L);
        verify(structure, never()).insertDay(any());
        var sequence = inOrder(structure);
        sequence.verify(structure).deleteDayItems(50L);
        sequence.verify(structure, times(2)).insertItem(any());
        sequence.verify(structure).deleteCountries(10L);
        sequence.verify(structure).insertCountry(any());
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
        sources.get(3).setCountryId(null);
        CourseDay day = new CourseDay();
        day.setId(50L);
        when(structure.findDayOne(10L)).thenReturn(day);
        when(structure.findDestinationSnapshots(anyList())).thenReturn(sources);
        when(structure.insertItem(any())).thenReturn(1);
        when(structure.insertCountry(any())).thenReturn(1);

        transition.synchronizeDayOne(10L, 7L, List.of(1L, 2L, 3L, 4L));

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
        assertThat(items.getAllValues().get(3).getCountryId()).isNull();
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
        when(structure.findDayOneStops(10L)).thenReturn(List.of(stop, unlinked));

        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops())
                .containsExactly(stop);
        verify(legacy, never()).findCourseStops(anyLong());
        when(structure.findDayOneStops(10L)).thenReturn(List.of(unlinked));
        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops()).isEmpty();
        verify(legacy, never()).findCourseStops(anyLong());
    }

    @Test
    void missingDayOrItemsFallsBackToLegacy() {
        prepareDetail();
        CourseStopDto legacyStop = new CourseStopDto();
        legacyStop.setDestinationId(3L);
        when(legacy.findCourseStops(10L)).thenReturn(List.of(legacyStop));
        when(structure.findDayOneStops(10L)).thenReturn(List.of());
        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops())
                .containsExactly(legacyStop);
    }

    @Test
    void newWriteFailureEscapesServiceAndRollsBackItsSpringTransaction() {
        prepareWrite();
        when(legacy.insertCourse(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            ((Course) call.getArgument(0)).setId(10L);
            return 1;
        });
        CourseDay day = new CourseDay();
        day.setId(50L);
        when(structure.findDayOne(10L)).thenReturn(day);
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
        request.setDestinationIds(List.of(3L, 2L));

        assertThatThrownBy(() -> proxy.createCourse(request, 5L))
                .isInstanceOf(IllegalStateException.class).hasMessage("country write failed");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verify(legacy, times(2)).insertCourseDestination(any());
        verify(structure, times(2)).insertItem(any());
    }

    @Test
    void editItemWriteFailureRollsBackAfterLegacyReplacement() {
        prepareWrite();
        Course current = new Course();
        current.setId(10L);
        current.setUserId(5L);
        when(legacy.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(legacy.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);
        CourseDay day = new CourseDay();
        day.setId(50L);
        when(structure.findDayOne(10L)).thenReturn(day);
        when(structure.insertItem(any())).thenReturn(1, 0);
        RecordingTransactionManager manager = new RecordingTransactionManager();
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(transactionAdvice(manager));
        CourseService proxy = (CourseService) factory.getProxy();
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setCountryId(7L);
        request.setTitle("서울 여행");
        request.setContent("<p>소개</p>");
        request.setDestinationIds(List.of(3L, 2L));

        assertThatThrownBy(() -> proxy.updateCourse(10L, 5L, request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Item");
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isZero();
        verify(legacy).deleteCourseDestinations(10L);
        verify(legacy, times(2)).insertCourseDestination(any());
        verify(structure, never()).deleteCountries(anyLong());
    }

    @Test
    void helperRequiresTheCallingServiceTransaction() {
        ProxyFactory factory = new ProxyFactory(transition);
        factory.addAdvice(transactionAdvice(new RecordingTransactionManager()));
        CourseStructureService proxy = (CourseStructureService) factory.getProxy();
        assertThatThrownBy(() -> proxy.synchronizeDayOne(10L, 7L, List.of(3L, 2L)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        verifyNoInteractions(structure);
    }

    @Test
    void missingSnapshotFailsInsteadOfSilentlySavingOnlyLegacy() {
        when(structure.findDestinationSnapshots(List.of(3L, 2L))).thenReturn(List.of(snapshot(3L)));
        assertThatThrownBy(() -> transition.synchronizeDayOne(10L, 7L, List.of(3L, 2L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("snapshot");
        verify(structure, never()).insertItem(any());
        verify(structure, never()).deleteCountries(anyLong());
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

    private void prepareWrite() {
        when(legacy.countExistingDestinations(List.of(3L, 2L))).thenReturn(2);
        when(legacy.findDestinationCountries(List.of(3L, 2L))).thenReturn(List.of(country(3L), country(2L)));
        when(legacy.insertCourseDestination(any())).thenReturn(1);
        // Deliberately reversed: input order, not the SELECT's row order, defines both structures.
        when(structure.findDestinationSnapshots(List.of(3L, 2L)))
                .thenReturn(List.of(snapshot(2L), snapshot(3L)));
        when(structure.insertItem(any())).thenReturn(1);
        lenient().when(structure.insertCountry(any())).thenReturn(1);
    }

    private void assertBothOrdersAndCountry() {
        ArgumentCaptor<CourseDestination> oldStops = ArgumentCaptor.forClass(CourseDestination.class);
        verify(legacy, times(2)).insertCourseDestination(oldStops.capture());
        assertThat(oldStops.getAllValues()).extracting(CourseDestination::getDestinationId)
                .containsExactly(3L, 2L);
        ArgumentCaptor<CourseItem> items = ArgumentCaptor.forClass(CourseItem.class);
        verify(structure, times(2)).insertItem(items.capture());
        assertThat(items.getAllValues()).extracting(CourseItem::getDestinationId).containsExactly(3L, 2L);
        assertThat(items.getAllValues()).extracting(CourseItem::getDisplayOrder).containsExactly(1, 2);
        assertThat(items.getAllValues()).extracting(CourseItem::getCourseDayId).containsOnly(50L);
        ArgumentCaptor<CourseCountry> countries = ArgumentCaptor.forClass(CourseCountry.class);
        verify(structure).insertCountry(countries.capture());
        assertThat(countries.getValue().getCourseId()).isEqualTo(10L);
        assertThat(countries.getValue().getCountryId()).isEqualTo(7L);
        assertThat(countries.getValue().getDisplayOrder()).isEqualTo(1);
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
