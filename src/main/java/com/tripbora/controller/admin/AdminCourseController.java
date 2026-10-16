package com.tripbora.controller.admin;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.OfficialCourseForm;
import com.tripbora.model.CountryCategory;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.course.OfficialCourseAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.InvalidPropertyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Controller
@RequestMapping("/admin/courses")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCourseController {
    private static final String FORM_VIEW = "admin/courses/form";
    private final OfficialCourseAdminService service;
    private final CourseService courses;

    @InitBinder("officialCourseForm")
    public void configureBinding(WebDataBinder binder) {
        binder.setAutoGrowCollectionLimit(90);
        binder.setAllowedFields("title", "summary", "status", "coverImage", "removeCoverImage",
                "themeIds", "themeIds[*]", "days[*].id", "days[*].dayNumber", "days[*].title",
                "days[*].items[*].destinationId", "days[*].items[*].displayOrder", "days[*].items[*].itemType",
                "days[*].items[*].countryId", "days[*].items[*].placeName", "days[*].items[*].address",
                "days[*].items[*].latitude", "days[*].items[*].longitude",
                "days[*].items[*].startTime", "days[*].items[*].memo");
    }

    @ExceptionHandler(InvalidPropertyException.class)
    @ResponseBody
    public ResponseEntity<String> invalidScheduleIndex() {
        return ResponseEntity.badRequest().body("일정 입력 범위를 확인해 주세요. Day는 최대 90일, 하루 장소는 최대 50곳입니다.");
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String keyword,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "1") int page, Model model) {
        int total = service.count(keyword, status);
        int totalPages = Math.max(1, (int) Math.ceil(total / 20.0));
        int currentPage = Math.max(1, Math.min(page, totalPages));
        model.addAttribute("courses", service.list(keyword, status, currentPage));
        model.addAttribute("keyword", keyword);
        model.addAttribute("status", status);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", total);
        return "admin/courses/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        var course = new CourseEditDto();
        course.setStatus("DRAFT");
        prepareForm(course, model);
        return FORM_VIEW;
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        prepareForm(service.getForEdit(id), model);
        return FORM_VIEW;
    }

    @PostMapping
    public String create(@ModelAttribute("officialCourseForm") OfficialCourseForm form, BindingResult binding,
                         @AuthenticationPrincipal CustomUserDetails admin, Model model) {
        if (binding.hasErrors()) return retry(null, form, "입력한 일정 형식을 확인해 주세요.", model);
        try {
            return "redirect:/admin/courses/" + service.create(form, admin.getId()) + "/preview";
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.BAD_REQUEST) throw error;
            return retry(null, form, error.getReason(), model);
        }
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @ModelAttribute("officialCourseForm") OfficialCourseForm form,
                         BindingResult binding, @AuthenticationPrincipal CustomUserDetails admin, Model model) {
        // Binding 실패 때도 OFFICIAL만 복원한다. 사용자 코스가 관리자 폼에 들어오지 않게 한다.
        if (binding.hasErrors()) return retry(id, form, "입력한 일정 형식을 확인해 주세요.", model);
        try {
            service.update(id, form, admin.getId());
        } catch (ResponseStatusException error) {
            if (error.getStatusCode() != HttpStatus.BAD_REQUEST) throw error;
            return retry(id, form, error.getReason(), model);
        }
        return "redirect:/admin/courses/" + id + "/preview";
    }

    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails admin, Model model) {
        var course = service.preview(id, admin.getId());
        model.addAttribute("course", course);
        model.addAttribute("adminPreview", true);
        model.addAttribute("seoRobots", "noindex, nofollow");
        return "course/detail";
    }

    private String retry(Long id, OfficialCourseForm form, String error, Model model) {
        CourseEditDto course = courses.prepareFormForRetry(form.getTitle(), null, null, form.getDays());
        course.setId(id);
        course.setTitle(form.getTitle());
        course.setSummary(form.getSummary());
        course.setStatus(form.getStatus());
        course.setThemeIds(form.getThemeIds() == null ? List.of() : form.getThemeIds());
        course.setRemoveCoverImage(form.isRemoveCoverImage());
        if (id != null) course.setCoverImageUrl(service.getForEdit(id).getCoverImageUrl());
        prepareForm(course, model);
        model.addAttribute("formError", error);
        return FORM_VIEW;
    }

    private void prepareForm(CourseEditDto course, Model model) {
        model.addAttribute("course", course);
        model.addAttribute("formAction", course.getId() == null ? "/admin/courses" : "/admin/courses/" + course.getId() + "/edit");
        model.addAttribute("courseThemes", courses.getCourseThemes());
        List<CountryCategory> countries = courses.getCourseCountries(SupportedLanguage.KOREAN);
        model.addAttribute("courseCountries", countries);
        model.addAttribute("domesticCourseCountries", countries.stream().filter(country -> country.getParentId() == null).toList());
        model.addAttribute("overseasCourseCountries", countries.stream().filter(country -> country.getParentId() != null).toList());
    }
}
