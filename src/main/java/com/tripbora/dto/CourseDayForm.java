package com.tripbora.dto;

import lombok.Data;
import java.util.List;

@Data
public class CourseDayForm {
    private Long id;
    private Integer dayNumber;
    private String title;
    private List<CourseItemForm> items;
}
