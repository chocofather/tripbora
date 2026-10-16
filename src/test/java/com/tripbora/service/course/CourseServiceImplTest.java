package com.tripbora.service.course;

import com.tripbora.dto.CourseCreateRequest;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseDestinationCountryDto;
import com.tripbora.dto.CourseDayForm;
import com.tripbora.dto.CourseItemForm;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseUpdateRequest;
import com.tripbora.dto.HomePopularCourseDto;
import com.tripbora.dto.HomePopularCourseStopDto;
import com.tripbora.model.Course;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.repository.category.CategoryMapper;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.category.LocalizedReferenceNameResolver;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class CourseServiceImplTest {

    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private DestinationMapper destinationMapper;
    @Mock
    private CountryCategoryMapper countryCategoryMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private InfoCategoryMapper infoCategoryMapper;

    private CourseServiceImpl service;
    @Mock
    private CourseStructureService courseStructureService;

    @BeforeEach
    void setUp() {
        service = new CourseServiceImpl(courseMapper, new PostContentSanitizer(),
                new DestinationLocalizationService(destinationMapper),
                new ReferenceNameLocalizationService(countryCategoryMapper, categoryMapper,
                        infoCategoryMapper, new LocalizedReferenceNameResolver()),
                new LocalContentLanguageDetector(), destinationImageService, courseStructureService,
                org.mockito.Mockito.mock(CourseCoverStorageService.class),
                org.mockito.Mockito.mock(com.tripbora.service.category.CountryCategoryService.class), org.mockito.Mockito.mock(CourseThemeService.class));
    }

    @Test
    void returnsSanitizedDetailAndStopsAfterIncrementingViews() {
        CourseDetailDto detail = new CourseDetailDto();
        detail.setId(10L);
        detail.setContent("<p onclick=\"alert(1)\">코스 소개</p><script>alert(1)</script>");
        CourseStopDto stop = new CourseStopDto();
        stop.setDestinationId(20L);

        when(courseMapper.incrementViews(10L)).thenReturn(1);
        detail.setBookmarked(true);
        when(courseMapper.findCourseDetail(10L, 5L)).thenReturn(detail);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of(stop));

        detail.setUserId(5L);
        CourseDetailDto result = service.getCourseDetail(10L, 5L, SupportedLanguage.KOREAN);

        assertThat(result.getContent())
                .isEqualTo("<p>코스 소개</p>")
                .doesNotContain("script", "onclick");
        assertThat(result.getStops()).containsExactly(stop);
        assertThat(result.isMyCourse()).isTrue();
        assertThat(result.isBookmarked()).isTrue();
        verify(courseMapper).incrementViews(10L);
        verify(courseMapper).findCourseStops(10L);
    }

    @Test
    void returnsEmptyStopListWithoutFailing() {
        CourseDetailDto detail = new CourseDetailDto();
        detail.setContent("<p>소개</p>");
        when(courseMapper.incrementViews(10L)).thenReturn(1);
        when(courseMapper.findCourseDetail(10L, null)).thenReturn(detail);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of());

        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN).getStops())
                .isEmpty();
    }

    @Test
    void detailOffersTranslationOnlyForAConfirmedForeignTitleOrContent() {
        CourseDetailDto foreign = new CourseDetailDto();
        foreign.setTitleSourceLanguage("ko");
        foreign.setContentSourceLanguage("ja");
        foreign.setContent("<p>紹介</p>");
        when(courseMapper.incrementViews(10L)).thenReturn(1);
        when(courseMapper.findCourseDetail(10L, null)).thenReturn(foreign);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of());

        assertThat(service.getCourseDetail(10L, null, SupportedLanguage.KOREAN)
                .isTranslationAvailable()).isTrue();

        CourseDetailDto undetermined = new CourseDetailDto();
        undetermined.setTitleSourceLanguage("ko");
        undetermined.setContentSourceLanguage("und");
        undetermined.setContent("<p><img src=\"/uploads/a.png\"></p>");
        when(courseMapper.incrementViews(11L)).thenReturn(1);
        when(courseMapper.findCourseDetail(11L, null)).thenReturn(undetermined);
        when(courseMapper.findCourseStops(11L)).thenReturn(List.of());

        assertThat(service.getCourseDetail(11L, null, SupportedLanguage.KOREAN)
                .isTranslationAvailable()).isFalse();
    }

    @Test
    void updateReDetectsOnlyChangedCourseFieldAndPreservesOtherLanguage() {
        Course current = activeCourse(10L, 5L);
        current.setTitleSourceLanguage("ko");
        current.setContentSourceLanguage("ja");
        CourseUpdateRequest request = updateRequest(
                "A completely new English itinerary", current.getContent(), List.of(12L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(courseMapper.countExistingDestinations(List.of(12L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(12L)))
                .thenReturn(destinationCountries(List.of(12L), 7L, "대한민국"));
        when(courseMapper.updateCourse(10L, 5L, 7L,
                request.getTitle(), current.getContent(), "en", "ja")).thenReturn(1);

        service.updateCourse(10L, 5L, request);

        verify(courseMapper).updateCourse(10L, 5L, 7L,
                request.getTitle(), current.getContent(), "en", "ja");
    }

    @Test
    void updateReDetectsChangedContentAndPreservesTitleLanguage() {
        Course current = activeCourse(10L, 5L);
        current.setTitleSourceLanguage("ja");
        current.setContentSourceLanguage("ko");
        String changedContent = "<p>This course has a detailed English description.</p>";
        CourseUpdateRequest request = updateRequest(
                current.getTitle(), changedContent, List.of(12L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(current);
        when(courseMapper.countExistingDestinations(List.of(12L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(12L)))
                .thenReturn(destinationCountries(List.of(12L), 7L, "대한민국"));
        when(courseMapper.updateCourse(10L, 5L, 7L,
                current.getTitle(), changedContent, "ja", "en")).thenReturn(1);

        service.updateCourse(10L, 5L, request);

        verify(courseMapper).updateCourse(10L, 5L, 7L,
                current.getTitle(), changedContent, "ja", "en");
    }

    @Test
    void missingOrDeletedCourseReturnsNotFound() {
        when(courseMapper.incrementViews(10L)).thenReturn(0);

        assertThatThrownBy(() -> service.getCourseDetail(10L, null, SupportedLanguage.KOREAN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(courseMapper, never()).findCourseDetail(10L, null);
        verify(courseMapper, never()).findCourseStops(10L);
    }

    @Test
    void missingDetailAfterIncrementStillReturnsNotFound() {
        when(courseMapper.incrementViews(10L)).thenReturn(1);
        when(courseMapper.findCourseDetail(10L, null)).thenReturn(null);

        assertThatThrownBy(() -> service.getCourseDetail(10L, null, SupportedLanguage.KOREAN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void createsCourseAndPassesScheduleInRequestOrderWithoutLegacyWrites() {
        CourseCreateRequest request = request("  서울 여행  ", "<p>코스 소개</p>", List.of(12L, 7L, 30L));
        when(courseMapper.countExistingDestinations(List.of(12L, 7L, 30L))).thenReturn(3);
        when(courseMapper.findDestinationCountries(List.of(12L, 7L, 30L)))
                .thenReturn(destinationCountries(List.of(12L, 7L, 30L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            assertThat(course.getTitle()).isEqualTo("서울 여행");
            assertThat(course.getContent()).isEqualTo("<p>코스 소개</p>");
            assertThat(course.getUserId()).isEqualTo(5L);
            assertThat(course.getCountryId()).isEqualTo(7L);
            assertThat(course.getTitleSourceLanguage()).isEqualTo("ko");
            assertThat(course.getContentSourceLanguage()).isEqualTo("ko");
            course.setId(100L);
            return 1;
        });

        Long courseId = service.createCourse(request, 5L);

        assertThat(courseId).isEqualTo(100L);
        verify(courseStructureService).replaceSchedule(100L, 7L, request.getDays());
        assertThat(request.getDays().get(0).getItems())
                .extracting(CourseItemForm::getDestinationId)
                .containsExactly(12L, 7L, 30L);
        assertThat(request.getDays().get(0).getItems())
                .extracting(CourseItemForm::getDisplayOrder)
                .containsExactly(1, 2, 3);
        verify(courseMapper, never()).insertCourseDestination(any());
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void imageOnlyContentIsStoredAsUndeterminedLanguage() {
        String imageOnlyContent = "<p><img src=\"/uploads/editor/course-map.png\" width=\"600\"></p>";
        CourseCreateRequest request = request("English city route", imageOnlyContent, List.of(12L));
        when(courseMapper.countExistingDestinations(List.of(12L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(12L)))
                .thenReturn(destinationCountries(List.of(12L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            assertThat(course.getTitleSourceLanguage()).isEqualTo("en");
            assertThat(course.getContentSourceLanguage()).isEqualTo("und");
            course.setId(102L);
            return 1;
        });

        assertThat(service.createCourse(request, 5L)).isEqualTo(102L);
    }

    @Test
    void createsCourseWithImageOnlyQuillContent() {
        String imageOnlyContent = "<p><img src=\"/uploads/editor/course-map.png\" width=\"600\"></p>";
        CourseCreateRequest request = request("이미지 코스", imageOnlyContent, List.of(12L));
        when(courseMapper.countExistingDestinations(List.of(12L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(12L)))
                .thenReturn(destinationCountries(List.of(12L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            assertThat(course.getContent()).isEqualTo(imageOnlyContent);
            course.setId(100L);
            return 1;
        });

        assertThat(service.createCourse(request, 5L)).isEqualTo(100L);
    }

    @Test
    void createsJapaneseCourseWithDestinationsFromDifferentCities() {
        CourseCreateRequest request = request("일본 도시 여행", "<p>도쿄와 오사카</p>", List.of(71L, 72L));
        request.setCountryId(8L);
        when(courseMapper.countExistingDestinations(List.of(71L, 72L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(71L, 72L)))
                .thenReturn(destinationCountries(List.of(71L, 72L), 8L, "일본"));
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            assertThat(course.getCountryId()).isEqualTo(8L);
            course.setId(101L);
            return 1;
        });

        assertThat(service.createCourse(request, 5L)).isEqualTo(101L);

        verify(courseStructureService).replaceSchedule(101L, 8L, request.getDays());
        assertThat(request.getDays().get(0).getItems())
                .extracting(CourseItemForm::getDestinationId)
                .containsExactly(71L, 72L);
    }

    @Test
    void createsDomesticAndJapaneseCourseUsingFirstItemCountryForLegacyColumn() {
        CourseCreateRequest request = request("혼합 코스", "<p>서울과 도쿄</p>", List.of(38L, 71L));
        when(courseMapper.countExistingDestinations(List.of(38L, 71L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(38L, 71L))).thenReturn(List.of(
                destinationCountry(71L, 8L, "일본"),
                destinationCountry(38L, 7L, "대한민국")
        ));

        expectInsertedCountry(7L);
        assertThat(service.createCourse(request, 5L)).isEqualTo(100L);
        verify(courseStructureService).replaceSchedule(100L, 7L, request.getDays());
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void createsCourseWithDifferentOverseasCountries() {
        CourseCreateRequest request = request("해외 혼합 코스", "<p>일본과 프랑스</p>", List.of(71L, 106L));
        when(courseMapper.countExistingDestinations(List.of(71L, 106L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(71L, 106L))).thenReturn(List.of(
                destinationCountry(71L, 8L, "일본"),
                destinationCountry(106L, 17L, "프랑스")
        ));

        expectInsertedCountry(8L);
        assertThat(service.createCourse(request, 5L)).isEqualTo(100L);
        verify(courseStructureService).replaceSchedule(100L, 8L, request.getDays());
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void rejectsDestinationWithoutResolvedCountryBeforeAnyInsert() {
        CourseCreateRequest request = request("국가 미확인", "<p>소개</p>", List.of(1L, 2L));
        when(courseMapper.countExistingDestinations(List.of(1L, 2L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(1L, 2L)))
                .thenReturn(List.of(destinationCountry(1L, 7L, "대한민국")));

        assertBadRequest(request);

        verify(courseMapper, never()).insertCourse(any());
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void createsLinkedCourseWithoutLegacyCountrySelection() {
        CourseCreateRequest request = request("국가 미선택", "<p>소개</p>", List.of(1L));
        request.setCountryId(null);

        when(courseMapper.countExistingDestinations(List.of(1L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(1L)))
                .thenReturn(destinationCountries(List.of(1L), 7L, "대한민국"));
        expectInsertedCountry(7L);
        assertThat(service.createCourse(request, 5L)).isEqualTo(100L);
    }

    @Test
    void ignoresLegacySelectedCountryWhenLinkedCountriesDiffer() {
        CourseCreateRequest request = request("국가 불일치", "<p>소개</p>", List.of(38L, 44L));
        request.setCountryId(8L);
        when(courseMapper.countExistingDestinations(List.of(38L, 44L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(38L, 44L)))
                .thenReturn(destinationCountries(List.of(38L, 44L), 7L, "대한민국"));

        expectInsertedCountry(7L);
        assertThat(service.createCourse(request, 5L)).isEqualTo(100L);
        verify(courseStructureService).replaceSchedule(100L, 7L, request.getDays());
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void rejectsAllInvalidInputBeforeCourseInsert() {
        assertBadRequest(request(" ", "<p>소개</p>", List.of(1L)));
        assertBadRequest(request("가".repeat(256), "<p>소개</p>", List.of(1L)));
        assertBadRequest(request("제목", "<p><br></p>", List.of(1L)));
        assertBadRequest(request("제목", "<p>소개</p>", null));
        assertBadRequest(request("제목", "<p>소개</p>", List.of()));
        assertBadRequest(request("제목", "<p>소개</p>", java.util.Arrays.asList(1L, null)));

        verify(courseMapper, never()).insertCourse(any());
    }

    @Test
    void rejectsNonexistentDestinationBeforeCourseInsert() {
        CourseCreateRequest request = request("제목", "<p>소개</p>", List.of(1L, 2L));
        when(courseMapper.countExistingDestinations(List.of(1L, 2L))).thenReturn(1);

        assertBadRequest(request);

        verify(courseMapper, never()).insertCourse(any());
    }

    @Test
    void failsWhenCourseInsertDoesNotReturnOneOrGeneratedIdIsMissing() {
        CourseCreateRequest request = request("제목", "<p>소개</p>", List.of(1L));
        when(courseMapper.countExistingDestinations(List.of(1L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(1L)))
                .thenReturn(destinationCountries(List.of(1L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenReturn(0);

        assertThatThrownBy(() -> service.createCourse(request, 5L))
                .isInstanceOf(IllegalStateException.class);
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void failsWhenGeneratedCourseIdIsMissing() {
        CourseCreateRequest request = request("제목", "<p>소개</p>", List.of(1L));
        when(courseMapper.countExistingDestinations(List.of(1L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(1L)))
                .thenReturn(destinationCountries(List.of(1L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenReturn(1);

        assertThatThrownBy(() -> service.createCourse(request, 5L))
                .isInstanceOf(IllegalStateException.class);
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void scheduleWriteFailureRaisesRuntimeExceptionForRollback() {
        CourseCreateRequest request = request("제목", "<p>소개</p>", List.of(1L, 2L));
        when(courseMapper.countExistingDestinations(List.of(1L, 2L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(1L, 2L)))
                .thenReturn(destinationCountries(List.of(1L, 2L), 7L, "대한민국"));
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            course.setId(100L);
            return 1;
        });
        doThrow(new IllegalStateException("schedule write failed"))
                .when(courseStructureService).replaceSchedule(100L, 7L, request.getDays());

        assertThatThrownBy(() -> service.createCourse(request, 5L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void editReadUsesUnlockedActiveCourseAndOrderedStops() {
        Course course = activeCourse(10L, 5L);
        CourseStopDto first = new CourseStopDto();
        first.setDestinationId(12L);
        CourseStopDto second = new CourseStopDto();
        second.setDestinationId(7L);
        when(courseMapper.findActiveCourse(10L)).thenReturn(course);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of(first, second));

        var edit = service.getCourseForEdit(10L, 5L, SupportedLanguage.KOREAN);

        assertThat(edit.getCountryId()).isEqualTo(7L);
        assertThat(edit.getCountryName()).isEqualTo("대한민국");
        assertThat(edit.getStops()).containsExactly(first, second);
        verify(courseMapper, never()).findActiveCourseForUpdate(any());
        verify(courseMapper, never()).incrementViews(any());
    }

    @Test
    void editReadReturnsNotFoundOrForbidden() {
        assertThatThrownBy(() -> service.getCourseForEdit(10L, 5L, SupportedLanguage.KOREAN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        when(courseMapper.findActiveCourse(11L)).thenReturn(activeCourse(11L, 9L));
        assertThatThrownBy(() -> service.getCourseForEdit(11L, 5L, SupportedLanguage.KOREAN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void updatesCourseAndReplacesScheduleWithoutLegacyWrites() {
        CourseUpdateRequest request = updateRequest("  수정 제목  ", "<p>수정 소개</p>", List.of(30L, 12L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(30L, 12L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(30L, 12L)))
                .thenReturn(destinationCountries(List.of(30L, 12L), 7L, "대한민국"));
        when(courseMapper.updateCourse(10L, 5L, 7L,
                "수정 제목", "<p>수정 소개</p>", "ko", "ko")).thenReturn(1);

        service.updateCourse(10L, 5L, request);

        verify(courseStructureService).replaceSchedule(10L, 7L, request.getDays());
        assertThat(request.getDays().get(0).getItems()).extracting(CourseItemForm::getDestinationId)
                .containsExactly(30L, 12L);
        assertThat(request.getDays().get(0).getItems()).extracting(CourseItemForm::getDisplayOrder)
                .containsExactly(1, 2);
        verify(courseMapper, never()).deleteCourseDestinations(any());
        verify(courseMapper, never()).insertCourseDestination(any());
        verify(courseMapper).updateCourse(10L, 5L, 7L,
                "수정 제목", "<p>수정 소개</p>", "ko", "ko");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createRejectsRepeatVisitsBeforeAnyWrite(boolean acrossDays) {
        CourseCreateRequest request = request("제목", "<p>소개</p>", List.of(1L, 1L));
        if (acrossDays) {
            request.setDays(repeatedDestinationAcrossDays());
        }

        assertBadRequest(request);

        verify(courseMapper, never()).countExistingDestinations(any());
        verify(courseMapper, never()).findDestinationCountries(any());
        verify(courseMapper, never()).insertCourse(any());
        verify(courseMapper, never()).insertCourseDestination(any());
        verify(courseMapper, never()).deleteCourseDestinations(any());
        verify(courseStructureService, never()).replaceSchedule(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateRejectsRepeatVisitsBeforeLockingOrAnyWrite(boolean acrossDays) {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(1L, 1L));
        if (acrossDays) {
            request.setDays(repeatedDestinationAcrossDays());
        }

        assertThatThrownBy(() -> service.updateCourse(10L, 5L, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(courseMapper, never()).findActiveCourseForUpdate(any());
        verify(courseMapper, never()).countExistingDestinations(any());
        verify(courseMapper, never()).findDestinationCountries(any());
        verify(courseMapper, never()).updateCourse(any(), any(), any(), any(), any(), any(), any());
        verify(courseMapper, never()).deleteCourseDestinations(any());
        verify(courseMapper, never()).insertCourseDestination(any());
        verify(courseStructureService, never()).replaceSchedule(any(), any(), any());
    }

    @Test
    void updatePerformsBasicValidationBeforeLocking() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of());

        assertThatThrownBy(() -> service.updateCourse(10L, 5L, request))
                .isInstanceOf(ResponseStatusException.class);

        verify(courseMapper, never()).findActiveCourseForUpdate(any());
    }

    @Test
    void updateUsesItemCountryWithoutLegacyCountrySelection() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(1L));
        request.setCountryId(null);
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));

        when(courseMapper.countExistingDestinations(List.of(1L))).thenReturn(1);
        when(courseMapper.findDestinationCountries(List.of(1L)))
                .thenReturn(destinationCountries(List.of(1L), 7L, "대한민국"));
        when(courseMapper.updateCourse(10L, 5L, 7L, "제목", "<p>소개</p>", "ko", "ko")).thenReturn(1);
        service.updateCourse(10L, 5L, request);
        verify(courseStructureService).replaceSchedule(10L, 7L, request.getDays());
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void updateRejectsMissingDestinationBeforeCountryValidationOrMutation() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(1L, 2L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(1L, 2L))).thenReturn(1);

        assertThatThrownBy(() -> service.updateCourse(10L, 5L, request))
                .isInstanceOf(ResponseStatusException.class);

        verify(courseMapper, never()).findDestinationCountries(any());
        verify(courseMapper, never()).updateCourse(any(), any(), any(), any(), any(), any(), any());
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void updateInsertFailureRaisesRuntimeExceptionForTransactionalRollback() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(1L, 2L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(1L, 2L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(1L, 2L)))
                .thenReturn(destinationCountries(List.of(1L, 2L), 7L, "대한민국"));
        when(courseMapper.updateCourse(10L, 5L, 7L, "제목", "<p>소개</p>", "ko", "ko"))
                .thenReturn(1);
        doThrow(new IllegalStateException("schedule write failed"))
                .when(courseStructureService).replaceSchedule(10L, 7L, request.getDays());

        assertThatThrownBy(() -> service.updateCourse(10L, 5L, request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateIgnoresLegacyCountryMismatch() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(30L, 12L));
        request.setCountryId(8L);
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(30L, 12L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(30L, 12L)))
                .thenReturn(destinationCountries(List.of(30L, 12L), 7L, "대한민국"));

        when(courseMapper.updateCourse(10L, 5L, 7L, "제목", "<p>소개</p>", "ko", "ko")).thenReturn(1);
        service.updateCourse(10L, 5L, request);
        verify(courseStructureService).replaceSchedule(10L, 7L, request.getDays());
        verify(courseMapper, never()).deleteCourseDestinations(any());
        verify(courseMapper, never()).insertCourseDestination(any());
    }

    @Test
    void updateAcceptsMixedCountriesAndUsesFirstItemCountryForLegacyColumn() {
        CourseUpdateRequest request = updateRequest("제목", "<p>소개</p>", List.of(30L, 71L));
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(30L, 71L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(30L, 71L))).thenReturn(List.of(
                destinationCountry(30L, 7L, "대한민국"),
                destinationCountry(71L, 8L, "일본")
        ));

        when(courseMapper.updateCourse(10L, 5L, 7L, "제목", "<p>소개</p>", "ko", "ko")).thenReturn(1);
        service.updateCourse(10L, 5L, request);
        verify(courseStructureService).replaceSchedule(10L, 7L, request.getDays());
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void updateCanChangeCourseCountryAfterValidatingNewDestinations() {
        CourseUpdateRequest request = updateRequest("일본 코스", "<p>도쿄와 오사카</p>", List.of(71L, 72L));
        request.setCountryId(8L);
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.countExistingDestinations(List.of(71L, 72L))).thenReturn(2);
        when(courseMapper.findDestinationCountries(List.of(71L, 72L)))
                .thenReturn(destinationCountries(List.of(71L, 72L), 8L, "일본"));
        when(courseMapper.updateCourse(10L, 5L, 8L,
                "일본 코스", "<p>도쿄와 오사카</p>", "ko", "ko")).thenReturn(1);

        service.updateCourse(10L, 5L, request);

        verify(courseMapper).updateCourse(10L, 5L, 8L,
                "일본 코스", "<p>도쿄와 오사카</p>", "ko", "ko");
        verify(courseStructureService).replaceSchedule(10L, 8L, request.getDays());
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void softDeleteLocksOwnedCourseAndDoesNotDeleteDestinations() {
        when(courseMapper.findActiveCourseForUpdate(10L)).thenReturn(activeCourse(10L, 5L));
        when(courseMapper.softDeleteCourse(10L, 5L)).thenReturn(1);

        service.deleteCourse(10L, 5L);

        verify(courseMapper).softDeleteCourse(10L, 5L);
        verify(courseMapper, never()).deleteCourseDestinations(any());
    }

    @Test
    void homePopularCoursesUseAtMostThreeCoursesAndThreeOrderedPreviewStops() {
        HomePopularCourseDto first = homeCourse(10L, 5);
        first.setRepresentativeImageUrl("/images/gyeongbokgung.jpg");
        HomePopularCourseDto second = homeCourse(20L, 2);
        second.setRepresentativeImageUrl("/images/haeundae.jpg");
        HomePopularCourseDto third = homeCourse(30L, 1);
        HomePopularCourseDto unexpectedFourth = homeCourse(40L, 1);
        when(courseMapper.findPopularCourses(3))
                .thenReturn(List.of(first, second, third, unexpectedFourth));
        when(courseMapper.findPopularCourseStops(List.of(10L, 20L, 30L))).thenReturn(List.of(
                homeStop(10L, 1, "경복궁", "/images/gyeongbokgung.jpg"),
                homeStop(10L, 2, "북촌한옥마을", null),
                homeStop(10L, 3, "창덕궁", "/images/changdeokgung.jpg"),
                homeStop(10L, 4, "익선동", "/images/ikseondong.jpg"),
                homeStop(10L, 5, "광화문", "/images/gwanghwamun.jpg"),
                homeStop(20L, 1, "해운대", "/images/haeundae.jpg"),
                homeStop(20L, 2, "광안리", "/images/gwangalli.jpg"),
                homeStop(30L, 1, "이미지 없는 여행지", "   ")
        ));

        List<HomePopularCourseDto> result =
                service.getPopularCoursesForHome(SupportedLanguage.KOREAN);

        assertThat(result).containsExactly(first, second, third);
        assertThat(first.getPreviewDestinationNames())
                .containsExactly("경복궁", "북촌한옥마을", "창덕궁");
        assertThat(first.getRoutePreview()).isEqualTo("경복궁 → 북촌한옥마을 → 창덕궁");
        assertThat(first.getRemainingDestinationCount()).isEqualTo(2);
        assertThat(first.getPreviewImageUrls()).containsExactly(
                "/images/gyeongbokgung.jpg",
                "/images/changdeokgung.jpg",
                "/images/ikseondong.jpg");
        assertThat(second.getPreviewDestinationNames()).containsExactly("해운대", "광안리");
        assertThat(second.getRemainingDestinationCount()).isZero();
        assertThat(second.getPreviewImageUrls())
                .containsExactly("/images/haeundae.jpg", "/images/gwangalli.jpg");
        assertThat(third.getPreviewImageUrls()).containsExactly("/images/default.png");
        verify(courseMapper).findPopularCourseStops(List.of(10L, 20L, 30L));
    }

    @Test
    void homePopularCourseUsesCoverBeforeStopPreviewImages() {
        HomePopularCourseDto course = homeCourse(10L, 2);
        course.setRepresentativeImageUrl("/uploads/course-cover.jpg");
        when(courseMapper.findPopularCourses(3)).thenReturn(List.of(course));
        when(courseMapper.findPopularCourseStops(List.of(10L))).thenReturn(List.of(
                homeStop(10L, 1, "첫 장소", "/images/first.jpg"),
                homeStop(10L, 2, "다음 장소", "/images/second.jpg")));

        service.getPopularCoursesForHome(SupportedLanguage.KOREAN);

        assertThat(course.getPreviewImageUrls()).containsExactly(
                "/uploads/course-cover.jpg", "/images/first.jpg", "/images/second.jpg");
    }

    @Test
    void homePopularCourseWithNoFirstStopImageKeepsDefaultDespiteLaterImages() {
        HomePopularCourseDto missing = homeCourse(10L, 2);
        HomePopularCourseDto blank = homeCourse(20L, 2);
        blank.setRepresentativeImageUrl("   ");
        when(courseMapper.findPopularCourses(3)).thenReturn(List.of(missing, blank));
        when(courseMapper.findPopularCourseStops(List.of(10L, 20L))).thenReturn(List.of(
                homeStop(10L, 1, "사진 없는 첫 장소", null),
                homeStop(10L, 2, "사진 있는 다음 장소", "/images/later.jpg"),
                homeStop(20L, 1, "사진 없는 첫 장소", "   "),
                homeStop(20L, 2, "사진 있는 다음 장소", "/images/another-later.jpg")));

        service.getPopularCoursesForHome(SupportedLanguage.KOREAN);

        assertThat(missing.getPreviewImageUrls()).containsExactly("/images/default.png");
        assertThat(blank.getPreviewImageUrls()).containsExactly("/images/default.png");
    }

    @Test
    void emptyHomePopularCoursesDoNotRunStopQuery() {
        when(courseMapper.findPopularCourses(3)).thenReturn(List.of());

        assertThat(service.getPopularCoursesForHome(SupportedLanguage.KOREAN)).isEmpty();

        verify(courseMapper, never()).findPopularCourseStops(any());
    }

    private Course activeCourse(Long id, Long userId) {
        Course course = new Course();
        course.setId(id);
        course.setUserId(userId);
        course.setCountryId(7L);
        course.setCountryName("대한민국");
        course.setTitle("기존 제목");
        course.setContent("<p>기존 소개</p>");
        return course;
    }

    private HomePopularCourseDto homeCourse(Long id, int totalDestinationCount) {
        HomePopularCourseDto course = new HomePopularCourseDto();
        course.setCourseId(id);
        course.setTotalDestinationCount(totalDestinationCount);
        return course;
    }

    private HomePopularCourseStopDto homeStop(Long courseId, int visitOrder, String name) {
        return homeStop(courseId, visitOrder, name, null);
    }

    private HomePopularCourseStopDto homeStop(Long courseId, int visitOrder, String name,
                                              String imageUrl) {
        HomePopularCourseStopDto stop = new HomePopularCourseStopDto();
        stop.setCourseId(courseId);
        stop.setVisitOrder(visitOrder);
        stop.setDestinationName(name);
        stop.setImageUrl(imageUrl);
        return stop;
    }

    private CourseUpdateRequest updateRequest(String title, String content, List<Long> destinationIds) {
        CourseUpdateRequest request = new CourseUpdateRequest();
        request.setCountryId(7L);
        request.setTitle(title);
        request.setContent(content);
        request.setDays(days(destinationIds));
        return request;
    }

    private List<CourseDestinationCountryDto> destinationCountries(
            List<Long> destinationIds, Long countryId, String countryName) {
        return destinationIds.stream()
                .map(destinationId -> destinationCountry(destinationId, countryId, countryName))
                .toList();
    }

    private void expectInsertedCountry(Long countryId) {
        when(courseMapper.insertCourse(any(Course.class))).thenAnswer(call -> {
            Course course = call.getArgument(0);
            assertThat(course.getCountryId()).isEqualTo(countryId);
            course.setId(100L);
            return 1;
        });
    }

    private CourseDestinationCountryDto destinationCountry(
            Long destinationId, Long countryId, String countryName) {
        CourseDestinationCountryDto destination = new CourseDestinationCountryDto();
        destination.setDestinationId(destinationId);
        destination.setCountryId(countryId);
        destination.setCountryName(countryName);
        return destination;
    }

    private CourseCreateRequest request(String title, String content, List<Long> destinationIds) {
        CourseCreateRequest request = new CourseCreateRequest();
        request.setCountryId(7L);
        request.setTitle(title);
        request.setContent(content);
        request.setDays(days(destinationIds));
        return request;
    }

    private List<CourseDayForm> repeatedDestinationAcrossDays() {
        CourseDayForm first = days(List.of(1L)).get(0);
        CourseDayForm second = days(List.of(1L)).get(0);
        second.setDayNumber(2);
        return List.of(first, second);
    }

    private List<CourseDayForm> days(List<Long> destinationIds) {
        if (destinationIds == null) {
            return null;
        }
        CourseDayForm day = new CourseDayForm();
        day.setDayNumber(1);
        java.util.ArrayList<CourseItemForm> items = new java.util.ArrayList<>();
        for (Long destinationId : destinationIds) {
            CourseItemForm item = new CourseItemForm();
            item.setDestinationId(destinationId);
            item.setDisplayOrder(items.size() + 1);
            items.add(item);
        }
        day.setItems(items);
        return List.of(day);
    }

    private void assertBadRequest(CourseCreateRequest request) {
        assertThatThrownBy(() -> service.createCourse(request, 5L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
