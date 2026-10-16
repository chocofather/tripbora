package com.tripbora.controller.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.service.course.CourseCatalogService;
import com.tripbora.service.course.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class CourseCatalogController {
    private final CourseCatalogService catalog;
    private final CourseService courses;

    @GetMapping("/courses")
    public String list(@RequestParam(defaultValue = "recommended") String kind,
                       @RequestParam(required = false) Long countryId,
                       @RequestParam(required = false) Long themeId,
                       @RequestParam(defaultValue = "latest") String sort,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        var result = catalog.browse(kind, countryId, themeId, sort, page);
        model.addAttribute("courses", result.getCourses());
        model.addAttribute("kind", result.getKind());
        model.addAttribute("catalogKind", "TRAVELER".equals(result.getKind()) ? "traveler" : null);
        model.addAttribute("countryId", result.getCountryId());
        model.addAttribute("themeId", result.getThemeId());
        model.addAttribute("sort", result.getSort());
        model.addAttribute("currentPage", result.getCurrentPage());
        model.addAttribute("totalPages", result.getTotalPages());
        model.addAttribute("totalCount", result.getTotalCount());
        var language = SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
        var countries = courses.getCourseCountries(language);
        var themes = courses.getCourseThemes();
        model.addAttribute("courseCountries", countries);
        model.addAttribute("courseThemes", themes);
        model.addAttribute("selectedCountryName", countries.stream()
                .filter(country -> result.getCountryId() != null && result.getCountryId().equals(country.getId()))
                .findFirst().map(country -> country.getRegionName()).orElse(null));
        model.addAttribute("selectedThemeName", themes.stream()
                .filter(theme -> result.getThemeId() != null && result.getThemeId().equals(theme.getId()))
                .findFirst().map(theme -> theme.getName()).orElse(null));
        return "course/list";
    }
}
