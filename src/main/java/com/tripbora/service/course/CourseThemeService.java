package com.tripbora.service.course;

import com.tripbora.dto.CourseThemeDto;
import com.tripbora.repository.course.CourseThemeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class CourseThemeService {
    private final CourseThemeMapper mapper;

    public List<CourseThemeDto> findActiveThemes() {
        return mapper.findActiveThemes();
    }

    public List<Long> validate(List<Long> themeIds) {
        if (themeIds == null || themeIds.isEmpty()) return List.of();
        if (themeIds.size() > 3 || themeIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(themeIds).size() != themeIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "테마는 중복 없이 최대 3개까지 선택해 주세요.");
        }
        var activeIds = findActiveThemes().stream().map(CourseThemeDto::getId).toList();
        if (!activeIds.containsAll(themeIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택할 수 없는 테마가 포함되어 있습니다.");
        }
        return List.copyOf(themeIds);
    }

    /** 기존 Course 쓰기 transaction에 참여한다. 수정 시 Course row lock도 유지된다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceThemes(Long courseId, List<Long> themeIds) {
        List<Long> ordered = validate(themeIds);
        mapper.deleteMappings(courseId);
        for (int index = 0; index < ordered.size(); index++) {
            if (mapper.insertMapping(courseId, ordered.get(index), index + 1) != 1) {
                throw new IllegalStateException("코스 테마 저장에 실패했습니다.");
            }
        }
    }

    public Map<Long, List<CourseThemeDto>> findByCourseIds(List<Long> courseIds) {
        Map<Long, List<CourseThemeDto>> result = new LinkedHashMap<>();
        if (courseIds.isEmpty()) return result;
        for (CourseThemeDto theme : mapper.findByCourseIds(courseIds.stream().distinct().toList())) {
            result.computeIfAbsent(theme.getCourseId(), ignored -> new ArrayList<>()).add(theme);
        }
        return result;
    }
}
