package com.tripbora.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class CourseDayDto {
    private Long id;
    private Integer dayNumber;
    private String title;
    private List<CourseStopDto> items = new ArrayList<>();
}
