package com.tripbora.service.destinationimport;

import com.tripbora.dto.destinationimport.DestinationUpdateSnapshot;
import com.tripbora.model.DestinationType;
import com.tripbora.model.DestinationTranslationSource;
import com.tripbora.repository.destination.DestinationJsonUpdateMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.repository.destination.DestinationTranslationSourceMapper;
import com.tripbora.service.wikidata.WikidataRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** Selective persistence. The caller owns the transaction and conflict check. */
@Service
@RequiredArgsConstructor
public class DestinationUpdateStore {
    private final DestinationJsonUpdateMapper mapper;
    private final DestinationMapper destinations;
    private final DestinationTranslationSourceMapper sources;
    private static final Set<String> BASE = Set.of("latitude", "longitude", "season", "regionId", "googlePlaceId");
    private static final Set<String> TEXT = Set.of("name", "shortDescription", "description");
    private static final Set<String> LANGUAGES = Set.of("ko", "en", "ja", "zh-CN", "zh-TW");

    public Map<String, Long> externalTargets(String sourceType, Collection<String> contentIds) {
        if (!Set.of("KTO_TOURAPI", "WIKIDATA").contains(sourceType)) throw new IllegalArgumentException("지원하지 않는 외부 출처입니다.");
        List<String> ids = contentIds.stream().filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 30) throw new IllegalArgumentException("미리보기는 최대 30건입니다.");
        Map<String, Long> targets = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (var row : mapper.findExternalTargets(sourceType, ids)) {
            String contentId = (String) row.get("external_content_id");
            if (targets.putIfAbsent(contentId, ((Number) row.get("id")).longValue()) != null) ambiguous.add(contentId);
        }
        // Duplicate external identities must never silently choose a destination.
        ambiguous.forEach(targets::remove);
        return Collections.unmodifiableMap(targets);
    }

    /** Bounded file-level preview read: three shared SELECTs plus three per destination type. */
    public Map<Long, DestinationUpdateSnapshot> readAll(Collection<Long> requestedIds) {
        List<Long> ids = requestedIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 30) throw new IllegalArgumentException("미리보기는 최대 30건입니다.");
        List<Map<String, Object>> bases = mapper.findBases(ids);
        if (bases.isEmpty()) return Map.of();
        Map<Long, List<Map<String, Object>>> translations = byDestination(mapper.findTranslationsBatch(ids));
        Map<Long, List<Map<String, Object>>> categories = byDestination(mapper.findCategoriesBatch(ids));
        Map<DestinationType, List<Long>> byType = new EnumMap<>(DestinationType.class);
        for (var row : bases) byType.computeIfAbsent(DestinationType.valueOf((String) row.get("type")),
                ignored -> new ArrayList<>()).add(((Number) row.get("id")).longValue());
        Map<Long, DestinationUpdateSnapshot> snapshots = new LinkedHashMap<>();
        for (var group : byType.entrySet()) {
            DestinationType type = group.getKey();
            Map<Long, List<Map<String, Object>>> info = byDestination(mapper.findInfoBatch(group.getValue(), type));
            Map<Long, List<Map<String, Object>>> infoTranslations = byDestination(mapper.findInfoTranslationsBatch(group.getValue(), type));
            Map<Long, List<Map<String, Object>>> amenities = byDestination(mapper.findAmenitiesBatch(group.getValue(), type));
            for (var row : bases) {
                if (!type.name().equals(row.get("type"))) continue;
                Long id = ((Number) row.get("id")).longValue();
                var infoRows = info.getOrDefault(id, List.of());
                snapshots.put(id, snapshot(id, type, row, translations.getOrDefault(id, List.of()),
                        infoRows.isEmpty() ? null : infoRows.get(0), infoTranslations.getOrDefault(id, List.of()),
                        categories.getOrDefault(id, List.of()), amenities.getOrDefault(id, List.of())));
            }
        }
        return Collections.unmodifiableMap(snapshots);
    }

    private static Map<Long, List<Map<String, Object>>> byDestination(List<Map<String, Object>> rows) {
        Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
        for (var row : rows) grouped.computeIfAbsent(((Number) row.get("destination_id")).longValue(),
                ignored -> new ArrayList<>()).add(row);
        return grouped;
    }

    public DestinationUpdateSnapshot read(Long id, boolean lock) {
        if (lock) {
            requireTransaction();
            if (mapper.lockDestination(id) == null) return null;
        }
        Map<String, Object> row = mapper.findBase(id, lock);
        if (row == null) return null;
        DestinationType type = DestinationType.valueOf((String) row.get("type"));
        return snapshot(id, type, row, mapper.findTranslations(id, lock), mapper.findInfo(id, type, lock),
                mapper.findInfoTranslations(id, type, lock), mapper.findCategories(id, lock), mapper.findAmenities(id, type, lock));
    }

    private static DestinationUpdateSnapshot snapshot(Long id, DestinationType type, Map<String, Object> row,
            List<Map<String, Object>> translations, Map<String, Object> info, List<Map<String, Object>> infoTranslations,
            List<Map<String, Object>> categories, List<Map<String, Object>> amenities) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (String field : BASE) values.put(field, row.get(snake(field)));
        labels.put("region." + row.get("region_id"), (String) row.get("region_name"));
        flatten(values, "translations.", translations, TEXT);
        for (String field : infoFields(type)) values.put("info." + field, info == null ? null : info.get(snake(field)));
        flatten(values, "info.translations.", infoTranslations, translatedFields(type));
        List<Long> categoryIds = new ArrayList<>();
        Long main = null;
        for (Map<String, Object> category : categories) {
            Long categoryId = ((Number) category.get("category_id")).longValue();
            categoryIds.add(categoryId);
            labels.put("category." + categoryId, (String) category.get("name"));
            if (truth(category.get("is_main"))) main = categoryId;
        }
        values.put("categories", categoryIds.stream().sorted().toList());
        values.put("mainCategory", main);
        List<Integer> amenityIds = new ArrayList<>();
        for (Map<String, Object> amenity : amenities) {
            Integer amenityId = ((Number) amenity.get("amenity_id")).intValue();
            amenityIds.add(amenityId);
            labels.put("amenity." + amenityId, (String) amenity.get("code"));
        }
        values.put("amenities", amenityIds.stream().sorted().toList());
        return new DestinationUpdateSnapshot(id, type, (String) row.get("source_type"),
                (String) row.get("external_content_id"), values, labels);
    }

    public void apply(Long id, DestinationType type, Map<String, Object> patch) {
        requireTransaction();
        // Validate every path before the first mutation, even for internal callers.
        patch.forEach((path, value) -> validate(type, path, value));
        DestinationUpdateSnapshot before = read(id, true);
        if (before == null || before.type() != type) throw new IllegalArgumentException("여행지 또는 유형이 변경되었습니다.");
        Map<String, Object> current = before.values();
        if (patch.containsKey("categories") && !patch.containsKey("mainCategory")
                && current.get("mainCategory") != null
                && !longIds(patch.get("categories")).contains(current.get("mainCategory"))) {
            throw new IllegalArgumentException("대표 카테고리를 제거하려면 새 대표 카테고리도 선택해야 합니다.");
        }
        Set<String> newTranslations = new HashSet<>();
        Set<String> newInfoTranslations = new HashSet<>();
        boolean infoReady = false;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String path = entry.getKey();
            Object value = entry.getValue();
            if (Objects.equals(current.get(path), value)) continue;
            if (BASE.contains(path)) {
                mapper.updateBaseField(id, path, value);
            } else if (path.startsWith("translations.")) {
                String[] parts = path.split("\\.");
                if (!current.containsKey("translations." + parts[1] + ".name") && newTranslations.add(parts[1])) {
                    mapper.insertTranslation(id, parts[1]);
                }
                mapper.updateTranslationField(id, parts[1], parts[2], value);
                if ("description".equals(parts[2])) updateWikipediaSource(id, parts[1], (String) value);
            } else if (path.startsWith("info.")) {
                if (!infoReady) {
                    if (mapper.findInfo(id, type, true) == null) mapper.insertInfo(id, type);
                    infoReady = true;
                }
                if (path.startsWith("info.translations.")) {
                    String[] parts = path.split("\\.");
                    if (!current.containsKey("info.translations." + parts[2] + "." + translatedFields(type).iterator().next())
                            && newInfoTranslations.add(parts[2])) mapper.insertInfoTranslation(id, type, parts[2]);
                    mapper.updateInfoTranslationField(id, type, parts[2], parts[3], value);
                } else mapper.updateInfoField(id, type, path.substring(5), value);
            }
        }
        applyRelations(id, type, patch, current);
    }

    private void applyRelations(Long id, DestinationType type, Map<String, Object> patch, Map<String, Object> current) {
        if (patch.containsKey("categories")) {
            Set<Long> oldIds = longIds(current.get("categories"));
            Set<Long> newIds = longIds(patch.get("categories"));
            for (Long category : oldIds) if (!newIds.contains(category)) destinations.deleteDestinationCategory(id, category);
            for (Long category : newIds) if (!oldIds.contains(category)) destinations.insertDestinationCategory(id, category);
        }
        if (patch.containsKey("mainCategory") && !Objects.equals(current.get("mainCategory"), patch.get("mainCategory"))) {
            Long main = ((Number) patch.get("mainCategory")).longValue();
            Set<Long> selected = longIds(patch.getOrDefault("categories", current.get("categories")));
            if (!selected.contains(main)) throw new IllegalArgumentException("대표 카테고리가 선택 목록에 없습니다.");
            destinations.clearMainCategory(id);
            if (destinations.markMainCategory(id, main) != 1) throw new IllegalStateException("대표 카테고리 저장 실패");
        }
        if (patch.containsKey("amenities")) {
            Set<Long> oldIds = longIds(current.get("amenities"));
            Set<Long> newIds = longIds(patch.get("amenities"));
            if (!newIds.isEmpty() && mapper.findInfo(id, type, true) == null) mapper.insertInfo(id, type);
            for (Long amenity : oldIds) if (!newIds.contains(amenity)) mapper.deleteAmenity(id, type, Math.toIntExact(amenity));
            for (Long amenity : newIds) if (!oldIds.contains(amenity)) mapper.insertAmenity(id, type, Math.toIntExact(amenity));
        }
    }

    private void updateWikipediaSource(Long id, String language, String description) {
        var translation = destinations.findTranslationByDestinationAndLanguage(id, language);
        if (translation == null) return;
        DestinationTranslationSource source = sources.findByTranslationId(translation.getId());
        if (source != null) sources.updateModified(translation.getId(), !Arrays.equals(
                WikidataRegistrationService.sha256(description), source.getOriginalContentSha256()));
    }

    private static void validate(DestinationType type, String path, Object value) {
        if (value == null) throw new IllegalArgumentException("null은 업데이트 패치에 포함할 수 없습니다.");
        if (BASE.contains(path) || Set.of("categories", "mainCategory", "amenities").contains(path)) return;
        String[] parts = path.split("\\.");
        if (parts.length == 3 && parts[0].equals("translations") && LANGUAGES.contains(parts[1]) && TEXT.contains(parts[2])) return;
        if (parts.length == 2 && parts[0].equals("info") && infoFields(type).contains(parts[1])) return;
        if (parts.length == 4 && parts[0].equals("info") && parts[1].equals("translations")
                && LANGUAGES.contains(parts[2]) && translatedFields(type).contains(parts[3])) return;
        throw new IllegalArgumentException("허용되지 않은 업데이트 필드: " + path);
    }

    private static void flatten(Map<String, Object> values, String prefix, List<Map<String, Object>> rows, Set<String> fields) {
        for (Map<String, Object> row : rows) for (String field : fields)
            values.put(prefix + row.get("language_code") + "." + field, row.get(snake(field)));
    }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() == 1; }
    private static String snake(String field) { return field.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    private static Set<Long> longIds(Object value) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : (Collection<?>) value) ids.add(((Number) item).longValue());
        return ids;
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("트랜잭션이 필요합니다.");
    }
    private static Set<String> infoFields(DestinationType type) {
        Set<String> fields = new LinkedHashSet<>(translatedFields(type));
        fields.addAll(Set.of("parkingAvailable", "contactNumber", "homepageUrl"));
        switch (type) {
            case ACCOMMODATION -> fields.addAll(Set.of("checkinTime", "checkoutTime", "roomCount", "starRating", "breakfastIncluded", "petAllowed"));
            case RESTAURANTS, CAFE -> fields.addAll(Set.of("petAllowed", "seatCount", "takeoutAvailable", "deliveryAvailable", "reservation"));
            case ACTIVITY -> fields.addAll(Set.of("reservation", "equipmentIncluded"));
            default -> { }
        }
        return fields;
    }
    private static Set<String> translatedFields(DestinationType type) {
        return switch (type) {
            case ATTRACTION -> Set.of("closedDays", "openingHours", "admissionFee", "guide");
            case ACCOMMODATION -> Set.of("roomType", "etc");
            case RESTAURANTS, CAFE -> Set.of("mainMenu", "priceRange", "openingHours", "breakTime", "closedDays", "etc");
            case ACTIVITY -> Set.of("openingHours", "requiredTime", "admissionFee", "ageLimit", "guide");
            case SHOP -> Set.of("closedDays", "openingHours", "mainProducts", "guide");
        };
    }
}
