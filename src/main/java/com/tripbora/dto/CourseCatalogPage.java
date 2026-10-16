package com.tripbora.dto;

import lombok.Value;

import java.util.List;

/** 1부터 시작하는 페이지와 실제 조회에 사용한 필터. */
@Value
public class CourseCatalogPage {
    List<CourseCardDto> courses;
    int totalCount;
    int currentPage;
    int totalPages;
    String kind;
    Long countryId;
    Long themeId;
    String sort;
}
