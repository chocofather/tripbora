package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseCreateRequest;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseUpdateRequest;
import com.tripbora.dto.HomePopularCourseDto;

import java.util.List;

public interface CourseService {

    /** STOP 이름은 요청 언어로 바꿔 담는다. 코스 제목·소개·작성자는 원문 그대로 둔다. */
    CourseDetailDto getCourseDetail(Long courseId, Long currentUserId,
                                    SupportedLanguage requestedLanguage);

    CourseEditDto getCourseForEdit(Long courseId, Long userId,
                                   SupportedLanguage requestedLanguage);

    List<HomePopularCourseDto> getPopularCoursesForHome(SupportedLanguage requestedLanguage);

    Long createCourse(CourseCreateRequest request, Long userId);

    void updateCourse(Long courseId, Long userId, CourseUpdateRequest request);

    void deleteCourse(Long courseId, Long userId);

    CourseEditDto prepareFormForRetry(String title, String content, Long countryId,
                                     List<com.tripbora.dto.CourseDayForm> days);

    /**
     * 그 여행지를 담고 있는 코스 번호들.
     *
     * <p>여행지를 지우면 연결 행도 FK CASCADE 로 함께 사라져 어느 코스였는지 알 수 없다.
     * 지우기 전에 이 값을 받아 두었다가, 지운 뒤 {@link #resequenceStops(List)} 에 넘긴다.
     */
    List<Long> getCourseIdsContainingDestination(Long destinationId);

    /**
     * 코스의 STOP 번호를 1부터 빈칸 없이 다시 매긴다.
     *
     * <p>여행지가 지워지면 그 여행지를 담고 있던 연결 행도 FK CASCADE 로 함께 사라지는데,
     * 남은 STOP 들은 예전 번호를 그대로 들고 있어 화면에 "STOP 2" 하나만 남는 일이 생긴다.
     * 보고 있던 차례는 그대로 두고 번호만 1, 2, 3 … 으로 메꾼다.
     *
     * <p>코스 작성·수정은 지금도 늘 1부터 다시 넣으므로 여기를 거치지 않는다.
     * 이 메서드는 코스 밖에서 STOP 이 사라졌을 때를 위한 것이다.
     */
    void resequenceStops(List<Long> courseIds);

}
