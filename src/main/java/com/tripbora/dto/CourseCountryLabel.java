package com.tripbora.dto;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 포함 국가의 저장 순서를 보존하는 공통 View 표현. */
public final class CourseCountryLabel {
    private CourseCountryLabel() {}

    public static String of(List<String> countryNames) {
        return countryNames == null ? "" : countryNames.stream().filter(Objects::nonNull)
                .map(String::strip).filter(name -> !name.isEmpty()).distinct().collect(Collectors.joining(" · "));
    }
}
