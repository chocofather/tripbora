package com.tripbora.controller.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseCreateRequest;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseUpdateRequest;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.seo.SeoModel;
import com.tripbora.seo.SeoStructuredData;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/course")
public class CourseController {

    private final CourseService courseService;
    private final CountryCategoryService countryCategoryService;

    @InitBinder
    public void configureCourseBinding(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(90);
        binder.setAllowedFields("title", "content", "countryId", "coverImage", "removeCoverImage", "days[*].id", "days[*].dayNumber",
                "days[*].title", "days[*].items[*].destinationId", "days[*].items[*].displayOrder",
                "days[*].items[*].itemType", "days[*].items[*].countryId", "days[*].items[*].placeName", "days[*].items[*].address",
                "days[*].items[*].latitude", "days[*].items[*].longitude",
                "days[*].items[*].startTime", "days[*].items[*].memo");
    }

    @ExceptionHandler(InvalidPropertyException.class)
    @ResponseBody
    public ResponseEntity<String> invalidScheduleIndex() {
        return ResponseEntity.badRequest().body("일정 입력 범위를 확인해 주세요. Day는 최대 90일, 하루 장소는 최대 50곳입니다.");
    }

    @GetMapping("/{id}")
    public String courseDetail(@PathVariable Long id,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        Long currentUserId = principal == null ? null : principal.getId();
        CourseDetailDto course = courseService.getCourseDetail(
                id, currentUserId, requestedLanguage());
        model.addAttribute("course", course);
        String seoImage = course.getRepresentativeImageUrl();
        SeoModel.apply(model, course.getTitle() + " | TripBora", course.getContent(),
                "/course/" + id, seoImage, "article");
        List<SeoStructuredData.ItineraryStop> itinerary = course.getStops() == null
                ? List.of()
                : course.getStops().stream()
                .map(stop -> new SeoStructuredData.ItineraryStop(
                        stop.getName(), stop.getDestinationId()))
                .toList();
        SeoStructuredData.touristTrip(model, "/course/" + id,
                course.getTitle(), course.getContent(), seoImage, itinerary);
        return "course/detail";
    }

    @GetMapping("/{id}/edit")
    public String courseEditPage(@PathVariable Long id,
                                 @AuthenticationPrincipal CustomUserDetails principal,
                                 Model model) {
        model.addAttribute("course",
                courseService.getCourseForEdit(id, principal.getId(), requestedLanguage()));
        addCourseCountries(model);
        return "course/edit";
    }

    @PostMapping("/{id}/edit")
    public String updateCourse(@PathVariable Long id,
                               @ModelAttribute CourseUpdateRequest request,
                               BindingResult binding,
                               @AuthenticationPrincipal CustomUserDetails principal,
                               Model model) {
        if (binding.hasErrors()) return retryForm(id, request.getTitle(), request.getContent(),
                request.getCountryId(), request.getDays(), "입력한 일정 형식을 확인해 주세요.", principal.getId(), request.isRemoveCoverImage(), model);
        try {
            courseService.updateCourse(id, principal.getId(), request);
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.BAD_REQUEST) throw error;
            return retryForm(id, request.getTitle(), request.getContent(), request.getCountryId(),
                    request.getDays(), error.getReason(), principal.getId(), request.isRemoveCoverImage(), model);
        }
        return "redirect:/course/" + id;
    }

    @PostMapping("/{id}/delete")
    public String deleteCourse(@PathVariable Long id,
                               @AuthenticationPrincipal CustomUserDetails principal) {
        courseService.deleteCourse(id, principal.getId());
        return "redirect:/board/list";
    }

    // 글쓰기 폼 페이지 (GET)
    @GetMapping("/write")
    public String courseWritePage(Model model) {
        addCourseCountries(model);
        return "course/write";
    }

    /** 화면에 쓸 언어. 코스 STOP 이름만 이 언어로 내려간다. */
    private SupportedLanguage requestedLanguage() {
        return SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
    }

    private void addCourseCountries(Model model) {
        List<CountryCategory> countries = courseService.getCourseCountries(requestedLanguage());
        model.addAttribute("courseCountries", countries);
        model.addAttribute("domesticCourseCountries", countries.stream()
                .filter(country -> country.getParentId() == null)
                .toList());
        model.addAttribute("overseasCourseCountries", countries.stream()
                .filter(country -> country.getParentId() != null)
                .toList());
    }

    // 글쓰기 저장 (POST)
    @PostMapping("/write")
    public String submitCourse(
            @ModelAttribute CourseCreateRequest request,
            BindingResult binding,
            @AuthenticationPrincipal CustomUserDetails principal,
            Model model
    ) {
        if (binding.hasErrors()) return retryForm(null, request.getTitle(), request.getContent(),
                request.getCountryId(), request.getDays(), "입력한 일정 형식을 확인해 주세요.", principal.getId(), false, model);
        Long courseId;
        try {
            courseId = courseService.createCourse(request, principal.getId());
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.BAD_REQUEST) throw error;
            return retryForm(null, request.getTitle(), request.getContent(), request.getCountryId(),
                    request.getDays(), error.getReason(), principal.getId(), false, model);
        }
        return "redirect:/course/" + courseId;
    }

    private String retryForm(Long id, String title, String content, Long countryId,
                             List<com.tripbora.dto.CourseDayForm> days, String error, Long userId,
                             boolean removeCoverImage, Model model) {
        CourseEditDto course = courseService.prepareFormForRetry(title, content, countryId, days);
        course.setId(id);
        if (id != null) {
            course.setCoverImageUrl(courseService.getCourseForEdit(id, userId, requestedLanguage()).getCoverImageUrl());
        }
        course.setRemoveCoverImage(removeCoverImage);
        addCourseCountries(model);
        @SuppressWarnings("unchecked")
        List<CountryCategory> overseas = (List<CountryCategory>) model.getAttribute("overseasCourseCountries");
        overseas.stream().filter(country -> java.util.Objects.equals(country.getId(), countryId))
                .findFirst().ifPresent(country -> course.setCountryName(country.getRegionName()));
        model.addAttribute("course", course);
        model.addAttribute("formError", error);
        return id == null ? "course/write" : "course/edit";
    }
}
