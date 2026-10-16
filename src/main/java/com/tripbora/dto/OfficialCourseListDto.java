package com.tripbora.dto;

import lombok.Data;
import java.sql.Timestamp;
import java.util.List;

@Data
public class OfficialCourseListDto {
    private Long id;
    private String title;
    private String coverImageUrl;
    private String representativeImageUrl;
    private List<String> countryNames = List.of();
    private List<CourseThemeDto> themes = List.of();
    private int dayCount;
    private String status;
    private Timestamp createdAt;

    public String getCountryLabel() { return CourseCountryLabel.of(countryNames); }
}
