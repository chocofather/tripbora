package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationImportItem;
import com.tripbora.dto.destinationimport.DestinationUpdatePreview.Change;
import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 순수 병합: 선택한 유효값만 패치하며 null/빈 배열로 기존 데이터를 지우지 않는다. */
@Component
public class DestinationUpdateMerge {
    public record Plan(List<Change> changes, Map<String, Object> patch, Set<String> protectedFields,
                       List<String> warnings, List<String> errors, boolean hasRemovals) { }

    public Plan plan(DestinationUpdateSnapshot before, DestinationImportItem item,
                     DestinationImportMasterResolver.Resolution resolution, Set<String> selected,
                     boolean replaceRelations) {
        Map<String, Object> proposed = new LinkedHashMap<>();
        item.translations().forEach((language, text) -> {
            put(proposed, "translations." + language + ".name", text.name());
            put(proposed, "translations." + language + ".shortDescription", text.shortDescription());
            put(proposed, "translations." + language + ".description", text.description());
        });
        item.info().values().forEach((field, value) -> put(proposed, "info." + field, value));
        item.info().translations().forEach((language, fields) -> fields.forEach((field, value) ->
                put(proposed, "info.translations." + language + "." + field, value)));
        put(proposed, "latitude", item.latitude());
        put(proposed, "longitude", item.longitude());
        put(proposed, "season", item.season() == null ? null : item.season().name());
        if (item.region().country() != null) put(proposed, "regionId", resolution.regionId());
        put(proposed, "googlePlaceId", item.external().googlePlaceId());
        if (!item.categories().isEmpty()) proposed.put("categories", relations(before.values().get("categories"),
                resolution.categoryIds(), replaceRelations));
        if (item.mainCategory() != null) put(proposed, "mainCategory", resolution.requestedMainCategoryId());
        if (!item.amenities().isEmpty()) proposed.put("amenities", relations(before.values().get("amenities"),
                resolution.amenityIds(), replaceRelations));

        Set<String> allowed = allowedPaths(before);
        List<String> errors = new ArrayList<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        proposed.forEach((path, next) -> kinds.put(path, kind(before, path, next)));
        // null은 최초 기본 선택이며, 빈 Set은 사용자가 자동 보강까지 모두 제외한 명시적 선택이다.
        boolean defaultSelection = selected == null;
        if (selected == null) {
            selected = new LinkedHashSet<>();
            for (var entry : kinds.entrySet()) if ("AUTO_ADD".equals(entry.getValue())) selected.add(entry.getKey());
        }
        Object resultingCategories = selected.contains("categories") && proposed.containsKey("categories") ? proposed.get("categories")
                : before.values().get("categories");
        if ("AUTO_ADD".equals(kinds.get("mainCategory"))
                && (!(resultingCategories instanceof Collection<?> categories)
                || !categories.contains(proposed.get("mainCategory")))) {
            kinds.put("mainCategory", "REPLACE");
            // 대표가 가리킬 연결이 아직 없으면 기본 자동 보강으로 오류를 만들지 않는다.
            if (defaultSelection) selected.remove("mainCategory");
        }
        selected.stream().filter(field -> !allowed.contains(field))
                .forEach(field -> errors.add("허용하지 않는 업데이트 항목: " + field));
        if ((item.latitude() == null) != (item.longitude() == null)) {
            errors.add("위도와 경도 입력은 함께 제공해야 합니다.");
            selected = new LinkedHashSet<>(selected);
            selected.remove("latitude");
            selected.remove("longitude");
        }
        // 좌표는 한 쌍으로 선택·저장한다. 한쪽만 바뀌더라도 다른 쪽은 새 값으로 함께 비교한다.
        if (selected.contains("latitude") != selected.contains("longitude")) {
            errors.add("위도와 경도는 함께 선택해 주세요.");
        }
        Map<String, String> labels = new LinkedHashMap<>(before.labels());
        for (int i = 0; i < item.categories().size() && i < resolution.categoryIds().size(); i++)
            labels.put("category." + resolution.categoryIds().get(i), item.categories().get(i));
        for (int i = 0; i < item.amenities().size() && i < resolution.amenityIds().size(); i++)
            labels.put("amenity." + resolution.amenityIds().get(i), item.amenities().get(i));
        if (resolution.regionId() != null && resolution.regionLabel() != null)
            labels.put("region." + resolution.regionId(), resolution.regionLabel());
        Map<String, Object> patch = new LinkedHashMap<>();
        Set<String> protectedFields = new LinkedHashSet<>();
        List<Change> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean removals = false;
        for (var entry : proposed.entrySet()) {
            String path = entry.getKey();
            Object old = before.values().get(path);
            Object next = entry.getValue();
            boolean changed = !same(old, next);
            boolean chosen = changed && selected.contains(path);
            String afterText = display(path, next, labels);
            int addedCount = 0;
            int removedCount = 0;
            if ((path.equals("categories") || path.equals("amenities")) && next instanceof Collection<?> incoming) {
                Collection<?> existing = old instanceof Collection<?> values ? values : List.of();
                List<?> added = incoming.stream().filter(value -> !existing.contains(value)).toList();
                List<?> removed = existing.stream().filter(value -> !incoming.contains(value)).toList();
                addedCount = added.size();
                removedCount = removed.size();
                afterText += "\n추가: " + display(path, added, labels) + "\n제거: " + display(path, removed, labels);
            }
            changes.add(new Change(path, label(path), old, next, display(path, old, labels),
                    afterText, chosen, changed, kinds.get(path), addedCount, removedCount));
            if (!chosen) continue;
            protectedFields.add(path);
            if (changed) patch.put(path, next);
            if ((path.equals("categories") || path.equals("amenities")) && old instanceof Collection<?> existing
                    && next instanceof Collection<?> incoming && !incoming.containsAll(existing)) removals = true;
            if (path.startsWith("info.") && !path.startsWith("info.translations.")) {
                String field = path.substring(5);
                var contract = DestinationImportFields.infoFieldMap(before.type()).get(field);
                if (contract != null && contract.translatable()) {
                    String korean = "info.translations.ko." + field;
                    Object existingKorean = before.values().get(korean);
                    if (!same(existingKorean, next)) {
                        if ("AUTO_ADD".equals(kinds.get(path)) && !empty(existingKorean)) {
                            warnings.add("기존 한국어 운영정보 번역은 유지합니다: " + field + ". 원문과 내용이 다를 수 있습니다.");
                        } else {
                            protectedFields.add(korean);
                            patch.put(korean, next);
                        }
                    }
                }
            }
        }
        if (patch.containsKey("categories") && before.values().get("mainCategory") != null
                && !((Collection<?>) patch.get("categories")).contains(before.values().get("mainCategory"))
                && !patch.containsKey("mainCategory")) {
            errors.add("기존 대표 카테고리를 제거하려면 새 대표 카테고리도 선택해 주세요.");
        }
        if (selected.contains("mainCategory") && proposed.containsKey("mainCategory")) {
            Object categories = patch.getOrDefault("categories", before.values().get("categories"));
            if (!(categories instanceof Collection<?> ids) || !ids.contains(proposed.get("mainCategory")))
                errors.add("새 대표 카테고리를 포함하도록 카테고리 항목도 선택해 주세요.");
        }
        for (String field : List.of("name", "shortDescription", "description")) {
            if (!patch.containsKey("translations.ko." + field)) continue;
            boolean retained = DestinationImportFields.LANGUAGES.stream().filter(lang -> !lang.equals("ko"))
                    .anyMatch(lang -> before.values().get("translations." + lang + "." + field) != null
                            && !patch.containsKey("translations." + lang + "." + field));
            if (retained) warnings.add(label("translations.ko." + field) + " 원문은 변경하지만 일부 기존 번역은 유지합니다. 내용이 다를 수 있습니다.");
        }
        if (patch.keySet().stream().anyMatch(path -> path.startsWith("info.") && !path.startsWith("info.translations.")))
            warnings.add("운영정보 원문 변경 시 선택하지 않은 운영정보 번역은 유지됩니다. 내용과 출처의 정확성을 확인해 주세요.");
        return new Plan(List.copyOf(changes), Collections.unmodifiableMap(patch), Set.copyOf(protectedFields),
                List.copyOf(warnings), List.copyOf(errors), removals);
    }

    /** 현재 원시값의 빈 칸만 자동 선택한다. 공개 화면의 언어 fallback은 사용하지 않는다. */
    public Set<String> automaticFields(DestinationUpdateSnapshot before, DestinationImportItem item,
                                       DestinationImportMasterResolver.Resolution resolution) {
        Set<String> fields = new LinkedHashSet<>();
        for (Change change : plan(before, item, resolution, null, false).changes()) {
            if (change.selected()) fields.add(change.path());
        }
        return Collections.unmodifiableSet(fields);
    }

    public Plan planDefault(DestinationUpdateSnapshot before, DestinationImportItem item,
                            DestinationImportMasterResolver.Resolution resolution, boolean replaceRelations) {
        return plan(before, item, resolution, null, replaceRelations);
    }

    private static String kind(DestinationUpdateSnapshot before, String path, Object next) {
        Object current = before.values().get(path);
        if (same(current, next)) return "UNCHANGED";
        // 좌표는 기존 양쪽이 모두 비어 있을 때만 자동 보강한다. 부분 좌표는 한 쌍의 교체 후보다.
        if (path.equals("latitude") || path.equals("longitude")) {
            return before.values().get("latitude") == null && before.values().get("longitude") == null
                    ? "AUTO_ADD" : "REPLACE";
        }
        return empty(current) ? "AUTO_ADD" : "REPLACE";
    }

    private static boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty();
    }

    private static Set<String> allowedPaths(DestinationUpdateSnapshot snapshot) {
        Set<String> paths = new HashSet<>(Set.of("categories", "mainCategory", "amenities", "latitude", "longitude",
                "season", "regionId", "googlePlaceId"));
        for (String language : DestinationImportFields.LANGUAGES)
            for (String field : List.of("name", "shortDescription", "description")) paths.add("translations." + language + "." + field);
        for (var field : DestinationImportFields.infoFields(snapshot.type())) {
            paths.add("info." + field.name());
            if (field.translatable()) for (String language : DestinationImportFields.INFO_TRANSLATION_LANGUAGES)
                paths.add("info.translations." + language + "." + field.name());
        }
        return paths;
    }

    private static void put(Map<String, Object> map, String path, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) map.put(path, value);
    }
    private static List<?> relations(Object current, List<?> proposed, boolean replace) {
        List<Object> values = new ArrayList<>();
        if (!replace && current instanceof Collection<?> existing) values.addAll(existing);
        values.addAll(proposed);
        return values.stream().distinct().sorted(Comparator.comparingLong(value -> ((Number) value).longValue())).toList();
    }
    static boolean same(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b)
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        if (first instanceof Boolean flag && second instanceof Number number) return flag == (number.intValue() != 0);
        if (second instanceof Boolean flag && first instanceof Number number) return flag == (number.intValue() != 0);
        return Objects.equals(first, second);
    }
    private static String display(String path, Object value, Map<String, String> labels) {
        if (value == null) return "(없음)";
        String prefix = path.equals("categories") || path.equals("mainCategory") ? "category."
                : path.equals("amenities") ? "amenity." : path.equals("regionId") ? "region." : null;
        if (prefix != null && value instanceof Collection<?> values)
            return values.stream().map(id -> labels.getOrDefault(prefix + id, "#" + id)).reduce((a, b) -> a + ", " + b).orElse("(없음)");
        return prefix == null ? value.toString() : labels.getOrDefault(prefix + value, "#" + value);
    }
    private static String label(String path) {
        if (path.startsWith("translations.")) {
            String[] parts = path.split("\\.");
            return parts[1] + " · " + switch (parts[2]) {
                case "name" -> "여행지 이름";
                case "shortDescription" -> "한 줄 소개";
                default -> "상세 설명";
            };
        }
        return switch (path) {
            case "categories" -> "카테고리";
            case "mainCategory" -> "대표 카테고리";
            case "amenities" -> "편의시설";
            case "latitude" -> "위도";
            case "longitude" -> "경도";
            case "season" -> "계절";
            case "regionId" -> "지역";
            case "googlePlaceId" -> "Google Place ID";
            default -> path.startsWith("info.translations.") ? "운영정보 번역 · " + path.substring(18)
                    : "운영정보 · " + path.substring(5);
        };
    }
}
