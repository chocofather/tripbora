package com.tripbora.repository.course;

import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.OfficialCourseListDto;
import com.tripbora.dto.CourseDestinationCountryDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.HomePopularCourseDto;
import com.tripbora.dto.HomePopularCourseStopDto;
import com.tripbora.model.Course;
import com.tripbora.model.CourseDestination;
import com.tripbora.model.translation.CourseLanguageBackfillRow;
import com.tripbora.model.translation.CourseTranslationSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseMapper {

    List<OfficialCourseListDto> findOfficialCourses(@Param("keyword") String keyword,
            @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);
    int countOfficialCourses(@Param("keyword") String keyword, @Param("status") String status);
    Course findOfficialCourse(@Param("courseId") Long courseId);
    Course findOfficialCourseForUpdate(@Param("courseId") Long courseId);
    CourseDetailDto findOfficialCourseDetail(@Param("courseId") Long courseId,
                                            @Param("currentUserId") Long currentUserId);
    int updateOfficialCourse(Course course);

    int incrementViews(@Param("courseId") Long courseId);

    CourseDetailDto findCourseDetail(@Param("courseId") Long courseId,
                                     @Param("currentUserId") Long currentUserId);

    Course findActiveCourse(@Param("courseId") Long courseId);

    Course findActiveCourseForUpdate(@Param("courseId") Long courseId);

    CourseTranslationSource findVisibleTranslationSource(@Param("courseId") Long courseId);

    CourseTranslationSource findVisibleTranslationSourceForUpdate(@Param("courseId") Long courseId);

    int correctTitleSourceLanguage(@Param("courseId") Long courseId,
                                   @Param("sourceLanguage") String sourceLanguage,
                                   @Param("updatedAt") java.sql.Timestamp updatedAt);

    int correctContentSourceLanguage(@Param("courseId") Long courseId,
                                     @Param("sourceLanguage") String sourceLanguage,
                                     @Param("updatedAt") java.sql.Timestamp updatedAt);

    List<CourseLanguageBackfillRow> findUndeterminedCourseLanguagesAfter(
            @Param("afterId") Long afterId,
            @Param("limit") int limit);

    int updateCourseTitleLanguageIfUndetermined(@Param("courseId") Long courseId,
                                                @Param("sourceLanguage") String sourceLanguage,
                                                @Param("title") String title,
                                                @Param("updatedAt") java.sql.Timestamp updatedAt);

    int updateCourseContentLanguageIfUndetermined(@Param("courseId") Long courseId,
                                                  @Param("sourceLanguage") String sourceLanguage,
                                                  @Param("content") String content,
                                                  @Param("updatedAt") java.sql.Timestamp updatedAt);

    List<CourseStopDto> findCourseStops(@Param("courseId") Long courseId);

    List<HomePopularCourseDto> findPopularCourses(@Param("limit") int limit);

    List<HomePopularCourseStopDto> findPopularCourseStops(
            @Param("courseIds") List<Long> courseIds);

    int countExistingDestinations(@Param("destinationIds") List<Long> destinationIds);

    List<CourseDestinationCountryDto> findDestinationCountries(
            @Param("destinationIds") List<Long> destinationIds);

    // 코스 등록
    int insertCourse(Course course);

    int updateCoverImage(@Param("courseId") Long courseId, @Param("userId") Long userId,
                        @Param("coverImageUrl") String coverImageUrl);

    // 코스-여행지 연결 등록
    int insertCourseDestination(CourseDestination courseDestination);

    int updateCourse(@Param("courseId") Long courseId,
                     @Param("userId") Long userId,
                     @Param("countryId") Long countryId,
                     @Param("title") String title,
                     @Param("content") String content,
                     @Param("titleSourceLanguage") String titleSourceLanguage,
                     @Param("contentSourceLanguage") String contentSourceLanguage);

    int deleteCourseDestinations(@Param("courseId") Long courseId);

    /**
     * 그 여행지를 담고 있는 코스 번호들.
     *
     * <p>여행지가 사라지면 연결 행도 FK CASCADE 로 함께 사라져 어느 코스였는지 알 수 없다.
     * 그래서 지우기 전에 여기서 미리 받아 둔다. 같은 코스에 두 번 담겨 있어도 한 번만 온다.
     */
    List<Long> findCourseIdsByDestinationId(@Param("destinationId") Long destinationId);

    /**
     * 그 코스에 남아 있는 STOP 들. 지금 화면에 보이는 순서 그대로 온다.
     *
     * <p>다시 번호를 매기는 데 필요한 것은 행 번호와 지금 방문 순서뿐이라
     * 그 둘만 읽는다(나머지 칸은 비어서 온다).
     */
    List<CourseDestination> findCourseStopOrders(@Param("courseId") Long courseId);

    /** STOP 하나의 방문 순서를 옮긴다. */
    int updateCourseDestinationVisitOrder(@Param("id") Long id,
                                          @Param("visitOrder") int visitOrder);

    int softDeleteCourse(@Param("courseId") Long courseId,
                         @Param("userId") Long userId);
}
