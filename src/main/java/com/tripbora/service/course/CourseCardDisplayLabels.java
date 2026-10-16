package com.tripbora.service.course;

import com.tripbora.dto.CourseCatalogRegionDto;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 실제 지역 ID/부모 관계와 일정 일수만 사용하는 카드 표시 규칙. */
public final class CourseCardDisplayLabels {
    private CourseCardDisplayLabels() {}

    public static String regionSummary(List<CourseCatalogRegionDto> rows) {
        Map<Long, List<CourseCatalogRegionDto>> paths = new LinkedHashMap<>();
        for (CourseCatalogRegionDto row : rows) {
            if (row != null && row.getStopId() != null) {
                paths.computeIfAbsent(row.getStopId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<Long, String> regions = new LinkedHashMap<>();
        for (var path : paths.values()) {
            var countries = path.stream().filter(node -> node.getRegionId() != null && node.isCountryNode()).toList();
            if (countries.size() != 1) continue; // 끊기거나 국가가 모호한 경로는 지역을 추측하지 않는다.
            var country = countries.get(0);
            path.stream().filter(node -> node.getRegionId() != null
                    && country.getRegionId().equals(node.getParentId())
                    && node.getRegionName() != null && !node.getRegionName().isBlank())
                    .findFirst().ifPresent(region -> regions.putIfAbsent(region.getRegionId(), region.getRegionName().strip()));
        }
        String summary = String.join(" · ", regions.values().stream().limit(2).toList());
        return regions.size() > 2 ? summary + " +" + (regions.size() - 2) : summary;
    }

    public static String durationLabel(Integer dayCount, MessageSource messages, Locale locale) {
        if (dayCount == null || dayCount <= 0) return "";
        if (dayCount == 1) return messages.getMessage("course.list.duration.dayTrip", null, locale);
        return messages.getMessage("course.list.duration.nightsDays", new Object[]{dayCount - 1, dayCount}, locale);
    }
}
