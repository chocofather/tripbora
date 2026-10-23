package com.tripbora.service.destination;

import com.tripbora.model.CountryCategory;
import com.tripbora.model.DestinationDuplicateIndexRow;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.category.CountryCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 외부 후보가 이미 등록된 여행지인지 판별한다. TourAPI·Wikidata 후보 목록과 저장 직전 확인이 함께 쓴다.
 *
 * <ol>
 *   <li>확정 중복(REGISTERED): 같은 출처의 외부 ID(contentId·QID) 일치, 또는 양쪽 모두 있는 Google Place ID 일치</li>
 *   <li>확인 필요(POSSIBLE_DUPLICATE): 정규화한 이름이 같고
 *       <ul>
 *         <li>양쪽 좌표가 모두 있으면 {@value #NEARBY_DISTANCE_METERS}m 이내</li>
 *         <li>한쪽이라도 좌표가 없으면 같은 지역 계층(한쪽이 다른 쪽의 상위 지역이거나 같은 지역)</li>
 *       </ul></li>
 *   <li>그 밖에는 미등록. 이름만 같거나 위치만 가까운 곳은 다른 여행지로 본다.</li>
 * </ol>
 * 이름·위치 판별은 서로 다른 여행지를 막지 않도록 확정 중복으로 올리지 않는다.
 *
 * <p>여행지 수가 많지 않은 관리자 기능이라 판별할 때마다 이름 색인 전체를 한 번 읽어 메모리에서 비교한다.
 * 이름 정규화가 SQL 로 옮길 수 없는 규칙이기 때문이다.</p>
 */
@Service
@RequiredArgsConstructor
public class DestinationDuplicateService {

    /** 이름이 같고 이 거리 안이면 같은 곳일 수 있다. */
    static final int NEARBY_DISTANCE_METERS = 2_000;
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final String KOREAN = "ko";
    /** 지역 계층을 거슬러 올라갈 최대 단계. 잘못된 parent_id 순환을 막는다. */
    private static final int MAX_REGION_DEPTH = 10;

    private final DestinationMapper destinationMapper;
    private final CountryCategoryService countryCategoryService;

    public DestinationDuplicateCheck check(DestinationDuplicateQuery query) {
        return checkAll(List.of(query)).get(0);
    }

    /** 업데이트 대상 자신의 이름·외부 ID를 중복으로 오인하지 않고 다른 여행지와 비교한다. */
    public DestinationDuplicateCheck checkExcluding(DestinationDuplicateQuery query, Long excludedId) {
        return check(query, new Index(destinationMapper.findDuplicateIndex().stream()
                .filter(row -> !java.util.Objects.equals(excludedId, row.getDestinationId())).toList()), new RegionPaths());
    }

    /** 이름·위치 기준의 모든 후보. 자동 업데이트의 근거가 아니며 관리자 확정용으로만 사용한다. */
    public List<DestinationDuplicateCheck> findUpdateCandidates(DestinationDuplicateQuery query) {
        return findUpdateCandidatesAll(List.of(query)).get(0);
    }

    /** Request-scoped lookup: one index and region-path cache for the whole update file. */
    public List<List<DestinationDuplicateCheck>> findUpdateCandidatesAll(List<DestinationDuplicateQuery> queries) {
        if (queries == null || queries.isEmpty()) return List.of();
        return openUpdateLookup().findCandidates(queries);
    }

    /** A bounded preview owns this scope; no shared/global cache and no reuse during apply. */
    public UpdateLookup openUpdateLookup() {
        return new UpdateLookup(List.copyOf(destinationMapper.findDuplicateIndex()));
    }

    public final class UpdateLookup {
        private final List<DestinationDuplicateIndexRow> rows;
        private final Index index;
        private final RegionPaths regions = new RegionPaths();

        private UpdateLookup(List<DestinationDuplicateIndexRow> rows) {
            this.rows = rows;
            this.index = new Index(rows);
        }

        public List<List<DestinationDuplicateCheck>> findCandidates(List<DestinationDuplicateQuery> queries) {
            List<List<DestinationDuplicateCheck>> results = new ArrayList<>(queries.size());
            for (var query : queries) results.add(findUpdateCandidates(query, index, regions));
            return List.copyOf(results);
        }

        public DestinationDuplicateCheck checkExcluding(DestinationDuplicateQuery query, Long excludedId) {
            // Filtering the in-memory index preserves the original exclusion policy without another DB read.
            return check(query, new Index(rows.stream()
                    .filter(row -> !java.util.Objects.equals(excludedId, row.getDestinationId())).toList()), regions);
        }
    }

    private List<DestinationDuplicateCheck> findUpdateCandidates(DestinationDuplicateQuery query,
                                                                Index index, RegionPaths regions) {
        List<DestinationDuplicateCheck> candidates = new ArrayList<>();
        for (Entry entry : index.byNames(query.names())) {
            Integer distance = distanceMeters(query.latitude(), query.longitude(), entry.latitude, entry.longitude);
            if (distance != null && distance <= NEARBY_DISTANCE_METERS) {
                candidates.add(DestinationDuplicateCheck.possibleDuplicate(DestinationDuplicateReason.NAME_AND_NEARBY,
                        entry.id, entry.displayName(), distance));
            } else if ((distance == null || query.regionId() != null)
                    && regions.related(query.regionId(), entry.regionId)) {
                candidates.add(DestinationDuplicateCheck.possibleDuplicate(DestinationDuplicateReason.NAME_AND_REGION,
                        entry.id, entry.displayName(), distance));
            }
        }
        return List.copyOf(candidates);
    }

    /** 후보 여러 건을 한 번에 판별한다. 결과는 넘긴 순서와 같다. */
    public List<DestinationDuplicateCheck> checkAll(List<DestinationDuplicateQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        Index index = new Index(destinationMapper.findDuplicateIndex());
        RegionPaths regions = new RegionPaths();
        List<DestinationDuplicateCheck> results = new ArrayList<>(queries.size());
        for (DestinationDuplicateQuery query : queries) {
            results.add(query == null ? DestinationDuplicateCheck.NOT_REGISTERED : check(query, index, regions));
        }
        return List.copyOf(results);
    }

    /**
     * 같은 묶음(JSON 일괄등록 파일 등) 안의 후보끼리 기존 여행지와 똑같은 규칙으로 판별한다.
     * 각 후보는 자기보다 앞선 후보와만 비교하므로, 같은 곳이 두 번 나오면 뒤쪽만 중복으로 표시된다.
     *
     * <p>결과의 {@code destinationId} 는 여행지 번호가 아니라 같은 곳으로 본 앞 후보의 순번(0부터)이고,
     * {@code destinationName} 은 그 후보의 첫 이름이다. 후보가 null 이면 미등록으로 본다.</p>
     */
    public List<DestinationDuplicateCheck> checkWithinBatch(List<DestinationDuplicateQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        RegionPaths regions = new RegionPaths();
        List<DestinationDuplicateIndexRow> earlier = new ArrayList<>();
        List<DestinationDuplicateCheck> results = new ArrayList<>(queries.size());
        for (int position = 0; position < queries.size(); position++) {
            DestinationDuplicateQuery query = queries.get(position);
            if (query == null) {
                results.add(DestinationDuplicateCheck.NOT_REGISTERED);
                continue;
            }
            results.add(earlier.isEmpty() ? DestinationDuplicateCheck.NOT_REGISTERED
                    : check(query, new Index(earlier), regions));
            earlier.addAll(batchRows((long) position, query));
        }
        return List.copyOf(results);
    }

    /** 묶음 안의 후보 하나를 색인 줄로 바꾼다. 이름마다 한 줄이며, 첫 이름이 표시 이름이 된다. */
    private static List<DestinationDuplicateIndexRow> batchRows(Long position, DestinationDuplicateQuery query) {
        List<DestinationDuplicateIndexRow> rows = new ArrayList<>();
        List<String> names = query.names().isEmpty() ? Collections.singletonList(null) : query.names();
        for (String name : names) {
            DestinationDuplicateIndexRow row = new DestinationDuplicateIndexRow();
            row.setDestinationId(position);
            row.setRegionId(query.regionId());
            row.setLatitude(query.latitude());
            row.setLongitude(query.longitude());
            row.setSourceType(query.sourceType());
            row.setExternalContentId(query.externalContentId());
            row.setGooglePlaceId(query.googlePlaceId());
            row.setLanguageCode(KOREAN);
            row.setName(name);
            rows.add(row);
        }
        return rows;
    }

    private DestinationDuplicateCheck check(DestinationDuplicateQuery query, Index index, RegionPaths regions) {
        Entry sameExternal = index.byExternalId(query.sourceType(), query.externalContentId());
        if (sameExternal != null) {
            return DestinationDuplicateCheck.registered(DestinationDuplicateReason.EXTERNAL_CONTENT_ID,
                    sameExternal.id, sameExternal.displayName());
        }
        Entry samePlace = index.byGooglePlaceId(query.googlePlaceId());
        if (samePlace != null) {
            return DestinationDuplicateCheck.registered(DestinationDuplicateReason.GOOGLE_PLACE_ID,
                    samePlace.id, samePlace.displayName());
        }

        DestinationDuplicateCheck nearest = null;
        DestinationDuplicateCheck sameRegion = null;
        for (Entry entry : index.byNames(query.names())) {
            Integer distance = distanceMeters(query.latitude(), query.longitude(), entry.latitude, entry.longitude);
            if (distance != null) {
                // 양쪽 좌표가 모두 있으면 좌표로만 본다. 이름이 같아도 멀리 떨어져 있으면 다른 곳이다.
                if (distance <= NEARBY_DISTANCE_METERS
                        && (nearest == null || distance < nearest.distanceMeters())) {
                    nearest = DestinationDuplicateCheck.possibleDuplicate(DestinationDuplicateReason.NAME_AND_NEARBY,
                            entry.id, entry.displayName(), distance);
                }
            } else if (sameRegion == null && regions.related(query.regionId(), entry.regionId)) {
                sameRegion = DestinationDuplicateCheck.possibleDuplicate(DestinationDuplicateReason.NAME_AND_REGION,
                        entry.id, entry.displayName(), null);
            }
        }
        if (nearest != null) {
            return nearest;
        }
        return sameRegion != null ? sameRegion : DestinationDuplicateCheck.NOT_REGISTERED;
    }

    /** 두 좌표 사이 거리(m, 대권거리). 한쪽이라도 좌표가 없으면 null. */
    static Integer distanceMeters(BigDecimal latitude1, BigDecimal longitude1,
                                  BigDecimal latitude2, BigDecimal longitude2) {
        if (latitude1 == null || longitude1 == null || latitude2 == null || longitude2 == null) {
            return null;
        }
        double lat1 = Math.toRadians(latitude1.doubleValue());
        double lat2 = Math.toRadians(latitude2.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double meters = 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        return (int) Math.round(meters);
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 기존 여행지 한 곳. */
    private static final class Entry {
        private final Long id;
        private final Long regionId;
        private final BigDecimal latitude;
        private final BigDecimal longitude;
        private final Map<String, String> names = new LinkedHashMap<>();

        private Entry(DestinationDuplicateIndexRow row) {
            this.id = row.getDestinationId();
            this.regionId = row.getRegionId();
            this.latitude = row.getLatitude();
            this.longitude = row.getLongitude();
        }

        private String displayName() {
            String korean = names.get(KOREAN);
            if (korean != null) {
                return korean;
            }
            return names.values().stream().findFirst().orElse(null);
        }
    }

    /** 외부 ID·Place ID·정규화 이름으로 기존 여행지를 찾는 색인. */
    private static final class Index {
        private final Map<String, Entry> byExternalId = new HashMap<>();
        private final Map<String, Entry> byGooglePlaceId = new HashMap<>();
        private final Map<String, List<Entry>> byName = new HashMap<>();

        private Index(List<DestinationDuplicateIndexRow> rows) {
            Map<Long, Entry> entries = new LinkedHashMap<>();
            for (DestinationDuplicateIndexRow row : rows == null ? List.<DestinationDuplicateIndexRow>of() : rows) {
                if (row == null || row.getDestinationId() == null) {
                    continue;
                }
                Entry entry = entries.computeIfAbsent(row.getDestinationId(), id -> {
                    Entry created = new Entry(row);
                    String sourceType = trimmed(row.getSourceType());
                    String externalId = trimmed(row.getExternalContentId());
                    if (sourceType != null && externalId != null) {
                        byExternalId.putIfAbsent(externalKey(sourceType, externalId), created);
                    }
                    String placeId = trimmed(row.getGooglePlaceId());
                    if (placeId != null) {
                        byGooglePlaceId.putIfAbsent(placeId, created);
                    }
                    return created;
                });
                String name = trimmed(row.getName());
                if (name == null) {
                    continue;
                }
                entry.names.putIfAbsent(row.getLanguageCode() == null ? "" : row.getLanguageCode(), name);
                String normalized = DestinationNameNormalizer.normalize(name);
                if (normalized != null) {
                    List<Entry> sameName = byName.computeIfAbsent(normalized, key -> new ArrayList<>());
                    if (!sameName.contains(entry)) {
                        sameName.add(entry);
                    }
                }
            }
        }

        private Entry byExternalId(String sourceType, String externalContentId) {
            String type = trimmed(sourceType);
            String id = trimmed(externalContentId);
            return type == null || id == null ? null : byExternalId.get(externalKey(type, id));
        }

        private Entry byGooglePlaceId(String googlePlaceId) {
            String placeId = trimmed(googlePlaceId);
            return placeId == null ? null : byGooglePlaceId.get(placeId);
        }

        /** 이름 중 하나라도 정규화 결과가 같은 여행지. 여행지 번호 순서로 준다. */
        private Collection<Entry> byNames(List<String> names) {
            Map<Long, Entry> matched = new TreeMap<>();
            Set<String> seen = new HashSet<>();
            for (String name : names) {
                String normalized = DestinationNameNormalizer.normalize(name);
                if (normalized == null || !seen.add(normalized)) {
                    continue;
                }
                for (Entry entry : byName.getOrDefault(normalized, List.of())) {
                    matched.putIfAbsent(entry.id, entry);
                }
            }
            return matched.values();
        }

        private static String externalKey(String sourceType, String externalContentId) {
            return sourceType + '\u0000' + externalContentId;
        }
    }

    /** 한 번의 판별 안에서 지역 경로를 다시 읽지 않도록 기억한다. 지역 한 건 조회는 캐시를 탄다. */
    private final class RegionPaths {
        private final Map<Long, List<Long>> paths = new HashMap<>();

        /**
         * 같은 지역이거나 한쪽이 다른 쪽의 상위 지역이면 같은 지역으로 본다.
         * 다만 겹치는 지역이 최상위(대륙·대한민국)뿐이면 너무 넓어서 같은 지역으로 보지 않는다.
         */
        private boolean related(Long first, Long second) {
            if (first == null || second == null) {
                return false;
            }
            return path(first).indexOf(second) >= 1 || path(second).indexOf(first) >= 1;
        }

        /** 최상위 → 지역 순서의 ID 경로. 찾을 수 없으면 빈 목록. */
        private List<Long> path(Long regionId) {
            return paths.computeIfAbsent(regionId, id -> {
                List<Long> reversed = new ArrayList<>();
                Set<Long> visited = new HashSet<>();
                Long currentId = id;
                while (currentId != null && reversed.size() < MAX_REGION_DEPTH) {
                    if (!visited.add(currentId)) {
                        return List.of();
                    }
                    CountryCategory region = countryCategoryService.getById(currentId);
                    if (region == null) {
                        return List.of();
                    }
                    reversed.add(currentId);
                    currentId = region.getParentId();
                }
                List<Long> path = new ArrayList<>(reversed.size());
                for (int index = reversed.size() - 1; index >= 0; index--) {
                    path.add(reversed.get(index));
                }
                return List.copyOf(path);
            });
        }
    }
}
