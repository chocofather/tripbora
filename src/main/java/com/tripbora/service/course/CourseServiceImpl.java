package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseCreateRequest;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseDestinationCountryDto;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseUpdateRequest;
import com.tripbora.dto.HomePopularCourseDto;
import com.tripbora.dto.HomePopularCourseStopDto;
import com.tripbora.model.Course;
import com.tripbora.model.CourseDestination;
import com.tripbora.model.DestinationTranslation;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.service.translation.TranslationVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CourseServiceImpl implements CourseService {

    private static final int HOME_POPULAR_COURSE_LIMIT = 3;
    private static final int HOME_COURSE_PREVIEW_STOP_LIMIT = 3;
    private static final String HOME_COURSE_FALLBACK_IMAGE_URL = "/images/default.png";

    private final CourseMapper courseMapper;
    private final PostContentSanitizer postContentSanitizer;
    /** STOP 이름만 여행지 번역에서 가져온다. 코스 글은 작성자가 쓴 그대로 둔다. */
    private final DestinationLocalizationService destinationLocalizationService;
    /** STOP 지역명은 지역 번역에서 가져온다. */
    private final ReferenceNameLocalizationService referenceNameLocalizationService;
    private final LocalContentLanguageDetector languageDetector;
    private final DestinationImageService destinationImageService;
    private final CourseStructureService courseStructureService;

    @Override
    @Transactional
    public CourseDetailDto getCourseDetail(Long courseId, Long currentUserId,
                                           SupportedLanguage requestedLanguage) {
        if (courseMapper.incrementViews(courseId) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행 코스를 찾을 수 없습니다.");
        }

        CourseDetailDto course = courseMapper.findCourseDetail(courseId, currentUserId);
        if (course == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행 코스를 찾을 수 없습니다.");
        }

        course.setContent(postContentSanitizer.sanitize(course.getContent()));
        applyTranslationMetadata(course, requestedLanguage);
        course.setStops(localizeStopNames(readTransitionStops(courseId), requestedLanguage));
        course.setMyCourse(currentUserId != null && Objects.equals(course.getUserId(), currentUserId));
        return course;
    }

    @Override
    @Transactional(readOnly = true)
    public CourseEditDto getCourseForEdit(Long courseId, Long userId,
                                          SupportedLanguage requestedLanguage) {
        Course course = requireOwnedActiveCourse(courseMapper.findActiveCourse(courseId), userId);

        CourseEditDto edit = new CourseEditDto();
        edit.setId(course.getId());
        edit.setCountryId(course.getCountryId());
        edit.setCountryName(course.getCountryName());
        edit.setTitle(course.getTitle());
        edit.setContent(postContentSanitizer.sanitize(course.getContent()));
        edit.setStops(localizeStopNames(readTransitionStops(courseId), requestedLanguage));
        return edit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HomePopularCourseDto> getPopularCoursesForHome(SupportedLanguage requestedLanguage) {
        List<HomePopularCourseDto> courses = courseMapper
                .findPopularCourses(HOME_POPULAR_COURSE_LIMIT)
                .stream()
                .limit(HOME_POPULAR_COURSE_LIMIT)
                .toList();
        if (courses.isEmpty()) {
            return courses;
        }

        // 경로 이름은 앞 3곳만 번역하고, 이미지는 뒤 STOP 까지 훑어 사용 가능한 3장을 고른다.
        Map<Long, List<HomePopularCourseStopDto>> previewStopsByCourseId = new LinkedHashMap<>();
        Map<Long, List<String>> previewImagesByCourseId = new LinkedHashMap<>();
        for (HomePopularCourseStopDto stop : courseMapper.findPopularCourseStops(
                courses.stream().map(HomePopularCourseDto::getCourseId).toList())) {
            List<HomePopularCourseStopDto> preview = previewStopsByCourseId.computeIfAbsent(
                    stop.getCourseId(), ignored -> new ArrayList<>());
            if (preview.size() < HOME_COURSE_PREVIEW_STOP_LIMIT) {
                preview.add(stop);
            }

            List<String> previewImages = previewImagesByCourseId.computeIfAbsent(
                    stop.getCourseId(), ignored -> new ArrayList<>());
            if (previewImages.size() < HOME_COURSE_PREVIEW_STOP_LIMIT
                    && stop.getImageUrl() != null
                    && !stop.getImageUrl().isBlank()) {
                previewImages.add(stop.getImageUrl());
            }
        }

        Map<Long, DestinationTranslation> localizedContent = resolveLocalizedContent(
                previewStopsByCourseId.values().stream()
                        .flatMap(List::stream)
                        .map(HomePopularCourseStopDto::getDestinationId),
                requestedLanguage);

        courses.forEach(course -> {
            course.setPreviewDestinationNames(
                    previewStopsByCourseId.getOrDefault(course.getCourseId(), List.of()).stream()
                            .map(stop -> localizedName(stop.getDestinationId(),
                                    stop.getDestinationName(), localizedContent))
                            .toList());
            List<String> previewImages = previewImagesByCourseId.getOrDefault(
                    course.getCourseId(), List.of());
            course.setPreviewImageUrls(previewImages.isEmpty()
                    ? List.of(HOME_COURSE_FALLBACK_IMAGE_URL)
                    : List.copyOf(previewImages));
        });
        return courses;
    }

    /**
     * STOP 의 여행지 이름과 지역명을 요청 언어로 바꾼다.
     * 여행지와 이어지지 않은 STOP 은 적힌 이름을 그대로 둔다.
     *
     * <p>번역은 STOP 마다 읽지 않는다. 여행지 번호와 지역 번호를 각각 모아 한 번씩만 읽는다.
     * 차례(visit_order)와 나머지 값은 손대지 않는다.
     */
    private List<CourseStopDto> localizeStopNames(List<CourseStopDto> stops,
                                                  SupportedLanguage requestedLanguage) {
        List<CourseStopDto> available = stops == null ? List.of() : stops;
        Map<Long, DestinationTranslation> localizedContent = resolveLocalizedContent(
                available.stream()
                        .filter(Objects::nonNull)
                        .map(CourseStopDto::getDestinationId),
                requestedLanguage);
        Map<Long, String> localizedRegionNames = resolveLocalizedRegionNames(
                available, requestedLanguage);
        for (CourseStopDto stop : available) {
            if (stop == null) {
                continue;
            }
            stop.setName(localizedName(stop.getDestinationId(), stop.getName(), localizedContent));
            if (stop.getRegionId() != null) {
                stop.setRegionName(localizedRegionNames.getOrDefault(
                        stop.getRegionId(), stop.getRegionName()));
            }
        }
        // 대표 이미지가 공공누리 제3유형(변경금지)이면 잘리지 않게 그린다.
        destinationImageService.markNoDerivatives(available, CourseStopDto::getImageUrl,
                CourseStopDto::setImageNoDerivatives);
        return available;
    }

    /** 코스에 나온 지역 번호를 모아 한 번에 번역한다. 번역이 없으면 원래 이름이 남는다. */
    private Map<Long, String> resolveLocalizedRegionNames(List<CourseStopDto> stops,
                                                          SupportedLanguage requestedLanguage) {
        Map<Long, String> baseRegionNames = new LinkedHashMap<>();
        for (CourseStopDto stop : stops) {
            if (stop != null && stop.getRegionId() != null) {
                baseRegionNames.putIfAbsent(stop.getRegionId(), stop.getRegionName());
            }
        }
        return referenceNameLocalizationService.localizeCountryCategoryNames(
                baseRegionNames, requestedLanguage);
    }

    private Map<Long, DestinationTranslation> resolveLocalizedContent(
            Stream<Long> destinationIds, SupportedLanguage requestedLanguage) {
        return destinationLocalizationService.resolveLocalizedContentByDestinationIds(
                destinationIds.filter(Objects::nonNull).distinct().toList(),
                requestedLanguage);
    }

    private String localizedName(Long destinationId,
                                 String baseName,
                                 Map<Long, DestinationTranslation> localizedContent) {
        if (destinationId == null) {
            return baseName;
        }
        DestinationTranslation content = localizedContent.get(destinationId);
        if (content == null || content.getName() == null || content.getName().isBlank()) {
            return baseName;
        }
        return content.getName();
    }

    @Override
    @Transactional
    public Long createCourse(CourseCreateRequest request, Long userId) {
        ValidatedCourse validated = validateBasic(request == null ? null : request.getTitle(),
                request == null ? null : request.getContent(),
                request == null ? null : request.getDestinationIds());
        Long requestedCountryId = validateRequestedCountryId(request == null ? null : request.getCountryId());
        validateDestinations(validated.destinationIds());
        Long countryId = validateCourseCountry(requestedCountryId, validated.destinationIds());

        Course course = new Course();
        course.setTitle(validated.title());
        course.setContent(validated.content());
        course.setTitleSourceLanguage(languageDetector.detect(validated.title()).code());
        course.setContentSourceLanguage(detectContentLanguage(validated.content()));
        course.setUserId(userId);
        course.setCountryId(countryId);
        course.setCourseKind("TRAVELER");
        course.setStatus("PUBLISHED");

        if (courseMapper.insertCourse(course) != 1 || course.getId() == null) {
            throw new IllegalStateException("여행 코스 저장에 실패했습니다.");
        }

        for (int index = 0; index < validated.destinationIds().size(); index++) {
            CourseDestination destination = new CourseDestination();
            destination.setCourseId(course.getId());
            destination.setDestinationId(validated.destinationIds().get(index));
            destination.setVisitOrder(index + 1);
            if (courseMapper.insertCourseDestination(destination) != 1) {
                throw new IllegalStateException("코스 여행지 저장에 실패했습니다.");
            }
        }
        courseStructureService.synchronizeDayOne(course.getId(), countryId, validated.destinationIds());
        return course.getId();
    }

    @Override
    @Transactional
    public void updateCourse(Long courseId, Long userId, CourseUpdateRequest request) {
        ValidatedCourse validated = validateBasic(request == null ? null : request.getTitle(),
                request == null ? null : request.getContent(),
                request == null ? null : request.getDestinationIds());

        Course current = requireOwnedActiveCourse(
                courseMapper.findActiveCourseForUpdate(courseId), userId);
        Long requestedCountryId = validateRequestedCountryId(request == null ? null : request.getCountryId());
        validateDestinations(validated.destinationIds());
        Long countryId = validateCourseCountry(requestedCountryId, validated.destinationIds());

        String titleLanguage = Objects.equals(current.getTitle(), validated.title())
                ? normalizeLanguage(current.getTitleSourceLanguage())
                : languageDetector.detect(validated.title()).code();
        String contentLanguage = Objects.equals(current.getContent(), validated.content())
                ? normalizeLanguage(current.getContentSourceLanguage())
                : detectContentLanguage(validated.content());
        if (courseMapper.updateCourse(courseId, userId, countryId,
                validated.title(), validated.content(), titleLanguage, contentLanguage) != 1) {
            throw new IllegalStateException("여행 코스 수정에 실패했습니다.");
        }

        courseMapper.deleteCourseDestinations(courseId);
        insertCourseDestinations(courseId, validated.destinationIds());
        courseStructureService.synchronizeDayOne(courseId, countryId, validated.destinationIds());
    }

    @Override
    @Transactional
    public void deleteCourse(Long courseId, Long userId) {
        requireOwnedActiveCourse(courseMapper.findActiveCourseForUpdate(courseId), userId);
        if (courseMapper.softDeleteCourse(courseId, userId) != 1) {
            throw new IllegalStateException("여행 코스 삭제에 실패했습니다.");
        }
    }

    /**
     * 그 여행지를 담고 있는 코스 번호들.
     *
     * <p>부르는 쪽(여행지 삭제)의 트랜잭션에 그대로 참여한다. 읽기 전용을 적어 두지만,
     * 이미 열려 있는 쓰기 트랜잭션에 합류할 때는 그 성질이 따로 적용되지 않는다.
     */
    @Override
    @Transactional(readOnly = true)
    public List<Long> getCourseIdsContainingDestination(Long destinationId) {
        if (destinationId == null) {
            return List.of();
        }
        List<Long> courseIds = courseMapper.findCourseIdsByDestinationId(destinationId);
        return courseIds == null ? List.of() : courseIds;
    }

    /**
     * STOP 번호를 1부터 빈칸 없이 다시 매긴다.
     *
     * <p>지금 보이는 차례(visit_order → 같으면 id)를 그대로 두고 번호만 메꾼다.
     * 그 차례는 화면이 STOP 목록을 읽는 기준과 같아서, 다시 매겨도 순서가 뒤바뀌지 않는다.
     *
     * <p>번호가 이미 맞는 STOP 은 건드리지 않는다. 여행지 하나가 지워졌을 때
     * 그 앞쪽 STOP 들은 대개 그대로라, 코스 전체를 다시 쓰지 않아도 된다.
     *
     * <p>부를 곳이 없으면 조용히 끝난다. 여기서 막을 잘못은 없다.
     */
    @Override
    @Transactional
    public void resequenceStops(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return;
        }
        // 같은 코스가 두 번 들어와도 한 번만 손본다.
        for (Long courseId : new LinkedHashSet<>(courseIds)) {
            if (courseId == null) {
                continue;
            }
            resequenceOneCourse(courseId);
        }
    }

    private void resequenceOneCourse(Long courseId) {
        List<CourseDestination> stops = courseMapper.findCourseStopOrders(courseId);
        if (stops == null || stops.isEmpty()) {
            // 남은 STOP 이 없는 코스. 매길 번호도 없다.
            return;
        }

        int visitOrder = 1;
        for (CourseDestination stop : stops) {
            if (!Objects.equals(stop.getVisitOrder(), visitOrder)
                    && courseMapper.updateCourseDestinationVisitOrder(
                            stop.getId(), visitOrder) != 1) {
                // 방금 읽은 줄이 사라졌다. 번호가 어긋난 채로 두지 않고 전부 되돌린다.
                throw new IllegalStateException("코스 여행지 순서 정리에 실패했습니다.");
            }
            visitOrder++;
        }
    }

    private List<CourseStopDto> readTransitionStops(Long courseId) {
        List<CourseStopDto> structured = courseStructureService.findDayOneStops(courseId);
        if (structured == null || structured.isEmpty()) {
            // Day 1 또는 Item 자체가 없는 transition 데이터에만 legacy를 사용한다.
            return courseMapper.findCourseStops(courseId);
        }
        // SET NULL된 snapshot은 보존하되, 기존 UI는 현재 등록된 여행지만 보여 준다.
        // snapshot만 남은 정상 구조를 legacy로 덮어쓰지 않는다.
        List<CourseStopDto> visible = new ArrayList<>();
        for (CourseStopDto stop : structured) {
            if (stop.getDestinationId() != null) {
                stop.setVisitOrder(visible.size() + 1);
                visible.add(stop);
            }
        }
        return visible;
    }

    private ValidatedCourse validateBasic(String requestedTitle, String content, List<Long> destinationIds) {
        String title = requestedTitle == null ? "" : requestedTitle.trim();
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "코스 제목을 입력해 주세요.");
        }
        if (title.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "코스 제목은 255자 이하로 입력해 주세요.");
        }

        String sanitizedContent = postContentSanitizer.sanitize(content);
        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(sanitizedContent);
        boolean hasText = !document.text().trim().isEmpty();
        boolean hasImage = !document.select("img[src]").isEmpty();
        if (!hasText && !hasImage) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "코스 소개를 입력해 주세요.");
        }

        if (destinationIds == null || destinationIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "여행지를 한 곳 이상 선택해 주세요.");
        }

        return new ValidatedCourse(title, sanitizedContent, new ArrayList<>(destinationIds));
    }

    private String detectContentLanguage(String sanitizedContent) {
        return languageDetector.detect(
                Jsoup.parseBodyFragment(sanitizedContent).text()).code();
    }

    private void applyTranslationMetadata(CourseDetailDto course,
                                          SupportedLanguage requestedLanguage) {
        String targetLanguage = (requestedLanguage == null
                ? SupportedLanguage.KOREAN : requestedLanguage).getLanguageTag();
        String titleLanguage = normalizeLanguage(course.getTitleSourceLanguage());
        String contentLanguage = normalizeLanguage(course.getContentSourceLanguage());
        course.setTitleSourceLanguage(titleLanguage);
        course.setContentSourceLanguage(contentLanguage);
        course.setTranslationAvailable(
                TranslationVisibility.shouldOffer(titleLanguage, targetLanguage)
                        || TranslationVisibility.shouldOffer(contentLanguage, targetLanguage));
    }

    private String normalizeLanguage(String language) {
        return language == null || language.isBlank() ? "und" : language;
    }

    private void validateDestinations(List<Long> destinationIds) {
        if (destinationIds.stream().anyMatch(id -> id == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 여행지가 포함되어 있습니다.");
        }
        if (new HashSet<>(destinationIds).size() != destinationIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 여행지는 한 번만 선택할 수 있습니다.");
        }
        if (courseMapper.countExistingDestinations(destinationIds) != destinationIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "존재하지 않는 여행지가 포함되어 있습니다.");
        }
    }

    private Long validateRequestedCountryId(Long countryId) {
        if (countryId == null || countryId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "코스 국가를 선택해 주세요.");
        }
        return countryId;
    }

    private Long validateCourseCountry(Long requestedCountryId, List<Long> destinationIds) {
        List<CourseDestinationCountryDto> destinations = courseMapper.findDestinationCountries(destinationIds);
        if (destinations.size() != destinationIds.size()
                || destinations.stream().anyMatch(destination -> destination.getCountryId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "국가 정보를 확인할 수 없는 여행지가 포함되어 있습니다.");
        }

        List<Long> countryIds = destinations.stream()
                .map(CourseDestinationCountryDto::getCountryId)
                .distinct()
                .toList();
        if (countryIds.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "하나의 여행 코스에는 같은 국가의 여행지만 추가할 수 있습니다.");
        }
        Long actualCountryId = countryIds.get(0);
        if (!Objects.equals(requestedCountryId, actualCountryId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "선택한 국가와 여행지의 국가가 일치하지 않습니다.");
        }
        return actualCountryId;
    }

    private Course requireOwnedActiveCourse(Course course, Long userId) {
        if (course == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "여행 코스를 찾을 수 없습니다.");
        }
        if (!Objects.equals(course.getUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "여행 코스 변경 권한이 없습니다.");
        }
        return course;
    }

    private void insertCourseDestinations(Long courseId, List<Long> destinationIds) {
        for (int index = 0; index < destinationIds.size(); index++) {
            CourseDestination destination = new CourseDestination();
            destination.setCourseId(courseId);
            destination.setDestinationId(destinationIds.get(index));
            destination.setVisitOrder(index + 1);
            if (courseMapper.insertCourseDestination(destination) != 1) {
                throw new IllegalStateException("코스 여행지 저장에 실패했습니다.");
            }
        }
    }

    private record ValidatedCourse(String title, String content, List<Long> destinationIds) {
    }
}
