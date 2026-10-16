package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseLocationLabel;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 실제 지역 경로와 기존 번역 fallback으로 Course 표시 label만 보강한다. */
public final class CourseLocationLabels {
    private CourseLocationLabels() {}

    public static Map<Long, String> localize(List<CourseStopDto> stops, SupportedLanguage language,
                                            CountryCategoryService categories,
                                            ReferenceNameLocalizationService localization) {
        Map<Long, List<CountryCategory>> paths = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (CourseStopDto stop : stops) {
            if (stop == null) continue;
            if (stop.getRegionId() != null) {
                names.putIfAbsent(stop.getRegionId(), stop.getRegionName());
                paths.computeIfAbsent(stop.getRegionId(), id -> categories.getRegionPath(id));
            }
            if (stop.getCountryId() != null) names.putIfAbsent(stop.getCountryId(), stop.getCountryName());
        }
        paths.values().forEach(path -> path.forEach(node -> names.put(node.getId(), node.getRegionName())));
        // 직접 입력 재시도처럼 국가명만 비어 있는 경우에도 저장된 국가 ID로 읽는다.
        names.replaceAll((id, name) -> {
            if (name != null && !name.isBlank()) return name;
            CountryCategory category = categories.getById(id);
            return category == null ? name : category.getRegionName();
        });
        Map<Long, String> translated = new LinkedHashMap<>(localization.localizeCountryCategoryNames(names, language));
        for (CourseStopDto stop : stops) {
            if (stop == null) continue;
            if (stop.getDestinationId() == null && stop.getCountryId() != null) {
                stop.setCountryName(translated.getOrDefault(stop.getCountryId(), stop.getCountryName()));
            }
            List<CountryCategory> path = paths.getOrDefault(stop.getRegionId(), List.of());
            String label = pathLabel(path, stop, translated);
            if (label == null) {
                String country = translated.getOrDefault(stop.getCountryId(), stop.getCountryName());
                String region = translated.getOrDefault(stop.getRegionId(), stop.getRegionName());
                label = stop.getDestinationId() == null && stop.getAddress() != null && !stop.getAddress().isBlank()
                        ? stop.getAddress().strip() : CourseLocationLabel.join(country, region);
            }
            stop.setLocationLabel(label);
        }
        return translated;
    }

    private static String pathLabel(List<CountryCategory> path, CourseStopDto stop, Map<Long, String> names) {
        if (path.isEmpty()) return null;
        String code = path.get(path.size() - 1).getCode();
        String countryCode = code == null ? stop.getCountryCode() : code.split("-", 2)[0];
        int countryIndex = -1;
        for (int i = 0; i < path.size(); i++) {
            if (countryCode != null && countryCode.equals(path.get(i).getCode())) countryIndex = i;
        }
        if (countryIndex < 0) return null; // 끊긴/legacy 경로에서 상위 지역을 추측하지 않는다.
        List<String> parts = new ArrayList<>();
        boolean domestic = "KR".equals(countryCode);
        if (!domestic || countryIndex == path.size() - 1) parts.add(names.get(path.get(countryIndex).getId()));
        for (int i = countryIndex + 1; i < path.size(); i++) {
            parts.add(names.get(path.get(i).getId()));
            if (!domestic) break; // 해외는 국가 바로 아래의 도시, 국내는 광역지역부터 시/군/구까지.
        }
        return CourseLocationLabel.join(parts.toArray(String[]::new));
    }
}
