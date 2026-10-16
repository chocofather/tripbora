package com.tripbora.controller.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseCreateRequest;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseUpdateRequest;
import com.tripbora.model.CountryCategory;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseControllerTest {

    @Mock
    private CourseService courseService;
    @Mock
    private CountryCategoryService countryCategoryService;
    @Mock
    private CustomUserDetails userDetails;

    @Test
    void createUsesAuthenticatedUserAndRedirectsToNewDetail() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CourseCreateRequest request = new CourseCreateRequest();
        when(userDetails.getId()).thenReturn(5L);
        when(courseService.createCourse(request, 5L)).thenReturn(100L);

        String view = controller.submitCourse(request,
                new BeanPropertyBindingResult(request, "request"), userDetails, new ExtendedModelMap());

        verify(courseService).createCourse(request, 5L);
        assertThat(view).isEqualTo("redirect:/course/100");
    }

    @Test
    void updateUsesAuthenticatedUserAndRedirectsToDetail() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CourseUpdateRequest request = new CourseUpdateRequest();
        when(userDetails.getId()).thenReturn(5L);

        String view = controller.updateCourse(100L, request,
                new BeanPropertyBindingResult(request, "request"), userDetails, new ExtendedModelMap());

        verify(courseService).updateCourse(100L, 5L, request);
        assertThat(view).isEqualTo("redirect:/course/100");
    }

    @Test
    void deleteUsesAuthenticatedUserAndRedirectsToUnifiedBoard() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        when(userDetails.getId()).thenReturn(5L);

        String view = controller.deleteCourse(100L, userDetails);

        verify(courseService).deleteCourse(100L, 5L);
        assertThat(view).isEqualTo("redirect:/board/list");
    }

    @Test
    void writePageUsesLocalizedCourseCountryOptionsFromService() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CountryCategory korea = country(7L, "대한민국", null);
        CountryCategory japan = country(8L, "일본", 1L);
        when(courseService.getCourseCountries(SupportedLanguage.KOREAN)).thenReturn(List.of(korea, japan));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.courseWritePage(model);

        assertThat(view).isEqualTo("course/write");
        assertThat(model.get("courseCountries")).isEqualTo(List.of(korea, japan));
    }

    @Test
    void editPageLoadsExistingCourseAndTheSameCountryOptionsAsCreate() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CourseEditDto course = new CourseEditDto();
        course.setId(100L);
        course.setCountryId(8L);
        course.setCountryName("일본");
        CountryCategory korea = country(7L, "대한민국", null);
        CountryCategory japan = country(8L, "일본", 1L);
        when(userDetails.getId()).thenReturn(5L);
        when(courseService.getCourseForEdit(eq(100L), eq(5L), any())).thenReturn(course);
        when(courseService.getCourseCountries(SupportedLanguage.KOREAN)).thenReturn(List.of(korea, japan));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.courseEditPage(100L, userDetails, model);

        assertThat(view).isEqualTo("course/edit");
        assertThat(model.get("course")).isSameAs(course);
        assertThat(model.get("courseCountries")).isEqualTo(List.of(korea, japan));
    }

    @Test
    void publicCourseDetailAddsTouristTripJsonLdWithOrderedStops() throws Exception {
        CourseDetailDto course = new CourseDetailDto();
        course.setId(100L);
        course.setTitle("서울 궁궐 산책");
        course.setContent("<p>두 궁궐을 차례로 걷는 코스입니다.</p>");
        course.setStops(List.of(stop(21L, 1, "경복궁"), stop(22L, 2, "창덕궁")));
        when(courseService.getCourseDetail(eq(100L), eq(null), any())).thenReturn(course);
        ExtendedModelMap model = new ExtendedModelMap();
        model.addAttribute("seoSiteBaseUrl", "https://travel.example");
        CourseController controller = new CourseController(courseService, countryCategoryService);

        controller.courseDetail(100L, null, model);

        JsonNode trip = new ObjectMapper().readTree((String) model.get("seoJsonLd"))
                .path("@graph").get(0);
        assertThat(trip.path("@type").asText()).isEqualTo("TouristTrip");
        JsonNode items = trip.path("itinerary").path("itemListElement");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("position").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("item").path("name").asText()).isEqualTo("경복궁");
        assertThat(items.get(0).path("item").path("url").asText())
                .isEqualTo("https://travel.example/destinations/21");
        assertThat(trip.path("url").asText())
                .isEqualTo("https://travel.example/course/100");
        assertThat(trip.has("datePublished")).isFalse();
    }

    private CountryCategory country(Long id, String name, Long parentId) {
        CountryCategory country = new CountryCategory();
        country.setId(id);
        country.setRegionName(name);
        country.setParentId(parentId);
        return country;
    }

    @Test
    void validationErrorRedisplaysTheSubmittedScheduleWithMessage() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CourseCreateRequest request = new CourseCreateRequest();
        request.setTitle("작성 중 제목");
        when(userDetails.getId()).thenReturn(5L);
        when(courseService.createCourse(request, 5L))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Day 2에 장소를 추가해 주세요."));
        CourseEditDto retry = new CourseEditDto();
        retry.setTitle(request.getTitle());
        when(courseService.prepareFormForRetry(request.getTitle(), null, null, null)).thenReturn(retry);
        when(courseService.getCourseCountries(SupportedLanguage.KOREAN)).thenReturn(List.of());
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.submitCourse(request, new BeanPropertyBindingResult(request, "request"),
                userDetails, model)).isEqualTo("course/write");
        assertThat(model.get("course")).isSameAs(retry);
        assertThat(model.get("formError")).isEqualTo("Day 2에 장소를 추가해 주세요.");
    }

    @Test
    void nestedBindingKeepsLastAllowedDayAndRejectsProtectedFields() {
        CourseController controller = new CourseController(courseService, countryCategoryService);
        CourseCreateRequest request = new CourseCreateRequest();
        WebDataBinder binder = new WebDataBinder(request);
        controller.configureCourseBinding(binder);
        MutablePropertyValues values = new MutablePropertyValues();
        values.add("days[89].dayNumber", "90");
        values.add("days[89].items[49].destinationId", "12");
        values.add("days[89].items[49].displayOrder", "50");
        values.add("days[89].items[49].memo", "마지막 방문 메모");
        values.add("days[89].items[49].startTime", "09:00");
        values.add("days[89].items[49].endTime", "10:30");
        values.add("coverImageUrl", "/uploads/course/cover/99/forged.jpg");
        values.add("days[89].items[49].placeName", "가짜 장소");
        values.add("days[89].items[49].countryId", "999");
        binder.bind(values);
        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(request.getDays()).hasSize(90);
        assertThat(request.getDays().get(89).getItems()).hasSize(50);
        assertThat(request.getDays().get(89).getItems().get(49).getDestinationId()).isEqualTo(12L);
        assertThat(request.getDays().get(89).getItems().get(49).getStartTime()).isEqualTo("09:00");
        assertThat(request.getDays().get(89).getItems().get(49).getEndTime()).isNull();
        assertThat(request.getDays().get(89).getItems().get(49).getPlaceName()).isEqualTo("가짜 장소");
        assertThat(request.getDays().get(89).getItems().get(49).getCountryId()).isEqualTo(999L);
        assertThat(binder.getBindingResult().getSuppressedFields())
                .containsExactlyInAnyOrder("days[89].items[49].endTime", "coverImageUrl");
    }

    private CourseStopDto stop(Long destinationId, int visitOrder, String name) {
        CourseStopDto stop = new CourseStopDto();
        stop.setDestinationId(destinationId);
        stop.setVisitOrder(visitOrder);
        stop.setName(name);
        return stop;
    }
}
