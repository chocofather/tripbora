package com.tripbora.repository.course;

import com.tripbora.dto.CourseDestinationSnapshotDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.model.CourseCountry;
import com.tripbora.model.CourseDay;
import com.tripbora.model.CourseItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CourseStructureMapper {
    CourseDay findDayOne(@Param("courseId") Long courseId);
    int insertDay(CourseDay day);
    int deleteDayItems(@Param("courseDayId") Long courseDayId);
    int insertItem(CourseItem item);
    int deleteCountries(@Param("courseId") Long courseId);
    int insertCountry(CourseCountry country);
    List<CourseDestinationSnapshotDto> findDestinationSnapshots(
            @Param("destinationIds") List<Long> destinationIds);
    List<CourseStopDto> findDayOneStops(@Param("courseId") Long courseId);
}
