package com.tripbora.repository.course;

import com.tripbora.dto.CourseThemeDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CourseThemeMapper {
    List<CourseThemeDto> findActiveThemes();
    List<CourseThemeDto> findByCourseIds(@Param("courseIds") List<Long> courseIds);
    int deleteMappings(@Param("courseId") Long courseId);
    int insertMapping(@Param("courseId") Long courseId, @Param("themeId") Long themeId,
                      @Param("displayOrder") int displayOrder);
}
