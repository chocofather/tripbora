package com.tripbora.dto;

import lombok.Data;

/** course_countries의 표시 순서로 읽는 페이지 내 국가 이름. */
@Data
public class CourseCatalogCountryDto {
    private Long courseId;
    private String countryName;
}
