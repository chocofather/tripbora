package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.HomePopularCourseDto;
import com.tripbora.dto.HomePopularCourseStopDto;
import com.tripbora.model.Course;
import com.tripbora.model.CountryCategoryTranslation;
import com.tripbora.model.DestinationTranslation;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 코스 화면의 STOP 이름만 요청 언어로 바뀌는지 본다.
 *
 * <p>코스 제목·소개·작성자와 여행지가 없는 STOP 은 적힌 그대로 남아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class CourseStopLocalizationTest {

    @Mock private CourseMapper courseMapper;
    @Mock private DestinationImageService destinationImageService;
    @Mock private DestinationMapper destinationMapper;
    @Mock private CountryCategoryMapper countryCategoryMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private InfoCategoryMapper infoCategoryMapper;

    private CourseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CourseServiceImpl(courseMapper, new PostContentSanitizer(),
                new DestinationLocalizationService(destinationMapper),
                new ReferenceNameLocalizationService(countryCategoryMapper, categoryMapper,
                        infoCategoryMapper, new LocalizedReferenceNameResolver()),
                new LocalContentLanguageDetector(), destinationImageService,
                org.mockito.Mockito.mock(CourseStructureService.class),
                org.mockito.Mockito.mock(CourseCoverStorageService.class),
                org.mockito.Mockito.mock(com.tripbora.service.category.CountryCategoryService.class), org.mockito.Mockito.mock(CourseThemeService.class));
    }

    @Test
    void detailShowsTranslatedStopNamesInVisitOrder() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "ko", "경복궁"),
                        translation(2L, 15L, "en", "Gyeongbokgung Palace"),
                        translation(3L, 16L, "ko", "북촌한옥마을"),
                        translation(4L, 16L, "en", "Bukchon Hanok Village"),
                        translation(5L, 17L, "ko", "창덕궁"),
                        translation(6L, 17L, "en", "Changdeokgung Palace")));

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.ENGLISH);

        assertThat(detail.getStops())
                .extracting(CourseStopDto::getName)
                .containsExactly("Gyeongbokgung Palace", "Bukchon Hanok Village",
                        "Changdeokgung Palace");
        assertThat(detail.getStops())
                .extracting(CourseStopDto::getVisitOrder)
                .containsExactly(1, 2, 3);
        assertThat(detail.getStops())
                .extracting(CourseStopDto::getDestinationId)
                .containsExactly(15L, 16L, 17L);
    }

    @Test
    void stopNamesAreReadInOneBatchForTheWholeCourse() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(any()))
                .thenReturn(List.of(translation(1L, 15L, "en", "Gyeongbokgung Palace")));

        service.getCourseDetail(10L, null, SupportedLanguage.ENGLISH);

        // STOP 이 셋이어도 번역 조회는 한 번, 여행지 번호를 모아 보낸다.
        verify(destinationMapper, times(1))
                .findTranslationsByDestinationIds(List.of(15L, 16L, 17L));
        verify(destinationMapper, never()).findTranslationsByDestinationId(anyLong());
    }

    @Test
    void stopWithoutRequestedLanguageFallsBackToKorean() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "ko", "경복궁"),
                        translation(2L, 15L, "en", "Gyeongbokgung Palace"),
                        translation(3L, 16L, "ko", "북촌한옥마을"),
                        // 창덕궁(17)은 요청 언어도 한국어도 없어 남은 번역 중 첫 언어를 쓴다.
                        translation(4L, 17L, "zh-CN", "昌德宫"),
                        translation(5L, 17L, "en", "Changdeokgung Palace")));

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.JAPANESE);

        assertThat(detail.getStops())
                .extracting(CourseStopDto::getName)
                .containsExactly("경복궁", "북촌한옥마을", "Changdeokgung Palace");
    }

    @Test
    void stopWithoutAnyTranslationKeepsTheNameFromTheCourseQuery() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of());

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.ENGLISH);

        assertThat(detail.getStops())
                .extracting(CourseStopDto::getName)
                .containsExactly("경복궁", "북촌한옥마을", "창덕궁");
    }

    @Test
    void stopWithoutADestinationKeepsItsOwnTextAndIsNotLookedUp() {
        CourseDetailDto detail = new CourseDetailDto();
        detail.setId(10L);
        detail.setTitle("서울 하루 고궁 산책");
        detail.setContent("<p>코스 소개</p>");
        detail.setNickname("여행자민준");
        when(courseMapper.incrementViews(10L)).thenReturn(1);
        when(courseMapper.findCourseDetail(10L, null)).thenReturn(detail);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of(
                stop(100L, 15L, 1, "경복궁"),
                stop(101L, null, 2, "친구가 알려준 골목 카페")));
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L)))
                .thenReturn(List.of(translation(1L, 15L, "en", "Gyeongbokgung Palace")));

        CourseDetailDto result = service.getCourseDetail(10L, null, SupportedLanguage.ENGLISH);

        assertThat(result.getStops())
                .extracting(CourseStopDto::getName)
                .containsExactly("Gyeongbokgung Palace", "친구가 알려준 골목 카페");
        // 코스 글은 작성자가 쓴 그대로다.
        assertThat(result.getTitle()).isEqualTo("서울 하루 고궁 산책");
        assertThat(result.getContent()).isEqualTo("<p>코스 소개</p>");
        assertThat(result.getNickname()).isEqualTo("여행자민준");
    }

    @Test
    void stopRegionNamesUseTheRequestedLanguageAndAreReadInOneBatch() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of());
        when(countryCategoryMapper.findTranslationsByCountryCategoryIds(List.of(235L, 236L)))
                .thenReturn(List.of(
                        regionTranslation(1L, 235L, "en", "Jongno-gu"),
                        regionTranslation(2L, 236L, "en", "Jung-gu")));

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.ENGLISH);

        assertThat(detail.getStops())
                .extracting(CourseStopDto::getRegionName)
                .containsExactly("Jongno-gu", "Jung-gu", "Jongno-gu");
        // 지역이 겹쳐도 번역 조회는 한 번, 지역 번호를 모아 보낸다.
        verify(countryCategoryMapper, times(1))
                .findTranslationsByCountryCategoryIds(List.of(235L, 236L));
    }

    @Test
    void stopRegionWithoutTranslationKeepsTheStoredRegionName() {
        givenCourseDetailWithThreeStops();
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of());
        when(countryCategoryMapper.findTranslationsByCountryCategoryIds(List.of(235L, 236L)))
                .thenReturn(List.of());

        CourseDetailDto detail = service.getCourseDetail(10L, null, SupportedLanguage.JAPANESE);

        assertThat(detail.getStops())
                .extracting(CourseStopDto::getRegionName)
                .containsExactly("종로구", "중구", "종로구");
    }

    @Test
    void editStopsUseTheRequestedLanguageWithoutTouchingTheCourseText() {
        Course course = new Course();
        course.setId(10L);
        course.setUserId(5L);
        course.setTitle("서울 하루 고궁 산책");
        course.setContent("<p>코스 소개</p>");
        when(courseMapper.findActiveCourse(10L)).thenReturn(course);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of(
                stop(100L, 15L, 1, "경복궁"),
                stop(101L, 16L, 2, "북촌한옥마을")));
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "en", "Gyeongbokgung Palace"),
                        translation(2L, 16L, "en", "Bukchon Hanok Village")));

        var edit = service.getCourseForEdit(10L, 5L, SupportedLanguage.ENGLISH);

        assertThat(edit.getStops())
                .extracting(CourseStopDto::getName)
                .containsExactly("Gyeongbokgung Palace", "Bukchon Hanok Village");
        assertThat(edit.getTitle()).isEqualTo("서울 하루 고궁 산책");
    }

    @Test
    void homeRoutePreviewUsesTranslatedNamesInOneBatch() {
        HomePopularCourseDto course = new HomePopularCourseDto();
        course.setCourseId(10L);
        course.setTitle("서울 하루 고궁 산책");
        course.setTotalDestinationCount(5);
        when(courseMapper.findPopularCourses(3)).thenReturn(List.of(course));
        when(courseMapper.findPopularCourseStops(List.of(10L))).thenReturn(List.of(
                homeStop(10L, 15L, 1, "경복궁"),
                homeStop(10L, 16L, 2, "북촌한옥마을"),
                homeStop(10L, 17L, 3, "창덕궁"),
                // 미리보기 밖 STOP 은 번역을 읽지 않는다.
                homeStop(10L, 18L, 4, "익선동")));
        when(destinationMapper.findTranslationsByDestinationIds(List.of(15L, 16L, 17L)))
                .thenReturn(List.of(
                        translation(1L, 15L, "en", "Gyeongbokgung Palace"),
                        translation(2L, 16L, "en", "Bukchon Hanok Village"),
                        translation(3L, 17L, "ko", "창덕궁")));

        List<HomePopularCourseDto> result =
                service.getPopularCoursesForHome(SupportedLanguage.ENGLISH);

        assertThat(result.get(0).getRoutePreview())
                .isEqualTo("Gyeongbokgung Palace → Bukchon Hanok Village → 창덕궁");
        assertThat(result.get(0).getRemainingDestinationCount()).isEqualTo(2);
        verify(destinationMapper, times(1)).findTranslationsByDestinationIds(any());
    }

    private void givenCourseDetailWithThreeStops() {
        CourseDetailDto detail = new CourseDetailDto();
        detail.setId(10L);
        detail.setContent("<p>코스 소개</p>");
        when(courseMapper.incrementViews(10L)).thenReturn(1);
        when(courseMapper.findCourseDetail(10L, null)).thenReturn(detail);
        when(courseMapper.findCourseStops(10L)).thenReturn(List.of(
                stop(100L, 15L, 1, "경복궁", 235L, "종로구"),
                stop(101L, 16L, 2, "북촌한옥마을", 236L, "중구"),
                stop(102L, 17L, 3, "창덕궁", 235L, "종로구")));
    }

    private CourseStopDto stop(Long courseDestinationId, Long destinationId,
                               int visitOrder, String name) {
        return stop(courseDestinationId, destinationId, visitOrder, name, null, null);
    }

    private CourseStopDto stop(Long courseDestinationId, Long destinationId,
                               int visitOrder, String name,
                               Long regionId, String regionName) {
        CourseStopDto stop = new CourseStopDto();
        stop.setCourseDestinationId(courseDestinationId);
        stop.setDestinationId(destinationId);
        stop.setVisitOrder(visitOrder);
        stop.setName(name);
        stop.setRegionId(regionId);
        stop.setRegionName(regionName);
        return stop;
    }

    private CountryCategoryTranslation regionTranslation(Long id, Long countryCategoryId,
                                                         String languageCode, String name) {
        CountryCategoryTranslation translation = new CountryCategoryTranslation();
        translation.setId(id);
        translation.setCountryCategoryId(countryCategoryId);
        translation.setLanguageCode(languageCode);
        translation.setName(name);
        return translation;
    }

    private HomePopularCourseStopDto homeStop(Long courseId, Long destinationId,
                                              int visitOrder, String name) {
        HomePopularCourseStopDto stop = new HomePopularCourseStopDto();
        stop.setCourseId(courseId);
        stop.setDestinationId(destinationId);
        stop.setVisitOrder(visitOrder);
        stop.setDestinationName(name);
        return stop;
    }

    private DestinationTranslation translation(Long id, Long destinationId,
                                               String languageCode, String name) {
        DestinationTranslation translation = new DestinationTranslation();
        translation.setId(id);
        translation.setDestinationId(destinationId);
        translation.setLanguageCode(languageCode);
        translation.setName(name);
        return translation;
    }
}
