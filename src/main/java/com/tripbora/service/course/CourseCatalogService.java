package com.tripbora.service.course;

import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogPage;
import com.tripbora.dto.CourseCatalogRegionDto;
import com.tripbora.repository.course.CourseCatalogMapper;
import com.tripbora.service.destination.DestinationImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CourseCatalogService {
    private static final int PAGE_SIZE = 12;
    private static final String FALLBACK_IMAGE_URL = "/images/default.png";
    private final CourseCatalogMapper mapper;
    private final CourseThemeService themes;
    private final DestinationImageService images;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public CourseCatalogPage browse(String kind, Long countryId, Long themeId, String sort, int page) {
        String normalizedKind = normalizeKind(kind);
        Long normalizedCountryId = positiveId(countryId);
        Long normalizedThemeId = positiveId(themeId);
        String normalizedSort = "views".equals(sort) ? "views" : "latest";
        int totalCount = mapper.countCourses(normalizedKind, normalizedCountryId, normalizedThemeId);
        int totalPages = Math.max(1, (int) ((totalCount + (long) PAGE_SIZE - 1) / PAGE_SIZE));
        int currentPage = Math.min(Math.max(page, 1), totalPages);
        List<CourseCardDto> courses = totalCount == 0 ? List.of() : mapper.findCourses(
                normalizedKind, normalizedCountryId, normalizedThemeId, normalizedSort,
                PAGE_SIZE, (currentPage - 1) * PAGE_SIZE);
        if (!courses.isEmpty()) populateMetadata(courses);
        return new CourseCatalogPage(courses, totalCount, currentPage, totalPages,
                normalizedKind, normalizedCountryId, normalizedThemeId, normalizedSort);
    }

    private void populateMetadata(List<CourseCardDto> courses) {
        List<Long> ids = courses.stream().map(CourseCardDto::getId).toList();
        Map<Long, List<String>> countries = new LinkedHashMap<>();
        for (var country : mapper.findCountriesByCourseIds(ids)) {
            countries.computeIfAbsent(country.getCourseId(), ignored -> new ArrayList<>())
                    .add(country.getCountryName());
        }
        Map<Long, List<CourseCatalogRegionDto>> regions = new LinkedHashMap<>();
        for (var region : mapper.findRegionsByCourseIds(ids)) {
            regions.computeIfAbsent(region.getCourseId(), ignored -> new ArrayList<>()).add(region);
        }
        var courseThemes = themes.findByCourseIds(ids);
        for (CourseCardDto course : courses) {
            course.setCountryNames(countries.getOrDefault(course.getId(), List.of()));
            course.setRegionSummary(CourseCardDisplayLabels.regionSummary(regions.getOrDefault(course.getId(), List.of())));
            course.setDurationLabel(CourseCardDisplayLabels.durationLabel(course.getDayCount(), messages, LocaleContextHolder.getLocale()));
            course.setThemes(courseThemes.getOrDefault(course.getId(), List.of()));
            if (course.getRepresentativeImageUrl() == null || course.getRepresentativeImageUrl().isBlank()) {
                course.setRepresentativeImageUrl(FALLBACK_IMAGE_URL);
            }
        }
        images.markNoDerivatives(courses, CourseCardDto::getRepresentativeImageUrl,
                CourseCardDto::setImageNoDerivatives);
    }

    private String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        // 과거 ALL과 알 수 없는 값도 추천 탭으로 수렴시켜 공개 목록의 범위를 일관되게 유지한다.
        return "TRAVELER".equals(normalized) ? "TRAVELER" : "OFFICIAL";
    }

    private Long positiveId(Long id) {
        return id != null && id > 0 ? id : null;
    }
}
