package com.tripbora.dto;

import lombok.Data;

/** Theme master의 표시값. courseId/displayOrder는 mapping 일괄 조회 시 함께 사용한다. */
@Data
public class CourseThemeDto {
    private Long id;
    private String code;
    private String name;
    private Long courseId;
    private Integer displayOrder;
}
