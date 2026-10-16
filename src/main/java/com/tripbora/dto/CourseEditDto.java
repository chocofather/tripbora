package com.tripbora.dto;

import lombok.Data;

import java.util.List;

@Data
public class CourseEditDto {
    private String coverImageUrl;
    private boolean removeCoverImage;
    private Long id;
    private Long countryId;
    private String countryName;
    private String title;
    private String content;
    private List<CourseStopDto> stops;
    private List<CourseDayDto> days;
    private List<String> countryNames;

    public String getCountryLabel() {
        return CourseCountryLabel.of(countryNames);
    }
}
