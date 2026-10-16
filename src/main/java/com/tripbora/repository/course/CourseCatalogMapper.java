package com.tripbora.repository.course;

import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogCountryDto;
import com.tripbora.dto.CourseCatalogRegionDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseCatalogMapper {
    int countCourses(@Param("kind") String kind, @Param("countryId") Long countryId,
                     @Param("themeId") Long themeId);

    List<CourseCardDto> findCourses(@Param("kind") String kind, @Param("countryId") Long countryId,
                                   @Param("themeId") Long themeId, @Param("sort") String sort,
                                   @Param("limit") int limit, @Param("offset") int offset);

    List<CourseCatalogCountryDto> findCountriesByCourseIds(@Param("courseIds") List<Long> courseIds);

    List<CourseCatalogRegionDto> findRegionsByCourseIds(@Param("courseIds") List<Long> courseIds);
}
