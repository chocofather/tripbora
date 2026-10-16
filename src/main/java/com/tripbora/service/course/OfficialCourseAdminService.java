package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.*;
import com.tripbora.model.Course;
import com.tripbora.repository.course.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

/** 공식 코스 관리 전용. 권한 검사는 /admin 경계에서, 종류/삭제 여부 검사는 이 서비스와 SQL에서 한다. */
@Service
@RequiredArgsConstructor
public class OfficialCourseAdminService {
    public static final int PAGE_SIZE = 20;
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "HIDDEN");

    private final CourseMapper mapper;
    private final CourseServiceImpl courses;
    private final CourseStructureService structure;
    private final CourseThemeService themes;
    private final CourseCoverStorageService covers;

    @Transactional(readOnly = true)
    public List<OfficialCourseListDto> list(String keyword, String status, int page) {
        int offset = (int) Math.min(Integer.MAX_VALUE, (long) (Math.max(1, page) - 1) * PAGE_SIZE);
        List<OfficialCourseListDto> rows = mapper.findOfficialCourses(keywordPattern(keyword), filterStatus(status), PAGE_SIZE, offset);
        var byCourse = themes.findByCourseIds(rows.stream().map(OfficialCourseListDto::getId).toList());
        for (OfficialCourseListDto row : rows) {
            row.setCountryNames(courses.readCountryNames(row.getId(), SupportedLanguage.KOREAN));
            row.setThemes(byCourse.getOrDefault(row.getId(), List.of()));
            if (row.getRepresentativeImageUrl() == null || row.getRepresentativeImageUrl().isBlank()) {
                row.setRepresentativeImageUrl("/images/default.png");
            }
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public int count(String keyword, String status) {
        return mapper.countOfficialCourses(keywordPattern(keyword), filterStatus(status));
    }

    @Transactional
    public Long create(OfficialCourseForm form, Long adminId) {
        requireAdminId(adminId);
        Course course = prepare(form);
        course.setUserId(adminId);
        if (hasCover(form)) course.setCoverImageUrl(covers.save(form.getCoverImage(), adminId));
        if (mapper.insertCourse(course) != 1 || course.getId() == null) {
            throw new IllegalStateException("공식 코스 저장에 실패했습니다.");
        }
        structure.replaceSchedule(course.getId(), course.getCountryId(), form.getDays());
        themes.replaceThemes(course.getId(), form.getThemeIds());
        return course.getId();
    }

    @Transactional
    public void update(Long id, OfficialCourseForm form, Long adminId) {
        requireAdminId(adminId);
        Course current = requireOfficial(mapper.findOfficialCourseForUpdate(id));
        Course course = prepare(form);
        course.setId(id);
        course.setUserId(current.getUserId());
        course.setCoverImageUrl(current.getCoverImageUrl());
        boolean coverChanged = hasCover(form) || form.isRemoveCoverImage();
        if (coverChanged) course.setCoverImageUrl(hasCover(form) ? covers.save(form.getCoverImage(), current.getUserId()) : null);
        if (mapper.updateOfficialCourse(course) != 1) {
            throw new IllegalStateException("공식 코스 수정에 실패했습니다.");
        }
        structure.replaceSchedule(id, course.getCountryId(), form.getDays());
        themes.replaceThemes(id, form.getThemeIds());
        if (coverChanged) covers.removeAfterCommit(current.getCoverImageUrl(), current.getUserId());
    }

    @Transactional(readOnly = true)
    public CourseEditDto getForEdit(Long id) {
        Course current = requireOfficial(mapper.findOfficialCourse(id));
        return courses.prepareEdit(current, SupportedLanguage.KOREAN);
    }

    @Transactional(readOnly = true)
    public CourseDetailDto preview(Long id, Long adminId) {
        requireAdminId(adminId);
        CourseDetailDto detail = mapper.findOfficialCourseDetail(id, adminId);
        if (detail == null || !"OFFICIAL".equals(detail.getCourseKind())) throw missing();
        return courses.prepareDetail(id, detail, adminId, SupportedLanguage.KOREAN);
    }

    private Course prepare(OfficialCourseForm form) {
        if (form == null) throw invalid("공식 코스 입력값을 확인해 주세요.");
        String status = form.getStatus();
        if (status == null || !STATUSES.contains(status)) throw invalid("유효한 공개 상태를 선택해 주세요.");
        String summary = form.getSummary() == null ? "" : form.getSummary().trim();
        if (summary.length() > 300) throw invalid("코스 요약은 300자 이하로 입력해 주세요.");
        Course course = new Course();
        course.setCourseKind("OFFICIAL");
        course.setStatus(status);
        course.setTitle(courses.validateTitle(form.getTitle()));
        course.setSummary(summary);
        course.setCountryId(courses.validateScheduleAndCountries(form.getDays()));
        themes.validate(form.getThemeIds());
        course.setTitleSourceLanguage("ko");
        course.setContentSourceLanguage("ko");
        course.setContent(summary.isEmpty() ? "" : "<p>" + org.jsoup.nodes.Entities.escape(summary) + "</p>");
        return course;
    }

    private boolean hasCover(OfficialCourseForm form) { return form.getCoverImage() != null && !form.getCoverImage().isEmpty(); }
    private Course requireOfficial(Course course) {
        if (course == null || !"OFFICIAL".equals(course.getCourseKind()) || Boolean.TRUE.equals(course.getDeleted())) throw missing();
        return course;
    }
    private void requireAdminId(Long adminId) {
        if (adminId == null || adminId <= 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 확인이 필요합니다.");
    }
    private String filterStatus(String status) {
        if (status == null || status.isBlank()) return null;
        if (!STATUSES.contains(status)) throw invalid("유효한 공개 상태를 선택해 주세요.");
        return status;
    }
    private String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        return keyword.trim().replace("=", "==").replace("%", "=%").replace("_", "=_");
    }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "공식 코스를 찾을 수 없습니다."); }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
