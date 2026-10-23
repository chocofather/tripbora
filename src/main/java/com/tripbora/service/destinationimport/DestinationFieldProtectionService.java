package com.tripbora.service.destinationimport;

import com.tripbora.repository.destination.DestinationFieldProtectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** JSON 보강 필드를 외부 원본 덮어쓰기로부터 보호한다. 필드 경로는 펼친 JSON 경로다. */
@Service
@RequiredArgsConstructor
public class DestinationFieldProtectionService {

    private static final int MAX_FIELD_PATH_LENGTH = 180;
    private static final Pattern FIELD_PATH = Pattern.compile(
            "[A-Za-z][A-Za-z0-9-]*(\\.[A-Za-z][A-Za-z0-9-]*)*");

    private final DestinationFieldProtectionMapper mapper;

    public Set<String> protectedFields(Long destinationId) {
        requireDestinationId(destinationId);
        return new LinkedHashSet<>(mapper.findFieldPaths(destinationId));
    }

    /** One read for the bounded preview file, including empty sets for unprotected targets. */
    public Map<Long, Set<String>> protectedFieldsAll(Collection<Long> destinationIds) {
        Objects.requireNonNull(destinationIds, "destinationIds");
        if (destinationIds.size() > 30) throw new IllegalArgumentException("한 번에 최대 30개 여행지만 조회할 수 있습니다.");
        Map<Long, Set<String>> fields = new LinkedHashMap<>();
        for (Long id : destinationIds) {
            requireDestinationId(id);
            fields.putIfAbsent(id, new LinkedHashSet<>());
        }
        if (fields.isEmpty()) return fields;
        for (var row : mapper.findProtectedFieldsBatch(fields.keySet())) {
            Long id = ((Number) row.get("destination_id")).longValue();
            if (fields.containsKey(id)) fields.get(id).add((String) row.get("field_path"));
        }
        return fields;
    }

    /** 보강 저장과 같은 트랜잭션에서만 호출한다. 기존 보호 및 최초 기록은 유지한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void protect(Long destinationId, Collection<String> fieldPaths, Long userId) {
        requireDestinationId(destinationId);
        Objects.requireNonNull(fieldPaths, "fieldPaths");
        Set<String> uniquePaths = new LinkedHashSet<>(fieldPaths);
        uniquePaths.forEach(this::requireFieldPath);
        for (String fieldPath : uniquePaths) {
            mapper.insert(destinationId, fieldPath, userId);
        }
    }

    /**
     * 향후 KTO 갱신은 저장 전에 이 검사를 거친다. 중첩 객체가 아닌 정확한 펼친 필드 경로만 받는다.
     * 현재 값의 빈 문자열/null/false 여부와 관계없이 보호 경로를 제외한다.
     */
    public Map<String, Object> filterKtoPatch(Long destinationId, Map<String, Object> proposed) {
        Objects.requireNonNull(proposed, "proposed");
        proposed.keySet().forEach(this::requireFieldPath);
        if (proposed.values().stream().anyMatch(value -> value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("KTO 변경값은 중첩 객체 대신 펼친 필드 경로로 전달해야 합니다.");
        }
        Set<String> protectedPaths = protectedFields(destinationId);
        Map<String, Object> filtered = new LinkedHashMap<>();
        proposed.forEach((path, value) -> {
            if (!protectedPaths.contains(path)) {
                filtered.put(path, value);
            }
        });
        return filtered;
    }

    private void requireDestinationId(Long destinationId) {
        if (destinationId == null || destinationId <= 0) {
            throw new IllegalArgumentException("여행지 ID가 올바르지 않습니다.");
        }
    }

    private void requireFieldPath(String fieldPath) {
        if (fieldPath == null || fieldPath.length() > MAX_FIELD_PATH_LENGTH
                || !FIELD_PATH.matcher(fieldPath).matches()) {
            throw new IllegalArgumentException("보호 필드 경로가 올바르지 않습니다.");
        }
    }
}
