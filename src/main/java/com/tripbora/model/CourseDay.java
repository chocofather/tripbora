package com.tripbora.model;

import lombok.Data;
import java.sql.Timestamp;

@Data
public class CourseDay {
    private Long id;
    private Long courseId;
    private Integer dayNumber;
    private String title;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
