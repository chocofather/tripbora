package com.tripbora.model;

import lombok.Data;

@Data
public class CourseCountry {
    private Long courseId;
    private Long countryId;
    private Integer displayOrder;
}
